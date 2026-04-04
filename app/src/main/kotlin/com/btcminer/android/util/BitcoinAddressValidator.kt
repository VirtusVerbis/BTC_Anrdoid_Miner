package com.btcminer.android.util

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Validates Bitcoin addresses with Base58Check (legacy P2PKH / P2SH) and Bech32 / Bech32m
 * (BIP-0173 / BIP-0350) checksum verification. Mainnet and common testnet prefix forms are accepted.
 */
object BitcoinAddressValidator {
    private const val BASE58_CHARS = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private const val BECH32_DATA_CHARS = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    private val BASE58_SPLIT = BigInteger.valueOf(58L)

    /** Min/max length for base58 addresses (P2PKH / P2SH decoded payload + checksum). */
    private const val BASE58_MIN_LEN = 25
    private const val BASE58_MAX_LEN = 35

    private const val BECH32_MIN_LEN = 14
    private const val BECH32_MAX_LEN = 90

    private val BECH32_GEN = intArrayOf(
        0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3
    )
    private const val BECH32_POLYMOD_OK = 1
    private const val BECH32M_POLYMOD_OK = 0x2bc830a3

    private const val VERSION_MAIN_P2PKH = 0x00
    private const val VERSION_MAIN_P2SH = 0x05
    private const val VERSION_TEST_P2PKH = 0x6f
    private const val VERSION_TEST_P2SH = 0xc4

    /**
     * If [stratumUser] uses the common `payout.worker` form and the segment before the first `.`
     * looks like a Bitcoin address, returns that segment for validation; otherwise null (pool-specific
     * usernames are valid and are not checked).
     */
    fun stratumPayoutAddressCandidate(stratumUser: String): String? {
        val u = stratumUser.trim()
        if (u.isEmpty()) return null
        val head = u.substringBefore('.')
        if (head.isEmpty()) return null
        if (!looksLikeBitcoinAddress(head)) return null
        return head
    }

    /**
     * True if [s] plausibly encodes a Bitcoin address (used to decide whether Stratum `addr.worker`
     * deserves a checksum check). Bech32-style strings are detected by prefix; legacy Base58 is
     * gated by length and charset so pool usernames like `myworker` do not match.
     */
    fun looksLikeBitcoinAddress(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return false
        return when {
            t.startsWith("bc1", ignoreCase = true) || t.startsWith("tb1", ignoreCase = true) -> true
            t[0] == '1' || t[0] == '3' ||
                t[0] == 'm' || t[0] == 'n' || t[0] == '2' ||
                t[0] == 'M' || t[0] == 'N' ->
                t.length in BASE58_MIN_LEN..BASE58_MAX_LEN && t.all { it in BASE58_CHARS }
            else -> false
        }
    }

    /**
     * Full checksum validation (Base58Check, Bech32, Bech32m). Mainnet and testnet (tb1 / m n 2 …) accepted.
     */
    fun isValidAddress(address: String): Boolean {
        val s = address.trim()
        if (s.isEmpty()) return false
        return when {
            s.startsWith("bc1", ignoreCase = true) || s.startsWith("tb1", ignoreCase = true) ->
                isValidSegwitAddress(s)
            s[0] == '1' || s[0] == '3' || s[0] == 'm' || s[0] == 'n' || s[0] == '2' || s[0] == 'M' || s[0] == 'N' ->
                isValidBase58CheckAddress(s)
            else -> false
        }
    }

    /**
     * Same as [isValidAddress] (kept for call sites that only need “safe for API / display” gating).
     */
    fun isValidFormat(address: String): Boolean = isValidAddress(address)

    private fun isValidSegwitMixedCase(addr: String): Boolean {
        var hasLower = false
        var hasUpper = false
        for (c in addr) {
            if (c.isLowerCase()) hasLower = true
            else if (c.isUpperCase()) hasUpper = true
            if (hasLower && hasUpper) return false
        }
        return true
    }

