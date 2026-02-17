package com.btcminer.android.config

/**
 * Mining configuration persisted via [MiningConfigRepository].
 * All credential and pool data stored encrypted (EncryptedSharedPreferences + Keystore).
 */
data class MiningConfig(
    val stratumUrl: String = "",
    val stratumPort: Int = 3333,
    val stratumUser: String = "",
    val stratumPass: String = "",
    val bitcoinAddress: String = "",
    val lightningAddress: String = "",
    val workerName: String = "",
    val wifiOnly: Boolean = true,
    val mineOnlyWhenCharging: Boolean = false,
    val maxIntensityPercent: Int = 75,
    val maxWorkerThreads: Int = 4,
    val statusUpdateIntervalMs: Int = 1000,
    val batteryTempFahrenheit: Boolean = false,
    val maxBatteryTempC: Int = 30,
    val hashrateTargetHps: Double? = null,
    val gpuCores: Int = 0,
    val gpuUtilizationPercent: Int = 75,
) {
    fun isValidForMining(): Boolean =
        stratumUrl.isNotBlank() && stratumUser.isNotBlank()

    companion object {
        const val MAX_INTENSITY_MIN = 1
        const val MAX_INTENSITY_MAX = 100
        const val MAX_WORKER_THREADS_MIN = 1
        const val DEFAULT_STRATUM_PORT = 3333
        const val STATUS_UPDATE_INTERVAL_MIN = 500
        const val STATUS_UPDATE_INTERVAL_MAX = 30_000
        const val MAX_BATTERY_TEMP_C = 45
        const val BATTERY_TEMP_DEFAULT_C = 30
        const val BATTERY_TEMP_HARD_STOP_C = 50
        const val GPU_CORES_MIN = 0
        /** Max workgroup steps (32 * this = local size). Capped by device maxComputeWorkGroupSize/32. */
        const val GPU_CORES_MAX = 64
        const val GPU_UTILIZATION_MIN = 1
        const val GPU_UTILIZATION_MAX = 100
    }
}
