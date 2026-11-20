package com.kevin.midnightai.ui

import android.os.Build
import android.view.KeyEvent
import com.kevin.midnightai.diag.InputProbe
import com.kevin.midnightai.diag.ProbeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * HU-1 T2/T9 key dispatch (review 2026-11-20): what is consumed never changes, volume keys go to
 * the volume path, and typed text never reaches the uploaded drive log (M1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class KeyDispatchTest {

    private val volumeCalls = mutableListOf<Pair<Boolean, Int>>()
    private var volumeUps = 0
    private val superCalls = mutableListOf<Int>()

    @Before fun reset() {
        InputProbe.clearForTest()
        volumeCalls.clear()
        volumeUps = 0
        superCalls.clear()
    }

    private fun dispatch(event: KeyEvent, editorActive: Boolean = false, superResult: Boolean = false): Boolean =
        KeyDispatch.dispatch(
            event = event,
            editorActive = editorActive,
            onVolumeKey = { up, repeat -> volumeCalls += up to repeat },
            onVolumeKeyUp = { volumeUps++ },
            superDispatch = { superCalls += it.keyCode; superResult },
        )

    private fun down(code: Int, repeat: Int = 0) = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, code, repeat)
    private fun up(code: Int) = KeyEvent(0L, 500L, KeyEvent.ACTION_UP, code, 0)

    @Test fun aPrintableKeyProducesNoLogLineAndIsPassedOnUnchanged() {
        assertTrue(dispatch(down(KeyEvent.KEYCODE_A), superResult = true))
        assertFalse(dispatch(up(KeyEvent.KEYCODE_A), superResult = false))
        assertEquals(listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_A), superCalls)
        assertTrue(InputProbe.wheel.value.isEmpty())
    }

    @Test fun digitsDeleteAndEnterAreNeverLogged() {
        listOf(KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_NUMPAD_5)
            .forEach { dispatch(down(it)) }
        assertTrue(InputProbe.wheel.value.isEmpty())
    }

    @Test fun aMediaKeyIsLoggedByNameAndStillPassedOn() {
        assertFalse(dispatch(down(KeyEvent.KEYCODE_MEDIA_NEXT), superResult = false))
        assertEquals(listOf(KeyEvent.KEYCODE_MEDIA_NEXT), superCalls)
        val line = InputProbe.wheel.value.single().text
        // The name comes from KeyEvent.keyCodeToString (Robolectric returns the bare number).
        assertTrue(line, line.contains(" code=87 "))
        assertTrue(line, line.endsWith("passed-on"))
    }

    @Test fun nothingIsLoggedWhileAnEditorHasTheKeyboard() {
        dispatch(down(KeyEvent.KEYCODE_MEDIA_NEXT), editorActive = true)
        dispatch(down(KeyEvent.KEYCODE_DPAD_UP), editorActive = true)
        assertTrue(InputProbe.wheel.value.isEmpty())
    }

    @Test fun volumeKeysAreConsumedAndGoToTheVolumePathNotToSuper() {
        assertTrue(dispatch(down(KeyEvent.KEYCODE_VOLUME_UP)))
        assertTrue(dispatch(down(KeyEvent.KEYCODE_VOLUME_UP, repeat = 3)))
        assertTrue(dispatch(up(KeyEvent.KEYCODE_VOLUME_UP)))
        assertTrue(dispatch(down(KeyEvent.KEYCODE_VOLUME_DOWN)))
        assertEquals(listOf(true to 0, true to 3, false to 0), volumeCalls)
        assertEquals(1, volumeUps)
        assertTrue(superCalls.isEmpty())
        // First press and release logged; the repeat is not.
        assertEquals(3, InputProbe.volume.value.size)
    }

    @Test fun theWheelRuleOnPlainValues() {
        assertFalse(ProbeFormat.mayLogWheelKey(KeyEvent.KEYCODE_Z, isPrinting = false, unicodeChar = 0, editorActive = false))
        assertFalse(ProbeFormat.mayLogWheelKey(250, isPrinting = true, unicodeChar = 0, editorActive = false))
        assertFalse(ProbeFormat.mayLogWheelKey(250, isPrinting = false, unicodeChar = 'x'.code, editorActive = false))
        assertTrue(ProbeFormat.mayLogWheelKey(250, isPrinting = false, unicodeChar = 0, editorActive = false))
        assertTrue(ProbeFormat.mayLogWheelKey(KeyEvent.KEYCODE_F5, isPrinting = false, unicodeChar = 0, editorActive = false))
    }
}
