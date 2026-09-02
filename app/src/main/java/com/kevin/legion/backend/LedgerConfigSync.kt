package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.data.local.BudgetTarget
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Category
import com.kevin.legion.data.local.CategoryRule
import com.kevin.legion.data.local.LedgerCurrency
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Per-table install-scoped high-water marks for [LedgerConfigSync.pull] - same shape as
 * [MemoryPullCursor]/[BodyPullCursor], one watermark per table rather than one shared minimum, so
 * a quiet table never holds back a busy one. See [BodyPullCursor]'s own class doc for the full
 * reasoning; unchanged here.
 */
internal object LedgerConfigPullCursor {
    private const val PREFS = "ledger_config_pull_cursor"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastPulledAtMs(context: Context, table: String): Long = prefs(context).getLong(table, 0L)

    fun advance(context: Context, table: String, atMs: Long) {
        prefs(context).edit().putLong(table, atMs).apply()
    }
}

/**
 * The generic merge [LedgerConfigSync.pull] performs for one table - a deliberate copy of
 * [MemoryMerge.merge]/[BodyMerge.merge], not a shared reference to either. Copied rather than
 * imported so this aspect never depends on another one's internals (the same "do not touch body/
 * memory sync" boundary every aspect built off this template respects) - if the two ever drift,
 * that is a decision for whoever notices, not an accident of one file quietly changing under the
 * other. **The five rules are [MemoryMerge.merge]'s own, verbatim** - see that object's own class
 * doc for the full reasoning behind each one.
 */
internal object LedgerConfigMerge {
    data class MergeReport(
        val inserted: Int = 0,
        val updated: Int = 0,
        val skippedLocalNewer: Int = 0,
        val tombstoned: Int = 0,
        val skippedTombstoneNoLocalMatch: Int = 0,
    ) {
        operator fun plus(other: MergeReport) = MergeReport(
            inserted + other.inserted,
            updated + other.updated,
            skippedLocalNewer + other.skippedLocalNewer,
            tombstoned + other.tombstoned,
            skippedTombstoneNoLocalMatch + other.skippedTombstoneNoLocalMatch,
        )
    }

    suspend fun <R, L> merge(
        remoteRows: List<R>,
        localRows: List<L>,
        remoteGuid: (R) -> String,
        localGuid: (L) -> String,
        remoteDeleted: (R) -> Boolean,
        remoteUpdatedAtMs: (R) -> Long,
        localUpdatedAtMs: (L) -> Long,
        localDeleted: (L) -> Boolean,
        toInserted: (R) -> L,
        toMerged: (R, L) -> L,
        withDeletedFlag: (L, Long) -> L,
        insert: suspend (L) -> Unit,
        update: suspend (L) -> Unit,
    ): MergeReport {
        val localByGuid = localRows.associateBy { localGuid(it) }
        var inserted = 0
        var updated = 0
        var skippedLocalNewer = 0
        var tombstoned = 0
        var skippedTombstoneNoLocalMatch = 0

        for (remote in remoteRows) {
            val local = localByGuid[remoteGuid(remote)]

            if (local == null && remoteDeleted(remote)) {
                skippedTombstoneNoLocalMatch++
                continue
            }

            if (local == null) {
                insert(toInserted(remote))
                inserted++
                continue
            }

            if (remoteDeleted(remote)) {
                if (!localDeleted(local)) {
                    update(withDeletedFlag(local, remoteUpdatedAtMs(remote)))
                    tombstoned++
                }
                continue
            }

            if (remoteUpdatedAtMs(remote) >= localUpdatedAtMs(local)) {
                val merged = toMerged(remote, local)
                if (merged != local) {
                    update(merged)
                    updated++
                }
            } else {
                skippedLocalNewer++
            }
        }
        // Rule 6, by omission: nothing above ever iterates localRows looking for a row to delete.

        return MergeReport(inserted, updated, skippedLocalNewer, tombstoned, skippedTombstoneNoLocalMatch)
    }
}

/**
 * The ledger CONFIG aspect's live pull - three independent sub-pulls, one per table, each
 * following [LedgerConfigMerge.merge]'s rules exactly. Mirrors [MemorySync]'s own shape.
 * `ledger_transactions` is explicitly out of scope - see [LedgerConfigBackend]'s own class doc.
 */
object LedgerConfigSync {
    data class PullReport(
        val inserted: Int,
        val updated: Int,
        val skippedLocalNewer: Int,
        val tombstoned: Int,
        val skippedTombstoneNoLocalMatch: Int,
    )

