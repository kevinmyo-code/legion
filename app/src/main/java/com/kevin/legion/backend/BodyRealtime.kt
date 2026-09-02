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

/**
 * Live-sync's body-aspect slice: an instant pull triggered by Supabase Realtime's
 * `postgres_changes` feed on all eight body tables, mirroring [EventsRealtime]'s own shape and
 * every rule its class doc states - **the realtime event is a TRIGGER, not a data source; every
 * actual merge still runs through [BodySync.pull]**. `MidnightApplication.onCreate` calls [bind]
 * once for the process.
 *
 * **One [PullCoalescer] shared across all eight tables' change feeds, not eight separate ones** -
 * [BodySync.pull] already re-fetches and re-merges every table on a single call, so a burst of
 * `postgres_changes` events across several body tables in quick succession (a voice turn that
 * logs a meal AND a bodyweight reading in one breath) collapses into at most one in-flight
 * [BodySync.pull] plus one more immediately after, exactly [PullCoalescer]'s own contract -
 * running a separate coalescer per table would let two of them race independent, redundant pulls
 * for what is really one underlying event.
 *
 * Subscribes on app foreground, unsubscribes on app background, fails silently always - identical
 * posture to [EventsRealtime], see that object's own class doc for the full reasoning.
 */
object BodyRealtime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var channels: List<RealtimeChannel> = emptyList()
    @Volatile private var bound = false

    private val TABLES = listOf(
        "bodyweight_logs", "meal_logs", "meal_targets", "sleep_logs",
        "sleep_targets", "workout_plans", "workout_plan_items", "workout_set_logs",
    )

    /** Idempotent - safe to call more than once, matching [EventsRealtime.bind]'s own contract. */
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
    // doc comment traces, never carried over to this file. `channels.isNotEmpty()` stays a
    // synchronous pre-launch check (unchanged) since it is reading in-memory state, not auth.
    private fun subscribe(context: Context) {
        val client = SupabaseClientProvider.get(context) ?: return
        if (channels.isNotEmpty()) return

        scope.launch {
            if (SupabaseAuth(context).resolveSignedInUserId() == null) return@launch
            try {
                val coalescer = PullCoalescer(scope) {
                    try {
                        val report = BodySync.pull(context, SupabaseBodyBackend(client))
                        MidnightEvents.bodyRealtimePullSucceeded(
                            report.inserted, report.updated, report.skippedLocalNewer, report.tombstoned,
                        )
                    } catch (e: Exception) {
                        MidnightEvents.bodyRealtimePullFailed(e)
                    }
                }
                val opened = TABLES.map { table ->
                    val realtimeChannel = client.realtime.channel("body-changes-$table")
                    realtimeChannel.postgresChangeFlow<PostgresAction>(schema = "public") {
                        this.table = table
                    }.onEach {
                        coalescer.trigger()
                    }.launchIn(scope)
                    realtimeChannel.subscribe()
                    realtimeChannel
                }
                channels = opened
            } catch (e: Exception) {
                channels = emptyList()
                MidnightEvents.bodyRealtimeSubscribeFailed(e)
            }
        }
    }

    private fun unsubscribe() {
        val toClose = channels
        if (toClose.isEmpty()) return
        channels = emptyList()
        scope.launch {
            for (channel in toClose) {
                try {
                    channel.unsubscribe()
                } catch (e: Exception) {
                    MidnightEvents.bodyRealtimeSubscribeFailed(e)
                }
            }
        }
    }
}
