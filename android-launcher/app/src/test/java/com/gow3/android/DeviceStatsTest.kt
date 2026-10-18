package com.gow3.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.cobrabm.fexdroid.DeviceStats
import java.io.File

class DeviceStatsTest {

    private fun root(): File = java.nio.file.Files.createTempDirectory("devicestats-test").toFile().apply { deleteOnExit() }

    private fun File.put(relative: String, text: String) {
        val file = File(this, relative)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    @Test
    fun `gpu load and clock from kgsl`() {
        val sys = root()
        sys.put("class/kgsl/kgsl-3d0/gpu_busy_percentage", "42 %\n")
        sys.put("class/kgsl/kgsl-3d0/devfreq/cur_freq", "1100000000\n")
        val stats = DeviceStats(sys, root())
        assertEquals(42, stats.gpuBusyPercent())
        assertEquals(1100, stats.gpuMhz())
    }

    @Test
    fun `gpu load falls back to gpubusy and clock to gpuclk`() {
        val sys = root()
        sys.put("class/kgsl/kgsl-3d0/gpubusy", "  250000   1000000\n")
        sys.put("class/kgsl/kgsl-3d0/gpuclk", "680000000\n")
        val stats = DeviceStats(sys, root())
        assertEquals(25, stats.gpuBusyPercent())
        assertEquals(680, stats.gpuMhz())
    }

    @Test
    fun `unreadable sources are unknown, not errors`() {
        val stats = DeviceStats(File(root(), "missing"), File(root(), "missing"))
        assertEquals(-1, stats.gpuBusyPercent())
        assertEquals(-1, stats.gpuMhz())
        assertTrue(stats.socTempC().isNaN())
        assertEquals(-1L, stats.rssMb(1234))
        assertEquals(-1, stats.findPid("gow3-probe"))
    }

    @Test
    fun `hottest plausible cpu or gpu zone`() {
        val sys = root()
        sys.put("class/thermal/thermal_zone0/type", "cpu-0-0\n")
        sys.put("class/thermal/thermal_zone0/temp", "48500\n")
        sys.put("class/thermal/thermal_zone1/type", "gpuss-0\n")
        sys.put("class/thermal/thermal_zone1/temp", "61000\n")
        sys.put("class/thermal/thermal_zone2/type", "battery\n")
        sys.put("class/thermal/thermal_zone2/temp", "90000\n")
        sys.put("class/thermal/thermal_zone3/type", "cpu-1-0\n")
        sys.put("class/thermal/thermal_zone3/temp", "-273000\n")
        assertEquals(61.0f, DeviceStats(sys, root()).socTempC(), 0.01f)
    }

    @Test
    fun `game process memory by its command line`() {
        val proc = root()
        proc.put("812/cmdline", "/rootfs/usr/lib/ld-linux-aarch64.so.1\u0000/x/gow3-probe\u0000boot-linked.bin\u0000")
        proc.put("812/status", "Name:\tld-linux\nVmPeak:\t 9000000 kB\nVmRSS:\t 4194304 kB\n")
        proc.put("77/cmdline", "/system/bin/sh\u0000")
        val stats = DeviceStats(root(), proc)
        val pid = stats.findPid("gow3-probe")
        assertEquals(812, pid)
        assertEquals(4096L, stats.rssMb(pid))
    }
}
