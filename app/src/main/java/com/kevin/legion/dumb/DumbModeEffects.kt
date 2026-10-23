package com.kevin.legion.dumb

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * What switching dumb mode on and off does to the rest of the phone (ADR 0062). Watches the
 * controller's state in the application scope, so the Quick Settings tile (which may flip the mode
 * with no Activity alive) triggers exactly the same effects as the app.
 *
 * Each step is idempotent, because the first observation after a process restart reports the
 * persisted state as if it were a change:
 * - **first observation** only re-asserts: on re-applies the DND policy (which keeps the user's
 *   saved state); off restores DND if a save is lying around from a crash;
 * - **off to on** clears the previous session's digest and applies the policy;
 * - **on to off** restores the user's DND and marks the digest to be shown.
 *
 * Grayscale was ruled in and then out the same day (Kevin, 2026-10-22); nothing here touches the colours.
 */
class DumbModeEffects(
    private val isOn: Flow<Boolean>,
    private val dnd: DumbDnd,
    private val digest: HeldDigest,
    private val scope: CoroutineScope,
    private val onResult: (DndResult) -> Unit = {},
) {
    fun start() {
        scope.launch {
            var previous: Boolean? = null
            isOn.distinctUntilChanged().collect { on ->
                // One failed step must not end the collector: later flips still have to be applied.
                runCatching { step(previous, on) }.onFailure { Log.w(TAG, "Dumb mode step failed", it) }
                previous = on
            }
        }
    }

    internal fun step(previous: Boolean?, on: Boolean) {
        if (on) {
            if (previous == false) digest.clear()
            onResult(dnd.enter())
        } else {
            onResult(dnd.exit())
            if (previous == true) digest.markPending()
        }
    }

    companion object {
        private const val TAG = "DumbModeEffects"

        fun stateFlow(controller: DumbModeController): Flow<Boolean> = controller.state.map { it.on }
    }
}
