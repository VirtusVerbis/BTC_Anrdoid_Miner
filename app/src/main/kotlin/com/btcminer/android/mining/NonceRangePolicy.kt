package com.btcminer.android.mining

import java.util.Locale

internal data class NonceRange(val start: Long, val endInclusive: Long)

/**
 * Chooses inclusive nonce scan ranges for CPU and GPU workers.
 * Dual mode: lower half CPU, upper half GPU with no overlap at the midpoint.
 * Single-backend mode: full 32-bit nonce space for the active backend.
 */
internal object NonceRangePolicy {
    const val MAX_NONCE = 0xFFFFFFFFL
    /** Inclusive end of CPU lower half when both backends active. */
    const val CPU_NONCE_END = MAX_NONCE / 2
    /** Inclusive start of GPU upper half when both backends active (no overlap with CPU). */
    const val GPU_NONCE_START = CPU_NONCE_END + 1

    fun cpuRange(threadCount: Int, gpuActive: Boolean): NonceRange? {
        if (threadCount <= 0) return null
        return if (gpuActive) {
            NonceRange(0L, CPU_NONCE_END)
        } else {
            NonceRange(0L, MAX_NONCE)
        }
    }

    fun gpuRange(threadCount: Int, gpuActive: Boolean): NonceRange? {
        if (!gpuActive) return null
        return if (threadCount > 0) {
            NonceRange(GPU_NONCE_START, MAX_NONCE)
        } else {
            NonceRange(0L, MAX_NONCE)
        }
    }

    fun formatRangeLog(cpuRange: NonceRange?, gpuRange: NonceRange?): String {
        fun fmt(range: NonceRange?): String =
            range?.let { r ->
                "${formatNonceU32(r.start)}-${formatNonceU32(r.endInclusive)}"
            } ?: "off"
        return "Nonce ranges: CPU=${fmt(cpuRange)} GPU=${fmt(gpuRange)}"
    }

    private fun formatNonceU32(value: Long): String =
        String.format(Locale.US, "%08x", value and 0xFFFFFFFFL)
}
