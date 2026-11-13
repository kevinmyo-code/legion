package com.kevin.legion.leaveby

import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Calendar events starting in a window. Throws when the calendar cannot be read: unreadable is not empty. */
fun interface LeaveByEvents {
    suspend fun upcoming(fromMs: Long, toMs: Long): List<LeaveByEvent>
}

/** The drive time from the phone's position to a place named in words, with traffic now. */
fun interface LeaveByDriveSource {
    suspend fun driveTo(location: String): LeaveByDrive
}

/** The one alarm. [Stage.RECHECK] recomputes and decides; [Stage.FIRE] says it. Arming replaces any earlier alarm. */
interface LeaveByAlarm {
    enum class Stage { RECHECK, FIRE }

    fun arm(atMs: Long, stage: Stage)

    fun cancel()
}

/** What the recheck decided, kept until the FIRE alarm: the figures the line is built from. */
data class LeaveByPending(
    val eventId: Long,
    val title: String,
    val location: String,
    val startMs: Long,
    val leaveByMs: Long,
    val driveSeconds: Double,
)

/** Small durable state: the setting, per-event silence, what was already said, and the pending figures. */
interface LeaveByStore {
    /** The one setting. Off is off for good: no estimate is asked for and nothing is raised. */
    fun enabled(): Boolean

    fun setEnabled(on: Boolean)

    fun silenced(eventId: Long): Boolean

    fun silence(eventId: Long)

    /**
     * [key] is `eventId@startMs`: moving an event is a new start and may be raised again, never the
     * same start twice.
     */
    fun fired(key: String): Boolean

    fun markFired(key: String, startMs: Long)

    fun pending(): LeaveByPending?

    fun setPending(pending: LeaveByPending?)
}

/**
 * Leave-by times (`.scratch/daily-loops/issues/05-leave-by.md`): for a timed calendar event with a
 * place, the drive time from where the phone is now WITH traffic, a leave-by time, one notification
 * at that time and Alfred saying it.
 *
 * **Flow.** [refresh] (every [REFRESH_EVERY_MS] at most, from the existing foreground loops via
 * [maybeRefresh]) looks [WINDOW_MS] ahead, asks for each drive time, publishes a line per event
 * ([lines]) and arms ONE alarm for the soonest unsaid event: a RECHECK [RECHECK_LEAD_MS] before its
 * leave-by time, which recomputes with fresh traffic ("once more shortly before firing") and then
 * arms the FIRE alarm for the recomputed leave-by time. FIRE hands a [LeaveByNotice] to [deliver].
 *
 * **Honesty.** No raise is ever made from a guess: no fix, no token, no permission, no place match,
 * a Directions error, or an unreadable calendar all mean NO figure, no alarm for that event, and a
 * line saying why wherever a leave-by line would show. A raise built from earlier figures is refused
 * if it is more than [LATE_GRACE_MS] late, if the event started, moved, or was deleted, if it is
 * silenced, or if the drive is under [MIN_DRIVE_S] (the user is already there).
 *
 * **Compulsion test (CLAUDE.md section 7):** (a) anchored to the event and the traffic figure, both
 * in the raise's reason and facts; (b) actionable now (leave); (c) says nothing of absence or the
 * app, and the prompt forbids it; (d) silenced per event or for good ([LeaveByStore]).
 */
