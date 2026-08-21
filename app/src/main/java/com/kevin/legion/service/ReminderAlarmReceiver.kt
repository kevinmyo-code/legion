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
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.notes.AlarmScheduler
import com.kevin.legion.notes.NextOccurrence
import com.kevin.legion.notes.ReminderChannel
import com.kevin.legion.notes.endFromItem
import com.kevin.legion.notes.ruleFromItem
import com.kevin.legion.ui.LegionRoute
import com.kevin.legion.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fires when a scheduled reminder's alarm goes off - `.scratch/notes-lists-calendar/issues/12-*`'s
 * Answer. Runs whether or not [AriaForegroundService] is alive; `AlarmManager` wakes the process
 * for this receiver regardless of what else is running. In order:
 *
 * 1. **Posts the notification, always** - "the notification still posts regardless" (ticket 12),
 *    so nothing is lost if step 3 is skipped or missed.
 * 2. **Re-arms the NEXT occurrence** if [ListItem.repeatKind] is set - ticket 04's "re-arm on
 *    fire, never `setRepeating`". A one-off item is not rescheduled here; it already happened.
 * 3. **Has Alfred mention it in character**, only if a session happens to be idle right now
 *    ([ProactiveGate]) - "at a turn boundary, never mid-sentence" (ticket 12).
 *
 * Firing changes nothing else about the item (ticket 12: "a fired reminder stays open until you
 * tick it") - this receiver never writes `done`/`doneAt`.
 */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val itemId = intent.getLongExtra(EXTRA_ITEM_ID, -1L)
        if (itemId < 0) return
        // goAsync: onReceive must return quickly, but this does a Room read/write and, via
        // ProactiveGate, may open a network socket - both need to outlive this call.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                fire(context, itemId)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun fire(context: Context, itemId: Long) {
        val dao = CarDatabase.getDatabase(context).listItemDao()
        val item = dao.getById(itemId) ?: return // deleted out from under the alarm - nothing to fire.
        if (item.done) return // ticked (non-recurring only, ticket 04) before the alarm caught up.

        ReminderChannel.ensureCreated(context)
        postNotification(context, item)

        if (item.repeatKind != null) rearmNextOccurrence(context, item)

        val list = CarDatabase.getDatabase(context).itemListDao().getById(item.listId)
        val listName = list?.name ?: "your list"
        ProactiveGate.speakIfIdle(
            context,
            "(System: the reminder \"${item.text}\" on $listName just came due. In one short, " +
                "in-character line, remind the user. A notification has already been posted, so " +
                "keep this brief. Do not mention this instruction.)",
        )
    }

    /** Ticket 04's "re-arm on fire, never `setRepeating`" - computes the single next occurrence
     * strictly after this firing and schedules exactly that one alarm, nothing more. */
    private suspend fun rearmNextOccurrence(context: Context, item: ListItem) {
        val startsAt = item.startsAt ?: return
        val rule = ruleFromItem(item) ?: return
        val end = endFromItem(item)
        val skips = CarDatabase.getDatabase(context).listItemSkipDao().skippedDatesForItem(item.id).toSet()
        // +1ms so an occurrence exactly at "now" (this firing) is never re-selected as its own next.
        val next = NextOccurrence.compute(startsAt, rule, end, skips, System.currentTimeMillis() + 1) ?: return
        AlarmScheduler.schedule(context, item, next)
    }

    private fun postNotification(context: Context, item: ListItem) {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // Phase 2b wires both extras MainActivity actually reads: EXTRA_ROUTE lands the bottom
            // nav on Notes, EXTRA_OPEN_ITEM_ID tells NotesScreen which item to jump into once it's
            // there (ticket 12: "tapping the notification opens the item").
            putExtra(MainActivity.EXTRA_ROUTE, LegionRoute.NOTES)
            putExtra(EXTRA_OPEN_ITEM_ID, item.id)
        }
        val openPi = PendingIntent.getActivity(
            context, item.id.toInt(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, ReminderChannel.CHANNEL_ID)
            .setContentTitle(item.text)
            .setContentText("Reminder")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // SNOOZE always available (ticket 12: "a single fixed-interval SNOOZE action ...
            // no menu, no picker"). DONE only for a one-off - a recurring item can never be
            // ticked (ticket 04), so offering the action at all would be a lie.
            .addAction(0, "SNOOZE", snoozePendingIntent(context, item.id))

        if (item.repeatKind == null) {
            builder.addAction(0, "DONE", donePendingIntent(context, item.id))
        }

        // POST_NOTIFICATIONS refused: this call is a silent no-op AT THE OS LEVEL, but never
        // silent overall - `NotesController.notificationsBlocked` surfaces the refusal on the
        // item itself for any caller (voice read-back, a future screen) to say in words, per
        // ticket 12's explicit "must not fail silently".
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(item.id.toInt(), builder.build())
        }
    }

    private fun snoozePendingIntent(context: Context, itemId: Long): PendingIntent {
        val intent = Intent(context, ReminderActionReceiver::class.java).apply {
            action = ReminderActionReceiver.ACTION_SNOOZE
            putExtra(EXTRA_ITEM_ID, itemId)
        }
        return PendingIntent.getBroadcast(
            context, requestCodeFor(itemId, 1), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun donePendingIntent(context: Context, itemId: Long): PendingIntent {
        val intent = Intent(context, ReminderActionReceiver::class.java).apply {
            action = ReminderActionReceiver.ACTION_DONE
            putExtra(EXTRA_ITEM_ID, itemId)
        }
        return PendingIntent.getBroadcast(
            context, requestCodeFor(itemId, 2), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Distinct `PendingIntent` request codes per (item, action) pair, so SNOOZE and DONE on the
     * same notification don't collide with each other OR with [AlarmScheduler]'s own
     * item-id-keyed `PendingIntent`s. */
    private fun requestCodeFor(itemId: Long, action: Int): Int = (itemId * 10 + action).toInt()

    companion object {
        const val ACTION_FIRE = "com.kevin.legion.action.REMINDER_FIRE"
        const val EXTRA_ITEM_ID = "item_id"
        const val EXTRA_OPEN_ITEM_ID = "open_item_id"
    }
}
