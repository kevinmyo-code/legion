package com.kevin.legion.books

import com.kevin.legion.data.local.BookConversionRow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * What became of one PDF's conversion (books ticket 05, rulings in ticket 03). Stored by name in
 * `book_conversions.status`. [terminal] ones are never retried; [FAILED] is retried up to
 * [PdfConversionRules.MAX_ATTEMPTS] times and then stops, and stays said.
 */
enum class ConversionStatus(val terminal: Boolean) {
    DONE(terminal = true),
    SCANNED(terminal = true),
    TOO_BIG(terminal = true),
    UNREADABLE(terminal = true),
    FAILED(terminal = false),
    ;

    companion object {
        fun of(name: String): ConversionStatus? = entries.firstOrNull { it.name == name }
    }
}

/** What the engine's `POST /api/books/convert` (ticket 04) answered, or why no answer came. */
sealed interface ConvertReply {
    /** 200: the EPUB's bytes and the page count the server reported (null when it sent none). */
    class Converted(val epub: ByteArray, val pages: Int?) : ConvertReply

    /** The server said no in words (422 scanned or unreadable, 413 too big, 502 failed). [message] is its sentence. */
    data class Refused(val status: ConversionStatus, val message: String) : ConvertReply

    /** Nothing reached the engine (offline, wrong address): transient, WorkManager retries on backoff. */
    data class Unreachable(val sentence: String) : ConvertReply

    /** Not signed in to the engine, or no engine address: not transient. Nothing was sent. */
    data class Unavailable(val sentence: String) : ConvertReply
}

/** The seam to the household engine. Django in production ([DjangoBookConvertBackend]), a fake in tests. */
fun interface BookConvertBackend {
    suspend fun convert(pdf: ByteArray): ConvertReply
}

/** The numbers and sentences of the conversion rulings, in one place. */
object PdfConversionRules {
    /** The phone's own cap: the server takes at most this much, so a bigger file is never uploaded. */
    const val MAX_BYTES = 30L * 1024 * 1024
    const val MAX_ATTEMPTS = 3
    private const val BYTES_PER_MB_LONG = 1024L * 1024L

    /** The sentence ticket 04's server sends for an oversize PDF; used here without uploading. One decimal. */
    fun tooBigSentence(bytes: Long): String =
        "This PDF is ${oneDecimalMiB(bytes)} MB; the most the server takes is 30 MB."

    /**
     * Server's `f"{size_mb:.1f}"`: Python rounds the exact binary value half-to-even, and dividing by a
     * power of two is exact, so BigDecimal HALF_EVEN reproduces it. Java's `%.1f` rounds half-UP and would
     * print 31.3 where the server prints 31.2 for a file of exactly 31.25 MiB.
     */
    private fun oneDecimalMiB(bytes: Long): String =
        java.math.BigDecimal(bytes).divide(java.math.BigDecimal(BYTES_PER_MB_LONG))
            .setScale(1, java.math.RoundingMode.HALF_EVEN).toPlainString()

    const val SCANNED_SENTENCE = "This PDF is scanned pages with no text, so its text cannot be refit."

    sealed interface Decision {
        data object Skip : Decision

        data object Convert : Decision

        /** A DONE row whose EPUB is gone (data cleared, storage pruned): forget the row, convert again. */
        data object ResetAndConvert : Decision
    }

    /** What to do about a PDF whose bytes hash to a row (or to none), and whether its EPUB file is there. */
    fun decide(row: BookConversionRow?, epubExists: Boolean): Decision {
        val status = row?.let { ConversionStatus.of(it.status) } ?: return Decision.Convert
        return when {
            status == ConversionStatus.DONE -> if (epubExists) Decision.Skip else Decision.ResetAndConvert
            status.terminal -> Decision.Skip
            row.attempts >= MAX_ATTEMPTS -> Decision.Skip
            else -> Decision.Convert
        }
    }

    /** True while this PDF still owes the user a conversion: no row, or a FAILED one with tries left. */
    fun isPending(row: BookConversionRow?): Boolean = decide(row, epubExists = true) == Decision.Convert
}

/** One PDF found in the folder. [sizeBytes] and [modifiedMs] come from the provider and may be absent. */
data class PdfRef(val book: Book, val sizeBytes: Long?, val modifiedMs: Long?)

/** The folder, as the runner sees it. SAF in production, a map in tests. */
interface PdfSource {
    /** Every PDF under the granted folder, or null when there is no readable grant. */
    fun list(): List<PdfRef>?

    /** The file's bytes as a stream, or null when it cannot be opened. */
    fun open(ref: PdfRef): InputStream?
}

/** The `book_conversions` table, as the runner sees it. */
interface ConversionStore {
    suspend fun get(sha256: String): BookConversionRow?

    suspend fun upsert(row: BookConversionRow)

    suspend fun delete(sha256: String)
}

/** The converted EPUBs under app-private storage: `filesDir/books/converted/<sha256>.epub`. */
interface ConvertedFiles {
    fun file(sha256: String): File

    fun exists(sha256: String): Boolean

    /** Writes whole or not at all (a temp file renamed into place), so a crash never leaves half an EPUB. */
    fun write(sha256: String, bytes: ByteArray)

    fun delete(sha256: String)
}

/** documentId to SHA-256, so the sheet and the reader never hash a 35 MB file to ask a question. */
interface PdfHashCache {
    /** The cached hash, only if the file still has the size and modified time it had when hashed. */
    fun get(ref: PdfRef): String?

    /** The last hash recorded for this document id with no freshness check (cheap; for counts and the sheet). */
    fun lastKnown(documentId: String): String?

    fun put(ref: PdfRef, sha256: String, sizeBytes: Long)
}

