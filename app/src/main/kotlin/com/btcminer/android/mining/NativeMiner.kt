package com.btcminer.android.mining

/**
 * Outcome of a CPU nonce scan ([nativeScanNoncesInto]). Status values match CPU JNI in [miner.c] only.
 */
data class CpuNonceScanResult(val status: Int, val nonceU32: Long) {
    val isHit: Boolean get() = status == HIT

    companion object {
        const val MISS = 0
        const val HIT = 1
        const val INTERRUPTED = -3
        const val FLAVOR_ERROR = -4
        const val JNI_ARG_ERROR = -5

        fun fromJniOut(out: LongArray): CpuNonceScanResult {
            require(out.size >= 2) { "CPU scan JNI out[] length >= 2" }
            return CpuNonceScanResult(out[0].toInt(), out[1])
        }
    }
}

/**
 * Outcome of a GPU nonce scan ([gpuScanNoncesInto]). Status values match GPU JNI in [vulkan_miner.c] only.
 */
data class GpuNonceScanResult(val status: Int, val nonceU32: Long) {
    val isHit: Boolean get() = status == HIT

    companion object {
        const val MISS = 0
        const val HIT = 1
        const val UNAVAILABLE = -2

        fun fromJniOut(out: LongArray): GpuNonceScanResult {
            require(out.size >= 2) { "GPU scan JNI out[] length >= 2" }
            return GpuNonceScanResult(out[0].toInt(), out[1])
        }
    }
}

/**
 * Native miner (Option A1). Loads libminer.so and exposes JNI functions.
 * Phase 1: trivial version call. Phase 2: SHA-256 + block header hash.
 */
object NativeMiner {

    init {
        System.loadLibrary("miner")
    }

    /**
     * Returns the native miner library version. Used to verify Phase 1 (JNI load + call).
     */
    external fun nativeVersion(): String

    /**
     * Runs a known SHA-256 test vector (NIST "abc"). Returns true if the implementation is correct.
     */
    external fun nativeTestSha256(): Boolean

    /**
     * Double SHA-256 of an 80-byte block header. Returns 32-byte hash or null if header length != 80.
     */
    external fun nativeHashBlockHeader(header: ByteArray): ByteArray?

    /** True when AArch64 reports SHA256 hardware support (AT_HWCAP HWCAP_SHA2). */
    external fun nativeHwcapSha2(): Boolean

    /** Verify double-SHA256 for [flavor] against scalar reference on fixed test vectors. */
    external fun nativeSelfTestCpuSha256Flavor(flavor: Int): Boolean

    /** Host-only: midstate vs full double-SHA for test header; logs GPU_SHA_SelfTest. */
    external fun gpuShaHostSelftest(): Boolean

    /** Vulkan SSBO readback vs CPU first/final SHA for test header. [useMidstate] 0 = full, 1 = midstate path. */
    external fun gpuShaVulkanSelftest(useMidstate: Int): Boolean

    /**
     * CPU nonce scan: writes [CpuNonceScanResult] wire format into [out] — `out[0]` = status, `out[1]` = winning
     * nonce as [Long] in `0..0xFFFFFFFFL` when status is [CpuNonceScanResult.HIT].
     * @param flavor [com.btcminer.android.config.CpuSha256Flavor.ordinal], 0..5.
     */
    external fun nativeScanNoncesInto(
        header76: ByteArray,
        nonceStart: Int,
        nonceEnd: Int,
        target: ByteArray,
        flavor: Int,
        out: LongArray,
    )

    /** @see CpuNonceScanResult.FLAVOR_ERROR */
    const val CPU_SHA_FLAVOR_ERROR = -4

    /**
     * Requests the GPU worker to interrupt. When set, [gpuScanNoncesInto] reports unavailable on the next
     * vkWaitForFences timeout (within ~1s). Used by the stuck-worker watchdog.
     */
    external fun gpuRequestInterrupt(): Unit

    /**
     * Requests CPU workers to interrupt. When set, [nativeScanNoncesInto] reports interrupted on its next
     * 64k-iteration check. Used by the stuck-worker watchdog.
     */
    external fun cpuRequestInterrupt(): Unit

    /**
     * Whether Vulkan is available for GPU compute. When true, [gpuScanNoncesInto] can be used.
     */
    external fun gpuIsAvailable(): Boolean

    /**
     * Maximum compute workgroup size for the device (maxComputeWorkGroupSize[0]).
     * Returns 0 when Vulkan is not available or not yet initialized.
     */
    external fun getMaxComputeWorkGroupSize(): Int

    /**
     * True only when the Vulkan compute pipeline for the given [gpuCores] can be created
     * (SPIR-V present and pipeline creation succeeds). When false, [gpuScanNoncesInto] would report unavailable;
     * use this to fail fast at mining start instead of on first chunk.
     * @param gpuSha256Mode [com.btcminer.android.config.GpuSha256Mode.ordinal].
     */
    external fun gpuPipelineReady(gpuCores: Int, gpuSha256Mode: Int): Boolean

    /**
     * GPU nonce scan: writes [GpuNonceScanResult] wire format into [out] — `out[0]` = status, `out[1]` = winning
     * nonce as [Long] in `0..0xFFFFFFFFL` when status is [GpuNonceScanResult.HIT] (including `0xFFFFFFFFL` as a valid hit).
     * @param gpuSha256Mode [com.btcminer.android.config.GpuSha256Mode.ordinal].
     */
    external fun gpuScanNoncesInto(
        header76: ByteArray,
        nonceStart: Int,
        nonceEnd: Int,
        target: ByteArray,
        gpuCores: Int,
        gpuSha256Mode: Int,
        out: LongArray,
    )
}
