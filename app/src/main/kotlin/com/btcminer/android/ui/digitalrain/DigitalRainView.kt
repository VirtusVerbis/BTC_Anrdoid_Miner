/*
 * Matrix-style digital rain background layer.
 * Adapted from Eric Nam's DigitalRainAnimation (DigitalRainAnimation.hpp), MIT License:
 * https://github.com/0015/Arduino_DigitalRain_Matrix
 */

package com.btcminer.android.ui.digitalrain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.max
import kotlin.random.Random

class DigitalRainView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var settings: DigitalRainSettings = DigitalRainSettings.defaults()

    private val choreographer = Choreographer.getInstance()
    private val rng = Random.Default

    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = DigitalRainGlyphTypography.typefaceFor(DigitalRainSettings.defaults())
        style = Paint.Style.FILL
    }

    private val satoshiBitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private val satoshiFlashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    @Volatile
    private var satoshiBackdropBitmap: Bitmap? = null

    @Volatile
    private var satoshiPortraitVisible = false

    @Volatile
    private var satoshiFlashWhite = false

    /** Portrait + flash are drawn inside this view; rain is between portrait and flash. Call from main thread. */
    fun setSatoshiBackdrop(bitmap: Bitmap?, showPortrait: Boolean, flashWhite: Boolean) {
        satoshiBackdropBitmap = bitmap
        satoshiPortraitVisible = showPortrait
        satoshiFlashWhite = flashWhite
        invalidate()
    }

    private var running = false
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNs: Long) {
            if (!running) return
            choreographer.postFrameCallback(this)
            if (!isAttachedToWindow) return

            val now = SystemClock.uptimeMillis()
            updateRuntimeMode(now)
            val frameMs = rainTickFrameMs()
            if (now - lastMatrixTickMs < frameMs) return
            lastMatrixTickMs = now
            frameNowMs = now

            when (runtimeMode) {
                DigitalRainAnimMode.MATRIX,
                DigitalRainAnimMode.TEXT,
                -> {
                    tickMatrixKeys(now)
                    tickDepthVacancies()
                }
                DigitalRainAnimMode.SHOWCASE -> clearKeySelection()
            }
            invalidate()
        }
    }

    private var lastMatrixTickMs = 0L
    private var lastKeyEventMs = 0L
    private var frameNowMs = 0L
    private var requestedMode = DigitalRainAnimMode.MATRIX
    private var runtimeMode = DigitalRainAnimMode.MATRIX
    private var messageCursor = 0
    private var nextShowcaseAtMs = Long.MAX_VALUE
    private var columnMessagePhase = IntArray(0)

    private var viewW = 0
    private var viewH = 0
    private var lineWidthPx = 0
    private var letterHeightPx = 0
    /** Baseline [Paint.textSize] from geometry; depth scales per column as `this * m`. */
    private var baseGlyphTextSize = 0f
    private var numColumns = 0

    private val lineLength = mutableListOf<Int>()
    private val linePos = mutableListOf<Float>()
    private val lineSpeed = mutableListOf<Int>()
    /** `\u0000` = no key streak; highlight lasts until that streak wraps off-screen. */
    private var columnKeyHeadChar = CharArray(0)
    /** Depth streak scale per column; `1f` = inactive. Sticky until wrap. */
    private var columnDepthMultiplier = FloatArray(0)

    /** CPU BVF: prefiltered glyphs for current [glyphPaint] (rebuilt in [applyGeometryConstants]). */
    private var bvfCpuBodyPool = CharArray(0)
    private var bvfCpuKeyPool = CharArray(0)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Applies persisted/runtime settings and refreshes layout-dependent rain state. */
    fun applySettings(newSettings: DigitalRainSettings) {
        settings = DigitalRainSettingsRepository.normalize(newSettings)
        applyGeometryConstants()
        prepareAnim()
        invalidate()
    }

    /** Starts vsync-driven ticks (call from activity [android.app.Activity.onStart]). */
    fun startRain() {
        if (running) return
        running = true
        val now = SystemClock.uptimeMillis()
        lastMatrixTickMs = now
        frameNowMs = now
        requestedMode = settings.animMode
        runtimeMode = settings.animMode
        choreographer.postFrameCallback(frameCallback)
    }

    /** Stops scheduling ticks (call from [android.app.Activity.onStop]). */
    fun stopRain() {
        running = false
        choreographer.removeFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        stopRain()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewW = w
        viewH = h
        applyGeometryConstants()
        prepareAnim()
    }

    private fun applyGeometryConstants() {
        lineWidthPx = if (settings.useBigText) settings.defaultLineWidth * 2 else settings.defaultLineWidth
        letterHeightPx = if (settings.useBigText) {
            (settings.defaultLetterHeight * 1.6f).toInt().coerceAtLeast(1)
        } else {
            settings.defaultLetterHeight.coerceAtLeast(1)
        }
        var candidateTextSize = max(
            letterHeightPx * 0.92f,
            lineWidthPx * (
                if (settings.useBigText) settings.fontScale * 0.45f
                else settings.fontScale * 0.42f
                ),
        )
        glyphPaint.typeface = DigitalRainGlyphTypography.typefaceFor(settings)
        if (settings.glyphAtlasMode == DigitalRainGlyphAtlasMode.BITCOIN_VS_FIAT) {
            glyphPaint.textSize = candidateTextSize
            val maxGlyphWidth = DigitalRainGlyphCharsets
                .bitcoinVsFiatMatrixChars()
                .distinct()
                .maxOfOrNull { ch -> glyphPaint.measureText(ch.toString()) }
                ?: 0f
            val targetWidth = (lineWidthPx - BVF_GLYPH_WIDTH_PAD_PX).coerceAtLeast(1).toFloat()
            if (maxGlyphWidth > targetWidth) {
                candidateTextSize *= (targetWidth / maxGlyphWidth)
            }
        }
        baseGlyphTextSize = candidateTextSize
        glyphPaint.textSize = baseGlyphTextSize
        rebuildBvfCpuGlyphPools()
    }

    /**
     * Builds font-supported glyph pools once per geometry/settings change (not per frame).
     * minSdk 24 guarantees [Paint.hasGlyph] is available.
     */
    private fun rebuildBvfCpuGlyphPools() {
        if (settings.glyphAtlasMode != DigitalRainGlyphAtlasMode.BITCOIN_VS_FIAT) {
            bvfCpuBodyPool = CharArray(0)
            bvfCpuKeyPool = CharArray(0)
            return
        }
        val bodyCandidates = DigitalRainGlyphCharsets.bitcoinVsFiatMatrixChars().distinct()
        val bodyFiltered = DigitalRainGlyphFontSupport.filterRenderableChars(glyphPaint, bodyCandidates)
        bvfCpuBodyPool = DigitalRainGlyphFontSupport.toBodyPoolWithFallback(glyphPaint, bodyFiltered)
        val keyFiltered = DigitalRainGlyphFontSupport.filterRenderableChars(
            glyphPaint,
            DigitalRainGlyphCharsets.keyHighlightHeadCharsBitcoinVsFiat,
        )
        bvfCpuKeyPool = DigitalRainGlyphFontSupport.toKeyPoolWithFallback(
            glyphPaint,
            keyFiltered,
            bvfCpuBodyPool,
        )
    }

    private fun sampleBvfCpuBodyChar(): Char =
        if (bvfCpuBodyPool.isNotEmpty()) bvfCpuBodyPool[rng.nextInt(bvfCpuBodyPool.size)] else '$'

    private fun sampleBvfCpuKeyChar(): Char =
        if (bvfCpuKeyPool.isNotEmpty()) bvfCpuKeyPool[rng.nextInt(bvfCpuKeyPool.size)] else sampleBvfCpuBodyChar()

    private fun prepareAnim() {
        clearKeySelection()
        lastKeyEventMs = SystemClock.uptimeMillis()
        messageCursor = 0

        lineLength.clear()
        linePos.clear()
        lineSpeed.clear()

        if (viewW <= 0 || viewH <= 0) {
            numColumns = 0
            return
        }

        numColumns = (viewW + lineWidthPx - 1) / lineWidthPx

        repeat(numColumns) {
            lineLength.add(randomInclusive(settings.lineLenMin, settings.lineLenMax))
            linePos.add((settings.columnStartYMultiplier * lineLength.last() - letterHeightPx).toFloat())
            lineSpeed.add(randomInclusive(settings.lineSpeedMin, settings.lineSpeedMax))
        }
        columnKeyHeadChar = CharArray(numColumns) { NO_KEY_HEAD }
        columnDepthMultiplier = FloatArray(numColumns) { 1f }
        if (!settings.depthEnabled) {
            columnDepthMultiplier.fill(1f)
        }

        val msg = DigitalRainMessageGlyphSource.sanitizeMessage(settings.showcaseMessage)
        val msgLen = msg.length.coerceAtLeast(1)
        columnMessagePhase = IntArray(numColumns) { rng.nextInt(0, msgLen) }

        background = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (viewW <= 0 || viewH <= 0) return
        canvas.drawColor(
            Color.rgb(settings.rainBackgroundR, settings.rainBackgroundG, settings.rainBackgroundB)
        )

        if (satoshiPortraitVisible) {
            val bmp = satoshiBackdropBitmap
            if (bmp != null && !bmp.isRecycled) {
                val dst = SatoshiBackdropFit.fitCenterRectF(bmp.width, bmp.height, viewW, viewH)
                canvas.drawBitmap(bmp, null, Rect(dst.left.toInt(), dst.top.toInt(), dst.right.toInt(), dst.bottom.toInt()), satoshiBitmapPaint)
            }
        }

        if (numColumns > 0) {
            when (runtimeMode) {
                DigitalRainAnimMode.MATRIX -> drawMatrixRain(canvas)
                DigitalRainAnimMode.TEXT -> drawTextModeRain(canvas)
                DigitalRainAnimMode.SHOWCASE -> drawShowcaseRain(canvas)
            }
        }

        if (satoshiFlashWhite) {
            canvas.drawRect(0f, 0f, viewW.toFloat(), viewH.toFloat(), satoshiFlashPaint)
        }
    }

    private fun rainTickFrameMs(): Long = settings.matrixFrameMs

    private fun computeDepthStripBounds(startX: Float, depthScale: Float): Pair<Float, Float> {
        val renderStripWidth = lineWidthPx.toFloat() * depthScale.coerceAtLeast(1f)
        val centerX = startX + lineWidthPx * 0.5f
        val viewRight = viewW.toFloat().coerceAtLeast(0f)
        var left = (centerX - renderStripWidth * 0.5f).coerceIn(0f, viewRight)
        var right = (centerX + renderStripWidth * 0.5f).coerceIn(0f, viewRight)
        if (right <= left) {
            left = startX.coerceIn(0f, viewRight)
            right = (left + lineWidthPx).coerceIn(left, viewRight)
        }
        return left to right
    }

    private fun drawMatrixRain(canvas: Canvas) {
        val base565 = rgb888To565(settings.rainTextR, settings.rainTextG, settings.rainTextB)
        val keyOrangeBase565 = rgb888To565(BITCOIN_ORANGE_R, BITCOIN_ORANGE_G, BITCOIN_ORANGE_B)
        val headRgb = Color.rgb(settings.headCharR, settings.headCharG, settings.headCharB)

        for (i in 0 until numColumns) {
            val keyHead = columnKeyHeadChar.getOrNull(i) ?: NO_KEY_HEAD
            val isKeyColumn = keyHead != NO_KEY_HEAD
            val startX = i * lineWidthPx.toFloat()
            val m = columnDepthDrawScale(i)
            val (stripLeft, stripRight) = computeDepthStripBounds(startX, m)

            canvas.save()
            canvas.clipRect(stripLeft, 0f, stripRight, viewH.toFloat())
            glyphPaint.textSize = baseGlyphTextSize * m
            val fm = glyphPaint.fontMetrics
            val letterStep = letterHeightPx.toFloat() * m
            var currentY = -letterStep
            val len = lineLength[i]
            val denom = max(1, len - 1)

            for (j in 0 until len) {
                val colorVal = 10 + (255 - 10) * j / denom
                val bodyColor = if (isKeyColumn) {
                    if (settings.bitcoinOrangeKeyHighlight) {
                        luminance888ToArgb(keyOrangeBase565, colorVal)
                    } else {
                        Color.rgb(colorVal.coerceIn(0, 255), 0, 0)
                    }
                } else {
                    luminance888ToArgb(base565, colorVal)
                }
                glyphPaint.color = bodyColor
                val baseline = linePos[i] + currentY - fm.ascent
                val bodyGlyph = when {
                    isKeyColumn && settings.glyphAtlasMode == DigitalRainGlyphAtlasMode.BITCOIN_VS_FIAT ->
                        sampleBvfCpuKeyChar().toString()
                    settings.glyphAtlasMode == DigitalRainGlyphAtlasMode.BITCOIN_VS_FIAT ->
                        sampleBvfCpuBodyChar().toString()
                    else -> DigitalRainGlyphSampling.randomGlyphString(rng, settings)
                }
                canvas.drawText(bodyGlyph, 0, 1, stripLeft, baseline, glyphPaint)
                currentY += letterStep
            }

            glyphPaint.color = headRgb
            val headChar = when {
                isKeyColumn && settings.glyphAtlasMode == DigitalRainGlyphAtlasMode.BITCOIN_VS_FIAT ->
                    sampleBvfCpuKeyChar().toString()
                isKeyColumn -> keyHead.toString()
                settings.glyphAtlasMode == DigitalRainGlyphAtlasMode.BITCOIN_VS_FIAT ->
                    sampleBvfCpuBodyChar().toString()
                else -> DigitalRainGlyphSampling.randomGlyphString(rng, settings)
            }
            val headBaseline = linePos[i] + currentY - fm.ascent
            canvas.drawText(headChar, 0, 1, stripLeft, headBaseline, glyphPaint)
            canvas.restore()
            glyphPaint.textSize = baseGlyphTextSize
            advanceColumn(i)
        }
    }

    private fun drawTextModeRain(canvas: Canvas) {
        val base565 = rgb888To565(settings.rainTextR, settings.rainTextG, settings.rainTextB)
        val keyOrangeBase565 = rgb888To565(BITCOIN_ORANGE_R, BITCOIN_ORANGE_G, BITCOIN_ORANGE_B)
        val headRgb = Color.rgb(settings.headCharR, settings.headCharG, settings.headCharB)
        val message = DigitalRainMessageGlyphSource.sanitizeMessage(settings.showcaseMessage)
        val msgLen = message.length.coerceAtLeast(1)

        for (i in 0 until numColumns) {
            val keyHead = columnKeyHeadChar.getOrNull(i) ?: NO_KEY_HEAD
            val isKeyColumn = keyHead != NO_KEY_HEAD
            val startX = i * lineWidthPx.toFloat()
            val m = columnDepthDrawScale(i)
            val (stripLeft, stripRight) = computeDepthStripBounds(startX, m)

            canvas.save()
            canvas.clipRect(stripLeft, 0f, stripRight, viewH.toFloat())
            glyphPaint.textSize = baseGlyphTextSize * m
            val fm = glyphPaint.fontMetrics
            val letterStep = letterHeightPx.toFloat() * m
            var currentY = -letterStep
            val len = lineLength[i]
            val denom = max(1, len - 1)
            val phase = columnMessagePhase.getOrElse(i) { 0 }

            for (j in 0 until len) {
                val colorVal = 10 + (255 - 10) * j / denom
                val bodyColor = if (isKeyColumn) {
                    if (settings.bitcoinOrangeKeyHighlight) {
                        luminance888ToArgb(keyOrangeBase565, colorVal)
                    } else {
                        Color.rgb(colorVal.coerceIn(0, 255), 0, 0)
                    }
                } else {
                    luminance888ToArgb(base565, colorVal)
                }
                glyphPaint.color = bodyColor
                val baseline = linePos[i] + currentY - fm.ascent
                val ch = when {
                    isKeyColumn && settings.glyphAtlasMode == DigitalRainGlyphAtlasMode.BITCOIN_VS_FIAT ->
                        sampleBvfCpuKeyChar()
                    else -> DigitalRainMessageGlyphSource.messageCharLooped(message, phase + j)
                }
                canvas.drawText(ch.toString(), 0, 1, stripLeft, baseline, glyphPaint)
                currentY += letterStep
            }

            glyphPaint.color = headRgb
            val headChar = when {
                isKeyColumn && settings.glyphAtlasMode == DigitalRainGlyphAtlasMode.BITCOIN_VS_FIAT ->
                    sampleBvfCpuKeyChar().toString()
                isKeyColumn -> keyHead.toString()
                else -> DigitalRainMessageGlyphSource.messageCharLooped(message, phase + len).toString()
            }
            val headBaseline = linePos[i] + currentY - fm.ascent
            canvas.drawText(headChar, 0, 1, stripLeft, headBaseline, glyphPaint)
            canvas.restore()
            glyphPaint.textSize = baseGlyphTextSize
            advanceColumn(i)
            columnMessagePhase[i] = (phase + 1) % msgLen
        }
    }

    /** One-shot message stream with MATRIX-style trails; completes once stream passes end of message (then spaces). */
    private fun drawShowcaseRain(canvas: Canvas) {
        val fm = glyphPaint.fontMetrics
        val base565 = rgb888To565(settings.rainTextR, settings.rainTextG, settings.rainTextB)
        val headRgb = Color.rgb(settings.headCharR, settings.headCharG, settings.headCharB)
        val message = DigitalRainMessageGlyphSource.sanitizeMessage(settings.showcaseMessage)
        var cursor = messageCursor
        var exhausted = false

        for (i in 0 until numColumns) {
            val startX = i * lineWidthPx.toFloat()

            var currentY = -letterHeightPx.toFloat()
            val len = lineLength[i]
            val denom = max(1, len - 1)

            for (j in 0 until len) {
                val colorVal = 10 + (255 - 10) * j / denom
                glyphPaint.color = luminance888ToArgb(base565, colorVal)
                val baseline = linePos[i] + currentY - fm.ascent
                if (cursor >= message.length) exhausted = true
                val (ch, nextCursor) = DigitalRainMessageGlyphSource.nextGlyphOrSpace(message, cursor)
                cursor = nextCursor
                canvas.drawText(ch.toString(), 0, 1, startX, baseline, glyphPaint)
                currentY += letterHeightPx
            }

            glyphPaint.color = headRgb
            if (cursor >= message.length) exhausted = true
            val (headChar, nextCursor) = DigitalRainMessageGlyphSource.nextGlyphOrSpace(message, cursor)
            cursor = nextCursor
            val headBaseline = linePos[i] + currentY - fm.ascent
            canvas.drawText(headChar.toString(), 0, 1, startX, headBaseline, glyphPaint)
            advanceColumn(i)
        }

        messageCursor = cursor
        if (exhausted) {
            completeShowcase(frameNowMs)
        }
    }

    private fun advanceColumn(index: Int, forcedLength: Int? = null) {
        linePos[index] = linePos[index] + lineSpeed[index]
        if (linePos[index] >= viewH) {
            if (settings.enableKeyMode && settings.stickyKeyHighlight && index in columnKeyHeadChar.indices) {
                columnKeyHeadChar[index] = NO_KEY_HEAD
            }
            if (settings.depthEnabled &&
                index < columnDepthMultiplier.size &&
                columnDepthMultiplier[index] > 1f + DEPTH_MULT_EPS
            ) {
                columnDepthMultiplier[index] = 1f
            }
            val newLength = forcedLength ?: randomInclusive(settings.lineLenMin, settings.lineLenMax)
            lineLength[index] = newLength
            linePos[index] = (settings.columnStartYMultiplier * newLength).toFloat()
            lineSpeed[index] = randomInclusive(settings.lineSpeedMin, settings.lineSpeedMax)
        }
    }

    private fun tickMatrixKeys(now: Long) {
        if (numColumns == 0 || viewH <= 0) return
        if (!settings.enableKeyMode) {
            if (columnKeyHeadChar.any { it != NO_KEY_HEAD }) {
                clearKeySelection()
            }
            return
        }
        if (settings.stickyKeyHighlight) {
            assignKeyVacanciesUpToTarget()
        } else {
            if (now - lastKeyEventMs >= settings.keyResetTimeMs) {
                lastKeyEventMs = now
                timedFullKeyRefresh()
            }
        }
    }

    /** Legacy timed refresh: clear all key heads then assign a new random subset of columns. */
    private fun timedFullKeyRefresh() {
        clearKeySelection()
        val selectionCount = keyHighlightCapacity()
        if (selectionCount <= 0) return
        val selectedColumns = (0 until numColumns).toMutableList().apply { shuffle(rng) }.take(selectionCount)
        for (columnIndex in selectedColumns) {
            columnKeyHeadChar[columnIndex] = DigitalRainGlyphSampling.randomKeyHeadChar(rng, settings)
        }
    }

    /** Adds key streaks on idle columns only; never strips an active streak before it wraps. */
    private fun tickDepthVacancies() {
        if (!settings.depthEnabled || numColumns <= 1) return
        assignDepthVacanciesUpToTarget()
    }

    private fun effectiveDepthTarget(): Int {
        if (!settings.depthEnabled || numColumns <= 1) return 0
        return settings.depthStreakCount.coerceAtMost(numColumns - 1)
    }

    private fun columnDepthDrawScale(columnIndex: Int): Float {
        if (!settings.depthEnabled || columnIndex !in columnDepthMultiplier.indices) return 1f
        return columnDepthMultiplier[columnIndex].coerceAtLeast(1f)
    }

    /** Adds depth scales on idle columns only; never strips an active streak before it wraps. */
    private fun assignDepthVacanciesUpToTarget() {
        val target = effectiveDepthTarget()
        if (target <= 0) return
        val maxScale = settings.depthMaxScalePercent / 100f
        var active = columnDepthMultiplier.count { it > 1f + DEPTH_MULT_EPS }
        if (active >= target) return
        val available = (0 until numColumns).filter { columnDepthMultiplier[it] <= 1f + DEPTH_MULT_EPS }.toMutableList()
        available.shuffle(rng)
        var ai = 0
        while (active < target && ai < available.size) {
            columnDepthMultiplier[available[ai]] = 1f + rng.nextFloat() * (maxScale - 1f)
            ai++
            active++
        }
    }

    private fun assignKeyVacanciesUpToTarget() {
        val target = keyHighlightCapacity()
        if (target <= 0) return
        var active = columnKeyHeadChar.count { it != NO_KEY_HEAD }
        if (active >= target) return
        val available = (0 until numColumns).filter { columnKeyHeadChar[it] == NO_KEY_HEAD }.toMutableList()
        available.shuffle(rng)
        var ai = 0
        while (active < target && ai < available.size) {
            columnKeyHeadChar[available[ai]] = DigitalRainGlyphSampling.randomKeyHeadChar(rng, settings)
            ai++
            active++
        }
    }

    private fun keyHighlightCapacity(): Int =
        if (settings.keyLengthColumns == 0) numColumns
        else settings.keyLengthColumns.coerceAtMost((numColumns - 1).coerceAtLeast(0))

    private fun clearKeySelection() {
        if (columnKeyHeadChar.isNotEmpty()) {
            columnKeyHeadChar.fill(NO_KEY_HEAD)
        }
    }

    private fun updateRuntimeMode(now: Long) {
        if (requestedMode != settings.animMode) {
            requestedMode = settings.animMode
            when (requestedMode) {
                DigitalRainAnimMode.MATRIX -> {
                    runtimeMode = DigitalRainAnimMode.MATRIX
                    nextShowcaseAtMs = Long.MAX_VALUE
                    messageCursor = 0
                }
                DigitalRainAnimMode.TEXT -> {
                    runtimeMode = DigitalRainAnimMode.TEXT
                    nextShowcaseAtMs = Long.MAX_VALUE
                    messageCursor = 0
                }
                DigitalRainAnimMode.SHOWCASE -> startShowcase(now)
            }
        }
        if (requestedMode == DigitalRainAnimMode.SHOWCASE &&
            runtimeMode == DigitalRainAnimMode.MATRIX &&
            now >= nextShowcaseAtMs
        ) {
            startShowcase(now)
        }
    }

    private fun startShowcase(now: Long) {
        runtimeMode = DigitalRainAnimMode.SHOWCASE
        nextShowcaseAtMs = Long.MAX_VALUE
        messageCursor = 0
        lastMatrixTickMs = now
        prepareAnim()
    }

    private fun completeShowcase(now: Long) {
        runtimeMode = DigitalRainAnimMode.MATRIX
        messageCursor = 0
        nextShowcaseAtMs = now + SHOWCASE_REPEAT_DELAY_MS
        lastMatrixTickMs = now
        prepareAnim()
    }

    private fun randomInclusive(min: Int, max: Int): Int = rng.nextInt(min, max + 1)

    companion object {
        private const val BVF_GLYPH_WIDTH_PAD_PX = 2
        private const val NO_KEY_HEAD = '\u0000'
        private const val DEPTH_MULT_EPS = 1e-3f
        private const val SHOWCASE_REPEAT_DELAY_MS = 60_000L
        private const val BITCOIN_ORANGE_R = 247
        private const val BITCOIN_ORANGE_G = 147
        private const val BITCOIN_ORANGE_B = 26

        /** Arduino `map`-style luminance on RGB565 expanded channels (see luminance() in .hpp). */
        private fun luminance888ToArgb(colorRgb565: Int, lum: Int): Int {
            var r = (colorRgb565 and 0xF800) shr 8
            r = r or (r shr 5)
            var g = (colorRgb565 and 0x07E0) shr 3
            g = g or (g shr 6)
            var b = (colorRgb565 and 0x001F) shl 3
            b = b or (b shr 5)
            val bb = ((b * lum + 255) shr 8) and 0xF8
            val gg = ((g * lum + 255) shr 8) and 0xFC
            val rr = ((r * lum + 255) shr 8) and 0xF8
            val packed565 = (rr shl 8) or (gg shl 3) or (bb shr 3)
            return rgb565To888(packed565)
        }

        private fun rgb888To565(r: Int, g: Int, b: Int): Int {
            val r5 = (r shr 3) and 0x1F
            val g6 = (g shr 2) and 0x3F
            val b5 = (b shr 3) and 0x1F
            return (r5 shl 11) or (g6 shl 5) or b5
        }

        private fun rgb565To888(c565: Int): Int {
            val r5 = (c565 shr 11) and 0x1F
            val g6 = (c565 shr 5) and 0x3F
            val b5 = c565 and 0x1F
            val r = (r5 * 255 + 15) / 31
            val g = (g6 * 255 + 31) / 63
            val b = (b5 * 255 + 15) / 31
            return Color.rgb(r, g, b)
        }
    }
}
