package com.kevin.legion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exercises [formatShellStatusLine] - the pure half of `MainActivity.kt`'s `shellStatusLine`,
 * split out by mission-control ticket 04's build so `LegionShell`'s `left`/`keySegment` pair (the
 * split [com.kevin.legion.ui.common.StatusLine] needs to let an ALARM segment replace SYNC/OBD
 * while KEY survives, per ticket 04 answer §6) is testable without an Android runtime. Plain JUnit,
 * same posture as [TodayGapResolversTest].
 */
class ShellStatusLineTest {

    @Test
    fun `every state ON reads LINK ARMED, split into left and keySegment`() {
        val parts = formatShellStatusLine(syncOn = true, obdConnected = true, keyArmed = true)
        assertEquals("SYNC ON   OBD LINK", parts.left)
        assertEquals("KEY ARMED", parts.keySegment)
    }

    @Test
    fun `every state OFF reads NO LINK NOT SET, still split the same way`() {
        val parts = formatShellStatusLine(syncOn = false, obdConnected = false, keyArmed = false)
        assertEquals("SYNC OFF   OBD NO LINK", parts.left)
        assertEquals("KEY NOT SET", parts.keySegment)
    }

    @Test
    fun `keySegment is independent of sync and obd - a key can be armed while both are down`() {
        val parts = formatShellStatusLine(syncOn = false, obdConnected = false, keyArmed = true)
        assertEquals("SYNC OFF   OBD NO LINK", parts.left)
        assertEquals("KEY ARMED", parts.keySegment)
    }

    @Test
    fun `mixed states never bleed into the wrong segment`() {
        val parts = formatShellStatusLine(syncOn = true, obdConnected = false, keyArmed = false)
        assertEquals("SYNC ON   OBD NO LINK", parts.left)
        assertEquals("KEY NOT SET", parts.keySegment)
    }
}
