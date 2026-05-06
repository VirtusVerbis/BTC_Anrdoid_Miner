package com.btcminer.android.ui.digitalrain

import android.graphics.Typeface

object DigitalRainGlyphTypography {

    fun typefaceFor(settings: DigitalRainSettings): Typeface =
        when (settings.glyphAtlasMode) {
            DigitalRainGlyphAtlasMode.MATRIX -> Typeface.MONOSPACE
            DigitalRainGlyphAtlasMode.BITCOIN_VS_FIAT -> Typeface.SANS_SERIF
        }
}
