package com.btcminer.android.mining

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalSquarifyTest {

    @Test
    fun layout_singleItem_fillsRect() {
        val rect = ThermalSquarify.Box(0f, 0f, 100f, 100f)
        val placed = ThermalSquarify.layoutTyped(
            listOf(ThermalSquarify.Item(1.0, "a")),
            rect,
        )
        assertEquals(1, placed.size)
        assertEquals(rect, placed[0].box)
    }

    @Test
    fun layout_twoItems_partitionArea() {
        val rect = ThermalSquarify.Box(0f, 0f, 100f, 100f)
        val placed = ThermalSquarify.layoutTyped(
            listOf(
                ThermalSquarify.Item(1.0, "a"),
                ThermalSquarify.Item(1.0, "b"),
            ),
            rect,
        )
        assertEquals(2, placed.size)
        val totalArea = placed.sumOf { it.box.area.toDouble() }
        assertEquals(10000.0, totalArea, 1.0)
    }

    @Test
    fun layout_manyItems_tilesWithoutOverlap() {
        val rect = ThermalSquarify.Box(0f, 0f, 200f, 200f)
        val items = (1..12).map { ThermalSquarify.Item(1.0, it) }
        val placed = ThermalSquarify.layoutTyped(items, rect)
        assertEquals(12, placed.size)
        val totalArea = placed.sumOf { it.box.area.toDouble() }
        assertEquals(40000.0, totalArea, 2.0)
        for (i in placed.indices) {
            for (j in i + 1 until placed.size) {
                assertTrue(!overlaps(placed[i].box, placed[j].box))
            }
        }
    }

    @Test
    fun innerBox_reservesHeader() {
        val outer = ThermalSquarify.Box(0f, 0f, 100f, 50f)
        val inner = ThermalSquarify.innerBox(outer, headerHeight = 10f)
        assertEquals(10f, inner.top)
        assertEquals(50f, inner.bottom)
        assertEquals(100f, inner.width)
    }

    private fun overlaps(a: ThermalSquarify.Box, b: ThermalSquarify.Box): Boolean {
        if (a.right <= b.left || b.right <= a.left) return false
        if (a.bottom <= b.top || b.bottom <= a.top) return false
        return true
    }
}
