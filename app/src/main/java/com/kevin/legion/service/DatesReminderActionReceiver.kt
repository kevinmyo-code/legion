package com.kevin.legion.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MutedReminder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The Dates reminder notification's SILENCE action - CLAUDE.md sec 7's compulsion test, clause
 * (d): "silenceable forever in one instruction". Writes a permanent [MutedReminder] row; the
 * record itself, and everything else about it, is completely untouched - muting is deliberately
 * NOT a [com.kevin.legion.engine.RecordStore] write, see [MutedReminder]'s own doc comment for why.
 *
 * Fired only by [DatesReminderAlarmReceiver]'s own notification action `PendingIntent`, never by
 * the system - `exported=false` in the manifest, same posture as `service/ReminderActionReceiver.kt`.
 */
class DatesReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SILENCE) return
        val recordId = intent.getLongExtra(DatesReminderAlarmReceiver.EXTRA_RECORD_ID, -1L)
        if (recordId < 0) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                CarDatabase.getDatabase(context).mutedReminderDao()
                    .mute(MutedReminder(recordId = recordId, mutedAt = System.currentTimeMillis()))
                NotificationManagerCompat.from(context).cancel(recordId.toInt())
                // Only one Dates alarm is ever armed at a time - if the just-muted record was that
                // one, this re-derives and arms whatever now qualifies as next. A cheap no-op
                // otherwise (armNext always re-derives fresh, never trusts what fired).
                DatesAlarmScheduler.armNext(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SILENCE = "com.kevin.legion.action.DATES_REMINDER_SILENCE"
    }
}
