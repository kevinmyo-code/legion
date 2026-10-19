package com.kevin.legion.ui.games

import android.view.KeyEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Auto-repeat on the test scheduler's virtual clock: DAS 170 ms, then one every ARR 50 ms. */
@OptIn(ExperimentalCoroutinesApi::class)
class HoldRepeatTest {
    @Test
    fun `fires once at press, again at DAS, then every ARR`() = runTest {
        var count = 0
        val hold = HoldRepeat(backgroundScope) { count++ }
        hold.press()
        runCurrent()
        assertEquals(1, count)
        advanceTimeBy(DAS_MS - 1)
        runCurrent()
        assertEquals("nothing before DAS", 1, count)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("first repeat at DAS", 2, count)
        advanceTimeBy(ARR_MS * 4)
        runCurrent()
        assertEquals("four ARR ticks later", 6, count)
        hold.release()
    }

    @Test
    fun `release stops it and a tap shorter than DAS is one action`() = runTest {
        var count = 0
        val hold = HoldRepeat(backgroundScope) { count++ }
        hold.press()
        runCurrent()
        advanceTimeBy(DAS_MS - 20)
        runCurrent()
        hold.release()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, count)
    }

    @Test
    fun `a second press while held does not double the rate`() = runTest {
        var count = 0
        val hold = HoldRepeat(backgroundScope) { count++ }
        hold.press()
        hold.press()
        runCurrent()
        assertEquals(1, count)
        hold.release()
    }

    @Test
    fun `controller keys map and unknown keys are left alone`() {
        assertEquals(PadButton.LEFT, padButtonForKey(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(PadButton.A, padButtonForKey(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(PadButton.B, padButtonForKey(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(PadButton.HOLD, padButtonForKey(KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals(PadButton.HOLD, padButtonForKey(KeyEvent.KEYCODE_BUTTON_R1))
        assertEquals(PadButton.START, padButtonForKey(KeyEvent.KEYCODE_BUTTON_START))
        assertEquals(PadButton.SELECT, padButtonForKey(KeyEvent.KEYCODE_BUTTON_SELECT))
        assertNull(padButtonForKey(KeyEvent.KEYCODE_BACK))
    }
}
