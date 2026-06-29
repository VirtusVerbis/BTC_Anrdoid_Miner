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
    val cpuUsageTargetPercent: Int? = null,
    /** When true, GPU mining is enabled via Vulkan compute. */
    val gpuEnabled: Boolean = false,
    /** Threads per workgroup (local_size_x); used only when [gpuEnabled]. */
    val gpuLocalSizeX: Int = GPU_LOCAL_SIZE_X_DEFAULT,
    /** Scalar hashes each GPU thread runs per invocation (1, 2, 4, or 8). */
    val gpuHashesPerThread: Int = GPU_HASHES_PER_THREAD_DEFAULT,
    val gpuUtilizationPercent: Int = 75,
    val usePartialWakeLock: Boolean = false,
    val useLegacyAlarm: Boolean = false,
    val miningThreadPriority: Int = 0,
    val alarmWakeIntervalSec: Int = 60,
    val cpuSha256Flavor: CpuSha256Flavor = CpuSha256Flavor.SCALAR,
    val gpuSha256Mode: GpuSha256Mode = GpuSha256Mode.GPU_FULL,
) {
    fun isValidForMining(): Boolean =
        stratumUrl.isNotBlank() && stratumUser.isNotBlank()

    /** True when at least one of CPU worker threads or GPU mining is configured. */
    fun hasActiveHashingConfig(): Boolean = maxWorkerThreads > 0 || gpuEnabled

    /** Clamp [gpuLocalSizeX] to device limits (step 32). */
    fun clampedGpuLocalSizeX(deviceMax: Int): Int =
        clampGpuLocalSizeX(gpuLocalSizeX, deviceMax)

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
        /** Max delay (ms) per chunk when intensity is 0. 600_000 = 10 minutes. */
        const val INTENSITY_MAX_DELAY_MS = 600_000L
        /** Delay (ms) per chunk for given intensity percent. 0% = 10 min, 100% = 0. */
        fun intensityDelayMs(percent: Int): Long {
            if (percent >= 100) return 0L
            return (100 - percent.coerceIn(0, 99)) * INTENSITY_MAX_DELAY_MS / 100
        }
        const val MAX_INTENSITY_MIN = 0
        const val MAX_INTENSITY_MAX = 100
        const val MAX_WORKER_THREADS_MIN = 0
        const val DEFAULT_STRATUM_PORT = 3333
        const val STATUS_UPDATE_INTERVAL_MIN = 500
        const val STATUS_UPDATE_INTERVAL_MAX = 30_000
        const val MAX_BATTERY_TEMP_C = 45
        const val BATTERY_TEMP_DEFAULT_C = 30
        const val BATTERY_TEMP_HARD_STOP_C = 43  //anything equal to or over 43C is dangerous
        const val GPU_LOCAL_SIZE_X_MIN = 32
        const val GPU_LOCAL_SIZE_X_DEFAULT = 32
        const val GPU_LOCAL_SIZE_X_STEP = 32
        /** Upper bound when Vulkan max is unknown (64 steps × 32). */
        const val GPU_LOCAL_SIZE_X_FALLBACK_MAX = GPU_LOCAL_SIZE_X_STEP * 64

        /** Round down to step 32 and clamp to [GPU_LOCAL_SIZE_X_MIN, deviceMax aligned]. */
        fun clampGpuLocalSizeX(value: Int, deviceMax: Int): Int {
            val maxAligned = (deviceMax.coerceAtLeast(GPU_LOCAL_SIZE_X_MIN) / GPU_LOCAL_SIZE_X_STEP) * GPU_LOCAL_SIZE_X_STEP
            val clamped = value.coerceIn(GPU_LOCAL_SIZE_X_MIN, maxAligned.coerceAtLeast(GPU_LOCAL_SIZE_X_MIN))
            return (clamped / GPU_LOCAL_SIZE_X_STEP) * GPU_LOCAL_SIZE_X_STEP
        }

        val GPU_HASHES_PER_THREAD_OPTIONS = intArrayOf(1, 2, 4, 8)
        const val GPU_HASHES_PER_THREAD_DEFAULT = 1

        fun clampGpuHashesPerThread(value: Int): Int =
            GPU_HASHES_PER_THREAD_OPTIONS.minByOrNull { kotlin.math.abs(it - value) }
                ?: GPU_HASHES_PER_THREAD_DEFAULT

        fun gpuHashesPerThreadFromSliderIndex(index: Int): Int =
            GPU_HASHES_PER_THREAD_OPTIONS.getOrElse(index.coerceIn(0, GPU_HASHES_PER_THREAD_OPTIONS.lastIndex)) {
                GPU_HASHES_PER_THREAD_DEFAULT
            }

        fun gpuHashesPerThreadSliderIndex(value: Int): Int {
            val clamped = clampGpuHashesPerThread(value)
            return GPU_HASHES_PER_THREAD_OPTIONS.indexOf(clamped).coerceAtLeast(0)
        }

        const val CPU_USAGE_TARGET_MIN = 1
        const val CPU_USAGE_TARGET_MAX = 100
        const val GPU_UTILIZATION_MIN = 0
        const val GPU_UTILIZATION_MAX = 100
        const val MINING_THREAD_PRIORITY_MIN = -20 //-8
        const val MINING_THREAD_PRIORITY_MAX = 0
        const val ALARM_WAKE_INTERVAL_SEC_MIN = 0
        const val ALARM_WAKE_INTERVAL_SEC_MAX = 900
    }
}
