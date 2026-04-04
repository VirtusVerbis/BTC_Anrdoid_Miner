package com.btcminer.android.config

/**
 * CPU double-SHA256 implementation for nonce scanning. Ordinal must match native [sha256_scan].
 */
enum class CpuSha256Flavor {
    HW_SHA2_MIDSTATE,
    HW_SHA2,
    NEON4_MIDSTATE,
    NEON4,
    SCALAR_MIDSTATE,
    SCALAR,
    ;

    companion object {
        /** Priority order for [CpuShaCapabilities.coerceToSupported]. */
        val COERCE_PRIORITY: List<CpuSha256Flavor> = listOf(
            HW_SHA2_MIDSTATE,
            HW_SHA2,
            NEON4_MIDSTATE,
            NEON4,
            SCALAR_MIDSTATE,
            SCALAR,
        )

        fun fromOrdinal(ordinal: Int): CpuSha256Flavor? =
            entries.getOrNull(ordinal)
    }
}
