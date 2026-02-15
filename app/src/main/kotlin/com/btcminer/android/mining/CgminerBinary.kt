package com.btcminer.android.mining

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Resolves the cgminer executable: extracts from assets (per ABI) to app filesDir
 * and returns the File, or null if no binary is present for this device.
 */
object CgminerBinary {

    private const val ASSET_DIR = "cgminer"
    private const val BINARY_NAME = "cgminer"

    /**
     * Returns the path to an executable cgminer binary, or null if not available.
     * On first call for an ABI, copies from assets to filesDir and sets executable.
     */
    fun getExecutablePath(context: Context): File? {
        val abi = getPreferredAbi() ?: return null
        val assetPath = "$ASSET_DIR/$abi/$BINARY_NAME"
        val destFile = File(context.filesDir, "cgminer_$abi")
        if (!destFile.exists()) {
            if (!extractAsset(context, assetPath, destFile)) return null
            destFile.setExecutable(true)
        }
        return if (destFile.canExecute()) destFile else null
    }

    private fun getPreferredAbi(): String? {
        val abis = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS
        } else {
            @Suppress("DEPRECATION")
            arrayOf(Build.CPU_ABI)
        }
        val supported = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        return abis.firstOrNull { supported.contains(it) }
    }

    private fun extractAsset(context: Context, assetPath: String, destFile: File): Boolean {
        return try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
