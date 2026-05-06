/*
 * Shared random glyph selection for Canvas and GLES Digital Rain (must stay in sync).
 */

package com.btcminer.android.ui.digitalrain

import kotlin.random.Random

object DigitalRainGlyphSampling {

    fun randomGlyphString(rng: Random, settings: DigitalRainSettings): String =
        randomGlyphChar(rng, settings).toString()

    fun randomGlyphChar(rng: Random, settings: DigitalRainSettings): Char =
        if (settings.alphabetOnly) {
            randomAbcChar(rng)
        } else {
            if (rng.nextInt(0, 2) == 0) {
                rng.nextInt(settings.asciiRange1Start, settings.asciiRange1End).toChar()
            } else {
                rng.nextInt(settings.asciiRange2Start, settings.asciiRange2End).toChar()
            }
        }

    fun randomAbcChar(rng: Random): Char =
        if (rng.nextInt(0, 2) == 0) {
            rng.nextInt('A'.code, 'Z'.code + 1).toChar()
        } else {
            rng.nextInt('a'.code, 'z'.code + 1).toChar()
        }
}
