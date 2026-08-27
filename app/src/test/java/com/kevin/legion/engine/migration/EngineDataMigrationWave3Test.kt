package com.kevin.legion.engine.migration

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.engine.PayloadCodec
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.ledger.LedgerAspectSeeder
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
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
 * Robolectric coverage for [EngineDataMigrationWave3] and [LedgerAspectSeeder] - Wave 3's own owed
 * tests (`.scratch/aspect-engine/issues/21-migration-waves.md` point 5, plus this wave's own extra
 * obligation: rule 7's transience guarantee must survive the copy - see
 * [EngineDataMigrationWave3]'s class doc). Same shape as [EngineDataMigrationWave1Test]/
 * [EngineDataMigrationWave2Test].
 */
@RunWith(RobolectricTestRunner::class)
class EngineDataMigrationWave3Test {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        context.getSharedPreferences("engine_migration_wave3", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun txn(
        sourceFile: String = "dbs_2026_08.pdf",
        accountId: String = "DBS-1234",
        currency: LedgerCurrency = LedgerCurrency.SGD,
        txnDate: Long = System.currentTimeMillis(),
        description: String = "NTUC FAIRPRICE",
        amountCents: Long = -4599L,
        balanceCents: Long? = 100_000L,
        lineRef: String = "L1",
        ingestMethod: IngestMethod = IngestMethod.DETERMINISTIC,
        sourceFileId: String? = "file-1",
        category: String? = null,
        categoryPending: Boolean = false,
        pendingLoggedAt: Long? = null,
    ) = LedgerTransaction(
        sourceFile = sourceFile, accountId = accountId, currency = currency, txnDate = txnDate,
        description = description, amountCents = amountCents, balanceCents = balanceCents,
        lineRef = lineRef, ingestMethod = ingestMethod, sourceFileId = sourceFileId,
        category = category, categoryPending = categoryPending, pendingLoggedAt = pendingLoggedAt,
    )

    @After
    fun drainRoomInvalidationTracker() {
        // A DAO write anywhere in this test can schedule a Room InvalidationTracker refresh
        // on ArchTaskExecutor's disk-IO pool. If that refresh is still queued or running when
        // this test method returns, it races Robolectric's own per-@Test-METHOD native SQLite
        // reset and throws "Illegal connection pointer" on a background thread - uncaught, and
        // blamed by kotlinx-coroutines-test on whatever runTest starts next, not on this class.
        // Draining here, before Robolectric ever gets a chance to reset, is the fix - see
        // RoomTestReset's own class doc comment
        // (.scratch/hardening/issues/13-the-suite-is-green-by-luck.md) for the full account.
        RoomTestReset.drainArchDiskIoPool()
    }


    // -------------------------------------------------------------------------- seeder idempotence

    @Test
    fun `seeder idempotence - a second ensureSeeded call returns the same aspect, record type, and field ids`() = runBlocking {
        val first = LedgerAspectSeeder.ensureSeeded(context)
        val second = LedgerAspectSeeder.ensureSeeded(context)

        assertEquals(first.aspectId, second.aspectId)
        assertEquals(first.transaction.recordTypeId, second.transaction.recordTypeId)
        assertEquals(first.transaction.fieldIds, second.transaction.fieldIds)
        assertEquals(1, db.aspectDao().listActive().count { it.name == LedgerAspectSeeder.ASPECT_NAME })
        assertEquals(1, db.recordTypeDao().listByAspect(first.aspectId).size)
    }

    // ------------------------------------------------------------------------------- count-exact

    @Test
    fun `count-exact - every LedgerTransaction produces exactly one engine record`() = runBlocking {
        db.ledgerTransactionDao().insertAll(
            listOf(
                txn(accountId = "A", lineRef = "1"),
                txn(accountId = "A", lineRef = "2"),
                txn(accountId = "B", lineRef = "3"),
            ),
        )

        val result = EngineDataMigrationWave3.copyLedgerIfNeeded(context)

        assertEquals(3, result.copied)
        val schema = LedgerAspectSeeder.ensureSeeded(context)
        assertEquals(3, db.engineRecordDao().activeByRecordType(schema.transaction.recordTypeId).size)
    }

    // ----------------------------------------------------------------------------- content-faithful

    @Test
    fun `content-faithful - every field, cents exact, dates exact, category and pending state exact`() = runBlocking {
        val txnDate = System.currentTimeMillis() - 86_400_000L
        val pendingAt = System.currentTimeMillis() - 3600_000L
        db.ledgerTransactionDao().insertAll(
            listOf(
                txn(
                    sourceFile = "bofa_card.pdf", accountId = "BOFA-5678", currency = LedgerCurrency.USD,
                    txnDate = txnDate, description = "AMAZON.COM", amountCents = -12_34L, balanceCents = 5_000_00L,
                    lineRef = "row-9", ingestMethod = IngestMethod.LLM_RECONCILED, sourceFileId = "drive-file-42",
                    category = "Shopping", categoryPending = true, pendingLoggedAt = pendingAt,
                ),
            ),
        )

        EngineDataMigrationWave3.copyLedgerIfNeeded(context)

        val schema = LedgerAspectSeeder.ensureSeeded(context)
        val record = db.engineRecordDao().activeByRecordType(schema.transaction.recordTypeId).single()
        val payload = JSONObject(record.payload)
        val f = schema.transaction.fieldIds

        assertEquals("bofa_card.pdf", PayloadCodec.readString(payload, f.getValue(LedgerAspectSeeder.FIELD_SOURCE_FILE)))
        assertEquals("BOFA-5678", PayloadCodec.readString(payload, f.getValue(LedgerAspectSeeder.FIELD_ACCOUNT_ID)))
        assertEquals("USD", PayloadCodec.readString(payload, f.getValue(LedgerAspectSeeder.FIELD_CURRENCY)))
        assertEquals(txnDate, PayloadCodec.readLong(payload, f.getValue(LedgerAspectSeeder.FIELD_TXN_DATE)))
        assertEquals("AMAZON.COM", PayloadCodec.readString(payload, f.getValue(LedgerAspectSeeder.FIELD_DESCRIPTION)))
        assertEquals(-12_34L, PayloadCodec.readLong(payload, f.getValue(LedgerAspectSeeder.FIELD_AMOUNT)))
        assertEquals(5_000_00L, PayloadCodec.readLong(payload, f.getValue(LedgerAspectSeeder.FIELD_BALANCE)))
        assertEquals("row-9", PayloadCodec.readString(payload, f.getValue(LedgerAspectSeeder.FIELD_LINE_REF)))
        assertEquals("drive-file-42", PayloadCodec.readString(payload, f.getValue(LedgerAspectSeeder.FIELD_SOURCE_FILE_ID)))
        assertEquals("Shopping", PayloadCodec.readString(payload, f.getValue(LedgerAspectSeeder.FIELD_CATEGORY)))
        assertTrue(payload.getBoolean(PayloadCodec.key(f.getValue(LedgerAspectSeeder.FIELD_CATEGORY_PENDING))))
        assertEquals(pendingAt, PayloadCodec.readLong(payload, f.getValue(LedgerAspectSeeder.FIELD_PENDING_LOGGED_AT)))

        assertEquals(-12_34L, record.amountCents) // primaryAmountFieldId promotion, exact cents, sign preserved
        assertEquals(RecordProvenance.LLM_RECONCILED, record.provenance)
        assertEquals(txnDate, record.createdAt) // seeded from txnDate, the closest available anchor
        assertEquals(txnDate, record.updatedAt)
    }

    @Test
    fun `a transaction with no balance and no category migrates those fields as absent, not zero or empty string`() = runBlocking {
        db.ledgerTransactionDao().insertAll(listOf(txn(balanceCents = null, category = null)))

        EngineDataMigrationWave3.copyLedgerIfNeeded(context)

        val schema = LedgerAspectSeeder.ensureSeeded(context)
        val record = db.engineRecordDao().activeByRecordType(schema.transaction.recordTypeId).single()
        val payload = JSONObject(record.payload)
        assertNull(PayloadCodec.readLong(payload, schema.transaction.fieldIds.getValue(LedgerAspectSeeder.FIELD_BALANCE)))
        assertNull(PayloadCodec.readString(payload, schema.transaction.fieldIds.getValue(LedgerAspectSeeder.FIELD_CATEGORY)))
    }

    // ------------------------------------------------------------------------------ provenance mapping

    @Test
    fun `provenance mapping - all three ingestMethod values map one-to-one, UNRECONCILED stays UNRECONCILED`() = runBlocking {
        db.ledgerTransactionDao().insertAll(
            listOf(
                txn(lineRef = "det", ingestMethod = IngestMethod.DETERMINISTIC),
                txn(lineRef = "llm", ingestMethod = IngestMethod.LLM_RECONCILED),
                txn(lineRef = "unr", ingestMethod = IngestMethod.UNRECONCILED),
            ),
        )

        EngineDataMigrationWave3.copyLedgerIfNeeded(context)

        val schema = LedgerAspectSeeder.ensureSeeded(context)
        val records = db.engineRecordDao().activeByRecordType(schema.transaction.recordTypeId)
        fun provenanceFor(lineRef: String) = records.single {
            PayloadCodec.readString(JSONObject(it.payload), schema.transaction.fieldIds.getValue(LedgerAspectSeeder.FIELD_LINE_REF)) == lineRef
        }.provenance

        assertEquals(RecordProvenance.DETERMINISTIC, provenanceFor("det"))
        assertEquals(RecordProvenance.LLM_RECONCILED, provenanceFor("llm"))
        assertEquals(RecordProvenance.UNRECONCILED, provenanceFor("unr")) // never upgraded, never collapsed
        assertTrue(records.none { it.provenance == RecordProvenance.USER }) // never invented
    }

    // ----------------------------------------------------------------------------------- idempotence

    @Test
    fun `copier idempotence - a second run copies nothing and leaves counts unchanged`() = runBlocking {
        db.ledgerTransactionDao().insertAll(listOf(txn(lineRef = "1"), txn(lineRef = "2")))

        val first = EngineDataMigrationWave3.copyLedgerIfNeeded(context)
        val second = EngineDataMigrationWave3.copyLedgerIfNeeded(context)

        assertEquals(2, first.copied)
        assertFalse(first.alreadyDone)
        assertEquals(0, second.copied)
        assertTrue(second.alreadyDone)

        val schema = LedgerAspectSeeder.ensureSeeded(context)
        assertEquals(2, db.engineRecordDao().activeByRecordType(schema.transaction.recordTypeId).size)
    }

    @Test
    fun `per-row guid check is also idempotent even without the completion flag`() = runBlocking {
        db.ledgerTransactionDao().insertAll(listOf(txn(lineRef = "1")))
        EngineDataMigrationWave3.copyLedgerIfNeeded(context)
        // Simulate a crash after the loop wrote its row but before the flag was set.
        context.getSharedPreferences("engine_migration_wave3", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("transactions_completed_v1", false).commit()

        val retry = EngineDataMigrationWave3.copyLedgerIfNeeded(context)

        assertEquals(0, retry.copied)
        val schema = LedgerAspectSeeder.ensureSeeded(context)
        assertEquals(1, db.engineRecordDao().activeByRecordType(schema.transaction.recordTypeId).size)
    }

    // ------------------------------------------------------------------------------- failure paths

    /**
     * `Transaction` has no [FieldType.REFERENCE] field by design (see the carve doc's field-mapping
     * table - `sourceFileId` is deliberately plain TEXT, since `ingested_files` is not a migrated
     * record type), which is also the ONLY validated failure trigger [RecordStore.create] has for a
     * schema with no required-field enforcement (traced: `RecordStore.create` fails only on a
     * missing record type - unreachable here, since [LedgerAspectSeeder.ensureSeeded] unconditionally
     * self-heals a missing record type at the very top of every [EngineDataMigrationWave3.copyLedgerIfNeeded]
     * call, before any row is copied - or a failed [FieldType.REFERENCE] validation). This
     * corrupts the already-seeded `sourceFileId` field's TYPE to `REFERENCE` for exactly one test,
     * the same "test seam" [EngineDataMigrationWave2Test]'s own corruption helpers use, adapted for
     * a schema with no naturally-occurring reference field to corrupt instead: a non-null
     * `sourceFileId` value is a `String`, and `RecordStore.validateReferences`'s `(raw as? Number)`
     * cast fails immediately for a REFERENCE-typed field holding a `String`, before even reaching
     * the (irrelevant, since nothing points at it) reference-config check.
     */
    private suspend fun corruptSourceFileIdField(schema: LedgerAspectSeeder.Schema) {
        val field = db.fieldDefDao().forRecordType(schema.transaction.recordTypeId)
            .single { it.id == schema.transaction.fieldIds.getValue(LedgerAspectSeeder.FIELD_SOURCE_FILE_ID) }
        db.fieldDefDao().update(field.copy(type = FieldType.REFERENCE))
    }

    private suspend fun restoreSourceFileIdField(schema: LedgerAspectSeeder.Schema) {
        val field = db.fieldDefDao().forRecordType(schema.transaction.recordTypeId)
            .single { it.id == schema.transaction.fieldIds.getValue(LedgerAspectSeeder.FIELD_SOURCE_FILE_ID) }
        db.fieldDefDao().update(field.copy(type = FieldType.TEXT))
    }

    @Test
    fun `a forced create failure leaves the completion flag UNSET, and the row is retried on the next run`() = runBlocking {
        db.ledgerTransactionDao().insertAll(listOf(txn(lineRef = "1", sourceFileId = "file-1")))
        val schema = LedgerAspectSeeder.ensureSeeded(context)
        corruptSourceFileIdField(schema)

        val first = EngineDataMigrationWave3.copyLedgerIfNeeded(context)

        assertEquals(0, first.copied)
        assertFalse(
            "the completion flag must stay clear when a create failed this pass",
            context.getSharedPreferences("engine_migration_wave3", android.content.Context.MODE_PRIVATE)
                .getBoolean("transactions_completed_v1", false),
        )
        assertEquals(0, db.engineRecordDao().activeByRecordType(schema.transaction.recordTypeId).size)

        restoreSourceFileIdField(schema)
        val retry = EngineDataMigrationWave3.copyLedgerIfNeeded(context)

        assertEquals(1, retry.copied)
        assertEquals(1, db.engineRecordDao().activeByRecordType(schema.transaction.recordTypeId).size)
    }

    @Test
    fun `failure-path mirror of idempotence - partial failure leaves the flag clear, second run completes the copy, count-exact`() = runBlocking {
        db.ledgerTransactionDao().insertAll(
            listOf(
                txn(lineRef = "1", sourceFileId = "file-1"),
                txn(lineRef = "2", sourceFileId = "file-2"),
                txn(lineRef = "3", sourceFileId = null), // no reference-shaped value - unaffected by the corruption
            ),
        )
        val schema = LedgerAspectSeeder.ensureSeeded(context)
        corruptSourceFileIdField(schema)

        val first = EngineDataMigrationWave3.copyLedgerIfNeeded(context)
        assertEquals(1, first.copied) // only the null-sourceFileId row survives validateReferences
        assertFalse(first.alreadyDone)
        assertFalse(
            context.getSharedPreferences("engine_migration_wave3", android.content.Context.MODE_PRIVATE)
                .getBoolean("transactions_completed_v1", false),
        )

        restoreSourceFileIdField(schema)
        val second = EngineDataMigrationWave3.copyLedgerIfNeeded(context)

        assertEquals(2, second.copied) // the two previously-failed rows land now
        assertFalse(second.alreadyDone)
        assertEquals(3, db.engineRecordDao().activeByRecordType(schema.transaction.recordTypeId).size)

        val third = EngineDataMigrationWave3.copyLedgerIfNeeded(context)
        assertTrue("a genuinely complete pass must now set the flag and fast-path every later call", third.alreadyDone)
    }

    // --------------------------------------------------------------- rule 7 transience, via catchUpOnce
    //
    // Cutover 3 (`docs/architecture/cutover3-2026-08-24.md`) moved rule 7's ONGOING supersession
    // into `ledger/IngestPipeline.commit`'s own engine-side transaction - see that object's doc
    // comment. `EngineDataMigrationWave3.copyLedgerIfNeeded` no longer runs a reconciliation pass on
    // every call (Wave 3's original per-call behaviour, retired); [EngineDataMigrationWave3.catchUpOnce]
    // is now the ONE place that reconciliation still runs, for exactly the historical gap named in
    // this object's own class doc: legacy-table supersession that happened BEFORE this cutover
    // branch's `IngestPipeline` took over. These three tests exercise `catchUpOnce` directly instead
    // of asserting on `copyLedgerIfNeeded`'s own (now narrower) `Result` shape.

    @Test
    fun `catchUpOnce trashes an engine-side UNRECONCILED mirror whose legacy row was already superseded`() = runBlocking {
        val accountId = "BOFA-CARD-9999"
        val provisional = txn(
            accountId = accountId, currency = LedgerCurrency.USD, txnDate = 1_000_000L,
            lineRef = "provisional-1", ingestMethod = IngestMethod.UNRECONCILED,
        )
        db.ledgerTransactionDao().insertAll(listOf(provisional))

        val firstCopy = EngineDataMigrationWave3.copyLedgerIfNeeded(context)
        assertEquals(1, firstCopy.copied)

        val schema = LedgerAspectSeeder.ensureSeeded(context)
        val mirrored = db.engineRecordDao().activeByRecordType(schema.transaction.recordTypeId).single()
        assertEquals(RecordProvenance.UNRECONCILED, mirrored.provenance)

        // Simulate what the PRE-cutover legacy path used to do (deleteSupersededProvisional still
        // exists on the DAO as dead code - nothing calls it anymore, but the query itself is
        // untouched, so it is still a faithful stand-in for "this happened on the legacy table
        // before this build's IngestPipeline took over").
        val deletedFromLegacy = db.ledgerTransactionDao().deleteSupersededProvisional(accountId, 0L, 2_000_000L)
        assertEquals(1, deletedFromLegacy)
        assertTrue(db.ledgerTransactionDao().getForAccount(accountId).isEmpty())

        // The engine mirror does not know yet - only catchUpOnce reconciles it now.
        assertEquals(1, db.engineRecordDao().activeByRecordType(schema.transaction.recordTypeId).size)

        EngineDataMigrationWave3.catchUpOnce(context)

        assertEquals(0, db.engineRecordDao().activeByRecordType(schema.transaction.recordTypeId).size)
        assertEquals(1, db.engineRecordDao().trashedByRecordType(schema.transaction.recordTypeId).size)
    }

    @Test
    fun `rule 7 scoping - a DETERMINISTIC row is never trashed by catchUpOnce even if its legacy row vanishes`() = runBlocking {
        db.ledgerTransactionDao().insertAll(listOf(txn(lineRef = "1", ingestMethod = IngestMethod.DETERMINISTIC)))
        EngineDataMigrationWave3.copyLedgerIfNeeded(context)

        // Wholesale legacy delete (mirrors LedgerController.purgeAll) - deliberately NOT the
        // UNRECONCILED-only deleteSupersededProvisional path.
        db.ledgerTransactionDao().deleteAll()

        EngineDataMigrationWave3.catchUpOnce(context)

        val schema = LedgerAspectSeeder.ensureSeeded(context)
        // DETERMINISTIC rows are out of scope - named gap, see the carve doc - so the mirror survives
        // even though its legacy row is gone.
        assertEquals(1, db.engineRecordDao().activeByRecordType(schema.transaction.recordTypeId).size)
        assertEquals(0, db.engineRecordDao().trashedByRecordType(schema.transaction.recordTypeId).size)
    }

    @Test
    fun `catchUpOnce's trash is a real restorable trash, not a hard delete, and runs at most once`() = runBlocking {
        db.ledgerTransactionDao().insertAll(listOf(txn(lineRef = "1", ingestMethod = IngestMethod.UNRECONCILED, accountId = "X-0001")))
        EngineDataMigrationWave3.copyLedgerIfNeeded(context)
        db.ledgerTransactionDao().deleteSupersededProvisional("X-0001", 0L, System.currentTimeMillis() + 1_000_000L)

        EngineDataMigrationWave3.catchUpOnce(context)
        // A second call must be a no-op (guarded by its own completion marker) - if it re-ran the
        // reconciliation pass it would have nothing new to trash anyway, but this pins the guard
        // itself rather than assuming it from the marker's existence.
        EngineDataMigrationWave3.catchUpOnce(context)

        val schema = LedgerAspectSeeder.ensureSeeded(context)
        val trashed = db.engineRecordDao().trashedByRecordType(schema.transaction.recordTypeId).single()
        val recordStore = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        val restored = recordStore.restore(trashed.id)

        assertTrue("a rule-7 supersession trash must be restorable, matching RecordStore's own 30-day trash contract", restored)
        assertEquals(1, db.engineRecordDao().activeByRecordType(schema.transaction.recordTypeId).size)
    }

    @Test
    fun `catchUpOnce also picks up a legacy row written after the ordinary copy already completed`() = runBlocking {
        db.ledgerTransactionDao().insertAll(listOf(txn(lineRef = "1")))
        val first = EngineDataMigrationWave3.copyLedgerIfNeeded(context)
        assertEquals(1, first.copied)
        assertFalse(first.alreadyDone)

        // A row written to the legacy table AFTER the ordinary copy's completion flag was already
        // set - simulating something that landed between wave 3's original landing and this
        // cutover branch's install.
        db.ledgerTransactionDao().insertAll(listOf(txn(lineRef = "2")))

        EngineDataMigrationWave3.catchUpOnce(context)

        val schema = LedgerAspectSeeder.ensureSeeded(context)
        assertEquals(2, db.engineRecordDao().activeByRecordType(schema.transaction.recordTypeId).size)
    }

    // ------------------------------------------------------------- MUST-FIX: first-launch race

    /**
     * Senior review, 2026-08-24 (MUST-FIX): the exact first-launch race - a user importing a CSV or
     * voice-logging a pending transaction BEFORE the fire-and-forget `catchUpOnce` coroutine
     * finishes must never have that legitimate row silently trashed. Simulates the race directly:
     * an engine-native `UNRECONCILED` row is created (mirroring what `ledger/IngestPipeline.commit`/
     * `ledger/LedgerController.logPendingTransaction` do post-cutover) with a `guid` that has NEVER
     * touched the legacy table - `db` here has ZERO legacy rows at all - and `catchUpOnce` is then
     * run on top of it. Before the MUST-FIX, `reconcileSupersededProvisional`'s old logic
     * ("guid missing from `allSyncIds()` -> trash") would have trashed this row on sight, since an
     * engine-native guid is *always* missing from the legacy table by construction. After the fix,
     * the row survives because it was never a member of the persisted migrated-guid set at all.
     */
    @Test
    fun `catchUpOnce never trashes an engine-native row whose guid was never in the legacy table, even if created before reconcile runs`() = runBlocking {
        val schema = LedgerAspectSeeder.ensureSeeded(context)
        val recordStore = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        val engineNative = txn(lineRef = "voice:pending-1", ingestMethod = IngestMethod.UNRECONCILED)
        val createResult = recordStore.create(
            recordTypeId = schema.transaction.recordTypeId,
            fieldValues = com.kevin.legion.engine.ledger.LedgerRecordBridge.fieldValuesFor(engineNative, schema.transaction.fieldIds),
            provenance = RecordProvenance.UNRECONCILED,
            now = engineNative.txnDate,
            guid = engineNative.syncId,
        )
        assertTrue(createResult is RecordStore.WriteResult.Success)
        assertEquals(1, db.engineRecordDao().activeByRecordType(schema.transaction.recordTypeId).size)
        // Sanity: the legacy table genuinely has nothing - this guid was never written there, ever,
        // matching the post-cutover reality that ledger_transactions has zero writers.
        assertTrue(db.ledgerTransactionDao().getAll().isEmpty())

        EngineDataMigrationWave3.catchUpOnce(context)

        assertEquals(
            "an engine-native row must survive catchUpOnce's reconciliation pass regardless of timing",
            1, db.engineRecordDao().activeByRecordType(schema.transaction.recordTypeId).size,
        )
        assertEquals(0, db.engineRecordDao().trashedByRecordType(schema.transaction.recordTypeId).size)
    }

    /**
     * The positive control for the test above - a GENUINELY migrated row (its guid recorded into
     * the persisted set by [EngineDataMigrationWave3.copyLedgerIfNeeded] because it really did come
     * from a legacy row) whose legacy backing later disappears is STILL correctly trashed. Without
     * this the MUST-FIX gate could be papering over a real regression by refusing to trash anything
     * at all.
     */
    @Test
    fun `catchUpOnce still trashes a genuinely migrated row once its legacy backing disappears, proving the new gate isn't overbroad`() = runBlocking {
        val accountId = "BOFA-CARD-7777"
        db.ledgerTransactionDao().insertAll(
            listOf(txn(accountId = accountId, lineRef = "provisional-migrated", ingestMethod = IngestMethod.UNRECONCILED)),
        )
        val first = EngineDataMigrationWave3.copyLedgerIfNeeded(context)
        assertEquals(1, first.copied)

        db.ledgerTransactionDao().deleteSupersededProvisional(accountId, 0L, System.currentTimeMillis() + 1_000_000L)
        assertTrue(db.ledgerTransactionDao().getForAccount(accountId).isEmpty())

        EngineDataMigrationWave3.catchUpOnce(context)

        val schema = LedgerAspectSeeder.ensureSeeded(context)
        assertEquals(0, db.engineRecordDao().activeByRecordType(schema.transaction.recordTypeId).size)
        assertEquals(1, db.engineRecordDao().trashedByRecordType(schema.transaction.recordTypeId).size)
    }
}
