package com.btcminer.android.ui.thermal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.btcminer.android.R
import com.btcminer.android.mining.ThermalColorScale
import com.btcminer.android.mining.ThermalTempFormat

class ThermalLegendView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var useFahrenheit: Boolean = false
    private var tooltipText: String? = null

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 8f * resources.displayMetrics.density
        color = ContextCompat.getColor(context, R.color.chart_axis_legend)
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 7.5f * resources.displayMetrics.density
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xEE212121.toInt()
    }
    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * resources.displayMetrics.density
        color = Color.WHITE
    }
    private val barRect = RectF()

    private var band = ThermalColorScale.UNIFIED_BAND
    private var titleText: String = ""

    fun setLegendBand(band: ThermalColorScale.Band, title: String) {
        this.band = band
        this.titleText = title
        invalidate()
    }

    fun setUseFahrenheit(useFahrenheit: Boolean) {
        if (this.useFahrenheit == useFahrenheit) return
        this.useFahrenheit = useFahrenheit
        invalidate()
    }

    fun showTooltip(text: String?) {
        tooltipText = text
        invalidate()
    }

    fun dismissTooltip() {
        if (tooltipText == null) return
        tooltipText = null
        invalidate()
    }

    fun hasTooltip(): Boolean = tooltipText != null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val density = resources.displayMetrics.density
        val labelW = 52f * density
        val barLeft = labelW + 2f
        val barRight = width - 2f
        val barTop = 1f
        val barBottom = barTop + 9f * density
        barRect.set(barLeft, barTop, barRight, barBottom)

        canvas.drawText(titleText, 2f, barBottom - 2f, labelPaint)

        val colors = ThermalColorScale.legendGradientColors(band)
        barPaint.shader = LinearGradient(
            barLeft,
            barTop,
            barRight,
            barTop,
            colors,
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(barRect, barPaint)
        barPaint.shader = null

        val bp = band.breakpointsC
        val tickY = (barTop + barBottom) / 2f - (tickPaint.ascent() + tickPaint.descent()) / 2f
        bp.forEachIndexed { index, tickC ->
            val t = ((tickC - bp.first()) / (bp.last() - bp.first())).toFloat().coerceIn(0f, 1f)
            val x = barLeft + t * (barRight - barLeft)
            val tickLabel = ThermalTempFormat.formatTickLabel(tickC, useFahrenheit)
            val textW = tickPaint.measureText(tickLabel)
            val drawX = when (index) {
                0 -> barLeft
                bp.lastIndex -> barRight - textW
                else -> x - textW / 2f
            }
            canvas.drawText(tickLabel, drawX, tickY, tickPaint)
        }

        val text = tooltipText
        if (text != null) {
            val padH = 10f * density
            val padV = 6f * density
            val lines = text.split('\n')
            val lineHeight = tooltipTextPaint.fontSpacing
            val textWidth = lines.maxOf { tooltipTextPaint.measureText(it) }
            val overlayW = textWidth + padH * 2
            val overlayH = lineHeight * lines.size + padV * 2
            val overlayLeft = barLeft + (barRect.width() - overlayW) / 2f
            val overlayBottom = barTop + 2f * density
            val overlayTop = overlayBottom - overlayH
            canvas.drawRect(overlayLeft, overlayTop, overlayLeft + overlayW, overlayBottom, tooltipBgPaint)
            lines.forEachIndexed { index, line ->
                val y = overlayTop + padV + lineHeight * (index + 1) - tooltipTextPaint.descent()
                canvas.drawText(line, overlayLeft + padH, y, tooltipTextPaint)
            }
        }
    }
}
