package com.btcminer.android.ui.digitalrain

import android.graphics.Paint

/**
 * Filters candidate characters by what the given [Paint] can actually draw on Canvas,
 * avoiding tofu / zero-width glyphs for CPU Digital Rain (Bitcoin vs Fiat).
 *
 * [Paint.hasGlyph] requires API 23; app minSdk is 24.
 */
object DigitalRainGlyphFontSupport {

    private const val MIN_RENDERABLE_WIDTH_PX = 0.5f

    /** When the full BVF charset yields nothing renderable, try these (then `'$'`). */
    private val SAFE_CURRENCY_FALLBACKS = listOf('$', '€', '£', '¥', '?')

    fun filterRenderableChars(paint: Paint, candidates: Iterable<Char>): List<Char> = buildList {
        for (ch in candidates) {
            if (ch.isWhitespace() || ch.isISOControl()) continue
            val s = ch.toString()
            if (!paint.hasGlyph(s)) continue
            if (paint.measureText(s) <= MIN_RENDERABLE_WIDTH_PX) continue
            add(ch)
        }
    }

    fun toBodyPoolWithFallback(paint: Paint, filtered: List<Char>): CharArray {
        if (filtered.isNotEmpty()) {
            return filtered.distinct().toCharArray()
        }
        val fromSafe = filterRenderableChars(paint, SAFE_CURRENCY_FALLBACKS)
        if (fromSafe.isNotEmpty()) {
            return fromSafe.toCharArray()
        }
        return charArrayOf('$')
    }

    /**
     * Key streak uses ₿ / ⚡ when supported; otherwise first [bodyPool] glyph, else ASCII fallback chain.
     */
    fun toKeyPoolWithFallback(
        paint: Paint,
        filteredKeys: List<Char>,
        bodyPool: CharArray,
    ): CharArray {
        if (filteredKeys.isNotEmpty()) {
            return filteredKeys.distinct().toCharArray()
        }
        if (bodyPool.isNotEmpty()) {
            return charArrayOf(bodyPool[0])
        }
        return toBodyPoolWithFallback(paint, emptyList())
    }
}
