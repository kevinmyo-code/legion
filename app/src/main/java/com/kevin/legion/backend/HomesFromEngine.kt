package com.kevin.legion.backend

import com.kevin.legion.data.local.Goal
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * An engine home's row back into the Room row the phone itself would have written (engine-brain
 * ticket 03): the other half of [HomesRows]. Pure, so a plain JVM test covers it. A row missing a
 * required field is skipped (null), never filled with a guess.
 */
object HomesFromEngine {
    fun parseMs(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    fun guidOf(row: JsonObject): String? = row.text(HomesRows.ORIGIN_GUID)

    fun textOf(row: JsonObject, key: String): String? = row.text(key)

    fun isDeleted(row: JsonObject): Boolean = row.text("deleted_at") != null

    fun updatedAtMs(row: JsonObject): Long = row.text("updated_at")?.let(::parseMs) ?: 0L

    /** An engine goal as a Room row, or null when a required field is missing. [supersedesId] is
     * resolved by the caller from its own rows. */
    fun goal(row: JsonObject, supersedesId: Long?): Goal? = runCatching {
        Goal(
            lineageId = requireNotNull(row.prim("lineage_id")?.longOrNull),
            aspect = requireNotNull(row.text("aspect")),
            statement = requireNotNull(row.text("statement")),
            targetValue = row.prim("target_value")?.doubleOrNull,
            unit = row.text("unit"),
            metricKey = row.text("metric_key"),
            deadlineEpoch = row.text("deadline_epoch")?.let(::parseMs),
            status = row.text("status") ?: "active",
            supersedesId = supersedesId,
            closedAt = row.text("closed_at")?.let(::parseMs),
            createdAt = row.text("created_at_client")?.let(::parseMs) ?: updatedAtMs(row),
            updatedAt = updatedAtMs(row),
            syncId = requireNotNull(guidOf(row)),
            serverId = row.text("id"),
            deleted = isDeleted(row),
        )
    }.getOrNull()

    /** An engine pending transaction as the Room row the phone's own `log_pending_transaction`
     * makes, or null when a required field is missing. */
    fun pending(row: JsonObject): LedgerTransaction? = runCatching {
        val guid = requireNotNull(guidOf(row))
        val currency = requireNotNull(row.text("currency"))
        LedgerTransaction(
            sourceFile = HomesRows.VOICE_SOURCE,
            accountId = requireNotNull(row.text("account_id")),
            currency = LedgerCurrency.entries.first { it.name == currency },
            txnDate = LocalDate.parse(requireNotNull(row.text("txn_date")))
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            description = requireNotNull(row.text("description")),
            amountCents = requireNotNull(row.prim("amount_cents")?.longOrNull),
            balanceCents = null,
            lineRef = "voice:$guid",
            ingestMethod = IngestMethod.UNRECONCILED,
            syncId = guid,
            sourceFileId = null,
            pendingLoggedAt = row.text("logged_at")?.let(::parseMs) ?: updatedAtMs(row),
        )
    }.getOrNull()

    private fun JsonObject.prim(key: String): JsonPrimitive? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }

    private fun JsonObject.text(key: String): String? = prim(key)?.contentOrNull
}
