package com.btcminer.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.BatteryManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.Scanner

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
            binding.hashRateValue.setTextColor(if (hashrateThrottle) if (flashPhase) Color.RED else throttleSecondary else orange)
            binding.batteryTempValue.setTextColor(if (batteryThrottle) if (flashPhase) Color.RED else throttleSecondary else orange)
            flashPhase = !flashPhase
            handler.postDelayed(this, THROTTLE_FLASH_PERIOD_MS / 2)
        }
    }
    private val pollRunnable = object : Runnable {
        override fun run() {
            updateBatteryTempUi()
            val service = miningService
            if (service != null) {
                val status = service.getStatus()
                updateStatsUi(status, service)
                updateChart(service.getHashrateHistoryCpu(), service.getHashrateHistoryGpu())
            } else {
                val status = MiningStatsRepository(applicationContext).get()
                updateStatsUi(status, null)
            }
            handler.postDelayed(this, 1000L)
        }
    }

    private val mempoolFetchRunnable: Runnable = object : Runnable {
        override fun run() {
            val self = this
            val address = configRepository.getConfig().bitcoinAddress.trim()
            if (address.isEmpty()) {
                binding.walletBalanceValue.text = "—"
                handler.postDelayed(self, MEMPOOL_BALANCE_FETCH_INTERVAL_MS)
                return
            }
            Thread {
                var result: String? = null
                try {
                    val encoded = URLEncoder.encode(address, "UTF-8")
                    val url = URL("$MEMPOOL_UTXO_URL/$encoded/utxo")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 15_000
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        result = "INVALID"
                    } else {
                        val text = conn.inputStream.use { Scanner(it).useDelimiter("\\A").next() }
                        val arr = JSONArray(text)
                        var sumSat = 0L
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            sumSat += obj.optLong("value", 0L)
                        }
                        val btc = sumSat / 100_000_000.0
                        result = String.format(Locale.US, "₿ %.8f", btc)
                    }
                } catch (_: Exception) {
                    result = "INVALID"
                }
                val toShow = result
                runOnUiThread {
                    binding.walletBalanceValue.text = toShow
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
                updateStatsUi(MiningStatsRepository(applicationContext).get(), null)
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

        setupChart()
        // Initialize lastBitcoinAddress to detect changes when returning from Config
        lastBitcoinAddress = configRepository.getConfig().bitcoinAddress.trim()
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
        binding.hashRateValue.setTextColor(ContextCompat.getColor(this, R.color.bitcoin_orange))
        binding.batteryTempValue.setTextColor(ContextCompat.getColor(this, R.color.bitcoin_orange))
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
        val hashrateStr = if (status.state == MiningStatus.State.Mining) {
            String.format(Locale.US, "%.2f H/s", status.hashrateHs)
        } else {
            "— H/s"
        }
        binding.hashRateValue.text = hashrateStr
        val gpuHashrateStr = if (status.state == MiningStatus.State.Mining) {
            String.format(Locale.US, "%.2f H/s", status.gpuHashrateHs)
        } else {
            "—"
        }
        binding.gpuHashRateValue.text = gpuHashrateStr
        binding.noncesValue.text = status.noncesScanned.toString()
        binding.acceptedSharesValue.text = status.acceptedShares.toString()
        binding.rejectedSharesValue.text = status.rejectedShares.toString()
        binding.root.findViewById<TextView>(R.id.identified_shares_value).text = status.identifiedShares.toString()
        binding.bestDifficultyValue.text = if (status.bestDifficulty > 0.0) String.format(Locale.US, "%.6f", status.bestDifficulty) else "—"
        binding.blockTemplateValue.text = status.blockTemplates.toString()
        val startMs = service?.getMiningStartTimeMillis()
        val timerStr = if (status.state == MiningStatus.State.Mining && startMs != null) {
            formatElapsed(System.currentTimeMillis() - startMs)
        } else {
            "00:00:00:00"
        }
        binding.miningTimerValue.text = timerStr
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
            binding.batteryTempValue.text = "—"
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
        binding.batteryTempValue.text = text
    }

    private fun clearStatsUi() {
        binding.hashRateValue.text = "0.00 H/s"
        binding.gpuHashRateValue.text = "0.00 H/s"
        binding.miningTimerValue.text = "00:00:00:00"
        binding.batteryTempValue.text = "—"
        binding.bestDifficultyValue.text = "—"
        binding.blockTemplateValue.text = "—"
        binding.walletBalanceValue.text = "—"
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
