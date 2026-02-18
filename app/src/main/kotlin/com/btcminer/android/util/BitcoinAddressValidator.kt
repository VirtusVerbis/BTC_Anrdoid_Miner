package com.btcminer.android.util

/**
 * Validates Bitcoin address format before using in API calls (e.g. mempool.space balance).
 * Supports legacy (P2PKH, base58), P2SH (base58), and Bech32/Bech32m (SegWit, bc1...).
 */
object BitcoinAddressValidator {
    private const val BASE58_CHARS = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private const val BECH32_DATA_CHARS = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    /** Min/max length for base58 addresses (P2PKH typically 33-34, P2SH 34). */
    private const val BASE58_MIN_LEN = 25
    private const val BASE58_MAX_LEN = 35

    /** Bech32: "bc1" (3) + at least 1 data char + 6 chars for separator and checksum. */
    private const val BECH32_MIN_LEN = 14
    private const val BECH32_MAX_LEN = 74

    /**
     * Returns true if [address] looks like a valid Bitcoin address (length and charset only).
     * Does not verify checksums; sufficient to avoid obviously wrong input before calling the API.
     */
    fun isValidFormat(address: String): Boolean {
        val s = address.trim()
        if (s.isEmpty()) return false
        return when {
            s.startsWith("bc1") -> isValidBech32(s)
            s[0] == '1' || s[0] == '3' || s[0] == 'm' || s[0] == 'n' || s[0] == '2' || s[0] == 'M' || s[0] == 'N' -> isValidBase58(s)
            else -> false
        }
    }

    private fun isValidBase58(s: String): Boolean {
        if (s.length !in BASE58_MIN_LEN..BASE58_MAX_LEN) return false
        return s.all { it in BASE58_CHARS }
    }

    private fun isValidBech32(s: String): Boolean {
        if (s.length !in BECH32_MIN_LEN..BECH32_MAX_LEN) return false
        if (s.lowercase() != s && s.uppercase() != s) return false // mixed case invalid
        val lower = s.lowercase()
        if (!lower.startsWith("bc1")) return false
        val dataPart = lower.drop(3)
        return dataPart.all { it in BECH32_DATA_CHARS }
    }
}
