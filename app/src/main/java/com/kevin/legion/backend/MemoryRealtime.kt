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
        val client = SupabaseClientProvider.get(context) ?: return
        if (channels.isNotEmpty()) return

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
