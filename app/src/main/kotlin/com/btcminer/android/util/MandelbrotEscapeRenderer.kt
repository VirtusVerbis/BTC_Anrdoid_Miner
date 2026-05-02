package com.btcminer.android.util

import android.graphics.Bitmap
import android.graphics.Color
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Escape-time fractal bitmap for [FractalPlotKind]; shared viewport and palette from
 * Random(sdPrev, sdNew). Julia uses fixed c at view center; Newton uses z0 = pixel basins for z^d - 1.
 */
object MandelbrotEscapeRenderer {

    private const val ESCAPE_R2 = 4f
    private val LOG_MIN = ln(1e-18)
    private val LOG_MAX = ln(1e15)

    private const val SPAN_MIN = 0.24
    private const val SPAN_MAX = 2.05
    /** Deterministic sub-pixel nudge; kept small so tight spans stay on-screen. */
    private const val JITTER_AMPLITUDE = 0.04

    /** Baseline center at uBlend=vBlend=0.5, tanhD=0 (matches pre-damping formulas). */
    private const val CENTER_ANCHOR_RE = -1.22
    private const val CENTER_ANCHOR_IM = -0.18
    /** Scales difficulty-driven pan excursions around the anchor. */
    private const val CENTER_PAN_SCALE = 0.5
    /** Visual tuning: shift Burning Ship view toward negative Im (hull). Adjust if frame still empty. */
    private const val BURNING_SHIP_CENTER_OFFSET_IM = -0.55
    /** Visual tuning: small Tricorn bias vs Mandelbrot anchor. */
    private const val TRICORN_CENTER_OFFSET_RE = -0.06
    private const val TRICORN_CENTER_OFFSET_IM = -0.12

    /** Dominant weight for session-relative zoom vs global ln(sdNew) band. */
    private const val W_SESSION_SPAN = 0.88
    /** Steepness of session zoom curve (harder within session → tighter view). */
    private const val GAMMA_SESSION_SPAN = 1.9
    /** Global span curve exponent (secondary channel after blend). */
    private const val GAMMA_GLOBAL_SPAN = 1.62
    /** Hash-derived span multiplier amplitude: span *= 1 + A_SPAN * (2*u - 1). */
    private const val A_SPAN_HASH = 0.36
    /** Primary jump sensitivity on span (tanh(dLog)). */
    private const val DLOG_SPAN_COEF = 0.48
    /** Secondary bounded jump term on span. */
    private const val DLOG_SPAN_COEF2 = 0.09

    // Distinct 64-bit salts (signed Long); not const — hex literals can exceed Kotlin const Long rules.
    private val SALT_SPAN = -2969278719257374676L
    private val SALT_JITTER = -6851491524843502115L
    private val SALT_MAXITER = -4215498970253509949L
    private val SALT_MULTIBROT = -4215498970253509947L
    private val SALT_NEWTON_D = -4215498970253509946L
    private val SALT_PHOENIX_P = -4215498970253509945L

    // Same bit patterns as 0x9E3779B97F4A7C15 / 0xBF58476D1CE4E5B9 / 0x94D049BB133111EB (Kotlin hex literals can overflow signed Long).
    private val MIX64_M1 = -7046029254386353131L
    private val MIX64_M2 = -4658895280553007687L
    private val MIX64_M3 = -7723592293110705685L

    private const val MAX_ITER_MIN = 128
    private const val MAX_ITER_MAX = 384
    private const val NEWTON_MAX_ITER = 88
    private const val NEWTON_EPS_CONV = 1e-5
    private const val NEWTON_EPS_STEP = 1e-9
    private const val NEWTON_Z_GUARD = 1e-12
    private const val NEWTON_DIVERGE = 1e8