    private fun isValidSegwitAddress(addr: String): Boolean {
        if (addr.length !in BECH32_MIN_LEN..BECH32_MAX_LEN) return false
        if (!isValidSegwitMixedCase(addr)) return false
        val lower = addr.lowercase()
        val pos = lower.lastIndexOf('1')
        if (pos < 1) return false
        val hrp = lower.substring(0, pos)
        if (hrp != "bc" && hrp != "tb") return false
        val dataPart = lower.substring(pos + 1)
        if (dataPart.length < 6) return false
        if (!dataPart.all { it in BECH32_DATA_CHARS }) return false
        val values = IntArray(dataPart.length)
        for (i in dataPart.indices) {
            values[i] = BECH32_DATA_CHARS.indexOf(dataPart[i])
        }
        val enc = verifyBech32Checksum(hrp, values) ?: return false
        val dataHuman = values.copyOfRange(0, values.size - 6)
        if (dataHuman.isEmpty()) return false
        val witVer = dataHuman[0]
        if (witVer > 16) return false
        val program5 = dataHuman.copyOfRange(1, dataHuman.size)
        val program = convertBits(program5, 5, 8, false) ?: return false
        if (program.size < 2 || program.size > 40) return false
        if (witVer == 0 && program.size != 20 && program.size != 32) return false
        if (witVer == 0 && enc != BECH32_POLYMOD_OK) return false
        if (witVer != 0 && enc != BECH32M_POLYMOD_OK) return false
        return when (witVer) {
            0 -> true
            1 -> program.size == 32
            in 2..16 -> program.size in 2..40
            else -> false
        }
    }

    private fun verifyBech32Checksum(hrp: String, data: IntArray): Int? {
        val p = polymod(hrpExpand(hrp) + data)
        return when (p) {
            BECH32_POLYMOD_OK -> BECH32_POLYMOD_OK
            BECH32M_POLYMOD_OK -> BECH32M_POLYMOD_OK
            else -> null
        }
    }

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val b = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) {
                chk = chk xor if ((b ushr i) and 1 != 0) BECH32_GEN[i] else 0
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val out = IntArray(hrp.length * 2 + 1)
        var i = 0
        for (c in hrp) {
            out[i++] = c.code shr 5
        }
        out[i++] = 0
        for (c in hrp) {
            out[i++] = c.code and 31
        }
        return out
    }

    /**
     * Converts from `fromBits` to `toBits` (BIP173 convertBits). Returns null if invalid padding.
     */
    private fun convertBits(data: IntArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray? {
        var acc = 0
        var bits = 0
        val maxv = (1 shl toBits) - 1
        val maxAcc = (1 shl (fromBits + toBits - 1)) - 1
        val out = ArrayList<Byte>(data.size * fromBits / toBits + 1)
        for (value in data) {
            if (value shr fromBits != 0) return null
            acc = ((acc shl fromBits) or value) and maxAcc
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                out.add(((acc ushr bits) and maxv).toByte())
            }
        }
        if (pad) {
            if (bits > 0) out.add(((acc shl (toBits - bits)) and maxv).toByte())
        } else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxv) != 0) {
            return null
        }
        return out.toByteArray()
    }

    private fun isValidBase58CheckAddress(s: String): Boolean {
        if (s.length !in BASE58_MIN_LEN..BASE58_MAX_LEN) return false
        if (!s.all { it in BASE58_CHARS }) return false
        val decoded = decodeBase58(s) ?: return false
        if (decoded.size != 25) return false
        val payload = decoded.copyOfRange(0, 21)
        val checksum = decoded.copyOfRange(21, 25)
        val check = doubleSha256(payload)
        if (!check.copyOfRange(0, 4).contentEquals(checksum)) return false
        val v = payload[0].toInt() and 0xff
        if (v != VERSION_MAIN_P2PKH && v != VERSION_MAIN_P2SH &&
            v != VERSION_TEST_P2PKH && v != VERSION_TEST_P2SH
        ) {
            return false
        }
        return true
    }

    private fun decodeBase58(input: String): ByteArray? {
        if (input.isEmpty()) return null
        var num = BigInteger.ZERO
        for (c in input) {
            val digit = BASE58_CHARS.indexOf(c)
            if (digit < 0) return null
            num = num.multiply(BASE58_SPLIT).add(BigInteger.valueOf(digit.toLong()))
        }
        var leadingOnes = 0
        for (c in input) {
            if (c != '1') break
            leadingOnes++
        }
        var bytes = num.toByteArray()
        if (bytes.isNotEmpty() && bytes[0] == 0.toByte()) {
            bytes = bytes.copyOfRange(1, bytes.size)
        }
        return ByteArray(leadingOnes).plus(bytes)
    }

    private fun doubleSha256(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val first = md.digest(data)
        md.reset()
        return md.digest(first)
    }
}
