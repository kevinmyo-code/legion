package com.kevin.legion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Exercises [formatShellStatusLine] - the pure half of `MainActivity.kt`'s `shellStatusLine`, split
 * out by mission-control ticket 04's build so `LegionShell`'s [ShellStatusLineParts] (the split
 * [com.kevin.legion.ui.common.StatusLine] needs to let the key state survive an alarm, per ticket 04
 * answer §6) is testable without an Android runtime. Plain JUnit, same posture as
 * [TodayGapResolversTest].
 *
 * **RESTRUCTURED home-launcher ticket 02, ADR 0050**: the old assertions checked two pre-formatted,
 * upper-case stamp strings ("SYNC ON   OBD LINK", "KEY ARMED") built for the retired mission-control
 * row. [ShellStatusLineParts] now carries plain booleans plus one nullable label, and [StatusLine]
 * itself decides the words - see that composable's own doc. The four cases below are UNCHANGED in
 * what they exercise (every state on, every state off, key independent of sync/obd, a mixed
 * combination) - only what each asserts against changed shape.
 */
class ShellStatusLineTest {

    @Test
    fun `every state ON - synced, connected, key armed so keyLabel is null`() {
        val parts = formatShellStatusLine(syncOn = true, obdConnected = true, keyArmed = true)
        assertEquals(true, parts.synced)
        assertEquals(true, parts.obdConnected)
        assertNull(parts.keyLabel)
    }

    @Test
    fun `every state OFF - not synced, not connected, key not set discloses a label`() {
        val parts = formatShellStatusLine(syncOn = false, obdConnected = false, keyArmed = false)
        assertEquals(false, parts.synced)
        assertEquals(false, parts.obdConnected)
        assertEquals("Key not set", parts.keyLabel)
    }

    @Test
    fun `keyLabel is independent of sync and obd - a key can be armed while both are down`() {
        val parts = formatShellStatusLine(syncOn = false, obdConnected = false, keyArmed = true)
        assertEquals(false, parts.synced)
        assertEquals(false, parts.obdConnected)
        assertNull(parts.keyLabel)
    }

    @Test
    fun `mixed states never bleed into the wrong field`() {
        val parts = formatShellStatusLine(syncOn = true, obdConnected = false, keyArmed = false)
        assertEquals(true, parts.synced)
        assertEquals(false, parts.obdConnected)
        assertEquals("Key not set", parts.keyLabel)
    }
}
