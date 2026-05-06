/*
 * Tunables for DigitalRainView — mirrored from Eric Nam's Arduino Digital Rain Animation
 * (DigitalRainAnimation.hpp), MIT License:
 * https://github.com/0015/Arduino_DigitalRain_Matrix
 */

package com.btcminer.android.ui.digitalrain

/** Matches Arduino `AnimMode`: MATRIX, TEXT, and SHOWCASE are supported. */
enum class DigitalRainAnimMode {
    MATRIX,
    TEXT,
    SHOWCASE,
}

/**
 * Central defaults for the matrix rain effect. Replace reads from this object with persisted
 * preferences when building the Digital Rain configuration screen.
 */
@Suppress("MemberVisibilityCanBePrivate")
object DigitalRainConstants {

    /** Active animation mode default. */
    val ANIM_MODE: DigitalRainAnimMode = DigitalRainAnimMode.MATRIX

    /** Matrix = ASCII sliders; Bitcoin vs Fiat = Unicode currency / ₿ / ⚡ pool. */
    val GLYPH_ATLAS_MODE: DigitalRainGlyphAtlasMode = DigitalRainGlyphAtlasMode.MATRIX

    // --- Geometry (Arduino DEFAULT_* / setBigText) ---
    const val DEFAULT_LINE_WIDTH = 12
    const val DEFAULT_LETTER_HEIGHT = 14
    /** Arduino `DEFAULT_FONT_SIZE` (GFX scale); used as a secondary scale hint with letter height. */
    const val FONT_SCALE = 2

    /** When true, doubles column width and scales letter height like Arduino `setBigText(true)`. */
    const val USE_BIG_TEXT = false

    /** Arduino `setYPos`: `lineLen * COLUMN_START_Y_MULTIPLIER`. */
    const val COLUMN_START_Y_MULTIPLIER = -20

    // --- Trail dynamics (`setup` / matrix settings) ---
    const val LINE_LEN_MIN = 3
    const val LINE_LEN_MAX = 20
    const val LINE_SPEED_MIN = 3
    const val LINE_SPEED_MAX = 15

    /** Arduino `matrixTimeFrame` — minimum milliseconds between matrix ticks. */
    const val MATRIX_FRAME_MS = 100L

    // --- Colors (Arduino RGB 0–255 → drawn on rain layer only) ---
    const val HEAD_CHAR_R = 255
    const val HEAD_CHAR_G = 255
    const val HEAD_CHAR_B = 255

    const val RAIN_TEXT_R = 0
    const val RAIN_TEXT_G = 255
    const val RAIN_TEXT_B = 0

    const val RAIN_BACKGROUND_R = 0
    const val RAIN_BACKGROUND_G = 0
    const val RAIN_BACKGROUND_B = 0

    // --- Character pools (`getASCIIChar`) ---
    const val ASCII_RANGE_1_START = 33
    const val ASCII_RANGE_1_END = 65
    const val ASCII_RANGE_2_START = 91
    const val ASCII_RANGE_2_END = 126

    /** When true, uses `getAbcASCIIChar()`-style A–Z / a–z only. */
    const val ALPHABET_ONLY = false

    // --- Key / red column mode ---
    const val KEY_RESET_TIME_MS = 60_000L
    const val KEY_LENGTH_COLUMNS = 0
    const val ENABLE_KEY_MODE = false

    /** Key streak stays highlighted until it wraps off-screen (when enabled). */
    const val STICKY_KEY_HIGHLIGHT = true

    /** Depth: scaled streak columns (sticky multiplier until wrap). */
    const val DEPTH_ENABLED = false
    const val DEPTH_STREAK_COUNT = 3
    const val DEPTH_MAX_SCALE_PERCENT = 130

    // --- TEXT / SHOWCASE defaults ---
    const val TEXT_FRAME_MS = 400L
    const val SHOWCASE_MESSAGE = ""
}
