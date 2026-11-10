package com.kevin.legion.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.kevin.legion.spokenbrief.HomeBrief
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fires on an OS geofence transition for a place registered by [GeofenceManager]
 * (location-intelligence ticket 05). May run with `AriaForegroundService` NOT alive at all - the
 * whole point of moving off GPS-poll distance math is that `GeofencingClient` wakes the process
 * for this receiver regardless of what else is running, same shape as
 * [com.kevin.legion.service.ReminderAlarmReceiver] firing off `AlarmManager` with no live Service.
 *
 * **Drives the SAME arrival path the GPS-poll fallback uses, does not re-implement it** (ticket
 * 05 part D, explicit): both this receiver and
 * `AriaForegroundService.startArrivalMonitor` call [ArrivalController.onArrived] and nothing else -
 * this receiver's only job is turning an OS geofence transition into that call.
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent)
        if (event == null) {
            Log.w(TAG, "GeofencingEvent.fromIntent returned null - not a geofence broadcast?")
            return
        }
        if (event.hasError()) {
            Log.w(TAG, "geofence transition delivered with error code ${event.errorCode}")
            return
        }

        // ENTER drives the arrival path. EXIT does one thing only: it records leaving home, for the
        // home brief (ADR 0082). [ReminderController]'s model is still arrival-triggered only.
        val labels = event.triggeringGeofences?.mapNotNull { it.requestId }.orEmpty()
        // EXIT has one reader (ADR 0082): leaving home is what lets the next home arrival brief.
        if (event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT) {
            if (HomeBrief.HOME_LABEL in labels) HomeBrief.noteLeftHome(context)
            return
        }
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return
        if (labels.isEmpty()) return

        // onReceive must return quickly, but ArrivalController.onArrived does Room reads and,
        // via ProactiveGate, may open a network socket to speak the reminder aloud - both need to
        // outlive this call. Same goAsync shape as ReminderAlarmReceiver.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // A single transition can name more than one geofence (overlapping radii around
                // two saved places close together) - surface each place's reminders independently
                // rather than picking just one.
                for (label in labels) {
                    ArrivalController.onArrived(context, label)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "GeofenceReceiver"
        const val ACTION_GEOFENCE_EVENT = "com.kevin.legion.action.GEOFENCE_EVENT"
    }
}
