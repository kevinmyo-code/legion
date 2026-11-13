package com.kevin.legion.leaveby

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kevin.legion.MidnightApplication
import com.kevin.legion.engine.dates.DatesAgenda
import com.kevin.legion.location.LocationController
import com.kevin.legion.navigation.DriveSeconds
import com.kevin.legion.navigation.GeoPoint
import com.kevin.legion.navigation.MapboxNavController
import com.kevin.legion.navigation.MapboxTokenSource
import com.kevin.legion.navigation.NavDestination
import com.kevin.legion.navigation.NavFormat
import com.kevin.legion.navigation.resolve.PlaceSearch
import com.kevin.legion.navigation.resolve.SearchAnswer
import com.kevin.legion.navigation.resolve.SearchQuery
import com.kevin.legion.notes.ReminderChannel
import com.kevin.legion.service.ProactiveBus
import com.kevin.legion.ui.MainActivity
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The process's [LeaveByController] and the Android end of each of its ports (daily-loops ticket 05).
 * Rides the foreground loops that already exist: [maybeRefresh] is called from
 * `ClaudeResultPolling`'s `alsoEachTick` (no new loop, no WorkManager), and one `AlarmManager` alarm
 * does the timing, as the Dates reminders do. **With the assistant off and the app closed nothing
 * refreshes, and an alarm armed earlier still fires** with the figures it had; after a reboot no
 * alarm is armed until the next refresh, because a leave-by figure from before a reboot is stale.
 */
object LeaveBy {
    @Volatile private var controller: LeaveByController? = null

    fun controller(context: Context): LeaveByController {
        controller?.let { return it }
        return synchronized(this) {
            controller ?: build(context.applicationContext).also { controller = it }
        }
    }

    /** The lines last worked out, for the calendar; empty where there is no app (a plain unit test). */
    fun linesNow(context: Context): Map<Long, String> =
        if (context.applicationContext is MidnightApplication) controller(context).lines.value else emptyMap()

    /** The existing loops' hook; a no-op until [LeaveByController.REFRESH_EVERY_MS] has passed. */
    suspend fun maybeRefresh(context: Context) {
        controller(context).maybeRefresh()
    }

    private fun build(app: Context): LeaveByController {
        val mapp = app as MidnightApplication
        return LeaveByController(
            events = LeaveByEvents { from, to ->
                DatesAgenda.windowed(app, from, to, from)
                    .filter { !it.allDay && !it.dueIsInferred && !it.location.isNullOrBlank() }
                    .map { LeaveByEvent(it.recordId, it.title, it.dueAt, it.location.orEmpty().trim()) }
            },
            drive = AndroidLeaveByDrive(
                app, mapp.mapboxTokens, mapp.navController, com.kevin.legion.navigation.resolve.MapboxPlaceSearch(),
            ),
            store = PrefsLeaveByStore(app),
            alarm = AlarmLeaveByAlarm(app),
            deliver = { notice -> deliver(app, notice) },
            trouble = { what, e -> android.util.Log.w("LeaveBy", what, e) },
        )
    }

    /** Alfred says it when speech is allowed, and the notification posts too (Kevin, 2026-11-12). */
    private suspend fun deliver(context: Context, notice: LeaveByNotice) {
        val zone = ZoneId.systemDefault()
        val outcome = ProactiveBus.speakIfAllowed(context, LeaveByRaise.build(notice, zone))
        if (LeaveByRaise.postsNotification(outcome)) LeaveByNotifier.post(context, notice, zone)
    }
}

/**
 * Geocodes the event's own words with the Mapbox Search SDK (the only route Mapbox's terms allow from
 * a phone) and asks the nav controller for the drive time. Everything is held in memory only. Every
 * way it can fail is a sentence, never a guess: no permission, no recent fix, no token, no place
 * match, a Directions failure.
 */
