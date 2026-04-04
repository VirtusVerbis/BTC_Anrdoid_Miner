package com.btcminer.android.config

import android.os.Build
import com.btcminer.android.mining.NativeMiner

/**
 * Device support for CPU SHA flavors. Primary ABI should match loaded `libminer.so`.
 */
object CpuShaCapabilities {

    val hasNeon4Build: Boolean
        get() {
            val primary = Build.SUPPORTED_64_BIT_ABIS.firstOrNull()
                ?: Build.SUPPORTED_ABIS.firstOrNull()
            return primary == "arm64-v8a"
        }

    val hasHwSha2: Boolean
        get() = try {
            NativeMiner.nativeHwcapSha2()
        } catch (_: Throwable) {
            false
        }

    fun isSelectable(flavor: CpuSha256Flavor): Boolean = when (flavor) {
        CpuSha256Flavor.HW_SHA2_MIDSTATE, CpuSha256Flavor.HW_SHA2 ->
            hasNeon4Build && hasHwSha2
        CpuSha256Flavor.NEON4_MIDSTATE, CpuSha256Flavor.NEON4 ->
            hasNeon4Build
        CpuSha256Flavor.SCALAR_MIDSTATE, CpuSha256Flavor.SCALAR ->
            true
    }

    /** Pick first flavor in priority order that is [isSelectable], else [CpuSha256Flavor.SCALAR]. */
    fun coerceToSupported(requested: CpuSha256Flavor): CpuSha256Flavor {
        if (isSelectable(requested)) return requested
        return CpuSha256Flavor.COERCE_PRIORITY.firstOrNull { isSelectable(it) }
            ?: CpuSha256Flavor.SCALAR
    }
}
