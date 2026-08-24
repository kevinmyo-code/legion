package com.kevin.legion.service

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.ledger.IngestPipeline
import com.kevin.legion.ledger.LedgerAccountMappingPreferences
import com.kevin.legion.ledger.LedgerIngestResult
import com.kevin.legion.ledger.parsers.DeterministicResult
import com.kevin.legion.ledger.parsers.PdfText
import com.kevin.legion.ledger.parsers.PdfWords
import com.kevin.legion.ledger.parsers.StatementDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Service-scoped folder-scan pipeline - `.scratch/ledger-drive-ingestion/issues/05-batch-ingestion-mechanics.md`
 * resolution §1: lives inside [AriaForegroundService] (no new dependency, no
 * manifest change; the service already declares
 * `dataSync`/`connectedDevice`/`microphone`), NOT `androidx.work`, because
 * ticket 03 already made process-death durability cheap (a killed scan is
 * re-run, not resumed - unchanged files cost zero bytes) and the rescan
 * trigger (§6, a listing-only diff on app foreground) needs nothing running
 * while the app is closed.
 *
 * Two-phase execution, per §2 and ticket 06's amendment:
 * ```
 * phase 1   fetch + sha256 + classify        PARALLEL, limit 4
 * phase 2a  deterministic parse, ALL staged  STRICTLY SERIAL
 *           >>> GATE. count exact, spend so far zero <<<
 * phase 2b  LLM for the approved set only    STRICTLY SERIAL
 * ```
 * Parallel phase 1 because that's where the measured per-file cost actually
 * goes (637ms cached / 1248ms uncached per the device probe). Serial phase 2
 * bounds peak PdfBox memory to one document, makes the gate's count exact
 * rather than racing work already in flight, and never fires concurrent
 * Gemini calls at a possibly rate-limited key.
 *
 * **The batch is NOT atomic as a whole** (§5) - this class never wraps the
 * scan in a Room transaction. Every commit is per-file, via
 * [IngestPipeline.commit], which is itself transactional only for the one
 * file (and its replace-flow siblings) it is committing.
 */
class IngestScanner(private val context: Context) {

    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state

    private val _events = Channel<ScanEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // Set only while a scan is parked at ScanState.AwaitingApproval; completed
    // by approveLlm()/declineLlm() to unblock phase 2b. Null the rest of the
    // time, so a stray call when nothing is waiting is a safe no-op.
    @Volatile private var pendingGate: CompletableDeferred<Boolean>? = null

    /** Approves the pending LLM set so phase 2b runs. No-op if no scan is currently parked at the gate. */
    fun approveLlm() {
        pendingGate?.complete(true)
    }

    /** Declines the pending LLM set. Every file in it stays NEEDS_LLM - "not now", re-offered on the next scan (ticket 06 amendment 3). Never "never". */
    fun declineLlm() {
        pendingGate?.complete(false)
    }

    /**
     * Runs one full scan of [treeUri]'s children. Suspends until finished
     * (including however long the gate takes to be answered - the caller is
     * expected to launch this in its own coroutine and observe [state]/
     * [events] rather than await a return value for progress).
     */
    suspend fun scan(treeUri: Uri): Unit = withContext(Dispatchers.IO) {
        // Re-entrancy guard. The SCAN control disables itself once state
        // leaves Idle/Finished, but state doesn't move until after the
        // blocking init below, so a fast double-tap could otherwise start two
        // runs that fight over `pendingGate` and sweep each other's scanDir.
        val current = _state.value
        if (current !is ScanState.Idle && current !is ScanState.Finished) return@withContext

        PdfWords.init(context)
        sweepOrphanScanDirs() // cleanup obligation 3: a killed prior run's leftovers

        val scanDir = File(context.cacheDir, "scan-${System.currentTimeMillis()}")
        scanDir.mkdirs()

        try {
            runScan(treeUri, scanDir)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Must rethrow, not swallow. CancellationException IS-A Exception,
            // so the generic handler below would have reported a cancelled
            // scan as Finished(FileResults()) - indistinguishable from a scan
            // that legitimately found nothing, on a batch that may have been
            // halfway through and may already have spent tokens.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "scan failed: ${e.message}", e)
            _events.trySend(ScanEvent.Failed("The scan hit an unexpected problem: ${e.message}"))
            _state.value = ScanState.Finished(FileResults())
        } finally {
            scanDir.deleteRecursively() // cleanup obligation 2: whatever the outcome
            pendingGate = null
        }
    }

