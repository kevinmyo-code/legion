package com.kevin.legion.backend.engine

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.kevin.legion.MidnightEvents
import com.kevin.legion.backend.ChecklistsSync
import com.kevin.legion.backend.EventsSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * What Supabase Realtime is to an aspect on [Transport.SUPABASE], this is to one on
 * [Transport.DJANGO]: the thing that notices a change made somewhere else without waiting for the
 * next foreground return. There is no socket to hold open against the engine, so it is a 60-second
 * poll of `/api/changes` instead - `.scratch/django-engine/research/execution-plan.md` Phase 2
 * step 2, "Realtime trigger is replaced by a 60 s poll while the app is foreground,
 * `WorkManager`-free as the codebase already is."
 *
 * **This REPLACES Realtime for a Django aspect; it does not run alongside it.**
 * [com.kevin.legion.backend.EventsRealtime] now declines to subscribe when `events` is on Django
 * (see its own guard), and this poll declines to run for any aspect that is not - so exactly one
 * mechanism is live per aspect at any moment, and an aspect left on Supabase keeps its socket and
 * never sees this at all.
 *
 * **Foreground only**, on [ProcessLifecycleOwner] - the same whole-process signal
 * [com.kevin.legion.backend.EventsRealtime] uses, and for the same reason: a backgrounded app
 * polling a household server every minute is a battery complaint. The periodic foreground pulls
 * (`EventsSync.maybeAutoPull`, `ChecklistsSync.maybeAutoPull`, throttled to 5 minutes) are
 * UNCHANGED and remain the fallback whenever this is not running.
 *
 * **Fails silently, always** - a failed poll degrades to a [MidnightEvents] breadcrumb and the app
 * behaves exactly as it did without this class, relying on the next foreground pull. Same posture
 * as `EventsRealtime`'s own "never a dialog, never a crash".
 *
 * **The two pull lambdas are constructor parameters, not calls made inside** - that is the test
 * seam. `EnginePollTest` constructs one with counting lambdas and asserts the poll calls NOTHING
 * while both aspects are on Supabase, which is the property this ticket's brief asks to be proven
 * rather than assumed.
 */
class EnginePoll(
    private val transport: EngineTransport,
    private val pullEvents: suspend () -> Unit,
    private val pullChecklists: suspend () -> Unit,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    private var loop: Job? = null

    /** True when at least one aspect this poll covers is on [Transport.DJANGO]. Read before the
     * loop starts AND on every tick, so flipping the debug transport row stops or starts polling
     * within one interval rather than needing a process restart. */
    internal fun shouldPoll(): Boolean = COVERED_ASPECTS.any {
        transport.transportFor(it) == Transport.DJANGO
    }

    /** One pass. Calls only the aspects actually on Django - an aspect on Supabase is served by
     * its Realtime channel and must not be pulled from here as well. */
    internal suspend fun pollOnce() {
        if (transport.transportFor(EngineBackends.ASPECT_EVENTS) == Transport.DJANGO) pullEvents()
        if (transport.transportFor(EngineBackends.ASPECT_CHECKLISTS) == Transport.DJANGO) pullChecklists()
    }

    /**
     * Registers the foreground/background observer. Idempotent - safe to call more than once,
     * matching [com.kevin.legion.backend.EventsRealtime.bind]'s own contract.
     *
     * [scope] is the process-lifetime scope `MidnightApplication` already owns for exactly this
     * kind of work, passed in rather than created here so a test can drive the loop on its own
     * dispatcher.
     */
    fun bind(scope: CoroutineScope) {
        if (bound) return
        bound = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    start(scope)
                }

                override fun onStop(owner: LifecycleOwner) {
                    stop()
                }
            },
        )
    }

    private fun start(scope: CoroutineScope) {
        if (loop?.isActive == true) return
        if (!shouldPoll()) return
        loop = scope.launch {
            // The first tick waits a full interval deliberately: MainActivity.onResume has just
            // fired its own drain-then-pull, so polling immediately would duplicate that round
            // trip for no new information.
            while (isActive) {
                delay(intervalMs)
                if (!shouldPoll()) break
                // Guarded, never bare: this loop runs on a process-lifetime SupervisorJob scope
                // with no CoroutineExceptionHandler, so an uncaught throw would reach the thread's
                // default handler and take the app down. See guardingForeground's own doc comment.
                guardingForeground(onFailure = { MidnightEvents.enginePollFailed(it) }) { pollOnce() }
            }
        }
    }

    private fun stop() {
        loop?.cancel()
        loop = null
    }

    @Volatile private var bound = false

    companion object {
        /** 60 s, from the execution plan's own wording. Not tunable at runtime - a knob here would
         * be one more thing to get wrong on a household server nobody is paying per request for. */
        const val DEFAULT_INTERVAL_MS = 60_000L

        /**
         * **Two aspects, and the two that were added on 2026-09-07 deliberately are not here.**
         * [com.kevin.legion.backend.PlacesSync] and [com.kevin.legion.backend.VoiceNotesSync] both
         * gained a pull that day and both stayed out of this list, each for its own reason stated
         * in its own `maybeAutoPull` doc comment: a saved place changes a handful of times a year
         * and is read from the Room replica on the geofence hot path, so a request a minute buys
         * freshness nobody is waiting for; and voice notes are sync-and-not-Realtime by Kevin's own
         * 2026-09-04 ruling, which this poll IS the Realtime replacement for - adding them here
         * would re-adopt by the back door the mechanism that ruling declines. Both are served by
         * the foreground cold-start-and-resume pull instead, which is the mechanism that
         * demonstrably delivered body's server-side change on the A25.
         *
         * A third entry is a decision about that aspect's read pattern, not a line to add because
         * the aspect happens to be on Django.
         */
        private val COVERED_ASPECTS =
            listOf(EngineBackends.ASPECT_EVENTS, EngineBackends.ASPECT_CHECKLISTS)

        /**
         * The production instance's two lambdas: the SAME pull functions the foreground path runs,
         * never a second implementation of the merge (which is the whole rule
         * [com.kevin.legion.backend.EventsRealtime]'s class doc states - "the realtime event is a
         * TRIGGER, not a data source"). Each resolves its own backend at call time so flipping a
         * transport row mid-session is picked up on the next tick.
         */
        fun forApp(context: Context): EnginePoll {
            val app = context.applicationContext
            return EnginePoll(
                transport = EngineTransport(app),
                pullEvents = {
                    val backend = EngineBackends(app).eventsBackendAfterAuth()
                    if (backend != null) {
                        val report = EventsSync.pull(app, backend)
                        MidnightEvents.enginePollPulled(
                            EngineBackends.ASPECT_EVENTS,
                            report.inserted,
                            report.updated,
                            report.tombstoned,
                        )
                    }
                },
                pullChecklists = {
                    val backend = EngineBackends(app).checklistsBackend()
                    if (backend != null) {
                        val report = ChecklistsSync.pull(app, backend)
                        MidnightEvents.enginePollPulled(
                            EngineBackends.ASPECT_CHECKLISTS,
                            report.inserted,
                            report.updated,
                            report.tombstoned,
                        )
                    }
                },
            )
        }
    }
}
