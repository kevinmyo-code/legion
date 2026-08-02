package com.kevin.legion.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * The assistant's on/off switch (ticket 07 resolution §1: "explicit user
 * toggle, off by default"). This is the ONLY place [AriaForegroundService]
 * is started or stopped now that `BootReceiver` is deleted - nothing starts
 * it implicitly on install, on boot, or on `MainActivity.onCreate`.
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

    /** Off by default (ticket 07 resolution §1) - a fresh install asks for nothing. */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    private fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
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
