package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.data.local.BudgetTarget
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Category
import com.kevin.legion.data.local.CategoryRule
import com.kevin.legion.data.local.OutboxEntry
import com.kevin.legion.data.local.OutboxOperation
import com.kevin.legion.data.local.OutboxTarget
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The push half of ledger CONFIG sync: write-through on every create/update/delete
 * [com.kevin.legion.ledger.LedgerController] performs against `categories`/`category_rules`/
 * `budget_targets`, plus the durable outbox that makes an offline write survive - mirrors
 * [MemoryWriteThrough]/[MemoryOutboxDrain]'s own shape. `ledger_transactions` is out of scope -
 * see [LedgerConfigBackend]'s own class doc.
 *
 * **Local write always happens first, unconditionally** - same posture as [MemoryWriteThrough]'s
 * own class doc.
 */
object LedgerConfigWriteThrough {
    /** Test seam, same mechanism as [MemoryWriteThrough.backendOverride]. */
    @Volatile
    internal var backendOverride: LedgerConfigBackend? = null

    private fun backend(context: Context): LedgerConfigBackend? {
        backendOverride?.let { return it }
        val client = SupabaseClientProvider.get(context) ?: return null
        return SupabaseLedgerConfigBackend(client)
    }

    /** See [MemoryWriteThrough.cancelPendingCreateIfPending]'s own doc comment for the full
     * reasoning - identical shape, applied to ledger config's own outbox targets. */
    private suspend fun cancelPendingCreateIfPending(db: CarDatabase, target: String, localId: Long): Boolean {
        val dao = db.outboxDao()
        val pending = dao.pendingForTable(target, Int.MAX_VALUE)
            .filter { it.operation == OutboxOperation.UPSERT && it.localId == localId }
        for (entry in pending) dao.delete(entry.id)
        return pending.isNotEmpty()
    }

    private suspend fun enqueue(db: CarDatabase, target: String, operation: String, localId: Long, payload: String, error: String?) {
        db.outboxDao().insert(
            OutboxEntry(
                targetTable = target,
                operation = operation,
                localId = localId,
                payload = payload,
                createdAt = System.currentTimeMillis(),
                attempts = 0,
                lastError = error,
            ),
        )
    }

    // --- Categories --------------------------------------------------------------------------------

    @Serializable
    internal data class CategoryPayload(
        val guid: String,
        val name: String,
        val isFoodCategory: Boolean,
    ) {
        fun toFields() = CategoryFields(name, isFoodCategory)
        companion object {
            fun from(row: Category) = CategoryPayload(row.guid, row.name, row.isFoodCategory)
        }
    }

    /** [com.kevin.legion.ledger.LedgerController.addCategory]'s write - inserts locally first,
     * unconditionally, then pushes if configured, enqueueing on failure. No delete counterpart:
     * nothing in this codebase deletes a category today (traced against
     * [com.kevin.legion.data.local.CategoryDao] - `insert`/reads only), matching
     * [BodyWriteThrough.setMealTarget]'s own "no delete counterpart" shape for a table nothing
     * currently deletes. [LedgerConfigBackend.softDeleteCategory] still exists on the interface
     * for symmetry with every other synced table, same as [BodyBackend.softDeleteMealTarget]'s own
     * unused-but-present precedent.
     */
    suspend fun addCategory(context: Context, row: Category): Category {
        val db = CarDatabase.getDatabase(context)
        db.categoryDao().insert(row)
        val backend = backend(context) ?: return row
        val result = backend.upsertCategory(row.guid, CategoryPayload.from(row).toFields())
        if (result.isFailure) {
            enqueue(
                db, OutboxTarget.LEDGER_CATEGORIES, OutboxOperation.UPSERT, row.id,
                Json.encodeToString(CategoryPayload.serializer(), CategoryPayload.from(row)),
                result.exceptionOrNull()?.message,
            )
        }
        return row
    }

    // --- Category rules ----------------------------------------------------------------------------

    @Serializable
    internal data class CategoryRulePayload(
        val guid: String,
        val category: String,
        val substring: String,
        val createdAtMs: Long,
    ) {
        fun toFields() = CategoryRuleFields(category, substring, createdAtMs)
        companion object {
            fun from(row: CategoryRule) = CategoryRulePayload(row.guid, row.category, row.substring, row.createdAt)
        }
    }

    /** [com.kevin.legion.ledger.LedgerController.setCategory]/[.confirmCategoryGuess]'s write. */
    suspend fun addCategoryRule(context: Context, row: CategoryRule): CategoryRule {
        val db = CarDatabase.getDatabase(context)
        db.categoryRuleDao().insert(row)
        val backend = backend(context) ?: return row
        val result = backend.upsertCategoryRule(row.guid, CategoryRulePayload.from(row).toFields())
        if (result.isFailure) {
            enqueue(
                db, OutboxTarget.LEDGER_CATEGORY_RULES, OutboxOperation.UPSERT, row.id,
                Json.encodeToString(CategoryRulePayload.serializer(), CategoryRulePayload.from(row)),
                result.exceptionOrNull()?.message,
            )
        }
        return row
    }

