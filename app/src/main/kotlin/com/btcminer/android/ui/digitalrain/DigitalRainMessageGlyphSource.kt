package com.btcminer.android.ui.digitalrain

object DigitalRainMessageGlyphSource {
    fun sanitizeMessage(raw: String): String = raw.ifBlank { " " }

    /** Character at logical index, wrapping for continuous TEXT rain (empty-safe via sanitized message). */
    fun messageCharLooped(message: String, index: Int): Char {
        val len = message.length.coerceAtLeast(1)
        val i = ((index % len) + len) % len
        return message[i]
    }

    fun nextGlyphOrSpace(message: String, cursor: Int): Pair<Char, Int> {
        if (cursor < 0) return ' ' to 0
        if (cursor >= message.length) return ' ' to cursor
        return message[cursor] to (cursor + 1)
    }
}
