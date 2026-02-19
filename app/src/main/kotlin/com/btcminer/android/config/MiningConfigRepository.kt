package com.btcminer.android.config

import android.content.Context
import java.lang.Runtime

/**
 * Single source of truth for mining config. All access goes through this repository.
 * Credentials and pool settings are stored encrypted; never log or send off-device.
 */
class MiningConfigRepository(context: Context) {

    private val storage = SecureConfigStorage(context)

    fun getConfig(): MiningConfig = MiningConfig(
        stratumUrl = storage.getStr(SecureConfigStorage.KEY_STRATUM_URL),
        stratumPort = storage.getInt(SecureConfigStorage.KEY_STRATUM_PORT, MiningConfig.DEFAULT_STRATUM_PORT),
        stratumUser = storage.getStr(SecureConfigStorage.KEY_STRATUM_USER),
        stratumPass = storage.getStr(SecureConfigStorage.KEY_STRATUM_PASS),
        bitcoinAddress = storage.getStr(SecureConfigStorage.KEY_BITCOIN_ADDRESS),
        lightningAddress = storage.getStr(SecureConfigStorage.KEY_LIGHTNING_ADDRESS),
        workerName = storage.getStr(SecureConfigStorage.KEY_WORKER_NAME),
        wifiOnly = storage.getBoolean(SecureConfigStorage.KEY_WIFI_ONLY, true),
        mineOnlyWhenCharging = storage.getBoolean(SecureConfigStorage.KEY_MINE_ONLY_WHEN_CHARGING, false),
        maxIntensityPercent = storage.getInt(
            SecureConfigStorage.KEY_MAX_INTENSITY_PERCENT,
            75
        ).coerceIn(MiningConfig.MAX_INTENSITY_MIN, MiningConfig.MAX_INTENSITY_MAX),
        maxWorkerThreads = run {
            val maxCaps = Runtime.getRuntime().availableProcessors()
            storage.getInt(SecureConfigStorage.KEY_MAX_WORKER_THREADS, minOf(4, maxCaps))
                .coerceIn(MiningConfig.MAX_WORKER_THREADS_MIN, maxCaps)
        },
        statusUpdateIntervalMs = storage.getInt(
            SecureConfigStorage.KEY_STATUS_UPDATE_INTERVAL_MS,
            1000
        ).coerceIn(MiningConfig.STATUS_UPDATE_INTERVAL_MIN, MiningConfig.STATUS_UPDATE_INTERVAL_MAX),
        batteryTempFahrenheit = storage.getBoolean(SecureConfigStorage.KEY_BATTERY_TEMP_FAHRENHEIT, false),
        maxBatteryTempC = storage.getInt(
            SecureConfigStorage.KEY_MAX_BATTERY_TEMP_C,
            MiningConfig.BATTERY_TEMP_DEFAULT_C
        ).coerceIn(1, MiningConfig.MAX_BATTERY_TEMP_C),
        autoTuningByBatteryTemp = storage.getBoolean(SecureConfigStorage.KEY_AUTO_TUNING_BY_BATTERY_TEMP, false),
        hashrateTargetHps = storage.getStr(SecureConfigStorage.KEY_HASHRATE_TARGET_HPS).trim()
            .takeIf { it.isNotEmpty() }?.toDoubleOrNull(),
        gpuCores = storage.getInt(SecureConfigStorage.KEY_GPU_CORES, 0)
            .coerceIn(MiningConfig.GPU_CORES_MIN, MiningConfig.GPU_CORES_MAX),
        gpuUtilizationPercent = storage.getInt(SecureConfigStorage.KEY_GPU_UTILIZATION_PERCENT, 75)
            .coerceIn(MiningConfig.GPU_UTILIZATION_MIN, MiningConfig.GPU_UTILIZATION_MAX),
    )

    /** Last throttle sleep (ms) written by auto-tuning (Option B). Clamped when read. */
    fun getAutoTuningLastSleepMs(): Long =
        storage.getLong(SecureConfigStorage.KEY_AUTO_TUNING_LAST_SLEEP_MS, 0L).coerceIn(0L, 60_000L)

    fun setAutoTuningLastSleepMs(ms: Long) {
        storage.putLong(SecureConfigStorage.KEY_AUTO_TUNING_LAST_SLEEP_MS, ms.coerceIn(0L, 60_000L))
    }

