package com.kevin.midnightai.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map's OpenGL ES 3 gate. Pure: [NavGles.probeEs3Configs] needs a real EGL stack and is the one
 * thing here only the car can run, so every other case is decided from its two inputs.
 */
class NavGlesTest {

    private val es2 = 0x20000
    private val es3 = 0x30000

    @Test fun aUnitThatDeclaresEs3NeedsNoProbe() {
        assertEquals(NavGles.Es3.DECLARED, NavGles.decide(es3, es3Configs = null))
        assertEquals(NavGles.Es3.DECLARED, NavGles.decide(0x30002, es3Configs = 0))
        assertTrue(NavGles.decide(es3, null).mapAllowed)
        assertNull(NavGles.words(es3, NavGles.decide(es3, null)))
    }

    /** The bug Kevin hit: a ROM that under-declares took the map away from a GPU that can draw it. */
    @Test fun aUnitThatDeclaresEs2ButHasEs3ConfigsMayRunTheMap() {
        val decision = NavGles.decide(es2, es3Configs = 12)
        assertEquals(NavGles.Es3.PROBED, decision)
        assertTrue(decision.mapAllowed)
        assertNull(NavGles.words(es2, decision))
    }

    @Test fun noEs3ConfigsIsARealRefusal_andTheWordsNameWhatTheUnitReported() {
        val decision = NavGles.decide(es2, es3Configs = 0)
        assertEquals(NavGles.Es3.NO, decision)
        assertFalse(decision.mapAllowed)
        val words = NavGles.words(es2, decision)!!
        assertTrue(words, "OpenGL ES 3" in words)
        assertTrue(words, "OpenGL ES 2.0" in words)
    }

    @Test fun aProbeThatCouldNotRunKeepsTheMapOff_andSaysThatIsWhy() {
        val decision = NavGles.decide(es2, es3Configs = null)
        assertEquals(NavGles.Es3.UNKNOWN, decision)
        assertFalse(decision.mapAllowed)
        val words = NavGles.words(es2, decision)!!
        assertTrue(words, "could not" in words)
        assertTrue(words, "OpenGL ES 2.0" in words)
    }

    @Test fun theVersionReadsTheWayTheDocsWriteIt() {
        assertEquals("2.0", NavGles.version(0x20000))
        assertEquals("3.0", NavGles.version(0x30000))
        assertEquals("3.2", NavGles.version(0x30002))
    }
}
