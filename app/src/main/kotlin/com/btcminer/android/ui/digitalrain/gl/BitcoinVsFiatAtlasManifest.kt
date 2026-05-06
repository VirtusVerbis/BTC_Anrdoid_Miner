package com.btcminer.android.ui.digitalrain.gl

import com.btcminer.android.ui.digitalrain.DigitalRainSettings
import kotlin.math.ceil

/**
 * Deterministic manifest for the bundled BVF atlas PNG using the same glyph-resolution path as
 * [RainGlyphAtlas.build], so UV mapping stays aligned with the generated atlas ordering.
 */
object BitcoinVsFiatAtlasManifest {

    const val ASSET_PATH = "ALTAS_BITCOIN_FIAT.png"
    const val GRID_COLS = 16
    const val TEXTURE_WIDTH = 304
    const val TEXTURE_HEIGHT = 60
    const val FALLBACK_CHAR = '?'
    const val BITCOIN_FIXED_INDEX = 39
    const val LIGHTNING_FIXED_INDEX = 40

    data class BuiltManifest(
        val charToUv: Map<Char, FloatArray>,
        val fallbackChar: Char,
        val bodyUvPool: Array<FloatArray>,
        val keyUvPool: Array<FloatArray>,
    )

    fun buildForSettings(
        settings: DigitalRainSettings,
        lineWidthPx: Int,
        letterHeightPx: Int,
    ): BuiltManifest {
        val chars = RainGlyphAtlas.resolvedCharsForSettings(settings, lineWidthPx, letterHeightPx)
        if (chars.isEmpty()) {
            return BuiltManifest(emptyMap(), FALLBACK_CHAR, emptyArray(), emptyArray())
        }

        val rows = ceil(chars.size.toFloat() / GRID_COLS).toInt().coerceAtLeast(1)
        val cellW = TEXTURE_WIDTH / GRID_COLS
        val cellH = TEXTURE_HEIGHT / rows

        val map = LinkedHashMap<Char, FloatArray>(chars.size)
        chars.forEachIndexed { index, ch ->
            val col = index % GRID_COLS
            val row = index / GRID_COLS
            val left = col * cellW
            val top = row * cellH
            val u0 = left.toFloat() / TEXTURE_WIDTH
            val u1 = (left + cellW).toFloat() / TEXTURE_WIDTH
            val v0 = top.toFloat() / TEXTURE_HEIGHT
            val v1 = (top + cellH).toFloat() / TEXTURE_HEIGHT
            map[ch] = floatArrayOf(u0, v0, u1, v1)
        }

        val fallback = if (map.containsKey(FALLBACK_CHAR)) FALLBACK_CHAR else chars.first()
        val fallbackUv = map[fallback] ?: floatArrayOf(0f, 0f, 1f, 1f)
        val bitcoinFixedUv = uvForFixedIndex(BITCOIN_FIXED_INDEX, rows)
        val lightningFixedUv = uvForFixedIndex(LIGHTNING_FIXED_INDEX, rows)
        val keyUvPool = arrayOf(
            bitcoinFixedUv ?: fallbackUv,
            lightningFixedUv ?: fallbackUv,
        )
        val bodyUvPool = chars
            .asSequence()
            .filter { it != fallback }
            .map { map[it] ?: fallbackUv }
            .toList()
            .toTypedArray()

        return BuiltManifest(map, fallback, bodyUvPool, keyUvPool)
    }

    private fun uvForFixedIndex(index: Int, rows: Int): FloatArray? {
        if (index < 0) return null
        val cellCount = GRID_COLS * rows
        if (index >= cellCount) return null
        val cellW = TEXTURE_WIDTH / GRID_COLS
        val cellH = TEXTURE_HEIGHT / rows
        val col = index % GRID_COLS
        val row = index / GRID_COLS
        val left = col * cellW
        val top = row * cellH
        val u0 = left.toFloat() / TEXTURE_WIDTH
        val u1 = (left + cellW).toFloat() / TEXTURE_WIDTH
        val v0 = top.toFloat() / TEXTURE_HEIGHT
        val v1 = (top + cellH).toFloat() / TEXTURE_HEIGHT
        return floatArrayOf(u0, v0, u1, v1)
    }
}