class AndroidLeaveByDrive(
    private val context: Context,
    private val tokens: MapboxTokenSource,
    private val nav: MapboxNavController,
    private val search: PlaceSearch,
) : LeaveByDriveSource {
    @Suppress("ReturnCount") // one named refusal per precondition, in order
    override suspend fun driveTo(location: String): LeaveByDrive = withContext(Dispatchers.Main) {
        if (!LocationController.hasPermission(context)) {
            return@withContext LeaveByDrive.Unavailable(LeaveByWording.NO_PERMISSION)
        }
        if (!tokens.state.value.isSet) return@withContext LeaveByDrive.Unavailable(NavFormat.NOT_SET_UP)
        val fix = LocationController.state.value
        if (fix == null || System.currentTimeMillis() - fix.time > MAX_FIX_AGE_MS) {
            return@withContext LeaveByDrive.Unavailable(LeaveByWording.NO_FIX)
        }
        val near = GeoPoint(fix.latitude, fix.longitude)
        val hit = when (val a = search.search(SearchQuery(text = location, near = near, limit = 1))) {
            is SearchAnswer.Hits -> a.candidates.first()
            SearchAnswer.NoMatch -> return@withContext LeaveByDrive.Unavailable(LeaveByWording.NO_PLACE)
            is SearchAnswer.Failed -> return@withContext LeaveByDrive.Unavailable("the place lookup failed: ${a.why}")
        }
        when (val r = nav.driveSecondsNow(NavDestination(hit.name, hit.latitude, hit.longitude))) {
            is DriveSeconds.Ok -> LeaveByDrive.Ok(r.seconds, hit.name)
            is DriveSeconds.Unavailable -> LeaveByDrive.Unavailable(r.why)
        }
    }

    private companion object {
        /** A fix older than this is "no recent fix": the position behind a leave-by time must be current. */
        const val MAX_FIX_AGE_MS = 20L * 60 * 1000
    }
}

/** [LeaveByStore] in a private SharedPreferences file: ids, times and the setting. No place, no coordinates. */
class PrefsLeaveByStore(context: Context) : LeaveByStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    override fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
    }

    override fun silenced(eventId: Long): Boolean = eventId.toString() in silencedIds()

    @Synchronized
    override fun silence(eventId: Long) {
        val kept = (silencedIds().toList() + eventId.toString()).takeLast(MAX_HELD).toSet()
        prefs.edit().putStringSet(KEY_SILENCED, kept).apply()
    }

    private fun silencedIds(): Set<String> = prefs.getStringSet(KEY_SILENCED, emptySet()).orEmpty()

    override fun fired(key: String): Boolean = prefs.contains(FIRED_PREFIX + key)

    @Synchronized
    override fun markFired(key: String, startMs: Long) {
        val edit = prefs.edit()
        // Pruned by the event's own start: a day after it began nothing can fire for it again.
        val cutoff = System.currentTimeMillis() - PRUNE_AFTER_MS
        prefs.all.filter { (k, v) -> k.startsWith(FIRED_PREFIX) && (v as? Long ?: 0L) < cutoff }
            .keys.forEach { edit.remove(it) }
        edit.putLong(FIRED_PREFIX + key, startMs).apply()
    }

    override fun pending(): LeaveByPending? = prefs.getString(KEY_PENDING, null)?.let {
        try {
            val o = JSONObject(it)
            LeaveByPending(
                o.getLong("id"), o.getString("title"), o.getString("location"),
                o.getLong("start"), o.getLong("leaveBy"), o.getDouble("drive"),
            )
        } catch (e: org.json.JSONException) {
            // A corrupt pending record is the same as none: no raise is made from it.
            android.util.Log.w("LeaveBy", "pending record unreadable, dropped", e)
            null
        }
    }

    override fun setPending(pending: LeaveByPending?) {
        val edit = prefs.edit()
        if (pending == null) {
            edit.remove(KEY_PENDING)
        } else {
            edit.putString(
                KEY_PENDING,
                JSONObject().put("id", pending.eventId).put("title", pending.title)
                    .put("location", pending.location).put("start", pending.startMs)
                    .put("leaveBy", pending.leaveByMs).put("drive", pending.driveSeconds).toString(),
            )
        }
        edit.apply()
    }

    private companion object {
        const val PREFS = "leave_by"
        const val KEY_ENABLED = "enabled"
        const val KEY_SILENCED = "silenced_event_ids"
        const val KEY_PENDING = "pending"
        const val FIRED_PREFIX = "fired:"
        const val MAX_HELD = 200
        const val PRUNE_AFTER_MS = 24L * 60 * 60 * 1000
    }
}