    fun render(
        width: Int,
        height: Int,
        sdPrev: Double,
        sdNew: Double,
        sessionLnAnchor: Double,
        sessionLnPeak: Double,
        rollingLnMin: Double?,
        rollingLnMax: Double?,
        plotKind: FractalPlotKind,
    ): Bitmap {
        val w = width.coerceIn(1, 4096)
        val h = height.coerceIn(1, 4096)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val eps = 1e-18
        val lp = ln(max(sdPrev, eps))
        val lq = ln(max(sdNew, eps))

        val spanLn = max(sessionLnPeak - sessionLnAnchor, 1e-12)
        val uS = if (spanLn <= 1e-10) {
            0.5
        } else {
            ((lp - sessionLnAnchor) / spanLn).coerceIn(0.0, 1.0)
        }
        val vS = if (spanLn <= 1e-10) {
            0.5
        } else {
            ((lq - sessionLnAnchor) / spanLn).coerceIn(0.0, 1.0)
        }

        val (uBlend, vBlend) = if (
            rollingLnMin != null &&
            rollingLnMax != null &&
            rollingLnMax > rollingLnMin + 1e-14
        ) {
            val rSpan = rollingLnMax - rollingLnMin
            val uR = ((lp - rollingLnMin) / rSpan).coerceIn(0.0, 1.0)
            val vR = ((lq - rollingLnMin) / rSpan).coerceIn(0.0, 1.0)
            0.5 * uS + 0.5 * uR to 0.5 * vS + 0.5 * vR
        } else {
            uS to vS
        }

        val dLog = lq - lp
        val tanhD = tanh(min(dLog, 12.0))

        val tSession = if (spanLn <= 1e-10) {
            0.5
        } else {
            ((lq - sessionLnAnchor) / spanLn).coerceIn(0.0, 1.0)
        }

        val seed = seedFromDifficulties(sdPrev, sdNew)

        var centerRe = CENTER_ANCHOR_RE + CENTER_PAN_SCALE * (
            1.58 * (uBlend - 0.5) + 0.52 * tanhD - 0.38 * (vBlend - 0.5)
            )
        var centerIm = CENTER_ANCHOR_IM + CENTER_PAN_SCALE * (
            2.28 * (vBlend - 0.5) - 0.48 * (uBlend - 0.5) + 0.42 * tanh(dLog * 0.72)
            )

        val (jx, jy) = deterministicJitter(seed)
        centerRe += jx
        centerIm += jy

        when (plotKind) {
            FractalPlotKind.BurningShip -> centerIm += BURNING_SHIP_CENTER_OFFSET_IM
            FractalPlotKind.Tricorn -> {
                centerRe += TRICORN_CENTER_OFFSET_RE
                centerIm += TRICORN_CENTER_OFFSET_IM
            }
            else -> Unit
        }

        val spanSession = SPAN_MAX - (SPAN_MAX - SPAN_MIN) * tSession.pow(GAMMA_SESSION_SPAN)
        val tGlob = ((lq - LOG_MIN) / (LOG_MAX - LOG_MIN)).coerceIn(0.0, 1.0)
        val spanGlob = SPAN_MAX - (SPAN_MAX - SPAN_MIN) * tGlob.pow(GAMMA_GLOBAL_SPAN)
        val wGlob = 1.0 - W_SESSION_SPAN
        var span = W_SESSION_SPAN * spanSession + wGlob * spanGlob

        val unitSpan = unitFromSeed(mix64(seed, SALT_SPAN))
        span *= 1.0 + A_SPAN_HASH * (2.0 * unitSpan - 1.0)
        span *= 1.0 + DLOG_SPAN_COEF * tanhD + DLOG_SPAN_COEF2 * tanh(dLog * 0.52)
        span = span.coerceIn(SPAN_MIN, SPAN_MAX)

        val aspect = w.toDouble() / h.toDouble()
        val spanRe = span * aspect
        val spanIm = span

        val reMin = centerRe - spanRe / 2.0
        val reMax = centerRe + spanRe / 2.0
        val imMin = centerIm - spanIm / 2.0
        val imMax = centerIm + spanIm / 2.0

        val maxIterEscape = computeMaxIter(seed, lq, dLog, tSession)
        val maxIterDraw = if (plotKind == FractalPlotKind.Newton) NEWTON_MAX_ITER else maxIterEscape

        val multibrotPower = multibrotExponentFromSeed(seed)
        val newtonDegree = newtonDegreeFromSeed(seed)
        val phoenixPr: Float
        val phoenixPi: Float
        run {
            val m = mix64(seed, SALT_PHOENIX_P)
            phoenixPr = ((unitFromSeed(m) * 2.0 - 1.0) * 0.35).toFloat()
            phoenixPi = ((unitFromSeed(mix64(m, SALT_PHOENIX_P xor 1L)) * 2.0 - 1.0) * 0.35).toFloat()
        }

        val rnd = Random(seed)
        val hueBase = rnd.nextFloat() * 360f
        val hueSpread = 100f + rnd.nextFloat() * 140f
        val satBase = 0.82f + rnd.nextFloat() * 0.18f
        val valBase = 0.88f + rnd.nextFloat() * 0.12f

        val wm1 = (w - 1).coerceAtLeast(1)
        val hm1 = (h - 1).coerceAtLeast(1)

        val juliaCr = centerRe.toFloat()
        val juliaCi = centerIm.toFloat()

        val pixels = IntArray(w * h)
        var idx = 0
        for (py in 0 until h) {
            val zIm0 = imMax - (imMax - imMin) * py / hm1
            for (px in 0 until w) {
                val zRe0 = reMin + (reMax - reMin) * px / wm1
                val iter = when (plotKind) {
                    FractalPlotKind.Mandelbrot -> iterateMandelbrot(zRe0, zIm0, maxIterEscape)
                    FractalPlotKind.Julia -> iterateJulia(zRe0, zIm0, juliaCr, juliaCi, maxIterEscape)
                    FractalPlotKind.BurningShip -> iterateBurningShip(zRe0, zIm0, maxIterEscape)
                    FractalPlotKind.Multibrot -> iterateMultibrot(zRe0, zIm0, multibrotPower, maxIterEscape)
                    FractalPlotKind.Tricorn -> iterateTricorn(zRe0, zIm0, maxIterEscape)
                    FractalPlotKind.Phoenix -> iteratePhoenix(zRe0, zIm0, phoenixPr, phoenixPi, maxIterEscape)
                    FractalPlotKind.Newton -> iterateNewtonBasins(zRe0, zIm0, newtonDegree, NEWTON_MAX_ITER)
                }
                pixels[idx++] = colorForIter(iter, maxIterDraw, hueBase, hueSpread, satBase, valBase)
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun computeMaxIter(seed: Long, lq: Double, dLog: Double, tSession: Double): Int {
        val m = mix64(seed, SALT_MAXITER)
        val h1 = (m xor (m ushr 33)).toInt()
        val h2 = (lq.toRawBits() xor dLog.toRawBits()).toInt()
        val h3 = (tSession * 1_000_000.0).toLong().toInt()
        val mix = abs(h1 xor h2 xor h3) % (MAX_ITER_MAX - MAX_ITER_MIN + 1)
        return MAX_ITER_MIN + mix
    }

    /** Splitmix64-style mixing for independent deterministic channels. */
    private fun mix64(seed: Long, salt: Long): Long {
        var z = seed xor salt
        z *= MIX64_M1
        z = z xor (z ushr 30)
        z *= MIX64_M2
        z = z xor (z ushr 27)
        z *= MIX64_M3
        z = z xor (z ushr 31)
        return z
    }

    private fun unitFromSeed(mixed: Long): Double {
        val u = (mixed ushr 32) and 0xFFFFFFFFL
        return (u.toDouble() / (1L shl 32).toDouble()).coerceIn(0.0, 1.0 - 1e-15)
    }

    private fun deterministicJitter(seed: Long): Pair<Double, Double> {
        val m1 = mix64(seed, SALT_JITTER)
        val m2 = mix64(m1, SALT_JITTER xor 1L)
        val nx = unitFromSeed(m1)
        val ny = unitFromSeed(m2)
        val jx = (nx * 2.0 - 1.0) * JITTER_AMPLITUDE
        val jy = (ny * 2.0 - 1.0) * JITTER_AMPLITUDE
        return jx to jy
    }

    private fun seedFromDifficulties(sdPrev: Double, sdNew: Double): Long {
        val a = sdPrev.toRawBits()
        val b = sdNew.toRawBits()
        return a xor (b shl 17) xor (b ushr 47)
    }

    private fun multibrotExponentFromSeed(seed: Long): Int =
        abs(mix64(seed, SALT_MULTIBROT).toInt()) % 3 + 3

    private fun newtonDegreeFromSeed(seed: Long): Int =
        abs(mix64(seed, SALT_NEWTON_D).toInt()) % 3 + 3

    private fun iterateMandelbrot(zRe0: Double, zIm0: Double, maxIter: Int): Int {
        var zr = 0f
        var zi = 0f
        val cRe = zRe0.toFloat()
        val cIm = zIm0.toFloat()
        var iter = 0
        while (iter < maxIter) {
            val zr2 = zr * zr
            val zi2 = zi * zi
            if (zr2 + zi2 > ESCAPE_R2) break
            val two = 2f * zr * zi
            zr = zr2 - zi2 + cRe
            zi = two + cIm
            iter++
        }
        return iter
    }

    private fun iterateJulia(zRe0: Double, zIm0: Double, juliaCr: Float, juliaCi: Float, maxIter: Int): Int {
        var zr = zRe0.toFloat()
        var zi = zIm0.toFloat()
        var iter = 0
        while (iter < maxIter) {
            val zr2 = zr * zr
            val zi2 = zi * zi
            if (zr2 + zi2 > ESCAPE_R2) break
            val two = 2f * zr * zi
            zr = zr2 - zi2 + juliaCr
            zi = two + juliaCi
            iter++
        }
        return iter
    }

    private fun iterateBurningShip(zRe0: Double, zIm0: Double, maxIter: Int): Int {
        var x = 0f
        var y = 0f
        val cr = zRe0.toFloat()
        val ci = zIm0.toFloat()
        var iter = 0
        while (iter < maxIter) {
            val xx = x * x
            val yy = y * y
            if (xx + yy > ESCAPE_R2) break
            val xy = x * y
            x = xx - yy + cr
            y = 2f * abs(xy) + ci
            iter++
        }
        return iter
    }

    private fun iterateMultibrot(zRe0: Double, zIm0: Double, n: Int, maxIter: Int): Int {
        var zr = 0f
        var zi = 0f
        val cr = zRe0.toFloat()
        val ci = zIm0.toFloat()
        var iter = 0
        while (iter < maxIter) {
            val r = hypot(zr.toDouble(), zi.toDouble())
            val theta = atan2(zi.toDouble(), zr.toDouble())
            val rn = r.pow(n)
            val nt = theta * n
            zr = (rn * cos(nt)).toFloat() + cr
            zi = (rn * sin(nt)).toFloat() + ci
            if (zr * zr + zi * zi > ESCAPE_R2) break
            iter++
        }
        return iter
    }

    private fun iterateTricorn(zRe0: Double, zIm0: Double, maxIter: Int): Int {
        var x = 0f
        var y = 0f
        val cr = zRe0.toFloat()
        val ci = zIm0.toFloat()
        var iter = 0
        while (iter < maxIter) {
            val xx = x * x
            val yy = y * y
            if (xx + yy > ESCAPE_R2) break
            val xNew = xx - yy + cr
            val yNew = -2f * x * y + ci
            x = xNew
            y = yNew
            iter++
        }
        return iter
    }

    private fun iteratePhoenix(zRe0: Double, zIm0: Double, pr: Float, pi: Float, maxIter: Int): Int {
        var zpr = 0f
        var zpi = 0f
        var zr = 0f
        var zi = 0f
        val cr = zRe0.toFloat()
        val ci = zIm0.toFloat()
        var iter = 0
        while (iter < maxIter) {
            val zr2 = zr * zr
            val zi2 = zi * zi
            if (zr2 + zi2 > ESCAPE_R2) break
            val pzr = pr * zpr - pi * zpi
            val pzi = pr * zpi + pi * zpr
            val two = 2f * zr * zi
            val zNewR = zr2 - zi2 + pzr + cr
            val zNewI = two + pzi + ci
            zpr = zr
            zpi = zi
            zr = zNewR
            zi = zNewI
            iter++
        }
        return iter
    }

    private fun complexPowDouble(x: Double, y: Double, n: Int): Pair<Double, Double> {
        if (n == 0) return 1.0 to 0.0
        val r = hypot(x, y)
        if (r < NEWTON_Z_GUARD) return 0.0 to 0.0
        val theta = atan2(y, x)
        val rn = r.pow(n)
        val nt = theta * n
        return rn * cos(nt) to rn * sin(nt)
    }

    private fun iterateNewtonBasins(zRe0: Double, zIm0: Double, d: Int, maxIter: Int): Int {
        var zr = zRe0
        var zi = zIm0
        var iter = 0
        while (iter < maxIter) {
            val (powR, powI) = complexPowDouble(zr, zi, d)
            val numerR = powR - 1.0
            val numerI = powI
            val (dPowR, dPowI) = complexPowDouble(zr, zi, d - 1)
            val dr = d.toDouble() * dPowR
            val di = d.toDouble() * dPowI
            val denomMag2 = dr * dr + di * di
            if (denomMag2 < NEWTON_Z_GUARD * NEWTON_Z_GUARD) break
            val nr = numerR * dr + numerI * di
            val ni = numerI * dr - numerR * di
            val deltaR = nr / denomMag2
            val deltaI = ni / denomMag2
            val zrNew = zr - deltaR
            val ziNew = zi - deltaI
            if (hypot(zrNew, ziNew) > NEWTON_DIVERGE) {
                return maxIter
            }
            val fdist = hypot(powR - 1.0, powI)
            if (fdist < NEWTON_EPS_CONV) break
            val step = hypot(zrNew - zr, ziNew - zi)
            if (step < NEWTON_EPS_STEP) break
            zr = zrNew
            zi = ziNew
            iter++
        }
        return iter
    }

    private fun colorForIter(
        iter: Int,
        maxIter: Int,
        hueBase: Float,
        hueSpread: Float,
        satBase: Float,
        valBase: Float,
    ): Int {
        if (iter >= maxIter) {
            return Color.rgb(8, 4, 18)
        }
        val t = iter / maxIter.toFloat()
        val h = (hueBase + t * hueSpread + iter * 3.7f) % 360f
        val s = satBase.coerceIn(0f, 1f)
        val v = valBase.coerceIn(0f, 1f)
        return Color.HSVToColor(floatArrayOf(h, s, v))
    }
}