/** What the last run learned that the sheet and viewer say in words. */
interface ConversionNotes {
    /** The engine could not be used (unreachable, signed out), as a sentence; null when the last run reached it. */
    var serverNote: String?
}

/** A PDF's bytes read once: its identity, its size, and the bytes only when it is small enough to upload. */
class PdfRead(val sha256: String, val sizeBytes: Long, val bytes: ByteArray?)

/** SHA-256 identity of a PDF (ticket 05). Streams, so a file over the cap is hashed without being held. */
object PdfIdentity {
    private const val BUFFER = 64 * 1024

    fun read(input: InputStream, cap: Long = PdfConversionRules.MAX_BYTES): PdfRead {
        val digest = MessageDigest.getInstance("SHA-256")
        val kept = ByteArrayOutputStream()
        var total = 0L
        val buffer = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
            total += n
            if (total <= cap) kept.write(buffer, 0, n)
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        return PdfRead(sha, total, if (total <= cap) kept.toByteArray() else null)
    }
}

/** How a run ended, for the worker. */
enum class RunOutcome {
    /** Nothing is left to try, or what is left will not change by trying again soon. */
    COMPLETE,

    /** The engine was unreachable, or a FAILED conversion has tries left: WorkManager should retry on backoff. */
    RETRY,
}

/**
 * The conversion state machine, with every effect behind a port so it is tested without WorkManager
 * (the `EnginePoll` pure-body pattern). The worker is a thin shell around [run].
 *
 * One PDF at a time, smallest first. A PDF over [PdfConversionRules.MAX_BYTES] is marked TOO_BIG
 * without being uploaded. An engine that cannot be reached stops the run (the rest would fail the
 * same way) and burns no attempt; a server refusal is recorded and the run goes on to the next PDF.
 */
class PdfConversionRunner(
    private val source: PdfSource,
    private val backend: BookConvertBackend,
    private val store: ConversionStore,
    private val files: ConvertedFiles,
    private val hashes: PdfHashCache,
    private val notes: ConversionNotes,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Suppress("ReturnCount") // a run ends the moment the state machine says stop
    suspend fun run(): RunOutcome {
        val pdfs = source.list() ?: return RunOutcome.COMPLETE
        var retry = false
        for (ref in pdfs.sortedWith(compareBy(nullsLast()) { it.sizeBytes })) {
            when (convertOne(ref)) {
                Step.NEXT -> Unit
                Step.RETRY_LATER -> retry = true
                Step.STOP_RETRY -> return RunOutcome.RETRY
                Step.STOP -> return RunOutcome.COMPLETE
            }
        }
        notes.serverNote = null
        return if (retry) RunOutcome.RETRY else RunOutcome.COMPLETE
    }

    private enum class Step { NEXT, RETRY_LATER, STOP_RETRY, STOP }

    @Suppress("ReturnCount") // one early exit per state of the machine
    private suspend fun convertOne(ref: PdfRef): Step {
        var read: PdfRead? = null
        var sha = hashes.get(ref)
        if (sha == null) {
            read = readOf(ref) ?: return Step.NEXT
            sha = read.sha256
            hashes.put(ref, sha, read.sizeBytes)
        }
        val row = store.get(sha)
        when (PdfConversionRules.decide(row, files.exists(sha))) {
            PdfConversionRules.Decision.Skip -> return Step.NEXT
            PdfConversionRules.Decision.ResetAndConvert -> {
                store.delete(sha)
                files.delete(sha)
            }
            PdfConversionRules.Decision.Convert -> Unit
        }
        val size = read?.sizeBytes ?: ref.sizeBytes
        if (size != null && size > PdfConversionRules.MAX_BYTES) {
            record(sha, ConversionStatus.TOO_BIG, row, PdfConversionRules.tooBigSentence(size), null)
            return Step.NEXT
        }
        val upload = read ?: readOf(ref) ?: return Step.NEXT
        val bytes = upload.bytes
        if (bytes == null) {
            record(sha, ConversionStatus.TOO_BIG, row, PdfConversionRules.tooBigSentence(upload.sizeBytes), null)
            return Step.NEXT
        }
        return apply(sha, row, backend.convert(bytes))
    }

    private suspend fun apply(sha: String, row: BookConversionRow?, reply: ConvertReply): Step = when (reply) {
        is ConvertReply.Converted -> {
            files.write(sha, reply.epub)
            record(sha, ConversionStatus.DONE, row, null, reply.pages)
            Step.NEXT
        }
        is ConvertReply.Refused -> {
            record(sha, reply.status, row, reply.message, null)
            // A FAILED with tries left is retried on backoff; the third failure stops and stays said.
            val triesLeft = (row?.attempts ?: 0) + 1 < PdfConversionRules.MAX_ATTEMPTS
            if (reply.status == ConversionStatus.FAILED && triesLeft) Step.RETRY_LATER else Step.NEXT
        }
        is ConvertReply.Unreachable -> {
            notes.serverNote = reply.sentence
            Step.STOP_RETRY
        }
        is ConvertReply.Unavailable -> {
            notes.serverNote = reply.sentence
            Step.STOP
        }
    }

    private suspend fun record(
        sha: String,
        status: ConversionStatus,
        previous: BookConversionRow?,
        message: String?,
        pages: Int?,
    ) {
        val attempts = (previous?.attempts ?: 0) + 1
        store.upsert(BookConversionRow(sha, status.name, attempts, message, now(), pages))
    }

    // a provider can fail to open or read a file in several ways; any of them skips that file this run
    @Suppress("SwallowedException") // skipped, not lost: the file is tried again on the next run
    private fun readOf(ref: PdfRef): PdfRead? = try {
        source.open(ref)?.use { PdfIdentity.read(it) }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }
}
