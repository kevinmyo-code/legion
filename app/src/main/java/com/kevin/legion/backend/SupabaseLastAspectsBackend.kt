package com.kevin.legion.backend

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val GOALS_TABLE = "goals"
private const val GROCERY_STAPLES_TABLE = "grocery_staples"
private const val ITEM_LISTS_TABLE = "item_lists"
private const val LIST_ITEMS_TABLE = "list_items"

private fun lastAspectsTs(ms: Long): String = Instant.ofEpochMilli(ms).toString()
private fun lastAspectsParseTs(s: String): Long = OffsetDateTime.parse(s).toInstant().toEpochMilli()

// ---------------------------------------------------------------------------------------------
// GOALS
// ---------------------------------------------------------------------------------------------

@Serializable
private data class GoalUpsertDto(
    @SerialName("lineage_id") val lineageId: Long,
    val aspect: String,
    val statement: String,
    @SerialName("target_value") val targetValue: Double?,
    val unit: String?,
    @SerialName("metric_key") val metricKey: String?,
    @SerialName("deadline_epoch") val deadlineEpoch: String?,
    val status: String,
    @SerialName("supersedes_guid") val supersedesGuid: String?,
    @SerialName("closed_at") val closedAt: String?,
    @SerialName("created_at_client") val createdAtClient: String,
    @SerialName("origin_guid") val originGuid: String,
)

