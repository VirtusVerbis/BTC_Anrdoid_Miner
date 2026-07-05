package com.btcminer.android.ui.chart

import android.graphics.Canvas
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.renderer.YAxisRenderer
import com.github.mikephil.charting.utils.Transformer
import com.github.mikephil.charting.utils.Utils
import com.github.mikephil.charting.utils.ViewPortHandler

/**
 * Draws left Y-axis labels with newline support for stacked dual-scale ticks (Mhz + Ms).
 */
class TelemetryLeftYAxisRenderer(
    viewPortHandler: ViewPortHandler,
    yAxis: YAxis,
    trans: Transformer,
) : YAxisRenderer(viewPortHandler, yAxis, trans) {

    override fun drawYLabels(c: Canvas, fixedPosition: Float, positions: FloatArray, offset: Float) {
        val from = if (mYAxis.isDrawBottomYLabelEntryEnabled) 0 else 1
        val to = if (mYAxis.isDrawTopYLabelEntryEnabled) {
            mYAxis.mEntryCount
        } else {
            mYAxis.mEntryCount - 1
        }

        val lineHeight = Utils.getLineHeight(mAxisLabelPaint)

        for (i in from until to) {
            val text = mYAxis.getFormattedLabel(i)
            val tickY = positions[i * 2 + 1] + offset

            if (!text.contains('\n')) {
                c.drawText(text, fixedPosition, tickY, mAxisLabelPaint)
                continue
            }

            val lines = text.split('\n')
            val startBaseline = tickY - (lines.size - 1) * lineHeight / 2f
            lines.forEachIndexed { index, line ->
                c.drawText(line, fixedPosition, startBaseline + index * lineHeight, mAxisLabelPaint)
            }
        }
    }
}
