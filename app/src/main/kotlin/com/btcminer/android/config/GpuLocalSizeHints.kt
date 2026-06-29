package com.btcminer.android.config

/**
 * Vendor-specific hints for tuning [MiningConfig.gpuLocalSizeX] (informational only).
 */
object GpuLocalSizeHints {

    fun vendorHint(deviceName: String, driverName: String): String {
        val combined = "$deviceName $driverName".lowercase()
        return when {
            combined.contains("adreno") -> "Adreno: try 64 or 128 threads/workgroup"
            combined.contains("mali") -> "Mali: try 32 or 64 threads/workgroup"
            else -> ""
        }
    }

    fun parseVulkanGpuInfo(info: String?): Pair<String, String> {
        if (info.isNullOrBlank()) return "" to ""
        val parts = info.split('|', limit = 2)
        return parts.getOrElse(0) { "" } to parts.getOrElse(1) { "" }
    }
}
