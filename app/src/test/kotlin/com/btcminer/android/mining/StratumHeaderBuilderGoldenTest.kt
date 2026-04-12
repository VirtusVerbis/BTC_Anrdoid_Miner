package com.btcminer.android.mining

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.security.MessageDigest

/** Golden vectors for Stratum header / share difficulty (public-pool DifficultyUtils + bitcoinjs ordering). */
class StratumHeaderBuilderGoldenTest {

    @Test
    fun normalize8Hex_padsShortValues() {
        assertEquals("00003039", StratumHeaderBuilder.normalize8Hex("3039"))
        assertEquals("20000000", StratumHeaderBuilder.normalize8Hex("20000000"))
    }

    @Test
    fun swapEndianWords32_isInvolution_matchesPublicPoolNotifyEncoding() {
        val wire = ByteArray(32) { ((it * 17 + 3) and 0xff).toByte() }
        val asNotify = StratumHeaderBuilder.swapEndianWords32(wire)
        val roundTrip = StratumHeaderBuilder.swapEndianWords32(asNotify)
        assertArrayEquals(wire, roundTrip)
    }

    @Test
    fun difficultyFromHeader80_matchesLeBufferTruediffone() {
        val hex80 =
            "5a5b58595e5f5c5d52535051565754554a4b48494e4f4c4d42434041464744457a7b78797e7f7c7d72737071767774756a6b68696e6f6c6d62636061666764651a1b18191e1f1c1d1213101116171415"
        require(hex80.length == 160)
        val header80 = StratumHeaderBuilder.hexToBytes(hex80)
        val sha256 = MessageDigest.getInstance("SHA-256")
        fun doubleSha256(data: ByteArray): ByteArray {
            val first = sha256.digest(data)
            return sha256.digest(first)
        }
        val hash = doubleSha256(header80)
        val hashInt = BigInteger(1, hash.reversedArray())
        val truediffone = BigInteger("26959535291011309493156476344723991336010898738574164086137773096960")
        val expected = BigDecimal(truediffone).divide(BigDecimal(hashInt), 16, RoundingMode.HALF_UP).toDouble()
        val got = StratumHeaderBuilder.difficultyFromHeader80(header80)
        assertEquals(expected, got, 1e-12)
    }

    @Test
    fun networkDifficultyFromNbitsHex_genesisStyle_isOne() {
        // 0x1d00ffff → difficulty-1 max target; network difficulty = 1.0 (public-pool compact nBits formula).
        val got = StratumHeaderBuilder.networkDifficultyFromNbitsHex("1d00ffff")
        assertEquals(1.0, got!!, 1e-9)
    }
}
