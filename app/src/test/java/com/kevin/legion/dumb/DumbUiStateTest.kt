package com.kevin.legion.dumb

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DumbUiStateTest {
    private val zone = ZoneId.of("UTC")
    private val at = ZonedDateTime.of(2026, 10, 22, 14, 47, 0, 0, zone).toInstant().toEpochMilli()

    private fun build(state: DumbModeState, now: Long = at, names: List<String> = emptyList()) =
        DumbUiStates.build(state, now, zone, Locale.US, names)

    @Test
    fun `home shows the clock, the date and the running time`() {
        val ui = build(DumbModeState(on = true, startedAtMs = at - (2 * 60 + 14) * 60_000L))
        assertEquals("2:47", ui.clock)
        assertEquals("Thursday, October 22", ui.date)
        assertEquals("2h 14m", ui.elapsed)
        assertNull(ui.leave)
    }

    @Test
    fun `elapsed reads minutes under an hour and 0m in the first minute`() {
        assertEquals("14m", DumbFormat.elapsed(14 * 60_000L))
        assertEquals("0m", DumbFormat.elapsed(59_000L))
        assertEquals("1h 0m", DumbFormat.elapsed(60 * 60_000L))
        assertEquals("0m", DumbFormat.elapsed(-5L))
    }

    @Test
    fun `seconds round up`() {
        assertEquals(1, DumbFormat.secondsCeil(1L))
        assertEquals(1, DumbFormat.secondsCeil(1_000L))
        assertEquals(2, DumbFormat.secondsCeil(1_001L))
        assertEquals(0, DumbFormat.secondsCeil(0L))
    }

    @Test
    fun `the people line follows the prototype and says so when nobody is chosen`() {
        assertEquals("Mia, Mom, Dad get through", DumbFormat.peopleLine(listOf("Mia", "Mom", "Dad")))
        assertEquals("Mia gets through", DumbFormat.peopleLine(listOf("Mia")))
        assertTrue(DumbFormat.peopleLine(emptyList()).startsWith("No one chosen"))
    }

    @Test
    fun `a pending leave shows the countdown rounded up and the progress`() {
        val state = DumbModeState(on = true, startedAtMs = 0, leaveRequestedAtMs = at - 14_500L)
        val leave = build(state).leave!!
        assertTrue(leave.waiting)
        assertEquals(46, leave.remainingSeconds)
        assertEquals(14_500f / 60_000f, leave.progress, 0.001f)
    }

    @Test
    fun `after the wait the leave is no longer waiting`() {
        val leave = build(DumbModeState(on = true, startedAtMs = 0, leaveRequestedAtMs = at - 60_000L)).leave!!
        assertFalse(leave.waiting)
        assertEquals(0, leave.remainingSeconds)
    }

    @Test
    fun `off shows no leave and no running time`() {
        val ui = build(DumbModeState(on = false, leaveRequestedAtMs = at))
        assertNull(ui.leave)
        assertEquals("", ui.elapsed)
    }

    @Test
    fun `the kept list is the nine entries in the ruled order`() {
        assertEquals(
            listOf("Phone", "Messages", "Maps", "Camera", "Music", "To-dos", "Calendar", "Books", "Assistant"),
            KeptEntry.entries.map { it.label },
        )
        assertEquals(
            setOf(KeptEntry.PHONE, KeptEntry.MESSAGES, KeptEntry.CAMERA, KeptEntry.MUSIC),
            KeptEntry.entries.filter { it.isExternal }.toSet(),
        )
    }

    @Test
    fun `kept packages come from the resolver and skip what nothing answers`() {
        val resolver = object : KeptAppResolver {
            override fun dialerPackage() = "dial.pkg"
            override fun smsPackage() = null
            override fun cameraPackage() = "cam.pkg"
            override fun musicPackage() = "dial.pkg"
        }
        assertEquals(setOf("dial.pkg", "cam.pkg"), resolver.keptPackages())
    }

    @Test
    fun `setup says a missing grant in words and what is not active`() {
        val rows = DumbSetup.rows(DumbGrantFacts(notificationAccess = false))
        assertFalse(rows.single().granted)
        assertTrue(rows.single().state.startsWith("Not granted"))
        assertEquals("Not active: Notifications are not being held.", DumbSetup.notActive(rows))
        assertNull(DumbSetup.notActive(DumbSetup.rows(DumbGrantFacts(notificationAccess = true))))
    }
}
