package com.btcminer.android.mining

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChartSnapshotTelemetryTest {

    private lateinit var repository: MiningStatsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("mining_stats", Context.MODE_PRIVATE).edit().clear().apply()
        repository = MiningStatsRepository(context)
    }

    @Test
    fun saveAndLoad_v2_telemetryRoundTrip() {
        repository.saveChartSnapshot(
            cpu = listOf(100.0, 200.0),
            gpu = listOf(50.0, 60.0),
            elapsedSec = listOf(0f, 1f),
            batteryTempC = listOf(32f, 33f),
            donutCpuShares = 1L,
            donutGpuShares = 2L,
            sessionStartMs = 9_000L,
            savedAtMs = 10_000L,
            cpussAvgC = listOf(40f, 41f),
            cpuAvgC = listOf(50f, Float.NaN),
            gpussAvgC = listOf(30f, 31f),
            gpuAvgC = listOf(Float.NaN, 55f),
            skinC = listOf(32f, 32.5f),
            telemetryBatteryAvgC = listOf(32f, 33f),
            cpuClkMhz = listOf(1000f, 1100f),
            gpuClkMhz = listOf(500f, 600f),
            avgWorkMs = listOf(120f, Float.NaN),
        )
        val snap = repository.getChartSnapshotOrNull(nowMs = 10_000L)!!
        assertEquals(2, snap.version)
        assertEquals(2, snap.cpussAvgC.size)
        assertEquals(41f, snap.cpussAvgC[1], 0.01f)
        assertFalse(snap.cpuAvgC[1].isFinite())
        assertTrue(snap.gpuAvgC[1].isFinite())
        assertEquals(600f, snap.gpuClkMhz[1], 0.01f)
        assertEquals(120f, snap.avgWorkMs[0], 0.01f)
        assertFalse(snap.avgWorkMs[1].isFinite())
    }
}
