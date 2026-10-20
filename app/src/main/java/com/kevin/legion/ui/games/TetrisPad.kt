package com.kevin.legion.ui.games

import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The buttons of the handheld layout; touch buttons and a physical controller both speak this. */
enum class PadButton {
    LEFT, RIGHT, DOWN, UP, A, B, HOLD, START, SELECT,

    /** The big labelled hard-drop key beside A and B (Kevin, 2026-10-19). Up hard-drops too. */
    DROP;

    /** Left, Right and Down repeat while held; the rest fire once per press. */
    val repeats: Boolean get() = this == LEFT || this == RIGHT || this == DOWN
}

/** Delayed auto shift and auto repeat rate, in milliseconds (the usual handheld feel). */
const val DAS_MS = 170L
const val ARR_MS = 50L

/**
 * Auto-repeat for one held button: fires [action] at once, again after [dasMs], then every [arrMs]
 * until [release]. Runs on [scope] and uses its [delay], so a test's virtual time drives it.
 */
class HoldRepeat(
    private val scope: CoroutineScope,
    private val dasMs: Long = DAS_MS,
    private val arrMs: Long = ARR_MS,
    private val action: () -> Unit,
) {
    private var job: Job? = null

    /** A second press while held (a key-down echo) does nothing. */
    fun press() {
        if (job?.isActive == true) return
        job = scope.launch {
            action()
            delay(dasMs)
            while (isActive) {
                action()
                delay(arrMs)
            }
        }
    }

    fun release() {
        job?.cancel()
        job = null
    }
}

/**
 * Hardware key to pad button, for a controller or the A25 used as an emulator handheld. Null means
 * "not ours": the caller must let the event through so Back and the volume keys still work.
 */
@Suppress("CyclomaticComplexMethod") // a flat key table; a map would say the same with more noise
fun padButtonForKey(keyCode: Int): PadButton? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_LEFT -> PadButton.LEFT
    KeyEvent.KEYCODE_DPAD_RIGHT -> PadButton.RIGHT
    KeyEvent.KEYCODE_DPAD_DOWN -> PadButton.DOWN
    KeyEvent.KEYCODE_DPAD_UP -> PadButton.UP
    KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> PadButton.A
    KeyEvent.KEYCODE_BUTTON_B -> PadButton.B
    KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1 -> PadButton.HOLD
    KeyEvent.KEYCODE_BUTTON_START -> PadButton.START
    KeyEvent.KEYCODE_BUTTON_SELECT -> PadButton.SELECT
    else -> null
}
