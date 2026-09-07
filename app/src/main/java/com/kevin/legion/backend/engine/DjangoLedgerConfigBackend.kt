package com.kevin.legion.backend.engine

import com.kevin.legion.backend.BudgetTargetFields
import com.kevin.legion.backend.CategoryFields
import com.kevin.legion.backend.CategoryRuleFields
import com.kevin.legion.backend.LedgerConfigBackend
import com.kevin.legion.backend.RemoteBudgetTarget
import com.kevin.legion.backend.RemoteCategory
import com.kevin.legion.backend.RemoteCategoryRule
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val LEDGER_ROOT = "/api/ledger/"

internal fun ledgerTs(ms: Long): String = Instant.ofEpochMilli(ms).toString()
internal fun ledgerParseTs(s: String): Long = OffsetDateTime.parse(s).toInstant().toEpochMilli()

/** A DATE column (`budget_targets.effective_from_month`), which DRF renders as a bare
 * `"2026-08-01"`. UTC midnight in both directions, the same convention
 * `SupabaseLedgerConfigBackend`'s own month handling uses, so a row round-trips between the two
 * transports without shifting a day. */
private fun ledgerDate(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()

internal fun ledgerParseDate(s: String): Long =
    LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

// ---------------------------------------------------------------------------------------------
// CATEGORIES
// ---------------------------------------------------------------------------------------------

/**
 * One `public.categories` row as `server/api/ledger.py`'s `CategorySerializer` renders it, read off
 * `server/openapi.yaml`'s `Category` schema rather than guessed: `id`, `name`, `is_food_category`,
 * `provenance`, `created_at`, `updated_at`, `deleted_at`, `origin_guid`.
 *
 * `provenance` and `created_at` are on the wire and dropped by [engineSyncedJson]'s
 * `ignoreUnknownKeys`, because [RemoteCategory] carries neither - exactly as
 * `SupabaseLedgerConfigBackend`'s own row DTO omits them.
 */
@Serializable
private data class DjangoCategoryRow(
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
        updatedAtMs = ledgerParseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

/**
 * The `PUT /api/ledger/categories/<origin_guid>/` body.
 *
 * **`origin_guid` is deliberately absent from every write DTO in this file**, for the reason
 * [DjangoBodyBackend]'s own write DTOs state: `SyncedModelViewSet.upsert` sets
 * `data[self.identity_field] = identity` from the URL before validating, so a guid in the body too
 * would be a second copy of a value that can only disagree with the first. `DjangoPlaceWrite` makes
 * the opposite call for `label` and says why there; the difference is that a label is human text a
 * percent-encoding bug could mangle, and a guid is not.
 */
@Serializable
private data class DjangoCategoryWrite(
    val name: String,
    @SerialName("is_food_category") val isFoodCategory: Boolean,
)

// ---------------------------------------------------------------------------------------------
// CATEGORY RULES
// ---------------------------------------------------------------------------------------------

/**
 * One `public.category_rules` row. **[createdAtClient] is `created_at_client`, NOT `created_at`** -
 * the server keeps both and they mean different things: `created_at` is when the row reached
 * Postgres, `created_at_client` is when the rule was made on a phone.
 * [RemoteCategoryRule.createdAtMs] is the second one (`SupabaseLedgerConfigBackend` maps it the
 * same way), so reading the wrong column here would silently re-date every rule to its migration
 * instant.
 */
@Serializable
private data class DjangoCategoryRuleRow(
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
        createdAtMs = ledgerParseTs(createdAtClient),
        updatedAtMs = ledgerParseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

@Serializable
private data class DjangoCategoryRuleWrite(
    val category: String,
    val substring: String,
    @SerialName("created_at_client") val createdAtClient: String,
)

// ---------------------------------------------------------------------------------------------
// BUDGET TARGETS
// ---------------------------------------------------------------------------------------------

/** One `public.budget_targets` row. [amountCents] is an integer number of cents on the wire and a
 * `Long` here, never a `Double` - CLAUDE.md section 4 rule 3, which the server states for itself in
 * `api/ledger.py`'s own module doc. */
@Serializable
private data class DjangoBudgetTargetRow(
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
        effectiveFromMonthEpochMs = ledgerParseDate(effectiveFromMonth),
        updatedAtMs = ledgerParseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

@Serializable
private data class DjangoBudgetTargetWrite(
    val category: String,
    val currency: String,
    @SerialName("amount_cents") val amountCents: Long,
    @SerialName("effective_from_month") val effectiveFromMonth: String,
)

/**
 * [LedgerConfigBackend] over the household Django engine (`server/api/ledger.py`'s three writable
 * viewsets on `server/api/synced.py`'s generic shape). Interchangeable with
 * [com.kevin.legion.backend.SupabaseLedgerConfigBackend] by construction: same interface, same
 * `Remote*` types out, so `LedgerConfigSync`/`LedgerConfigOutboxDrain`/`LedgerConfigBackfill`
 * cannot tell which one they hold.
 *
 * **These three tables are the ledger aspect's WRITABLE half, and the split is the server's rather
 * than this class's.** `categories`, `category_rules` and `budget_targets` are ordinary synced
 * tables with the full four routes; `statements`, `ledger_transactions` and `ingested_files` are
 * gated and answer 405 naming the gate endpoint for any write ([DjangoLedgerBackend] is the
 * read-only client for those). Nothing here can write a gated row even by mistake, because no route
 * exists to try it against.
 *
 * **An upsert here does NOT revive a tombstone** (`put_revives_tombstone` is `True` only for
 * places), matching `SupabaseLedgerConfigBackend`'s own upsert DTOs, which carry no `deleted_at` and
 * so leave an existing tombstone alone.
 */
class DjangoLedgerConfigBackend(http: EngineHttp) : LedgerConfigBackend {

    private val categories = EngineSyncedTable(
        http = http,
        path = LEDGER_ROOT + "categories/",
        rowSerializer = DjangoCategoryRow.serializer(),
        idOf = { it.id },
    )

    private val categoryRules = EngineSyncedTable(
        http = http,
        path = LEDGER_ROOT + "category_rules/",
        rowSerializer = DjangoCategoryRuleRow.serializer(),
        idOf = { it.id },
    )

    private val budgetTargets = EngineSyncedTable(
        http = http,
        path = LEDGER_ROOT + "budget_targets/",
        rowSerializer = DjangoBudgetTargetRow.serializer(),
        idOf = { it.id },
    )

    override suspend fun fetchChangedCategoriesSince(sinceMs: Long): Result<List<RemoteCategory>> =
        translatingEngineCall("load your spending categories") {
            // Tombstones INCLUDED - `?since=` and never `?active=1`, per LedgerConfigBackend's own
            // interface doc ("return tombstones too, never active-only").
            categories.fetchChangedSince(ledgerTs(sinceMs)).map { it.toRemote() }
        }

    override suspend fun upsertCategory(originGuid: String, fields: CategoryFields): Result<RemoteCategory> =
        translatingEngineCall("save that category") {
            val body = engineSyncedJson.encodeToString(
                DjangoCategoryWrite.serializer(),
                DjangoCategoryWrite(name = fields.name, isFoodCategory = fields.isFoodCategory),
            )
            categories.put(originGuid, body).toRemote()
        }

    override suspend fun softDeleteCategory(originGuid: String): Result<Boolean> =
        deletingEngineRow("remove that category") { categories.deleteRow(originGuid) }

    override suspend fun fetchChangedCategoryRulesSince(sinceMs: Long): Result<List<RemoteCategoryRule>> =
        translatingEngineCall("load your categorisation rules") {
            categoryRules.fetchChangedSince(ledgerTs(sinceMs)).map { it.toRemote() }
        }

    override suspend fun upsertCategoryRule(
        originGuid: String,
        fields: CategoryRuleFields,
    ): Result<RemoteCategoryRule> =
        translatingEngineCall("save that categorisation rule") {
            val body = engineSyncedJson.encodeToString(
                DjangoCategoryRuleWrite.serializer(),
                DjangoCategoryRuleWrite(
                    category = fields.category,
                    substring = fields.substring,
                    createdAtClient = ledgerTs(fields.createdAtMs),
                ),
            )
            categoryRules.put(originGuid, body).toRemote()
        }

    override suspend fun softDeleteCategoryRule(originGuid: String): Result<Boolean> =
        deletingEngineRow("remove that categorisation rule") { categoryRules.deleteRow(originGuid) }

    override suspend fun fetchChangedBudgetTargetsSince(sinceMs: Long): Result<List<RemoteBudgetTarget>> =
        translatingEngineCall("load your budgets") {
            budgetTargets.fetchChangedSince(ledgerTs(sinceMs)).map { it.toRemote() }
        }

    override suspend fun upsertBudgetTarget(
        originGuid: String,
        fields: BudgetTargetFields,
    ): Result<RemoteBudgetTarget> =
        translatingEngineCall("save that budget") {
            val body = engineSyncedJson.encodeToString(
                DjangoBudgetTargetWrite.serializer(),
                DjangoBudgetTargetWrite(
                    category = fields.category,
                    currency = fields.currency,
                    amountCents = fields.amountCents,
                    effectiveFromMonth = ledgerDate(fields.effectiveFromMonthEpochMs),
                ),
            )
            budgetTargets.put(originGuid, body).toRemote()
        }

    override suspend fun softDeleteBudgetTarget(originGuid: String): Result<Boolean> =
        deletingEngineRow("remove that budget") { budgetTargets.deleteRow(originGuid) }
}
