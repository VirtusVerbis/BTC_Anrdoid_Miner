package com.btcminer.android.ui.digitalrain

import android.content.Context

/**
 * Plain SharedPreferences for Digital Rain UI tuning (non-sensitive).
 */
class DigitalRainSettingsRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): DigitalRainSettings {
        val d = DigitalRainSettings.defaults()
        val animOrdinal = prefs.getInt(KEY_ANIM_MODE, d.animMode.ordinal)
            .coerceIn(0, DigitalRainAnimMode.entries.lastIndex)
        val animMode = DigitalRainAnimMode.entries[animOrdinal]
        val atlasOrdinal = prefs.getInt(KEY_GLYPH_ATLAS_MODE, d.glyphAtlasMode.ordinal)
            .coerceIn(0, DigitalRainGlyphAtlasMode.entries.lastIndex)
        val glyphAtlasMode = DigitalRainGlyphAtlasMode.entries[atlasOrdinal]
        return normalize(
            DigitalRainSettings(
                animMode = animMode,
                glyphAtlasMode = glyphAtlasMode,
                defaultLineWidth = prefs.getInt(KEY_LINE_WIDTH, d.defaultLineWidth),
                defaultLetterHeight = prefs.getInt(KEY_LETTER_HEIGHT, d.defaultLetterHeight),
                fontScale = prefs.getInt(KEY_FONT_SCALE, d.fontScale),
                useBigText = prefs.getBoolean(KEY_USE_BIG_TEXT, d.useBigText),
                columnStartYMultiplier = prefs.getInt(KEY_COLUMN_Y_MULT, d.columnStartYMultiplier),
                lineLenMin = prefs.getInt(KEY_LINE_LEN_MIN, d.lineLenMin),
                lineLenMax = prefs.getInt(KEY_LINE_LEN_MAX, d.lineLenMax),
                lineSpeedMin = prefs.getInt(KEY_LINE_SPEED_MIN, d.lineSpeedMin),
                lineSpeedMax = prefs.getInt(KEY_LINE_SPEED_MAX, d.lineSpeedMax),
                matrixFrameMs = prefs.getLong(KEY_MATRIX_FRAME_MS, d.matrixFrameMs),
                headCharR = prefs.getInt(KEY_HEAD_R, d.headCharR),
                headCharG = prefs.getInt(KEY_HEAD_G, d.headCharG),
                headCharB = prefs.getInt(KEY_HEAD_B, d.headCharB),
                rainTextR = prefs.getInt(KEY_RAIN_R, d.rainTextR),
                rainTextG = prefs.getInt(KEY_RAIN_G, d.rainTextG),
                rainTextB = prefs.getInt(KEY_RAIN_B, d.rainTextB),
                rainBackgroundR = prefs.getInt(KEY_BG_R, d.rainBackgroundR),
                rainBackgroundG = prefs.getInt(KEY_BG_G, d.rainBackgroundG),
                rainBackgroundB = prefs.getInt(KEY_BG_B, d.rainBackgroundB),
                asciiRange1Start = prefs.getInt(KEY_ASCII_1_START, d.asciiRange1Start),
                asciiRange1End = prefs.getInt(KEY_ASCII_1_END, d.asciiRange1End),
                asciiRange2Start = prefs.getInt(KEY_ASCII_2_START, d.asciiRange2Start),
                asciiRange2End = prefs.getInt(KEY_ASCII_2_END, d.asciiRange2End),
                alphabetOnly = prefs.getBoolean(KEY_ALPHABET_ONLY, d.alphabetOnly),
                keyResetTimeMs = prefs.getLong(KEY_KEY_RESET_MS, d.keyResetTimeMs),
                keyLengthColumns = prefs.getInt(KEY_KEY_LENGTH_COLS, d.keyLengthColumns),
                enableKeyMode = prefs.getBoolean(KEY_ENABLE_KEY_MODE, d.enableKeyMode),
                stickyKeyHighlight = prefs.getBoolean(KEY_STICKY_KEY_HIGHLIGHT, d.stickyKeyHighlight),
                bitcoinOrangeKeyHighlight = prefs.getBoolean(KEY_BITCOIN_ORANGE_KEY_HIGHLIGHT, d.bitcoinOrangeKeyHighlight),
                depthEnabled = prefs.getBoolean(KEY_DEPTH_ENABLED, d.depthEnabled),
                depthStreakCount = prefs.getInt(KEY_DEPTH_STREAK_COUNT, d.depthStreakCount),
                depthMaxScalePercent = prefs.getInt(KEY_DEPTH_MAX_SCALE_PERCENT, d.depthMaxScalePercent),
                textFrameMs = prefs.getLong(KEY_TEXT_FRAME_MS, d.textFrameMs),
                showcaseMessage = prefs.getString(KEY_SHOWCASE_MESSAGE, d.showcaseMessage)
                    ?: d.showcaseMessage,
            ),
        )
    }

    fun save(settings: DigitalRainSettings) {
        val n = normalize(settings)
        prefs.edit().apply {
            putInt(KEY_ANIM_MODE, n.animMode.ordinal)
            putInt(KEY_GLYPH_ATLAS_MODE, n.glyphAtlasMode.ordinal)
            putInt(KEY_LINE_WIDTH, n.defaultLineWidth)
            putInt(KEY_LETTER_HEIGHT, n.defaultLetterHeight)
            putInt(KEY_FONT_SCALE, n.fontScale)
            putBoolean(KEY_USE_BIG_TEXT, n.useBigText)
            putInt(KEY_COLUMN_Y_MULT, n.columnStartYMultiplier)
            putInt(KEY_LINE_LEN_MIN, n.lineLenMin)
            putInt(KEY_LINE_LEN_MAX, n.lineLenMax)
            putInt(KEY_LINE_SPEED_MIN, n.lineSpeedMin)
            putInt(KEY_LINE_SPEED_MAX, n.lineSpeedMax)
            putLong(KEY_MATRIX_FRAME_MS, n.matrixFrameMs)
            putInt(KEY_HEAD_R, n.headCharR)
            putInt(KEY_HEAD_G, n.headCharG)
            putInt(KEY_HEAD_B, n.headCharB)
            putInt(KEY_RAIN_R, n.rainTextR)
            putInt(KEY_RAIN_G, n.rainTextG)
            putInt(KEY_RAIN_B, n.rainTextB)
            putInt(KEY_BG_R, n.rainBackgroundR)
            putInt(KEY_BG_G, n.rainBackgroundG)
            putInt(KEY_BG_B, n.rainBackgroundB)
            putInt(KEY_ASCII_1_START, n.asciiRange1Start)
            putInt(KEY_ASCII_1_END, n.asciiRange1End)
            putInt(KEY_ASCII_2_START, n.asciiRange2Start)
            putInt(KEY_ASCII_2_END, n.asciiRange2End)
            putBoolean(KEY_ALPHABET_ONLY, n.alphabetOnly)
            putLong(KEY_KEY_RESET_MS, n.keyResetTimeMs)
            putInt(KEY_KEY_LENGTH_COLS, n.keyLengthColumns)
            putBoolean(KEY_ENABLE_KEY_MODE, n.enableKeyMode)
            putBoolean(KEY_STICKY_KEY_HIGHLIGHT, n.stickyKeyHighlight)
            putBoolean(KEY_BITCOIN_ORANGE_KEY_HIGHLIGHT, n.bitcoinOrangeKeyHighlight)
            putBoolean(KEY_DEPTH_ENABLED, n.depthEnabled)
            putInt(KEY_DEPTH_STREAK_COUNT, n.depthStreakCount)
            putInt(KEY_DEPTH_MAX_SCALE_PERCENT, n.depthMaxScalePercent)
            putLong(KEY_TEXT_FRAME_MS, n.textFrameMs)
            putString(KEY_SHOWCASE_MESSAGE, n.showcaseMessage)
            apply()
        }
    }

    fun resetToDefaults(): DigitalRainSettings {
        val d = normalize(DigitalRainSettings.defaults())
        save(d)
        return d
    }

    companion object {
        private const val PREFS_NAME = "digital_rain_settings"

        private const val KEY_ANIM_MODE = "anim_mode"
        private const val KEY_GLYPH_ATLAS_MODE = "glyph_atlas_mode"
        private const val KEY_LINE_WIDTH = "line_width"
        private const val KEY_LETTER_HEIGHT = "letter_height"
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_USE_BIG_TEXT = "use_big_text"
        private const val KEY_COLUMN_Y_MULT = "column_y_mult"
        private const val KEY_LINE_LEN_MIN = "line_len_min"
        private const val KEY_LINE_LEN_MAX = "line_len_max"
        private const val KEY_LINE_SPEED_MIN = "line_speed_min"
        private const val KEY_LINE_SPEED_MAX = "line_speed_max"
        private const val KEY_MATRIX_FRAME_MS = "matrix_frame_ms"
        private const val KEY_HEAD_R = "head_r"
        private const val KEY_HEAD_G = "head_g"
        private const val KEY_HEAD_B = "head_b"
        private const val KEY_RAIN_R = "rain_r"
        private const val KEY_RAIN_G = "rain_g"
        private const val KEY_RAIN_B = "rain_b"
        private const val KEY_BG_R = "bg_r"
        private const val KEY_BG_G = "bg_g"
        private const val KEY_BG_B = "bg_b"
        private const val KEY_ASCII_1_START = "ascii_1_start"
        private const val KEY_ASCII_1_END = "ascii_1_end"
        private const val KEY_ASCII_2_START = "ascii_2_start"
        private const val KEY_ASCII_2_END = "ascii_2_end"
        private const val KEY_ALPHABET_ONLY = "alphabet_only"
        private const val KEY_KEY_RESET_MS = "key_reset_ms"
        private const val KEY_KEY_LENGTH_COLS = "key_length_cols"
        private const val KEY_ENABLE_KEY_MODE = "enable_key_mode"
        private const val KEY_STICKY_KEY_HIGHLIGHT = "sticky_key_highlight"
        private const val KEY_BITCOIN_ORANGE_KEY_HIGHLIGHT = "bitcoin_orange_key_highlight"
        private const val KEY_DEPTH_ENABLED = "depth_enabled"
        private const val KEY_DEPTH_STREAK_COUNT = "depth_streak_count"
        private const val KEY_DEPTH_MAX_SCALE_PERCENT = "depth_max_scale_percent"
        private const val KEY_TEXT_FRAME_MS = "text_frame_ms"
        private const val KEY_SHOWCASE_MESSAGE = "showcase_message"

        /** Clamp and fix inconsistent pairs (ASCII ranges, trail bounds). */
        fun normalize(s: DigitalRainSettings): DigitalRainSettings {
            val lineLenMin = s.lineLenMin.coerceIn(LINE_LEN_ABS_MIN, LINE_LEN_ABS_MAX)
            val lineLenMax = s.lineLenMax.coerceIn(LINE_LEN_ABS_MIN, LINE_LEN_ABS_MAX)
            val (loLen, hiLen) = if (lineLenMin <= lineLenMax) lineLenMin to lineLenMax else lineLenMax to lineLenMin

            val speedMin = s.lineSpeedMin.coerceIn(SPEED_ABS_MIN, SPEED_ABS_MAX)
            val speedMax = s.lineSpeedMax.coerceIn(SPEED_ABS_MIN, SPEED_ABS_MAX)
            val (loSp, hiSp) = if (speedMin <= speedMax) speedMin to speedMax else speedMax to speedMin

            /** `endExclusive` upper bound for [kotlin.random.Random.nextInt] (exclusive). */
            fun clampAscii(start: Int, endExclusive: Int): Pair<Int, Int> {
                var st = start.coerceIn(ASCII_MIN_CODE, ASCII_MAX_CODE)
                var ex = endExclusive.coerceIn(st + 1, ASCII_MAX_CODE + 1)
                if (ex <= st) ex = (st + 1).coerceAtMost(ASCII_MAX_CODE + 1)
                return st to ex
            }

            val (r1s, r1e) = clampAscii(s.asciiRange1Start, s.asciiRange1End)
            val (r2s, r2e) = clampAscii(s.asciiRange2Start, s.asciiRange2End)

            val msg = s.showcaseMessage.take(SHOWCASE_MESSAGE_MAX_LEN)
            val atlasOrdinal = s.glyphAtlasMode.ordinal.coerceIn(0, DigitalRainGlyphAtlasMode.entries.lastIndex)
            val glyphAtlasMode = DigitalRainGlyphAtlasMode.entries[atlasOrdinal]

            return s.copy(
                glyphAtlasMode = glyphAtlasMode,
                defaultLineWidth = s.defaultLineWidth.coerceIn(LINE_WIDTH_MIN, LINE_WIDTH_MAX),
                defaultLetterHeight = s.defaultLetterHeight.coerceIn(LETTER_HEIGHT_MIN, LETTER_HEIGHT_MAX),
                fontScale = s.fontScale.coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX),
                columnStartYMultiplier = s.columnStartYMultiplier.coerceIn(COLUMN_MULT_MIN, COLUMN_MULT_MAX),
                lineLenMin = loLen,
                lineLenMax = hiLen,
                lineSpeedMin = loSp,
                lineSpeedMax = hiSp,
                matrixFrameMs = s.matrixFrameMs.coerceIn(MATRIX_FRAME_MIN_MS, MATRIX_FRAME_MAX_MS),
                headCharR = s.headCharR.coerceIn(0, 255),
                headCharG = s.headCharG.coerceIn(0, 255),
                headCharB = s.headCharB.coerceIn(0, 255),
                rainTextR = s.rainTextR.coerceIn(0, 255),
                rainTextG = s.rainTextG.coerceIn(0, 255),
                rainTextB = s.rainTextB.coerceIn(0, 255),
                rainBackgroundR = s.rainBackgroundR.coerceIn(0, 255),
                rainBackgroundG = s.rainBackgroundG.coerceIn(0, 255),
                rainBackgroundB = s.rainBackgroundB.coerceIn(0, 255),
                asciiRange1Start = r1s,
                asciiRange1End = r1e,
                asciiRange2Start = r2s,
                asciiRange2End = r2e,
                keyResetTimeMs = s.keyResetTimeMs.coerceIn(KEY_RESET_MIN_MS, KEY_RESET_MAX_MS),
                keyLengthColumns = s.keyLengthColumns.coerceIn(0, KEY_LENGTH_COLS_MAX),
                depthStreakCount = s.depthStreakCount.coerceIn(1, DEPTH_STREAK_COUNT_MAX),
                depthMaxScalePercent = s.depthMaxScalePercent.coerceIn(DEPTH_SCALE_PERCENT_MIN, DEPTH_SCALE_PERCENT_MAX),
                textFrameMs = s.textFrameMs.coerceIn(TEXT_FRAME_MIN_MS, TEXT_FRAME_MAX_MS),
                showcaseMessage = msg,
            )
        }

        private const val LINE_WIDTH_MIN = 4
        private const val LINE_WIDTH_MAX = 48
        private const val LETTER_HEIGHT_MIN = 6
        private const val LETTER_HEIGHT_MAX = 48
        private const val FONT_SCALE_MIN = 1
        private const val FONT_SCALE_MAX = 8
        private const val COLUMN_MULT_MIN = -80
        private const val COLUMN_MULT_MAX = 0
        private const val LINE_LEN_ABS_MIN = 1
        private const val LINE_LEN_ABS_MAX = 80
        private const val SPEED_ABS_MIN = 1
        private const val SPEED_ABS_MAX = 50
        private const val MATRIX_FRAME_MIN_MS = 16L
        private const val MATRIX_FRAME_MAX_MS = 500L
        private const val ASCII_MIN_CODE = 32
        private const val ASCII_MAX_CODE = 126
        private const val KEY_RESET_MIN_MS = 1_000L
        private const val KEY_RESET_MAX_MS = 600_000L
        private const val KEY_LENGTH_COLS_MAX = 512
        private const val TEXT_FRAME_MIN_MS = 50L
        private const val TEXT_FRAME_MAX_MS = 5000L
        private const val SHOWCASE_MESSAGE_MAX_LEN = 500
        private const val DEPTH_STREAK_COUNT_MAX = 128
        private const val DEPTH_SCALE_PERCENT_MIN = 101
        private const val DEPTH_SCALE_PERCENT_MAX = 600
    }
}