    private class StagedFile(
        val driveFileId: String,
        val displayName: String,
        val cacheFile: File,
        val staged: IngestPipeline.StageOutcome.Staged,
        /** Resolved via [LedgerAccountMappingPreferences.accountFor] against [SafChild.containingFolderId] - see the account-mapping ticket. Null for a file directly in the connected root, or whose subfolder has no mapping yet. */
        val accountHint: String?,
    )

    private suspend fun runScan(treeUri: Uri, scanDir: File) {
        _state.value = ScanState.Listing(0)
        val children = try {
            listChildren(treeUri)
        } catch (e: Exception) {
            // A stale/empty or failed listing is a normal, expected outcome
            // (ticket 05 §9 - the provider serves stale-empty results with no
            // signal at all) - never surfaced as an error here. A genuine
            // exception (permission revoked, tree gone) still gets reported.
            _events.trySend(ScanEvent.Failed("Couldn't read that folder: ${e.message}"))
            _state.value = ScanState.Finished(FileResults())
            return
        }
        if (children == null) {
            // The provider declined the ROOT query. Distinct from an empty
            // folder, and distinct from a revoked grant - measured on the A17K
            // 2026-08-06, where the persisted READ permission was still live
            // (the folder row rendered SCAN, not its revoked state) and Drive
            // returned a null cursor anyway, twice, an hour apart. The listing
            // came back fine immediately after the folder was opened in the
            // Drive app itself.
            //
            // So the copy names the action that actually works rather than
            // blaming permissions. Reported, never silent: this exact failure
            // read as "Scan finished: nothing new" through four scans tonight,
            // which is indistinguishable from a healthy scan of a folder with
            // no new statements, and cost an hour of chasing the wrong thing.
            Log.w(TAG, "scan ABORTED: root listing refused for $treeUri")
            _events.trySend(
                ScanEvent.Failed(
                    "Google Drive didn't answer for that folder. Open the Drive app, look inside the " +
                        "folder so it loads, then scan again. Nothing was imported and nothing was lost."
                )
            )
            _state.value = ScanState.Finished(FileResults())
            return
        }
        Log.d(TAG, "scan listed ${children.size} file(s) under $treeUri")
        _state.value = ScanState.Listing(children.size)

        // Phase 1: parallel limit 4 - fetch + sha256 + classify + spill.
        val skipped = AtomicInteger(0)
        val duplicate = AtomicInteger(0)
        val unreadable = AtomicInteger(0)
        val done1 = AtomicInteger(0)
        val staged = ConcurrentLinkedQueue<StagedFile>()
        val semaphore = Semaphore(PHASE1_PARALLELISM)

        coroutineScope {
            children.map { child ->
                async {
                    semaphore.withPermit {
                        val driveFileId = IngestPipeline.stripAccountPrefix(child.documentId)
                        val outcome = IngestPipeline.stage(
                            context = context,
                            driveFileId = driveFileId,
                            treeUri = treeUri.toString(),
                            displayName = child.displayName,
                            sizeBytes = child.size,
                            lastModified = child.lastModified,
                            mimeType = child.mimeType,
                        ) { openBytes(treeUri, child.documentId) }

                        when (outcome) {
                            is IngestPipeline.StageOutcome.Skipped -> skipped.incrementAndGet()
                            is IngestPipeline.StageOutcome.DuplicateContent -> duplicate.incrementAndGet()
                            is IngestPipeline.StageOutcome.Unreadable -> unreadable.incrementAndGet()
                            is IngestPipeline.StageOutcome.Staged -> {
                                // Spill to disk immediately and drop the in-memory
                                // reference - this is what bounds phase 1's peak
                                // memory to roughly PHASE1_PARALLELISM files at once
                                // rather than however many the folder holds.
                                val cacheFile = File(scanDir, URLEncoder.encode(driveFileId, "UTF-8"))
                                cacheFile.writeBytes(outcome.bytes)
                                val accountHint = LedgerAccountMappingPreferences.accountFor(child.containingFolderId)
                                staged += StagedFile(driveFileId, child.displayName, cacheFile, outcome, accountHint)
                            }
                        }
                        _state.value = ScanState.Staging(done1.incrementAndGet(), children.size)
                    }
                }
            }.awaitAll()
        }

        val stagedList = staged.toList()

        // Phase 2a: strictly serial deterministic parse over every staged file.
        val needsLlm = mutableListOf<StagedFile>()
        var ingestedCount = 0
        var quarantinedCount = 0
        var engineWriteFailedCount = 0
        for ((index, sf) in stagedList.withIndex()) {
            _state.value = ScanState.ParsingDeterministic(index, stagedList.size, sf.displayName)
            val bytes = sf.cacheFile.readBytes()
            when (val det = StatementDispatcher.dispatchDeterministic(sf.displayName, bytes, sf.accountHint)) {
                is DeterministicResult.Success -> {
                    when (
                        IngestPipeline.commit(
                            context, sf.driveFileId, treeUri.toString(), sf.displayName,
                            sf.staged, LedgerIngestResult.Success(det.transactions),
                        )
                    ) {
                        is IngestPipeline.CommitOutcome.Ingested -> ingestedCount++
                        is IngestPipeline.CommitOutcome.Quarantined -> quarantinedCount++ // shouldn't happen for a Success result, but exhaustive
                        is IngestPipeline.CommitOutcome.EngineWriteFailed -> engineWriteFailedCount++
                    }
                    sf.cacheFile.delete() // cleanup obligation 1: per-entry after consumption
                }
                // On the FOLDER path an unmapped account really is a quarantine
                // and the parser's own wording is right: there IS a folder, and
                // mapping it is the fix. Handled alongside Quarantined rather
                // than separately for exactly that reason - the single-file
                // path is the one that needed a different answer.
                is DeterministicResult.NeedsAccount, is DeterministicResult.Quarantined -> {
                    val reason = when (det) {
                        is DeterministicResult.NeedsAccount -> det.reason
                        is DeterministicResult.Quarantined -> det.reason
                        else -> "This statement's numbers didn't reconcile."
                    }
                    IngestPipeline.commit(
                        context, sf.driveFileId, treeUri.toString(), sf.displayName,
                        sf.staged, LedgerIngestResult.Quarantined(reason),
                    )
                    quarantinedCount++
                    sf.cacheFile.delete()
                }
                is DeterministicResult.NeedsLlm -> {
                    // Never touched Gemini to get here - dispatchDeterministic
                    // is pure CPU work. The cache file is KEPT: phase 2b still
                    // needs these bytes if the gate is approved.
                    IngestPipeline.markNeedsLlm(context, sf.driveFileId, treeUri.toString(), sf.displayName)
                    needsLlm += sf
                }
            }
        }
        _state.value = ScanState.ParsingDeterministic(stagedList.size, stagedList.size, "")

        var llmPromptTokensUsed = 0
        var llmResponseTokensUsed = 0
        var needsLlmDeclined = 0

        // The gate. Count is exact, spend so far is zero (ticket 06 amendment to ticket 05).
        if (needsLlm.isNotEmpty()) {
            val estimate = buildSpendEstimate(needsLlm.size)
            val gate = CompletableDeferred<Boolean>()
            pendingGate = gate
            _state.value = ScanState.AwaitingApproval(needsLlm.size, estimate)
            val approved = gate.await()
            pendingGate = null

            if (approved) {
                // Phase 2b: strictly serial LLM for the approved set only.
                for ((index, sf) in needsLlm.withIndex()) {
                    _state.value = ScanState.ParsingLlm(index, needsLlm.size, sf.displayName)
                    val bytes = sf.cacheFile.readBytes()
                    val text = PdfText.extractText(bytes.inputStream())
                    val llmOutcome = StatementDispatcher.runLlm(sf.displayName, text)
                    val commitOutcome = IngestPipeline.commit(
                        context, sf.driveFileId, treeUri.toString(), sf.displayName,
                        sf.staged, llmOutcome.result,
                        llmUsage = llmOutcome.promptTokens to llmOutcome.responseTokens,
                    )
                    when (commitOutcome) {
                        is IngestPipeline.CommitOutcome.Ingested -> ingestedCount++
                        is IngestPipeline.CommitOutcome.Quarantined -> quarantinedCount++
                        is IngestPipeline.CommitOutcome.EngineWriteFailed -> engineWriteFailedCount++
                    }
                    llmPromptTokensUsed += llmOutcome.promptTokens ?: 0
                    llmResponseTokensUsed += llmOutcome.responseTokens ?: 0
                    sf.cacheFile.delete() // cleanup obligation 1
                }
                _state.value = ScanState.ParsingLlm(needsLlm.size, needsLlm.size, "")
            } else {
                needsLlmDeclined = needsLlm.size
                needsLlm.forEach { it.cacheFile.delete() } // declined: nothing further to do with the bytes
            }
        }

        _state.value = ScanState.Finished(
            FileResults(
                ingested = ingestedCount,
                quarantined = quarantinedCount,
                unreadable = unreadable.get(),
                duplicate = duplicate.get(),
                skipped = skipped.get(),
                needsLlmDeclined = needsLlmDeclined,
                llmPromptTokensUsed = llmPromptTokensUsed,
                llmResponseTokensUsed = llmResponseTokensUsed,
                engineWriteFailed = engineWriteFailedCount,
            )
        )
    }