    private fun LedgerConfigMerge.MergeReport.toPullReport() =
        PullReport(inserted, updated, skippedLocalNewer, tombstoned, skippedTombstoneNoLocalMatch)

    private operator fun PullReport.plus(other: PullReport) = PullReport(
        inserted + other.inserted,
        updated + other.updated,
        skippedLocalNewer + other.skippedLocalNewer,
        tombstoned + other.tombstoned,
        skippedTombstoneNoLocalMatch + other.skippedTombstoneNoLocalMatch,
    )

    /** Pulls and merges all three ledger config tables. Each table's watermark advances
     * independently and only after that table's own batch is fully merged - a failure partway
     * through throws straight out, same posture as [MemorySync.pull]. */
    suspend fun pull(context: Context, backend: LedgerConfigBackend): PullReport {
        var total = PullReport(0, 0, 0, 0, 0)
        total += pullCategories(context, backend)
        total += pullCategoryRules(context, backend)
        total += pullBudgetTargets(context, backend)
        return total
    }

    private const val T_CATEGORIES = "categories"
    private const val T_CATEGORY_RULES = "category_rules"
    private const val T_BUDGET_TARGETS = "budget_targets"

    private suspend fun pullCategories(context: Context, backend: LedgerConfigBackend): PullReport {
        val db = CarDatabase.getDatabase(context)
        val sinceMs = LedgerConfigPullCursor.lastPulledAtMs(context, T_CATEGORIES)
        val remote = backend.fetchChangedCategoriesSince(sinceMs).getOrThrow()
        val local = db.categoryDao().getAllIncludingDeleted()
        val report = LedgerConfigMerge.merge(
            remoteRows = remote,
            localRows = local,
            remoteGuid = { it.originGuid },
            localGuid = { it.guid },
            remoteDeleted = { it.deleted },
            remoteUpdatedAtMs = { it.updatedAtMs },
            localUpdatedAtMs = { it.updatedAtMs },
            localDeleted = { it.deleted },
            toInserted = { r ->
                Category(
                    id = 0,
                    name = r.name,
                    isFoodCategory = r.isFoodCategory,
                    guid = r.originGuid,
                    serverId = r.serverId,
                    updatedAtMs = r.updatedAtMs,
                    deleted = r.deleted,
                )
            },
            toMerged = { r, existing ->
                existing.copy(
                    name = r.name,
                    isFoodCategory = r.isFoodCategory,
                    serverId = r.serverId,
                    updatedAtMs = r.updatedAtMs,
                    deleted = r.deleted,
                )
            },
            withDeletedFlag = { existing, atMs -> existing.copy(deleted = true, updatedAtMs = atMs) },
            insert = { db.categoryDao().insert(it) },
            update = { db.categoryDao().update(it) },
        )
        remote.maxOfOrNull { it.updatedAtMs }?.let { LedgerConfigPullCursor.advance(context, T_CATEGORIES, it) }
        return report.toPullReport()
    }

    private suspend fun pullCategoryRules(context: Context, backend: LedgerConfigBackend): PullReport {
        val db = CarDatabase.getDatabase(context)
        val sinceMs = LedgerConfigPullCursor.lastPulledAtMs(context, T_CATEGORY_RULES)
        val remote = backend.fetchChangedCategoryRulesSince(sinceMs).getOrThrow()
        val local = db.categoryRuleDao().getAllIncludingDeleted()
        val report = LedgerConfigMerge.merge(
            remoteRows = remote,
            localRows = local,
            remoteGuid = { it.originGuid },
            localGuid = { it.guid },
            remoteDeleted = { it.deleted },
            remoteUpdatedAtMs = { it.updatedAtMs },
            localUpdatedAtMs = { it.updatedAtMs },
            localDeleted = { it.deleted },
            toInserted = { r ->
                CategoryRule(
                    id = 0,
                    category = r.category,
                    substring = r.substring,
                    createdAt = r.createdAtMs,
                    guid = r.originGuid,
                    serverId = r.serverId,
                    updatedAtMs = r.updatedAtMs,
                    deleted = r.deleted,
                )
            },
            toMerged = { r, existing ->
                existing.copy(
                    category = r.category,
                    substring = r.substring,
                    createdAt = r.createdAtMs,
                    serverId = r.serverId,
                    updatedAtMs = r.updatedAtMs,
                    deleted = r.deleted,
                )
            },
            withDeletedFlag = { existing, atMs -> existing.copy(deleted = true, updatedAtMs = atMs) },
            insert = { db.categoryRuleDao().insert(it) },
            update = { db.categoryRuleDao().update(it) },
        )
        remote.maxOfOrNull { it.updatedAtMs }?.let { LedgerConfigPullCursor.advance(context, T_CATEGORY_RULES, it) }
        return report.toPullReport()
    }

