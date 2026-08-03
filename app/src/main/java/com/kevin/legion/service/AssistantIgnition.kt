package com.kevin.legion.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The assistant's on/off switch (ticket 07 resolution §1: "explicit user
 * toggle, off by default"). This is the only place [AriaForegroundService] is
 * started or stopped BY CONSENT now that `BootReceiver` is deleted - nothing
 * starts it implicitly on install, on boot, or on `MainActivity.onCreate`.
 *
 * One other call site sends it an intent:
 * `ui/assistant/AssistantStrip.kt`'s tap-to-talk sends `ACTION_TALK`. That is
 * normally a no-op on lifecycle (the strip only draws when this toggle is
 * already on, so the service is already up), but if the persisted flag reads
 * true while the process has been killed, that `startService` restarts the
 * service. Not a consent bypass - the driver already opted in and the flag
 * still says so - but it does mean this object is no longer the ONLY thing
 * that can bring the service up.
 *
 * The persisted flag is the toggle's remembered position, not a live signal
 * that the service is actually running - if the process is killed out from
 * under it (low memory, battery optimiser) the flag can read `true` while
 * nothing is alive. `START_STICKY` (see [AriaForegroundService.onStartCommand])
 * covers the OS-restart case; a genuine liveness check is out of scope here,
 * same as the rest of ticket 07's "minimal host, not a redesign" posture.
 */
object AssistantIgnition {
    private const val PREFS = "assistant_ignition"
    private const val KEY_ENABLED = "enabled"

    // In-memory mirror of the persisted flag, lazily seeded from disk on the
    // first read. Exists so a caller besides the Settings toggle - namely the
    // persistent tap-to-talk strip, which lives outside the settings tab
    // entirely - can observe the flag changing live instead of polling
    // SharedPreferences or missing a toggle flipped elsewhere in the same
    // process. isEnabled() stays the source of truth for a one-shot read;
    // this is purely a process-life cache of the same bit.
    private var mirror: MutableStateFlow<Boolean>? = null

    /** Off by default (ticket 07 resolution §1) - a fresh install asks for nothing. */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    /**
     * Live mirror of [isEnabled]. Seeded from disk the first time anything
     * observes it (whichever of [start]/[stop]/this runs first in the
     * process), then updated in-memory by [start]/[stop] from then on -
     * those are the only writers of the underlying preference, so the mirror
     * can never drift from disk within one process's lifetime.
     */
    fun enabledState(context: Context): StateFlow<Boolean> =
        mirrorFor(context).asStateFlow()

    private fun mirrorFor(context: Context): MutableStateFlow<Boolean> =
        mirror ?: MutableStateFlow(isEnabled(context)).also { mirror = it }

    private fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        mirrorFor(context).value = enabled
    }

    /**
     * Flip on. Callers (the Settings toggle) are responsible for holding
     * POST_NOTIFICATIONS and RECORD_AUDIO *before* calling this - this
     * function does not request permissions itself, since permission
     * launchers are Activity-scoped and this object is not.
     */
    fun start(context: Context) {
        setEnabled(context, true)
        ContextCompat.startForegroundService(
            context,
            Intent(context, AriaForegroundService::class.java),
        )
    }

    /** Flip off. Stops the service; ledger/pantry/fleet are unaffected. */
    fun stop(context: Context) {
        setEnabled(context, false)
        context.stopService(Intent(context, AriaForegroundService::class.java))
    }
}
