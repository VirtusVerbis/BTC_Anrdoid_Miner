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

    /**
     * Scan nonce range for a block header prefix. Returns winning nonce or -1 if none meets target.
     * @param header76 76-byte block header (version, prevhash, merkle_root, ntime, nbits; no nonce).
     * @param nonceStart first nonce (inclusive).
     * @param nonceEnd last nonce (inclusive).
     * @param target 32-byte pool target (big-endian); hash must be <= target.
     */
    external fun nativeScanNonces(header76: ByteArray, nonceStart: Int, nonceEnd: Int, target: ByteArray): Int

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
     */
    external fun gpuPipelineReady(gpuCores: Int): Boolean

    /**
     * Scan nonce range on GPU path. Returns winning nonce, -1 if no solution in chunk, or -2 if
     * GPU path is unavailable (Vulkan/SPIR-V or dispatch failed; no CPU fallback). [gpuCores] (1..N)
     * sets local workgroup size (32 * gpuCores, capped by device max) for parallelism.
     */
    external fun gpuScanNonces(header76: ByteArray, nonceStart: Int, nonceEnd: Int, target: ByteArray, gpuCores: Int): Int
}
