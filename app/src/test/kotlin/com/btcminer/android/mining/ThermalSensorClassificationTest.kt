package com.btcminer.android.mining

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalSensorClassificationTest {

    @Test
    fun classifyType_cpussAndCpuAndGpuss() {
        val cpuss = ThermalSensorClassification.classifyType("cpuss-0-usr", 10)!!
        assertEquals(ThermalSensorGroup.CPUSS, cpuss.group)
        assertFalse(cpuss.isVirtual)

        val cpu = ThermalSensorClassification.classifyType("cpu-0-3-usr", 11)!!
        assertEquals(ThermalSensorGroup.CPU, cpu.group)
        assertEquals(0, cpu.cluster)
        assertEquals(3, cpu.core)
        assertEquals("0-3", cpu.shortLabel)

        val gpuss = ThermalSensorClassification.classifyType("gpuss-max-step", 12)!!
        assertEquals(ThermalSensorGroup.GPUSS, gpuss.group)
        assertTrue(gpuss.isVirtual)
    }

    @Test
    fun classifyType_skinAndBattery() {
        assertEquals(
            ThermalSensorGroup.SKIN,
            ThermalSensorClassification.classifyType("skin-msm-therm", 5)!!.group,
        )
        assertEquals(
            ThermalSensorGroup.BATTERY_SYSFS,
            ThermalSensorClassification.classifyType("battery", 6)!!.group,
        )
    }

    @Test
    fun classifyType_unknownReturnsNull() {
        assertNull(ThermalSensorClassification.classifyType("gpu-usr", 1))
    }

    @Test
    fun batteryApiMeta() {
        val meta = ThermalSensorClassification.batteryApiMeta()
        assertEquals(ThermalSensorGroup.BATTERY_API, meta.group)
        assertNull(meta.zoneId)
        assertEquals("battery-api", meta.type)
    }
}
