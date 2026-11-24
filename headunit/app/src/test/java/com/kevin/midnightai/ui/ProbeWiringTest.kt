package com.kevin.midnightai.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import com.kevin.midnightai.diag.InputProbe
import com.kevin.midnightai.media.MixtapePlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The wiring the review listed as untested (recheck 2026-11-20): MainActivity's editor detection
 * (KeyDispatch.editorActive, what it passes) and the mixtape session's media-button probe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class ProbeWiringTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clear() = InputProbe.clearForTest()

    @Test fun aFocusedTextEditorCountsAsAnEditor() {
        assertTrue(KeyDispatch.editorActive(EditText(context), imm = null))
    }

    @Test fun aPlainViewOrNoFocusIsNotAnEditor() {
        assertFalse(KeyDispatch.editorActive(View(context), imm = null))
        assertFalse(KeyDispatch.editorActive(null, imm = null))
    }

    @Test fun aFocusedEditorSilencesTheWheelLogEndToEnd() {
        val editor = KeyDispatch.editorActive(EditText(context), imm = null)
        KeyDispatch.dispatch(
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT), editor, { _, _ -> }, {}, { false },
        )
        assertTrue(InputProbe.wheel.value.isEmpty())
    }

    private fun button(action: Int, repeat: Int = 0) =
        Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(
            Intent.EXTRA_KEY_EVENT, KeyEvent(0L, 0L, action, KeyEvent.KEYCODE_MEDIA_NEXT, repeat),
        )

    @Test fun aMixtapeMediaButtonIsLoggedAndLeftToMedia3() {
        assertFalse(MixtapePlayer.logMediaButton(button(KeyEvent.ACTION_DOWN)))
        val line = InputProbe.wheel.value.single().text
        assertTrue(line, line.startsWith("media-button ") && line.endsWith(" down session=mixtape"))
    }

    @Test fun repeatsAndKeylessIntentsAddNoLine() {
        MixtapePlayer.logMediaButton(button(KeyEvent.ACTION_DOWN, repeat = 2))
        assertFalse(MixtapePlayer.logMediaButton(Intent(Intent.ACTION_MEDIA_BUTTON)))
        assertEquals(0, InputProbe.wheel.value.size)
    }
}
