package com.kevin.legion.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.CarDatabase
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

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
 *  1. **The phone is not silenced** (ADR 0082, Kevin 2026-11-10: *"can speak at screen off. unless
 *     i silent the phone ofc"*). Silenced is ringer silent or vibrate, or Do Not Disturb on
 *     ([isSilenced]); LEGION's own Quiet mode is both, so it reads as silenced. **The screen-on
 *     check this replaced (proactive-mode decision 17) is gone**: a line may be spoken with the
 *     screen off and the phone locked.
 *  2. **No appointment is running right now** - a direct read of the local `events` table
 *     (`kind = 'appointment'`), repointed off the retired `calendar/CalendarProvider.kt`'s live
 *     `CalendarContract` read by one-today ticket 01, "cut Google entirely". Kevin kept this half.
 *
 * **Both limits are accepted rather than hidden.** An unbooked conversation, a call that is not on
 * the calendar, a meeting that ran long - none of those are visible, and quiet hours plus the
 * ringer switch carry what the checks miss. (A daily cap used to carry some of it too; Kevin
 * removed it, ADR 0082.)
 *
 * ### Speaking with the screen off, and why the microphone may not open with it
 *
 * Playing the line needs only the running `AriaForegroundService` and its network, both of which
 * a foreground service keeps with the screen off. **Opening the microphone for a reply needs the
 * service to hold the `microphone` foreground type**, which Android grants only when the service
 * started while LEGION was on screen; a boot-started service never has it, and a capture without it
 * hears silence. So [mayListen] reads that type, and a raise that asks for a reply is spoken
 * WITHOUT listening when it is missing. Traced, not run on the phone.
 *
 * ### Where a "wait for the screen" option would go
 *
 * [route] is the one decision between spoken, posted and rung. A future "hold the line and speak it
 * at the next screen-on" is one more [Route] value decided here and handled beside [notify]; no
 * raise site needs to change, because raise sites only build a [ProactiveRaise].
 *
 * **No calendar permission must never read as "no meetings".** Same trap `OpenerCalendarBriefing`
 * splits three ways: unreadable is not empty. Here the safe reading is *unknown, so post it* rather
 * than *free, so say it out loud*.
 *
 * ### Nothing is silently dropped, and nothing is delivered twice
 *
 * A raise that cannot be spoken is **notified** (ticket 06 call 3). Today's behaviour drops
 * everything when the phone is idle-but-locked, and a silently dropped safety warning is the worst
 * outcome on this map. **The gate governs whether a line is SPOKEN, not whether it exists.**
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

    /** How one raise reaches him. */
    enum class Route {
        /** Said aloud now. */
        SPEAK,

        /** Posted on [CHANNEL_ID]. Never dropped. */
        NOTIFY,

        /** Rung like an alarm, facts on screen, said after Dismiss. Only a Safety [ProactiveUrgency.Alarm]. */
        RING,
    }

    /**
     * The one decision between [Route.SPEAK], [Route.NOTIFY] and [Route.RING], pure so it is tested
     * without a phone. [gateAllowsSpeech] is false when quiet hours refused a non-exempt raise.
     * [listenerUp] is false when nothing is collecting the bus (the assistant service is not
     * running): a line emitted then is lost, so it is posted instead.
     *
     * An alarm rings inside quiet hours or on a silenced phone, because ordinary speech at media
     * volume would not wake him and would not sound through silent at all. Otherwise it is an
     * ordinary Safety raise: spoken, or posted during a meeting.
     */
    @Suppress("LongParameterList") // every input is one named fact the route depends on
    internal fun routeFor(
        gateAllowsSpeech: Boolean,
        alarm: Boolean,
        quietHour: Boolean,
        silenced: Boolean,
        inMeeting: Boolean,
        listenerUp: Boolean,
    ): Route = when {
        alarm && (quietHour || silenced) -> Route.RING
        !gateAllowsSpeech || silenced || inMeeting || !listenerUp -> Route.NOTIFY
        else -> Route.SPEAK
    }

    /** [routeFor] with this phone's live readings. */
    suspend fun route(context: Context, raise: ProactiveRaise, gateAllowsSpeech: Boolean, time: LocalTime): Route =
        routeFor(
            gateAllowsSpeech = gateAllowsSpeech,
            alarm = raise.urgency is ProactiveUrgency.Alarm,
            quietHour = ProactiveBus.isQuietHour(time),
            silenced = phoneSilenced(context),
            inMeeting = inAMeeting(context),
            listenerUp = ProactiveBus.hasListener,
        )

    /**
     * Waits up to [timeoutMs] for something to collect the bus, for a raise site that may have
     * woken a process whose assistant service is still starting (an alarm dismiss, a geofence
     * arrival). False on time-out; the raise is then posted rather than emitted into nothing.
     */
    suspend fun awaitListener(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { ProactiveBus.listeners.first { it > 0 } } != null

    /**
     * True when a line may be spoken ALOUD right now: the phone is not silenced and no meeting is
     * running. False means post it instead - never means drop it.
     *
     * Both halves fail SAFE, toward the notification: an unreadable calendar, an unreadable ringer
     * or a thrown query all resolve to "not now, post it", because the failure that matters is
     * speaking into a room, not posting one notification too many.
     */
    suspend fun maySpeakAloud(context: Context): Boolean = !phoneSilenced(context) && !inAMeeting(context)

    /**
     * Whether a spoken raise may also open the microphone for a reply: only while the assistant
     * service holds the `microphone` foreground type (see the class doc). Read in one place, by
     * [ProactiveBus], so no raise site has to know.
     */
    fun mayListen(): Boolean = AriaForegroundService.holdsMicrophoneType

    /**
     * Ringer silent or vibrate, or any Do Not Disturb filter other than "all", is silenced (Kevin's
     * "unless i silent the phone"). Pure, for the test.
     */
    internal fun isSilenced(ringerMode: Int, interruptionFilter: Int): Boolean =
        ringerMode != AudioManager.RINGER_MODE_NORMAL ||
            (interruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                interruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN)

    /** Unreadable reads as silenced: posting is the safe side. */
    fun phoneSilenced(context: Context): Boolean = runCatching {
        val audio = context.getSystemService(AudioManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)
        isSilenced(audio.ringerMode, nm.currentInterruptionFilter)
    }.getOrDefault(true)

    /**
     * True when an appointment is running right now - or when the read fails outright, which is
     * the unreadable-versus-empty split promoted to a rule of the raise contract (settled decision
     * 20). **One-today ticket 01, "cut Google entirely":** the local `events` table has no
     * permission to be refused any more, so the only failure mode left is a genuine Room exception;
     * kept fail-safe (`true`, i.e. "assume a meeting") for the identical reason the old
     * permission-refused branch did - the honest handling of not knowing is to post rather than
     * speak.
     */
    private suspend fun inAMeeting(context: Context): Boolean {
        val now = System.currentTimeMillis()
        return runCatching {
            CarDatabase.getDatabase(context).eventDao()
                .activeTimedByKindRunningAt(EventKind.EVENT, now)
                .isNotEmpty()
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
            .apply { raise.contentIntent?.let { setContentIntent(it) } }
            .build()
        return runCatching {
            NotificationManagerCompat.from(context).notify(raise.ruleId.hashCode(), notification)
            true
        }.getOrDefault(false)
    }
}
