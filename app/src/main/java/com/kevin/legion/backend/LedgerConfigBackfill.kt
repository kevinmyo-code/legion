package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.backend.engine.EngineTransport
import com.kevin.legion.backend.engine.Transport
import com.kevin.legion.data.local.BudgetTarget
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Category
import com.kevin.legion.data.local.CategoryRule
import com.kevin.legion.data.local.MerchantAlias
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
        /** Categories the engine already had under the same name, linked rather than pushed. */
        val linkedByName: Int = 0,
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
                        val error = result.exceptionOrNull()
                        val line = "$table: " + failureLine(error, "row id ${localId(row)}, guid ${localGuid(row)}")
                        failure = failure?.let { "$it; $line" } ?: line
                        // A refusal is stepped over, never retried (see [isDefinitiveRefusal]);
                        // anything else stops this table so the next run retries the row.
                        if (!isDefinitiveRefusal(error)) break
                        LedgerConfigBackfillCursor.advance(context, table, localId(row))
                    }
                }
            }
        }

        return TableResult(pushed, alreadyPresent, skippedLocalOnlyDeleted, failure)
    }

    private fun failureLine(error: Throwable?, where: String): String {
        val message = error?.message ?: "unknown error"
        val prefix = if (isDefinitiveRefusal(error)) "refused by the engine, skipped: " else ""
        return "$prefix$message ($where)"
    }

    /**
     * Whether [error] is the engine refusing the row itself, which no retry changes (engine logs
     * 2026-11-10: one category's `PUT` came back 400 on every auto-run, five minutes apart, and
     * every row after it waited behind it). A 4xx other than a timeout or a throttle is; anything
     * that never reached the engine, a 5xx, or a reply that did not decode is not, and still stops
     * the table so the next run retries it.
     */
    internal fun isDefinitiveRefusal(error: Throwable?): Boolean {
        val refused = (error as? EngineHttpException)?.failure as? EngineFailure.Refused ?: return false
        return refused.status in CLIENT_ERROR_FIRST..CLIENT_ERROR_LAST &&
            refused.status != REQUEST_TIMEOUT && refused.status != TOO_MANY_REQUESTS
    }

    private const val CLIENT_ERROR_FIRST = 400
    private const val CLIENT_ERROR_LAST = 499
    private const val REQUEST_TIMEOUT = 408
    private const val TOO_MANY_REQUESTS = 429

    /** Every function below reuses [LedgerConfigWriteThrough]'s own payload-mapping classes rather
     * than re-deriving `Local -> Fields`, same "one mapping, never two" reasoning [BodyBackfill]'s
     * own class doc gives. */
    suspend fun run(context: Context, backend: LedgerConfigBackend): Report {
        val db = CarDatabase.getDatabase(context)

        var linked = 0
        val categories = backfillTable(
            context, OutboxTarget.LEDGER_CATEGORIES, db.categoryDao().getAllIncludingDeleted(),
            localId = { it.id }, localGuid = { it.guid }, localServerId = { it.serverId }, localDeleted = { it.deleted },
            push = { row: Category ->
                val pushed = backend.upsertCategory(row.guid, LedgerConfigWriteThrough.CategoryPayload.from(row).toFields())
                val refused = pushed.isFailure && isDefinitiveRefusal(pushed.exceptionOrNull())
                if (refused && linkCategoryByName(db, backend, row)) {
                    linked++
                    Result.success(Unit)
                } else {
                    pushed
                }
            },
        )
        val categoryRules = backfillTable(
            context, OutboxTarget.LEDGER_CATEGORY_RULES, db.categoryRuleDao().getAllIncludingDeleted(),
            localId = { it.id }, localGuid = { it.guid }, localServerId = { it.serverId }, localDeleted = { it.deleted },
            push = { row: CategoryRule -> backend.upsertCategoryRule(row.guid, LedgerConfigWriteThrough.CategoryRulePayload.from(row).toFields()) },
        )
        val merchantAliases = backfillTable(
            context, OutboxTarget.LEDGER_MERCHANT_ALIASES, db.merchantAliasDao().getAllIncludingDeleted(),
            localId = { it.id }, localGuid = { it.guid }, localServerId = { it.serverId }, localDeleted = { it.deleted },
            push = { row: MerchantAlias ->
                val fields = LedgerConfigWriteThrough.MerchantAliasPayload.from(row).toFields()
                backend.upsertMerchantAlias(row.guid, fields)
            },
        )
        val budgetTargets = backfillTable(
            context, OutboxTarget.LEDGER_BUDGET_TARGETS, db.budgetTargetDao().getAll(),
            localId = { it.id }, localGuid = { it.guid }, localServerId = { it.serverId }, localDeleted = { it.deleted },
            push = { row: BudgetTarget -> backend.upsertBudgetTarget(row.guid, LedgerConfigWriteThrough.BudgetTargetPayload.from(row).toFields()) },
        )

        val results = listOf(categories, categoryRules, merchantAliases, budgetTargets)

        return Report(
            pushed = results.sumOf { it.pushed },
            alreadyPresent = results.sumOf { it.alreadyPresent },
            skippedLocalOnlyDeleted = results.sumOf { it.skippedLocalOnlyDeleted },
            failed = results.mapNotNull { it.failure },
            linkedByName = linked,
        )
    }

    /**
     * Kevin's ruling (2026-11-10): a phone category the engine refuses because the engine already
     * has one with the same name is LINKED to that row, not skipped. The engine log showed
     * `PUT /api/ledger/categories/<guid>/` answered 400 on every run: the phone seeds its starter
     * categories with fresh guids, the engine already holds those names under other guids, and
     * `categories_name_unique` (household, name) refuses the second. The local row takes the
     * engine row's guid and id, so the next pull merges the two as one and nothing is pushed
     * again; the engine's values win on that pull. True when a row was linked. Matched by exact
     * name first, then ignoring case (the phone's own name index is case-insensitive in use).
     */
    internal suspend fun linkCategoryByName(db: CarDatabase, backend: LedgerConfigBackend, row: Category): Boolean {
        val remote = backend.fetchChangedCategoriesSince(0L).getOrNull()?.filterNot { it.deleted } ?: return false
        val match = remote.firstOrNull { it.name == row.name }
            ?: remote.firstOrNull { it.name.equals(row.name, ignoreCase = true) }
            ?: return false
        return runCatching {
            db.categoryDao().update(row.copy(guid = match.originGuid, serverId = match.serverId))
        }.isSuccess
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
     * in.
     *
     * Transport switch (django-engine Phase 5): the gate and the backend both come from
     * [EngineBackends] now, so a device whose `ledger` row is flipped to Django is not turned away
     * for having no Supabase project. `ledger` still defaults to Supabase. On Django there is no
     * Supabase session to resolve, so [runIfSignedIn]'s gate is bypassed rather than failed - same
     * shape [BodyBackfill.maybeAutoRun]'s own doc comment states. */
    suspend fun maybeAutoRun(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAutoRunAt < AUTO_RUN_MIN_INTERVAL_MS) return
        val app = context.applicationContext
        val backends = EngineBackends(app)
        if (!backends.isConfiguredFor(EngineBackends.ASPECT_LEDGER)) return
        val onDjango = EngineTransport(app).transportFor(EngineBackends.ASPECT_LEDGER) == Transport.DJANGO
        lastAutoRunAt = now
        try {
            val backend = backends.ledgerConfigBackend() ?: return
            val report = when {
                onDjango -> run(app, backend)
                else -> runIfSignedIn(app, SupabaseAuth(app), backend)
            } ?: return
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