@Suppress("LongParameterList") // the ports ARE the seams; each one is faked in the tests
class LeaveByController(
    private val events: LeaveByEvents,
    private val drive: LeaveByDriveSource,
    private val store: LeaveByStore,
    private val alarm: LeaveByAlarm,
    private val deliver: suspend (LeaveByNotice) -> Unit,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** Where a swallowed failure is reported (a log line in production); never thrown on. */
    private val trouble: (String, Throwable) -> Unit = { _, _ -> },
) {
    private val lock = Mutex()

    @Volatile private var lastRefreshMs = 0L

    private val _lines = MutableStateFlow<Map<Long, String>>(emptyMap())

    /** Event id to the line shown on that event (a leave-by time, or why there is none). Not persisted. */
    val lines: StateFlow<Map<Long, String>> = _lines.asStateFlow()

    /** One refresh when [REFRESH_EVERY_MS] has passed since the last; returns whether it ran. */
    suspend fun maybeRefresh(): Boolean {
        if (nowMs() - lastRefreshMs < REFRESH_EVERY_MS) return false
        refresh()
        return true
    }

    suspend fun refresh() = lock.withLock { refreshLocked() }

    /** Entry for the alarm receiver. */
    suspend fun onAlarm(stage: LeaveByAlarm.Stage) = lock.withLock {
        if (stage == LeaveByAlarm.Stage.FIRE) fireLocked()
        refreshLocked()
    }

    /** "Skip this one": silences the event and arms whatever is next. */
    suspend fun silenceEvent(eventId: Long) {
        store.silence(eventId)
        refresh()
    }

    /** The one setting. Turning it off clears every line and the alarm at once. */
    suspend fun setEnabled(on: Boolean) {
        store.setEnabled(on)
        refresh()
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught") // an unreadable calendar keeps what was armed
    private suspend fun refreshLocked() {
        val now = nowMs()
        lastRefreshMs = now
        if (!store.enabled()) {
            _lines.value = emptyMap()
            store.setPending(null)
            alarm.cancel()
            return
        }
        val found = try {
            events.upcoming(now, now + WINDOW_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unreadable is not empty: keep the alarm and lines as they were, claim nothing new.
            trouble("the calendar could not be read", e)
            return
        }
        val candidates = found
            .filter { it.location.isNotBlank() && it.startMs > now }
            .sortedBy { it.startMs }
            .take(MAX_EVENTS)
        val plans = mutableListOf<LeaveByPlan>()
        val lines = LinkedHashMap<Long, String>()
        for (event in candidates) {
            if (store.silenced(event.id)) {
                lines[event.id] = LeaveByWording.SILENCED_LINE
                continue
            }
            val plan = planFor(event, now)
            plans += plan
            lines[event.id] = LeaveByWording.eventLine(plan, zone())
        }
        _lines.value = lines
        schedule(
            plans.filterIsInstance<LeaveByPlan.Known>()
                .filter { !store.fired(keyOf(it.event.id, it.event.startMs)) }
                .minByOrNull { it.leaveByMs },
            now,
        )
    }

    private suspend fun planFor(event: LeaveByEvent, now: Long): LeaveByPlan =
        when (val d = drive.driveTo(event.location)) {
            is LeaveByDrive.Ok -> LeaveByPlan.Known(
                event, LeaveByWording.leaveByMs(event.startMs, d.seconds), d.seconds, d.toName, now,
            )
            is LeaveByDrive.Unavailable -> LeaveByPlan.Unknown(event, d.why)
        }

    private fun schedule(next: LeaveByPlan.Known?, now: Long) {
        if (next == null) {
            store.setPending(null)
            alarm.cancel()
            return
        }
        if (next.leaveByMs - now > RECHECK_LEAD_MS) {
            store.setPending(null)
            alarm.arm(next.leaveByMs - RECHECK_LEAD_MS, LeaveByAlarm.Stage.RECHECK)
        } else {
            // Inside the lead: these figures were read just now, so they ARE the recheck.
            store.setPending(
                LeaveByPending(
                    next.event.id, next.event.title, next.event.location, next.event.startMs,
                    next.leaveByMs, next.driveSeconds,
                ),
            )
            alarm.arm(maxOf(next.leaveByMs, now + FIRE_SOON_MS), LeaveByAlarm.Stage.FIRE)
        }
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught") // one named refusal per check, in order
    private suspend fun fireLocked() {
        val p = store.pending() ?: return
        store.setPending(null)
        val now = nowMs()
        val key = keyOf(p.eventId, p.startMs)
        if (!store.enabled() || store.silenced(p.eventId) || store.fired(key)) return
        if (now >= p.startMs || now - p.leaveByMs > LATE_GRACE_MS || p.driveSeconds < MIN_DRIVE_S) return
        // The event must still exist at the same start with a place: it may have moved or been deleted.
        val same = try {
            events.upcoming(now, p.startMs + 1).any {
                it.id == p.eventId && it.startMs == p.startMs && it.location.isNotBlank()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            trouble("the event could not be re-read before firing", e)
            false
        }
        if (!same) return
        // Marked BEFORE delivery: once per event start, even if delivery throws.
        store.markFired(key, p.startMs)
        deliver(LeaveByNotice(p.eventId, p.title, p.location, p.startMs, p.leaveByMs, p.driveSeconds, now))
    }

    companion object {
        /** How far ahead events are considered. */
        const val WINDOW_MS = 3L * 60 * 60 * 1000

        /** The estimate is never asked for more often than this (Kevin's floor: 15 minutes). */
        const val REFRESH_EVERY_MS = 15L * 60 * 1000

        /** The recomputation happens this long before the leave-by time. */
        const val RECHECK_LEAD_MS = 5L * 60 * 1000

        /** A FIRE alarm for a time already reached goes off this soon rather than in the past. */
        const val FIRE_SOON_MS = 1_000L

        /** Past this far behind its leave-by time a raise is dropped: a stale instruction is worse than none. */
        const val LATE_GRACE_MS = 10L * 60 * 1000

        /** Under this the user is already there; nothing is raised. */
        const val MIN_DRIVE_S = 120.0

        /** At most this many events cost a drive-time request per refresh. */
        const val MAX_EVENTS = 3

        fun keyOf(eventId: Long, startMs: Long): String = "$eventId@$startMs"
    }
}
