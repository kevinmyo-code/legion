package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Install-scoped high-water mark for [LedgerTransactionsSync.pull] - one table, so one key, same
 * shape as [LedgerConfigPullCursor] narrowed to a single entry. `created_at` is the clock (see
 * this file's own class doc for why there is no `updated_at` to prefer).
 */
internal object LedgerTransactionsPullCursor {
    private const val PREFS = "ledger_transactions_pull_cursor"
    private const val KEY = "ledger_transactions"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastPulledAtMs(context: Context): Long = prefs(context).getLong(KEY, 0L)

    fun advance(context: Context, atMs: Long) {
        prefs(context).edit().putLong(KEY, atMs).apply()
    }
}

/**
 * Live-sync's ledger-TRANSACTIONS pull (the map's own ticket "give LEGION's pantry and ledger
 * transactions a pull" - see `.scratch/live-sync/map.md`). Follows
 * [LedgerConfigSync]/[LedgerConfigBackfill]/[LedgerConfigRealtime]'s shape, the newest template with
 * every fix folded in, per that ticket's own brief - narrowed for a table with a genuinely different
 * shape from every sibling this map has pulled so far.
 *
 * **`ledger_transactions` is append-only with no `updated_at` and no `deleted_at` at all** - traced
 * against `supabase/migrations/20260825000300_aspect_ledger_pantry.sql` and
 * [RemoteLedgerTransaction]'s own doc comment: the `forbid_mutation_of_facts` trigger blocks every
 * UPDATE unconditionally, and blocks DELETE except on an `UNRECONCILED` row a rule-7 supersession
 * removes (`ledger_transactions_provisional_idx`'s own comment: "Rule 7 supersession scans exactly
 * this"). A superseded row is not soft-deleted, it is physically GONE - there is no tombstone for
 * this pull to observe, ever, for any row. **So this merge has only one real branch: insert what is
 * missing.** There is no LWW comparison (nothing to compare - a row that exists never changes), no
 * tombstone branch (nothing to receive one), and therefore no way for this pull to notice a
 * supersession that already happened server-side. That is a genuine, narrow gap: a device that
 * pulled a since-superseded `UNRECONCILED` row before the reconciled statement landed keeps showing
 * it until something else removes it locally - out of this ticket's scope (no local write path for
 * ledger transactions is being added here at all, per the ticket's own constraint), named rather
 * than silently accepted.
 *
 * **Identity, in the absence of a local `serverId` column.** [LedgerTransaction] carries no
 * `serverId` field and none is being added here (no schema change was needed - see this object's own
 * `pull` doc comment for the two-way match this uses instead). [LedgerTransactionDao.allSyncIds] is
 * the existence check both directions already share: [LedgerReconcile]'s upload sets
 * `origin_guid = txn.syncId` for a row this device minted locally and later migrated up, so a
 * migrated row is already "present" the moment its `syncId` appears in that set. A row this pull
 * inserts for the first time - one this device has NEVER seen, whether server-native or migrated
 * from another device - gets `syncId = remote.serverId` (mirroring
 * [com.kevin.legion.pantry.PantryController.commitReceiptRemote]'s own "the server's own id becomes
 * the local syncId" convention for a freshly-committed row), so a re-fetch of the same remote row on
 * a later pull is recognised by `remote.serverId` being in that same set - no second column needed.
 *
 * **Provenance survives exactly, or the row is refused, never guessed** (CLAUDE.md section 4 -
 * ledger is gate-governed). `USER` maps to [IngestMethod.UNRECONCILED], the same mapping
 * [com.kevin.legion.engine.ledger.LedgerRecordBridge.ingestMethodFor] already applies for the
 * identical reason (a hand-authored row has no document behind it - the most literal case of "no
 * anchor to check against" [IngestMethod.UNRECONCILED]'s own doc comment already covers). Anything
 * else unrecognised is refused outright and reported in [PullReport.unrecognizedProvenance] -
 * **never defaulted, never silently dropped** - this is CLAUDE.md's "a row arriving without a
 * recognised tag is a hard failure, not a row to guess about" applied literally.
 */
object LedgerTransactionsSync {

    data class PullReport(
        val inserted: Int,
        val alreadyPresent: Int,
        val unrecognizedProvenance: List<String>,
    )

    private fun mapProvenance(raw: String): IngestMethod? = when (raw) {
        "DETERMINISTIC" -> IngestMethod.DETERMINISTIC
        "LLM_RECONCILED" -> IngestMethod.LLM_RECONCILED
        "UNRECONCILED" -> IngestMethod.UNRECONCILED
        // A hand-authored server row, same mapping LedgerRecordBridge.ingestMethodFor already
        // applies for RecordProvenance.USER - no document backs it, so it is the most literal case
        // of "no anchor to check against" IngestMethod.UNRECONCILED already names.
        "USER" -> IngestMethod.UNRECONCILED
        else -> null
    }

    /**
     * Insert-if-absent only - see this file's own class doc for why an append-only, tombstone-free
     * table has no other branch to run. [known] is read once, up front
     * ([LedgerTransactionDao.allSyncIds] - the same existence check [LedgerReconcile]'s upload
     * direction already shares), never re-queried per row.
     */
    suspend fun pull(context: Context, backend: LedgerBackend): PullReport {
        val db = CarDatabase.getDatabase(context)
        val sinceMs = LedgerTransactionsPullCursor.lastPulledAtMs(context)
        val remote = backend.fetchChangedTransactionsSince(sinceMs).getOrThrow()
        val known = db.ledgerTransactionDao().allSyncIds().toSet()

        var inserted = 0
        var alreadyPresent = 0
        val unrecognized = mutableListOf<String>()
        val toInsert = mutableListOf<LedgerTransaction>()

        for (r in remote) {
            val seenByServerId = r.serverId in known
            val seenByOriginGuid = r.originGuid != null && r.originGuid in known
            if (seenByServerId || seenByOriginGuid) {
                alreadyPresent++
                continue
            }

            val ingestMethod = mapProvenance(r.provenance)
            if (ingestMethod == null) {
                unrecognized.add("${r.description} (${r.serverId}): unrecognised provenance '${r.provenance}' - not inserted")
                continue
            }

            toInsert.add(
                LedgerTransaction(
                    // "synced" rather than a fabricated filename - CLAUDE.md section 4 rule 5 never
                    // invents a fact the source didn't state, and the server carries no filename for
                    // a row this pull downloads. Matches LedgerController's own "voice" convention
                    // for a row with no document behind it (LedgerController.kt:249).
                    sourceFile = "synced",
                    // Reverses LedgerReconcile's own upload mapping exactly (accountNickname =
                    // txn.accountId) so a migrated-then-pulled-back row round-trips to the same
                    // accountId string it left with.
                    accountId = r.accountNickname,
                    currency = LedgerCurrency.valueOf(r.currency),
                    txnDate = r.txnDateEpochMs,
                    description = r.description,
                    amountCents = r.amountCents,
                    balanceCents = r.balanceCents,
                    lineRef = r.lineRef,
                    ingestMethod = ingestMethod,
                    syncId = r.serverId,
                    sourceFileId = null,
                    category = r.category,
                    categoryPending = r.categoryPending,
                    pendingLoggedAt = r.pendingLoggedAtMs,
                ),
            )
            inserted++
        }

        if (toInsert.isNotEmpty()) {
            db.ledgerTransactionDao().insertAll(toInsert)
        }

        remote.maxOfOrNull { it.createdAtMs }?.let { LedgerTransactionsPullCursor.advance(context, it) }
        return PullReport(inserted, alreadyPresent, unrecognized)
    }

    private val autoPullScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var lastAutoPullAt = 0L

    private const val AUTO_PULL_MIN_INTERVAL_MS = 5 * 60 * 1000L
    private const val AUTO_PULL_RETRY_DELAY_MS = 1_000L

    /** Thin delegation to [SupabaseAuth.resolveSignedInUserId], same shape as
     * [LedgerConfigSync.resolveUserIdForAutoPull] - `internal` so a test can drive it directly. */
    internal suspend fun resolveUserIdForAutoPull(
        auth: SupabaseAuth,
        retryDelayMs: Long = AUTO_PULL_RETRY_DELAY_MS,
    ): String? = auth.resolveSignedInUserId(retryDelayMs)

    /** `MainActivity.onResume`'s hook. No-ops silently when Supabase is not configured or nobody is
     * signed in. No outbox to drain first - this ticket adds no live write path for ledger
     * transactions (constraint stated in the ticket brief itself; [LedgerReconcile]'s own class doc
     * explains why CLAUDE.md section 4 blocks one), so there is nothing local this pull needs to
     * wait on. */
    fun maybeAutoPull(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAutoPullAt < AUTO_PULL_MIN_INTERVAL_MS) return
        val app = context.applicationContext
        val client = SupabaseClientProvider.get(app) ?: return
        lastAutoPullAt = now
        autoPullScope.launch {
            try {
                val userId = resolveUserIdForAutoPull(SupabaseAuth(app))
                if (userId == null) return@launch
                val report = pull(app, SupabaseLedgerBackend(client))
                MidnightEvents.ledgerTransactionsAutoPullSucceeded(
                    report.inserted, report.alreadyPresent, report.unrecognizedProvenance.size,
                )
            } catch (e: Exception) {
                MidnightEvents.ledgerTransactionsAutoPullFailed(e)
            }
        }
    }
}
