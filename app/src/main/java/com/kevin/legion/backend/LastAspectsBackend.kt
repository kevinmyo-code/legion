package com.kevin.legion.backend

/**
 * live-sync's LAST aspect slice - four tables across three domains (`goals`, `grocery_staples`,
 * `item_lists`, `list_items` - `.scratch/live-sync/map.md`'s "Lists"/"Goals"/"Pantry config"
 * rows), one interface, following [LedgerConfigBackend]'s own "many entities, one interface"
 * shape exactly - the FOURTH aspect built off that template, copied from the current file rather
 * than an older description of it (CLAUDE.md's own instruction for this ticket).
 *
 * **Why one interface for three domains rather than three**: the map bundles all four tables into
 * a single "last slice" because each is small (76/34/4 rows total) and none needs its own RLS
 * shape or upload cadence distinct from the others - splitting them into GoalsBackend/
 * ListsBackend/PantryConfigBackend would be three files each doing exactly what
 * [LedgerConfigBackend] already proves works for an unrelated trio (`categories`/`category_rules`/
 * `budget_targets`).
 *
 * **`item_lists`/`list_items` are FROZEN from the live app's own point of view** - see
 * [com.kevin.legion.data.local.ItemList]'s own class doc for the cutover
 * (`notes/NotesController.kt`) that repointed every live read/write onto the `events` table
 * instead. This backend still gives their existing rows a server home (the map's own ruling: "not
 * a duplicate of events"), but [LastAspectsWriteThrough]'s `item_lists`/`list_items` functions have
 * no live caller today - see that file's own class doc for the honest accounting of which write
 * sites this ticket could and could not route through write-through.
 *
 * **Every `originGuid` below is [com.kevin.legion.data.local.Goal.syncId] /
 * [com.kevin.legion.data.local.GroceryStaple.syncId] /
 * [com.kevin.legion.data.local.ItemList.syncId] / [com.kevin.legion.data.local.ListItem.syncId]
 * REUSED, never a freshly-minted `guid`** - all four tables already carried a portable identity
 * column before this ticket (unlike `categories`/`category_rules`/`budget_targets`, which had
 * none), matching [MemoryBackend]'s own precedent for `memories`/`companion_memories`. See
 * [MIGRATION_62_63]'s own class doc for the full account.
 *
 * **[RemoteListItem.listSyncId] carries the PARENT list's
 * [com.kevin.legion.data.local.ItemList.syncId], never
 * [com.kevin.legion.data.local.ListItem.listId]** - that column is a local autoincrement
 * surrogate key with no portable meaning across two devices' own copies of the same list, exactly
 * the "do not trust a client-minted id" ruling applied to a FOREIGN key instead of a primary one.
 * [LastAspectsSync] resolves it back to a local `listId` by looking the parent list up by its own
 * `syncId` - pulling `item_lists` before `list_items` in the same [LastAspectsSync.pull] call
 * guarantees the parent already exists locally by the time a child item is merged.
 *
 * Every function returns [Result], no [io.github.jan.supabase.SupabaseClient] in any signature,
 * matching [LedgerConfigBackend]'s own seam discipline.
 */

// ---------------------------------------------------------------------------------------------
// GOALS
// ---------------------------------------------------------------------------------------------

/** A `public.goals` row as Postgres reports it. [supersedesGuid] carries the prior revision's own
 * `originGuid`, never [com.kevin.legion.data.local.Goal.supersedesId] (a local autoincrement
 * surrogate, same non-portability reasoning as [RemoteListItem.listSyncId]'s own doc comment). */
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

// ---------------------------------------------------------------------------------------------
// GROCERY_STAPLES
// ---------------------------------------------------------------------------------------------

/** A `public.grocery_staples` row as Postgres reports it. */
data class RemoteGroceryStaple(
    val serverId: String,
    val name: String,
    val displayName: String,
    val timesBought: Int,
    val lastBoughtAt: Long,
    val updatedAtMs: Long,
    val deleted: Boolean,
    val originGuid: String,
)

data class GroceryStapleFields(
    val name: String,
    val displayName: String,
    val timesBought: Int,
    val lastBoughtAt: Long,
)

