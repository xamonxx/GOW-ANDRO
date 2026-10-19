package com.gow3.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.cobrabm.fexdroid.AutoBackup
import ro.cobrabm.fexdroid.LsfgAuto
import ro.cobrabm.fexdroid.SessionRecorder
import java.io.File

class SmartFeaturesTest {

    private fun dir(): File = java.nio.file.Files.createTempDirectory("smart-test").toFile().apply { deleteOnExit() }

    @Test
    fun `session numbers and json`() {
        val session = SessionRecorder()
        session.start(0, 60, 0)
        repeat(98) { session.sample(60, 38.5f, 1) }
        session.sample(20, 44.0f, 3)
        session.sample(-1, Float.NaN, -1)  // not presenting: no fps sample
        session.capped()
        val json = session.json(100_000, 0, true)
        assertTrue(json, json.contains("\"duration_s\": 100"))
        assertTrue(json, json.contains("\"low1_fps\": 20"))
        assertTrue(json, json.contains("\"pct_55\": 98"))
        assertTrue(json, json.contains("\"max_battery_c\": 44.0"))
        assertTrue(json, json.contains("\"severe_s\": 1"))
        assertTrue(json, json.contains("\"caps\": 1"))
        assertTrue(json, json.contains("\"removable\": 1"))
        assertTrue(json, json.contains("\"avg_fps\": 59.6"))
    }

    @Test
    fun `a checkpoint leaves a report when the app is killed, and the session goes on`() {
        val file = File(dir(), "session-last.json")
        val session = SessionRecorder()
        session.checkpoint(file, 0, false)
        assertFalse(file.exists())  // nothing before a start
        session.start(0, 60, 0)
        repeat(30) { session.sample(60, 40.0f, 2) }
        assertEquals(30, session.seconds())
        session.checkpoint(file, 30_000, false)
        val text = file.readText()
        assertTrue(text, text.contains("\"duration_s\": 30"))
        assertTrue(text, text.contains("\"exit_code\": -1"))
        assertTrue(session.running())
        session.sample(30, 41.0f, 3)
        session.finish(file, 31_000, 139, false)
        assertTrue(file.readText().contains("\"exit_code\": 139"))
        assertFalse(session.running())
    }

    @Test
    fun `history keeps the last codes and spots two crashes in a row`() {
        val file = File(dir(), "launch-history")
        for (code in listOf(0, 1, 139, 2, 11, 6)) SessionRecorder.appendHistory(file, code, 5)
        assertEquals(listOf(1, 139, 2, 11, 6), SessionRecorder.history(file))
        assertTrue(SessionRecorder.crashedTwice(SessionRecorder.history(file)))
        // The player's stop, a driver failure or the runtime missing a library are not crashes.
        assertFalse(SessionRecorder.crashedTwice(listOf(139, SessionRecorder.STOPPED)))
        assertFalse(SessionRecorder.crashedTwice(listOf(4, 4)))
        assertFalse(SessionRecorder.crashedTwice(listOf(127, 139)))
        assertFalse(SessionRecorder.crashedTwice(listOf(139)))
        assertTrue(SessionRecorder.history(File(dir(), "missing")).isEmpty())
    }

    @Test
    fun `automatic save backups keep the newest copies`() {
        val root = dir()
        val savedata = File(root, "savedata/1/CUSA01623/AUTOSAVE").apply { mkdirs() }
        File(savedata, "data.bin").writeText("v1")
        val backups = File(root, "savedata_backups")
        for (stamp in listOf("20261018-100000", "20261018-110000", "20261018-120000")) {
            assertNotNull(AutoBackup.run(File(root, "savedata"), backups, stamp, 2))
        }
        val kept = backups.list()!!.sorted()
        assertEquals(listOf("auto-20261018-110000", "auto-20261018-120000"), kept)
        assertEquals("v1", File(backups, "auto-20261018-120000/1/CUSA01623/AUTOSAVE/data.bin").readText())
        // Nothing to save: no copy.
        assertNull(AutoBackup.run(File(root, "empty"), backups, "20261018-130000", 2))
    }

    @Test
    fun `lsfg multiplier fits the display`() {
        val iqoo = floatArrayOf(60f, 90f, 120f, 144f)
        assertEquals(2, LsfgAuto.pick(60, iqoo))
        assertEquals(4, LsfgAuto.pick(30, iqoo))
        assertEquals(2, LsfgAuto.pick(60, floatArrayOf(60f, 144f)))  // no even mode: x2 still fits
        assertEquals(0, LsfgAuto.pick(60, floatArrayOf(60f)))
        assertEquals(0, LsfgAuto.pick(0, iqoo))
    }
}
