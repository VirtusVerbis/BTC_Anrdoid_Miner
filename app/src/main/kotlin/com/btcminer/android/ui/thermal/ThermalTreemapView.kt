package com.btcminer.android.ui.thermal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.btcminer.android.R
import com.btcminer.android.mining.ThermalCellRect
import com.btcminer.android.mining.ThermalColorScale
import com.btcminer.android.mining.ThermalGroupLayout
import com.btcminer.android.mining.ThermalSubGroupLayout
import com.btcminer.android.mining.ThermalTempFormat
import com.btcminer.android.mining.ThermalTreemapLayout
import com.btcminer.android.mining.ThermalTreemapLayoutEngine
import com.btcminer.android.mining.ThermalUiState

class ThermalTreemapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var uiState: ThermalUiState? = null
    private var useFahrenheit: Boolean = false
    private var scaleX: Float = 1f
    private var scaleY: Float = 1f
    private var drawOffsetX: Float = 0f
    private var drawOffsetY: Float = 0f

    private val density get() = resources.displayMetrics.density

    private val groupHeaderBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2D2D2D.toInt()
    }
    private val groupHeaderTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val subHeaderTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val clusterLabelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.thermal_group_outline)
    }
    private val cellTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = ContextCompat.getColor(context, R.color.chart_axis_legend)
    }
    private val groupOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = ContextCompat.getColor(context, R.color.thermal_group_outline)
    }
    private val dashedBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.WHITE
        pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f)
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 13f * resources.displayMetrics.density
        color = ContextCompat.getColor(context, R.color.chart_axis_legend)
    }
    private val cellFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val textBounds = Rect()

    var onCellTapped: ((ThermalCellRect) -> Unit)? = null
    var onBackgroundTapped: (() -> Unit)? = null

    fun bind(state: ThermalUiState?, useFahrenheit: Boolean) {
        this.uiState = state
        this.useFahrenheit = useFahrenheit
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val height = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val state = uiState
        val layout = state?.layout
        if (layout == null || state.readings.isEmpty()) {
            canvas.drawText(
                context.getString(R.string.thermal_chart_unavailable),
                width / 2f,
                height / 2f,
                emptyPaint,
            )
            return
        }

        computeTransform(layout)

        canvas.save()
        canvas.translate(drawOffsetX, drawOffsetY)
        canvas.scale(scaleX, scaleY)

        for (cell in layout.cells) {
            drawCell(canvas, cell)
        }
        canvas.restore()

        groupHeaderTextPaint.textSize = 8.5f * 0.95f * density
        subHeaderTextPaint.textSize = 6.5f * density

        for (group in layout.groups) {
            drawGroupHeader(canvas, group)
            drawGroupOutline(canvas, group)
            for (sub in group.subGroups) {
                drawSubGroupCornerLabel(canvas, sub)
            }
        }
    }

    private fun computeTransform(layout: ThermalTreemapLayout) {
        val pad = 3f * density
        val (sx, sy) = ThermalTreemapLayoutEngine.scaleToFill(
            layout,
            viewportWidthPx = width.toFloat(),
            viewportHeightPx = height.toFloat(),
            paddingPx = pad,
        )
        scaleX = sx
        scaleY = sy
        drawOffsetX = pad
        drawOffsetY = pad
    }

    private fun drawCell(canvas: Canvas, cell: ThermalCellRect) {
        val color = ThermalColorScale.colorForGroup(cell.meta.group, cell.reading.tempC)
        cellFillPaint.color = color
        rect.set(cell.left, cell.top, cell.right, cell.bottom)
        canvas.drawRect(rect, cellFillPaint)
        if (cell.meta.isVirtual) {
            canvas.drawRect(rect, dashedBorderPaint)
        } else {
            canvas.drawRect(rect, borderPaint)
        }
        val label = ThermalTempFormat.formatCellLabel(cell.reading.tempC, useFahrenheit)
        val cellW = cell.right - cell.left
        val cellH = cell.bottom - cell.top
        cellTextPaint.textSize = fitTextSize(cellTextPaint, label, cellW * 0.85f, cellH * 0.55f)
        val cx = (cell.left + cell.right) / 2f
        val cy = (cell.top + cell.bottom) / 2f - (cellTextPaint.descent() + cellTextPaint.ascent()) / 2f
        canvas.drawText(label, cx, cy, cellTextPaint)
    }

    private fun drawGroupHeader(canvas: Canvas, group: ThermalGroupLayout) {
        val left = drawOffsetX + group.left * scaleX
        val top = drawOffsetY + group.top * scaleY
        val right = drawOffsetX + group.right * scaleX
        val bottom = top + ThermalTreemapLayoutEngine.GROUP_HEADER_PX * scaleY
        canvas.drawRect(left, top, right, bottom, groupHeaderBgPaint)

        val pad = 8f * density
        val label = group.title.uppercase()
        val barHeight = bottom - top
        val textY = top + barHeight / 2f - (groupHeaderTextPaint.ascent() + groupHeaderTextPaint.descent()) / 2f

        canvas.save()
        canvas.clipRect(left, top, right, bottom)
        canvas.drawText(label, left + pad, textY, groupHeaderTextPaint)
        canvas.restore()
    }

    private fun drawGroupOutline(canvas: Canvas, group: ThermalGroupLayout) {
        val left = drawOffsetX + group.left * scaleX
        val top = drawOffsetY + group.top * scaleY
        val right = drawOffsetX + group.right * scaleX
        val bottom = drawOffsetY + group.bottom * scaleY
        canvas.drawRect(left, top, right, bottom, groupOutlinePaint)
    }

    private fun drawSubGroupCornerLabel(canvas: Canvas, sub: ThermalSubGroupLayout) {
        val anchorLeft = drawOffsetX + sub.left * scaleX
        val anchorTop = drawOffsetY + sub.top * scaleY
        val padH = 2f * density
        val padV = 1f * density
        val margin = 1f * density

        subHeaderTextPaint.getTextBounds(sub.title, 0, sub.title.length, textBounds)
        val badgeLeft = anchorLeft + margin
        val badgeTop = anchorTop + margin
        val badgeRight = badgeLeft + textBounds.width() + padH * 2f
        val badgeBottom = badgeTop + (subHeaderTextPaint.descent() - subHeaderTextPaint.ascent()) + padV * 2f

        canvas.drawRect(badgeLeft, badgeTop, badgeRight, badgeBottom, clusterLabelBgPaint)

        val textX = badgeLeft + padH
        val textY = badgeTop + padV - subHeaderTextPaint.ascent()
        canvas.drawText(sub.title, textX, textY, subHeaderTextPaint)
    }

    private fun fitTextSize(paint: Paint, text: String, maxWidth: Float, maxHeight: Float): Float {
        val minSize = 6f * density
        val maxSize = 14f * density
        var lo = minSize
        var hi = maxSize
        while (lo < hi) {
            val mid = (lo + hi + 0.5f) / 2f
            paint.textSize = mid
            val w = paint.measureText(text)
            val h = paint.descent() - paint.ascent()
            if (w <= maxWidth && h <= maxHeight) {
                lo = mid
            } else {
                hi = mid - 0.5f
            }
        }
        return lo.coerceIn(minSize, maxSize)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val layout = uiState?.layout ?: return true
        val x = (event.x - drawOffsetX) / scaleX
        val y = (event.y - drawOffsetY) / scaleY
        val hit = layout.cells.lastOrNull { cell ->
            x >= cell.left && x <= cell.right && y >= cell.top && y <= cell.bottom
        }
        if (hit != null) {
            onCellTapped?.invoke(hit)
        } else {
            onBackgroundTapped?.invoke()
        }
        return true
    }
}
