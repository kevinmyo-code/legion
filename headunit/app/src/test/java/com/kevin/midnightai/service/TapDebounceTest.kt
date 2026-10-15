package com.kevin.midnightai.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapDebounceTest {

    @Test fun `the first tap is accepted`() {
        assertTrue(TapDebounce(700).accept(0))
    }

    @Test fun `a double fire inside the window is swallowed`() {
        val d = TapDebounce(700)
        assertTrue(d.accept(1_000))
        assertFalse(d.accept(1_050))
        assertFalse(d.accept(1_699))
    }

    @Test fun `a swallowed tap does not extend the window`() {
        val d = TapDebounce(700)
        assertTrue(d.accept(1_000))
        assertFalse(d.accept(1_600))
        // 700 ms after the ACCEPTED tap, not after the swallowed one.
        assertTrue(d.accept(1_700))
    }

    @Test fun `a deliberate later tap goes through`() {
        val d = TapDebounce(700)
        assertTrue(d.accept(0))
        assertTrue(d.accept(5_000))
    }
}
