package com.btcminer.android.mining

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
     * Scan nonce range for a block header prefix. Returns winning nonce, -1 if none meets target (or JNI
     * validation error), **-3** ([CPU_INTERRUPTED]) when interrupted, **-4** ([CPU_SHA_FLAVOR_ERROR])
     * when flavor/dispatch fails — treat -4 like -3 for worker exit and do not count full chunk scanned.
     * @param flavor [com.btcminer.android.config.CpuSha256Flavor.ordinal], 0..5.
     */
    external fun nativeScanNonces(header76: ByteArray, nonceStart: Int, nonceEnd: Int, target: ByteArray, flavor: Int): Int

    /** Returned when CPU SHA flavor path fails in native (invalid flavor, ARM/NEON error). See [nativeScanNonces]. */
    const val CPU_SHA_FLAVOR_ERROR = -4

    /**
     * Requests the GPU worker to interrupt. When set, [gpuScanNonces] will return -2 on its next
     * vkWaitForFences timeout (within ~1s). Used by the stuck-worker watchdog.
     */
    external fun gpuRequestInterrupt(): Unit

    /**
     * Requests CPU workers to interrupt. When set, [nativeScanNonces] will return -3 on its next
     * 64k-iteration check. Used by the stuck-worker watchdog.
     */
    external fun cpuRequestInterrupt(): Unit

    /**
     * Whether Vulkan is available for GPU compute. When true, [gpuScanNonces] can be used.
     */
    external fun gpuIsAvailable(): Boolean

    /**
     * Maximum compute workgroup size for the device (maxComputeWorkGroupSize[0]).
     * Returns 0 when Vulkan is not available or not yet initialized.
     */
    external fun getMaxComputeWorkGroupSize(): Int

    /**
     * True only when the Vulkan compute pipeline for the given [gpuCores] can be created
     * (SPIR-V present and pipeline creation succeeds). When false, [gpuScanNonces] would return -2;
     * use this to fail fast at mining start instead of on first chunk.
     * @param gpuSha256Mode [com.btcminer.android.config.GpuSha256Mode.ordinal].
     */
    external fun gpuPipelineReady(gpuCores: Int, gpuSha256Mode: Int): Boolean

    /**
     * Scan nonce range on GPU path. Returns winning nonce, -1 if no solution in chunk, or -2 if
     * GPU path is unavailable (Vulkan/SPIR-V or dispatch failed; no CPU fallback). [gpuCores] (1..N)
     * sets local workgroup size (32 * gpuCores, capped by device max) for parallelism.
     * @param gpuSha256Mode [com.btcminer.android.config.GpuSha256Mode.ordinal].
     */
    external fun gpuScanNonces(
        header76: ByteArray,
        nonceStart: Int,
        nonceEnd: Int,
        target: ByteArray,
        gpuCores: Int,
        gpuSha256Mode: Int,
    ): Int
}
