package com.btcminer.android.config

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Rect
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.btcminer.android.AppLog
import com.btcminer.android.R
import com.btcminer.android.databinding.ActivityConfigBinding
import com.btcminer.android.mining.MiningForegroundService
import com.btcminer.android.mining.NativeMiner
import com.btcminer.android.network.StratumPinCapture
import com.google.android.material.slider.Slider
import java.lang.Runtime

class ConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfigBinding
    private lateinit var repository: MiningConfigRepository
    private var loadedConfig: MiningConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = MiningConfigRepository(applicationContext)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.config_title)

        loadConfig()
        binding.configSliderCores.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
            binding.configCoresValue.text = "${value.toInt()}"
        })
        binding.configSliderIntensity.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
            binding.configMaxIntensityValue.text = "${value.toInt()}%"
        })
        binding.configSliderStatusInterval.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
            binding.configStatusIntervalValue.text = "${value.toInt()} ms"
        })
        binding.configSliderBatteryTemp.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
            binding.configBatteryTempValue.text = "${value.toInt()} °C"
        })
        binding.configSliderGpuCores.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
            binding.configGpuCoresValue.text = "${value.toInt()}"
        })
        binding.configSliderGpuUtilization.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
            binding.configGpuUtilizationValue.text = "${value.toInt()}%"
        })
        binding.configSave.setOnClickListener { saveConfig() }
        binding.configSaveFloating.setOnClickListener { saveConfig() }
        binding.configResetCounters.setOnClickListener { showResetCountersDialog() }
        updateFloatingButtonVisibility()
        binding.configScroll.viewTreeObserver.addOnGlobalLayoutListener {
            updateFloatingButtonVisibility()
        }
        binding.configScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            updateFloatingButtonVisibility()
        }
    }

    private fun updateFloatingButtonVisibility() {
        val rect = Rect()
        val inFlowVisible = binding.configSave.getLocalVisibleRect(rect)
        binding.configSaveFloating.visibility = if (inFlowVisible) View.GONE else View.VISIBLE
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun showResetCountersDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_counters_dialog_title)
            .setMessage(R.string.reset_counters_dialog_message)
            .setPositiveButton(R.string.reset_counters_yes) { _, _ ->
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        (binder as? MiningForegroundService.LocalBinder)?.getService()?.resetAllCounters()
                        try { unbindService(this) } catch (_: Exception) { }
                    }
                    override fun onServiceDisconnected(name: ComponentName?) {}
                }
                bindService(
                    Intent(this, MiningForegroundService::class.java),
                    conn,
                    Context.BIND_AUTO_CREATE
                )
            }
            .setNegativeButton(R.string.reset_counters_no) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun loadConfig() {
        val c = repository.getConfig()
        binding.configStratumUrl.editText?.setText(c.stratumUrl)
        binding.configStratumPort.editText?.setText(if (c.stratumPort > 0) c.stratumPort.toString() else "")
        binding.configStratumUser.editText?.setText(c.stratumUser)
        binding.configStratumPass.editText?.setText(c.stratumPass)
        binding.configBitcoinAddress.editText?.setText(c.bitcoinAddress)
        binding.configLightningAddress.editText?.setText(c.lightningAddress)
        binding.configWorkerName.editText?.setText(c.workerName)
        binding.configWifiOnly.isChecked = c.wifiOnly
        binding.configMineOnlyWhenCharging.isChecked = c.mineOnlyWhenCharging
        binding.configBatteryTempFahrenheit.isChecked = c.batteryTempFahrenheit
        val batteryTempC = c.maxBatteryTempC.coerceIn(20, MiningConfig.MAX_BATTERY_TEMP_C)
        binding.configSliderBatteryTemp.value = batteryTempC.toFloat()
        binding.configBatteryTempValue.text = "$batteryTempC °C"
        binding.configHashrateTarget.editText?.setText(c.hashrateTargetHps?.toString() ?: "")
        val maxCores = Runtime.getRuntime().availableProcessors()
        binding.configSliderCores.valueTo = maxCores.toFloat()
        val cores = c.maxWorkerThreads.coerceIn(MiningConfig.MAX_WORKER_THREADS_MIN, maxCores)
        binding.configSliderCores.value = cores.toFloat()
        binding.configCoresValue.text = "$cores"
        binding.configSliderIntensity.value = c.maxIntensityPercent.toFloat()
        binding.configMaxIntensityValue.text = "${c.maxIntensityPercent}%"
        val maxWorkGroupSize = NativeMiner.getMaxComputeWorkGroupSize()
        val gpuMaxSteps = if (maxWorkGroupSize == 0) 8 else (maxWorkGroupSize / 32).coerceAtLeast(1).coerceAtMost(MiningConfig.GPU_CORES_MAX)
        binding.configSliderGpuCores.valueTo = gpuMaxSteps.toFloat()
        val gpuCores = c.gpuCores.coerceIn(MiningConfig.GPU_CORES_MIN, gpuMaxSteps)
        binding.configSliderGpuCores.value = gpuCores.toFloat()
        binding.configGpuCoresValue.text = "$gpuCores"
        binding.configSliderGpuUtilization.value = c.gpuUtilizationPercent.coerceIn(MiningConfig.GPU_UTILIZATION_MIN, MiningConfig.GPU_UTILIZATION_MAX).toFloat()
        binding.configGpuUtilizationValue.text = "${c.gpuUtilizationPercent}%"
        val statusMs = c.statusUpdateIntervalMs.coerceIn(MiningConfig.STATUS_UPDATE_INTERVAL_MIN, MiningConfig.STATUS_UPDATE_INTERVAL_MAX)
        binding.configSliderStatusInterval.value = statusMs.toFloat()
        binding.configStatusIntervalValue.text = "$statusMs ms"
        loadedConfig = c
    }

    private fun saveConfig() {
        val stratumUrlRaw = binding.configStratumUrl.editText?.text?.toString()?.trim() ?: ""
        val port = binding.configStratumPort.editText?.text?.toString()?.toIntOrNull() ?: MiningConfig.DEFAULT_STRATUM_PORT
        val portCoerced = port.coerceIn(1, 65535)
        val useTls = stratumUrlRaw.lowercase().contains("ssl") || portCoerced == 443
        val pinThisPoolChecked = binding.configPinThisPool.isChecked

        val config = MiningConfig(
            stratumUrl = MiningConfig.sanitize(stratumUrlRaw, MiningConfig.MAX_STRATUM_URL_LEN),
            stratumPort = portCoerced,
            stratumUser = MiningConfig.sanitize(binding.configStratumUser.editText?.text?.toString()?.trim() ?: "", MiningConfig.MAX_STRATUM_USER_LEN),
            stratumPass = MiningConfig.sanitize(binding.configStratumPass.editText?.text?.toString() ?: "", MiningConfig.MAX_STRATUM_PASS_LEN),
            bitcoinAddress = MiningConfig.sanitize(binding.configBitcoinAddress.editText?.text?.toString()?.trim() ?: "", MiningConfig.MAX_BITCOIN_ADDRESS_LEN),
            lightningAddress = MiningConfig.sanitize(binding.configLightningAddress.editText?.text?.toString()?.trim() ?: "", MiningConfig.MAX_LIGHTNING_ADDRESS_LEN),
            workerName = MiningConfig.sanitize(binding.configWorkerName.editText?.text?.toString()?.trim() ?: "", MiningConfig.MAX_WORKER_NAME_LEN),
            wifiOnly = binding.configWifiOnly.isChecked,
            mineOnlyWhenCharging = binding.configMineOnlyWhenCharging.isChecked,
            batteryTempFahrenheit = binding.configBatteryTempFahrenheit.isChecked,
            maxBatteryTempC = binding.configSliderBatteryTemp.value.toInt().coerceIn(1, MiningConfig.MAX_BATTERY_TEMP_C),
            hashrateTargetHps = binding.configHashrateTarget.editText?.text?.toString()?.trim()?.toDoubleOrNull(),
            maxIntensityPercent = binding.configSliderIntensity.value.toInt().coerceIn(
                MiningConfig.MAX_INTENSITY_MIN,
                MiningConfig.MAX_INTENSITY_MAX
            ),
            maxWorkerThreads = binding.configSliderCores.value.toInt().coerceIn(
                MiningConfig.MAX_WORKER_THREADS_MIN,
                Runtime.getRuntime().availableProcessors()
            ),
            statusUpdateIntervalMs = binding.configSliderStatusInterval.value.toInt().coerceIn(
                MiningConfig.STATUS_UPDATE_INTERVAL_MIN,
                MiningConfig.STATUS_UPDATE_INTERVAL_MAX
            ),
            gpuCores = binding.configSliderGpuCores.value.toInt().coerceIn(MiningConfig.GPU_CORES_MIN, binding.configSliderGpuCores.valueTo.toInt()),
            gpuUtilizationPercent = binding.configSliderGpuUtilization.value.toInt().coerceIn(
                MiningConfig.GPU_UTILIZATION_MIN,
                MiningConfig.GPU_UTILIZATION_MAX
            ),
        )

        if (pinThisPoolChecked && config.stratumUrl.isNotBlank() && useTls) {
            val host = StratumPinCapture.normalizeHost(config.stratumUrl)
            if (host.isBlank()) {
                Toast.makeText(this, R.string.config_could_not_pin_certificate, Toast.LENGTH_LONG).show()
                return
            }
            val progressDialog = AlertDialog.Builder(this)
                .setMessage(R.string.config_pinning_pool_certificate)
                .setCancelable(false)
                .show()
            Thread {
                try {
                    val pin = StratumPinCapture.capturePin(host, config.stratumPort)
                    runOnUiThread {
                        progressDialog.dismiss()
                        repository.saveStratumPin(host, pin)
                        binding.configPinThisPool.isChecked = false
                        repository.saveConfig(config)
                        startService(Intent(this@ConfigActivity, MiningForegroundService::class.java).apply {
                            action = MiningForegroundService.ACTION_RESTART
                        })
                        Toast.makeText(this@ConfigActivity, R.string.config_saved, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                } catch (e: Exception) {
                    val errMsg = e.message
                    AppLog.e("Config") { "Pin certificate failed: $errMsg" }
                    runOnUiThread {
                        progressDialog.dismiss()
                        val toastText = getString(R.string.config_could_not_pin_certificate) + "\n" + (errMsg ?: "Unknown error")
                        Toast.makeText(this@ConfigActivity, toastText, Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
            return
        }

        if (config == loadedConfig) {
            finish()
            return
        }
        repository.saveConfig(config)
        startService(Intent(this, MiningForegroundService::class.java).apply {
            action = MiningForegroundService.ACTION_RESTART
        })
        Toast.makeText(this, R.string.config_saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}
