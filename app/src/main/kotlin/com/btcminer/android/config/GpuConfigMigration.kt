package com.btcminer.android.config

/**
 * Pure migration helpers for legacy GPU workgroups / gpu_cores prefs → gpuEnabled + gpuLocalSizeX.
 */
internal object GpuConfigMigration {

    fun resolveGpuEnabled(
        hasGpuEnabledKey: Boolean,
        gpuEnabledStored: Boolean,
        legacyWorkgroups: Int,
        legacyGpuCores: Int,
    ): Boolean = when {
        hasGpuEnabledKey -> gpuEnabledStored
        else -> legacyWorkgroups > 0 || legacyGpuCores > 0
    }

    fun resolveGpuLocalSizeX(
        hasLocalSizeKey: Boolean,
        storedLocalSizeX: Int,
        legacyWorkgroups: Int,
        deviceMax: Int,
    ): Int {
        val raw = when {
            hasLocalSizeKey -> storedLocalSizeX
            legacyWorkgroups > 0 -> legacyWorkgroups * MiningConfig.GPU_LOCAL_SIZE_X_STEP
            else -> MiningConfig.GPU_LOCAL_SIZE_X_DEFAULT
        }
        return MiningConfig.clampGpuLocalSizeX(raw, deviceMax)
    }
}
