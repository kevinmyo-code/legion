package com.kevin.legion.service.turn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** toolCallCancellation: report what happened, no undo (Kevin, 2026-10-27). */
@RunWith(RobolectricTestRunner::class)
class CancelledToolReportTest {
    @Test
    fun `an uncancelled tool is untouched`() {
        val r = CancelledToolReport()
        assertFalse(r.onFinished("1", "set_reminder", JSONObject().put("success", true), isWrite = true))
        assertFalse(r.hasNotes())
    }

    @Test
    fun `a cancelled write that ran is swallowed and told as done`() {
        val r = CancelledToolReport()
        r.onCancellation(listOf("1"))
        assertTrue(r.isCancelled("1"))
        val response = JSONObject().put("success", true).put("message", "Reminder set for 6pm")
        assertTrue(r.onFinished("1", "set_reminder", response, isWrite = true))
        val note = r.takeNotes().single().text
        assertTrue(note.contains("already run and succeeded"))
        assertTrue(note.contains("Reminder set for 6pm"))
        assertTrue(note.contains("Nothing was undone"))
        assertFalse(r.hasNotes())
    }

    @Test
    fun `a cancelled write that failed is told as not done`() {
        val r = CancelledToolReport()
        r.onCancellation(listOf("2"))
        r.onFinished("2", "log_meal", JSONObject().put("success", false).put("message", "Server down"), isWrite = true)
        assertTrue(r.takeNotes().single().text.contains("did NOT complete: Server down"))
    }

    @Test
    fun `a result with no success field is never reported as done`() {
        assertTrue(CancelledToolReport.noteFor("x", JSONObject()).contains("did NOT complete"))
    }

    @Test
    fun `a cancelled read is swallowed silently`() {
        val r = CancelledToolReport()
        r.onCancellation(listOf("3"))
        assertTrue(r.onFinished("3", "get_weather", JSONObject().put("success", true), isWrite = false))
        assertEquals(0, r.takeNotes().size)
    }
}
