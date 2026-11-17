package com.kevin.legion.backend

import com.kevin.legion.data.local.AdvisorAdvice
import com.kevin.legion.data.local.Goal
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.data.local.ProactiveRaiseRow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Engine-brain ticket 03: Room rows to the engine homes and back, and the backfill's paging. Invented rows. */
class HomesRowsTest {

    private val dayMs = 1_767_225_600_000L // 2026-01-01T00:00:00Z

    @Test
    fun aRaiseGuidIsStableForTheSameRuleAndInstantAndDiffersOtherwise() {
        val a = HomesRows.raiseGuid("long_drive_break", dayMs)
        assertEquals(a, HomesRows.raiseGuid("long_drive_break", dayMs))
        assertNotEquals(a, HomesRows.raiseGuid("long_drive_break", dayMs + 1))
        assertNotEquals(a, HomesRows.raiseGuid("coolant_overheat", dayMs))
    }

    @Test
    fun aGoalCarriesItsSyncIdAndSendsNullsExplicitly() {
        val goal = Goal(lineageId = -7, aspect = "bio", statement = "Swim 1 km", createdAt = dayMs, syncId = "g-1")
        val row = HomesRows.goal(goal, supersedesGuid = null)
        assertEquals(JsonPrimitive("g-1"), row["origin_guid"])
        assertEquals(JsonPrimitive(-7L), row["lineage_id"])
        assertEquals(JsonNull, row["target_value"])
        assertEquals(JsonNull, row["supersedes_guid"])
        assertEquals(JsonPrimitive("2026-01-01T00:00:00Z"), row["created_at_client"])
    }

    @Test
    fun onlyAVoiceLoggedRowIsAPendingTransactionAndItsDayIsUtc() {
        val pending = LedgerTransaction(
            sourceFile = "voice", accountId = "Test card 1111", currency = LedgerCurrency.USD,
            txnDate = dayMs, description = "Bagel", amountCents = -425, lineRef = "voice:x",
            ingestMethod = IngestMethod.UNRECONCILED, syncId = "p-1", pendingLoggedAt = dayMs + 1000,
        )
        val row = HomesRows.pending(pending)!!
        assertEquals(JsonPrimitive("2026-01-01"), row["txn_date"])
        assertEquals(JsonPrimitive(-425L), row["amount_cents"])
        assertNull(HomesRows.pending(pending.copy(pendingLoggedAt = null)))
    }

    @Test
    fun anEnginePendingRowComesBackAsTheSameRoomRow() {
        val pending = LedgerTransaction(
            sourceFile = "voice", accountId = "Test card 1111", currency = LedgerCurrency.USD,
            txnDate = dayMs, description = "Bagel", amountCents = -425, lineRef = "voice:p-1",
            ingestMethod = IngestMethod.UNRECONCILED, syncId = "p-1", pendingLoggedAt = dayMs,
        )
        val back = HomesRows.pendingFromEngine(HomesRows.pending(pending)!!)!!
        assertEquals(pending.copy(id = 0), back)
    }

    @Test
    fun anEngineGoalBecomesARoomGoalWithItsTombstone() {
        val row = buildJsonObject {
            put("id", "srv-1")
            put("origin_guid", "g-2")
            put("lineage_id", 99L)
            put("aspect", "cred")
            put("statement", "Save for a bike")
            put("status", "achieved")
            put("created_at_client", "2026-01-01T00:00:00Z")
            put("updated_at", "2026-01-02T00:00:00Z")
            put("deleted_at", "2026-01-03T00:00:00Z")
        }
        val goal = HomesRows.goalFromEngine(row, supersedesId = 5L)!!
        assertEquals("g-2", goal.syncId)
        assertEquals(99L, goal.lineageId)
        assertEquals(5L, goal.supersedesId)
        assertTrue(goal.deleted)
        assertEquals(dayMs + 86_400_000L, goal.updatedAt)
    }

    @Test
    fun adviceAndRaisesMapTheirColumns() {
        val advice = HomesRows.advice(
            AdvisorAdvice(aspect = "bio", questionText = "q", gist = "g", adviceText = "a", createdAt = dayMs, syncId = "a-1"),
        )
        assertEquals(JsonPrimitive("pending"), advice["outcome"])
        val raise = HomesRows.raise(ProactiveRaiseRow(ruleId = "r", category = "timing", reason = "why", spokenAt = dayMs))
        assertEquals(JsonPrimitive(HomesRows.raiseGuid("r", dayMs)), raise["origin_guid"])
        assertEquals(JsonPrimitive(false), raise["declined"])
    }

    // -- the backfill's paging ------------------------------------------------------------------

    private class FakeBackend(private val failOnCall: Int = -1) : HomesBackend {
        val pages = mutableListOf<Int>()

        override suspend fun batch(table: HomeTable, rows: List<JsonObject>): Result<HomesBatchResult> {
            if (pages.size == failOnCall) return Result.failure(IllegalStateException("offline"))
            pages += rows.size
            return Result.success(HomesBatchResult(rows.size, rows.size, 0, 0, 0, pages.sum()))
        }

        override suspend fun put(table: HomeTable, guid: String, row: JsonObject) = Result.success(Unit)

        override suspend fun delete(table: HomeTable, guid: String) = Result.success(Unit)

        override suspend fun changedSince(table: HomeTable, sinceIso: String?) = Result.success(emptyList<JsonObject>())
    }

    private fun rows(n: Int) = List(n) { buildJsonObject { put("origin_guid", "r-$it") } }

    @Test
    fun theBackfillSendsEveryRowInPagesAndCountsThem() = runBlocking {
        val backend = FakeBackend()
        val report = HomesSync.pushTable(backend, HomeTable.RAISES, 450, rows(450))
        assertEquals(listOf(HomesSync.PAGE, HomesSync.PAGE, 50), backend.pages)
        assertEquals(450, report.sent)
        assertEquals(450, report.engine)
        assertTrue(report.clean)
        assertTrue(report.line(first = true).contains("room=450 sent=450"))
    }

    @Test
    fun aPageTheEngineDidNotAnswerLeavesTheTableNotDone() = runBlocking {
        val report = HomesSync.pushTable(FakeBackend(failOnCall = 1), HomeTable.GOALS, 450, rows(450))
        assertEquals(HomesSync.PAGE, report.sent)
        assertFalse(report.complete)
        assertFalse(report.clean)
    }
}
