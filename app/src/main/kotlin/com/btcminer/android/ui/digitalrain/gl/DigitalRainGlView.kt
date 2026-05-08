/*
 * OpenGL ES surface for Digital Rain backdrop (pairs with [com.btcminer.android.ui.digitalrain.DigitalRainView]).
 */

package com.btcminer.android.ui.digitalrain.gl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.btcminer.android.ui.digitalrain.DigitalRainSettings
import com.btcminer.android.ui.digitalrain.DigitalRainSettingsRepository

class DigitalRainGlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    private val renderer = DigitalRainGlRenderer(context) {
        onGpuInitFailedListener?.invoke()
    }

    /** Invoked on main thread when shader/program setup fails. */
    var onGpuInitFailedListener: (() -> Unit)? = null

    private val choreographer = Choreographer.getInstance()
    private var running = false
    private var lastPostMs = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNs: Long) {
            if (!running || visibility != View.VISIBLE) return
            choreographer.postFrameCallback(this)
            val now = SystemClock.uptimeMillis()
            val interval = renderer.matrixFrameMsForUi.coerceAtLeast(16L)
            if (now - lastPostMs < interval) return
            lastPostMs = now
            requestRender()
        }
    }

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(false)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun applySettings(settings: DigitalRainSettings) {
        val normalized = DigitalRainSettingsRepository.normalize(settings)
        queueEvent {
            renderer.applySettings(normalized)
        }
    }

    fun setSatoshiBackdrop(bitmap: Bitmap?, showPortrait: Boolean, flashWhite: Boolean) {
        queueEvent {
            renderer.setSatoshiBackdrop(bitmap, showPortrait, flashWhite)
        }
        requestRender()
    }

    fun startRain() {
        if (running) return
        running = true
        lastPostMs = SystemClock.uptimeMillis()
        choreographer.postFrameCallback(frameCallback)
    }

    fun stopRain() {
        running = false
        choreographer.removeFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        stopRain()
        super.onDetachedFromWindow()
    }
}