@Serializable
private data class GoalRowDto(
    val id: String,
    @SerialName("lineage_id") val lineageId: Long,
    val aspect: String,
    val statement: String,
    @SerialName("target_value") val targetValue: Double? = null,
    val unit: String? = null,
    @SerialName("metric_key") val metricKey: String? = null,
    @SerialName("deadline_epoch") val deadlineEpoch: String? = null,
    val status: String,
    @SerialName("supersedes_guid") val supersedesGuid: String? = null,
    @SerialName("closed_at") val closedAt: String? = null,
    @SerialName("created_at_client") val createdAtClient: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("origin_guid") val originGuid: String,
) {
    fun toRemote() = RemoteGoal(
        serverId = id,
        lineageId = lineageId,
        aspect = aspect,
        statement = statement,
        targetValue = targetValue,
        unit = unit,
        metricKey = metricKey,
        deadlineEpoch = deadlineEpoch?.let { lastAspectsParseTs(it) },
        status = status,
        supersedesGuid = supersedesGuid,
        closedAt = closedAt?.let { lastAspectsParseTs(it) },
        createdAt = lastAspectsParseTs(createdAtClient),
        updatedAtMs = lastAspectsParseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

// ---------------------------------------------------------------------------------------------
// GROCERY_STAPLES
// ---------------------------------------------------------------------------------------------

@Serializable
private data class GroceryStapleUpsertDto(
    val name: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("times_bought") val timesBought: Int,
    @SerialName("last_bought_at") val lastBoughtAt: String,
    @SerialName("origin_guid") val originGuid: String,
)

@Serializable
private data class GroceryStapleRowDto(
    val id: String,
    val name: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("times_bought") val timesBought: Int,
    @SerialName("last_bought_at") val lastBoughtAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("origin_guid") val originGuid: String,
) {
    fun toRemote() = RemoteGroceryStaple(
        serverId = id,
        name = name,
        displayName = displayName,
        timesBought = timesBought,
        lastBoughtAt = lastAspectsParseTs(lastBoughtAt),
        updatedAtMs = lastAspectsParseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

// ---------------------------------------------------------------------------------------------
// ITEM_LISTS
// ---------------------------------------------------------------------------------------------

@Serializable
private data class ItemListUpsertDto(
    val name: String,
    val tickable: Boolean,
    @SerialName("sort_order") val sortOrder: Int,
    @SerialName("last_used_at") val lastUsedAt: String,
    val archived: Boolean,
    @SerialName("created_at_client") val createdAtClient: String,
    @SerialName("origin_guid") val originGuid: String,
)

@Serializable
private data class ItemListRowDto(
    val id: String,
    val name: String,
    val tickable: Boolean,
    @SerialName("sort_order") val sortOrder: Int,
    @SerialName("last_used_at") val lastUsedAt: String,
    val archived: Boolean,
    @SerialName("created_at_client") val createdAtClient: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("origin_guid") val originGuid: String,
) {
    fun toRemote() = RemoteItemList(
        serverId = id,
        name = name,
        tickable = tickable,
        sortOrder = sortOrder,
        lastUsedAt = lastAspectsParseTs(lastUsedAt),
        archived = archived,
        createdAt = lastAspectsParseTs(createdAtClient),
        updatedAtMs = lastAspectsParseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

// ---------------------------------------------------------------------------------------------
// LIST_ITEMS
// ---------------------------------------------------------------------------------------------

@Serializable
private data class ListItemUpsertDto(
    @SerialName("list_origin_guid") val listOriginGuid: String,
    val text: String,
    val done: Boolean,
    @SerialName("done_at") val doneAt: String?,
    @SerialName("sort_order") val sortOrder: Int,
    @SerialName("created_at_client") val createdAtClient: String,
    @SerialName("starts_at") val startsAt: String?,
    @SerialName("ends_at") val endsAt: String?,
    @SerialName("all_day") val allDay: Boolean,
    @SerialName("trigger_place_label") val triggerPlaceLabel: String?,
    @SerialName("repeat_kind") val repeatKind: String?,
    @SerialName("repeat_every") val repeatEvery: Int?,
    @SerialName("repeat_days_of_week") val repeatDaysOfWeek: String?,
    @SerialName("repeat_day") val repeatDay: Int?,
    @SerialName("repeat_month") val repeatMonth: Int?,
    @SerialName("repeat_end_kind") val repeatEndKind: String?,
    @SerialName("repeat_end_date") val repeatEndDate: String?,
    @SerialName("repeat_end_count") val repeatEndCount: Int?,
    val exact: Boolean,
    @SerialName("exact_downgraded") val exactDowngraded: Boolean,
    @SerialName("missed_at") val missedAt: String?,
    @SerialName("missed_dismissed_at") val missedDismissedAt: String?,
    @SerialName("logged_at") val loggedAt: String?,
    @SerialName("origin_guid") val originGuid: String,
)

@Serializable
private data class ListItemRowDto(
    val id: String,
    @SerialName("list_origin_guid") val listOriginGuid: String,
    val text: String,
    val done: Boolean,
    @SerialName("done_at") val doneAt: String? = null,
    @SerialName("sort_order") val sortOrder: Int,
    @SerialName("created_at_client") val createdAtClient: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    @SerialName("all_day") val allDay: Boolean,
    @SerialName("trigger_place_label") val triggerPlaceLabel: String? = null,
    @SerialName("repeat_kind") val repeatKind: String? = null,
    @SerialName("repeat_every") val repeatEvery: Int? = null,
    @SerialName("repeat_days_of_week") val repeatDaysOfWeek: String? = null,
    @SerialName("repeat_day") val repeatDay: Int? = null,
    @SerialName("repeat_month") val repeatMonth: Int? = null,
    @SerialName("repeat_end_kind") val repeatEndKind: String? = null,
    @SerialName("repeat_end_date") val repeatEndDate: String? = null,
    @SerialName("repeat_end_count") val repeatEndCount: Int? = null,
    val exact: Boolean,
    @SerialName("exact_downgraded") val exactDowngraded: Boolean,
    @SerialName("missed_at") val missedAt: String? = null,
    @SerialName("missed_dismissed_at") val missedDismissedAt: String? = null,
    @SerialName("logged_at") val loggedAt: String? = null,
    @SerialName("origin_guid") val originGuid: String,
) {
    fun toRemote() = RemoteListItem(
        serverId = id,
        listSyncId = listOriginGuid,
        text = text,
        done = done,
        doneAt = doneAt?.let { lastAspectsParseTs(it) },
        sortOrder = sortOrder,
        createdAt = lastAspectsParseTs(createdAtClient),
        updatedAtMs = lastAspectsParseTs(updatedAt),
        deleted = deletedAt != null,
        startsAt = startsAt?.let { lastAspectsParseTs(it) },
        endsAt = endsAt?.let { lastAspectsParseTs(it) },
        allDay = allDay,
        triggerPlaceLabel = triggerPlaceLabel,
        repeatKind = repeatKind,
        repeatEvery = repeatEvery,
        repeatDaysOfWeek = repeatDaysOfWeek,
        repeatDay = repeatDay,
        repeatMonth = repeatMonth,
        repeatEndKind = repeatEndKind,
        repeatEndDate = repeatEndDate?.let { lastAspectsParseTs(it) },
        repeatEndCount = repeatEndCount,
        exact = exact,
        exactDowngraded = exactDowngraded,
        missedAt = missedAt?.let { lastAspectsParseTs(it) },
        missedDismissedAt = missedDismissedAt?.let { lastAspectsParseTs(it) },
        loggedAt = loggedAt?.let { lastAspectsParseTs(it) },
        originGuid = originGuid,
    )
}

@Serializable
private data class LastAspectsDeletedAtDto(@SerialName("deleted_at") val deletedAt: String)

/**
 * [LastAspectsBackend]'s real implementation over Postgrest, against the four `public` tables
 * `supabase/migrations/20260902000500_aspect_last.sql` creates. Deliberately untested seam, same
 * posture as [SupabaseLedgerConfigBackend]/[SupabaseMemoryBackend] - exercising it for real needs a
 * live project. [LastAspectsBackend] is the fake-friendly interface; every branch here does
 * nothing but translate exceptions, decode DTOs, and pick `on conflict (origin_guid)` as the
 * upsert key.
 *
 * **`list_items.list_origin_guid`** carries the parent [ItemList]'s own `origin_guid` (a `text`
 * foreign key against `item_lists.origin_guid`, not `item_lists.id`) - see [LastAspectsBackend]'s
 * own class doc for why a local `listId` is never sent.
 */
class SupabaseLastAspectsBackend(private val client: SupabaseClient) : LastAspectsBackend {

    private suspend inline fun <T> translating(action: String, block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: RestException) {
        Result.failure(LastAspectsBackendException("Supabase rejected the request to $action: ${e.error}"))
    } catch (e: IOException) {
        Result.failure(LastAspectsBackendException("Couldn't reach the server to $action."))
    } catch (e: Exception) {
        Result.failure(LastAspectsBackendException("Couldn't $action: ${e.message ?: "unknown error"}"))
    }

    private suspend fun softDeleteByOriginGuid(table: String, originGuid: String, action: String): Result<Boolean> =
        translating(action) {
            client.postgrest.from(table)
                .update(LastAspectsDeletedAtDto(deletedAt = OffsetDateTime.now().toString())) {
                    select()
                    filter {
                        eq("origin_guid", originGuid)
                        filter("deleted_at", FilterOperator.IS, "null")
                    }
                }
                .decodeList<kotlinx.serialization.json.JsonElement>()
                .isNotEmpty()
        }

    // --- Goals -------------------------------------------------------------------------------

    override suspend fun fetchChangedGoalsSince(sinceMs: Long): Result<List<RemoteGoal>> =
        translating("load changed goals") {
            client.postgrest.from(GOALS_TABLE)
                .select { filter { gte("updated_at", lastAspectsTs(sinceMs)) } }
                .decodeList<GoalRowDto>()
                .map { it.toRemote() }
        }

    override suspend fun upsertGoal(originGuid: String, fields: GoalFields): Result<RemoteGoal> =
        translating("save that goal") {
            client.postgrest.from(GOALS_TABLE)
                .upsert(
                    GoalUpsertDto(
                        lineageId = fields.lineageId,
                        aspect = fields.aspect,
                        statement = fields.statement,
                        targetValue = fields.targetValue,
                        unit = fields.unit,
                        metricKey = fields.metricKey,
                        deadlineEpoch = fields.deadlineEpoch?.let { lastAspectsTs(it) },
                        status = fields.status,
                        supersedesGuid = fields.supersedesGuid,
                        closedAt = fields.closedAt?.let { lastAspectsTs(it) },
                        createdAtClient = lastAspectsTs(fields.createdAt),
                        originGuid = originGuid,
                    ),
                ) {
                    onConflict = "origin_guid"
                    select()
                }
                .decodeSingle<GoalRowDto>()
                .toRemote()
        }

    override suspend fun softDeleteGoal(originGuid: String): Result<Boolean> =
        softDeleteByOriginGuid(GOALS_TABLE, originGuid, "remove that goal")

    // --- Grocery staples -----------------------------------------------------------------------

    override suspend fun fetchChangedGroceryStaplesSince(sinceMs: Long): Result<List<RemoteGroceryStaple>> =
        translating("load changed grocery staples") {
            client.postgrest.from(GROCERY_STAPLES_TABLE)
                .select { filter { gte("updated_at", lastAspectsTs(sinceMs)) } }
                .decodeList<GroceryStapleRowDto>()
                .map { it.toRemote() }
        }

    override suspend fun upsertGroceryStaple(originGuid: String, fields: GroceryStapleFields): Result<RemoteGroceryStaple> =
        translating("save that staple") {
            client.postgrest.from(GROCERY_STAPLES_TABLE)
                .upsert(
                    GroceryStapleUpsertDto(
                        name = fields.name,
                        displayName = fields.displayName,
                        timesBought = fields.timesBought,
                        lastBoughtAt = lastAspectsTs(fields.lastBoughtAt),
                        originGuid = originGuid,
                    ),
                ) {
                    onConflict = "origin_guid"
                    select()
                }
                .decodeSingle<GroceryStapleRowDto>()
                .toRemote()
        }

    override suspend fun softDeleteGroceryStaple(originGuid: String): Result<Boolean> =
        softDeleteByOriginGuid(GROCERY_STAPLES_TABLE, originGuid, "forget that staple")

    // --- Item lists ------------------------------------------------------------------------------

    override suspend fun fetchChangedItemListsSince(sinceMs: Long): Result<List<RemoteItemList>> =
        translating("load changed lists") {
            client.postgrest.from(ITEM_LISTS_TABLE)
                .select { filter { gte("updated_at", lastAspectsTs(sinceMs)) } }
                .decodeList<ItemListRowDto>()
                .map { it.toRemote() }
        }

    override suspend fun upsertItemList(originGuid: String, fields: ItemListFields): Result<RemoteItemList> =
        translating("save that list") {
            client.postgrest.from(ITEM_LISTS_TABLE)
                .upsert(
                    ItemListUpsertDto(
                        name = fields.name,
                        tickable = fields.tickable,
                        sortOrder = fields.sortOrder,
                        lastUsedAt = lastAspectsTs(fields.lastUsedAt),
                        archived = fields.archived,
                        createdAtClient = lastAspectsTs(fields.createdAt),
                        originGuid = originGuid,
                    ),
                ) {
                    onConflict = "origin_guid"
                    select()
                }
                .decodeSingle<ItemListRowDto>()
                .toRemote()
        }

    override suspend fun softDeleteItemList(originGuid: String): Result<Boolean> =
        softDeleteByOriginGuid(ITEM_LISTS_TABLE, originGuid, "remove that list")

    // --- List items ------------------------------------------------------------------------------

    override suspend fun fetchChangedListItemsSince(sinceMs: Long): Result<List<RemoteListItem>> =
        translating("load changed list items") {
            client.postgrest.from(LIST_ITEMS_TABLE)
                .select { filter { gte("updated_at", lastAspectsTs(sinceMs)) } }
                .decodeList<ListItemRowDto>()
                .map { it.toRemote() }
        }

    override suspend fun upsertListItem(originGuid: String, fields: ListItemFields): Result<RemoteListItem> =
        translating("save that item") {
            client.postgrest.from(LIST_ITEMS_TABLE)
                .upsert(
                    ListItemUpsertDto(
                        listOriginGuid = fields.listSyncId,
                        text = fields.text,
                        done = fields.done,
                        doneAt = fields.doneAt?.let { lastAspectsTs(it) },
                        sortOrder = fields.sortOrder,
                        createdAtClient = lastAspectsTs(fields.createdAt),
                        startsAt = fields.startsAt?.let { lastAspectsTs(it) },
                        endsAt = fields.endsAt?.let { lastAspectsTs(it) },
                        allDay = fields.allDay,
                        triggerPlaceLabel = fields.triggerPlaceLabel,
                        repeatKind = fields.repeatKind,
                        repeatEvery = fields.repeatEvery,
                        repeatDaysOfWeek = fields.repeatDaysOfWeek,
                        repeatDay = fields.repeatDay,
                        repeatMonth = fields.repeatMonth,
                        repeatEndKind = fields.repeatEndKind,
                        repeatEndDate = fields.repeatEndDate?.let { lastAspectsTs(it) },
                        repeatEndCount = fields.repeatEndCount,
                        exact = fields.exact,
                        exactDowngraded = fields.exactDowngraded,
                        missedAt = fields.missedAt?.let { lastAspectsTs(it) },
                        missedDismissedAt = fields.missedDismissedAt?.let { lastAspectsTs(it) },
                        loggedAt = fields.loggedAt?.let { lastAspectsTs(it) },
                        originGuid = originGuid,
                    ),
                ) {
                    onConflict = "origin_guid"
                    select()
                }
                .decodeSingle<ListItemRowDto>()
                .toRemote()
        }

    override suspend fun softDeleteListItem(originGuid: String): Result<Boolean> =
        softDeleteByOriginGuid(LIST_ITEMS_TABLE, originGuid, "remove that item")
}