/**
 * The ONE leave-by alarm: exact when Android allows it, inexact and Doze-exempt otherwise (the Dates
 * scheduler's answer).
 */
class AlarmLeaveByAlarm(context: Context) : LeaveByAlarm {
    private val app = context.applicationContext

    private fun manager() = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pi(stage: LeaveByAlarm.Stage): PendingIntent = PendingIntent.getBroadcast(
        app, REQUEST_CODE,
        Intent(app, LeaveByAlarmReceiver::class.java)
            .setAction(LeaveByAlarmReceiver.ACTION_ALARM)
            .putExtra(LeaveByAlarmReceiver.EXTRA_STAGE, stage.name),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    override fun arm(atMs: Long, stage: LeaveByAlarm.Stage) {
        val am = manager()
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi(stage))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi(stage))
    }

    override fun cancel() {
        manager().cancel(pi(LeaveByAlarm.Stage.RECHECK))
    }

    private companion object {
        const val REQUEST_CODE = 90017
    }
}

/** Fired only by [AlarmLeaveByAlarm]'s own PendingIntent (`exported=false`). Runs with or without the service alive. */
class LeaveByAlarmReceiver : BroadcastReceiver() {
    @Suppress("TooGenericExceptionCaught") // a failed recheck must never crash the receiver; the next refresh re-arms
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ALARM) return
        val stage = intent.getStringExtra(EXTRA_STAGE)
            ?.let { s -> LeaveByAlarm.Stage.entries.firstOrNull { it.name == s } } ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                LeaveBy.controller(context).onAlarm(stage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("LeaveBy", "alarm $stage failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ALARM = "com.kevin.legion.action.LEAVE_BY_ALARM"
        const val EXTRA_STAGE = "stage"
    }
}

/**
 * The notification's two actions: skip this event, or stop leave-by for good (compulsion test (d)).
 * `exported=false`.
 */
class LeaveByActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        val action = intent.action
        if (action != ACTION_SKIP && action != ACTION_STOP) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val controller = LeaveBy.controller(context)
                if (action == ACTION_STOP) {
                    controller.setEnabled(false)
                } else if (eventId >= 0) {
                    controller.silenceEvent(eventId)
                }
                NotificationManagerCompat.from(context).cancel(LeaveByNotifier.TAG, eventId.toInt())
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SKIP = "com.kevin.legion.action.LEAVE_BY_SKIP"
        const val ACTION_STOP = "com.kevin.legion.action.LEAVE_BY_STOP"
        const val EXTRA_EVENT_ID = "event_id"
    }
}

/** Posts the leave-by notification on the reminders channel (a sound: it is a "go now"). */
object LeaveByNotifier {
    const val TAG = "leave_by"

    fun post(context: Context, n: LeaveByNotice, zone: ZoneId) {
        // POST_NOTIFICATIONS refused: a silent no-op at the OS level, as the other reminders document.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ReminderChannel.ensureCreated(context)
        val code = n.eventId.toInt()
        val open = PendingIntent.getActivity(
            context, code,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(name: String, offset: Int) = PendingIntent.getBroadcast(
            context, code * 2 + offset,
            Intent(context, LeaveByActionReceiver::class.java).setAction(name)
                .putExtra(LeaveByActionReceiver.EXTRA_EVENT_ID, n.eventId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = LeaveByWording.notificationText(n, zone)
        val notification = NotificationCompat.Builder(context, ReminderChannel.CHANNEL_ID)
            .setContentTitle("Time to leave")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "SKIP THIS ONE", action(LeaveByActionReceiver.ACTION_SKIP, 0))
            .addAction(0, "STOP LEAVE-BY", action(LeaveByActionReceiver.ACTION_STOP, 1))
            .build()
        NotificationManagerCompat.from(context).notify(TAG, code, notification)
    }
}
