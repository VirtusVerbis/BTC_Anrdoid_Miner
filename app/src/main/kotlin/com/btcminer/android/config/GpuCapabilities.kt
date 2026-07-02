package com.btcminer.android.config

import com.btcminer.android.mining.NativeMiner

/**
 * Safe wrappers for native Vulkan/GPU capability queries.
 * Prefer this over calling [NativeMiner] directly from app code.
 */
object GpuCapabilities {

    const val VULKAN_ENV_UNKNOWN = -1
    const val VULKAN_ENV_REAL_DEVICE = 0
    const val VULKAN_ENV_EMULATOR = 1

    fun maxLocalSizeX(): Int =
        safeNative { NativeMiner.getMaxGpuLocalSizeX() }
            ?.takeIf { it > 0 }
            ?: MiningConfig.GPU_LOCAL_SIZE_X_FALLBACK_MAX

    fun isVulkanAvailable(): Boolean =
        safeNative { NativeMiner.gpuIsAvailable() } == true

    fun vulkanGpuInfo(): String =
        safeNative { NativeMiner.getVulkanGpuInfo() } ?: "|"

    fun pipelineReady(localSizeX: Int, hashesPerThread: Int, gpuSha256Mode: Int, gpuCompressStyle: Int): Boolean =
        safeNative { NativeMiner.gpuPipelineReady(localSizeX, hashesPerThread, gpuSha256Mode, gpuCompressStyle) } == true

    fun vulkanRuntimeEnv(): Int =
        safeNative { NativeMiner.getVulkanRuntimeEnv() } ?: VULKAN_ENV_UNKNOWN

    private inline fun <T> safeNative(block: () -> T): T? =
        try {
            block()
        } catch (_: Throwable) {
            null
        }
}
