package com.kevin.legion.backend

import com.kevin.legion.data.local.AdvisorAdvice
import com.kevin.legion.data.local.Goal
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.data.local.ProactiveRaiseRow
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The four engine homes for data that lived only on this phone (engine-brain ticket 03, ADR 0095),
 * and how a Room row becomes the engine's row and back. Pure: no Context, no Room, no network, so
 * every conversion is a plain JVM test.
 *
 * Identity is the phone's own: `origin_guid` is the Room row's `syncId` (goals, advice, pending),
 * and for a raise, which has no guid column, a name-based uuid of `(ruleId, spokenAt)` derived the
 * same way every time ([raiseGuid]). So a write-through, a retry and the backfill land on one row.
 */
enum class HomeTable(val path: String, val key: String) {
    GOALS("/api/body/goals/", "goals"),
    ADVICE("/api/advisor/advice/", "advisor_advice"),
    PENDING("/api/ledger/pending/", "pending_transactions"),
    RAISES("/api/proactive/raises/", "proactive_raises"),
}

/** What `POST <table>/batch/` said it did. [failed] counts rows it refused, each named on the wire. */
data class HomesBatchResult(
    val received: Int,
    val created: Int,
    val updated: Int,
    val unchanged: Int,
    val failed: Int,
    val engineCount: Int,
    /** Each refused row as `<origin_guid>:<field>,<field>`: its id and the fields the engine named,
     * never a value, so it can go in a log line. */
    val refused: List<String> = emptyList(),
)

/** The engine side of the homes. One implementation talks to Django; tests use a fake. */
interface HomesBackend {
    suspend fun batch(table: HomeTable, rows: List<JsonObject>): Result<HomesBatchResult>
    suspend fun put(table: HomeTable, guid: String, row: JsonObject): Result<Unit>
    suspend fun delete(table: HomeTable, guid: String): Result<Unit>
    suspend fun changedSince(table: HomeTable, sinceIso: String?): Result<List<JsonObject>>
}

object HomesRows {
    const val ORIGIN_GUID = "origin_guid"
    const val VOICE_SOURCE = "voice"

    fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

    /** A raise's engine identity: stable for the same rule spoken at the same millisecond. */
    fun raiseGuid(ruleId: String, spokenAt: Long): String =
        UUID.nameUUIDFromBytes("raise|$ruleId|$spokenAt".toByteArray(Charsets.UTF_8)).toString()

    fun goal(row: Goal, supersedesGuid: String?): JsonObject = buildJsonObject {
        put(ORIGIN_GUID, row.syncId)
        put("lineage_id", row.lineageId)
        put("aspect", row.aspect)
        put("statement", row.statement)
        put("target_value", row.targetValue)
        put("unit", row.unit)
        put("metric_key", row.metricKey)
        put("deadline_epoch", row.deadlineEpoch?.let(::iso))
        put("status", row.status)
        put("supersedes_guid", supersedesGuid)
        put("closed_at", row.closedAt?.let(::iso))
        put("created_at_client", iso(row.createdAt))
    }

    fun advice(row: AdvisorAdvice): JsonObject = buildJsonObject {
        put(ORIGIN_GUID, row.syncId)
        put("aspect", row.aspect)
        put("question_text", row.questionText)
        put("gist", row.gist)
        put("advice_text", row.adviceText)
        put("proposal_json", row.proposalJson)
        put("outcome", row.outcome)
        put("given_at", iso(row.createdAt))
        put("resolved_at", row.resolvedAt?.let(::iso))
    }

    /** Only a voice-logged row ([LedgerTransaction.pendingLoggedAt] set) is a pending transaction. */
    fun pending(row: LedgerTransaction): JsonObject? {
        val loggedAt = row.pendingLoggedAt ?: return null
        return buildJsonObject {
            put(ORIGIN_GUID, row.syncId)
            put("account_id", row.accountId)
            put("currency", row.currency.name)
            // The phone stores a pending day as UTC midnight (LiveToolbox's own convention).
            put("txn_date", Instant.ofEpochMilli(row.txnDate).atZone(ZoneOffset.UTC).toLocalDate().toString())
            put("description", row.description)
            put("amount_cents", row.amountCents)
            put("logged_at", iso(loggedAt))
        }
    }

    /** A Claude task's result text already lives on the engine with its task, kept 30 days (ADR
     * 0067), and may answer from his mail (CLAUDE.md section 7's carve-out). The raise row the
     * engine keeps names the task instead of holding a second, longer-lived copy of that text. */
    private const val CLAUDE_RESULT_RULE = "claude_task_result_"

    fun raise(row: ProactiveRaiseRow): JsonObject = buildJsonObject {
        put(ORIGIN_GUID, raiseGuid(row.ruleId, row.spokenAt))
        put("rule_id", row.ruleId)
        put("category", row.category)
        put(
            "reason",
            if (row.ruleId.startsWith(CLAUDE_RESULT_RULE)) {
                "Claude task ${row.ruleId.removePrefix(CLAUDE_RESULT_RULE)} finished; its result is with the task"
            } else {
                row.reason
            },
        )
        put("spoken_at", iso(row.spokenAt))
        put("declined", row.declined)
        put("delivery", row.delivery)
    }
}
