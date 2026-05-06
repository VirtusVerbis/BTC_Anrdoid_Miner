/*
 * OpenGL ES 2.0 Digital Rain: atlas-textured glyphs (parity with DigitalRainView typeface/atlas).
 */

package com.btcminer.android.ui.digitalrain.gl

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import com.btcminer.android.ui.digitalrain.DigitalRainAnimMode
import com.btcminer.android.ui.digitalrain.DigitalRainGlyphAtlasMode
import com.btcminer.android.ui.digitalrain.DigitalRainGlyphSampling
import com.btcminer.android.ui.digitalrain.DigitalRainMessageGlyphSource
import com.btcminer.android.ui.digitalrain.DigitalRainSettings
import com.btcminer.android.ui.digitalrain.DigitalRainSettingsRepository
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.random.Random

class DigitalRainGlRenderer(
    @Suppress("unused") private val context: Context,
    private val onGpuInitFailed: () -> Unit,
) : GLSurfaceView.Renderer {

    @Volatile
    var matrixFrameMsForUi: Long = 100L
        private set

    private var settings: DigitalRainSettings = DigitalRainSettings.defaults()
    private val rng = Random.Default

    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var colorHandle = 0
    private var mvpHandle = 0
    private var textureHandle = 0
    private val mvpMatrix = FloatArray(16)

    private var textureId = 0
    private var charToUv: Map<Char, FloatArray> = emptyMap()
    private var fallbackChar = '?'
    private var atlasKeyCached = Int.MIN_VALUE
    private var atlasUploaded = false

    private var viewW = 0
    private var viewH = 0
    private var lineWidthPx = 0
    private var letterHeightPx = 0
    private var numColumns = 0

    private val lineLength = mutableListOf<Int>()
    private val linePos = mutableListOf<Float>()
    private val lineSpeed = mutableListOf<Int>()
    /** `\u0000` = no key streak; highlight lasts until that streak wraps off-screen. */
    private var columnKeyHeadChar = CharArray(0)
    private var columnDepthMultiplier = FloatArray(0)

    private var lastMatrixTickMs = 0L
    private var lastKeyEventMs = 0L
    private var frameNowMs = 0L
    private var requestedMode = DigitalRainAnimMode.MATRIX
    private var runtimeMode = DigitalRainAnimMode.MATRIX
    private var messageCursor = 0
    private var nextShowcaseAtMs = Long.MAX_VALUE
    private var columnMessagePhase = IntArray(0)
    private var initFailurePosted = false

    private var vertexBuffer: FloatBuffer = allocateFloatBuffer(INITIAL_FLOAT_CAPACITY)
    private var vertexCount = 0

    /** Cached supported glyph pools (GPU atlas keys). */
    private var bvfBodyPool: CharArray = charArrayOf()
    private var bvfKeyPool: CharArray = charArrayOf()
    private var bvfBodyUvPool: Array<FloatArray> = emptyArray()
    private var bvfKeyUvPool: Array<FloatArray> = emptyArray()

    fun applySettings(newSettings: DigitalRainSettings) {
        settings = DigitalRainSettingsRepository.normalize(newSettings)
        requestedMode = settings.animMode
        runtimeMode = settings.animMode
        nextShowcaseAtMs = Long.MAX_VALUE
        messageCursor = 0
        matrixFrameMsForUi = settings.matrixFrameMs
        applyGeometryConstants()
        val newKey = atlasCacheKey()
        if (newKey != atlasKeyCached) {
            atlasUploaded = false
        }
        prepareAnim()
        GLES20.glClearColor(
            settings.rainBackgroundR / 255f,
            settings.rainBackgroundG / 255f,
            settings.rainBackgroundB / 255f,
            1f,
        )
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(
            settings.rainBackgroundR / 255f,
            settings.rainBackgroundG / 255f,
            settings.rainBackgroundB / 255f,
            1f,
        )
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program == 0) {
            Log.e(TAG, "GPU rain: shader program failed")
            postGpuFailure()
            return
        }
        positionHandle = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordHandle = GLES20.glGetAttribLocation(program, "a_TexCoord")
        colorHandle = GLES20.glGetAttribLocation(program, "a_Color")
        mvpHandle = GLES20.glGetUniformLocation(program, "u_MVPMatrix")
        textureHandle = GLES20.glGetUniformLocation(program, "u_Texture")

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        textureId = texIds[0]

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        atlasUploaded = false
        charToUv = emptyMap()
        vertexCount = 0
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
        GLES20.glViewport(0, 0, width, height)
        Matrix.setIdentityM(mvpMatrix, 0)
        Matrix.orthoM(mvpMatrix, 0, 0f, width.toFloat(), height.toFloat(), 0f, -1f, 1f)
        applyGeometryConstants()
        prepareAnim()
        val now = SystemClock.uptimeMillis()
        lastMatrixTickMs = now
        frameNowMs = now
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (program == 0 || viewW <= 0 || viewH <= 0) return

        val now = SystemClock.uptimeMillis()
        updateRuntimeMode(now)
        val frameMs = rainTickFrameMs()
        val isTick = now - lastMatrixTickMs >= frameMs

        if (!isTick) {
            drawUploadedGeometry()
            return
        }
        lastMatrixTickMs = now
        frameNowMs = now

        if (numColumns == 0) {
            vertexCount = 0
            return
        }

        if (!ensureAtlasReady()) {
            vertexCount = 0
            return
        }

        when (runtimeMode) {
            DigitalRainAnimMode.MATRIX,
            DigitalRainAnimMode.TEXT,
            -> {
                tickMatrixKeys(now)
                tickDepthVacancies()
            }
            DigitalRainAnimMode.SHOWCASE -> clearKeySelection()
        }
        uploadRainGeometry(runtimeMode == DigitalRainAnimMode.SHOWCASE)
        drawUploadedGeometry()
    }

    private fun ensureAtlasReady(): Boolean {
        val key = atlasCacheKey()
        if (atlasUploaded && key == atlasKeyCached && charToUv.isNotEmpty() && textureId != 0) return true

        if (settings.glyphAtlasMode == DigitalRainGlyphAtlasMode.BITCOIN_VS_FIAT) {
            return ensureBundledBitcoinVsFiatAtlasReady(key)
        }

        val maxSize = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxSize, 0)
        val built = RainGlyphAtlas.build(settings, lineWidthPx, letterHeightPx, maxSize[0])
        if (built == null) {
            Log.e(TAG, "Glyph atlas build failed")
            postGpuFailure()
            return false
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        val filter = if (settings.glyphAtlasMode == DigitalRainGlyphAtlasMode.BITCOIN_VS_FIAT) {
            GLES20.GL_NEAREST
        } else {
            GLES20.GL_LINEAR
        }
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, built.bitmap, 0)
        built.bitmap.recycle()

        charToUv = built.charToUv
        fallbackChar = built.fallbackChar
        atlasKeyCached = key
        atlasUploaded = true

        // Update supported pools from atlas keys.
        val keys = charToUv.keys
        bvfBodyPool = keys
            .asSequence()
            .filter { it != built.fallbackChar }
            .toList()
            .toCharArray()
        val keySet = charArrayOf('\u20BF', '\u26A1').toSet()
        bvfKeyPool = keys
            .asSequence()
            .filter { it in keySet }
            .toList()
            .toCharArray()

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return true
    }

    private fun ensureBundledBitcoinVsFiatAtlasReady(key: Int): Boolean {
        try {
            val decoded = context.assets.open(BitcoinVsFiatAtlasManifest.ASSET_PATH).use { stream ->
                BitmapFactory.decodeStream(stream)
            }
            if (decoded == null) {
                Log.e(TAG, "Failed to decode bundled atlas: ${BitcoinVsFiatAtlasManifest.ASSET_PATH}")
                postGpuFailure()
                return false
            }
            if (decoded.width != BitcoinVsFiatAtlasManifest.TEXTURE_WIDTH ||
                decoded.height != BitcoinVsFiatAtlasManifest.TEXTURE_HEIGHT
            ) {
                Log.e(
                    TAG,
                    "Bundled atlas size ${decoded.width}x${decoded.height} does not match " +
                        "spec ${BitcoinVsFiatAtlasManifest.TEXTURE_WIDTH}x${BitcoinVsFiatAtlasManifest.TEXTURE_HEIGHT}",
                )
                decoded.recycle()
                postGpuFailure()
                return false
            }
            val manifest = BitcoinVsFiatAtlasManifest.buildForSettings(settings, lineWidthPx, letterHeightPx)
            if (manifest.charToUv.isEmpty()) {
                Log.e(TAG, "BVF manifest has empty charToUv")
                decoded.recycle()
                postGpuFailure()
                return false
            }

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, decoded, 0)
            decoded.recycle()

            charToUv = manifest.charToUv
            fallbackChar = manifest.fallbackChar
            atlasKeyCached = key
            atlasUploaded = true

            bvfBodyUvPool = manifest.bodyUvPool
            bvfKeyUvPool = manifest.keyUvPool
            bvfBodyPool = charArrayOf()
            bvfKeyPool = charArrayOf('\u20BF', '\u26A1')

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load bundled BVF atlas", t)
            postGpuFailure()
            return false
        }
    }

    private fun atlasCacheKey(): Int {
        var h = settings.glyphAtlasMode.ordinal
        h = 31 * h + settings.showcaseMessage.hashCode()
        h = 31 * h + settings.alphabetOnly.hashCode()
        h = 31 * h + settings.asciiRange1Start
        h = 31 * h + settings.asciiRange1End
        h = 31 * h + settings.asciiRange2Start
        h = 31 * h + settings.asciiRange2End
        h = 31 * h + settings.useBigText.hashCode()
        h = 31 * h + settings.defaultLineWidth
        h = 31 * h + settings.defaultLetterHeight
        h = 31 * h + settings.fontScale
        h = 31 * h + lineWidthPx
        h = 31 * h + letterHeightPx
        return h
    }

    private fun postGpuFailure() {
        if (!initFailurePosted) {
            initFailurePosted = true
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onGpuInitFailed()
            }
        }
    }

    private fun drawUploadedGeometry() {
        if (vertexCount == 0 || program == 0 || textureId == 0) return
        vertexBuffer.position(0)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(textureHandle, 0)

        val stride = FLOATS_PER_VERTEX * BYTES_PER_FLOAT
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, stride, vertexBuffer)

        vertexBuffer.position(2)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, stride, vertexBuffer)

        vertexBuffer.position(4)
        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, stride, vertexBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun uvForChar(ch: Char): FloatArray =
        charToUv[ch] ?: charToUv[fallbackChar] ?: floatArrayOf(0f, 0f, 1f, 1f)

    private fun randomFromPool(pool: CharArray): Char {
        if (pool.isEmpty()) return fallbackChar
        return pool[rng.nextInt(pool.size)]
    }

    private fun randomUvFromPool(pool: Array<FloatArray>): FloatArray =
        if (pool.isEmpty()) uvForChar(fallbackChar) else pool[rng.nextInt(pool.size)]

    private fun rainTickFrameMs(): Long = settings.matrixFrameMs

    private fun uploadRainGeometry(isShowcase: Boolean) {
        val base565 = companionRgb888To565(settings.rainTextR, settings.rainTextG, settings.rainTextB)
        val keyOrangeBase565 = companionRgb888To565(BITCOIN_ORANGE_R, BITCOIN_ORANGE_G, BITCOIN_ORANGE_B)
        val headRgb = Color.rgb(settings.headCharR, settings.headCharG, settings.headCharB)
        val headR = Color.red(headRgb) / 255f
        val headG = Color.green(headRgb) / 255f
        val headB = Color.blue(headRgb) / 255f
        val message = DigitalRainMessageGlyphSource.sanitizeMessage(settings.showcaseMessage)
        val msgLen = message.length.coerceAtLeast(1)
        var cursor = messageCursor
        var exhausted = false

        var needed = 0
        for (i in 0 until numColumns) {
            val len = lineLength[i]
            needed += len * TEXTURED_QUAD_FLOATS + TEXTURED_QUAD_FLOATS
        }
        ensureVertexCapacity(needed)
        vertexBuffer.clear()
        var floatsWritten = 0

        for (i in 0 until numColumns) {
            val keyHead = columnKeyHeadChar.getOrNull(i) ?: NO_KEY_HEAD
            val keyHighlightModes =
                runtimeMode == DigitalRainAnimMode.MATRIX || runtimeMode == DigitalRainAnimMode.TEXT
            val isKeyColumn = keyHighlightModes && keyHead != NO_KEY_HEAD
            val startX = i * lineWidthPx.toFloat()
            val m = if (isShowcase || !settings.depthEnabled) {
                1f
            } else {
                columnDepthMultiplier.getOrElse(i) { 1f }.coerceAtLeast(1f)
            }
            val cx = startX + lineWidthPx * 0.5f
            val halfW = lineWidthPx * m * 0.5f
            val xL = cx - halfW
            val xR = cx + halfW
            val letterH = letterHeightPx * m
            var currentY = -letterH
            val len = lineLength[i]
            val denom = max(1, len - 1)
            val phase = columnMessagePhase.getOrElse(i) { 0 }

            for (j in 0 until len) {
                val colorVal = 10 + (255 - 10) * j / denom
                val bodyColor = when (runtimeMode) {
                    DigitalRainAnimMode.MATRIX,
                    DigitalRainAnimMode.TEXT,
                    -> {
                        if (isKeyColumn) {
                            if (settings.bitcoinOrangeKeyHighlight) {
                                val argb = companionLuminance888ToArgb(keyOrangeBase565, colorVal)
                                Triple(
                                    Color.red(argb) / 255f,
                                    Color.green(argb) / 255f,
                                    Color.blue(argb) / 255f,
                                )
                            } else {
                                Triple(
                                    colorVal.coerceIn(0, 255) / 255f,
                                    0f,
                                    0f,
                                )
                            }
                        } else {
                            val argb = companionLuminance888ToArgb(base565, colorVal)
                            Triple(
                                Color.red(argb) / 255f,
                                Color.green(argb) / 255f,
                                Color.blue(argb) / 255f,
                            )
                        }
                    }
                    DigitalRainAnimMode.SHOWCASE -> {
                        val argb = companionLuminance888ToArgb(base565, colorVal)
                        Triple(
                            Color.red(argb) / 255f,
                            Color.green(argb) / 255f,
                            Color.blue(argb) / 255f,
                        )
                    }
                }
                val yT = linePos[i] + currentY
                val yB = yT + letterH
                val uv = when (runtimeMode) {
                    DigitalRainAnimMode.MATRIX,
                    DigitalRainAnimMode.TEXT,
                    -> if (settings.glyphAtlasMode == DigitalRainGlyphAtlasMode.BITCOIN_VS_FIAT) {
                        if (isKeyColumn) randomUvFromPool(bvfKeyUvPool) else randomUvFromPool(bvfBodyUvPool)
                    } else {
                        val ch = if (runtimeMode == DigitalRainAnimMode.MATRIX) {
                            DigitalRainGlyphSampling.randomGlyphChar(rng, settings)
                        } else {
                            DigitalRainMessageGlyphSource.messageCharLooped(message, phase + j)
                        }
                        uvForChar(ch)
                    }
                    DigitalRainAnimMode.SHOWCASE -> {
                        if (cursor >= message.length) exhausted = true
                        val (nextCh, nextCursor) = DigitalRainMessageGlyphSource.nextGlyphOrSpace(message, cursor)
                        cursor = nextCursor
                        uvForChar(nextCh)
                    }
                }
                floatsWritten += writeTexturedGlyphQuad(xL, yT, xR, yB, uv, bodyColor)
                currentY += letterH
            }

            val headUv = when (runtimeMode) {
                DigitalRainAnimMode.MATRIX -> when {
                    isKeyColumn && settings.glyphAtlasMode == DigitalRainGlyphAtlasMode.BITCOIN_VS_FIAT ->
                        randomUvFromPool(bvfKeyUvPool)
                    isKeyColumn -> uvForChar(keyHead)
                    else -> if (settings.glyphAtlasMode == DigitalRainGlyphAtlasMode.BITCOIN_VS_FIAT) randomUvFromPool(bvfBodyUvPool) else uvForChar(DigitalRainGlyphSampling.randomGlyphChar(rng, settings))
                }
                DigitalRainAnimMode.TEXT -> when {
                    isKeyColumn && settings.glyphAtlasMode == DigitalRainGlyphAtlasMode.BITCOIN_VS_FIAT ->
                        randomUvFromPool(bvfKeyUvPool)
                    isKeyColumn -> uvForChar(keyHead)
                    else -> if (settings.glyphAtlasMode == DigitalRainGlyphAtlasMode.BITCOIN_VS_FIAT) randomUvFromPool(bvfBodyUvPool) else uvForChar(DigitalRainMessageGlyphSource.messageCharLooped(message, phase + len))
                }
                DigitalRainAnimMode.SHOWCASE -> {
                    if (cursor >= message.length) exhausted = true
                    val (nextCh, nextCursor) = DigitalRainMessageGlyphSource.nextGlyphOrSpace(message, cursor)
                    cursor = nextCursor
                    uvForChar(nextCh)
                }
            }
            floatsWritten += writeTexturedGlyphQuad(
                xL,
                linePos[i] + currentY,
                xR,
                linePos[i] + currentY + letterH,
                headUv,
                Triple(headR, headG, headB),
            )
            advanceColumn(i)
            if (runtimeMode == DigitalRainAnimMode.TEXT) {
                columnMessagePhase[i] = (phase + 1) % msgLen
            }
        }

        vertexBuffer.position(0)
        vertexCount = floatsWritten / FLOATS_PER_VERTEX
        if (runtimeMode == DigitalRainAnimMode.SHOWCASE) {
            messageCursor = cursor
            if (isShowcase && exhausted) {
                completeShowcase(frameNowMs)
            }
        }
    }

    private fun writeTexturedGlyphQuad(
        xL: Float,
        yT: Float,
        xR: Float,
        yB: Float,
        uv: FloatArray,
        rgb: Triple<Float, Float, Float>,
    ): Int {
        val u0 = uv[0]
        val v0 = uv[1]
        val u1 = uv[2]
        val v1 = uv[3]
        val r = rgb.first
        val g = rgb.second
        val b = rgb.third
        val a = 1f
        putTexturedTri(xL, yT, u0, v0, xR, yT, u1, v0, xL, yB, u0, v1, r, g, b, a)
        putTexturedTri(xR, yT, u1, v0, xR, yB, u1, v1, xL, yB, u0, v1, r, g, b, a)
        return TEXTURED_QUAD_FLOATS
    }

    private fun putTexturedTri(
        x1: Float,
        y1: Float,
        u1: Float,
        v1: Float,
        x2: Float,
        y2: Float,
        u2: Float,
        v2: Float,
        x3: Float,
        y3: Float,
        u3: Float,
        v3: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float,
    ) {
        vertexBuffer.put(x1)
        vertexBuffer.put(y1)
        vertexBuffer.put(u1)
        vertexBuffer.put(v1)
        vertexBuffer.put(r)
        vertexBuffer.put(g)
        vertexBuffer.put(b)
        vertexBuffer.put(a)
        vertexBuffer.put(x2)
        vertexBuffer.put(y2)
        vertexBuffer.put(u2)
        vertexBuffer.put(v2)
        vertexBuffer.put(r)
        vertexBuffer.put(g)
        vertexBuffer.put(b)
        vertexBuffer.put(a)
        vertexBuffer.put(x3)
        vertexBuffer.put(y3)
        vertexBuffer.put(u3)
        vertexBuffer.put(v3)
        vertexBuffer.put(r)
        vertexBuffer.put(g)
        vertexBuffer.put(b)
        vertexBuffer.put(a)
    }

    private fun ensureVertexCapacity(floatCount: Int) {
        if (vertexBuffer.capacity() >= floatCount) return
        var cap = vertexBuffer.capacity()
        while (cap < floatCount) cap *= 2
        vertexBuffer = allocateFloatBuffer(cap)
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

    private fun timedFullKeyRefresh() {
        clearKeySelection()
        val selectionCount = keyHighlightCapacity()
        if (selectionCount <= 0) return
        val selectedColumns = (0 until numColumns).toMutableList().apply { shuffle(rng) }.take(selectionCount)
        for (columnIndex in selectedColumns) {
            columnKeyHeadChar[columnIndex] = DigitalRainGlyphSampling.randomKeyHeadChar(rng, settings)
        }
    }

    private fun tickDepthVacancies() {
        if (!settings.depthEnabled) return
        assignDepthVacanciesUpToTarget()
    }

    private fun effectiveDepthTarget(): Int {
        if (!settings.depthEnabled || numColumns <= 1) return 0
        return settings.depthStreakCount.coerceAtMost(numColumns - 1)
    }

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

    private fun applyGeometryConstants() {
        lineWidthPx = if (settings.useBigText) settings.defaultLineWidth * 2 else settings.defaultLineWidth
        letterHeightPx = if (settings.useBigText) {
            (settings.defaultLetterHeight * 1.6f).toInt().coerceAtLeast(1)
        } else {
            settings.defaultLetterHeight.coerceAtLeast(1)
        }
    }

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
        val mlen = msg.length.coerceAtLeast(1)
        columnMessagePhase = IntArray(numColumns) { rng.nextInt(0, mlen) }

        vertexCount = 0
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

    private fun clearKeySelection() {
        if (columnKeyHeadChar.isNotEmpty()) {
            columnKeyHeadChar.fill(NO_KEY_HEAD)
        }
    }

    private fun randomInclusive(min: Int, max: Int): Int =
        rng.nextInt(min, max + 1)

    private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (vertexShader == 0 || fragmentShader == 0) return 0
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vertexShader)
        GLES20.glAttachShader(p, fragmentShader)
        GLES20.glLinkProgram(p)
        val link = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, link, 0)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        if (link[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Program link: ${GLES20.glGetProgramInfoLog(p)}")
            GLES20.glDeleteProgram(p)
            return 0
        }
        return p
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        private const val TAG = "DigitalRainGlRenderer"
        private const val NO_KEY_HEAD = '\u0000'
        private const val DEPTH_MULT_EPS = 1e-3f
        private const val SHOWCASE_REPEAT_DELAY_MS = 60_000L
        private const val BITCOIN_ORANGE_R = 247
        private const val BITCOIN_ORANGE_G = 147
        private const val BITCOIN_ORANGE_B = 26
        private const val FLOATS_PER_VERTEX = 8
        private const val BYTES_PER_FLOAT = 4
        private const val VERTICES_PER_QUAD = 6
        private const val TEXTURED_QUAD_FLOATS = VERTICES_PER_QUAD * FLOATS_PER_VERTEX
        private const val INITIAL_FLOAT_CAPACITY = 12288

        private fun allocateFloatBuffer(capacity: Int): FloatBuffer =
            ByteBuffer.allocateDirect(capacity * BYTES_PER_FLOAT).order(ByteOrder.nativeOrder()).asFloatBuffer()

        private fun companionLuminance888ToArgb(colorRgb565: Int, lum: Int): Int {
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
            return companionRgb565To888(packed565)
        }

        private fun companionRgb888To565(r: Int, g: Int, b: Int): Int {
            val r5 = (r shr 3) and 0x1F
            val g6 = (g shr 2) and 0x3F
            val b5 = (b shr 3) and 0x1F
            return (r5 shl 11) or (g6 shl 5) or b5
        }

        private fun companionRgb565To888(c565: Int): Int {
            val r5 = (c565 shr 11) and 0x1F
            val g6 = (c565 shr 5) and 0x3F
            val b5 = c565 and 0x1F
            val r = (r5 * 255 + 15) / 31
            val g = (g6 * 255 + 31) / 63
            val b = (b5 * 255 + 15) / 31
            return Color.rgb(r, g, b)
        }

        private const val VERTEX_SHADER = """
            uniform mat4 u_MVPMatrix;
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            attribute vec4 a_Color;
            varying vec2 v_TexCoord;
            varying vec4 v_Color;
            void main() {
                v_TexCoord = a_TexCoord;
                v_Color = a_Color;
                gl_Position = u_MVPMatrix * a_Position;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D u_Texture;
            varying vec2 v_TexCoord;
            varying vec4 v_Color;
            void main() {
                vec4 tex = texture2D(u_Texture, v_TexCoord);
                gl_FragColor = tex * v_Color;
            }
        """
    }
}
