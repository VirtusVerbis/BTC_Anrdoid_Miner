package com.btcminer.android

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.appcompat.widget.AppCompatTextView
import com.github.mikephil.charting.charts.PieChart

/**
 * Positions [centerLabel] over the pie hole and sizes it so [AppCompatTextView] uniform autosize
 * can shrink the total count to fit. Requires [chart] to have completed layout ([radius] valid).
 */
object SharesDonutCenterLabelHelper {

    private const val HOLE_MARGIN = 0.88f

    fun updateLayout(chart: PieChart, centerLabel: View) {
        if (centerLabel.parent !is FrameLayout) return
        if (chart.width <= 0 || chart.height <= 0) return
        val r = chart.radius
        if (!r.isFinite() || r <= 0f) return
        val holeDiameter = 2f * r * (chart.holeRadius / 100f) * HOLE_MARGIN
        val maxBox = minOf(chart.width, chart.height)
        val size = holeDiameter.toInt().coerceIn(1, maxBox)
        val c = chart.centerOffsets
        val left = (c.x - size / 2f).toInt().coerceAtLeast(0)
        val top = (c.y - size / 2f).toInt().coerceAtLeast(0)
        val lp = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START)
        lp.leftMargin = left
        lp.topMargin = top
        centerLabel.layoutParams = lp
        centerLabel.requestLayout()
    }

    fun setContent(centerLabel: AppCompatTextView, text: CharSequence, @ColorInt textColor: Int, maxLines: Int) {
        centerLabel.maxLines = maxLines
        centerLabel.setTextColor(textColor)
        centerLabel.text = text
        centerLabel.visibility = View.VISIBLE
    }
}