    /**
     * Soft-deletes and pushes a tombstone for every ACTIVE rule matching [substring] -
     * [com.kevin.legion.data.local.CategoryRuleDao.deleteBySubstring]'s configured-install
     * replacement, called by [com.kevin.legion.ledger.LedgerController.setCategory] (before
     * inserting the corrected rule) and [com.kevin.legion.ledger.LedgerController.clearCategoryRules]
     * (the undo path). Returns the count affected, matching [CategoryRuleDao.deleteBySubstring]'s
     * own `Int` return so neither caller's logic (e.g. `rowsTouched > 0` gating) needs to change.
     *
     * On an unconfigured install, falls back to the original bare
     * [com.kevin.legion.data.local.CategoryRuleDao.deleteBySubstring] - unchanged behaviour for
     * every install that never had a server copy to resurrect a hard-deleted row from, same shape
     * as [MemoryWriteThrough.deleteAllMemoryEntries]'s own fallback.
     */
    suspend fun deleteCategoryRulesBySubstring(context: Context, substring: String): Int {
        val db = CarDatabase.getDatabase(context)
        val backend = backend(context)
        if (backend == null) {
            return db.categoryRuleDao().deleteBySubstring(substring)
        }
        val matches = db.categoryRuleDao().getActiveBySubstring(substring)
        for (rule in matches) {
            // A still-pending create is cancelled outright first (same shape as
            // MemoryWriteThrough.deleteMemoryEntry's own "cancel, then continue" branch) - if it
            // fires, the row never reached the server, so the soft-delete below still runs (there
            // is no unsynced-create/hard-delete fast path here the way memory has one, since this
            // table's rows are soft-delete-first by design) but there is genuinely nothing for
            // [backend.softDeleteCategoryRule] to find, and Supabase's own upsert-then-tombstone
            // ordering means the enqueued create is simply gone, so this call is a defensive no-op
            // rather than a required step.
            cancelPendingCreateIfPending(db, OutboxTarget.LEDGER_CATEGORY_RULES, rule.id)
            db.categoryRuleDao().update(rule.copy(deleted = true, updatedAtMs = System.currentTimeMillis()))
            val result = backend.softDeleteCategoryRule(rule.guid)
            if (result.isFailure) {
                enqueue(
                    db, OutboxTarget.LEDGER_CATEGORY_RULES, OutboxOperation.SOFT_DELETE, rule.id,
                    Json.encodeToString(LedgerConfigDeletePayload.serializer(), LedgerConfigDeletePayload(rule.guid)),
                    result.exceptionOrNull()?.message,
                )
            }
        }
        return matches.size
    }

    // --- Budget targets ------------------------------------------------------------------------------

    @Serializable
    internal data class BudgetTargetPayload(
        val guid: String,
        val category: String,
        val currency: String,
        val amountCents: Long,
        val effectiveFromMonthEpochMs: Long,
    ) {
        fun toFields() = BudgetTargetFields(category, currency, amountCents, effectiveFromMonthEpochMs)
        companion object {
            fun from(row: BudgetTarget) = BudgetTargetPayload(row.guid, row.category, row.currency.name, row.amountCents, row.effectiveFromMonthEpoch)
        }
    }

    /** [com.kevin.legion.ledger.LedgerController.setBudget]'s write - `upsert`'s `REPLACE`, not a
     * create/update fork, same shape as [BodyWriteThrough.setMealTarget]. No delete counterpart:
     * [com.kevin.legion.ledger.LedgerController] never deletes a budget target, only writes a new
     * effective-dated one (the "copy forward" shape every target table uses) - see
     * [com.kevin.legion.data.local.BudgetTarget]'s own doc comment. */
    suspend fun setBudgetTarget(context: Context, row: BudgetTarget): BudgetTarget {
        val db = CarDatabase.getDatabase(context)
        db.budgetTargetDao().upsert(row)
        val backend = backend(context) ?: return row
        val result = backend.upsertBudgetTarget(row.guid, BudgetTargetPayload.from(row).toFields())
        if (result.isFailure) {
            enqueue(
                db, OutboxTarget.LEDGER_BUDGET_TARGETS, OutboxOperation.UPSERT, row.id,
                Json.encodeToString(BudgetTargetPayload.serializer(), BudgetTargetPayload.from(row)),
                result.exceptionOrNull()?.message,
            )
        }
        return row
    }
}

/** The wire shape queued for every [OutboxOperation.SOFT_DELETE] entry across ledger config's
 * write-through tables - just the [LedgerConfigBackend]'s own upsert key, matching
 * [MemoryDeletePayload]'s shape. */
