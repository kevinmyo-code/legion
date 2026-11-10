package com.kevin.legion.ui.typeset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Typeset compass's arithmetic: angles wrap, the waypoint is relative to where the phone points. */
class CompassMathTest {

    @Test
    fun `angles wrap into 0 until 360`() {
        assertEquals(350f, CompassMath.normalize(-10f), 1e-4f)
        assertEquals(10f, CompassMath.normalize(370f), 1e-4f)
    }

    @Test
    fun `the waypoint arrow is the bearing less the heading`() {
        assertEquals(0f, CompassMath.relative(bearing = 42f, heading = 42f), 1e-4f)
        assertEquals(90f, CompassMath.relative(bearing = 90f, heading = 0f), 1e-4f)
        assertEquals(340f, CompassMath.relative(bearing = 10f, heading = 30f), 1e-4f)
    }

    @Test
    fun `a bearing is written in three figures`() {
        assertEquals("042°", CompassMath.degrees(42.4f))
        assertEquals("000°", CompassMath.degrees(359.7f))
        assertEquals("270°", CompassMath.degrees(-90f))
    }

    @Test
    fun `cardinals and tick weights`() {
        assertEquals("N", CompassMath.cardinal(0))
        assertEquals("W", CompassMath.cardinal(270))
        assertNull(CompassMath.cardinal(30))
        assertEquals(listOf(2, 0, 1, 2), listOf(0, 10, 30, 90).map(CompassMath::tickWeight))
    }
}
