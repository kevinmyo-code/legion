package com.kevin.legion.books

import android.content.Context
import android.provider.DocumentsContract
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.data.local.BookConversionDao
import com.kevin.legion.data.local.BookConversionRow
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.games.SafRomTree
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

// The Android side of PDF conversion (books ticket 05): the SAF source, Room store, app-private
// files, the Django backend, and the WorkManager shell. The state machine is PdfConversionRunner.

/** [ConversionStore] over Room. */
class RoomConversionStore(private val dao: BookConversionDao) : ConversionStore {
    override suspend fun get(sha256: String) = dao.get(sha256)

    override suspend fun upsert(row: BookConversionRow) = dao.upsert(row)

    override suspend fun delete(sha256: String) = dao.delete(sha256)
}

/** [ConvertedFiles] under `filesDir/books/converted/`, never the books folder (ticket 03 ruling 3). */
class AppConvertedFiles(filesDir: File) : ConvertedFiles {
    private val dir = File(filesDir, "books/converted")

    override fun file(sha256: String) = File(dir, "$sha256.epub")

    override fun exists(sha256: String) = file(sha256).let { it.isFile && it.length() > 0L }

    override fun write(sha256: String, bytes: ByteArray) {
        dir.mkdirs()
        val temp = File(dir, "$sha256.epub.part")
        temp.writeBytes(bytes)
        check(temp.renameTo(file(sha256))) { "Could not move the converted EPUB into place." }
    }

    override fun delete(sha256: String) {
        file(sha256).delete()
    }
}

/**
 * [PdfHashCache] and [ConversionNotes] in one small private preferences file, the way
 * [ReaderSettingsStore] keeps its per-book facts: no query, nothing worth a Room table, rebuildable
 * by hashing again. Each entry is `sha256|size|modifiedMs`.
 */
class PrefsConversionState(context: Context) : PdfHashCache, ConversionNotes {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun get(ref: PdfRef): String? {
        val parts = prefs.getString(key(ref.book.documentId), null)?.split(SEP) ?: return null
        val fresh = parts.size == ENTRY_PARTS && parts[1] == ref.sizeBytes.toString() &&
            parts[2] == ref.modifiedMs.toString()
        // An unknown size or time can never be proven unchanged, so it always rehashes.
        return parts[0].takeIf { fresh && ref.sizeBytes != null && ref.modifiedMs != null }
    }

    override fun lastKnown(documentId: String): String? =
        prefs.getString(key(documentId), null)?.substringBefore(SEP)?.takeIf { it.isNotEmpty() }

    override fun put(ref: PdfRef, sha256: String, sizeBytes: Long) {
        val size = ref.sizeBytes ?: sizeBytes
        prefs.edit().putString(key(ref.book.documentId), listOf(sha256, size, ref.modifiedMs).joinToString(SEP)).apply()
    }

    override var serverNote: String?
        get() = prefs.getString(KEY_NOTE, null)
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY_NOTE) else putString(KEY_NOTE, value) }.apply()
        }

    /**
     * The document ids whose last cached hash is [sha256] (reading sync, ticket 09, matches a remote row to a file by
     * it).
     */
    fun documentsFor(sha256: String): List<String> =
        prefs.all.entries
            .filter { (k, v) -> k.startsWith(KEY_PREFIX) && (v as? String)?.substringBefore(SEP) == sha256 }
            .map { it.key.removePrefix(KEY_PREFIX) }

    private fun key(documentId: String) = "$KEY_PREFIX$documentId"

    private companion object {
        const val PREFS = "books_pdf_conversion"
        const val KEY_NOTE = "server_note"
        const val KEY_PREFIX = "hash:"
        const val SEP = "|"
        const val ENTRY_PARTS = 3
    }
}