// ---------------------------------------------------------------------------------------------
// ITEM_LISTS
// ---------------------------------------------------------------------------------------------

/** A `public.item_lists` row as Postgres reports it. */
data class RemoteItemList(
    val serverId: String,
    val name: String,
    val tickable: Boolean,
    val sortOrder: Int,
    val lastUsedAt: Long,
    val archived: Boolean,
    val createdAt: Long,
    val updatedAtMs: Long,
    val deleted: Boolean,
    val originGuid: String,
)

data class ItemListFields(
    val name: String,
    val tickable: Boolean,
    val sortOrder: Int,
    val lastUsedAt: Long,
    val archived: Boolean,
    val createdAt: Long,
)

// ---------------------------------------------------------------------------------------------
// LIST_ITEMS
// ---------------------------------------------------------------------------------------------

/** A `public.list_items` row as Postgres reports it. See this file's own class doc for why
 * [listSyncId] is a list's `syncId`, never its local `listId`. */
data class RemoteListItem(
    val serverId: String,
    val listSyncId: String,
    val text: String,
    val done: Boolean,
    val doneAt: Long?,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAtMs: Long,
    val deleted: Boolean,
    val startsAt: Long?,
    val endsAt: Long?,
    val allDay: Boolean,
    val triggerPlaceLabel: String?,
    val repeatKind: String?,
    val repeatEvery: Int?,
    val repeatDaysOfWeek: String?,
    val repeatDay: Int?,
    val repeatMonth: Int?,
    val repeatEndKind: String?,
    val repeatEndDate: Long?,
    val repeatEndCount: Int?,
    val exact: Boolean,
    val exactDowngraded: Boolean,
    val missedAt: Long?,
    val missedDismissedAt: Long?,
    val loggedAt: Long?,
    val originGuid: String,
)

data class ListItemFields(
    val listSyncId: String,
    val text: String,
    val done: Boolean,
    val doneAt: Long?,
    val sortOrder: Int,
    val createdAt: Long,
    val startsAt: Long?,
    val endsAt: Long?,
    val allDay: Boolean,
    val triggerPlaceLabel: String?,
    val repeatKind: String?,
    val repeatEvery: Int?,
    val repeatDaysOfWeek: String?,
    val repeatDay: Int?,
    val repeatMonth: Int?,
    val repeatEndKind: String?,
    val repeatEndDate: Long?,
    val repeatEndCount: Int?,
    val exact: Boolean,
    val exactDowngraded: Boolean,
    val missedAt: Long?,
    val missedDismissedAt: Long?,
    val loggedAt: Long?,
)

/** See this file's own class doc for the shared shape every one of the following functions
 * follows. */
interface LastAspectsBackend {
    suspend fun fetchChangedGoalsSince(sinceMs: Long): Result<List<RemoteGoal>>
    suspend fun upsertGoal(originGuid: String, fields: GoalFields): Result<RemoteGoal>
    suspend fun softDeleteGoal(originGuid: String): Result<Boolean>

    suspend fun fetchChangedGroceryStaplesSince(sinceMs: Long): Result<List<RemoteGroceryStaple>>
    suspend fun upsertGroceryStaple(originGuid: String, fields: GroceryStapleFields): Result<RemoteGroceryStaple>
    suspend fun softDeleteGroceryStaple(originGuid: String): Result<Boolean>

    suspend fun fetchChangedItemListsSince(sinceMs: Long): Result<List<RemoteItemList>>
    suspend fun upsertItemList(originGuid: String, fields: ItemListFields): Result<RemoteItemList>
    suspend fun softDeleteItemList(originGuid: String): Result<Boolean>

    suspend fun fetchChangedListItemsSince(sinceMs: Long): Result<List<RemoteListItem>>
    suspend fun upsertListItem(originGuid: String, fields: ListItemFields): Result<RemoteListItem>
    suspend fun softDeleteListItem(originGuid: String): Result<Boolean>
}

/** Thrown (wrapped in [Result.failure]) by [SupabaseLastAspectsBackend] for every failure branch -
 * same posture as [LedgerConfigBackendException]. */
class LastAspectsBackendException(message: String) : Exception(message)