    private suspend fun pullBudgetTargets(context: Context, backend: LedgerConfigBackend): PullReport {
        val db = CarDatabase.getDatabase(context)
        val sinceMs = LedgerConfigPullCursor.lastPulledAtMs(context, T_BUDGET_TARGETS)
        val remote = backend.fetchChangedBudgetTargetsSince(sinceMs).getOrThrow()
        val local = db.budgetTargetDao().getAll()
        val report = LedgerConfigMerge.merge(
            remoteRows = remote,
            localRows = local,
            remoteGuid = { it.originGuid },
            localGuid = { it.guid },
            remoteDeleted = { it.deleted },
            remoteUpdatedAtMs = { it.updatedAtMs },
            // BudgetTarget has no separate updatedAtMs column - `updatedAt` doubles as the sync
            // clock, per that entity's own v62 doc comment (matching MealTarget's own precedent).
            localUpdatedAtMs = { it.updatedAt },
            localDeleted = { it.deleted },
            toInserted = { r ->
                BudgetTarget(
                    id = 0,
                    category = r.category,
                    currency = LedgerCurrency.valueOf(r.currency),
                    amountCents = r.amountCents,
                    effectiveFromMonthEpoch = r.effectiveFromMonthEpochMs,
                    updatedAt = r.updatedAtMs,
                    guid = r.originGuid,
                    serverId = r.serverId,
                    deleted = r.deleted,
                )
            },
            toMerged = { r, existing ->
                existing.copy(
                    category = r.category,
                    currency = LedgerCurrency.valueOf(r.currency),
                    amountCents = r.amountCents,
                    effectiveFromMonthEpoch = r.effectiveFromMonthEpochMs,
                    updatedAt = r.updatedAtMs,
                    serverId = r.serverId,
                    deleted = r.deleted,
                )
            },
            withDeletedFlag = { existing, atMs -> existing.copy(deleted = true, updatedAt = atMs) },
            // No local match found - BudgetTargetDao has no bare `insert`, only `upsert` (REPLACE
            // on the (category, currency, effectiveFromMonthEpoch) unique conflict). Safe here: a
            // genuinely new remote row cannot conflict with anything local by definition (if it
            // did, [localByGuid] would have found it by guid first, since a same-key local row
            // sharing no guid with this remote row would itself be a data problem outside this
            // merge's scope).
            insert = { db.budgetTargetDao().upsert(it) },
            update = { db.budgetTargetDao().update(it) },
        )
        remote.maxOfOrNull { it.updatedAtMs }?.let { LedgerConfigPullCursor.advance(context, T_BUDGET_TARGETS, it) }
        return report.toPullReport()
    }

    // --- Foreground auto-trigger, mirroring MemorySync's own shape -----------------------------

    private val autoPullScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var lastAutoPullAt = 0L

    private const val AUTO_PULL_MIN_INTERVAL_MS = 5 * 60 * 1000L
    private const val AUTO_PULL_RETRY_DELAY_MS = 1_000L

    /** Thin delegation to [SupabaseAuth.resolveSignedInUserId], same shape as
     * [MemorySync.resolveUserIdForAutoPull] - `internal` so a test can drive it directly. */
    internal suspend fun resolveUserIdForAutoPull(
        auth: SupabaseAuth,
        retryDelayMs: Long = AUTO_PULL_RETRY_DELAY_MS,
    ): String? = auth.resolveSignedInUserId(retryDelayMs)

    /** `MainActivity.onResume`'s hook, called alongside [MemorySync.maybeAutoPull]. No-ops
     * silently when Supabase is not configured or nobody is signed in. */
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
                val report = pull(app, SupabaseLedgerConfigBackend(client))
                MidnightEvents.ledgerConfigAutoPullSucceeded(
                    report.inserted, report.updated, report.skippedLocalNewer,
                    report.tombstoned, report.skippedTombstoneNoLocalMatch,
                )
            } catch (e: Exception) {
                MidnightEvents.ledgerConfigAutoPullFailed(e)
            }
        }
    }
}
