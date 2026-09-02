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

private const val CATEGORIES_TABLE = "categories"
private const val CATEGORY_RULES_TABLE = "category_rules"
private const val BUDGET_TARGETS_TABLE = "budget_targets"

private fun ledgerConfigTs(ms: Long): String = Instant.ofEpochMilli(ms).toString()
private fun ledgerConfigParseTs(s: String): Long = OffsetDateTime.parse(s).toInstant().toEpochMilli()

// ---------------------------------------------------------------------------------------------
// CATEGORIES
// ---------------------------------------------------------------------------------------------

@Serializable
private data class CategoryUpsertDto(
    val name: String,
    @SerialName("is_food_category") val isFoodCategory: Boolean,
    @SerialName("origin_guid") val originGuid: String,
)

@Serializable
private data class CategoryRowDto(
    val id: String,
    val name: String,
    @SerialName("is_food_category") val isFoodCategory: Boolean,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("origin_guid") val originGuid: String,
) {
    fun toRemote() = RemoteCategory(
        serverId = id,
        name = name,
        isFoodCategory = isFoodCategory,
        updatedAtMs = ledgerConfigParseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

// ---------------------------------------------------------------------------------------------
// CATEGORY_RULES
// ---------------------------------------------------------------------------------------------

@Serializable
private data class CategoryRuleUpsertDto(
    val category: String,
    val substring: String,
    @SerialName("created_at_client") val createdAtClient: String,
    @SerialName("origin_guid") val originGuid: String,
)

@Serializable
private data class CategoryRuleRowDto(
    val id: String,
    val category: String,
    val substring: String,
    @SerialName("created_at_client") val createdAtClient: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("origin_guid") val originGuid: String,
) {
    fun toRemote() = RemoteCategoryRule(
        serverId = id,
        category = category,
        substring = substring,
        createdAtMs = ledgerConfigParseTs(createdAtClient),
        updatedAtMs = ledgerConfigParseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

// ---------------------------------------------------------------------------------------------
// BUDGET_TARGETS
// ---------------------------------------------------------------------------------------------

@Serializable
private data class BudgetTargetUpsertDto(
    val category: String,
    val currency: String,
    @SerialName("amount_cents") val amountCents: Long,
    @SerialName("effective_from_month") val effectiveFromMonth: String,
    @SerialName("origin_guid") val originGuid: String,
)

@Serializable
private data class BudgetTargetRowDto(
    val id: String,
    val category: String,
    val currency: String,
    @SerialName("amount_cents") val amountCents: Long,
    @SerialName("effective_from_month") val effectiveFromMonth: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("origin_guid") val originGuid: String,
) {
    fun toRemote() = RemoteBudgetTarget(
        serverId = id,
        category = category,
        currency = currency,
        amountCents = amountCents,
        effectiveFromMonthEpochMs = OffsetDateTime.parse(effectiveFromMonth + "T00:00:00Z").toInstant().toEpochMilli(),
        updatedAtMs = ledgerConfigParseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

@Serializable
private data class LedgerConfigDeletedAtDto(@SerialName("deleted_at") val deletedAt: String)

/**
 * [LedgerConfigBackend]'s real implementation over Postgrest, against the three `public` tables
 * `supabase/migrations/20260902000400_aspect_ledger_config.sql` creates. This is the deliberately
 * untested seam, same posture as [SupabaseMemoryBackend]/[SupabaseBodyBackend] - exercising it for
 * real needs a live project. [LedgerConfigBackend] is the fake-friendly interface; every branch
 * here does nothing but translate exceptions, decode DTOs, and pick `on conflict (origin_guid)` as
 * the upsert key.
 *
 * [effectiveFromMonth]/[BudgetTargetRowDto.effectiveFromMonth] round-trip as a bare `date`
 * (`YYYY-MM-DD`), unlike every timestamptz column elsewhere in this file - matching the migration's
 * own `effective_from_month date` column, month-start UTC by convention on the phone side.
 */
class SupabaseLedgerConfigBackend(private val client: SupabaseClient) : LedgerConfigBackend {

    private suspend inline fun <T> translating(action: String, block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: RestException) {
        Result.failure(LedgerConfigBackendException("Supabase rejected the request to $action: ${e.error}"))
    } catch (e: IOException) {
        Result.failure(LedgerConfigBackendException("Couldn't reach the server to $action."))
    } catch (e: Exception) {
        Result.failure(LedgerConfigBackendException("Couldn't $action: ${e.message ?: "unknown error"}"))
    }

    private suspend fun softDeleteByOriginGuid(table: String, originGuid: String, action: String): Result<Boolean> =
        translating(action) {
            client.postgrest.from(table)
                .update(LedgerConfigDeletedAtDto(deletedAt = OffsetDateTime.now().toString())) {
                    select()
                    filter {
                        eq("origin_guid", originGuid)
                        filter("deleted_at", FilterOperator.IS, "null")
                    }
                }
                .decodeList<kotlinx.serialization.json.JsonElement>()
                .isNotEmpty()
        }

    // --- Categories ------------------------------------------------------------------------------

    override suspend fun fetchChangedCategoriesSince(sinceMs: Long): Result<List<RemoteCategory>> =
        translating("load changed categories") {
            client.postgrest.from(CATEGORIES_TABLE)
                .select { filter { gte("updated_at", ledgerConfigTs(sinceMs)) } }
                .decodeList<CategoryRowDto>()
                .map { it.toRemote() }
        }

    override suspend fun upsertCategory(originGuid: String, fields: CategoryFields): Result<RemoteCategory> =
        translating("save that category") {
            client.postgrest.from(CATEGORIES_TABLE)
                .upsert(
                    CategoryUpsertDto(
                        name = fields.name,
                        isFoodCategory = fields.isFoodCategory,
                        originGuid = originGuid,
                    ),
                ) {
                    onConflict = "origin_guid"
                    select()
                }
                .decodeSingle<CategoryRowDto>()
                .toRemote()
        }

    override suspend fun softDeleteCategory(originGuid: String): Result<Boolean> =
        softDeleteByOriginGuid(CATEGORIES_TABLE, originGuid, "remove that category")

    // --- Category rules --------------------------------------------------------------------------

    override suspend fun fetchChangedCategoryRulesSince(sinceMs: Long): Result<List<RemoteCategoryRule>> =
        translating("load changed category rules") {
            client.postgrest.from(CATEGORY_RULES_TABLE)
                .select { filter { gte("updated_at", ledgerConfigTs(sinceMs)) } }
                .decodeList<CategoryRuleRowDto>()
                .map { it.toRemote() }
        }

    override suspend fun upsertCategoryRule(originGuid: String, fields: CategoryRuleFields): Result<RemoteCategoryRule> =
        translating("save that category rule") {
            client.postgrest.from(CATEGORY_RULES_TABLE)
                .upsert(
                    CategoryRuleUpsertDto(
                        category = fields.category,
                        substring = fields.substring,
                        createdAtClient = ledgerConfigTs(fields.createdAtMs),
                        originGuid = originGuid,
                    ),
                ) {
                    onConflict = "origin_guid"
                    select()
                }
                .decodeSingle<CategoryRuleRowDto>()
                .toRemote()
        }

    override suspend fun softDeleteCategoryRule(originGuid: String): Result<Boolean> =
        softDeleteByOriginGuid(CATEGORY_RULES_TABLE, originGuid, "remove that category rule")

    // --- Budget targets --------------------------------------------------------------------------

    override suspend fun fetchChangedBudgetTargetsSince(sinceMs: Long): Result<List<RemoteBudgetTarget>> =
        translating("load changed budget targets") {
            client.postgrest.from(BUDGET_TARGETS_TABLE)
                .select { filter { gte("updated_at", ledgerConfigTs(sinceMs)) } }
                .decodeList<BudgetTargetRowDto>()
                .map { it.toRemote() }
        }

    override suspend fun upsertBudgetTarget(originGuid: String, fields: BudgetTargetFields): Result<RemoteBudgetTarget> =
        translating("save that budget target") {
            client.postgrest.from(BUDGET_TARGETS_TABLE)
                .upsert(
                    BudgetTargetUpsertDto(
                        category = fields.category,
                        currency = fields.currency,
                        amountCents = fields.amountCents,
                        effectiveFromMonth = Instant.ofEpochMilli(fields.effectiveFromMonthEpochMs).toString().substring(0, 10),
                        originGuid = originGuid,
                    ),
                ) {
                    onConflict = "origin_guid"
                    select()
                }
                .decodeSingle<BudgetTargetRowDto>()
                .toRemote()
        }

    override suspend fun softDeleteBudgetTarget(originGuid: String): Result<Boolean> =
        softDeleteByOriginGuid(BUDGET_TARGETS_TABLE, originGuid, "remove that budget target")
}
