package com.gow3.android

import com.gow3.android.data.AdviceKind
import com.gow3.android.data.Advisor
import com.gow3.android.data.DeviceFacts
import com.gow3.android.data.PerfPreset
import com.gow3.android.data.PerformancePresets
import com.gow3.android.data.SessionReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvisorTest {

    private val iqoo = DeviceFacts(
        socModel = "SM8845", totalRamMb = 11_500, maxRefreshHz = 144f, batteryPercent = 80,
        charging = false, batteryC = 33f, lsfgAvailable = true, gameOnRemovable = false,
    )
    private val balanced = PerformancePresets.updates(PerfPreset.BALANCED, true)

    private fun session(avg: Float = 59f, severe: Int = 0, lsfg: Int = 0, removable: Boolean = false) = SessionReport(
        durationS = 900, avgFps = avg, low1Fps = 40, pct55 = 90, maxBatteryC = 41f, severeS = severe,
        caps = 0, exitCode = 0, fpsLimit = 60, lsfg = lsfg, removable = removable,
    )

    private fun kinds(session: SessionReport?, ini: Map<String, String>, device: DeviceFacts = iqoo, crashed: Boolean = false) =
        Advisor.advise(session, ini, device, crashed).map { it.kind }

    @Test
    fun `parses the recorder's json and rejects garbage`() {
        val report = SessionReport.parse(
            "{\"duration_s\": 900, \"avg_fps\": 47.5, \"low1_fps\": 22, \"pct_55\": 10, \"max_battery_c\": 45.0, " +
                "\"severe_s\": 300, \"caps\": 2, \"exit_code\": 139, \"fps_limit\": 60, \"lsfg\": 0, \"removable\": 1}",
        )!!
        assertEquals(47.5f, report.avgFps, 0.01f)
        assertEquals(300, report.severeS)
        assertEquals(139, report.exitCode)
        assertTrue(report.removable)
        assertNull(SessionReport.parse("not json"))
    }

    @Test
    fun `recommends a preset for the device when none was chosen`() {
        assertEquals(PerfPreset.BALANCED, Advisor.recommendPreset("SM8845", 11_500))
        assertEquals(PerfPreset.COOL, Advisor.recommendPreset("SM8845", 7_500))
        assertEquals(PerfPreset.COOL, Advisor.recommendPreset("MT6989", 16_000))
        val advice = Advisor.advise(null, emptyMap(), iqoo, false)
        assertEquals(AdviceKind.RECOMMENDED_PRESET, advice.single().kind)
        assertEquals(PerfPreset.BALANCED, advice.single().preset)
        assertTrue(kinds(null, balanced).isEmpty())
    }

    @Test
    fun `a hot session suggests the cool preset, a slow cool one suggests 480p`() {
        assertEquals(listOf(AdviceKind.OVERHEATED), kinds(session(severe = 300), balanced))
        assertEquals(listOf(AdviceKind.LOW_FPS), kinds(session(avg = 42f), balanced))
        // Not when it is already 480p, or frame generation runs, or the session was fine.
        assertTrue(kinds(session(avg = 42f), balanced + ("render_resolution" to "480p")).isEmpty())
        assertTrue(kinds(session(avg = 42f, lsfg = 2), balanced).isEmpty())
        assertTrue(kinds(session(), balanced).isEmpty())
    }

    @Test
    fun `lsfg above the panel, crashes, usb storage and battery`() {
        assertEquals(listOf(AdviceKind.LSFG_TOO_HIGH), kinds(null, balanced + ("lsfg_multiplier" to "3")))
        assertTrue(kinds(null, balanced + ("lsfg_multiplier" to "2")).isEmpty())
        assertEquals(listOf(AdviceKind.CRASHES_FAST_CPU), kinds(null, balanced, crashed = true))
        assertTrue(kinds(null, balanced + ("cpu_mode" to "accurate"), crashed = true).isEmpty())
        assertEquals(listOf(AdviceKind.GAME_ON_USB), kinds(session(removable = true), balanced))
        assertEquals(listOf(AdviceKind.BATTERY_LOW), kinds(null, balanced, iqoo.copy(batteryPercent = 12)))
        assertTrue(kinds(null, balanced, iqoo.copy(batteryPercent = 12, charging = true)).isEmpty())
        assertEquals(listOf(AdviceKind.CHARGING_HOT), kinds(null, balanced, iqoo.copy(charging = true, batteryC = 42f)))
    }
}
