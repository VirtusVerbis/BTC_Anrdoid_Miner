package com.btcminer.android

import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.utils.MPPointF
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

object SharesDonutRimLabelHelper {

    fun hide(overlay: View, cpuTv: TextView, gpuTv: TextView) {
        overlay.visibility = View.GONE
        cpuTv.text = ""
        gpuTv.text = ""
        cpuTv.translationX = 0f
        cpuTv.translationY = 0f
        gpuTv.translationX = 0f
        gpuTv.translationY = 0f
        cpuTv.visibility = View.VISIBLE
        gpuTv.visibility = View.VISIBLE
    }

    fun forwardTouchToPieChart(chart: PieChart, overlay: View, event: MotionEvent): Boolean {
        val chartLoc = IntArray(2)
        val overlayLoc = IntArray(2)
        chart.getLocationOnScreen(chartLoc)
        overlay.getLocationOnScreen(overlayLoc)
        val relX = event.x + overlayLoc[0] - chartLoc[0]
        val relY = event.y + overlayLoc[1] - chartLoc[1]
        val copy = MotionEvent.obtain(event)
        copy.setLocation(relX, relY)
        val handled = chart.dispatchTouchEvent(copy)
        copy.recycle()
        return handled
    }

    fun updateIfHighlighted(
        chart: PieChart,
        overlay: FrameLayout,
        cpuTv: TextView,
        gpuTv: TextView,
        cpuLabel: String,
        gpuLabel: String,
    ) {
        val highlights = chart.highlighted
        if (highlights == null || highlights.isEmpty()) {
            hide(overlay, cpuTv, gpuTv)
            return
        }
        val pieData = chart.data as? PieData ?: run {
            hide(overlay, cpuTv, gpuTv)
            return
        }
        val set = pieData.dataSet as? PieDataSet ?: run {
            hide(overlay, cpuTv, gpuTv)
            return
        }
        if (set.entryCount < 2) {
            hide(overlay, cpuTv, gpuTv)
            return
        }
        val cpuEntry = set.getEntryForIndex(0) as? PieEntry ?: run {
            hide(overlay, cpuTv, gpuTv)
            return
        }
        val gpuEntry = set.getEntryForIndex(1) as? PieEntry ?: run {
            hide(overlay, cpuTv, gpuTv)
            return
        }
        val total = cpuEntry.y + gpuEntry.y
        if (total <= 0f) {
            hide(overlay, cpuTv, gpuTv)
            return
        }
        val cpuPct = (100.0 * cpuEntry.y / total).roundToInt()
        val gpuPct = (100.0 * gpuEntry.y / total).roundToInt()
        cpuTv.visibility = View.INVISIBLE
        gpuTv.visibility = View.INVISIBLE
        cpuTv.text = "$cpuLabel: $cpuPct%"
        gpuTv.text = "$gpuLabel: $gpuPct%"
        cpuTv.setTextColor(android.graphics.Color.WHITE)
        gpuTv.setTextColor(android.graphics.Color.WHITE)
        overlay.visibility = View.VISIBLE
        overlay.post {
            measureUnspecified(cpuTv)
            measureUnspecified(gpuTv)
            positionLabel(chart, overlay, cpuTv, 0)
            positionLabel(chart, overlay, gpuTv, 1)
            cpuTv.visibility = View.VISIBLE
            gpuTv.visibility = View.VISIBLE
        }
    }

    private fun measureUnspecified(v: TextView) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
    }

    /** Same geometry as PieChart.getMarkerPosition (protected); uses public getters only. */
    private fun sliceMidpointOnRingPx(chart: PieChart, entryIndex: Int): Pair<Float, Float>? {
        val center = chart.getCenterCircleBox()
        try {
            var r = chart.radius
            var off = r / 10f * 3.6f
            if (chart.isDrawHoleEnabled) {
                off = (r - (r / 100f * chart.holeRadius)) / 2f
            }
            r -= off
            val rotationAngle = chart.rotationAngle
            val drawAngles = chart.drawAngles
            val absoluteAngles = chart.absoluteAngles
            if (entryIndex < 0 || entryIndex >= drawAngles.size || entryIndex >= absoluteAngles.size) {
                return null
            }
            val halfSlice = drawAngles[entryIndex] / 2f
            val phaseY = chart.animator.phaseY
            val angleDeg = (rotationAngle + absoluteAngles[entryIndex] - halfSlice) * phaseY
            val rad = Math.toRadians(angleDeg.toDouble())
            val x = (r * cos(rad) + center.x).toFloat()
            val y = (r * sin(rad) + center.y).toFloat()
            return x to y
        } finally {
            MPPointF.recycleInstance(center)
        }
    }

    private fun positionLabel(chart: PieChart, overlay: View, tv: TextView, entryIndex: Int) {
        val (px, py) = sliceMidpointOnRingPx(chart, entryIndex) ?: return
        val c = chart.centerOffsets
        val r = chart.radius
        if (!r.isFinite() || r <= 0f) {
            return
        }
        val marginPx = 16f * chart.resources.displayMetrics.density
        val m = min(marginPx, r * 0.4f)
        val dx = px - c.x
        val dy = py - c.y
        val len = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1e-4f)
        val ux = dx / len
        val uy = dy / len
        val rimX = c.x + ux * (r + m)
        val rimY = c.y + uy * (r + m)
        val chartLoc = IntArray(2)
        val overlayLoc = IntArray(2)
        chart.getLocationOnScreen(chartLoc)
        overlay.getLocationOnScreen(overlayLoc)
        val left = rimX + chartLoc[0] - overlayLoc[0] - tv.measuredWidth / 2f
        val top = rimY + chartLoc[1] - overlayLoc[1] - tv.measuredHeight / 2f
        tv.translationX = left
        tv.translationY = top
    }
}
