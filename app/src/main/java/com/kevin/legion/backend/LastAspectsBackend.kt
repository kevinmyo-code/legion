package com.kevin.legion.backend

/**
 * live-sync's LAST aspect slice, narrowed to ONE table: `goals`
 * (`.scratch/live-sync/map.md`'s "Goals" row), one interface, following [LedgerConfigBackend]'s own
 * "many entities, one interface" shape - the FOURTH aspect built off that template.
 *
 * **NARROWED by the lists redesign slice 1a (2026-11-24).** This slice carried four tables across
 * three domains. Three of them are gone:
 * - `item_lists`/`list_items` were FROZEN from the live app's point of view long before
 *   (`notes/NotesController.kt` repointed every live read/write onto `events`, backend-erp ticket
 *   15 step 4), so these functions had no producer and nothing read what they pulled. The engine
 *   has no twin for either table either.
 * - `grocery_staples` lost its one writer when `grocery/GroceryController.kt` was deleted in the
 *   same slice. The engine's own `grocery_staples` (`server/api/pantry.py`) is a different, live
 *   store and is untouched by this.
 *
 * `goals` stays because the phone's `goals/GoalController.kt` still writes it. Retiring that is its
 * own slice; when it lands, this whole file set goes with it.
 *
 * **Every `originGuid` below is [com.kevin.legion.data.local.Goal.syncId] REUSED, never a
 * freshly-minted `guid`** - the table already carried a portable identity column before this ticket
 * (unlike `categories`/`category_rules`/`budget_targets`, which had none), matching
 * [MemoryBackend]'s own precedent for `memories`/`companion_memories`. See [MIGRATION_62_63]'s own
 * class doc for the full account.
 *
 * Every function returns [Result], no [io.github.jan.supabase.SupabaseClient] in any signature,
 * matching [LedgerConfigBackend]'s own seam discipline.
 */

// ---------------------------------------------------------------------------------------------
// GOALS
// ---------------------------------------------------------------------------------------------

/** A `public.goals` row as Postgres reports it. [supersedesGuid] carries the prior revision's own
 * `originGuid`, never [com.kevin.legion.data.local.Goal.supersedesId] - that is a local
 * autoincrement surrogate key with no portable meaning across two devices' own copies of the same
 * row, which is the "do not trust a client-minted id" ruling applied to a reference instead of a
 * primary key. */
data class RemoteGoal(
    val serverId: String,
    val lineageId: Long,
    val aspect: String,
    val statement: String,
    val targetValue: Double?,
    val unit: String?,
    val metricKey: String?,
    val deadlineEpoch: Long?,
    val status: String,
    val supersedesGuid: String?,
    val closedAt: Long?,
    val createdAt: Long,
    val updatedAtMs: Long,
    val deleted: Boolean,
    val originGuid: String,
)

data class GoalFields(
    val lineageId: Long,
    val aspect: String,
    val statement: String,
    val targetValue: Double?,
    val unit: String?,
    val metricKey: String?,
    val deadlineEpoch: Long?,
    val status: String,
    val supersedesGuid: String?,
    val closedAt: Long?,
    val createdAt: Long,
)

/** See this file's own class doc for the shared shape every one of the following functions
 * follows. */
interface LastAspectsBackend {
    suspend fun fetchChangedGoalsSince(sinceMs: Long): Result<List<RemoteGoal>>
    suspend fun upsertGoal(originGuid: String, fields: GoalFields): Result<RemoteGoal>
    suspend fun softDeleteGoal(originGuid: String): Result<Boolean>
}

/** Thrown (wrapped in [Result.failure]) by [SupabaseLastAspectsBackend] for every failure branch -
 * same posture as [LedgerConfigBackendException]. */
class LastAspectsBackendException(message: String) : Exception(message)
