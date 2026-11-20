package com.kevin.midnightai.ui

import android.view.KeyEvent
import com.kevin.midnightai.diag.InputProbe
import com.kevin.midnightai.diag.ProbeFormat

/**
 * What [MainActivity.dispatchKeyEvent] does with a key, split out so it is testable with plain
 * [KeyEvent]s (Robolectric) and no Activity.
 *
 * - Volume keys are consumed and go down the app's own AudioManager path ([onVolumeKey]), the one
 *   known to reach the head unit's speakers (2026-10-13 field report). HU-1 T2 logs each press.
 * - Every other key is handed to [superDispatch] and its answer returned unchanged; the probe
 *   never changes what is consumed.
 * - HU-1 T9 logs non-text keys only (media, volume, d-pad, function, vendor wheel codes) and
 *   nothing while an editor has the keyboard ([editorActive]): the Gemini key field lives in this
 *   activity and the drive log is uploaded (review M1, 2026-11-20).
 */
internal object KeyDispatch {

    fun dispatch(
        event: KeyEvent,
        editorActive: Boolean,
        onVolumeKey: (up: Boolean, repeatCount: Int) -> Unit,
        onVolumeKeyUp: () -> Unit,
        superDispatch: (KeyEvent) -> Boolean,
    ): Boolean {
        val up = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        if (!up && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            val handled = superDispatch(event)
            if (mayLog(event, editorActive)) logKey(event, handled, InputProbe::wheel)
            return handled
        }
        logKey(event, handled = true, sink = InputProbe::wheel)
        logKey(event, handled = true, sink = InputProbe::volume)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> onVolumeKey(up, event.repeatCount)
            KeyEvent.ACTION_UP -> onVolumeKeyUp()
        }
        return true
    }

    /**
     * Whether a text editor has the keyboard: the IME says it is accepting text, or the focused
     * view is a text editor (Compose's host view answers true while a TextField has focus).
     * Either one stops the wheel log.
     */
    fun editorActive(focused: android.view.View?, imm: android.view.inputmethod.InputMethodManager?): Boolean =
        runCatching { imm?.isAcceptingText == true }.getOrDefault(true) ||
            runCatching { focused?.onCheckIsTextEditor() == true }.getOrDefault(true)

    private fun mayLog(event: KeyEvent, editorActive: Boolean): Boolean {
        val printing = runCatching { event.isPrintingKey }.getOrDefault(true)
        val char = runCatching { event.unicodeChar }.getOrDefault(1)
        return ProbeFormat.mayLogWheelKey(event.keyCode, printing, char, editorActive)
    }

    /** One probe line (first press and release only). Never throws, never changes what is consumed. */
    private fun logKey(event: KeyEvent, handled: Boolean, sink: (String) -> Unit) {
        if (!ProbeFormat.shouldLogKey(event.action, event.repeatCount)) return
        runCatching {
            sink(
                ProbeFormat.keyLine(
                    name = KeyEvent.keyCodeToString(event.keyCode),
                    keyCode = event.keyCode,
                    scanCode = event.scanCode,
                    source = event.source,
                    action = event.action,
                    repeatCount = event.repeatCount,
                    heldMs = if (event.action == KeyEvent.ACTION_UP) event.eventTime - event.downTime else null,
                    device = event.device?.name,
                    handled = handled,
                ),
            )
        }
    }
}
