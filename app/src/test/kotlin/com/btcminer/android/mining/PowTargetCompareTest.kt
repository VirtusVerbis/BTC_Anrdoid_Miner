package com.btcminer.android.mining

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * GPU [hash_meets_target] fast path must match native CPU rule:
 * memcmp(reverse(digest_bytes), target_bytes) <= 0 (see sha256_scan.c / miner.comp).
 */
class PowTargetCompareTest {

    /** Mirror sha256_scan.c / vulkan_miner.c byte-reverse then memcmp <= 0. */
    private fun referenceMeetsTarget(hash: ByteArray, target: ByteArray): Boolean {
        require(hash.size == 32 && target.size == 32)
        val rev = hash.reversedArray()
        for (i in 0 until 32) {
            val d = rev[i].toInt() and 0xff
            val t = target[i].toInt() and 0xff
            if (d != t) return d < t
        }
        return true
    }

    /** SHA state words (big-endian uint32 per word) from 32-byte digest. */
    private fun digestBytesToBeWords(hash: ByteArray): IntArray {
        require(hash.size == 32)
        return IntArray(8) { i ->
            val o = i * 4
            ((hash[o].toInt() and 0xff) shl 24) or
                ((hash[o + 1].toInt() and 0xff) shl 16) or
                ((hash[o + 2].toInt() and 0xff) shl 8) or
                (hash[o + 3].toInt() and 0xff)
        }
    }

    /** Target UBO words: memcpy of 32-byte target (LE per 32-bit word). */
    private fun targetBytesToLeWords(target: ByteArray): IntArray {
        require(target.size == 32)
        return IntArray(8) { i ->
            val o = i * 4
            (target[o].toInt() and 0xff) or
                ((target[o + 1].toInt() and 0xff) shl 8) or
                ((target[o + 2].toInt() and 0xff) shl 16) or
                ((target[o + 3].toInt() and 0xff) shl 24)
        }
    }

    /** Mirror miner.comp bswap32 + 8-word unsigned compare (bswap both digest and target UBO words). */
    private fun fastMeetsTarget(hashWordsBe: IntArray, targetWordsLe: IntArray): Boolean {
        require(hashWordsBe.size == 8 && targetWordsLe.size == 8)
        fun bswap(x: Int): Int = Integer.reverseBytes(x)
        fun u(x: Int): UInt = x.toUInt()

        for (i in 0 until 8) {
            val d = bswap(hashWordsBe[7 - i])
            val t = bswap(targetWordsLe[i])
            if (d != t) return u(d) < u(t)
        }
        return true
    }

    private fun assertBothAgree(hash: ByteArray, target: ByteArray) {
        val ref = referenceMeetsTarget(hash, target)
        val fast = fastMeetsTarget(
            digestBytesToBeWords(hash),
            targetBytesToLeWords(target),
        )
        assertEquals("hash=${hash.joinToString("") { "%02x".format(it) }}", ref, fast)
    }

    @Test
    fun equalHashAndTarget() {
        val bytes = ByteArray(32) { 0x42 }
        assertBothAgree(bytes, bytes)
    }

    @Test
    fun allZeroTarget() {
        val hash = ByteArray(32) { it.toByte() }
        val target = ByteArray(32)
        assertBothAgree(hash, target)
    }

    @Test
    fun allFfTarget() {
        val hash = ByteArray(32) { 0xFF.toByte() }
        val target = ByteArray(32) { 0xFF.toByte() }
        assertBothAgree(hash, target)
    }

    @Test
    fun hashLessAtMsbByte() {
        val hash = ByteArray(32) { 0x01 }
        val target = ByteArray(32) { 0x02 }
        assertTrue(referenceMeetsTarget(hash, target))
        assertBothAgree(hash, target)
    }

    @Test
    fun hashGreaterAtMsbByte() {
        val hash = ByteArray(32) { 0x03 }
        val target = ByteArray(32) { 0x02 }
        assertFalse(referenceMeetsTarget(hash, target))
        assertBothAgree(hash, target)
    }

    @Test
    fun hashLessAtLsbByte() {
        val hash = ByteArray(32)
        val target = ByteArray(32)
        hash[31] = 0x01
        target[31] = 0x02
        assertBothAgree(hash, target)
    }

    @Test
    fun wordBoundaryDifferences() {
        for (word in 0 until 8) {
            val hash = ByteArray(32) { 0x10 }
            val target = ByteArray(32) { 0x10 }
            val byteIndex = word * 4
            hash[byteIndex] = 0x0F
            target[byteIndex] = 0x11
            assertBothAgree(hash, target)

            hash[byteIndex] = 0x12
            target[byteIndex] = 0x11
            assertBothAgree(hash, target)
        }
    }

    @Test
    fun randomPairsMatchReference() {
        val rng = Random(0xB1TC0DE)
        repeat(1000) {
            val hash = ByteArray(32) { rng.nextInt(256).toByte() }
            val target = ByteArray(32) { rng.nextInt(256).toByte() }
            assertBothAgree(hash, target)
        }
    }

    /** Progressive CPU/GPU second-compress rejection is validated in native Python tests (test_target_rejection.py). */
    @Test
    fun diff1TargetWordOrderMatchesReference() {
        val target = ByteArray(32)
        target[0] = 0
        target[1] = 0
        target[2] = 0
        target[3] = 0
        target[4] = 0xFF.toByte()
        target[5] = 0xFF.toByte()
        val hash = ByteArray(32) { 0xFF.toByte() }
        assertFalse(referenceMeetsTarget(hash, target))
        assertBothAgree(hash, target)
    }
}
