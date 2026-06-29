package com.btcminer.android.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuConfigMigrationTest {

    @Test
    fun legacyWorkgroups0_disablesGpu_defaultLocalSize32() {
        assertFalse(
            GpuConfigMigration.resolveGpuEnabled(
                hasGpuEnabledKey = false,
                gpuEnabledStored = false,
                legacyWorkgroups = 0,
                legacyGpuCores = 0,
            )
        )
        assertEquals(
            32,
            GpuConfigMigration.resolveGpuLocalSizeX(
                hasLocalSizeKey = false,
                storedLocalSizeX = 32,
                legacyWorkgroups = 0,
                deviceMax = 1024,
            )
        )
    }

    @Test
    fun legacyWorkgroups8_enablesGpu_localSize256() {
        assertTrue(
            GpuConfigMigration.resolveGpuEnabled(
                hasGpuEnabledKey = false,
                gpuEnabledStored = false,
                legacyWorkgroups = 8,
                legacyGpuCores = 0,
            )
        )
        assertEquals(
            256,
            GpuConfigMigration.resolveGpuLocalSizeX(
                hasLocalSizeKey = false,
                storedLocalSizeX = 32,
                legacyWorkgroups = 8,
                deviceMax = 1024,
            )
        )
    }

    @Test
    fun legacyWorkgroups1_enablesGpu_localSize32() {
        assertTrue(
            GpuConfigMigration.resolveGpuEnabled(
                hasGpuEnabledKey = false,
                gpuEnabledStored = false,
                legacyWorkgroups = 1,
                legacyGpuCores = 0,
            )
        )
        assertEquals(
            32,
            GpuConfigMigration.resolveGpuLocalSizeX(
                hasLocalSizeKey = false,
                storedLocalSizeX = 32,
                legacyWorkgroups = 1,
                deviceMax = 1024,
            )
        )
    }

    @Test
    fun storedKeysOverrideLegacy() {
        assertFalse(
            GpuConfigMigration.resolveGpuEnabled(
                hasGpuEnabledKey = true,
                gpuEnabledStored = false,
                legacyWorkgroups = 8,
                legacyGpuCores = 0,
            )
        )
        assertEquals(
            128,
            GpuConfigMigration.resolveGpuLocalSizeX(
                hasLocalSizeKey = true,
                storedLocalSizeX = 128,
                legacyWorkgroups = 8,
                deviceMax = 1024,
            )
        )
    }

    @Test
    fun legacyGpuCores_enablesGpu() {
        assertTrue(
            GpuConfigMigration.resolveGpuEnabled(
                hasGpuEnabledKey = false,
                gpuEnabledStored = false,
                legacyWorkgroups = 0,
                legacyGpuCores = 4,
            )
        )
    }
}

class GpuLocalSizeClampTest {

    @Test
    fun clamp_roundsDownToStep32_andRespectsDeviceMax() {
        assertEquals(32, MiningConfig.clampGpuLocalSizeX(33, 1024))
        assertEquals(256, MiningConfig.clampGpuLocalSizeX(300, 1024))
        assertEquals(992, MiningConfig.clampGpuLocalSizeX(1000, 1000))
        assertEquals(32, MiningConfig.clampGpuLocalSizeX(16, 1024))
    }
}

class GpuLocalSizeHintsTest {

    @Test
    fun adrenoHint() {
        assertEquals(
            "Adreno: try 64 or 128 threads/workgroup",
            GpuLocalSizeHints.vendorHint("Qualcomm Adreno 730", "Qualcomm Proprietary"),
        )
    }

    @Test
    fun maliHint() {
        assertEquals(
            "Mali: try 32 or 64 threads/workgroup",
            GpuLocalSizeHints.vendorHint("Mali-G78", "Arm"),
        )
    }

    @Test
    fun parseVulkanGpuInfo() {
        val (device, driver) = GpuLocalSizeHints.parseVulkanGpuInfo("Pixel GPU|Mesa driver")
        assertEquals("Pixel GPU", device)
        assertEquals("Mesa driver", driver)
    }
}
