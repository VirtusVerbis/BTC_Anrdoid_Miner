package com.btcminer.android.mining

import kotlin.math.max
import kotlin.math.min

/**
 * Pure-Kotlin squarified treemap (Bruls et al.).
 */
internal object ThermalSquarify {

    data class Item<T>(val weight: Double, val value: T)

    data class Box(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val area: Float get() = width * height
    }

    data class Placed<T>(val box: Box, val value: T)

    fun <T> layoutTyped(items: List<Item<T>>, rect: Box): List<Placed<T>> {
        if (items.isEmpty()) return emptyList()
        if (items.size == 1) {
            return listOf(Placed(rect, items.first().value))
        }
        val totalWeight = items.sumOf { it.weight }
        if (totalWeight <= 0.0) return emptyList()
        val sorted = items.sortedByDescending { it.weight }
        return squarify(sorted, emptyList(), rect, totalWeight)
    }

    private fun <T> squarify(
        items: List<Item<T>>,
        row: List<Item<T>>,
        rect: Box,
        totalWeight: Double,
    ): List<Placed<T>> {
        if (items.isEmpty()) {
            return layoutRow(row, rect, totalWeight)
        }
        val next = items.first()
        val extended = row + next
        if (row.isEmpty() || worst(extended, rect, totalWeight) <= worst(row, rect, totalWeight)) {
            return squarify(items.drop(1), extended, rect, totalWeight)
        }
        val placed = layoutRow(row, rect, totalWeight)
        val rowWeight = row.sumOf { it.weight }
        val rowArea = rect.area * (rowWeight / totalWeight).toFloat()
        val horizontal = rect.width >= rect.height
        val thickness = if (horizontal) {
            rowArea / rect.width
        } else {
            rowArea / rect.height
        }
        val leftover = if (horizontal) {
            Box(rect.left, rect.top + thickness, rect.right, rect.bottom)
        } else {
            Box(rect.left + thickness, rect.top, rect.right, rect.bottom)
        }
        val remainingWeight = items.sumOf { it.weight }
        return placed + squarify(items, emptyList(), leftover, remainingWeight)
    }

    private fun <T> worst(row: List<Item<T>>, rect: Box, totalWeight: Double): Double {
        if (row.isEmpty()) return Double.MAX_VALUE
        val rowWeight = row.sumOf { it.weight }
        val length = if (rect.width >= rect.height) rect.width else rect.height
        if (length <= 0f || totalWeight <= 0.0) return Double.MAX_VALUE
        val rowArea = rect.area * (rowWeight / totalWeight)
        val thickness = rowArea / length
        if (thickness <= 0f) return Double.MAX_VALUE
        var worstRatio = 0.0
        for (item in row) {
            val itemArea = rect.area * (item.weight / totalWeight)
            val itemLength = itemArea / thickness
            val ratio = max(thickness / itemLength, itemLength / thickness)
            worstRatio = max(worstRatio, ratio.toDouble())
        }
        return worstRatio
    }

    private fun <T> layoutRow(row: List<Item<T>>, rect: Box, totalWeight: Double): List<Placed<T>> {
        if (row.isEmpty()) return emptyList()
        val horizontal = rect.width >= rect.height
        val length = if (horizontal) rect.width else rect.height
        val rowWeight = row.sumOf { it.weight }
        val rowArea = rect.area * (rowWeight / totalWeight).toFloat()
        val thickness = rowArea / length
        val placed = ArrayList<Placed<T>>(row.size)
        var offset = 0f
        for (item in row) {
            val itemArea = rect.area * (item.weight / totalWeight).toFloat()
            val itemLength = itemArea / thickness
            val box = if (horizontal) {
                Box(
                    rect.left + offset,
                    rect.top,
                    rect.left + offset + itemLength,
                    rect.top + thickness,
                )
            } else {
                Box(
                    rect.left,
                    rect.top + offset,
                    rect.left + thickness,
                    rect.top + offset + itemLength,
                )
            }
            placed.add(Placed(box, item.value))
            offset += itemLength
        }
        return placed
    }

    fun innerBox(outer: Box, headerHeight: Float): Box =
        Box(outer.left, outer.top + headerHeight, outer.right, outer.bottom)
}
