package com.btcminer.android.mining

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.security.MessageDigest

/**
 * Builds 76-byte block header prefix and 32-byte target from Stratum job + difficulty.
 * Merkle root from coinbase + merkle_branch; target from difficulty (2^256 / difficulty approx).
 */
object StratumHeaderBuilder {

    private val sha256 = MessageDigest.getInstance("SHA-256")

    private fun sha256(data: ByteArray): ByteArray = sha256.digest(data)
    private fun doubleSha256(data: ByteArray): ByteArray = sha256(sha256(data))

    /** Decode hex string to byte array (big-endian). */
    fun hexToBytes(hex: String): ByteArray {
        val s = hex.replace(" ", "").lowercase()
        require(s.length % 2 == 0) { "Even length hex required" }
        return ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** Encode bytes to hex. */
    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    /** Reverse byte order (for Bitcoin prevhash/merkle in header). */
    private fun reverseBytes(b: ByteArray): ByteArray = b.reversedArray()

    /**
     * Build merkle root: coinbase = coinb1 + extranonce1 + extranonce2 + coinb2,
     * then double-SHA256(coinbase), then apply merkle_branch.
     */
    fun buildMerkleRoot(
        coinb1Hex: String,
        coinb2Hex: String,
        extranonce1Hex: String,
        extranonce2Hex: String,
        merkleBranchHex: List<String>,
    ): ByteArray {
        val coinb1 = hexToBytes(coinb1Hex)
        val coinb2 = hexToBytes(coinb2Hex)
        val en1 = hexToBytes(extranonce1Hex)
        val en2 = hexToBytes(extranonce2Hex)
        val coinbase = coinb1 + en1 + en2 + coinb2
        var hash = doubleSha256(coinbase)
        for (branchHex in merkleBranchHex) {
            val branch = hexToBytes(branchHex).reversedArray()
            hash = doubleSha256(hash.reversedArray() + branch)
        }
        return hash.reversedArray()
    }

    /** Pad or trim to 4 bytes little-endian from hex. */
    private fun hexTo4BytesLE(hex: String): ByteArray {
        val b = hexToBytes(hex).reversedArray()
        return when {
            b.size >= 4 -> b.copyOfRange(0, 4)
            else -> ByteArray(4) { i -> if (i < b.size) b[i] else 0 }
        }
    }

    /**
     * Build 76-byte header prefix (no nonce). Version, prevhash, merkle_root, ntime, nbits; nonce appended later.
     */
    fun buildHeader76(
        job: StratumJob,
        merkleRoot: ByteArray,
    ): ByteArray {
        require(merkleRoot.size == 32) { "Merkle root must be 32 bytes" }
        val version = hexTo4BytesLE(job.versionHex)
        val prevhash = reverseBytes(hexToBytes(job.prevhashHex)).copyOf(32)
        val ntime = hexTo4BytesLE(job.ntimeHex)
        val nbits = hexTo4BytesLE(job.nbitsHex)
        return version + prevhash + merkleRoot + ntime + nbits
    }

    /**
     * Build 32-byte target from difficulty (big-endian). Pool target = max_target / difficulty,
     * capped at max_target when difficulty < 1 so low-diff pools (e.g. for small miners) work.
     * Bitcoin max target from nbits 0x1d00ffff: 0x00000000ffff0000...
     */
    fun buildTargetFromDifficulty(difficulty: Double): ByteArray {
        val maxTarget = BigInteger("00000000ffff0000000000000000000000000000000000000000000000000000", 16)
        if (difficulty <= 0.0) return bigIntegerToTarget32(maxTarget)
        val targetValue = maxTarget.toBigDecimal()
            .divide(difficulty.toBigDecimal(), 0, RoundingMode.DOWN)
            .toBigInteger()
        val capped = if (targetValue > maxTarget) maxTarget else targetValue
        return bigIntegerToTarget32(capped)
    }

    private fun bigIntegerToTarget32(target: BigInteger): ByteArray {
        val bytes = target.toByteArray()
        return when {
            bytes.size >= 32 -> bytes.takeLast(32).toByteArray()
            bytes.isEmpty() -> ByteArray(32) { 0xff.toByte() }
            else -> ByteArray(32) { i -> if (i < 32 - bytes.size) 0 else bytes[i - (32 - bytes.size)] }
        }
    }

    /** Append nonce (4 bytes, little-endian) to 76-byte header. */
    fun header76WithNonce(header76: ByteArray, nonce: Int): ByteArray {
        require(header76.size == 76)
        val nonceBytes = byteArrayOf(
            (nonce and 0xff).toByte(),
            (nonce shr 8 and 0xff).toByte(),
            (nonce shr 16 and 0xff).toByte(),
            (nonce shr 24 and 0xff).toByte(),
        )
        return header76 + nonceBytes
    }

    /** Bitcoin "difficulty 1" target as double (max_target numeric value). */
    private const val TRUEDIFFONE = 26959535291011309493156476344723991336010898738574164086137773096960.0

    /**
     * Compute share difficulty from 80-byte block header (double-SHA256 hash, then truediffone / hash).
     * Hash is interpreted as little-endian 256-bit value (Bitcoin convention).
     */
    fun difficultyFromHeader80(header80: ByteArray): Double {
        require(header80.size == 80) { "Header must be 80 bytes" }
        val hash = doubleSha256(header80)
        val hashValue = le256ToBigInteger(hash)
        if (hashValue.signum() == 0) return TRUEDIFFONE
        val diff = BigDecimal(TRUEDIFFONE.toString()).divide(BigDecimal(hashValue), 16, RoundingMode.HALF_UP)
        return diff.toDouble()
    }

    /** Interpret 32-byte hash as little-endian 256-bit value. */
    private fun le256ToBigInteger(hash: ByteArray): BigInteger {
        require(hash.size == 32)
        return BigInteger(1, hash.reversedArray())
    }
}
