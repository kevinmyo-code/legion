package com.kevin.legion.backend

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.kevin.legion.MidnightEvents
import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.backend.engine.EngineTransport
import com.kevin.legion.backend.engine.Transport
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
 * Live-sync's memory-aspect slice: an instant pull triggered by Supabase Realtime's
 * `postgres_changes` feed on all three memory tables, mirroring [BodyRealtime]'s own shape and
 * every rule its class doc states - the realtime event is a TRIGGER, not a data source; every
 * actual merge still runs through [MemorySync.pull]. `MidnightApplication.onCreate` calls [bind]
 * once for the process.
 *
 * One [PullCoalescer] shared across all three tables' change feeds, same reasoning
 * [BodyRealtime]'s own class doc gives for its eight.
 *
 * Subscribes on app foreground, unsubscribes on app background, fails silently always - identical
 * posture to [BodyRealtime].
 */
object MemoryRealtime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var channels: List<RealtimeChannel> = emptyList()
    @Volatile private var bound = false

    private val TABLES = listOf("memories", "companion_memories", "memory_audit")

    /** Idempotent - safe to call more than once, matching [BodyRealtime.bind]'s own contract. */
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

    // Same cold-start-safe shape as BodyRealtime.subscribe's own doc comment: the auth check
    // happens inside `scope.launch` via SupabaseAuth.resolveSignedInUserId (suspend), never
    // synchronously before it.
    private fun subscribe(context: Context) {
        // The transport switch (django-engine Phase 5), the same guard
        // [EventsRealtime.subscribe] already carries and for the same reason: Realtime is
        // Supabase's mechanism, so an aspect moved to the Django engine must not also hold a
        // `postgres_changes` socket open against a project it no longer reads. Checked here rather
        // than in [bind] so flipping the debug transport row takes effect on the next foreground
        // return instead of needing a process restart.
        //
        // **What takes over is NOT a poll, and that is a real gap rather than an oversight.**
        // [com.kevin.legion.backend.engine.EnginePoll] covers `events` and `checklists` only, so a
        // `memory` aspect flipped to Django has no 60 s live-change mechanism at all - it falls back
        // to the five-minute foreground pull ([MemorySync.maybeAutoPull]), which is slower but never
        // wrong. Widening that poll belongs with `memory`'s own cutover run on the phone.
        val onDjango =
            EngineTransport(context).transportFor(EngineBackends.ASPECT_MEMORY) == Transport.DJANGO
        // Folded into ONE guard with the pre-existing `channels.isNotEmpty()` check rather than
        // sitting on its own line above it - byte for byte the shape [EventsRealtime.subscribe]
        // uses (`if (onDjango || channel != null) return`), and it also keeps this function inside
        // detekt's two-return ceiling without a suppression. Both halves are pure reads of
        // in-memory or SharedPreferences state, so the order between them is free.
        if (onDjango || channels.isNotEmpty()) return
        val client = SupabaseClientProvider.get(context) ?: return

        scope.launch {
            if (SupabaseAuth(context).resolveSignedInUserId() == null) return@launch
            try {
                val coalescer = PullCoalescer(scope) {
                    try {
                        val report = MemorySync.pull(context, SupabaseMemoryBackend(client))
                        MidnightEvents.memoryRealtimePullSucceeded(
                            report.inserted, report.updated, report.skippedLocalNewer, report.tombstoned,
                        )
                    } catch (e: Exception) {
                        MidnightEvents.memoryRealtimePullFailed(e)
                    }
                }
                val opened = TABLES.map { table ->
                    val realtimeChannel = client.realtime.channel("memory-changes-$table")
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
                MidnightEvents.memoryRealtimeSubscribeFailed(e)
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
                    MidnightEvents.memoryRealtimeSubscribeFailed(e)
                }
            }
        }
    }
}