    /** Cleanup obligation 3: orphaned `cacheDir/scan-*` directories a previously killed run left behind. */
    private fun sweepOrphanScanDirs() {
        context.cacheDir.listFiles { f -> f.isDirectory && f.name.startsWith("scan-") }
            ?.forEach { it.deleteRecursively() }
    }

    /**
     * Builds the gate's estimate: exact [fileCount], plus per-file token
     * counts that are MEASURED (from real past LLM calls, ticket 06 §6) when
     * any exist, else the resolution's reasoned "typical" constants. No price
     * - see [SpendEstimate]'s doc comment.
     */
    private suspend fun buildSpendEstimate(fileCount: Int): SpendEstimate {
        val avg = CarDatabase.getDatabase(context).ingestedFileDao().averageLlmTokenUsage()
        val measured = avg?.avgPrompt != null && avg.avgResponse != null
        return SpendEstimate(
            fileCount = fileCount,
            estimatedPromptTokensPerFile = avg?.avgPrompt?.toInt() ?: REASONED_TYPICAL_PROMPT_TOKENS,
            estimatedResponseTokensPerFile = avg?.avgResponse?.toInt() ?: REASONED_TYPICAL_RESPONSE_TOKENS,
            basedOnMeasuredAverage = measured,
        )
    }

