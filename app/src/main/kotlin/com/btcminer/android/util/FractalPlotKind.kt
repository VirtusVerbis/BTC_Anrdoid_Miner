package com.btcminer.android.util

/**
 * Escapetime fractal mode for the mining chart bitmap.
 * Extend when adding renderer branches (Burning Ship, Multibrot, etc.).
 */
enum class FractalPlotKind {
    Mandelbrot,
    Julia,
    BurningShip,
    Multibrot,
    Tricorn,
    Phoenix,
    Newton,
}
