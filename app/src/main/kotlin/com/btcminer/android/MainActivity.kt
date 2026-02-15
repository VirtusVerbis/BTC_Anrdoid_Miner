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
import com.btcminer.android.mining.MiningStatus
import com.btcminer.android.mining.NativeMiner
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        /** Full cycle period (ms) for throttle flash (red/white). Low frequency for safety. */
        private const val THROTTLE_FLASH_PERIOD_MS = 1000L
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var configRepository: MiningConfigRepository

    private var miningService: MiningForegroundService? = null
    private val handler = Handler(Looper.getMainLooper())
    private var flashPhase = false
    private val flashRunnable = object : Runnable {
        override fun run() {
            val service = miningService
            val hashrateThrottle = service?.isHashrateThrottleActive() == true
            val batteryThrottle = service?.isBatteryThrottleActive() == true
            val orange = ContextCompat.getColor(this@MainActivity, R.color.bitcoin_orange)
            binding.hashRateValue.setTextColor(if (hashrateThrottle) if (flashPhase) Color.RED else Color.WHITE else orange)
            binding.batteryTempValue.setTextColor(if (batteryThrottle) if (flashPhase) Color.RED else Color.WHITE else orange)
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
                val history = service.getHashrateHistory()
                updateStatsUi(status, service)
                updateChart(history)
            }
            handler.postDelayed(this, 1000L)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            miningService = (binder as? MiningForegroundService.LocalBinder)?.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            miningService = null
            handler.removeCallbacks(pollRunnable)
            clearStatsUi()
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
        // Phase 2: verify SHA-256 implementation
        val phase2Ok = NativeMiner.nativeTestSha256()
        Toast.makeText(
            this,
            if (phase2Ok) "Phase 2: SHA-256 OK" else "Phase 2: SHA-256 FAIL",
            Toast.LENGTH_SHORT
        ).show()

        binding.buttonConfig.setOnClickListener {
            startActivity(Intent(this, ConfigActivity::class.java))
        }
        binding.buttonStartMining.setOnClickListener { onStartMiningClicked() }
        binding.buttonStopMining.setOnClickListener { onStopMiningClicked() }

        setupChart()
    }

    override fun onStart() {
        super.onStart()
        handler.post(pollRunnable)
        handler.post(flashRunnable)
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
        binding.hashRateValue.setTextColor(ContextCompat.getColor(this, R.color.bitcoin_orange))
        binding.batteryTempValue.setTextColor(ContextCompat.getColor(this, R.color.bitcoin_orange))
    }

    private fun setupChart() {
        binding.hashRateChart.description.isEnabled = false
        binding.hashRateChart.legend.isEnabled = false
        binding.hashRateChart.xAxis.setDrawLabels(true)
        binding.hashRateChart.xAxis.setTextColor(Color.WHITE)
        binding.hashRateChart.axisLeft.setDrawLabels(true)
        binding.hashRateChart.axisLeft.setTextColor(Color.WHITE)
        binding.hashRateChart.axisRight.isEnabled = false
    }

    private fun updateStatsUi(status: MiningStatus, service: MiningForegroundService?) {
        val hashrateStr = if (status.state == MiningStatus.State.Mining) {
            String.format(Locale.US, "%.2f H/s", status.hashrateHs)
        } else {
            "— H/s"
        }
        binding.hashRateValue.text = hashrateStr
        binding.noncesValue.text = status.noncesScanned.toString()
        binding.acceptedSharesValue.text = status.acceptedShares.toString()
        binding.rejectedSharesValue.text = status.rejectedShares.toString()
        binding.root.findViewById<TextView>(R.id.identified_shares_value).text = status.identifiedShares.toString()
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
        binding.noncesValue.text = "0"
        binding.acceptedSharesValue.text = "0"
        binding.rejectedSharesValue.text = "0"
        binding.root.findViewById<TextView>(R.id.identified_shares_value).text = "0"
        binding.miningTimerValue.text = "00:00:00:00"
        binding.batteryTempValue.text = "—"
        binding.hashRateChart.data = null
        binding.hashRateChart.invalidate()
    }

    private fun updateChart(history: List<Double>) {
        if (history.isEmpty()) {
            binding.hashRateChart.data = null
            binding.hashRateChart.invalidate()
            return
        }
        val orange = ContextCompat.getColor(this, R.color.bitcoin_orange)
        val gray = Color.GRAY

        val historyEntries = history.mapIndexed { i, v -> Entry(i.toFloat(), v.toFloat()) }
        val historySet = LineDataSet(historyEntries, "").apply {
            setColor(gray)
            setCircleColor(gray)
            lineWidth = 2f
            setDrawCircles(false)
            setDrawValues(false)
        }

        val average = history.average()
        val avgEntries = history.mapIndexed { i, _ -> Entry(i.toFloat(), average.toFloat()) }
        val avgSet = LineDataSet(avgEntries, "").apply {
            setColor(orange)
            setCircleColor(orange)
            lineWidth = 2f
            setDrawCircles(false)
            setDrawValues(false)
        }

        binding.hashRateChart.data = LineData(historySet, avgSet)
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
