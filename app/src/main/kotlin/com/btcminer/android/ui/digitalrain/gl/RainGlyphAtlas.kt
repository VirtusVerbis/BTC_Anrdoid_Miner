/*
 * Monospace glyph atlas for GLES Digital Rain (matches Canvas font metrics).
 */

package com.btcminer.android.ui.digitalrain.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import com.btcminer.android.ui.digitalrain.DigitalRainSettings
import kotlin.math.ceil
import kotlin.math.max

object RainGlyphAtlas {

    private const val TAG = "RainGlyphAtlas"
    const val GRID_COLS = 16
    private const val CELL_PAD_PX = 2

    data class BuiltAtlas(
        val bitmap: Bitmap,
        val charToUv: Map<Char, FloatArray>,
        val fallbackChar: Char,
    )

    /** Characters that may appear in matrix cells (plus fallback glyph). */
    fun charsetForSettings(settings: DigitalRainSettings): List<Char> =
        if (settings.alphabetOnly) {
            ('A'..'Z').toList() + ('a'..'z').toList()
        } else {
            buildSet {
                for (c in settings.asciiRange1Start until settings.asciiRange1End) add(c.toChar())
                for (c in settings.asciiRange2Start until settings.asciiRange2End) add(c.toChar())
            }.sorted()
        }

    fun glyphTextSizePx(settings: DigitalRainSettings, lineWidthPx: Int, letterHeightPx: Int): Float =
        max(
            letterHeightPx * 0.92f,
            lineWidthPx * (
                if (settings.useBigText) settings.fontScale * 0.45f
                else settings.fontScale * 0.42f
                ),
        )

    /**
     * @param maxTextureSize from GLES GL_MAX_TEXTURE_SIZE; rejects oversize atlases.
     */
    fun build(
        settings: DigitalRainSettings,
        lineWidthPx: Int,
        letterHeightPx: Int,
        maxTextureSize: Int,
    ): BuiltAtlas? {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            style = Paint.Style.FILL
            color = Color.WHITE
            textSize = glyphTextSizePx(settings, lineWidthPx, letterHeightPx)
        }
        val fm = paint.fontMetrics

        val baseChars = charsetForSettings(settings)
        val chars = (baseChars + '?').distinct().sorted()
        if (chars.isEmpty()) {
            Log.e(TAG, "empty charset")
            return null
        }

        var maxTextW = 0f
        for (ch in chars) {
            val s = ch.toString()
            maxTextW = max(maxTextW, paint.measureText(s))
        }
        val minCellH = (-fm.ascent + fm.descent)
        val cellW = max(lineWidthPx.toFloat(), ceil(maxTextW) + CELL_PAD_PX * 2).toInt().coerceAtLeast(1)
        val cellH = max(letterHeightPx.toFloat(), ceil(minCellH) + CELL_PAD_PX * 2).toInt().coerceAtLeast(1)

        val rows = ceil(chars.size.toFloat() / GRID_COLS).toInt().coerceAtLeast(1)
        val texW = cellW * GRID_COLS
        val texH = cellH * rows

        if (texW > maxTextureSize || texH > maxTextureSize) {
            Log.e(TAG, "Atlas ${texW}x${texH} exceeds GL_MAX_TEXTURE_SIZE $maxTextureSize")
            return null
        }

        val bitmap = Bitmap.createBitmap(texW, texH, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)

        val charToUv = mutableMapOf<Char, FloatArray>()
        chars.forEachIndexed { index, ch ->
            val col = index % GRID_COLS
            val row = index / GRID_COLS
            val left = col * cellW
            val top = row * cellH
            val baseline = top - fm.ascent + CELL_PAD_PX
            val chStr = ch.toString()
            val textWidth = paint.measureText(chStr)
            val x = left + (cellW - textWidth) / 2f
            canvas.drawText(chStr, 0, 1, x, baseline, paint)

            val u0 = left.toFloat() / texW
            val u1 = (left + cellW).toFloat() / texW
            val v0 = top.toFloat() / texH
            val v1 = (top + cellH).toFloat() / texH
            charToUv[ch] = floatArrayOf(u0, v0, u1, v1)
        }

        val fallbackChar = if (chars.contains('?')) '?' else chars.first()
        return BuiltAtlas(bitmap, charToUv, fallbackChar)
    }
}
