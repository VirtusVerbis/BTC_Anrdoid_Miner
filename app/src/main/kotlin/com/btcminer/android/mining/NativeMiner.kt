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
     * Scan nonce range on GPU path. Same contract as [nativeScanNonces]. Returns winning nonce or -1.
     * When Vulkan is not available or compute shader fails, falls back to CPU. [gpuCores] (1-8) sets
     * local workgroup size (32 * gpuCores, capped by device) for parallelism.
     */
    external fun gpuScanNonces(header76: ByteArray, nonceStart: Int, nonceEnd: Int, target: ByteArray, gpuCores: Int): Int
}
