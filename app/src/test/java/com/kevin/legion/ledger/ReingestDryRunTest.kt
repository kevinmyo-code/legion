package com.kevin.legion.ledger

import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Plain JVM, no Robolectric - [BofaCsvStatementParser] (used for every [ReingestDryRun.run] case
 * here, per its own test's doc comment) never touches PdfBox/AssetManager. The DBS/PDF-backed
 * cases (a real [ReingestDryRun.FileOutcome.Parsed] via [com.kevin.legion.ledger.parsers.DbsStatementParser]
 * and a real [ReingestDryRun.FileOutcome.NeedsLlm]) live in [ReingestDryRunRobolectricTest]
 * instead, same split [com.kevin.legion.ledger.parsers.StatementDispatcherTest] already uses.
 */
class ReingestDryRunTest {
    private fun fixture(name: String) = File("src/test/resources/ledger_fixtures/$name")

    private var nextLineRef = 0

    /** One transaction fixture for the [ReingestDryRun.aggregate]/[ReingestDryRun.projectRowCount] cases below. */
    private fun txn(
        accountId: String = "acc-1",
        amountCents: Long = -500L,
        balanceCents: Long? = null,
    ) = LedgerTransaction(
        sourceFile = "statement.pdf",
        accountId = accountId,
        currency = LedgerCurrency.USD,
        txnDate = 1_000L,
        description = "SOMETHING",
        amountCents = amountCents,
        balanceCents = balanceCents,
        lineRef = "line-${nextLineRef++}",
        ingestMethod = IngestMethod.DETERMINISTIC,
    )

    // ---------------------------------------------------------------------
    // run() - real parse, through the real StatementDispatcher, no second parse path.
    // The per-parser anchor recovery itself (opening/closing/stated total, and which of the
    // three each bank format can and cannot print) is now tested at the source, directly against
    // each parser's own `parse` result - see e.g. BofaCsvStatementParserTest,
    // BofaStatementParserTest, DbsStatementParserTest, BofaCardStatementParserTest. ReingestDryRun
    // no longer computes anchors itself (it used to, via a since-removed `recoverAnchors` that
    // stood in a `sum(amountCents)` for a statement's printed total - see this file's own class
    // doc for why that was wrong); it only relays whatever StatementDispatcher.DeterministicResult.Success
    // already carries, so these tests exist to prove that relay, not to re-derive the numbers.
    // ---------------------------------------------------------------------

    private fun fakeReader(bytesByFile: Map<String, ByteArray?>) = ReingestDryRun.ByteReader { driveFileId, _, _ ->
        bytesByFile.getValue(driveFileId)
    }

    /** Always-fails saved-link reader - every row in [driveFileIds] falls straight to the content-match fallback. */
    private fun deadReader(vararg driveFileIds: String) = ReingestDryRun.ByteReader { driveFileId, _, _ ->
        require(driveFileId in driveFileIds) { "unexpected driveFileId $driveFileId" }
        null
    }

    /** A [ReingestDryRun.ContentFolderScanner] over a fixed in-memory candidate set - the "currently connected folder". */
    private fun fakeConnectedFolder(candidates: Map<String, ByteArray>) = object : ReingestDryRun.ContentFolderScanner {
        override suspend fun listCandidates(): List<ReingestDryRun.ConnectedCandidate> =
            candidates.map { (documentId, bytes) -> ReingestDryRun.ConnectedCandidate(documentId, documentId, bytes.size.toLong()) }

        override suspend fun readCandidate(documentId: String): ByteArray? = candidates[documentId]
    }

    @Test
    fun `run reports Unreachable when the byte reader returns null and no content fallback is supplied`() = runBlocking {
        val input = ReingestDryRun.FileInput("gone", "tree://x", "gone.pdf")
        val reports = ReingestDryRun.run(listOf(input), fakeReader(mapOf("gone" to null)))
        val report = reports.single()
        assertTrue(report.outcome is ReingestDryRun.FileOutcome.Unreachable)
        assertNull(report.resolvedVia)
    }

    // ---------------------------------------------------------------------
    // Ticket 19 - content-hash fallback, resolving a row by CONTENT rather than by its saved
    // treeUri+driveFileId address. `.scratch/backend-erp/issues/19-re-ingest-historical-statements.md`'s
    // 2026-08-28 device pass: LEGION holds one Drive folder grant at a time, so a row whose ORIGINAL
    // folder isn't the one connected now can only ever be recovered by matching its stored
    // contentSha256 against whatever bytes are sitting in the folder that IS connected.
    // ---------------------------------------------------------------------

    @Test
    fun `run resolves by saved link first, never touching the content fallback, when the saved link works`() = runBlocking {
        val bytes = fixture("bofa_csv_happy_path.csv").readBytes()
        val hash = IngestPipeline.sha256(bytes)
        val input = ReingestDryRun.FileInput("bofa-csv-1", "tree://x", "bofa_csv_happy_path.csv", sizeBytes = bytes.size.toLong(), contentSha256 = hash)
        // A content-fallback scanner that throws if it's ever asked anything - proves it's never consulted.
        val explodingFolder = object : ReingestDryRun.ContentFolderScanner {
            override suspend fun listCandidates(): List<ReingestDryRun.ConnectedCandidate> =
                error("content fallback must not run when the saved link already worked")
            override suspend fun readCandidate(documentId: String): ByteArray? =
                error("content fallback must not run when the saved link already worked")
        }
        val reports = ReingestDryRun.run(
            listOf(input),
            fakeReader(mapOf("bofa-csv-1" to bytes)),
            explodingFolder,
            accountHint = { "BOFA-CHECKING" },
        )
        val report = reports.single()
        assertEquals(ReingestDryRun.ResolvedVia.SAVED_LINK, report.resolvedVia)
        assertTrue(report.outcome is ReingestDryRun.FileOutcome.Parsed)
    }

    @Test
    fun `run resolves by content hash when the saved link fails but a byte-identical copy sits in the connected folder`() = runBlocking {
        val bytes = fixture("bofa_csv_happy_path.csv").readBytes()
        val hash = IngestPipeline.sha256(bytes)
        val input = ReingestDryRun.FileInput("bofa-csv-1", "tree://original-folder", "bofa_csv_happy_path.csv", sizeBytes = bytes.size.toLong(), contentSha256 = hash)
        val connectedFolder = fakeConnectedFolder(mapOf("some-copy-doc-id" to bytes))

        val reports = ReingestDryRun.run(
            listOf(input),
            deadReader("bofa-csv-1"),
            connectedFolder,
            accountHint = { "BOFA-CHECKING" },
        )
        val report = reports.single()
        assertEquals(ReingestDryRun.ResolvedVia.CONTENT_MATCH, report.resolvedVia)
        assertTrue(report.outcome is ReingestDryRun.FileOutcome.Parsed)
    }

    @Test
    fun `run reports Unreachable with the folder-not-connected wording when neither route resolves`() = runBlocking {
        val bytes = fixture("bofa_csv_happy_path.csv").readBytes()
        val hash = IngestPipeline.sha256(bytes)
        val input = ReingestDryRun.FileInput("bofa-csv-1", "tree://original-folder", "bofa_csv_happy_path.csv", sizeBytes = bytes.size.toLong(), contentSha256 = hash)
        // Connected folder is real (listable) but holds nothing matching this row's size/hash.
        val connectedFolder = fakeConnectedFolder(mapOf("unrelated-doc-id" to "not the same bytes at all".toByteArray()))

        val reports = ReingestDryRun.run(listOf(input), deadReader("bofa-csv-1"), connectedFolder)
        val report = reports.single()
        assertNull(report.resolvedVia)
        val outcome = report.outcome
        assertTrue(outcome is ReingestDryRun.FileOutcome.Unreachable)
        outcome as ReingestDryRun.FileOutcome.Unreachable
        assertTrue(
            "expected the folder-not-connected wording to lead, got: ${outcome.reason}",
            outcome.reason.startsWith("Most likely this file's Drive folder is not the one connected right now"),
        )
    }

    @Test
    fun `run never hashes a connected-folder candidate whose size cannot match any wanted file`() = runBlocking {
        val bytes = fixture("bofa_csv_happy_path.csv").readBytes()
        val hash = IngestPipeline.sha256(bytes)
        val input = ReingestDryRun.FileInput("bofa-csv-1", "tree://original-folder", "bofa_csv_happy_path.csv", sizeBytes = bytes.size.toLong(), contentSha256 = hash)
        var readCandidateCalls = 0
        val connectedFolder = object : ReingestDryRun.ContentFolderScanner {
            override suspend fun listCandidates(): List<ReingestDryRun.ConnectedCandidate> = listOf(
                // Wrong size on purpose - the size-first shortcut must skip this without ever reading it.
                ReingestDryRun.ConnectedCandidate("wrong-size-doc", "wrong-size-doc", bytes.size.toLong() + 1),
            )
            override suspend fun readCandidate(documentId: String): ByteArray? {
                readCandidateCalls++
                return bytes
            }
        }

        val reports = ReingestDryRun.run(listOf(input), deadReader("bofa-csv-1"), connectedFolder)
        assertEquals(0, readCandidateCalls)
        assertTrue(reports.single().outcome is ReingestDryRun.FileOutcome.Unreachable)
    }

    @Test
    fun `run reports opening and closing balance for a real reconciling BofA CSV export, but no stated total`() = runBlocking {
        val bytes = fixture("bofa_csv_happy_path.csv").readBytes()
        val input = ReingestDryRun.FileInput("bofa-csv-1", "tree://x", "bofa_csv_happy_path.csv")
        val reports = ReingestDryRun.run(
            listOf(input),
            fakeReader(mapOf("bofa-csv-1" to bytes)),
            accountHint = { "BOFA-CHECKING" },
        )
        val outcome = reports.single().outcome
        assertTrue(outcome is ReingestDryRun.FileOutcome.Parsed)
        outcome as ReingestDryRun.FileOutcome.Parsed
        assertEquals(7, outcome.rowCount)
        // BofaCsvStatementParser prints "Total credits" and "Total debits" separately, never one
        // combined total - so statedTotalCents is honestly null, and this file is a rule-7
        // provisional candidate (two of three anchors), not the complete recovery the old
        // sum(amountCents) stand-in used to fake here.
        assertFalse(outcome.anchors.isComplete)
        assertEquals(listOf("stated total"), outcome.anchors.missing)
        assertEquals(-631L, outcome.anchors.openingBalanceCents)
        assertEquals(222061L, outcome.anchors.closingBalanceCents)
        assertNull(outcome.anchors.statedTotalCents)
    }

    @Test
    fun `run reports NeedsAccount for a numerically clean file with no account hint`() = runBlocking {
        val bytes = fixture("bofa_csv_happy_path.csv").readBytes()
        val input = ReingestDryRun.FileInput("bofa-csv-2", "tree://x", "bofa_csv_happy_path.csv")
        // Default accountHint lambda returns null - no folder-mapping context available.
        val reports = ReingestDryRun.run(listOf(input), fakeReader(mapOf("bofa-csv-2" to bytes)))
        assertTrue(reports.single().outcome is ReingestDryRun.FileOutcome.NeedsAccount)
    }

    @Test
    fun `run reports Unparseable for a file whose numbers no longer reconcile on re-read`() = runBlocking {
        val bytes = fixture("bofa_csv_balance_mismatch.csv").readBytes()
        val input = ReingestDryRun.FileInput("bofa-csv-3", "tree://x", "bofa_csv_balance_mismatch.csv")
        val reports = ReingestDryRun.run(listOf(input), fakeReader(mapOf("bofa-csv-3" to bytes)))
        assertTrue(reports.single().outcome is ReingestDryRun.FileOutcome.Unparseable)
    }

    // ---------------------------------------------------------------------
    // aggregate() - counts and bucketing
    // ---------------------------------------------------------------------

    @Test
    fun `aggregate buckets every outcome kind and names each missing anchor`() {
        val complete = ReingestDryRun.FileReport(
            "f1", "f1.csv",
            ReingestDryRun.FileOutcome.Parsed(2, ReingestDryRun.AnchorRecovery(1L, 2L, 3L), listOf(txn())),
        )
        val incomplete = ReingestDryRun.FileReport(
            "f2", "f2.csv",
            ReingestDryRun.FileOutcome.Parsed(1, ReingestDryRun.AnchorRecovery(null, null, 3L), listOf(txn())),
        )
        val unreachable = ReingestDryRun.FileReport("f3", "f3.csv", ReingestDryRun.FileOutcome.Unreachable("gone"))
        val unparseable = ReingestDryRun.FileReport("f4", "f4.csv", ReingestDryRun.FileOutcome.Unparseable("bad"))
        val needsAccount = ReingestDryRun.FileReport("f5", "f5.csv", ReingestDryRun.FileOutcome.NeedsAccount("which?"))
        val needsLlm = ReingestDryRun.FileReport("f6", "f6.csv", ReingestDryRun.FileOutcome.NeedsLlm)

        val report = ReingestDryRun.aggregate(listOf(complete, incomplete, unreachable, unparseable, needsAccount, needsLlm))

        assertEquals(6, report.totalFiles)
        assertEquals(1, report.completeAnchors)
        assertEquals(1, report.incompleteAnchors)
        assertEquals(mapOf("opening balance" to 1, "closing balance" to 1), report.missingAnchorCounts)
        assertEquals(1, report.unreachable)
        assertEquals(1, report.unparseable)
        assertEquals(1, report.needsAccount)
        assertEquals(1, report.needsLlm)
        assertEquals(3, report.rawRowsParsed) // 2 + 1
    }

    // ---------------------------------------------------------------------
    // projectRowCount() - replays the real resolveDedup, in memory
    // ---------------------------------------------------------------------

    @Test
    fun `projectRowCount drops an exact restatement across two overlapping files, same as a real commit would`() {
        val julyFile = listOf(
            txn(accountId = "acc-1", amountCents = -500L).copy(txnDate = 1L, description = "COFFEE"),
            txn(accountId = "acc-1", amountCents = -100L).copy(txnDate = 2L, description = "TEA"),
        )
        // A YTD statement restating the exact same July transactions, byte-for-byte identical key.
        val ytdFile = listOf(
            txn(accountId = "acc-1", amountCents = -500L).copy(txnDate = 1L, description = "COFFEE"),
            txn(accountId = "acc-1", amountCents = -100L).copy(txnDate = 2L, description = "TEA"),
            txn(accountId = "acc-1", amountCents = -900L).copy(txnDate = 3L, description = "GROCERIES"),
        )
        val reports = listOf(
            ReingestDryRun.FileReport("july", "july.pdf", ReingestDryRun.FileOutcome.Parsed(2, ReingestDryRun.AnchorRecovery(0, 0, -600), julyFile)),
            ReingestDryRun.FileReport("ytd", "ytd.pdf", ReingestDryRun.FileOutcome.Parsed(3, ReingestDryRun.AnchorRecovery(0, 0, -1500), ytdFile)),
        )
        // 2 + 3 raw rows, but the two July rows in the YTD file are exact restatements of the
        // july file's own rows - only the one genuinely new row (GROCERIES) should project in.
        assertEquals(3, ReingestDryRun.projectRowCount(reports))
    }

    @Test
    fun `projectRowCount keeps two genuinely separate same-day same-amount purchases`() {
        val rows = listOf(
            txn(accountId = "acc-1", amountCents = -450L).copy(txnDate = 1L, description = "COFFEE A"),
            txn(accountId = "acc-1", amountCents = -450L).copy(txnDate = 1L, description = "COFFEE B"),
        )
        val reports = listOf(
            ReingestDryRun.FileReport("f", "f.pdf", ReingestDryRun.FileOutcome.Parsed(2, ReingestDryRun.AnchorRecovery(0, 0, -900), rows)),
        )
        assertEquals(2, ReingestDryRun.projectRowCount(reports))
    }

    // ---------------------------------------------------------------------
    // The test that matters most: this dry run cannot write, by construction.
    // ---------------------------------------------------------------------

    @Test
    fun `ReingestDryRun's public API has no dependency capable of writing anything`() {
        // ReingestDryRun.run()'s only inputs are FileInput, a ByteReader, and a pure account-hint
        // lambda - none of which can reach Room, the engine, or ingested_files. Checked by
        // REFLECTION on the actual compiled function signatures, not by grepping the source text
        // (which would also match this class's own doc comments describing what it does NOT do,
        // e.g. mentioning IngestPipeline.commit by name to explain the contrast). A parameter or
        // return type containing "Context", "Dao", "RecordStore", or "CarDatabase" would be the
        // one way a future edit could smuggle a write path in.
        val writeCapableNames = listOf("Context", "Dao", "RecordStore", "CarDatabase", "ContentResolver")
        for (function in ReingestDryRun::class.java.declaredMethods) {
            val allTypes = function.parameterTypes.toList() + function.returnType
            for (type in allTypes) {
                for (bad in writeCapableNames) {
                    assertFalse(
                        "ReingestDryRun.${function.name} touches $bad via ${type.name} - this object must stay incapable of writing anything",
                        type.name.contains(bad),
                    )
                }
            }
        }
    }
}
