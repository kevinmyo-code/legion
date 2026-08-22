package com.kevin.legion.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.kevin.legion.MidnightEvents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The assistant's on/off switch (ticket 07 resolution §1: "explicit user
 * toggle, off by default"). [start]/[stop] are the only places the flag
 * itself is WRITTEN - the Settings toggle's own handler is the only caller of
 * either, so consent only ever changes there.
 *
 * **[resumeIfEnabled] is a second, narrower entry point, added 2026-08-17
 * after the flag was found reading "On" on a real device while
 * [AriaForegroundService] was not running at all** (a reboot, or any process
 * death, killed the service and nothing ever restarted it - `BootReceiver`
 * was deleted in ticket 07 specifically to stop a car-launcher-era
 * `BootReceiver` from starting `MainActivity` on boot, and that removal
 * incidentally left NOTHING reconciling the service to an already-true flag
 * either). It never calls [setEnabled] - see its own doc for why that
 * distinction is load-bearing.
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
    private const val TAG = "AssistantIgnition"
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
    /**
     * True when the last [resumeIfEnabled] was REFUSED by Android's background-start restriction -
     * so [isEnabled] says on, and nothing is actually running.
     *
     * Read by the settings ignition row so it can say that out loud instead of repeating the flag.
     * Cleared by the next successful start, which in practice is the user opening the app
     * ([com.kevin.legion.ui.MainActivity] calls [resumeIfEnabled] on resume, and a launch is a
     * genuine foreground start that Android permits).
     */
    @Volatile var startRefused: Boolean = false
        private set

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

    /**
     * Reconciliation, not consent. Measured on the A25 2026-08-17: after a reboot (or any
     * process death) [AriaForegroundService] was NOT running while the persisted flag - and
     * every UI surface reading it - said "On". Before this function the ONLY callers of [start]
     * were the Settings toggle's own handler, so nothing ever brought the service back up once
     * the process holding it died; the flag just sat there being wrong.
     *
     * This is deliberately NOT [start]: it never calls [setEnabled]. The flag already reflects a
     * consent decision the driver made earlier - this function's whole job is making reality
     * match a flag that is already true, never changing what the flag says. If [isEnabled] is
     * false, this does nothing and returns false: a driver who never opted in gets nothing
     * started on their behalf, on boot or on launch, full stop.
     *
     * Callers: [com.kevin.legion.MidnightApplication.onCreate] (the app is starting because the
     * user launched it - no background-start restriction applies, so the service comes up with
     * every foreground type the permissions it already holds allow) and [BootReceiver] (the
     * service comes up WITHOUT the `microphone` type - see
     * [AriaForegroundService.startForegroundCompat]'s own doc for why that type specifically
     * cannot be requested from a boot receiver at this app's target SDK, and
     * [AriaForegroundService.onStartCommand]'s ACTION_CAR_SWITCHED/every restart already
     * re-declaring types for how the mic type gets added back once the app is actually opened).
     *
     * @return true if the service was (re)started, false if the flag was off and nothing happened.
     */
    fun resumeIfEnabled(context: Context): Boolean {
        if (!isEnabled(context)) return false
        return try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AriaForegroundService::class.java),
            )
            startRefused = false
            true
        } catch (t: Throwable) {
            // ForegroundServiceStartNotAllowedException on API 31+, and this is NOT an edge case.
            // Measured on the A25, 2026-08-21: `code:DENIED`, `startForegroundCount=0`. The comment
            // at the MidnightApplication call site claimed the app "is starting because the user
            // opened it - no background-start restriction applies". **That assumption is false.**
            // Application.onCreate runs whenever the PROCESS starts, for any reason - a broadcast, a
            // job, a package replace - and Android refuses a foreground-service start from the
            // background whatever the app thinks it is doing.
            //
            // Swallowed rather than rethrown because there is nothing to do about it here: Android
            // will not be argued out of this. What matters is that it stops being INVISIBLE, which
            // is what [startRefused] is for - the flag said "On" for 45 minutes while nothing ran,
            // a call came in, and nobody found out until the log was read.
            startRefused = true
            Log.w(TAG, "foreground service start refused: ${t::class.simpleName}: ${t.message}")
            MidnightEvents.appStartWorkFailed("assistant_ignition_refused", t)
            false
        }
    }
}
