package com.kevin.legion.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kevin.legion.MidnightEvents
import com.kevin.legion.location.GeofenceManager
import com.kevin.legion.notes.AlarmScheduler
import com.kevin.legion.wellbeing.WellbeingDigestScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Re-arms every scheduled reminder after a reboot
 * (`.scratch/notes-lists-calendar/issues/03-android-alarm-mechanism.md`) - `AlarmManager` does not
 * persist alarms across a power cycle, so without this every reminder set before a reboot would
 * silently never fire again.
 *
 * **Still does NOT start `MainActivity`.** A `BootReceiver` existed before and was deleted in
 * `legion-shape` ticket 07 because it used to `startActivity(MainActivity)` on
 * `ACTION_BOOT_COMPLETED` - car-launcher behaviour with no place in a phone app the driver opens
 * on their own. That ruling is unchanged: nothing here brings any UI forward.
 *
 * **DOES now reconcile `AriaForegroundService` (2026-08-17, measured defect)**, via
 * [AssistantIgnition.resumeIfEnabled]. Previously this comment said the assistant's on/off state
 * was untouched here - that was true, and it was also the bug: [AssistantIgnition.start] was only
 * ever called from the Settings toggle's own handler, so a reboot left the persisted flag reading
 * "On" while the service that flag describes was simply not running, for as long as the app
 * stayed unopened. [resumeIfEnabled] is not a consent action - it never flips the flag, it only
 * starts the service the flag ALREADY says should be running - so a driver who never opted in
 * still gets nothing started on boot.
 *
 * The service is started here WITHOUT the `microphone` foreground-service type. At this app's
 * target SDK (34), `microphone` is on the documented boot-prohibited list -
 * `ForegroundServiceStartNotAllowedException` if a `BOOT_COMPLETED` receiver tries to claim it -
 * while `dataSync`/`connectedDevice` are not. [AriaForegroundService.startForegroundCompat] gates
 * the microphone bit on the app's own foreground-eligibility, not merely on the RECORD_AUDIO
 * permission, specifically so this call site cannot crash-loop the service it is trying to save.
 * The mic type is promoted back in once the driver actually opens the app (see
 * [com.kevin.legion.MidnightApplication.onCreate]'s own call to the same function, and
 * [AriaForegroundService.onStartCommand]'s re-declaration of types on every start).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Each guarded SEPARATELY, and neither is allowed to take the process down.
                // This scope has a SupervisorJob and no CoroutineExceptionHandler, so a throw
                // here reaches the default uncaught handler and crashes the process during boot
                // - the one moment nobody is watching. Guarding them independently also means a
                // failure to re-arm reminders cannot cost the assistant its restart, or vice
                // versa; before this they shared one try and the first thrower ate the second.
                runCatching { AlarmScheduler.rescheduleAll(context) }
                    .onFailure { MidnightEvents.appStartWorkFailed("boot_reschedule_alarms", it) }
                // NOTE: the sitrep used to have its own single alarm here (ticket 22). Ticket 32
                // (Kevin: "sitreps stay tap only or via voice activation only") retired it -
                // `SitrepScheduler`/`SitrepAlarmReceiver` are both gone, and nothing re-arms a
                // sitrep on boot anymore. A sitrep is produced only from a tap on the Home card or
                // a spoken `get_sitrep`, never queued.
                // The wellbeing digest's own single alarm (goal-plans ticket 05) - same
                // AlarmManager-forgets-everything-across-a-reboot problem as the sitrep's alarm
                // above, and the same no-op-when-never-set behaviour. Guarded separately for the
                // same reason every guard in this receiver is separate: one missed re-arm must
                // not cost any of the others theirs.
                runCatching { WellbeingDigestScheduler.rescheduleFromSettings(context) }
                    .onFailure { MidnightEvents.appStartWorkFailed("boot_reschedule_wellbeing_digest", it) }
                // resumeIfEnabled is a plain (non-suspend) SharedPreferences read plus a
                // startForegroundService IPC call - safe to fire from this receiver's IO scope
                // same as rescheduleAll above; nothing about starting a Service requires the
                // caller to be on the main thread. It is guarded because the boot-completed
                // foreground-service-start exemption is time-windowed: if this receiver is slow
                // enough that the window has closed, startForegroundService throws
                // ForegroundServiceStartNotAllowedException, and a failed assistant restart must
                // degrade to "still off" rather than to a boot-time crash.
                runCatching { AssistantIgnition.resumeIfEnabled(context) }
                    .onFailure { MidnightEvents.appStartWorkFailed("boot_resume_ignition", it) }
                // Geofences do NOT survive a reboot (location-intelligence ticket 05) - same
                // "AlarmManager forgets everything across a reboot" problem AlarmScheduler solves
                // above, for GeofencingClient instead. Guarded the same way, for the same reason:
                // a failure here must not cost the reminders or the ignition their restart.
                // Realistically a frequent no-op immediately after boot - GeofenceManager.
                // registerNearest needs a GPS fix from LocationController.state, and nothing has
                // requested one yet this process (that happens in AriaForegroundService.onCreate,
                // triggered by resumeIfEnabled just above, which hasn't produced a fix in the few
                // milliseconds since). That's fine: startArrivalMonitor's own 20s poll loop calls
                // registerNearest again once a fix exists, so this call is a courtesy for the rare
                // case a fix is already cached, not the only chance to re-arm.
                runCatching { GeofenceManager.registerNearest(context) }
                    .onFailure { MidnightEvents.appStartWorkFailed("boot_reregister_geofences", it) }
            } finally {
                pending.finish()
            }
        }
    }
}
