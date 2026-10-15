package com.kevin.midnightai.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuityTextTest {

    @Test fun `no memories means no note`() {
        assertNull(AriaBrain.continuityText(emptyList()))
    }

    @Test fun `memories are carried and no gap is stated`() {
        val t = AriaBrain.continuityText(listOf("(May 1, 2026) the XJ stalls cold", "(June 2, 2026) oil at 3k"))!!
        assertTrue(t.contains("the XJ stalls cold"))
        assertTrue(t.contains("oil at 3k"))
        // Compulsion test clause (c): never the driver's absence. The words below are how the old
        // note phrased the gap; none may come back.
        for (banned in listOf("last conversation", "days ago", "week(s)", "a day ago", "earlier today", "over a month")) {
            assertFalse(banned, t.contains(banned))
        }
    }

    @Test fun `the note forbids commenting on how long it has been`() {
        assertTrue(AriaBrain.continuityText(listOf("x"))!!.contains("never by commenting on how long it has been"))
    }
}
