package com.kevin.legion.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kevin.legion.notes.AlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * `AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` -
 * `.scratch/notes-lists-calendar/issues/03-android-alarm-mechanism.md`'s finding #1: "revoking
 * `SCHEDULE_EXACT_ALARM` kills the app process and cancels every pending exact alarm. ...
 * [this broadcast] is the only recovery path." One of the three callers of the one idempotent
 * [AlarmScheduler.rescheduleAll] - the other two are app start and reboot.
 *
 * Fires on BOTH directions of the permission changing (granted -> revoked and revoked -> granted),
 * so [AlarmScheduler.rescheduleAll] has to handle both without being told which happened - it
 * does, since it always re-derives `canScheduleExact` fresh from `AlarmManager` itself rather than
 * trusting anything cached.
 */
class ExactAlarmPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                AlarmScheduler.rescheduleAll(context)
            } finally {
                pending.finish()
            }
        }
    }
}
