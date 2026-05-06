/*
 * Non-sensitive Digital Rain UI preferences (renderer backend).
 */

package com.btcminer.android.ui.digitalrain

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ConfigurationInfo

/**
 * Persisted render backend; defaults to [DigitalRainRenderBackend.CANVAS_CPU] when unset.
 * Use [deviceSupportsGles2] to grey out GPU when the device cannot host GLES 2.
 */
class DigitalRainPreferences(private val context: Context) {

    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getRenderBackend(): DigitalRainRenderBackend {
        val stored = when (prefs.getString(KEY_RENDER_BACKEND, null)) {
            VALUE_GPU -> DigitalRainRenderBackend.OPENGL_GPU
            else -> DigitalRainRenderBackend.CANVAS_CPU
        }
        return if (stored == DigitalRainRenderBackend.OPENGL_GPU && !deviceSupportsGles2(context)) {
            DigitalRainRenderBackend.CANVAS_CPU
        } else {
            stored
        }
    }

    /**
     * Persists backend. If [DigitalRainRenderBackend.OPENGL_GPU] but [deviceSupportsGles2] is false,
     * stores [DigitalRainRenderBackend.CANVAS_CPU] instead.
     */
    fun setRenderBackend(backend: DigitalRainRenderBackend) {
        val toStore = when {
            backend == DigitalRainRenderBackend.OPENGL_GPU && !deviceSupportsGles2(context) ->
                DigitalRainRenderBackend.CANVAS_CPU
            else -> backend
        }
        prefs.edit().putString(
            KEY_RENDER_BACKEND,
            when (toStore) {
                DigitalRainRenderBackend.CANVAS_CPU -> VALUE_CPU
                DigitalRainRenderBackend.OPENGL_GPU -> VALUE_GPU
            },
        ).apply()
    }

    companion object {
        private const val PREFS_NAME = "digital_rain_prefs"
        private const val KEY_RENDER_BACKEND = "render_backend"
        private const val VALUE_CPU = "canvas_cpu"
        private const val VALUE_GPU = "opengl_gpu"

        /** OpenGL ES 2.0 minor/major encoding used by [ConfigurationInfo.reqGlEsVersion]. */
        private const val GLES_VERSION_2_0 = 0x00020000

        fun deviceSupportsGles2(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val cfg = am.deviceConfigurationInfo ?: return false
            return cfg.reqGlEsVersion >= GLES_VERSION_2_0
        }
    }
}
