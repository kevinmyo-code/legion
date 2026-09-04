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
 * Live-sync's fleet-aspect slice: an instant pull triggered by Supabase Realtime's
 * `postgres_changes` feed on the ten tables [FleetSync.pull] merges, mirroring
 * [LedgerConfigRealtime]'s own shape and every rule its class doc states - the realtime event is a
 * TRIGGER, not a data source; every actual merge still runs through [FleetSync.pull].
 * `MidnightApplication.onCreate` calls [bind] once for the process.
 *
 * **`obd_samples` and `chassis_quirks` are deliberately NOT in [TABLES]**, same "in scope" boundary
 * [FleetSync]'s own class doc draws. `chassis_quirks` was never blocked by the obdMac gap this ticket
 * exists to fix - it carries no vehicle reference at all - and stays on [FleetReconcile]'s existing
 * refill. `obd_samples` writes every ~30s while a car is driving (this device's OWN dongle, the
 * common case), so a realtime trigger on it would fire a full [FleetSync.pull] on essentially every
 * tick - the coalescer would absorb the burst, but there is no reason to pay for it: the windowed
 * pull ([FleetSync.pullObdSamples]) already runs on every foreground [FleetSync.maybeAutoPull], which
 * is the cadence Kevin's own OBD-volume ruling asked for.
 *
 * One [PullCoalescer] shared across all ten tables' change feeds, same reasoning
 * [LedgerConfigRealtime]'s own class doc gives for its three.
 *
 * Subscribes on app foreground, unsubscribes on app background, fails silently always - identical
 * posture to [LedgerConfigRealtime]/[MemoryRealtime].
 */
object FleetRealtime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var channels: List<RealtimeChannel> = emptyList()
    @Volatile private var bound = false

    private val TABLES = listOf(
        "vehicles",
        "service_history",
        "drives",
        "code_events",
        "code_clear_events",
        "oil_analyses",
        "vehicle_specs",
        "build_entries",
        "drive_reassignments",
        "maintenance_schedules",
    )

    /** Idempotent - safe to call more than once, matching [LedgerConfigRealtime.bind]'s own contract. */
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

    // Same cold-start-safe shape as LedgerConfigRealtime.subscribe's own doc comment: the auth check
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
                        val report = FleetSync.pull(context, SupabaseFleetBackend(client))
                        val total = report.total
                        MidnightEvents.fleetRealtimePullSucceeded(total.inserted, total.updated, total.tombstoned)
                    } catch (e: Exception) {
                        MidnightEvents.fleetRealtimePullFailed(e)
                    }
                }
                val opened = TABLES.map { table ->
                    val realtimeChannel = client.realtime.channel("fleet-changes-$table")
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
                MidnightEvents.fleetRealtimeSubscribeFailed(e)
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
                    MidnightEvents.fleetRealtimeSubscribeFailed(e)
                }
            }
        }
    }
}
