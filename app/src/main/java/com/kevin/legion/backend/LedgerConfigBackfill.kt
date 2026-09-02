package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.data.local.BudgetTarget
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Category
import com.kevin.legion.data.local.CategoryRule
import com.kevin.legion.data.local.OutboxTarget

/**
 * Per-table install-scoped high-water mark for [LedgerConfigBackfill]'s one-time (then perpetually
 * cheap-no-op) upload - same shape as [MemoryBackfillCursor]/[BodyBackfillCursor]. See
 * [BodyBackfill]'s own class doc for the full reasoning; unchanged here.
 */
internal object LedgerConfigBackfillCursor {
    private const val PREFS = "ledger_config_backfill_cursor"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastBackfilledId(context: Context, table: String): Long = prefs(context).getLong(table, 0L)

    fun advance(context: Context, table: String, id: Long) {
        prefs(context).edit().putLong(table, id).apply()
    }
}

/**
 * The one-time backfill for ledger config: every local row that predates write-through
 * (`LedgerConfigWriteThrough`/`LedgerConfigOutbox.kt`) has no path to the server at all, because
 * write-through only ever pushes NEW writes going forward - same gap [BodyBackfill]'s own class
 * doc describes for body, same five numbered rules apply (idempotent via `origin_guid`, resumable
 * via [LedgerConfigBackfillCursor], `serverId` trusted only in the "already present" direction, a
 * locally-deleted-and-never-synced row is skipped rather than resurrected, per-table failures do
 * not abort the whole run) - see [BodyBackfill]'s own class doc for the reasoning behind each,
 * verbatim here. This is the ticket's own "106 category_rules / 16 categories / 10 budget_targets"
 * rows, all of which predate this migration entirely.
 */
object LedgerConfigBackfill {
    private data class TableResult(
        val pushed: Int,
        val alreadyPresent: Int,
        val skippedLocalOnlyDeleted: Int,
        val failure: String?,
    )

    data class Report(
        val pushed: Int,
        val alreadyPresent: Int,
        val skippedLocalOnlyDeleted: Int,
        val failed: List<String>,
    )

    private suspend fun <L> backfillTable(
        context: Context,
        table: String,
        rows: List<L>,
        localId: (L) -> Long,
        localGuid: (L) -> String,
        localServerId: (L) -> String?,
        localDeleted: (L) -> Boolean,
        push: suspend (L) -> Result<*>,
    ): TableResult {
        val cursorAtStart = LedgerConfigBackfillCursor.lastBackfilledId(context, table)
        val pending = rows.filter { localId(it) > cursorAtStart }.sortedBy { localId(it) }

        var pushed = 0
        var alreadyPresent = 0
        var skippedLocalOnlyDeleted = 0
        var failure: String? = null

        for (row in pending) {
            when {
                localServerId(row) != null -> {
                    alreadyPresent++
                    LedgerConfigBackfillCursor.advance(context, table, localId(row))
                }
                localDeleted(row) -> {
                    skippedLocalOnlyDeleted++
                    LedgerConfigBackfillCursor.advance(context, table, localId(row))
                }
                else -> {
                    val result = push(row)
                    if (result.isSuccess) {
                        pushed++
                        LedgerConfigBackfillCursor.advance(context, table, localId(row))
                    } else {
                        val message = result.exceptionOrNull()?.message ?: "unknown error"
                        failure = "$table: $message (row id ${localId(row)}, guid ${localGuid(row)})"
                        break
                    }
                }
            }
        }

        return TableResult(pushed, alreadyPresent, skippedLocalOnlyDeleted, failure)
    }

    /** Every function below reuses [LedgerConfigWriteThrough]'s own payload-mapping classes rather
     * than re-deriving `Local -> Fields`, same "one mapping, never two" reasoning [BodyBackfill]'s
     * own class doc gives. */
    suspend fun run(context: Context, backend: LedgerConfigBackend): Report {
        val db = CarDatabase.getDatabase(context)

        val categories = backfillTable(
            context, OutboxTarget.LEDGER_CATEGORIES, db.categoryDao().getAllIncludingDeleted(),
            localId = { it.id }, localGuid = { it.guid }, localServerId = { it.serverId }, localDeleted = { it.deleted },
            push = { row: Category -> backend.upsertCategory(row.guid, LedgerConfigWriteThrough.CategoryPayload.from(row).toFields()) },
        )
        val categoryRules = backfillTable(
            context, OutboxTarget.LEDGER_CATEGORY_RULES, db.categoryRuleDao().getAllIncludingDeleted(),
            localId = { it.id }, localGuid = { it.guid }, localServerId = { it.serverId }, localDeleted = { it.deleted },
            push = { row: CategoryRule -> backend.upsertCategoryRule(row.guid, LedgerConfigWriteThrough.CategoryRulePayload.from(row).toFields()) },
        )
        val budgetTargets = backfillTable(
            context, OutboxTarget.LEDGER_BUDGET_TARGETS, db.budgetTargetDao().getAll(),
            localId = { it.id }, localGuid = { it.guid }, localServerId = { it.serverId }, localDeleted = { it.deleted },
            push = { row: BudgetTarget -> backend.upsertBudgetTarget(row.guid, LedgerConfigWriteThrough.BudgetTargetPayload.from(row).toFields()) },
        )

        val results = listOf(categories, categoryRules, budgetTargets)

        return Report(
            pushed = results.sumOf { it.pushed },
            alreadyPresent = results.sumOf { it.alreadyPresent },
            skippedLocalOnlyDeleted = results.sumOf { it.skippedLocalOnlyDeleted },
            failed = results.mapNotNull { it.failure },
        )
    }

    // --- Foreground auto-trigger ------------------------------------------------------------------

    @Volatile private var lastAutoRunAt = 0L

    /** Same floor as [LedgerConfigSync]'s own auto-pull interval - see
     * [BodyBackfill.AUTO_RUN_MIN_INTERVAL_MS]'s own doc comment for why. */
    private const val AUTO_RUN_MIN_INTERVAL_MS = 5 * 60 * 1000L

    /** `MainActivity.onResume`'s hook - runs BEFORE [LedgerConfigSync.maybeAutoPull], same
     * reasoning as [BodyBackfill.maybeAutoRun]'s own class doc gives for body: an unsent local row
     * must reach the server before the pull weighs last-write-wins against a server copy that does
     * not know about it yet. No-ops silently when Supabase is not configured or nobody is signed
     * in. */
    suspend fun maybeAutoRun(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAutoRunAt < AUTO_RUN_MIN_INTERVAL_MS) return
        val app = context.applicationContext
        val client = SupabaseClientProvider.get(app) ?: return
        lastAutoRunAt = now
        try {
            val report = runIfSignedIn(app, SupabaseAuth(app), SupabaseLedgerConfigBackend(client)) ?: return
            MidnightEvents.ledgerConfigBackfillSucceeded(report.pushed, report.alreadyPresent, report.skippedLocalOnlyDeleted, report.failed)
        } catch (e: Exception) {
            MidnightEvents.ledgerConfigBackfillFailed(e)
        }
    }

    /** [maybeAutoRun]'s guard-then-[run], factored out so a test can drive the "still restoring,
     * then succeeds" retry directly - same shape as [BodyBackfill.runIfSignedIn]. */
    internal suspend fun runIfSignedIn(context: Context, auth: SupabaseAuth, backend: LedgerConfigBackend): Report? {
        if (auth.resolveSignedInUserId() == null) return null
        return run(context, backend)
    }
}
