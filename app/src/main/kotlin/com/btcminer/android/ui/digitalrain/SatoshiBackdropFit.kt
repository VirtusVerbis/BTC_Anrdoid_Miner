package com.btcminer.android.ui.digitalrain

import android.graphics.RectF
import kotlin.math.min

/** Letterboxed rect for `ImageView.ScaleType.FIT_CENTER` parity (CPU canvas + GL quad). */
object SatoshiBackdropFit {

    fun fitCenterRectF(bitmapW: Int, bitmapH: Int, viewW: Int, viewH: Int): RectF {
        if (bitmapW <= 0 || bitmapH <= 0 || viewW <= 0 || viewH <= 0) {
            return RectF(0f, 0f, viewW.toFloat(), viewH.toFloat())
        }
        val bw = bitmapW.toFloat()
        val bh = bitmapH.toFloat()
        val vw = viewW.toFloat()
        val vh = viewH.toFloat()
        val scale = min(vw / bw, vh / bh)
        val dw = bw * scale
        val dh = bh * scale
        val left = (vw - dw) * 0.5f
        val top = (vh - dh) * 0.5f
        return RectF(left, top, left + dw, top + dh)
    }
}