/** [PdfSource] over the granted Books folder. A document's size and modified time come from the provider. */
class SafPdfSource(
    context: Context,
    private val folders: BooksFolderStore = BooksFolderStore(context),
    /** False lists every readable format, for the hash pass of reading sync (ticket 09), which hashes every book. */
    private val onlyPdf: Boolean = true,
) : PdfSource {
    private val app = context.applicationContext

    @Suppress("ReturnCount") // no folder, no grant, an unreadable root: each is its own way to answer null
    override fun list(): List<PdfRef>? {
        val tree = folders.treeUri() ?: return null
        if (!folders.isGranted(tree)) return null
        val scan = BookLibrary(SafRomTree(app.contentResolver, tree)).scan(SafRomTree.rootDocumentId(tree))
        if (scan.unreadable) return null
        return scan.books.filter { !onlyPdf || it.format == BookFormat.PDF }.map { book -> stat(tree, book) }
    }

    override fun open(ref: PdfRef): InputStream? {
        val tree = folders.treeUri() ?: return null
        return app.contentResolver.openInputStream(SafRomTree.documentUri(tree, ref.book.documentId))
    }

    /** The ref for one PDF with the provider's size and modified time (either may be null). */
    fun stat(tree: android.net.Uri, book: Book): PdfRef {
        val uri = SafRomTree.documentUri(tree, book.documentId)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        var size: Long? = null
        var modified: Long? = null
        runCatching {
            app.contentResolver.query(uri, columns, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    size = if (c.isNull(0)) null else c.getLong(0)
                    modified = if (c.isNull(1)) null else c.getLong(1)
                }
            }
        }
        return PdfRef(book, size, modified)
    }
}

/** The household engine's `POST /api/books/convert` (ticket 04): the PDF as the raw body, an EPUB back. */
class DjangoBookConvertBackend(private val http: EngineHttp) : BookConvertBackend {
    override suspend fun convert(pdf: ByteArray): ConvertReply {
        val reply = http.postBytes(PATH, pdf, ContentType.Application.Pdf, listOf(PAGES_HEADER))
        reply.getOrNull()?.let { ok ->
            return ConvertReply.Converted(ok.bytes, ok.headers[PAGES_HEADER]?.toIntOrNull())
        }
        return when (val failure = (reply.exceptionOrNull() as? EngineHttpException)?.failure) {
            is EngineFailure.Unreachable -> ConvertReply.Unreachable(
                "The engine could not be reached, so nothing was converted: ${failure.message}",
            )
            is EngineFailure.Unauthorized -> ConvertReply.Unavailable(failure.sentence)
            is EngineFailure.Refused -> refusal(failure)
            is EngineFailure.Malformed -> ConvertReply.Refused(ConversionStatus.FAILED, failure.sentence)
            null -> ConvertReply.Refused(ConversionStatus.FAILED, NO_REASON)
        }
    }

    /** The server's `{"code", "message"}` becomes a status and its own sentence, verbatim. */
    private fun refusal(failure: EngineFailure.Refused): ConvertReply {
        val json = runCatching { JSONObject(failure.body) }.getOrNull()
        val message = json?.optString("message")?.takeIf { it.isNotBlank() }
        val status = when (json?.optString("code")) {
            "scanned" -> ConversionStatus.SCANNED
            "unreadable" -> ConversionStatus.UNREADABLE
            "too_big" -> ConversionStatus.TOO_BIG
            else -> ConversionStatus.FAILED
        }
        return ConvertReply.Refused(status, message ?: "The engine refused the conversion (HTTP ${failure.status}).")
    }

    private companion object {
        const val PATH = "/api/books/convert"
        const val PAGES_HEADER = "X-Legion-Pages"
        const val NO_REASON = "The conversion failed for a reason the engine did not give."
    }
}

/**
 * The backend for this device's engine, or null with no engine address or no signed-in token.
 * A conversion takes up to ~50 s server-side and uploads up to 30 MB, so this client carries its own
 * timeouts instead of the shared client's defaults (which would cut the wait short).
 */
fun djangoBookConvertBackendOrNull(context: Context): BookConvertBackend? {
    val http = EngineHttp(EngineConfig(context.applicationContext), conversionClient)
    return if (http.isUsable()) DjangoBookConvertBackend(http) else null
}

private const val CONVERT_TIMEOUT_MS = 150_000L
private const val CONNECT_TIMEOUT_MS = 20_000L

private val conversionClient: HttpClient by lazy {
    HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = CONVERT_TIMEOUT_MS
            socketTimeoutMillis = CONVERT_TIMEOUT_MS
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
        }
    }
}