    private data class SafChild(
        val documentId: String,
        val displayName: String,
        val mimeType: String,
        val size: Long,
        val lastModified: Long,
        /** The containing per-account subfolder's SAF document id, null for a file directly in the connected root. Resolved into an account via [LedgerAccountMappingPreferences.accountFor] in [runScan]'s phase 1. */
        val containingFolderId: String? = null,
    )

    /**
     * Queries the tree's children directly via [android.content.ContentResolver]
     * (not `DocumentFile.listFiles()`, which costs one IPC per attribute per
     * file and discards the cursor's extras) - one binder call for every
     * column this pipeline needs. `.scratch/ledger-drive-ingestion/research/01-saf-drive-folder-findings.md`
     * §2c point 2.
     *
     * **Recurses ONE level into subfolders** - Kevin's real layout puts
     * per-account folders (`checking/`, `credit/`) directly under the
     * connected root, each holding both a PDF (prints its own account) and a
     * CSV (BofA's export, which doesn't). Every file found inside a
     * subfolder carries that subfolder's document id as
     * [SafChild.containingFolderId], the hint
     * [LedgerAccountMappingPreferences] resolves into an account for any
     * file that states none of its own.
     *
     * **Capped at one level on purpose.** The stated layout is exactly one
     * folder deep; an unbounded walk risks unbounded binder-call fan-out
     * against a provider that already serves stale-empty results with no
     * signal at all (ticket 05's finding). A folder nested two levels down
     * (a folder inside `checking/`) is simply not descended into again -
     * that is a known, documented limit, not a silent gap. `reasoned`, not
     * verified on-device: SAF recursion behavior only truly proves out on
     * hardware, and none was available while this was built.
     *
     * A subfolder is NEVER itself staged as a file - only its children come
     * back from this function. This is also the fix for the prior flat-only
     * behavior's bug: a subfolder used to come back as an ordinary child,
     * fail every parser, and land [com.kevin.legion.data.local.IngestState.UNREADABLE].
     */
    private fun listChildren(treeUri: Uri): List<SafChild>? {
        // Null from the ROOT query is fatal to the scan and is reported, not
        // absorbed. Null from a SUBFOLDER is logged and skipped: one
        // uncooperative subfolder must not discard the files another one
        // listed fine, and the caller has no way to act on it anyway.
        val topLevel = queryChildDocuments(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            ?: return null
        val out = mutableListOf<SafChild>()
        for (child in topLevel) {
            if (child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                // One level of recursion, no further. Files inside inherit
                // this subfolder's document id as their account-mapping
                // hint; any directory found AT THIS DEPTH is not descended
                // into again - the cap this function's doc comment names.
                out += queryChildDocuments(treeUri, child.documentId)
                    .orEmpty()
                    .filter { it.mimeType != DocumentsContract.Document.MIME_TYPE_DIR }
                    .map { it.copy(containingFolderId = child.documentId) }
            } else {
                out += child
            }
        }
        return out
    }

    /** One binder call: every direct child of [parentDocumentId] within [treeUri]. Shared by the root listing and the one-level subfolder recursion in [listChildren]. */
    private fun queryChildDocuments(treeUri: Uri, parentDocumentId: String): List<SafChild>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = mutableListOf<SafChild>()
        // A THROWN cursor is the same event as a null one (crash fix,
        // 2026-08-07). The null case below is handled carefully and at length -
        // but a revoked SAF grant does not politely return null, it throws
        // SecurityException, and nothing here caught it. That crashed the app
        // mid-scan for exactly the reason the null branch exists to explain.
        // Both funnel to the same `null` return so the caller's "LEGION can no
        // longer read your folder" reporting covers both, rather than one being
        // explained and the other killing the process.
        val cursor = try {
            context.contentResolver.query(childrenUri, columns, null, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "child listing threw (grant revoked or tree gone): ${e.message}")
            null
        }
        if (cursor == null) {
            // A null cursor is NOT an empty folder. It is the provider
            // declining the query outright - a dropped persisted grant, a
            // signed-out account, a provider that failed to start. The
            // previous `query(...)?.use { }` swallowed that into the same
            // empty list a genuinely empty folder produces, and the scan then
            // reported "nothing new" for both. That is the whole difference
            // between "your folder has no new statements" and "LEGION can no
            // longer read your folder at all", and it was invisible.
            //
            // Still not thrown: ticket 05 §9's rule that a scan finding
            // nothing is a normal outcome holds, and a mid-listing failure
            // must not abort a batch. It is logged loudly instead, because
            // there is no crash reporter to catch it (CLAUDE.md §3, Firebase
            // is not wired up).
            Log.w(TAG, "listing REFUSED for parent=$parentDocumentId (null cursor, not an empty folder)")
            return null
        }
        cursor.use { c ->
            while (c.moveToNext()) {
                val documentId = c.getString(0) ?: continue
                out += SafChild(
                    documentId = documentId,
                    displayName = c.getString(1) ?: "statement.pdf",
                    mimeType = c.getString(2).orEmpty(),
                    size = c.getLong(3),
                    lastModified = c.getLong(4),
                )
            }
        }
        Log.d(TAG, "listing for parent=$parentDocumentId returned ${out.size} children")
        return out
    }

    /** Reads one child's bytes. Null (never a thrown exception past this point) on any failure - a Drive read can fail offline or on a stream hiccup, and that must route to UNREADABLE, not crash the batch. */
    private fun openBytes(treeUri: Uri, documentId: String): ByteArray? {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        return try {
            context.contentResolver.openInputStream(docUri)?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "read failed for $documentId: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "IngestScanner"
        private const val PHASE1_PARALLELISM = 4

        // Reasoned "typical" constants from the ticket 06 cost model
        // (`.scratch/ledger-drive-ingestion/issues/06-llm-spend-gate.md` §2) -
        // used only until a real batch has measured actual usage. NOT a price;
        // see SpendEstimate's doc comment for why no dollar figure ships.
        private const val REASONED_TYPICAL_PROMPT_TOKENS = 3_220
        private const val REASONED_TYPICAL_RESPONSE_TOKENS = 915
    }
}
