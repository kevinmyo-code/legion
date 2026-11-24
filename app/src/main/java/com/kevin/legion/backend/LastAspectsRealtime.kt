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
 * Live-sync's last-aspect-slice: an instant pull triggered by Supabase Realtime's
 * `postgres_changes` feed on `goals`, mirroring [LedgerConfigRealtime]'s own shape and every rule
 * its class doc states - the realtime event is a TRIGGER, not a data source; every actual merge
 * still runs through [LastAspectsSync.pull]. `MidnightApplication.onCreate` calls [bind] once for
 * the process.
 *
 * **NARROWED by the lists redesign slice 1a (2026-11-24)** from four channels to one: the
 * `grocery_staples`/`item_lists`/`list_items` feeds cost Supabase Realtime egress to drive a pull
 * of tables nothing on the phone read any more. See [LastAspectsBackend]'s own class doc.
 *
 * [TABLES] stays a list rather than collapsing into a single channel: the shape is what the next
 * table added here needs, and [PullCoalescer] already folds every feed into one pull.
 */
object LastAspectsRealtime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var channels: List<RealtimeChannel> = emptyList()
    @Volatile private var bound = false

    private val TABLES = listOf("goals")

    /** Idempotent - safe to call more than once, matching [LedgerConfigRealtime.bind]'s own
     * contract. */
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

    private fun subscribe(context: Context) {
        val client = SupabaseClientProvider.get(context) ?: return
        if (channels.isNotEmpty()) return

        scope.launch {
            if (SupabaseAuth(context).resolveSignedInUserId() == null) return@launch
            try {
                val coalescer = PullCoalescer(scope) {
                    try {
                        val report = LastAspectsSync.pull(context, SupabaseLastAspectsBackend(client))
                        MidnightEvents.lastAspectsRealtimePullSucceeded(report.inserted, report.updated, report.skippedLocalNewer, report.tombstoned)
                    } catch (e: Exception) {
                        MidnightEvents.lastAspectsRealtimePullFailed(e)
                    }
                }
                val opened = TABLES.map { table ->
                    val realtimeChannel = client.realtime.channel("last-aspects-changes-$table")
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
                MidnightEvents.lastAspectsRealtimeSubscribeFailed(e)
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
                    MidnightEvents.lastAspectsRealtimeSubscribeFailed(e)
                }
            }
        }
    }
}
