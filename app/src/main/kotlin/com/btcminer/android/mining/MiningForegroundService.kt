package com.btcminer.android.mining

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
import android.os.BatteryManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.btcminer.android.AppLog
import java.util.concurrent.atomic.AtomicReference
import com.btcminer.android.MainActivity
import com.btcminer.android.R
import com.btcminer.android.config.MiningConfig
import com.btcminer.android.config.MiningConfigRepository

class MiningForegroundService : Service() {

    private lateinit var configRepository: MiningConfigRepository
    private val throttleStateRef = AtomicReference(ThrottleState(100, false))
    private val engine: MiningEngine by lazy { NativeMiningEngine(throttleStateRef) }
    private var constraintReceiver: BroadcastReceiver? = null
    private var lastBatteryThrottleActive = false
    private var lastHashrateThrottleActive = false
    private var miningStartTimeMillis: Long? = null

    private val handler = Handler(Looper.getMainLooper())
    private val hashrateHistory = mutableListOf<Double>()
    private val maxHistorySize = 120
    private val sampleRunnable = object : Runnable {
        override fun run() {
            if (engine.isRunning()) {
                val status = engine.getStatus()
                if (status.state == MiningStatus.State.Mining) {
                    synchronized(hashrateHistory) {
                        hashrateHistory.add(status.hashrateHs)
                        while (hashrateHistory.size > maxHistorySize) hashrateHistory.removeAt(0)
                    }
                }
                val config = configRepository.getConfig()
                val tempTenthsC = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
                val tempC = tempTenthsC / 10.0
                val hashrateHs = status.hashrateHs

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
                    hashrateHs > hashrateTarget -> true
                    hashrateHs <= hashrateTarget * 0.9 -> false
                    else -> lastHashrateThrottleActive
                }
                if (stopDueToOverheat) {
                    handler.post {
                        engine.stop()
                        Toast.makeText(applicationContext, R.string.battery_too_hot_toast, Toast.LENGTH_LONG).show()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    return
                }
                val throttleSleepMs = if (lastBatteryThrottleActive || lastHashrateThrottleActive) THROTTLE_SLEEP_MS else 0L
                throttleStateRef.set(ThrottleState(config.maxIntensityPercent, false, throttleSleepMs))
            }
            handler.postDelayed(this, 1000L)
        }
    }

    inner class LocalBinder : android.os.Binder() {
        fun getService(): MiningForegroundService = this@MiningForegroundService
    }

    fun getStatus(): MiningStatus = engine.getStatus()

    fun getHashrateHistory(): List<Double> = synchronized(hashrateHistory) { hashrateHistory.toList() }

    fun isBatteryThrottleActive(): Boolean = lastBatteryThrottleActive
    fun isHashrateThrottleActive(): Boolean = lastHashrateThrottleActive
    fun getMiningStartTimeMillis(): Long? = miningStartTimeMillis

    override fun onCreate() {
        super.onCreate()
        configRepository = MiningConfigRepository(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> tryStartMining()
            ACTION_STOP -> stopMining()
            ACTION_RESTART -> restartMining()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onDestroy() {
        unregisterConstraintReceiver()
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
                throttleStateRef.set(ThrottleState(configRepository.getConfig().maxIntensityPercent, false, 0L))
                lastBatteryThrottleActive = false
                lastHashrateThrottleActive = false
                Toast.makeText(applicationContext, getString(R.string.mining_started), Toast.LENGTH_SHORT).show()
                registerConstraintReceiver()
                handler.post(sampleRunnable)
            }
        }.start()
    }

    private fun restartMining() {
        AppLog.d(LOG_TAG) { "restartMining()" }
        if (!engine.isRunning()) return
        handler.removeCallbacks(sampleRunnable)
        synchronized(hashrateHistory) { hashrateHistory.clear() }
        unregisterConstraintReceiver()
        engine.stop()
        Thread {
            val config = configRepository.getConfig()
            if (!config.isValidForMining() || !MiningConstraints.canStartMining(this@MiningForegroundService, config)) {
                Handler(Looper.getMainLooper()).post { stopMining() }
                return@Thread
            }
            engine.start(config)
            if (!engine.isRunning()) {
                Handler(Looper.getMainLooper()).post { stopMining() }
                return@Thread
            }
            Handler(Looper.getMainLooper()).post {
                miningStartTimeMillis = System.currentTimeMillis()
                throttleStateRef.set(ThrottleState(configRepository.getConfig().maxIntensityPercent, false, 0L))
                lastBatteryThrottleActive = false
                lastHashrateThrottleActive = false
                Toast.makeText(applicationContext, getString(R.string.mining_restarted), Toast.LENGTH_SHORT).show()
                registerConstraintReceiver()
                handler.post(sampleRunnable)
            }
        }.start()
    }

    private fun registerConstraintReceiver() {
        if (constraintReceiver != null) return
        constraintReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val config = configRepository.getConfig()
                if (engine.isRunning() && !MiningConstraints.canStartMining(this@MiningForegroundService, config)) {
                    stopMining()
                }
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
        registerReceiver(constraintReceiver, filter, flags)
    }

    private fun unregisterConstraintReceiver() {
        constraintReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) { }
            constraintReceiver = null
        }
    }

    private fun stopMining() {
        AppLog.d(LOG_TAG) { "stopMining()" }
        miningStartTimeMillis = null
        handler.removeCallbacks(sampleRunnable)
        synchronized(hashrateHistory) { hashrateHistory.clear() }
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.mining_notification_title))
            .setContentText(getString(R.string.mining_notification_text))
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
        }
    }

    companion object {
        /** Sleep duration (ms) after each chunk when battery or hashrate throttle is active. Adjust for testing. */
        const val THROTTLE_SLEEP_MS = 60_000L //5000L
        private const val LOG_TAG = "Mining"
        const val ACTION_START = "com.btcminer.android.mining.START"
        const val ACTION_STOP = "com.btcminer.android.mining.STOP"
        const val ACTION_RESTART = "com.btcminer.android.mining.RESTART"
        private const val CHANNEL_ID = "mining"
        private const val NOTIFICATION_ID = 1
    }
}
