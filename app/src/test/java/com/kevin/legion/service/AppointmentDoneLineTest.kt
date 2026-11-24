package com.kevin.legion.service

import com.kevin.legion.service.cascade.ScriptedLines
import com.kevin.legion.testutil.RoomTestReset
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A voice-added appointment is said by its name (2026-11-23 voice log: "Done: added it." for an
 * appointment the user had just named). The whole path, as a turn runs it: the real
 * `manage_item add` dispatch, then the fact and the spoken line code builds from its result.
 * Titles are made up.
 */
@RunWith(RobolectricTestRunner::class)
class AppointmentDoneLineTest {
    private val context = RuntimeEnvironment.getApplication()
    private val day: LocalDate = LocalDate.now(ZoneId.systemDefault()).plusDays(3)

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun drain() {
        RoomTestReset.drainArchDiskIoPool()
    }

    @Test
    fun `an appointment added by voice is named in the done line, never called it`() = runBlocking {
        val args = JSONObject()
            .put("action", "add")
            .put("kind", "appointment")
            .put("item", "Dentist")
            .put("date", day.toString())
            .put("time", "15:00")
        val result = LiveToolbox.dispatch(context, "manage_item", args)!!
        assertTrue(result.toString(), result.getBoolean("success"))

        val fact = ScriptedLines.factOf("manage_item", args, result, ok = true, confirmed = true)
        val line = ScriptedLines.doneLine(listOf(fact))
        assertTrue("spoken line was $line", line != null && line.contains("Dentist"))
    }
}
