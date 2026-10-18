package com.gow3.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.cobrabm.fexdroid.SmartFps

class SmartFpsTest {

    private fun SmartFps.run(fromS: Int, toS: Int, status: Int, forecast: Float, fps: Int, limit: Int = 60): Boolean {
        var on = capped()
        for (s in fromS..toS) on = update(s * 1000L, status, forecast, fps, limit)
        return on
    }

    @Test
    fun `severe caps at once and releases only when cool for a minute`() {
        val smart = SmartFps()
        assertTrue(smart.update(0, 3, Float.NaN, 60, 60))
        assertEquals(SmartFps.Reason.SEVERE, smart.reason())
        // Cooler but not yet LIGHT: stays capped.
        assertTrue(smart.run(1, 120, 2, 0.8f, 30))
        // LIGHT and a cool forecast: released (more than 60 s since the cap).
        assertFalse(smart.run(121, 122, 1, 0.6f, 30))
    }

    @Test
    fun `a hot forecast caps after 15 s, before the phone is severe`() {
        val smart = SmartFps()
        assertFalse(smart.run(0, 13, 1, 0.97f, 60))
        assertTrue(smart.run(14, 16, 1, 0.97f, 60))
        assertEquals(SmartFps.Reason.FORECAST, smart.reason())
        // A brief cool sample right after the cap does not release it (minimum 60 s).
        assertTrue(smart.update(20_000, 0, 0.5f, 30, 60))
        assertFalse(smart.update(80_000, 0, 0.5f, 30, 60))
    }

    @Test
    fun `an interrupted hot forecast starts counting again`() {
        val smart = SmartFps()
        smart.run(0, 10, 1, 0.97f, 60)
        smart.update(11_000, 1, 0.8f, 60, 60)
        assertFalse(smart.run(12, 25, 1, 0.97f, 60))
    }

    @Test
    fun `low fps while cool caps to an even 30 and retries after 3 minutes`() {
        val smart = SmartFps()
        assertFalse(smart.run(0, 19, 0, 0.5f, 42))
        assertTrue(smart.run(20, 21, 0, 0.5f, 42))
        assertEquals(SmartFps.Reason.LOW_FPS, smart.reason())
        assertTrue(smart.run(22, 199, 0, 0.5f, 30))
        assertFalse(smart.update(201_000, 0, 0.5f, 30, 60))
    }

    @Test
    fun `nothing to cap when the limit is already 30, and unknown readings change nothing`() {
        val smart = SmartFps()
        assertFalse(smart.run(0, 60, 2, 0.99f, 25, limit = 30))
        assertFalse(SmartFps().run(0, 60, -1, Float.NaN, -1))
    }

    @Test
    fun `unlimited frame rate can still be capped`() {
        assertTrue(SmartFps().update(0, 4, Float.NaN, 70, 0))
    }
}
