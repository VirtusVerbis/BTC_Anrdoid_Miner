/*
 * Shared random glyph selection for Canvas and GLES Digital Rain (must stay in sync).
 */

package com.btcminer.android.ui.digitalrain

import kotlin.random.Random

object DigitalRainGlyphSampling {

    fun randomGlyphString(rng: Random, settings: DigitalRainSettings): String =
        randomGlyphChar(rng, settings).toString()

    fun randomGlyphChar(rng: Random, settings: DigitalRainSettings): Char =
        when (settings.glyphAtlasMode) {
            DigitalRainGlyphAtlasMode.BITCOIN_VS_FIAT -> {
                val pool = DigitalRainGlyphCharsets.bitcoinVsFiatMatrixChars().distinct()
                pool[rng.nextInt(pool.size)]
            }
            DigitalRainGlyphAtlasMode.MATRIX ->
                if (settings.alphabetOnly) {
                    randomAbcChar(rng)
                } else {
                    if (rng.nextInt(0, 2) == 0) {
                        rng.nextInt(settings.asciiRange1Start, settings.asciiRange1End).toChar()
                    } else {
                        rng.nextInt(settings.asciiRange2Start, settings.asciiRange2End).toChar()
                    }
                }
        }

    /** Sticky key streak head: A–Z under Matrix; ₿ / ⚡ under Bitcoin vs Fiat. */
    fun randomKeyHeadChar(rng: Random, settings: DigitalRainSettings): Char =
        when (settings.glyphAtlasMode) {
            DigitalRainGlyphAtlasMode.MATRIX -> randomAbcChar(rng)
            DigitalRainGlyphAtlasMode.BITCOIN_VS_FIAT -> {
                val heads = DigitalRainGlyphCharsets.keyHighlightHeadCharsBitcoinVsFiat
                heads[rng.nextInt(heads.size)]
            }
        }

    /**
     * Bitcoin vs Fiat key-column streak glyphs (trail + head): independent uniform ₿ / ⚡ per call.
     * Delegates to [randomKeyHeadChar]; Matrix atlas must not use this path for rendering.
     */
    fun randomBitcoinVsFiatKeyStreakChar(rng: Random, settings: DigitalRainSettings): Char =
        randomKeyHeadChar(rng, settings)

    fun randomAbcChar(rng: Random): Char =
        if (rng.nextInt(0, 2) == 0) {
            rng.nextInt('A'.code, 'Z'.code + 1).toChar()
        } else {
            rng.nextInt('a'.code, 'z'.code + 1).toChar()
        }
}
