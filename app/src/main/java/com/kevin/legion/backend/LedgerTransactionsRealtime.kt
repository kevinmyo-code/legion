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
 * Live-sync's ledger-TRANSACTIONS-aspect slice: an instant pull triggered by Supabase Realtime's
 * `postgres_changes` feed on `ledger_transactions`, mirroring [LedgerConfigRealtime]/[FleetRealtime]'s
 * own shape and every rule their class docs state - the realtime event is a TRIGGER, not a data
 * source; every actual merge still runs through [LedgerTransactionsSync.pull].
 * `MidnightApplication.onCreate` calls [bind] once for the process.
 *
 * **The realtime `postgres_changes` feed on this table only ever reports INSERT** - the same
 * `forbid_mutation_of_facts` trigger [LedgerTransactionsSync]'s own class doc traces blocks every
 * UPDATE unconditionally, so an UPDATE event can never fire here. A DELETE event (a rule-7
 * supersession) DOES still reach this channel and still triggers a pull, exactly as any change on
 * this table should - but [LedgerTransactionsSync.pull] has no branch that acts on it (see that
 * object's own class doc for the named, not-fixed-here gap), so a supersession's realtime trigger
 * currently causes a harmless extra pull rather than a correction. Named, not silently absorbed.
 *
 * Subscribes on app foreground, unsubscribes on app background, fails silently always - identical
 * posture to every sibling `*Realtime` object in this package.
 */
object LedgerTransactionsRealtime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var channel: RealtimeChannel? = null
    @Volatile private var bound = false

    private const val TABLE = "ledger_transactions"

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

    // Same cold-start-safe shape as LedgerConfigRealtime.subscribe's own doc comment: the auth
    // check happens inside `scope.launch` via SupabaseAuth.resolveSignedInUserId (suspend), never
    // synchronously before it.
    private fun subscribe(context: Context) {
        val client = SupabaseClientProvider.get(context) ?: return
        if (channel != null) return

        scope.launch {
            if (SupabaseAuth(context).resolveSignedInUserId() == null) return@launch
            try {
                val coalescer = PullCoalescer(scope) {
                    try {
                        val report = LedgerTransactionsSync.pull(context, SupabaseLedgerBackend(client))
                        MidnightEvents.ledgerTransactionsRealtimePullSucceeded(report.inserted, report.alreadyPresent)
                    } catch (e: Exception) {
                        MidnightEvents.ledgerTransactionsRealtimePullFailed(e)
                    }
                }
                val realtimeChannel = client.realtime.channel("ledger-transactions-changes")
                realtimeChannel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    this.table = TABLE
                }.onEach {
                    coalescer.trigger()
                }.launchIn(scope)
                realtimeChannel.subscribe()
                channel = realtimeChannel
            } catch (e: Exception) {
                channel = null
                MidnightEvents.ledgerTransactionsRealtimeSubscribeFailed(e)
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
                MidnightEvents.ledgerTransactionsRealtimeSubscribeFailed(e)
            }
        }
    }
}
