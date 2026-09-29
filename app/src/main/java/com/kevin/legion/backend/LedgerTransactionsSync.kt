package com.kevin.legion.backend

import android.content.Context
import androidx.room.withTransaction
import com.kevin.legion.MidnightEvents
import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.backend.engine.EngineTransport
import com.kevin.legion.backend.engine.Transport
import com.kevin.legion.data.local.CarDatabase
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
 * it until something else removes it locally.
 *
 * **On the Django engine that gap is closed by [mirror], not by [pull]** (backend-etl ticket 14): the
 * engine's full list is the set, so a server-origin row it no longer lists is deleted here, and the
 * foreground pull on that transport runs [mirror] instead. [pull] survives unchanged in behaviour
 * for the Supabase transport and its Realtime trigger, where the list cannot be vouched complete
 * and a delete-by-absence would be unsafe.
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

    /** What one [mirror] pass did. [deletionsSkipped] true means nothing was removed because the
     * engine's list was not known to be the whole set. [rulesApplied] is how many rows the phone's
     * own categorisation rules then filled ([com.kevin.legion.ledger.LedgerController.applyCategoryRules]). */
    data class MirrorReport(
        val inserted: Int,
        val categoriesFilled: Int,
        val deleted: Int,
        val deletionsSkipped: Boolean,
        val alreadyPresent: Int,
        val unrecognizedProvenance: List<String>,
        val rulesApplied: Int,
    )

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

            val ingestMethod = ledgerIngestMethodFor(r.provenance)
            if (ingestMethod == null) {
                unrecognized.add("${r.description} (${r.serverId}): unrecognised provenance '${r.provenance}' - not inserted")
                continue
            }

            // Shared with [mirror] (LedgerTransactionsMirror.kt), so the two transports cannot map
            // one server row two ways.
            toInsert.add(r.toLocalRow(ingestMethod))
            inserted++
        }

        if (toInsert.isNotEmpty()) {
            db.ledgerTransactionDao().insertAll(toInsert)
        }

        remote.maxOfOrNull { it.createdAtMs }?.let { LedgerTransactionsPullCursor.advance(context, it) }
        return PullReport(inserted, alreadyPresent, unrecognized)
    }

    /**
     * The Django engine's pull, and the one that closes this file's named gap: **Room's
     * server-origin rows are made to match the engine's full list** - inserted, category-filled,
     * and deleted where the engine no longer lists them (a rule-7 supersession, which is now the
     * daily path: each day's card export is replaced by the next and eventually by the statement).
     * [planLedgerMirror] holds every rule, including the two that make a delete safe (only
     * [SYNCED_SOURCE_FILE] rows, and only off a list known to be complete).
     *
     * **The fetch happens before any write**, so an unreachable engine throws out of here with Room
     * exactly as it was. The caller records that ([LedgerMirrorStatus]) so the Money surfaces can
     * say so.
     *
     * **Then the phone's own categorisation rules run** over whatever is still uncategorised. Rows
     * the engine stored before it learned to categorise at insert can never be categorised there
     * (the gate's trigger refuses the UPDATE; `server/ingest/category_rules.py`), so without this
     * they would stay uncategorised on every surface and outside every budget line. It is the same
     * [com.kevin.legion.ledger.LedgerController.applyCategoryRules] the ledger screen's button runs,
     * over the same `category_rules` the engine holds (pulled by [LedgerConfigSync]), not a second
     * implementation. [categorize] is the seam a test replaces.
     *
     * No cursor: the whole list every time, which is the contract. At household scale that is one
     * to three pages.
     */
    suspend fun mirror(
        context: Context,
        backend: LedgerBackend,
        categorize: suspend (Context) -> Int = { com.kevin.legion.ledger.LedgerController.applyCategoryRules(it) },
    ): MirrorReport {
        val set = backend.fetchTransactionSet().getOrThrow()
        val database = CarDatabase.getDatabase(context)
        val dao = database.ledgerTransactionDao()
        var deleted = 0
        val plan = database.withTransaction {
            val plan = planLedgerMirror(dao.getAll(), set.rows, set.complete)
            if (plan.toInsert.isNotEmpty()) dao.insertAll(plan.toInsert)
            for (fill in plan.categoryFills) dao.updateCategoryById(fill.localId, fill.category, fill.categoryPending)
            for (chunk in plan.toDeleteSyncIds.chunked(DELETE_CHUNK)) deleted += dao.deleteSyncedBySyncIds(chunk)
            plan
        }
        val rulesApplied = categorize(context)
        return MirrorReport(
            inserted = plan.toInsert.size,
            categoriesFilled = plan.categoryFills.size,
            deleted = deleted,
            deletionsSkipped = plan.deletionsSkipped,
            alreadyPresent = plan.alreadyPresent,
            unrecognizedProvenance = plan.unrecognizedProvenance,
            rulesApplied = rulesApplied,
        )
    }

    /** Below SQLite's bound-variable limit on every API level this app runs on. */
    private const val DELETE_CHUNK = 500

    /**
     * [mirror] with its outcome recorded for the Money surfaces: success stamps
     * [LedgerMirrorStatus.recordSuccess]; a failure stamps [LedgerMirrorStatus.recordFailure] and
     * rethrows, so the caller's own logging still sees it. Shared by the foreground pull and
     * [com.kevin.legion.backend.engine.EngineSyncNow], so the "couldn't reach the server" line means
     * the same thing whichever one ran last.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun runMirror(context: Context, backend: LedgerBackend): MirrorReport {
        val report = try {
            mirror(context, backend)
        } catch (e: Exception) {
            LedgerMirrorStatus.recordFailure(context)
            throw e
        }
        val changedRoom = report.inserted + report.categoriesFilled + report.deleted + report.rulesApplied > 0
        LedgerMirrorStatus.recordSuccess(context, changedRoom)
        MidnightEvents.ledgerTransactionsMirrorSucceeded(
            report.inserted, report.categoriesFilled, report.deleted, report.deletionsSkipped,
            report.unrecognizedProvenance.size, report.rulesApplied,
        )
        return report
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
        // Transport switch (django-engine Phase 5). The gate is EngineBackends.isConfiguredFor
        // rather than a raw SupabaseClientProvider read, so a device whose `ledger` row is flipped
        // to Django is not turned away for having no Supabase project - same substitution
        // BodySync.maybeAutoPull's own doc comment records for `body`.
        val backends = EngineBackends(app)
        if (!backends.isConfiguredFor(EngineBackends.ASPECT_LEDGER)) return
        val onDjango = EngineTransport(app).transportFor(EngineBackends.ASPECT_LEDGER) == Transport.DJANGO
        lastAutoPullAt = now
        autoPullScope.launch {
            try {
                // The Supabase session resolve is SKIPPED on the Django branch - see
                // BodySync.maybeAutoPull's own doc comment for why.
                if (!onDjango && resolveUserIdForAutoPull(SupabaseAuth(app)) == null) return@launch
                val backend = backends.ledgerBackend() ?: return@launch
                if (onDjango) {
                    runMirror(app, backend)
                } else {
                    val report = pull(app, backend)
                    MidnightEvents.ledgerTransactionsAutoPullSucceeded(
                        report.inserted, report.alreadyPresent, report.unrecognizedProvenance.size,
                    )
                }
            } catch (e: Exception) {
                MidnightEvents.ledgerTransactionsAutoPullFailed(e)
            }
        }
    }
}
