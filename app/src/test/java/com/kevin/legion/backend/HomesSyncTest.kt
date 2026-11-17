package com.kevin.legion.backend

import com.kevin.legion.data.local.AdvisorAdvice
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Goal
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.data.local.ProactiveRaiseRow
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Engine-brain ticket 03: [HomesSync.run] against an in-memory engine and Robolectric's Room.
 * The backfill marks a table done only after a clean pass and resumes after a failed page; the
 * pull runs before the push, so a goal the engine closed survives; a pending row the engine
 * cleared leaves Room; a queued clear is retried. Invented rows.
 */
@RunWith(RobolectricTestRunner::class)
class HomesSyncTest {
    private val context = RuntimeEnvironment.getApplication()

    /** The engine's four tables as maps of origin_guid to row, with its own clock. */
    private class FakeEngine : HomesBackend {
        val rows = HomeTable.entries.associateWith { mutableMapOf<String, JsonObject>() }
        val deletes = mutableListOf<String>()
        var failBatches = 0
        var offline = false
        private var clock = 1_800_000_000_000L

        fun stamp(): String = HomesRows.iso(clock++)

        fun write(table: HomeTable, row: JsonObject, extra: Map<String, String?> = emptyMap()) {
            val stamped = row.toMutableMap()
            stamped["updated_at"] = JsonPrimitive(stamp())
            extra.forEach { (k, v) -> stamped[k] = JsonPrimitive(v) }
            rows.getValue(table)[HomesFromEngine.guidOf(row).orEmpty()] = JsonObject(stamped)
        }

        override suspend fun batch(table: HomeTable, rows: List<JsonObject>): Result<HomesBatchResult> {
            if (offline || failBatches > 0) {
                failBatches--
                return Result.failure(IllegalStateException("offline"))
            }
            rows.forEach { write(table, it) }
            return Result.success(HomesBatchResult(rows.size, rows.size, 0, 0, 0, this.rows.getValue(table).size))
        }

        override suspend fun put(table: HomeTable, guid: String, row: JsonObject): Result<Unit> {
            if (offline) return Result.failure(IllegalStateException("offline"))
            write(table, row)
            return Result.success(Unit)
        }

        override suspend fun delete(table: HomeTable, guid: String): Result<Unit> {
            if (offline) return Result.failure(IllegalStateException("offline"))
            deletes += guid
            rows.getValue(table)[guid]?.let { write(table, it, mapOf("deleted_at" to stamp())) }
            return Result.success(Unit)
        }

        override suspend fun changedSince(table: HomeTable, sinceIso: String?): Result<List<JsonObject>> {
            if (offline) return Result.failure(IllegalStateException("offline"))
            return Result.success(
                rows.getValue(table).values.filter { row ->
                    sinceIso == null || HomesFromEngine.textOf(row, "updated_at").orEmpty() >= sinceIso
                },
            )
        }
    }

    private lateinit var engine: FakeEngine
    private val db get() = CarDatabase.getDatabase(context)

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        engine = FakeEngine()
    }

    private suspend fun seedRoom() {
        db.goalDao().insert(
            Goal(
                lineageId = 7, aspect = "bio", statement = "Swim 1 km", createdAt = 1_000L, updatedAt = 1_000L,
                syncId = "g-1",
            ),
        )
        db.advisorAdviceDao().insert(
            AdvisorAdvice(aspect = "bio", questionText = "q", gist = "g", adviceText = "a", syncId = "a-1"),
        )
        db.ledgerTransactionDao().insertAll(listOf(pending("p-1")))
        db.proactiveRaiseDao().insert(
            ProactiveRaiseRow(
                ruleId = "long_drive_break", category = "timing", reason = "two hours", spokenAt = 5_000L,
            ),
        )
    }

    private fun pending(syncId: String) = LedgerTransaction(
        sourceFile = "voice", accountId = "Test card 1111", currency = LedgerCurrency.USD, txnDate = 0L,
        description = "Bagel", amountCents = -425, lineRef = "voice:$syncId", ingestMethod = IngestMethod.UNRECONCILED,
        syncId = syncId, pendingLoggedAt = 2_000L,
    )

    @Test
    fun theFirstRunSendsEveryTableAndMarksItDone() = runBlocking {
        seedRoom()
        val reports = HomesSync.run(context, engine, now = 10_000L)
        assertTrue(reports.all { it.clean })
        HomeTable.entries.forEach { assertEquals(1, engine.rows.getValue(it).size) }
        // Done: a second run re-sends only what the reconcile covers (raises from the last 30 days).
        val again = HomesSync.run(context, engine, now = 10_000L + 40L * 24 * 60 * 60_000L)
        assertEquals(0, again.single { it.table == HomeTable.RAISES }.room)
        assertEquals(1, again.single { it.table == HomeTable.GOALS }.room)
    }

    @Test
    fun aFailedPageLeavesTheTableNotDoneAndTheNextRunResends() = runBlocking {
        seedRoom()
        engine.failBatches = 1
        val first = HomesSync.run(context, engine, now = 10_000L)
        assertFalse(first.single { it.table == HomeTable.GOALS }.clean)
        val second = HomesSync.run(context, engine, now = 10_000L + 40L * 24 * 60 * 60_000L)
        assertTrue(second.single { it.table == HomeTable.GOALS }.clean)
        // RAISES was clean the first time, so its reconcile window applies; GOALS was not and resends whole.
        assertEquals(1, engine.rows.getValue(HomeTable.GOALS).size)
    }

    @Test
    fun aGoalTheEngineClosedSurvivesThePush() = runBlocking {
        seedRoom()
        HomesSync.run(context, engine, now = 10_000L)
        val sent = engine.rows.getValue(HomeTable.GOALS).getValue("g-1")
        engine.write(HomeTable.GOALS, sent, mapOf("status" to "achieved", "closed_at" to engine.stamp()))
        HomesSync.run(context, engine, now = 20_000L)
        assertEquals("achieved", db.goalDao().getAllIncludingDeleted().single().status)
        val onEngine = engine.rows.getValue(HomeTable.GOALS).getValue("g-1")
        assertEquals("achieved", HomesFromEngine.textOf(onEngine, "status"))
    }

    @Test
    fun aPendingRowTheEngineClearedLeavesRoomAndOneItWroteArrives() = runBlocking {
        seedRoom()
        HomesSync.run(context, engine, now = 10_000L)
        engine.delete(HomeTable.PENDING, "p-1")
        engine.write(HomeTable.PENDING, HomesRows.pending(pending("p-2"))!!)
        HomesSync.run(context, engine, now = 20_000L)
        assertEquals(listOf("p-2"), db.ledgerTransactionDao().pendingRows().map { it.syncId })
    }

    @Test
    fun aClearMadeOfflineIsQueuedAndSentNextRun() = runBlocking {
        engine.offline = true
        HomesWriteThrough.pendingCleared(context, "p-9")
        assertEquals(setOf("p-9"), HomesWriteThrough.queuedClears(context))
        engine.offline = false
        HomesSync.run(context, engine, now = 10_000L)
        assertTrue(engine.deletes.contains("p-9"))
        assertTrue(HomesWriteThrough.queuedClears(context).isEmpty())
    }

    @Test
    fun anEngineGoalWithoutItsRequiredFieldsIsSkipped() {
        val broken = JsonObject(mapOf("origin_guid" to JsonPrimitive("x")))
        assertNull(HomesFromEngine.goal(broken, null))
        assertNull(HomesFromEngine.pending(broken))
    }
}