@Serializable
internal data class LedgerConfigDeletePayload(val guid: String)

/**
 * Retries every still-pending ledger-config-table [OutboxEntry], across
 * [OutboxTarget.LEDGER_CATEGORIES], [OutboxTarget.LEDGER_CATEGORY_RULES] and
 * [OutboxTarget.LEDGER_BUDGET_TARGETS] - mirrors [MemoryOutboxDrain]'s own shape and
 * bounded-attempts reasoning exactly. `ui/MainActivity.kt`'s `onResume` hook calls this BEFORE
 * [LedgerConfigBackfill.maybeAutoRun] and [LedgerConfigSync.maybeAutoPull], same load-bearing
 * ordering [MemoryOutboxDrain]'s own class doc explains.
 */
object LedgerConfigOutboxDrain {
    const val MAX_ATTEMPTS = EventsOutboxDrain.MAX_ATTEMPTS

    data class DrainReport(val succeeded: Int, val stillPending: Int, val poisoned: Int) {
        operator fun plus(other: DrainReport) = DrainReport(
            succeeded + other.succeeded, stillPending + other.stillPending, poisoned + other.poisoned,
        )
    }

    suspend fun drain(context: Context, backend: LedgerConfigBackend): DrainReport {
        val db = CarDatabase.getDatabase(context)
        var total = DrainReport(0, 0, 0)

        total += drainOne(db, OutboxTarget.LEDGER_CATEGORIES) { entry ->
            when (entry.operation) {
                OutboxOperation.UPSERT -> {
                    val p = Json.decodeFromString(LedgerConfigWriteThrough.CategoryPayload.serializer(), entry.payload)
                    backend.upsertCategory(p.guid, p.toFields())
                }
                OutboxOperation.SOFT_DELETE -> {
                    val p = Json.decodeFromString(LedgerConfigDeletePayload.serializer(), entry.payload)
                    backend.softDeleteCategory(p.guid)
                }
                else -> Result.success(Unit)
            }
        }
        total += drainOne(db, OutboxTarget.LEDGER_CATEGORY_RULES) { entry ->
            when (entry.operation) {
                OutboxOperation.UPSERT -> {
                    val p = Json.decodeFromString(LedgerConfigWriteThrough.CategoryRulePayload.serializer(), entry.payload)
                    backend.upsertCategoryRule(p.guid, p.toFields())
                }
                OutboxOperation.SOFT_DELETE -> {
                    val p = Json.decodeFromString(LedgerConfigDeletePayload.serializer(), entry.payload)
                    backend.softDeleteCategoryRule(p.guid)
                }
                else -> Result.success(Unit)
            }
        }
        total += drainOne(db, OutboxTarget.LEDGER_BUDGET_TARGETS) { entry ->
            when (entry.operation) {
                OutboxOperation.UPSERT -> {
                    val p = Json.decodeFromString(LedgerConfigWriteThrough.BudgetTargetPayload.serializer(), entry.payload)
                    backend.upsertBudgetTarget(p.guid, p.toFields())
                }
                OutboxOperation.SOFT_DELETE -> {
                    val p = Json.decodeFromString(LedgerConfigDeletePayload.serializer(), entry.payload)
                    backend.softDeleteBudgetTarget(p.guid)
                }
                else -> Result.success(Unit)
            }
        }
        return total
    }

    private suspend fun drainOne(db: CarDatabase, target: String, run: suspend (OutboxEntry) -> Result<*>): DrainReport {
        val dao = db.outboxDao()
        val pending = dao.pendingForTable(target, MAX_ATTEMPTS)
        var succeeded = 0
        var stillPending = 0
        var poisoned = 0
        for (entry in pending) {
            val result = run(entry)
            if (result.isSuccess) {
                dao.delete(entry.id)
                succeeded++
                continue
            }
            val attempts = entry.attempts + 1
            val message = result.exceptionOrNull()?.message ?: "unknown error"
            dao.recordAttempt(entry.id, attempts, message)
            if (attempts >= MAX_ATTEMPTS) poisoned++ else stillPending++
        }
        return DrainReport(succeeded, stillPending, poisoned)
    }

    /** `MainActivity.onResume`'s hook - see this object's own class doc for the ordering that
     * matters. No-ops silently when Supabase is not configured or nobody is signed in. */
    suspend fun maybeDrain(context: Context) {
        val app = context.applicationContext
        val client = SupabaseClientProvider.get(app) ?: return
        if (SupabaseAuth(app).resolveSignedInUserId() == null) return
        try {
            val report = drain(app, SupabaseLedgerConfigBackend(client))
            MidnightEvents.ledgerConfigOutboxDrainSucceeded(report.succeeded, report.stillPending, report.poisoned)
        } catch (e: Exception) {
            MidnightEvents.ledgerConfigOutboxDrainFailed(e)
        }
    }
}