    /** Stable in-band learned sleep (ms) for Option C. Clamped when read. */
    fun getAutoTuningLearnedSleepMs(): Long? {
        val v = storage.getLong(SecureConfigStorage.KEY_AUTO_TUNING_LEARNED_SLEEP_MS, -1L)
        return if (v < 0) null else v.coerceIn(0L, 60_000L)
    }

    fun setAutoTuningLearnedSleepMs(ms: Long) {
        storage.putLong(SecureConfigStorage.KEY_AUTO_TUNING_LEARNED_SLEEP_MS, ms.coerceIn(0L, 60_000L))
    }

    /** Returns the stored stratum cert pin for the given host, or null if none. Host should be normalized (no scheme, first segment). */
    fun getStratumPin(host: String): String? =
        storage.getStr(SecureConfigStorage.KEY_STRATUM_PIN_PREFIX + host, "").takeIf { it.isNotBlank() }

    /** Saves the stratum cert pin for the given host in the secure vault (encrypted). */
    fun saveStratumPin(host: String, pin: String) {
        storage.putStr(SecureConfigStorage.KEY_STRATUM_PIN_PREFIX + host, pin)
    }

    fun saveConfig(config: MiningConfig) {
        storage.commitBatch { edit ->
            edit.putString(SecureConfigStorage.KEY_STRATUM_URL, config.stratumUrl)
            edit.putInt(SecureConfigStorage.KEY_STRATUM_PORT, config.stratumPort)
            edit.putString(SecureConfigStorage.KEY_STRATUM_USER, config.stratumUser)
            edit.putString(SecureConfigStorage.KEY_STRATUM_PASS, config.stratumPass)
            edit.putString(SecureConfigStorage.KEY_BITCOIN_ADDRESS, config.bitcoinAddress)
            edit.putString(SecureConfigStorage.KEY_LIGHTNING_ADDRESS, config.lightningAddress)
            edit.putString(SecureConfigStorage.KEY_WORKER_NAME, config.workerName)
            edit.putBoolean(SecureConfigStorage.KEY_WIFI_ONLY, config.wifiOnly)
            edit.putBoolean(SecureConfigStorage.KEY_MINE_ONLY_WHEN_CHARGING, config.mineOnlyWhenCharging)
            edit.putInt(
                SecureConfigStorage.KEY_MAX_INTENSITY_PERCENT,
                config.maxIntensityPercent.coerceIn(MiningConfig.MAX_INTENSITY_MIN, MiningConfig.MAX_INTENSITY_MAX)
            )
            edit.putInt(
                SecureConfigStorage.KEY_STATUS_UPDATE_INTERVAL_MS,
                config.statusUpdateIntervalMs.coerceIn(MiningConfig.STATUS_UPDATE_INTERVAL_MIN, MiningConfig.STATUS_UPDATE_INTERVAL_MAX)
            )
            edit.putInt(
                SecureConfigStorage.KEY_MAX_WORKER_THREADS,
                config.maxWorkerThreads.coerceIn(MiningConfig.MAX_WORKER_THREADS_MIN, Runtime.getRuntime().availableProcessors())
            )
            edit.putBoolean(SecureConfigStorage.KEY_BATTERY_TEMP_FAHRENHEIT, config.batteryTempFahrenheit)
            edit.putInt(
                SecureConfigStorage.KEY_MAX_BATTERY_TEMP_C,
                config.maxBatteryTempC.coerceIn(1, MiningConfig.MAX_BATTERY_TEMP_C)
            )
            edit.putBoolean(SecureConfigStorage.KEY_AUTO_TUNING_BY_BATTERY_TEMP, config.autoTuningByBatteryTemp)
            edit.putString(
                SecureConfigStorage.KEY_HASHRATE_TARGET_HPS,
                config.hashrateTargetHps?.toString() ?: ""
            )
            edit.putInt(
                SecureConfigStorage.KEY_GPU_CORES,
                config.gpuCores.coerceIn(MiningConfig.GPU_CORES_MIN, MiningConfig.GPU_CORES_MAX)
            )
            edit.putInt(
                SecureConfigStorage.KEY_GPU_UTILIZATION_PERCENT,
                config.gpuUtilizationPercent.coerceIn(MiningConfig.GPU_UTILIZATION_MIN, MiningConfig.GPU_UTILIZATION_MAX)
            )
        }
    }
}
