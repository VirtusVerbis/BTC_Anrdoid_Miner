/*
 * User-selectable renderer for Digital Rain backdrop (Canvas vs OpenGL ES).
 */

package com.btcminer.android.ui.digitalrain

/** Canvas path uses [DigitalRainView]; GPU path uses GLES 2 via [com.btcminer.android.ui.digitalrain.gl.DigitalRainGlView]. */
enum class DigitalRainRenderBackend {
    CANVAS_CPU,
    OPENGL_GPU,
}
