package com.kevin.legion.backend

/**
 * The ledger CONFIG aspect's Supabase seam - three tables
 * (`supabase/migrations/20260902000400_aspect_ledger_config.sql`), one interface, following
 * [MemoryBackend]'s own "many entities, one interface" shape exactly - **this is the THIRD aspect
 * built off that template**, copied from the current file rather than an older description of it.
 *
 * **Scope, deliberately narrow.** `categories`, `category_rules`, `budget_targets` only -
 * `ledger_transactions` already has a server table and its own upload path (`LedgerReconcile`)
 * and is explicitly out of scope (`.scratch/live-sync/map.md`'s own table). These three are "the
 * categorisation rules and budgets Kevin built by hand" - irreplaceable in the sense that
 * re-deriving them means redoing that work, which is why the map gives them a server home at all.
 *
 * - **Every write is a genuine upsert keyed on [originGuid], never a create/update fork** - same
 *   reasoning as [MemoryBackend]'s own class doc. [originGuid] is backed by a freshly-minted
 *   `guid` column on all three tables (none of them had an existing portable identity column to
 *   reuse) - see each entity's own v62 doc comment.
 * - **[fetchChangedCategoriesSince]/[fetchChangedCategoryRulesSince]/[fetchChangedBudgetTargetsSince]
 *   return tombstones too, never active-only** - same [MemoryBackend] posture, built in from the
 *   start rather than discovered the hard way.
 * - **The soft-delete functions take [originGuid], never a server uuid** - same reasoning as
 *   [MemoryBackend.softDeleteMemoryEntry]'s own doc comment.
 *
 * Every function returns [Result], no [io.github.jan.supabase.SupabaseClient] in any signature,
 * matching [MemoryBackend]'s own seam discipline.
 */

// ---------------------------------------------------------------------------------------------
// CATEGORIES
// ---------------------------------------------------------------------------------------------

/** A `public.categories` row as Postgres reports it. */
data class RemoteCategory(
    val serverId: String,
    val name: String,
    val isFoodCategory: Boolean,
    val updatedAtMs: Long,
    val deleted: Boolean,
    val originGuid: String,
)

data class CategoryFields(
    val name: String,
    val isFoodCategory: Boolean,
)

// ---------------------------------------------------------------------------------------------
// CATEGORY_RULES
// ---------------------------------------------------------------------------------------------

/** A `public.category_rules` row as Postgres reports it. */
data class RemoteCategoryRule(
    val serverId: String,
    val category: String,
    val substring: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val deleted: Boolean,
    val originGuid: String,
)

data class CategoryRuleFields(
    val category: String,
    val substring: String,
    val createdAtMs: Long,
)

// ---------------------------------------------------------------------------------------------
// BUDGET_TARGETS
// ---------------------------------------------------------------------------------------------

/** A `public.budget_targets` row as Postgres reports it. */
data class RemoteBudgetTarget(
    val serverId: String,
    val category: String,
    val currency: String,
    val amountCents: Long,
    val effectiveFromMonthEpochMs: Long,
    val updatedAtMs: Long,
    val deleted: Boolean,
    val originGuid: String,
)

data class BudgetTargetFields(
    val category: String,
    val currency: String,
    val amountCents: Long,
    val effectiveFromMonthEpochMs: Long,
)

/** See this file's own class doc for the shared shape every one of the following functions
 * follows. */
interface LedgerConfigBackend {
    suspend fun fetchChangedCategoriesSince(sinceMs: Long): Result<List<RemoteCategory>>
    suspend fun upsertCategory(originGuid: String, fields: CategoryFields): Result<RemoteCategory>
    suspend fun softDeleteCategory(originGuid: String): Result<Boolean>

    suspend fun fetchChangedCategoryRulesSince(sinceMs: Long): Result<List<RemoteCategoryRule>>
    suspend fun upsertCategoryRule(originGuid: String, fields: CategoryRuleFields): Result<RemoteCategoryRule>
    suspend fun softDeleteCategoryRule(originGuid: String): Result<Boolean>

    suspend fun fetchChangedBudgetTargetsSince(sinceMs: Long): Result<List<RemoteBudgetTarget>>
    suspend fun upsertBudgetTarget(originGuid: String, fields: BudgetTargetFields): Result<RemoteBudgetTarget>
    suspend fun softDeleteBudgetTarget(originGuid: String): Result<Boolean>
}

/** Thrown (wrapped in [Result.failure]) by [SupabaseLedgerConfigBackend] for every failure branch -
 * same posture as [MemoryBackendException]. */
class LedgerConfigBackendException(message: String) : Exception(message)
