package com.btcminer.android

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.btcminer.android.config.ConfigActivity
import com.btcminer.android.config.MiningConfigRepository
import com.btcminer.android.databinding.ActivityMainBinding
import com.btcminer.android.mining.MiningConstraints
import com.btcminer.android.mining.MiningForegroundService
import com.btcminer.android.mining.MiningStatsRepository
import com.btcminer.android.mining.MiningStatus
import com.btcminer.android.mining.NativeMiner
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.btcminer.android.network.CertPins
import com.btcminer.android.util.BitcoinAddressValidator
import com.btcminer.android.util.NumberFormatUtils
import com.google.android.material.snackbar.Snackbar
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.Locale
import java.util.Scanner
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        /** Full cycle period (ms) for throttle flash (red/white). Low frequency for safety. */
        private const val THROTTLE_FLASH_PERIOD_MS = 1000L
        /** How often to fetch wallet balance from Mempool.space (ms). */
        private const val MEMPOOL_BALANCE_FETCH_INTERVAL_MS = 3_600_000L  // 1 hour
        private const val MEMPOOL_UTXO_URL = "https://mempool.space/api/address"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var configRepository: MiningConfigRepository
    private val statsRepository by lazy { MiningStatsRepository(applicationContext) }

    private var page1Fragment: DashboardStatsPage1Fragment? = null
    private var page2Fragment: DashboardStatsPage2Fragment? = null

    private val dashboardFragmentCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
            when (f) {
                is DashboardStatsPage1Fragment -> page1Fragment = f
                is DashboardStatsPage2Fragment -> page2Fragment = f
                else -> return
            }
            refreshDashboardFromPoll()
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            when (f) {
                is DashboardStatsPage1Fragment -> if (page1Fragment === f) page1Fragment = null
                is DashboardStatsPage2Fragment -> if (page2Fragment === f) page2Fragment = null
            }
        }
    }

    /** Full-screen dimensions saved before entering PIP; used to scale root to fit in PIP. */
    private var pipFullWidth = 0
    private var pipFullHeight = 0
    /** Listener that updates root scale when PIP window is resized; removed when leaving PIP. */
    private var pipScaleLayoutListener: View.OnLayoutChangeListener? = null

    private val mempoolOkHttpClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
        if (CertPins.hasMempoolSpacePins()) {
            val pinnerBuilder = CertificatePinner.Builder()
            CertPins.MEMPOOL_SPACE_PINS.forEach { pin -> pinnerBuilder.add("mempool.space", pin) }
            builder.certificatePinner(pinnerBuilder.build())
        }
        builder.build()
    }

    private var miningService: MiningForegroundService? = null
    private var lastBitcoinAddress: String = ""
    private val handler = Handler(Looper.getMainLooper())
    private var flashPhase = false
    private val flashRunnable = object : Runnable {
        override fun run() {
            val service = miningService
            val hashrateThrottle = service?.isHashrateThrottleActive() == true
            val batteryThrottle = service?.isBatteryThrottleActive() == true
            val orange = ContextCompat.getColor(this@MainActivity, R.color.bitcoin_orange)
            val throttleSecondary = ContextCompat.getColor(this@MainActivity, R.color.throttle_secondary)
            val p1 = page1Fragment?.pageBinding
            p1?.hashRateValue?.setTextColor(if (hashrateThrottle) if (flashPhase) Color.RED else throttleSecondary else orange)
            p1?.batteryTempValue?.setTextColor(if (batteryThrottle) if (flashPhase) Color.RED else throttleSecondary else orange)
            flashPhase = !flashPhase
            handler.postDelayed(this, THROTTLE_FLASH_PERIOD_MS / 2)
        }
    }
    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshDashboardFromPoll()
            handler.postDelayed(this, 1000L)
        }
    }

    private val mempoolFetchRunnable: Runnable = object : Runnable {
        override fun run() {
            val self = this
            val address = configRepository.getConfig().bitcoinAddress.trim()
            if (address.isEmpty()) {
                binding.walletBalanceValue.text = "—"
                binding.walletBalanceNote.visibility = View.GONE
                handler.postDelayed(self, MEMPOOL_BALANCE_FETCH_INTERVAL_MS)
                return
            }
            if (!BitcoinAddressValidator.isValidAddress(address)) {
                binding.walletBalanceValue.text = "—"
                binding.walletBalanceNote.setText(R.string.wallet_balance_invalid_address)
                binding.walletBalanceNote.visibility = View.VISIBLE
                handler.postDelayed(self, MEMPOOL_BALANCE_FETCH_INTERVAL_MS)
                return
            }
            Thread {
                var result: String? = null
                var certFailure = false
                try {
                    val encoded = URLEncoder.encode(address, "UTF-8")
                    val url = "$MEMPOOL_UTXO_URL/$encoded/utxo"
                    val request = Request.Builder().url(url).get().build()
                    val response = mempoolOkHttpClient.newCall(request).execute()
                    if (!response.isSuccessful) {
                        result = "INVALID"
                    } else {
                        val text = response.body?.string() ?: ""
                        val arr = JSONArray(text)
                        var sumSat = 0L
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            sumSat += obj.optLong("value", 0L)
                        }
                        val btc = sumSat / 100_000_000.0
                        result = String.format(Locale.US, "₿ %.8f", btc)
                    }
                } catch (e: Exception) {
                    result = "INVALID"
                    certFailure = e is javax.net.ssl.SSLPeerUnverifiedException || e is javax.net.ssl.SSLException
                }
                val toShow = result
                val showCertInvalid = certFailure
                val showCertValidated = result != null && result != "INVALID" && CertPins.hasMempoolSpacePins()
                runOnUiThread {
                    if (CertPins.hasMempoolSpacePins()) {
                        if (showCertValidated) Toast.makeText(this@MainActivity, R.string.mempool_cert_validated, Toast.LENGTH_SHORT).show()
                        if (showCertInvalid) Toast.makeText(this@MainActivity, R.string.mempool_cert_invalid, Toast.LENGTH_SHORT).show()
                    }
                    binding.walletBalanceValue.text = toShow
                    if (toShow == "INVALID") {
                        binding.walletBalanceNote.setText(R.string.wallet_balance_tor_note)
                        binding.walletBalanceNote.visibility = View.VISIBLE
                    } else {
                        binding.walletBalanceNote.visibility = View.GONE
                    }
                    handler.postDelayed(self, MEMPOOL_BALANCE_FETCH_INTERVAL_MS)
                }
            }.start()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            miningService = (binder as? MiningForegroundService.LocalBinder)?.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            miningService = null
            // Keep poll running so dashboard shows persisted counters; show persisted stats and clear chart only
            handler.post {
                updateStatsUi(statsRepository.get(), null)
                updateLifetimeUi()
                binding.hashRateChart.data = null
                binding.hashRateChart.invalidate()
            }
        }
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> tryStartMiningService() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configRepository = MiningConfigRepository(applicationContext)

        // Phase 1: verify native miner loads and responds
        Toast.makeText(this, "Native miner: ${NativeMiner.nativeVersion()}", Toast.LENGTH_SHORT).show()
        // Validate: SHA-256 implementation
        val phase2Ok = NativeMiner.nativeTestSha256()
        Toast.makeText(
            this,
            if (phase2Ok) "Validate: SHA-256 OK" else "Validate: SHA-256 FAIL",
            Toast.LENGTH_SHORT
        ).show()

        binding.buttonConfig.setOnClickListener {
            startActivity(Intent(this, ConfigActivity::class.java))
        }
        binding.buttonStartMining.setOnClickListener { onStartMiningClicked() }
        binding.buttonStopMining.setOnClickListener { onStopMiningClicked() }

        supportFragmentManager.registerFragmentLifecycleCallbacks(dashboardFragmentCallbacks, true)
        binding.dashboardPager.adapter = MainPagerAdapter(this)

        setupChart()
        // Initialize lastBitcoinAddress to detect changes when returning from Config
        lastBitcoinAddress = configRepository.getConfig().bitcoinAddress.trim()
    }

    override fun onDestroy() {
        supportFragmentManager.unregisterFragmentLifecycleCallbacks(dashboardFragmentCallbacks)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Check if Bitcoin address changed in Config and trigger immediate fetch
        val currentAddress = configRepository.getConfig().bitcoinAddress.trim()
        if (currentAddress != lastBitcoinAddress) {
            lastBitcoinAddress = currentAddress
            // Trigger immediate balance check when address changes
            handler.post(mempoolFetchRunnable)
        }
        // One-time overheat banner: if mining was stopped due to overheat, show until user dismisses
        val prefs = getSharedPreferences(MiningForegroundService.OVERHEAT_BANNER_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(MiningForegroundService.KEY_SHOW_OVERHEAT_BANNER, false)) {
            Snackbar.make(binding.root, getString(R.string.overheat_banner_message), Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.overheat_banner_dismiss) {
                    prefs.edit().putBoolean(MiningForegroundService.KEY_SHOW_OVERHEAT_BANNER, false).apply()
                }
                .show()
        }
    }

    override fun onStart() {
        super.onStart()
        handler.post(pollRunnable)
        handler.post(flashRunnable)
        handler.post(mempoolFetchRunnable)
        bindService(
            Intent(this, MiningForegroundService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        super.onStop()
        try {
            unbindService(connection)
        } catch (_: Exception) { }
        miningService = null
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(flashRunnable)
        handler.removeCallbacks(mempoolFetchRunnable)
        val orange = ContextCompat.getColor(this, R.color.bitcoin_orange)
        page1Fragment?.pageBinding?.hashRateValue?.setTextColor(orange)
        page1Fragment?.pageBinding?.batteryTempValue?.setTextColor(orange)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureModeIfSupported()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val root = binding.root
        if (isInPictureInPictureMode) {
            // Keep root at full size and scale it down so entire screen fits in PIP (no reflow/crop).
            if (pipFullWidth > 0 && pipFullHeight > 0) {
                root.layoutParams = (root.layoutParams as? FrameLayout.LayoutParams)?.apply {
                    width = pipFullWidth
                    height = pipFullHeight
                } ?: FrameLayout.LayoutParams(pipFullWidth, pipFullHeight)
                pipScaleLayoutListener?.let { (root.parent as? View)?.removeOnLayoutChangeListener(it) }
                pipScaleLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    updatePipScale()
                }
                (root.parent as? View)?.addOnLayoutChangeListener(pipScaleLayoutListener)
                root.post { updatePipScale() }
            }
        } else {
            pipScaleLayoutListener?.let { (root.parent as? View)?.removeOnLayoutChangeListener(it) }
            pipScaleLayoutListener = null
            // Restore normal layout and scale when leaving PIP.
            root.layoutParams = (root.layoutParams as? FrameLayout.LayoutParams)?.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
            } ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            root.scaleX = 1f
            root.scaleY = 1f
            root.requestLayout()
        }
    }

    /** Recompute root scale so full content fits the current PIP window size. */
    private fun updatePipScale() {
        if (pipFullWidth <= 0 || pipFullHeight <= 0) return
        val root = binding.root
        val parent = root.parent as? View ?: return
        val pw = parent.width
        val ph = parent.height
        if (pw > 0 && ph > 0) {
            val scale = minOf(pw.toFloat() / pipFullWidth, ph.toFloat() / pipFullHeight)
            root.pivotX = 0f
            root.pivotY = 0f
            root.scaleX = scale
            root.scaleY = scale
        }
    }

    @Suppress("DEPRECATION")
    private fun enterPictureInPictureModeIfSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val root = binding.root
        val w = root.width
        val h = root.height
        val dm = resources.displayMetrics
        val width = if (w > 0 && h > 0) w else dm.widthPixels
        val height = if (w > 0 && h > 0) h else dm.heightPixels
        if (w > 0 && h > 0) {
            pipFullWidth = w
            pipFullHeight = h
        }
        // Portrait: height >= width. Ensure portrait Rational so PIP window is always tall.
        val portraitW = minOf(width, height)
        val portraitH = maxOf(width, height)
        val rational = Rational(portraitW, portraitH)
        // Source rect in window coordinates.
        val sourceRect = if (w > 0 && h > 0) {
            val loc = IntArray(2)
            root.getLocationInWindow(loc)
            Rect(loc[0], loc[1], loc[0] + w, loc[1] + h)
        } else {
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(rational)
            .setSourceRectHint(sourceRect)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setSeamlessResizeEnabled(false)
        }
        if (!enterPictureInPictureMode(builder.build())) {
            // PIP not entered (e.g. not allowed by device or policy)
        }
    }

    private fun setupChart() {
        val chartTextColor = ContextCompat.getColor(this, R.color.chart_axis_legend)
        binding.hashRateChart.description.isEnabled = false
        binding.hashRateChart.legend.isEnabled = false
        binding.hashRateChart.legend.setTextColor(chartTextColor)
        binding.hashRateChart.xAxis.setDrawLabels(true)
        binding.hashRateChart.xAxis.setTextColor(chartTextColor)
        binding.hashRateChart.axisLeft.setDrawLabels(true)
        binding.hashRateChart.axisLeft.setTextColor(chartTextColor)
        binding.hashRateChart.axisRight.isEnabled = false
    }

    private fun updateStatsUi(status: MiningStatus, service: MiningForegroundService?) {
        page1Fragment?.pageBinding?.let { p1 ->
            val hashrateStr = if (status.state == MiningStatus.State.Mining) {
                "${NumberFormatUtils.formatHashrateWithSpaces(status.hashrateHs)} H/s"
            } else {
                "— H/s"
            }
            p1.hashRateValue.text = hashrateStr
            val gpuHashrateStr = when {
                !status.gpuAvailable -> "----"
                status.state == MiningStatus.State.Mining -> "${NumberFormatUtils.formatHashrateWithSpaces(status.gpuHashrateHs)} H/s"
                else -> "—"
            }
            p1.gpuHashRateValue.text = gpuHashrateStr
            val config = configRepository.getConfig()
            val gpuCores = config.gpuCores.coerceAtLeast(0)
            val maxWorkGroupSize = NativeMiner.getMaxComputeWorkGroupSize().coerceAtLeast(32)
            val effectiveWorkgroupSize = if (gpuCores > 0) (32 * gpuCores).coerceAtMost(maxWorkGroupSize) else 0
            p1.gpuHashRateLabel.text = getString(R.string.hash_rate_gpu_label) + if (gpuCores > 0) " - $effectiveWorkgroupSize" else ""
            val cpuPct = service?.getLastCpuUtilizationPercent()
            p1.cpuUtilizationValue.text = if (cpuPct != null) String.format(Locale.US, "%.1f%%", cpuPct) else "—"
            p1.noncesValue.text = NumberFormatUtils.formatWithSpaces(status.noncesScanned)
            p1.acceptedSharesValue.text = status.acceptedShares.toString()
            p1.rejectedSharesValue.text = status.rejectedShares.toString()
            p1.identifiedSharesValue.text = status.identifiedShares.toString()
            p1.bestDifficultyValue.text = if (status.bestDifficulty > 0.0) String.format(Locale.US, "%.6f", status.bestDifficulty) else "—"
            p1.blockTemplateValue.text = status.blockTemplates.toString()
            val startMs = service?.getMiningStartTimeMillis()
            val (timerStr, timerLabel) = if (status.state == MiningStatus.State.Mining && startMs != null) {
                formatElapsed(System.currentTimeMillis() - startMs) to getString(R.string.mining_timer_label)
            } else {
                val lastRunMs = statsRepository.getLastRunDurationMs()
                if (lastRunMs > 0) {
                    formatElapsed(lastRunMs) to getString(R.string.mining_timer_last_run)
                } else {
                    "00:00:00:00" to getString(R.string.mining_timer_label)
                }
            }
            p1.miningTimerValue.text = timerStr
            p1.miningTimerLabel.text = timerLabel
            p1.stratumConnectionIcon.alpha = if (status.state == MiningStatus.State.Mining && !status.connectionLost) 1f else 0.3f
        }
        binding.reconnectingBanner.visibility = if (MiningConstraints.isBothWifiAndDataUnavailable(this) && status.connectionLost) View.VISIBLE else View.GONE
    }

    private fun refreshDashboardFromPoll() {
        updateBatteryTempUi()
        val service = miningService
        if (service != null) {
            updateStatsUi(service.getStatus(), service)
            updateChart(service.getHashrateHistoryCpu(), service.getHashrateHistoryGpu())
        } else {
            updateStatsUi(statsRepository.get(), null)
        }
        updateLifetimeUi()
        val config = configRepository.getConfig()
        if (config.autoTuningByBatteryTemp && service != null) {
            binding.autoTuneBlock.visibility = View.VISIBLE
            binding.autoTuneValue.text = "${service.getAutoTuningThrottleSleepMs() / 1000}"
            val dir = service.getAutoTuningDirection()
            val color = when (dir) {
                MiningForegroundService.AUTO_TUNING_DIRECTION_DECREASING -> ContextCompat.getColor(this, R.color.auto_tune_decreasing)
                MiningForegroundService.AUTO_TUNING_DIRECTION_INCREASING -> ContextCompat.getColor(this, R.color.bitcoin_orange)
                else -> ContextCompat.getColor(this, R.color.white)
            }
            binding.autoTuneValue.setTextColor(color)
        } else {
            binding.autoTuneBlock.visibility = View.GONE
        }
    }

    private fun updateLifetimeUi() {
        val p2 = page2Fragment?.pageBinding ?: return
        val s = statsRepository.getLifetimeStats()
        p2.lifetimeTotalHashValue.text = "${NumberFormatUtils.formatHashrateWithSpaces(s.sumSessionAvgTotalHs)} H/s"
        p2.lifetimeCpuHashValue.text = "${NumberFormatUtils.formatHashrateWithSpaces(s.sumSessionAvgCpuHs)} H/s"
        p2.lifetimeGpuHashValue.text = "${NumberFormatUtils.formatHashrateWithSpaces(s.sumSessionAvgGpuHs)} H/s"
        p2.lifetimeNoncesValue.text = NumberFormatUtils.formatWithSpaces(s.totalNonces)
    }

    private fun formatElapsed(elapsedMs: Long): String {
        var totalSeconds = (elapsedMs / 1000).coerceAtLeast(0)
        val days = (totalSeconds / 86400).coerceAtMost(99)
        totalSeconds %= 86400
        val hours = totalSeconds / 3600
        totalSeconds %= 3600
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d:%02d", days, hours, minutes, seconds)
    }

    private fun updateBatteryTempUi() {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: run {
            page1Fragment?.pageBinding?.batteryTempValue?.text = "—"
            return
        }
        val tempTenthsC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val useF = configRepository.getConfig().batteryTempFahrenheit
        val text = if (tempTenthsC == 0) "—" else {
            val tempC = tempTenthsC / 10f
            if (useF) {
                val tempF = tempC * 9f / 5f + 32f
                String.format(Locale.US, "%.1f °F", tempF)
            } else {
                String.format(Locale.US, "%.1f °C", tempC)
            }
        }
        page1Fragment?.pageBinding?.batteryTempValue?.text = text
    }

    private fun clearStatsUi() {
        page1Fragment?.pageBinding?.let { p1 ->
            p1.hashRateValue.text = "0.00 H/s"
            p1.gpuHashRateValue.text = "0.00 H/s"
            p1.cpuUtilizationValue.text = "—"
            p1.miningTimerValue.text = "00:00:00:00"
            p1.batteryTempValue.text = "—"
            p1.bestDifficultyValue.text = "—"
            p1.blockTemplateValue.text = "—"
        }
        binding.walletBalanceValue.text = "—"
        binding.walletBalanceNote.visibility = View.GONE
        binding.hashRateChart.data = null
        binding.hashRateChart.invalidate()
        // Persistent counters (nonces, accepted/rejected/identified shares, block template, best difficulty) are not zeroed here; they reset only via Config "Reset All UI Counters"
    }

    private fun updateChart(historyCpu: List<Double>, historyGpu: List<Double>) {
        val maxSize = maxOf(historyCpu.size, historyGpu.size)
        if (maxSize == 0) {
            binding.hashRateChart.data = null
            binding.hashRateChart.invalidate()
            return
        }
        val orange = ContextCompat.getColor(this, R.color.bitcoin_orange)
        val gray = Color.GRAY
        val green = Color.parseColor("#4CAF50")

        val cpuEntries = historyCpu.mapIndexed { i, v -> Entry(i.toFloat(), v.toFloat()) }
        val cpuSet = LineDataSet(cpuEntries, "CPU").apply {
            setColor(gray)
            setCircleColor(gray)
            lineWidth = 2f
            setDrawCircles(false)
            setDrawValues(false)
        }

        val gpuEntries = historyGpu.mapIndexed { i, v -> Entry(i.toFloat(), v.toFloat()) }
        val gpuSet = LineDataSet(gpuEntries, "GPU").apply {
            setColor(green)
            setCircleColor(green)
            lineWidth = 2f
            setDrawCircles(false)
            setDrawValues(false)
        }

        val totalHistory = (0 until maxSize).map { i ->
            (historyCpu.getOrNull(i) ?: 0.0) + (historyGpu.getOrNull(i) ?: 0.0)
        }
        val avg = if (totalHistory.isNotEmpty()) totalHistory.average() else 0.0
        val avgEntries = (0 until maxSize).map { i -> Entry(i.toFloat(), avg.toFloat()) }
        val avgSet = LineDataSet(avgEntries, "Avg").apply {
            setColor(orange)
            setCircleColor(orange)
            lineWidth = 2f
            setDrawCircles(false)
            setDrawValues(false)
        }

        binding.hashRateChart.data = LineData(cpuSet, gpuSet, avgSet)
        binding.hashRateChart.legend.isEnabled = true
        binding.hashRateChart.legend.setTextColor(ContextCompat.getColor(this, R.color.chart_axis_legend))
        binding.hashRateChart.invalidate()
    }

    private fun onStartMiningClicked() {
        val config = configRepository.getConfig()
        if (!config.isValidForMining()) {
            Toast.makeText(this, R.string.mining_start_fail_config, Toast.LENGTH_SHORT).show()
            return
        }
        if (!MiningConstraints.isNetworkOk(this, config)) {
            Toast.makeText(this, R.string.mining_start_fail_network, Toast.LENGTH_SHORT).show()
            return
        }
        if (!MiningConstraints.isChargingOk(this, config)) {
            Toast.makeText(this, R.string.mining_start_fail_charging, Toast.LENGTH_SHORT).show()
            return
        }
        // Refresh Bitcoin balance when starting mining
        handler.post(mempoolFetchRunnable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            tryStartMiningService()
        }
    }

    private fun tryStartMiningService() {
        startService(Intent(this, MiningForegroundService::class.java).apply {
            action = MiningForegroundService.ACTION_START
        })
    }

    private fun onStopMiningClicked() {
        startService(Intent(this, MiningForegroundService::class.java).apply {
            action = MiningForegroundService.ACTION_STOP
        })
    }
}
