package com.btcminer.android.mining

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalTreemapLayoutTest {

    @Test
    fun build_emptyReturnsNull() {
        assertNull(ThermalTreemapLayoutEngine.build(emptyList()))
    }

    @Test
    fun build_omitsEmptyGroups() {
        val readings = listOf(
            reading("cpu-0-0-usr", ThermalSensorGroup.CPU, 40.0, cluster = 0, core = 0),
            reading("gpuss-0-usr", ThermalSensorGroup.GPUSS, 35.0),
        )
        val layout = ThermalTreemapLayoutEngine.build(readings)!!
        assertEquals(2, layout.groups.size)
        assertTrue(layout.groups.none { it.group == ThermalSensorGroup.CPUSS })
        assertEquals(2, layout.cells.size)
    }

    @Test
    fun build_cpuHasSubGroupsForClusters() {
        val readings = listOf(
            reading("cpu-0-0-usr", ThermalSensorGroup.CPU, 41.0, cluster = 0, core = 0),
            reading("cpu-0-1-usr", ThermalSensorGroup.CPU, 42.0, cluster = 0, core = 1),
            reading("cpu-1-0-usr", ThermalSensorGroup.CPU, 43.0, cluster = 1, core = 0),
            reading("cpu-0-max-step", ThermalSensorGroup.CPU, 44.0, virtual = true),
        )
        val layout = ThermalTreemapLayoutEngine.build(readings)!!
        val cpuGroup = layout.groups.single { it.group == ThermalSensorGroup.CPU }
        assertTrue(cpuGroup.cells.isEmpty())
        assertEquals(3, cpuGroup.subGroups.size)
        assertEquals(4, layout.cells.size)
    }

    @Test
    fun build_singleSensor_fillsLayout() {
        val layout = ThermalTreemapLayoutEngine.build(
            listOf(reading("battery-api", ThermalSensorGroup.BATTERY_API, 25.0)),
        )!!
        assertEquals(1, layout.cells.size)
        assertEquals(ThermalTreemapLayoutEngine.LAYOUT_SIZE, layout.contentWidth)
        assertEquals(ThermalTreemapLayoutEngine.LAYOUT_SIZE, layout.contentHeight)
    }

    @Test
    fun build_cellsTileRootArea() {
        val readings = (0 until 8).map { i ->
            reading("gpuss-$i-usr", ThermalSensorGroup.GPUSS, 40.0 + i)
        }
        val layout = ThermalTreemapLayoutEngine.build(readings)!!
        val totalCellArea = layout.cells.sumOf { (it.right - it.left) * (it.bottom - it.top).toDouble() }
        val group = layout.groups.single()
        val innerTop = group.top + ThermalTreemapLayoutEngine.GROUP_HEADER_PX
        val innerArea = (group.right - group.left) * (group.bottom - innerTop)
        assertEquals(innerArea.toDouble(), totalCellArea, innerArea * 0.05)
    }

    @Test
    fun build_lastRowHeightReducedBySeventyPercent() {
        val cpuReadings = (0 until 16).flatMap { cluster ->
            (0 until 2).map { core ->
                reading(
                    "cpu-$cluster-$core-usr",
                    ThermalSensorGroup.CPU,
                    40.0 + cluster + core,
                    cluster = cluster,
                    core = core,
                )
            }
        }
        val readings = cpuReadings + listOf(
            reading("skin-0", ThermalSensorGroup.SKIN, 35.0),
            reading("battery-0", ThermalSensorGroup.BATTERY_SYSFS, 34.0),
            reading("battery-api", ThermalSensorGroup.BATTERY_API, 33.0),
        )
        val layout = ThermalTreemapLayoutEngine.build(readings)!!
        val skinGroup = layout.groups.single { it.group == ThermalSensorGroup.SKIN }
        val battGroup = layout.groups.single { it.group == ThermalSensorGroup.BATTERY_SYSFS }
        val cpuGroup = layout.groups.single { it.group == ThermalSensorGroup.CPU }
        val skinHeight = skinGroup.bottom - skinGroup.top
        assertTrue(skinHeight < cpuGroup.bottom - cpuGroup.top)
        assertEquals(ThermalTreemapLayoutEngine.LAYOUT_SIZE, skinGroup.bottom, 1f)
        assertEquals(battGroup.top, skinGroup.top, 1f)
    }

    @Test
    fun scaleToFit_aspectFitsViewport() {
        val layout = ThermalTreemapLayoutEngine.build(
            listOf(reading("cpuss-0-usr", ThermalSensorGroup.CPUSS, 40.0)),
        )!!
        val scale = ThermalTreemapLayoutEngine.scaleToFit(
            layout,
            viewportWidthPx = 400f,
            viewportHeightPx = 172f,
        )
        assertEquals(172f / ThermalTreemapLayoutEngine.LAYOUT_SIZE, scale, 0.001f)
    }

    @Test
    fun scaleToFill_wideViewport_stretchesHorizontally() {
        val layout = ThermalTreemapLayoutEngine.build(
            listOf(reading("cpuss-0-usr", ThermalSensorGroup.CPUSS, 40.0)),
        )!!
        val (scaleX, scaleY) = ThermalTreemapLayoutEngine.scaleToFill(
            layout,
            viewportWidthPx = 400f,
            viewportHeightPx = 172f,
        )
        assertEquals(400f / ThermalTreemapLayoutEngine.LAYOUT_SIZE, scaleX, 0.001f)
        assertEquals(172f / ThermalTreemapLayoutEngine.LAYOUT_SIZE, scaleY, 0.001f)
        assertTrue(scaleX > scaleY)
    }

    private fun reading(
        type: String,
        group: ThermalSensorGroup,
        tempC: Double,
        cluster: Int? = null,
        core: Int? = null,
        virtual: Boolean = false,
    ): ThermalSensorReading {
        val meta = ThermalSensorMeta(
            zoneId = 1,
            type = type,
            group = group,
            isVirtual = virtual,
            cluster = cluster,
            core = core,
            shortLabel = type,
        )
        return ThermalSensorReading(meta, tempC)
    }
}
