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
 * Live-sync's ledger-config-aspect slice: an instant pull triggered by Supabase Realtime's
 * `postgres_changes` feed on all three ledger config tables, mirroring [MemoryRealtime]'s own
 * shape and every rule its class doc states - the realtime event is a TRIGGER, not a data source;
 * every actual merge still runs through [LedgerConfigSync.pull]. `MidnightApplication.onCreate`
 * calls [bind] once for the process.
 *
 * One [PullCoalescer] shared across all three tables' change feeds, same reasoning
 * [MemoryRealtime]'s own class doc gives for its three.
 *
 * Subscribes on app foreground, unsubscribes on app background, fails silently always - identical
 * posture to [MemoryRealtime].
 */
object LedgerConfigRealtime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var channels: List<RealtimeChannel> = emptyList()
    @Volatile private var bound = false

    private val TABLES = listOf("categories", "category_rules", "budget_targets")

    /** Idempotent - safe to call more than once, matching [MemoryRealtime.bind]'s own contract. */
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

    // Same cold-start-safe shape as MemoryRealtime.subscribe's own doc comment: the auth check
    // happens inside `scope.launch` via SupabaseAuth.resolveSignedInUserId (suspend), never
    // synchronously before it.
    //
    // The transport switch (django-engine Phase 5), same guard [BodyRealtime.subscribe] already
    // carries and for the same reason: Realtime is Supabase's mechanism, so an aspect moved to the
    // Django engine must not also hold a `postgres_changes` socket open against a project it no
    // longer reads. Folded into ONE guard with the pre-existing `channels.isNotEmpty()` check -
    // same shape [BodyRealtime.subscribe]'s own doc comment states, and for the same reason: it
    // keeps this function inside detekt's two-return ceiling without a suppression.
    private fun subscribe(context: Context) {
        val onDjango =
            EngineTransport(context).transportFor(EngineBackends.ASPECT_LEDGER) == Transport.DJANGO
        if (onDjango || channels.isNotEmpty()) return
        val client = SupabaseClientProvider.get(context) ?: return

        scope.launch {
            if (SupabaseAuth(context).resolveSignedInUserId() == null) return@launch
            try {
                val coalescer = PullCoalescer(scope) {
                    try {
                        val report = LedgerConfigSync.pull(context, SupabaseLedgerConfigBackend(client))
                        MidnightEvents.ledgerConfigRealtimePullSucceeded(
                            report.inserted, report.updated, report.skippedLocalNewer, report.tombstoned,
                        )
                    } catch (e: Exception) {
                        MidnightEvents.ledgerConfigRealtimePullFailed(e)
                    }
                }
                val opened = TABLES.map { table ->
                    val realtimeChannel = client.realtime.channel("ledger-config-changes-$table")
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
                MidnightEvents.ledgerConfigRealtimeSubscribeFailed(e)
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
                    MidnightEvents.ledgerConfigRealtimeSubscribeFailed(e)
                }
            }
        }
    }
}
