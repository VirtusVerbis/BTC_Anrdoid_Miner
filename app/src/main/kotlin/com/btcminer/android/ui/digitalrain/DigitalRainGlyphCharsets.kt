package com.btcminer.android.ui.digitalrain

/**
 * Character sets for [DigitalRainGlyphAtlasMode]. Matrix mode uses settings-driven ASCII ranges.
 */
object DigitalRainGlyphCharsets {

    /** Legacy matrix pool from ASCII sliders / alphabet-only (sorted). */
    fun matrixCharsetForSettings(settings: DigitalRainSettings): List<Char> =
        if (settings.alphabetOnly) {
            ('A'..'Z').toList() + ('a'..'z').toList()
        } else {
            buildSet {
                for (c in settings.asciiRange1Start until settings.asciiRange1End) add(c.toChar())
                for (c in settings.asciiRange2Start until settings.asciiRange2End) add(c.toChar())
            }.sorted()
        }

    /** U+20BF Bitcoin sign */
    private const val BITCOIN = '\u20BF'

    /** U+26A1 High voltage / lightning */
    private const val LIGHTNING = '\u26A1'

    /**
     * Key-mode sticky head glyphs when atlas is Bitcoin vs Fiat (not the general matrix pool).
     */
    val keyHighlightHeadCharsBitcoinVsFiat: List<Char> = listOf(BITCOIN, LIGHTNING)

    /**
     * Full matrix pool: ₿, ⚡, common ASCII currency symbols, and Unicode Currency Symbols block subset.
     * Sorted distinct order is applied by callers when building the atlas.
     */
    fun bitcoinVsFiatMatrixChars(): List<Char> = buildList {
        add(BITCOIN)
        add(LIGHTNING)
        add('$')
        add('¢')
        add('¤')
        add('£')
        add('¥')
        add('ƒ')
        add('€')
        // Currency Symbols block (U+20A0–U+20CF) — de-dupe ₿
        for (code in 0x20A0..0x20CF) {
            val c = code.toChar()
            if (c != BITCOIN) add(c)
        }
    }
}
