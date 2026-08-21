package com.kevin.legion.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kevin.legion.calendar.CalendarProvider

/**
 * Whether an unprompted line is SPOKEN or merely POSTED, and the channel it is posted on -
 * ticket 06 (`.scratch/proactive-mode/issues/06-delivery.md`).
 *
 * ### It speaks through the day, not only in a car
 *
 * Kevin, 2026-08-21, overriding the session-gated recommendation: *"speak to me even when im not
 * driving. thats the whole point of something like jarvis or alfred right, throughout the day keeps
 * me on track."*
 *
 * This is the delivery-layer counterpart to the concierge reframe (CLAUDE.md §1) - the same
 * correction applied to WHEN it may talk rather than to how it addresses him. Every raise that
 * exists today was written assuming a car; none of them assume one after this.
 *
 * ### What stops it speaking at a bad moment
 *
 * It cannot know it is in a meeting, so two cheap checks stand in ([maySpeakAloud]):
 *
 *  1. **The screen is on** - he is demonstrably with the phone. No permission, no guessing.
 *  2. **No calendar event is running right now** - reusing the read
 *     `calendar/OpenerCalendarBriefing.kt` already does.
 *
 * **Both limits are accepted rather than hidden.** An unbooked conversation, a call that is not on
 * the calendar, a meeting that ran long - none of those are visible, and the 3-a-day cap plus quiet
 * hours carry what the checks miss.
 *
 * **No calendar permission must never read as "no meetings".** Same trap `OpenerCalendarBriefing`
 * splits three ways: unreadable is not empty. Here the safe reading is *unknown, so post it* rather
 * than *free, so say it out loud*.
 *
 * ### Nothing is silently dropped, and nothing is delivered twice
 *
 * A raise that cannot be spoken is **notified** (ticket 06 call 3). Today's behaviour drops
 * everything when the phone is idle-but-locked, and a silently dropped safety warning is the worst
 * outcome on this map. **The cap governs whether a line is SPOKEN, not whether it exists.**
 *
 * And exactly one of the two happens, never both (ticket 06 call 5) - `ReminderAlarmReceiver` used
 * to speak a fired reminder AND post a notification for the same item in the same method. The cost
 * of fixing that is real: a spoken reminder now leaves nothing on the lock screen to find later.
 *
 * ### The channel is a second kill switch, and that cuts both ways
 *
 * One channel for all proactive delivery. It is a real escape hatch - Kevin can silence proactive at
 * the OS level without opening the app - and it means **nothing may ever claim a notification was
 * delivered or seen**, because the app cannot know. CLAUDE.md §7's outcome-verb rule already covers
 * that; this is a new place it applies.
 */
object ProactiveDelivery {

    const val CHANNEL_ID = "proactive_channel"

    /**
     * `IMPORTANCE_DEFAULT`, deliberately between the app's two existing postures: quieter than
     * `reminders_channel`'s `IMPORTANCE_HIGH` (a reminder is something he explicitly asked to be
     * interrupted for; a nudge is not), louder than the two silent `IMPORTANCE_LOW` service
     * channels (a warning nobody sees is not a warning).
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Proactive",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description =
                "Lines the assistant raises on its own when it cannot say them out loud. " +
                    "Turning this off silences them without the app knowing."
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * True when a line may be spoken ALOUD right now. False means post it instead - never means
     * drop it.
     *
     * Both halves fail SAFE, toward the notification: an unreadable calendar, a missing permission
     * or a thrown query all resolve to "not now, post it", because the failure that matters is
     * speaking into a room, not posting one notification too many.
     */
    fun maySpeakAloud(context: Context): Boolean = screenIsOn(context) && !inAMeeting(context)

    private fun screenIsOn(context: Context): Boolean =
        runCatching {
            context.getSystemService(PowerManager::class.java)?.isInteractive == true
        }.getOrDefault(false)

    /**
     * True when the calendar says an event is running right now - or when it **cannot be read at
     * all**, which is the unreadable-versus-empty split promoted to a rule of the raise contract
     * (settled decision 20). Without permission the honest answer is "I do not know where he is",
     * and the honest handling of not knowing is to post rather than speak.
     */
    private fun inAMeeting(context: Context): Boolean {
        if (!CalendarProvider.hasReadPermission(context)) return true
        val now = System.currentTimeMillis()
        return runCatching {
            CalendarProvider.eventsInWindow(context, now, now + 1)
                .any { !it.allDay && it.startMs <= now && it.endMs > now }
        }.getOrDefault(true)
    }

    /**
     * Posts [raise] to the shade. Returns false when it could not be posted at all (no
     * `POST_NOTIFICATIONS` grant) - **the one path where a raise really is lost**, and it is
     * returned rather than swallowed so a caller can log it honestly instead of assuming delivery.
     */
    fun notify(context: Context, raise: ProactiveRaise, text: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            // Same platform icon the app's three existing notifications use. There is no
            // app-drawn notification icon in res/ yet; inventing one here would be the only
            // notification in LEGION that looks different, for no reason.
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(raise.category.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        return runCatching {
            NotificationManagerCompat.from(context).notify(raise.ruleId.hashCode(), notification)
            true
        }.getOrDefault(false)
    }
}
