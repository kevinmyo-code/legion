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
 * Live-sync's pantry-RECEIPTS-aspect slice: an instant pull triggered by Supabase Realtime's
 * `postgres_changes` feed on `receipts`, mirroring [LedgerTransactionsRealtime]'s own shape (and
 * that object's own class doc for why a DELETE event here still fires a harmless extra pull rather
 * than a correction - the identical `forbid_mutation_of_facts` trigger governs `receipts` too).
 * `receipt_line_items` is deliberately NOT its own channel - a line never arrives independently of
 * its header (see [PantryReceiptsSync]'s own class doc: lines are only ever inserted alongside a
 * brand-new receipt), so a change on `receipts` alone is a sufficient trigger.
 * `MidnightApplication.onCreate` calls [bind] once for the process.
 *
 * Subscribes on app foreground, unsubscribes on app background, fails silently always - identical
 * posture to every sibling `*Realtime` object in this package.
 */
object PantryReceiptsRealtime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var channel: RealtimeChannel? = null
    @Volatile private var bound = false

    private const val TABLE = "receipts"

    /** Idempotent - safe to call more than once, matching [LedgerTransactionsRealtime.bind]'s own
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

    // Same cold-start-safe shape as LedgerTransactionsRealtime.subscribe's own doc comment: the
    // auth check happens inside `scope.launch` via SupabaseAuth.resolveSignedInUserId (suspend),
    // never synchronously before it.
    private fun subscribe(context: Context) {
        val client = SupabaseClientProvider.get(context) ?: return
        if (channel != null) return

        scope.launch {
            if (SupabaseAuth(context).resolveSignedInUserId() == null) return@launch
            try {
                val coalescer = PullCoalescer(scope) {
                    try {
                        val report = PantryReceiptsSync.pull(context, SupabasePantryBackend(client))
                        MidnightEvents.pantryReceiptsRealtimePullSucceeded(report.inserted, report.alreadyPresent)
                    } catch (e: Exception) {
                        MidnightEvents.pantryReceiptsRealtimePullFailed(e)
                    }
                }
                val realtimeChannel = client.realtime.channel("pantry-receipts-changes")
                realtimeChannel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    this.table = TABLE
                }.onEach {
                    coalescer.trigger()
                }.launchIn(scope)
                realtimeChannel.subscribe()
                channel = realtimeChannel
            } catch (e: Exception) {
                channel = null
                MidnightEvents.pantryReceiptsRealtimeSubscribeFailed(e)
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
                MidnightEvents.pantryReceiptsRealtimeSubscribeFailed(e)
            }
        }
    }
}
