package com.btcminer.android.mining

import kotlin.math.min
import kotlin.math.sqrt

data class ThermalCellRect(
    val meta: ThermalSensorMeta,
    val reading: ThermalSensorReading,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class ThermalSubGroupLayout(
    val title: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val cells: List<ThermalCellRect>,
)

data class ThermalGroupLayout(
    val group: ThermalSensorGroup,
    val title: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val cells: List<ThermalCellRect>,
    val subGroups: List<ThermalSubGroupLayout> = emptyList(),
)

data class ThermalTreemapLayout(
    val contentWidth: Float,
    val contentHeight: Float,
    val groups: List<ThermalGroupLayout>,
    val cells: List<ThermalCellRect>,
)

object ThermalTreemapLayoutEngine {

    const val LAYOUT_SIZE = 1000f
    const val GROUP_HEADER_PX = 40f
    const val SUBGROUP_HEADER_PX = 0f
    /** @deprecated Use [GROUP_HEADER_PX]; kept for any external references. */
    const val HEADER_HEIGHT_PX = GROUP_HEADER_PX

    private const val MIN_ROOT_GROUP_WEIGHT = 5.0
    /** Retain 30% of last-row height (reduce bottom row height by 70%). */
    private const val LAST_ROW_HEIGHT_RETAIN = 0.3f

    private val LAST_ROW_GROUPS = setOf(
        ThermalSensorGroup.SKIN,
        ThermalSensorGroup.BATTERY_SYSFS,
        ThermalSensorGroup.BATTERY_API,
    )

    private val GROUP_ORDER = listOf(
        ThermalSensorGroup.CPUSS,
        ThermalSensorGroup.CPU,
        ThermalSensorGroup.GPUSS,
        ThermalSensorGroup.GPU,
        ThermalSensorGroup.SKIN,
        ThermalSensorGroup.BATTERY_SYSFS,
        ThermalSensorGroup.BATTERY_API,
    )

    private val GROUP_TITLES = mapOf(
        ThermalSensorGroup.CPUSS to "CPUSS",
        ThermalSensorGroup.CPU to "CPU",
        ThermalSensorGroup.GPUSS to "GPUSS",
        ThermalSensorGroup.GPU to "GPU",
        ThermalSensorGroup.SKIN to "SKIN",
        ThermalSensorGroup.BATTERY_SYSFS to "BATT",
        ThermalSensorGroup.BATTERY_API to "BATT-API",
    )

    private data class GroupBuild(
        val group: ThermalSensorGroup,
        val groupLayout: ThermalGroupLayout,
        val weight: Double,
    )

    fun build(readings: List<ThermalSensorReading>): ThermalTreemapLayout? {
        if (readings.isEmpty()) return null
        val byGroup = readings.groupBy { it.meta.group }
        val activeGroups = GROUP_ORDER.mapNotNull { g ->
            val list = byGroup[g].orEmpty()
            if (list.isEmpty()) null else g to list
        }
        if (activeGroups.isEmpty()) return null

        val root = ThermalSquarify.Box(0f, 0f, LAYOUT_SIZE, LAYOUT_SIZE)
        val groupItems = activeGroups.map { (group, list) ->
            ThermalSquarify.Item(groupRootWeight(list), group to list)
        }
        val placedGroups = ThermalSquarify.layoutTyped(groupItems, root)
        val adjustedPlacements = compressLastRowGroups(placedGroups)

        val builtGroups = adjustedPlacements.map { placed ->
            val (group, list) = placed.value
            buildGroupLayout(group, list, placed.box)
        }
        val allCells = builtGroups.flatMap { it.groupLayout.allCells() }

        return ThermalTreemapLayout(
            contentWidth = LAYOUT_SIZE,
            contentHeight = LAYOUT_SIZE,
            groups = builtGroups.map { it.groupLayout },
            cells = allCells,
        )
    }

    private fun groupRootWeight(readings: List<ThermalSensorReading>): Double {
        val n = readings.size.toDouble()
        return sqrt(n).coerceAtLeast(MIN_ROOT_GROUP_WEIGHT)
    }

    private fun compressLastRowGroups(
        placed: List<ThermalSquarify.Placed<Pair<ThermalSensorGroup, List<ThermalSensorReading>>>>,
    ): List<ThermalSquarify.Placed<Pair<ThermalSensorGroup, List<ThermalSensorReading>>>> {
        if (placed.size <= 1) return placed
        val epsilon = 1f
        val tailPlaced = placed.filter { it.value.first in LAST_ROW_GROUPS }
        if (tailPlaced.isEmpty()) return placed
        val rowTop = tailPlaced.minOf { it.box.top }
        val rowHeight = LAYOUT_SIZE - rowTop
        val newRowTop = LAYOUT_SIZE - rowHeight * LAST_ROW_HEIGHT_RETAIN
        return placed.map { item ->
            val box = item.box
            val group = item.value.first
            val newBox = when {
                group in LAST_ROW_GROUPS ->
                    ThermalSquarify.Box(box.left, newRowTop, box.right, LAYOUT_SIZE)
                box.bottom >= rowTop - epsilon && box.bottom <= rowTop + epsilon ->
                    ThermalSquarify.Box(box.left, box.top, box.right, newRowTop)
                else -> box
            }
            if (newBox.left == box.left && newBox.top == box.top &&
                newBox.right == box.right && newBox.bottom == box.bottom
            ) {
                item
            } else {
                ThermalSquarify.Placed(newBox, item.value)
            }
        }
    }

    private fun buildGroupLayout(
        group: ThermalSensorGroup,
        readings: List<ThermalSensorReading>,
        box: ThermalSquarify.Box,
    ): GroupBuild {
        val title = GROUP_TITLES[group] ?: group.name
        return when (group) {
            ThermalSensorGroup.CPU -> buildCpuGroupLayout(group, title, readings, box)
            else -> buildFlatGroupLayout(group, title, readings, box)
        }
    }

    private fun buildFlatGroupLayout(
        group: ThermalSensorGroup,
        title: String,
        readings: List<ThermalSensorReading>,
        box: ThermalSquarify.Box,
    ): GroupBuild {
        val inner = ThermalSquarify.innerBox(box, GROUP_HEADER_PX)
        val items = readings.map { ThermalSquarify.Item(1.0, it) }
        val cells = squarifyLeaves(items, inner)
        val groupLayout = ThermalGroupLayout(
            group = group,
            title = title,
            left = box.left,
            top = box.top,
            right = box.right,
            bottom = box.bottom,
            cells = cells,
        )
        return GroupBuild(group, groupLayout, readings.size.toDouble())
    }

    private fun buildCpuGroupLayout(
        group: ThermalSensorGroup,
        title: String,
        readings: List<ThermalSensorReading>,
        box: ThermalSquarify.Box,
    ): GroupBuild {
        val inner = ThermalSquarify.innerBox(box, GROUP_HEADER_PX)
        val matrix = readings.filter { it.meta.cluster != null && it.meta.core != null }
        val extras = readings.filter { it.meta.cluster == null || it.meta.core == null }

        val subGroupEntries = mutableListOf<Pair<String, List<ThermalSensorReading>>>()
        matrix.groupBy { it.meta.cluster!! }
            .toSortedMap()
            .forEach { (cluster, cores) ->
                subGroupEntries.add("C$cluster" to cores.sortedBy { it.meta.core })
            }
        if (extras.isNotEmpty()) {
            subGroupEntries.add("EXTRAS" to extras)
        }

        val subGroupItems = subGroupEntries.map { (subTitle, list) ->
            ThermalSquarify.Item(list.size.toDouble(), subTitle to list)
        }
        val placedSubs = ThermalSquarify.layoutTyped(subGroupItems, inner)

        val subGroups = placedSubs.map { placed ->
            val (subTitle, list) = placed.value
            val subInner = ThermalSquarify.innerBox(placed.box, SUBGROUP_HEADER_PX)
            val cells = squarifyLeaves(list.map { ThermalSquarify.Item(1.0, it) }, subInner)
            ThermalSubGroupLayout(
                title = subTitle,
                left = placed.box.left,
                top = placed.box.top,
                right = placed.box.right,
                bottom = placed.box.bottom,
                cells = cells,
            )
        }

        val groupLayout = ThermalGroupLayout(
            group = group,
            title = title,
            left = box.left,
            top = box.top,
            right = box.right,
            bottom = box.bottom,
            cells = emptyList(),
            subGroups = subGroups,
        )
        return GroupBuild(group, groupLayout, readings.size.toDouble())
    }

    private fun squarifyLeaves(
        items: List<ThermalSquarify.Item<ThermalSensorReading>>,
        rect: ThermalSquarify.Box,
    ): List<ThermalCellRect> {
        if (items.isEmpty()) return emptyList()
        return ThermalSquarify.layoutTyped(items, rect).map { placed ->
            ThermalCellRect(
                meta = placed.value.meta,
                reading = placed.value,
                left = placed.box.left,
                top = placed.box.top,
                right = placed.box.right,
                bottom = placed.box.bottom,
            )
        }
    }

    private fun ThermalGroupLayout.allCells(): List<ThermalCellRect> =
        cells + subGroups.flatMap { it.cells }

    fun scaleToFit(
        layout: ThermalTreemapLayout,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        paddingPx: Float = 0f,
    ): Float {
        if (layout.contentWidth <= 0f || layout.contentHeight <= 0f) return 1f
        val availW = viewportWidthPx - 2f * paddingPx
        val availH = viewportHeightPx - 2f * paddingPx
        if (availW <= 0f || availH <= 0f) return 1f
        return min(
            availW / layout.contentWidth,
            availH / layout.contentHeight,
        )
    }

    fun scaleToFill(
        layout: ThermalTreemapLayout,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        paddingPx: Float = 0f,
    ): Pair<Float, Float> {
        if (layout.contentWidth <= 0f || layout.contentHeight <= 0f) return 1f to 1f
        val availW = viewportWidthPx - 2f * paddingPx
        val availH = viewportHeightPx - 2f * paddingPx
        if (availW <= 0f || availH <= 0f) return 1f to 1f
        return availW / layout.contentWidth to availH / layout.contentHeight
    }
}
