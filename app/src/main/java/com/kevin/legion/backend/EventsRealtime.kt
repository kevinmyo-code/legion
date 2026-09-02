package com.kevin.legion.backend

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.kevin.legion.MidnightEvents
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Coalesces a burst of [trigger] calls into AT MOST one in-flight [pull] execution plus, if more
 * triggers arrived while it was already running, exactly ONE more immediately after it finishes -
 * never a pull per trigger. This is the mechanism [EventsRealtime] needs because a single write on
 * another device can fan out into several `postgres_changes` events in quick succession (an
 * upsert plus a skip-row insert, or a batch import), and [EventsSync.pull] already fetches
 * everything changed since the last successful pull - a second call queued directly behind a
 * first one would see the exact same server state that first call is about to observe anyway, so
 * running it twice back-to-back would add cost with zero additional information.
 *
 * **Deliberately generic over [pull] and independent of [RealtimeChannel]/[io.github.jan.supabase.SupabaseClient]**
 * so this can be exercised in a plain unit test with no Robolectric, no network, and no real
 * `postgres_changes` flow - see `PullCoalescerTest`.
 */
internal class PullCoalescer(
    private val scope: CoroutineScope,
    private val pull: suspend () -> Unit,
) {
    private val mutex = Mutex()

    /** Set by a [trigger] that arrives while a pull is already running, telling that IN-FLIGHT
     *  run to loop once more after it finishes rather than this call starting a second, concurrent
     *  pull of its own. `@Volatile` because [tryLock] failing and this write can race a genuinely
     *  concurrent caller on a different thread; the loop below only ever reads it while holding
     *  the lock, so a plain volatile flag (not a second [Mutex]) is enough for correctness here. */
    @Volatile private var queuedAgain = false

    /** Fire-and-forget - launches at most one coroutine that actually calls [pull], regardless of
     *  how many times this is called while one is already in flight. */
    fun trigger() {
        scope.launch { runOnce() }
    }

    private suspend fun runOnce() {
        if (!mutex.tryLock()) {
            // Another runOnce() already holds the lock and is either inside pull() right now or
            // about to check queuedAgain and loop - either way, THAT call is what will honour this
            // trigger, not a second concurrent pull started here.
            queuedAgain = true
            return
        }
        try {
            do {
                queuedAgain = false
                pull()
            } while (queuedAgain)
        } finally {
            mutex.unlock()
        }
    }
}

/**
 * Live-sync's final slice: an instant pull triggered by Supabase Realtime's `postgres_changes`
 * feed on `public.events`, so a change on another device or the web app reaches this phone without
 * waiting for the next foreground return. **The realtime event is a TRIGGER, not a data source -
 * every actual merge still runs through [EventsSync.pull], the one and only merge implementation.**
 * `MidnightApplication.onCreate` calls [bind] once for the process, same shape as
 * [com.kevin.legion.engine.mirror.MirrorLifecycleBinder.bind].
 *
 * **Lifecycle: subscribes on app foreground, unsubscribes on app background** (this ticket's own
 * brief) - a socket held open by a backgrounded app is a battery complaint waiting to happen, and
 * [ProcessLifecycleOwner] is the whole-process foreground/background signal
 * ([MirrorLifecycleBinder]'s own doc comment explains why that, not a single Activity's
 * `onStart`/`onStop`, is the right signal here too). The periodic foreground pull
 * ([EventsSync.maybeAutoPull]) is UNCHANGED and stays the fallback for whenever the socket is
 * down, slow to (re)connect, or simply never subscribed (not configured, nobody signed in yet) -
 * this object is an optimisation layered over that pull, never a replacement for it.
 *
 * **Fails silently, always.** A subscribe failure, a dropped socket, a `postgres_changes` decode
 * error inside supabase-kt - none of it may surface as a dialog or a crash, per the brief. Every
 * catch here degrades to [MidnightEvents] and leaves the app behaving exactly as it did before
 * this object existed: relying on the next foreground pull.
 */
object EventsRealtime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var channel: RealtimeChannel? = null
    @Volatile private var bound = false

    /** Idempotent - safe to call more than once (it will not double-register the lifecycle
     *  observer), matching [com.kevin.legion.engine.mirror.MirrorLifecycleBinder.bind]'s own
     *  contract. */
    fun bind(context: Context) {
        if (bound) return
        bound = true
        val app = context.applicationContext

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    subscribe(app)
                }

                override fun onStop(owner: LifecycleOwner) {
                    unsubscribe()
                }
            },
        )
    }

    // `subscribe` is a plain `fun` (called from a lifecycle callback that is not itself suspend),
    // so the cold-start-sensitive auth check cannot happen right here - it now happens as the
    // first thing inside `scope.launch` below, via SupabaseAuth.resolveSignedInUserId, which IS
    // suspend. Cold-start fix, 2026-09-02: the guard used to be a raw `currentUserId() == null`
    // read taken synchronously before the launch, the same race EventsSync.maybeAutoPull's own
    // doc comment traces, never carried over to this file. The `channel != null` guard stays a
    // synchronous pre-launch check (unchanged) since it is reading in-memory state, not auth.
    private fun subscribe(context: Context) {
        val client = SupabaseClientProvider.get(context) ?: return
        // Guard against a channel already open from a PRIOR onStart this same process never saw
        // an onStop for (defensive - ProcessLifecycleOwner does not double-fire onStart without an
        // intervening onStop, but a leaked channel from a previous subscribe attempt that itself
        // threw partway through must not be silently doubled).
        if (channel != null) return

        scope.launch {
            if (SupabaseAuth(context).resolveSignedInUserId() == null) return@launch
            try {
                val realtimeChannel = client.realtime.channel("events-changes")
                val coalescer = PullCoalescer(scope) {
                    // Caught here, not left to propagate into PullCoalescer's own loop - an
                    // uncaught throw would abandon that loop's queuedAgain check entirely (the
                    // `do { ... } while` never reaches its condition), leaking the mutex held via
                    // its own `finally` but silently dropping any trigger that arrived mid-pull.
                    try {
                        val report = EventsSync.pull(context, SupabaseEventsBackend(client))
                        MidnightEvents.eventsRealtimePullSucceeded(
                            report.inserted,
                            report.updated,
                            report.skippedLocalNewer,
                            report.tombstoned,
                            report.unrecognizedKinds,
                        )
                    } catch (e: Exception) {
                        MidnightEvents.eventsRealtimePullFailed(e)
                    }
                }
                realtimeChannel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "events"
                }.onEach {
                    coalescer.trigger()
                }.launchIn(scope)
                realtimeChannel.subscribe()
                channel = realtimeChannel
            } catch (e: Exception) {
                channel = null
                MidnightEvents.eventsRealtimeSubscribeFailed(e)
            }
        }
    }

    private fun unsubscribe() {
        val toClose = channel ?: return
        channel = null
        scope.launch {
            try {
                toClose.unsubscribe()
            } catch (e: Exception) {
                MidnightEvents.eventsRealtimeSubscribeFailed(e)
            }
        }
    }
}
