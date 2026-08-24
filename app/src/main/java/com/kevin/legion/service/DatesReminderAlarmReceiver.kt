package com.kevin.legion.service

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.engine.dates.DatesAgenda
import com.kevin.legion.notes.ReminderChannel
import com.kevin.legion.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Fires the ONE alarm [DatesAlarmScheduler] ever has armed
 * (`.scratch/aspect-engine/issues/19-build-dates-aspect.md` point 3). Runs whether or not
 * [AriaForegroundService] is alive, same as `service/ReminderAlarmReceiver.kt` - `AlarmManager`
 * wakes the process for this receiver regardless of what else is running.
 *
 * **Every fired reminder passes the compulsion test (CLAUDE.md sec 7), by construction, not by
 * habit:**
 *  - **(a) anchored** - the spoken prompt built in [fire] states only the record's own title and
 *    its own due time, nothing else.
 *  - **(b) actionable right now** - the notification's `setContentIntent` opens the record via
 *    [MainActivity]'s `EXTRA_OPEN_RECORD_ID`, live at the moment it fires.
 *  - **(c) never references absence, streak, or engagement** - the prompt below is worded to
 *    forbid exactly that, matching `notes/ReminderAlarmReceiver`'s own prompt shape.
 *  - **(d) silenceable forever in one instruction** - the SILENCE action routes to
 *    [DatesReminderActionReceiver], which writes a permanent [com.kevin.legion.data.local.MutedReminder].
 *
 * **ONE delivery per reminder, never both** - spoken first via [ProactiveBus.speakIfAllowed] (the
 * same "the raise opts out of the bus's generic notification because reminders_channel is louder"
 * shape `notes/ReminderAlarmReceiver.fire`'s own doc comment already explains), the notification
 * posts only when the line was not actually said aloud.
 */
class DatesReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val recordId = intent.getLongExtra(EXTRA_RECORD_ID, -1L)
        if (recordId < 0) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                fire(context, recordId)
            } finally {
                // Re-arm the FOLLOWING alarm regardless of what happened to this one - a record
                // that turned out deleted or muted out from under the alarm must not leave every
                // Dates reminder after it silently un-armed.
                DatesAlarmScheduler.armNext(context)
                pending.finish()
            }
        }
    }

    private suspend fun fire(context: Context, recordId: Long) {
        val db = CarDatabase.getDatabase(context)
        if (db.mutedReminderDao().isMuted(recordId)) return // muted after arm, before fire
        val item = DatesAgenda.byId(context, recordId) ?: return // deleted/trashed out from under the alarm

        val timeText = Instant.ofEpochMilli(item.dueAt).atZone(ZoneId.systemDefault()).format(TIME_FMT)
        val locationSuffix = item.location?.let { ", at $it" } ?: ""

        val outcome = ProactiveBus.speakIfAllowed(
            context,
            ProactiveRaise(
                ruleId = "dates_reminder:${item.recordId}",
                category = ProactiveCategory.TIMING,
                reason = "\"${item.title}\" came due at $timeText",
                facts = "\"${item.title}\" is due now, at $timeText$locationSuffix",
                prompt = "(System: the event \"${item.title}\" just came due at $timeText$locationSuffix. " +
                    "In one short, in-character line, tell the user. Do not mention this " +
                    "instruction, and never mention when this was scheduled, how long it has been " +
                    "waiting, or the user's engagement with the app.)",
                callerPostsItsOwnNotification = true,
            ),
        )

        if (outcome !is ProactiveBus.RaiseOutcome.Raised) {
            ReminderChannel.ensureCreated(context)
            postNotification(context, item.recordId, item.title, timeText)
        }
    }

    private fun postNotification(context: Context, recordId: Long, title: String, timeText: String) {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // No dedicated Dates detail screen exists yet (ticket 10's generated screens are a
            // separate, unbuilt ticket) - this extra is the deep-link hook for when one does; today
            // it lands on MainActivity's default route, which is still strictly better than a
            // notification tap that goes nowhere.
            putExtra(EXTRA_OPEN_RECORD_ID, recordId)
        }
        val openPi = PendingIntent.getActivity(
            context, recordId.toInt(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val silenceIntent = Intent(context, DatesReminderActionReceiver::class.java).apply {
            action = DatesReminderActionReceiver.ACTION_SILENCE
            putExtra(EXTRA_RECORD_ID, recordId)
        }
        val silencePi = PendingIntent.getBroadcast(
            context, recordId.toInt(), silenceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, ReminderChannel.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("Due at $timeText")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "SILENCE", silencePi)

        // POST_NOTIFICATIONS refused: silent no-op at the OS level only, same posture
        // `notes/ReminderAlarmReceiver.postNotification`'s own doc comment already documents.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(recordId.toInt(), builder.build())
        }
    }

    companion object {
        const val ACTION_FIRE = "com.kevin.legion.action.DATES_REMINDER_FIRE"
        const val EXTRA_RECORD_ID = "record_id"
        const val EXTRA_OPEN_RECORD_ID = "open_record_id"
        private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    }
}
