package com.gow3.android

import com.gow3.android.data.PerfPreset
import com.gow3.android.data.PerformancePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformancePresetsTest {

    // The keys the runtime reads (launch_env.py, run-gow3.sh, GameActivity, gow3_settings.cpp).
    private val runtimeKeys = setOf(
        "render_resolution", "fps_limit", "frames_queued", "gpu_boost", "sustained_perf",
        "thermal_guard", "lsfg_multiplier",
    )

    @Test
    fun `presets only write keys the runtime reads`() {
        for (preset in PerfPreset.entries) {
            for (dll in listOf(true, false)) {
                val updates = PerformancePresets.updates(preset, dll)
                assertTrue(preset.name, (updates.keys - PerformancePresets.KEY).all { it in runtimeKeys })
                assertEquals(preset.key, updates[PerformancePresets.KEY])
            }
        }
    }

    @Test
    fun `an applied preset is detected again`() {
        for (preset in PerfPreset.entries) {
            for (dll in listOf(true, false)) {
                assertEquals(preset, PerformancePresets.detect(PerformancePresets.updates(preset, dll), dll))
            }
        }
    }

    @Test
    fun `a hand change makes the settings custom`() {
        val ini = PerformancePresets.updates(PerfPreset.BALANCED, false) + ("fps_limit" to "120")
        assertNull(PerformancePresets.detect(ini, false))
    }

    @Test
    fun `missing keys count as the runtime defaults`() {
        // An empty gow3.ini runs 720p / 60 FPS / two frames queued / GPU boost / no sustained mode.
        assertEquals(PerfPreset.MAX, PerformancePresets.detect(emptyMap(), false))
    }

    @Test
    fun `cool uses frame generation only with the player's dll`() {
        assertEquals("2", PerformancePresets.values(PerfPreset.COOL, true)["lsfg_multiplier"])
        assertEquals("0", PerformancePresets.values(PerfPreset.COOL, false)["lsfg_multiplier"])
        // Any multiplier outside 2-4 is off for run-gow3.sh.
        val ini = PerformancePresets.updates(PerfPreset.MAX, false) + ("lsfg_multiplier" to "1")
        assertEquals(PerfPreset.MAX, PerformancePresets.detect(ini, false))
    }
}
