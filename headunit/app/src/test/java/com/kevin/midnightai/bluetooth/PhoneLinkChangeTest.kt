package com.kevin.midnightai.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneLinkChangeTest {

    @Test fun `an unchanged set is not a change`() {
        assertNull(PhoneLinkChange.diff(setOf("a"), setOf("a")))
        assertNull(PhoneLinkChange.diff(emptySet(), emptySet()))
    }

    @Test fun `the only phone leaving is counted as lost`() {
        val d = PhoneLinkChange.diff(setOf("a"), emptySet())!!
        assertEquals(PhoneLinkChange.Diff(lost = 1, gained = 0, nowConnected = 0), d)
    }

    @Test fun `a swap counts one lost and one gained`() {
        val d = PhoneLinkChange.diff(setOf("a"), setOf("b"))!!
        assertEquals(PhoneLinkChange.Diff(lost = 1, gained = 1, nowConnected = 1), d)
    }

    @Test fun `the description carries counts and never an address`() {
        val text = PhoneLinkChange.describe(PhoneLinkChange.diff(setOf("AA:BB:CC:DD:EE:FF"), emptySet())!!)
        assertEquals("audio_lost=1 audio_gained=0 audio_now=0", text)
    }
}
