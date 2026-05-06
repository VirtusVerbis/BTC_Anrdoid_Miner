package com.btcminer.android.ui.digitalrain

/** Glyph texture/sampling preset for matrix rain and GPU atlas. */
enum class DigitalRainGlyphAtlasMode {
    /** ASCII-range / alphabet-only behavior (legacy Matrix rain). */
    MATRIX,

    /** Unicode ₿, ⚡, and fiat/currency symbols; key heads use only ₿/⚡. */
    BITCOIN_VS_FIAT,
}
