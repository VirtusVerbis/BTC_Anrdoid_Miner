package com.btcminer.android.config

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.btcminer.android.R
import com.btcminer.android.databinding.ActivityConfigBinding
import com.btcminer.android.mining.MiningForegroundService
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
        binding.configSave.setOnClickListener { saveConfig() }
        binding.configSaveFloating.setOnClickListener { saveConfig() }
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
        val statusMs = c.statusUpdateIntervalMs.coerceIn(MiningConfig.STATUS_UPDATE_INTERVAL_MIN, MiningConfig.STATUS_UPDATE_INTERVAL_MAX)
        binding.configSliderStatusInterval.value = statusMs.toFloat()
        binding.configStatusIntervalValue.text = "$statusMs ms"
        loadedConfig = c
    }

    private fun saveConfig() {
        val port = binding.configStratumPort.editText?.text?.toString()?.toIntOrNull() ?: MiningConfig.DEFAULT_STRATUM_PORT
        val config = MiningConfig(
            stratumUrl = binding.configStratumUrl.editText?.text?.toString()?.trim() ?: "",
            stratumPort = port.coerceIn(1, 65535),
            stratumUser = binding.configStratumUser.editText?.text?.toString()?.trim() ?: "",
            stratumPass = binding.configStratumPass.editText?.text?.toString() ?: "",
            bitcoinAddress = binding.configBitcoinAddress.editText?.text?.toString()?.trim() ?: "",
            lightningAddress = binding.configLightningAddress.editText?.text?.toString()?.trim() ?: "",
            workerName = binding.configWorkerName.editText?.text?.toString()?.trim() ?: "",
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
        )
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