/** Production wiring of the runner for this device. */
fun pdfConversionRunner(context: Context): PdfConversionRunner {
    val app = context.applicationContext
    val state = PrefsConversionState(app)
    val backend = djangoBookConvertBackendOrNull(app) ?: BookConvertBackend {
        ConvertReply.Unavailable("No engine is set up on this device, so PDFs were not converted.")
    }
    return PdfConversionRunner(
        source = SafPdfSource(app),
        backend = backend,
        store = RoomConversionStore(CarDatabase.getDatabase(app).bookConversionDao()),
        files = AppConvertedFiles(app.filesDir),
        hashes = state,
        notes = state,
    )
}

/** The WorkManager shell around [PdfConversionRunner]: all the logic is there, none here. */
class PdfConversionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (pdfConversionRunner(applicationContext).run()) {
        RunOutcome.COMPLETE -> Result.success()
        RunOutcome.RETRY -> Result.retry()
    }
}

/** Unique work `books-pdf-convert`: unmetered network, battery not low, enqueued after every folder scan. */
object PdfConversionScheduler {
    const val WORK_NAME = "books-pdf-convert"
    private const val BACKOFF_MINUTES = 5L

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<PdfConversionWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        // KEEP: a scan while a run is queued or running must not restart it.
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** True while the worker is mid-run, so the viewer says "Converting" rather than "Waiting for Wi-Fi". */
    fun isRunning(context: Context): Boolean = runCatching {
        WorkManager.getInstance(context.applicationContext).getWorkInfosForUniqueWork(WORK_NAME).get()
            .any { it.state == WorkInfo.State.RUNNING }
    }.getOrDefault(false)
}

/** [PdfConversions] over Room, the files and the hash cache; what the reader and the sheet ask. */
class AndroidPdfConversions(context: Context) : PdfConversions {
    private val app = context.applicationContext
    private val folders = BooksFolderStore(app)
    private val state = PrefsConversionState(app)
    private val files = AppConvertedFiles(app.filesDir)
    private val dao get() = CarDatabase.getDatabase(app).bookConversionDao()

    /** The SHA-256 of [book]: cached when the file is unchanged, else hashed now (and cached). */
    private suspend fun shaOf(book: Book): String? = withContext(Dispatchers.IO) {
        val tree = folders.treeUri() ?: return@withContext null
        val source = SafPdfSource(app, folders)
        val ref = source.stat(tree, book)
        state.get(ref) ?: runCatching {
            source.open(ref)?.use { PdfIdentity.read(it, cap = 0L) }
        }.getOrNull()?.also { state.put(ref, it.sha256, it.sizeBytes) }?.sha256
    }

    override suspend fun copyFor(book: Book): ConvertedCopy? {
        val sha = shaOf(book) ?: return null
        val row = dao.get(sha)
        return if (row?.status == ConversionStatus.DONE.name && files.exists(sha)) {
            ConvertedCopy(files.file(sha), sha)
        } else {
            null
        }
    }

    override suspend fun noteFor(book: Book): String? {
        val sha = shaOf(book)
        val row = sha?.let { dao.get(it) }
        val running = withContext(Dispatchers.IO) { PdfConversionScheduler.isRunning(app) }
        return PdfConversionSentences.viewerLine(row, running, state.serverNote)
    }

    /** How many PDFs still owe a conversion, from what is cached (no hashing): the sheet's count. */
    suspend fun pendingCount(pdfs: List<Book>): Int = withContext(Dispatchers.IO) {
        val rows = dao.all().associateBy { it.sha256 }
        pdfs.count { book ->
            val sha = state.lastKnown(book.documentId)
            PdfConversionRules.isPending(sha?.let { rows[it] })
        }
    }

    fun serverNote(): String? = state.serverNote

    /** Whether [book] has a DONE conversion with its file present, by the cache alone (cheap; for the sheet's tag). */
    suspend fun isResizable(book: Book): Boolean = withContext(Dispatchers.IO) {
        val sha = state.lastKnown(book.documentId) ?: return@withContext false
        dao.get(sha)?.status == ConversionStatus.DONE.name && files.exists(sha)
    }
}
