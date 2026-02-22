package com.btcminer.android.mining

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.BatteryManager
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.btcminer.android.AppLog
import com.btcminer.android.MainActivity
import com.btcminer.android.R
import com.btcminer.android.config.MiningConfig
import com.btcminer.android.config.MiningConfigRepository
import com.btcminer.android.network.StratumPinCapture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class MiningForegroundService : Service() {

    private lateinit var configRepository: MiningConfigRepository
    private lateinit var statsRepository: MiningStatsRepository
    private lateinit var pendingSharesRepository: PendingSharesRepository
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockRefCount = 0
    private val wakeLockLock = Any()
    private var alarmPendingIntent: PendingIntent? = null
    private val throttleStateRef = AtomicReference(ThrottleState(100, false))
    private val engine: MiningEngine by lazy {
        val e = NativeMiningEngine(
            throttleStateRef,
            onPoolRedirectRequested = { host, port -> handler.post { showPoolRedirectNotification(host, port) } },
            getStratumPin = configRepository::getStratumPin,
            onPinVerified = { handler.post { Toast.makeText(applicationContext, R.string.security_confirmed_pool_cert_verified, Toast.LENGTH_SHORT).show() } },
            pendingSharesRepository = pendingSharesRepository,
            isBothWifiAndDataUnavailable = { MiningConstraints.isBothWifiAndDataUnavailable(applicationContext) },
        )
        e.loadPersistedStats(statsRepository.get())
        e
    }
    private var constraintReceiver: BroadcastReceiver? = null
    @Volatile
    private var lastBatteryThrottleActive = false
    @Volatile
    private var lastHashrateThrottleActive = false
    private var miningStartTimeMillis: Long? = null

    /** Current throttle sleep (ms) when auto-tuning is ON. 0–60_000. */
    @Volatile
    private var autoTuningThrottleSleepMs: Long = 0L
    /** Last adjustment direction for dashboard: 0 = NONE, 1 = DECREASING, 2 = INCREASING */
    @Volatile
    private var autoTuningDirection: Int = 0

    private val throttleThreadRunning = AtomicBoolean(false)
    private var throttleThread: Thread? = null

    private val handler = Handler(Looper.getMainLooper())
    private val hashrateHistoryCpu = mutableListOf<Double>()
    private val hashrateHistoryGpu = mutableListOf<Double>()
    private val maxHistorySize = 120
    /** UI-only: hashrate history and notification. Throttle is updated by [throttleThread]. */
    private val sampleRunnable = object : Runnable {
        override fun run() {
            if (engine.isRunning()) {
                val status = engine.getStatus()
                if (status.state == MiningStatus.State.Mining) {
                    synchronized(hashrateHistoryCpu) {
                        hashrateHistoryCpu.add(status.hashrateHs)
                        while (hashrateHistoryCpu.size > maxHistorySize) hashrateHistoryCpu.removeAt(0)
                    }
                    synchronized(hashrateHistoryGpu) {
                        hashrateHistoryGpu.add(status.gpuHashrateHs)
                        while (hashrateHistoryGpu.size > maxHistorySize) hashrateHistoryGpu.removeAt(0)
                    }
                }
                startForeground(NOTIFICATION_ID, createNotification())
            }
            handler.postDelayed(this, 1000L)
        }
    }

    /** Runs on a dedicated thread; updates throttle every second so it is not deferred during Doze. */
    private fun runThrottleLoop() {
        while (throttleThreadRunning.get() && engine.isRunning()) {
            val config = configRepository.getConfig()
            val status = engine.getStatus()
            val tempTenthsC = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            val tempC = tempTenthsC / 10.0
            val totalHs = status.hashrateHs + status.gpuHashrateHs

            val stopDueToOverheat = tempTenthsC != 0 && tempC >= MiningConfig.BATTERY_TEMP_HARD_STOP_C
            val resumeTempC = config.maxBatteryTempC * 0.9
            lastBatteryThrottleActive = when {
                tempTenthsC == 0 -> lastBatteryThrottleActive
                tempC >= config.maxBatteryTempC -> true
                tempC <= resumeTempC -> false
                else -> lastBatteryThrottleActive
            }
            val hashrateTarget = config.hashrateTargetHps
            lastHashrateThrottleActive = when {
                hashrateTarget == null -> false
                totalHs > hashrateTarget -> true
                totalHs <= hashrateTarget * 0.9 -> false
                else -> lastHashrateThrottleActive
            }
            if (stopDueToOverheat) {
                handler.post {
                    releaseWakeLock()
                    setOverheatBannerFlag(true)
                    showOverheatNotification()
                    engine.stop()
                    Toast.makeText(applicationContext, R.string.battery_too_hot_toast, Toast.LENGTH_LONG).show()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return
            }
            val throttleSleepMs = if (config.autoTuningByBatteryTemp) {
                val targetLo = config.maxBatteryTempC * AUTO_TUNING_TARGET_LO_RATIO
                val targetHi = config.maxBatteryTempC * AUTO_TUNING_TARGET_HI_RATIO
                if (tempTenthsC != 0) {
                    when {
                        tempC >= config.maxBatteryTempC -> {
                            autoTuningThrottleSleepMs = THROTTLE_SLEEP_MS
                            autoTuningDirection = AUTO_TUNING_DIRECTION_NONE
                            lastBatteryThrottleActive = true
                        }
                        tempC < targetLo -> {
                            autoTuningThrottleSleepMs = (autoTuningThrottleSleepMs - AUTO_TUNING_STEP_DOWN_MS).coerceAtLeast(0L)
                            autoTuningDirection = AUTO_TUNING_DIRECTION_DECREASING
                            lastBatteryThrottleActive = false
                        }
                        tempC in targetLo..targetHi -> {
                            autoTuningDirection = AUTO_TUNING_DIRECTION_NONE
                            lastBatteryThrottleActive = false
                        }
                        else -> {
                            autoTuningThrottleSleepMs = (autoTuningThrottleSleepMs + AUTO_TUNING_STEP_UP_MS).coerceIn(0L, AUTO_TUNING_SLEEP_MAX)
                            autoTuningDirection = AUTO_TUNING_DIRECTION_INCREASING
                            lastBatteryThrottleActive = true
                        }
                    }
                }
                val batterySleep = if (tempTenthsC != 0 && tempC >= config.maxBatteryTempC) THROTTLE_SLEEP_MS else autoTuningThrottleSleepMs
                if (lastHashrateThrottleActive) maxOf(THROTTLE_SLEEP_MS, batterySleep) else batterySleep
            } else {
                lastBatteryThrottleActive = when {
                    tempTenthsC == 0 -> lastBatteryThrottleActive
                    tempC >= config.maxBatteryTempC -> true
                    tempC <= config.maxBatteryTempC * 0.9 -> false
                    else -> lastBatteryThrottleActive
                }
                if (lastBatteryThrottleActive || lastHashrateThrottleActive) THROTTLE_SLEEP_MS else 0L
            }
            throttleStateRef.set(ThrottleState(config.maxIntensityPercent, false, throttleSleepMs, config.gpuUtilizationPercent))
            try {
                Thread.sleep(1000L)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private val saveStatsRunnable = object : Runnable {
        override fun run() {
            statsRepository.save(engine.getStatus())
            handler.postDelayed(this, STATS_SAVE_INTERVAL_MS)
        }
    }

    inner class LocalBinder : android.os.Binder() {
        fun getService(): MiningForegroundService = this@MiningForegroundService
    }

    fun getStatus(): MiningStatus = engine.getStatus()

    /** Reset persistent UI counters (accepted/rejected/identified shares, block templates, best difficulty, nonces). */
    fun resetAllCounters() {
        engine.resetAllCounters()
        statsRepository.saveZeros()
    }

    fun getHashrateHistoryCpu(): List<Double> = synchronized(hashrateHistoryCpu) { hashrateHistoryCpu.toList() }
    fun getHashrateHistoryGpu(): List<Double> = synchronized(hashrateHistoryGpu) { hashrateHistoryGpu.toList() }

    fun isBatteryThrottleActive(): Boolean = lastBatteryThrottleActive
    fun isHashrateThrottleActive(): Boolean = lastHashrateThrottleActive
    fun getMiningStartTimeMillis(): Long? = miningStartTimeMillis

    /** Current auto-tuning throttle sleep (ms). Only meaningful when auto-tuning is enabled in config. */
    fun getAutoTuningThrottleSleepMs(): Long = autoTuningThrottleSleepMs

    /** 0 = NONE, 1 = DECREASING, 2 = INCREASING. For dashboard color. */
    fun getAutoTuningDirection(): Int = autoTuningDirection

    override fun onCreate() {
        super.onCreate()
        configRepository = MiningConfigRepository(applicationContext)
        statsRepository = MiningStatsRepository(applicationContext)
        pendingSharesRepository = PendingSharesRepository(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> tryStartMining()
            ACTION_STOP -> stopMining()
            ACTION_RESTART -> restartMining()
            ACTION_ALARM_WAKEUP -> {
                if (!engine.isRunning()) return START_NOT_STICKY
                acquireWakeLock()
                handler.postDelayed({ releaseWakeLock() }, WAKE_LOCK_HOLD_MS)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onDestroy() {
        handler.removeCallbacks(sampleRunnable)
        handler.removeCallbacks(saveStatsRunnable)
        stopThrottleThread()
        statsRepository.save(engine.getStatus())
        unregisterConstraintReceiver()
        cancelAlarm()
        releaseWakeLock()
        engine.stop()
        super.onDestroy()
    }

    private fun tryStartMining() {
        AppLog.d(LOG_TAG) { "tryStartMining()" }
        val config = configRepository.getConfig()
        if (!config.isValidForMining()) {
            stopSelf()
            return
        }
        if (!MiningConstraints.canStartMining(this, config)) {
            stopSelf()
            return
        }
        startForeground(NOTIFICATION_ID, createNotification())
        AppLog.d(LOG_TAG) { "startForeground done, starting engine on background thread" }
        Thread {
            val host = StratumPinCapture.normalizeHost(config.stratumUrl)
            val useTls = config.stratumUrl.trim().lowercase().contains("ssl") || config.stratumPort == 443
            if (useTls && configRepository.getStratumPin(host) == null) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(applicationContext, R.string.security_reminder_defaulting_to_trust, Toast.LENGTH_SHORT).show()
                }
            }
            engine.start(config)
            if (!engine.isRunning()) {
                val err = engine.getStatus().lastError ?: "Mining failed"
                AppLog.e(LOG_TAG) { "Mining start failed: $err" }
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(applicationContext, getString(R.string.mining_failed, err), Toast.LENGTH_LONG).show()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return@Thread
            }
            Handler(Looper.getMainLooper()).post {
                miningStartTimeMillis = System.currentTimeMillis()
                val c = configRepository.getConfig()
                if (c.autoTuningByBatteryTemp) {
                    autoTuningThrottleSleepMs = AUTO_TUNING_DEFAULT_SLEEP_MS
                    autoTuningDirection = AUTO_TUNING_DIRECTION_NONE
                }
                throttleStateRef.set(ThrottleState(c.maxIntensityPercent, false, 0L, c.gpuUtilizationPercent))
                lastBatteryThrottleActive = false
                lastHashrateThrottleActive = false
                if (c.usePartialWakeLock) {
                    when {
                        c.alarmWakeIntervalSec == 0 -> acquireWakeLock()
                        c.alarmWakeIntervalSec in 1..60 -> scheduleAlarm(c.alarmWakeIntervalSec)
                        else -> { }
                    }
                }
                Toast.makeText(applicationContext, getString(R.string.mining_started), Toast.LENGTH_SHORT).show()
                registerConstraintReceiver()
                throttleThreadRunning.set(true)
                throttleThread = Thread { runThrottleLoop() }.apply { isDaemon = true; start() }
                handler.post(sampleRunnable)
                handler.postDelayed(saveStatsRunnable, STATS_SAVE_INTERVAL_MS)
            }
        }.start()
    }

    private fun stopThrottleThread() {
        throttleThreadRunning.set(false)
        throttleThread?.interrupt()
        throttleThread = null
    }

    private fun restartMining() {
        AppLog.d(LOG_TAG) { "restartMining()" }
        if (!engine.isRunning()) return
        handler.removeCallbacks(sampleRunnable)
        handler.removeCallbacks(saveStatsRunnable)
        stopThrottleThread()
        synchronized(hashrateHistoryCpu) { hashrateHistoryCpu.clear() }
        synchronized(hashrateHistoryGpu) { hashrateHistoryGpu.clear() }
        unregisterConstraintReceiver()
        cancelAlarm()
        releaseWakeLock()
        engine.stop()
        Thread {
            val config = configRepository.getConfig()
            if (!config.isValidForMining() || !MiningConstraints.canStartMining(this@MiningForegroundService, config)) {
                Handler(Looper.getMainLooper()).post { stopMining() }
                return@Thread
            }
            val host = StratumPinCapture.normalizeHost(config.stratumUrl)
            val useTls = config.stratumUrl.trim().lowercase().contains("ssl") || config.stratumPort == 443
            if (useTls && configRepository.getStratumPin(host) == null) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(applicationContext, R.string.security_reminder_defaulting_to_trust, Toast.LENGTH_SHORT).show()
                }
            }
            engine.start(config)
            if (!engine.isRunning()) {
                Handler(Looper.getMainLooper()).post { stopMining() }
                return@Thread
            }
            Handler(Looper.getMainLooper()).post {
                miningStartTimeMillis = System.currentTimeMillis()
                val c = configRepository.getConfig()
                if (c.autoTuningByBatteryTemp) {
                    autoTuningThrottleSleepMs = AUTO_TUNING_DEFAULT_SLEEP_MS
                    autoTuningDirection = AUTO_TUNING_DIRECTION_NONE
                }
                throttleStateRef.set(ThrottleState(c.maxIntensityPercent, false, 0L, c.gpuUtilizationPercent))
                lastBatteryThrottleActive = false
                lastHashrateThrottleActive = false
                if (c.usePartialWakeLock) {
                    when {
                        c.alarmWakeIntervalSec == 0 -> acquireWakeLock()
                        c.alarmWakeIntervalSec in 1..60 -> scheduleAlarm(c.alarmWakeIntervalSec)
                        else -> { }
                    }
                }
                Toast.makeText(applicationContext, getString(R.string.mining_restarted), Toast.LENGTH_SHORT).show()
                registerConstraintReceiver()
                throttleThreadRunning.set(true)
                throttleThread = Thread { runThrottleLoop() }.apply { isDaemon = true; start() }
                handler.post(sampleRunnable)
                handler.postDelayed(saveStatsRunnable, STATS_SAVE_INTERVAL_MS)
            }
        }.start()
    }

    private fun registerConstraintReceiver() {
        if (constraintReceiver != null) return
        constraintReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val config = configRepository.getConfig()
                if (!engine.isRunning()) return
                if (MiningConstraints.canStartMining(this@MiningForegroundService, config)) return
                // Allow offline mining: do not stop when the only failing constraint is network.
                val onlyNetworkGone = config.isValidForMining() &&
                    MiningConstraints.isChargingOk(this@MiningForegroundService, config) &&
                    !MiningConstraints.isNetworkOk(this@MiningForegroundService, config)
                if (!onlyNetworkGone) stopMining()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_POWER_CONNECTED)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_NOT_EXPORTED
        } else {
            @Suppress("InlinedApi") 0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(constraintReceiver, filter, flags)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(constraintReceiver, filter)
        }
    }

    private fun unregisterConstraintReceiver() {
        constraintReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) { }
            constraintReceiver = null
        }
    }

    private fun acquireWakeLock() {
        synchronized(wakeLockLock) {
            wakeLockRefCount++
            if (wakeLockRefCount == 1) {
                wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "btcminer:wake").apply { acquire() }
            }
        }
    }

    private fun releaseWakeLock() {
        synchronized(wakeLockLock) {
            if (wakeLockRefCount > 0) {
                wakeLockRefCount--
            }
            if (wakeLockRefCount == 0) {
                try {
                    wakeLock?.let { if (it.isHeld) it.release() }
                } catch (_: Exception) { }
                wakeLock = null
            }
        }
    }

    private fun scheduleAlarm(intervalSec: Int) {
        val intervalMs = (intervalSec * 1000).toLong()
        val pending = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_ALARM_WAKEUP).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmPendingIntent = pending
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = SystemClock.elapsedRealtime() + 1000L
        alarmManager.setRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAt,
            intervalMs,
            pending
        )
    }

    private fun cancelAlarm() {
        alarmPendingIntent?.let { pi ->
            (getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
            alarmPendingIntent = null
        }
    }

    private fun stopMining() {
        AppLog.d(LOG_TAG) { "stopMining()" }
        miningStartTimeMillis = null
        cancelAlarm()
        releaseWakeLock()
        handler.removeCallbacks(sampleRunnable)
        handler.removeCallbacks(saveStatsRunnable)
        stopThrottleThread()
        statsRepository.save(engine.getStatus())
        synchronized(hashrateHistoryCpu) { hashrateHistoryCpu.clear() }
        synchronized(hashrateHistoryGpu) { hashrateHistoryGpu.clear() }
        unregisterConstraintReceiver()
        engine.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(): Notification {
        createNotificationChannel()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val status = if (::configRepository.isInitialized) engine.getStatus() else null
        val contentText = if (MiningConstraints.isBothWifiAndDataUnavailable(applicationContext) && (status?.connectionLost == true)) getString(R.string.mining_reconnecting) else getString(R.string.mining_notification_text)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.mining_notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_mining_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.mining_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
            val redirectChannel = NotificationChannel(
                REDIRECT_CHANNEL_ID,
                getString(R.string.pool_redirect_notification_title),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { setShowBadge(false) }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(redirectChannel)
            val overheatChannel = NotificationChannel(
                OVERHEAT_CHANNEL_ID,
                getString(R.string.overheat_notification_title),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { setShowBadge(true) }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(overheatChannel)
        }
    }

    private fun setOverheatBannerFlag(show: Boolean) {
        getSharedPreferences(OVERHEAT_BANNER_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOW_OVERHEAT_BANNER, show).apply()
    }

    /** Shows a persistent, user-dismissible notification when mining stops due to overheat. */
    private fun showOverheatNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, OVERHEAT_CHANNEL_ID)
            .setContentTitle(getString(R.string.overheat_notification_title))
            .setContentText(getString(R.string.overheat_notification_body, MiningConfig.BATTERY_TEMP_HARD_STOP_C))
            .setSmallIcon(R.drawable.ic_mining_notification)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(OVERHEAT_NOTIFICATION_ID, notification)
    }

    /** Shows a persistent, user-dismissible notification when the pool sends client.reconnect (redirect not applied). */
    private fun showPoolRedirectNotification(host: String, port: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, REDIRECT_CHANNEL_ID)
            .setContentTitle(getString(R.string.pool_redirect_notification_title))
            .setContentText(getString(R.string.pool_redirect_notification_text, host, port))
            .setSmallIcon(R.drawable.ic_mining_notification)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(REDIRECT_NOTIFICATION_ID, notification)
    }

    companion object {
        /** Sleep duration (ms) after each chunk when battery or hashrate throttle is active. Adjust for testing. */
        const val THROTTLE_SLEEP_MS = 240_000L //120_000L //60_000L
        /** Per-second increase (ms) when temp in 95–100% band. */
        const val AUTO_TUNING_STEP_UP_MS = 1_000L
        /** Per-second decrease (ms) when temp below 90%; faster recovery. */
        const val AUTO_TUNING_STEP_DOWN_MS = 5_000L
        const val AUTO_TUNING_SLEEP_MAX = 240_000L //120_000L //60_000L
        /** Default throttle sleep (ms) when auto-tuning starts; no persistence. */
        const val AUTO_TUNING_DEFAULT_SLEEP_MS = 30_000L
        /** Fraction of max battery temp for auto-tune band low bound (below this → decrease delay). */
        const val AUTO_TUNING_TARGET_LO_RATIO: Double = 0.90 // 0.85 //0.70
        /** Fraction of max battery temp for auto-tune band high bound (above this up to 100% → increase delay). */
        const val AUTO_TUNING_TARGET_HI_RATIO: Double = 0.95 // 0.90
        const val AUTO_TUNING_DIRECTION_NONE = 0
        const val AUTO_TUNING_DIRECTION_DECREASING = 1
        const val AUTO_TUNING_DIRECTION_INCREASING = 2
        private const val STATS_SAVE_INTERVAL_MS = 60_000L
        private const val LOG_TAG = "Mining"
        const val ACTION_START = "com.btcminer.android.mining.START"
        const val ACTION_STOP = "com.btcminer.android.mining.STOP"
        const val ACTION_RESTART = "com.btcminer.android.mining.RESTART"
        const val ACTION_ALARM_WAKEUP = "com.btcminer.android.mining.ALARM_WAKEUP"
        const val WAKE_LOCK_HOLD_MS = 55_000L
        private const val CHANNEL_ID = "mining"
        private const val NOTIFICATION_ID = 1
        private const val REDIRECT_CHANNEL_ID = "pool_redirect"
        private const val REDIRECT_NOTIFICATION_ID = 2
        private const val OVERHEAT_CHANNEL_ID = "overheat"
        private const val OVERHEAT_NOTIFICATION_ID = 3
        const val OVERHEAT_BANNER_PREFS = "overheat_banner"
        const val KEY_SHOW_OVERHEAT_BANNER = "show_overheat_stopped_banner"
    }
}
