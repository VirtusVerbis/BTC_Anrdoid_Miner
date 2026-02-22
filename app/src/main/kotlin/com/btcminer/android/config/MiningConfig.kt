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
    val autoTuningByBatteryTemp: Boolean = false,
    val hashrateTargetHps: Double? = null,
    val gpuCores: Int = 0,
    val gpuUtilizationPercent: Int = 75,
    val usePartialWakeLock: Boolean = false,
    val miningThreadPriority: Int = 0,
    val alarmWakeIntervalSec: Int = 60,
) {
    fun isValidForMining(): Boolean =
        stratumUrl.isNotBlank() && stratumUser.isNotBlank()

    companion object {
        /** Max lengths for config strings to avoid abuse and storage bloat. */
        const val MAX_STRATUM_URL_LEN = 256
        const val MAX_STRATUM_USER_LEN = 256
        const val MAX_STRATUM_PASS_LEN = 256
        const val MAX_BITCOIN_ADDRESS_LEN = 74
        const val MAX_LIGHTNING_ADDRESS_LEN = 256
        const val MAX_WORKER_NAME_LEN = 64

        /** Remove control characters (ASCII 0–31, 127) and truncate to maxLen. */
        fun sanitize(str: String, maxLen: Int): String =
            str.filter { c -> c.code in 32..126 }.take(maxLen)
        const val MAX_INTENSITY_MIN = 1
        const val MAX_INTENSITY_MAX = 100
        const val MAX_WORKER_THREADS_MIN = 1
        const val DEFAULT_STRATUM_PORT = 3333
        const val STATUS_UPDATE_INTERVAL_MIN = 500
        const val STATUS_UPDATE_INTERVAL_MAX = 30_000
        const val MAX_BATTERY_TEMP_C = 45
        const val BATTERY_TEMP_DEFAULT_C = 30
        const val BATTERY_TEMP_HARD_STOP_C = 43  //anything equal to or over 43C is dangerous
        const val GPU_CORES_MIN = 0
        /** Max workgroup steps (32 * this = local size). Capped by device maxComputeWorkGroupSize/32. */
        const val GPU_CORES_MAX = 64
        const val GPU_UTILIZATION_MIN = 1
        const val GPU_UTILIZATION_MAX = 100
        const val MINING_THREAD_PRIORITY_MIN = -20 //-8
        const val MINING_THREAD_PRIORITY_MAX = 0
        const val ALARM_WAKE_INTERVAL_SEC_MIN = 0
        const val ALARM_WAKE_INTERVAL_SEC_MAX = 60
    }
}
