package com.kevin.legion.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kevin.legion.notes.AlarmScheduler
import com.kevin.legion.notes.NotesController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * SNOOZE / DONE tapped directly on a fired reminder's notification
 * (`.scratch/notes-lists-calendar/issues/12-*`) - must work with the app fully backgrounded or
 * killed, hence a receiver rather than routing through any in-process controller state.
 */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val itemId = intent.getLongExtra(ReminderAlarmReceiver.EXTRA_ITEM_ID, -1L)
        if (itemId < 0) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_SNOOZE -> snooze(context, itemId)
                    ACTION_DONE -> done(context, itemId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** "A single fixed-interval SNOOZE action ... reschedules the alarm through the same path as
     * any other schedule, so it inherits ticket 03's rules - including the downgrade-to-inexact
     * behaviour when exact permission is refused" (ticket 12) - [AlarmScheduler.schedule] IS that
     * same path, called here exactly as `notes/NotesController` calls it for a driver-set schedule. */
    private suspend fun snooze(context: Context, itemId: Long) {
        val item = NotesController.itemById(context, itemId) ?: return
        AlarmScheduler.schedule(context, item, System.currentTimeMillis() + SNOOZE_INTERVAL_MS)
        dismissNotification(context, itemId)
    }

    private suspend fun done(context: Context, itemId: Long) {
        val item = NotesController.itemById(context, itemId) ?: return
        // NotesController.tick refuses a recurring item (ticket 04) - the notification never
        // offers DONE for one in the first place (ReminderAlarmReceiver.postNotification), so
        // reaching here with a recurring item can only mean a stale/replayed intent, and tick()
        // silently no-ops on it rather than throwing.
        NotesController.tick(context, item)
        dismissNotification(context, itemId)
    }

    private fun dismissNotification(context: Context, itemId: Long) {
        context.getSystemService(NotificationManager::class.java).cancel(itemId.toInt())
    }

    companion object {
        const val ACTION_SNOOZE = "com.kevin.legion.action.REMINDER_SNOOZE"
        const val ACTION_DONE = "com.kevin.legion.action.REMINDER_DONE"

        /** Ticket 12: "a single fixed-interval SNOOZE action ... do not label the snooze interval
         * with false precision" - the notification action is just labelled SNOOZE, never "in 1h",
         * precisely because this number rides on `setAndAllowWhileIdle`'s still-unmeasured real
         * lateness (the map's one remaining fog item). One hour is the intention, not a promise. */
        const val SNOOZE_INTERVAL_MS = 60 * 60 * 1000L
    }
}
