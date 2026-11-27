package com.kevin.legion.books

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.games.SafRomTree
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

// The Android side of the library download (alfred-builds ticket 06, phone half): the Django
// backend, the SAF folder writer, preferences and the WorkManager shell. The rules are
// LibrarySyncRunner's.

/** `GET /api/books/library` and the signed download link, over [EngineHttp]. */
class DjangoLibraryBackend(private val http: EngineHttp) : LibraryBackend {
    override suspend fun list(): SyncReply<List<LibraryBook>> =
        http.get("/api/books/library").fold(
            onSuccess = { ok ->
                runCatching { parse(ok.body) }.getOrElse {
                    SyncReply.Rejected("The engine's library answer could not be read, so nothing was downloaded.")
                }
            },
            onFailure = ::failure,
        )

    override suspend fun download(id: Long): SyncReply<ByteArray> {
        val link = http.get("/api/books/library/$id/download").getOrElse { return failure(it) }
        val target = runCatching { JSONObject(link.body).getString("url") }.getOrNull()
            ?.let { runCatching { URI(it) }.getOrNull() }
            ?: return SyncReply.Rejected("The engine's download link could not be read.")
        // Path and query only: the host the engine put in the link may not be the one this phone reaches it by.
        val path = target.rawPath + (target.rawQuery?.let { "?$it" } ?: "")
        return http.getBytes(path).fold(onSuccess = { SyncReply.Ok(it.bytes) }, onFailure = ::failure)
    }

    private fun parse(body: String): SyncReply<List<LibraryBook>> {
        val rows = JSONObject(body).getJSONArray("results")
        val books = (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            val sha = row.optString("sha256")
            if (!row.has("id") || sha.isEmpty() || row.optString("format", "epub") != "epub") return@mapNotNull null
            LibraryBook(
                id = row.getLong("id"),
                title = row.optString("title"),
                author = row.optString("author"),
                sha256 = sha,
                size = row.optLong("size"),
                licenceNote = row.optString("licence_note"),
            )
        }
        return SyncReply.Ok(books)
    }

    private fun failure(e: Throwable): SyncReply<Nothing> = when (val f = (e as? EngineHttpException)?.failure) {
        is EngineFailure.Unreachable -> SyncReply.Unreachable("The engine could not be reached: ${f.message}")
        is EngineFailure.Unauthorized -> SyncReply.Unavailable(f.sentence)
        is EngineFailure.Refused ->
            if (f.status >= SERVER_ERROR) {
                SyncReply.Unreachable("The engine failed on its side (HTTP ${f.status}).")
            } else {
                SyncReply.Rejected(f.body.ifBlank { "The engine refused the library request (HTTP ${f.status})." })
            }
        is EngineFailure.Malformed -> SyncReply.Rejected(f.sentence)
        null -> SyncReply.Unreachable("The library request failed: ${e.message}")
    }

    private companion object {
        const val SERVER_ERROR = 500
    }
}

/** No engine address or token on this device: nothing is fetched, and the sentence says why. */
private object NoEngineLibrary : LibraryBackend {
    private const val SENTENCE = "No engine is set up on this device, so the library was not checked."

    override suspend fun list() = SyncReply.Unavailable(SENTENCE)

    override suspend fun download(id: Long) = SyncReply.Unavailable(SENTENCE)
}

/** The granted Books tree, written through the Storage Access Framework. */
class SafLibraryFolder(context: Context, private val folders: BooksFolderStore = BooksFolderStore(context)) :
    LibraryFolder {
    private val app = context.applicationContext

    override fun canWrite(): Boolean {
        val tree = folders.treeUri() ?: return false
        return app.contentResolver.persistedUriPermissions.any {
            it.uri == tree && it.isReadPermission && it.isWritePermission
        }
    }

    override fun has(fileName: String): Boolean {
        val tree = folders.treeUri() ?: return false
        val root = SafRomTree.rootDocumentId(tree)
        return SafRomTree(app.contentResolver, tree).children(root).any { it.name == fileName }
    }

    override fun write(fileName: String, bytes: ByteArray): Boolean {
        val tree = folders.treeUri() ?: return false
        val parent: Uri = SafRomTree.documentUri(tree, SafRomTree.rootDocumentId(tree))
        val created = runCatching {
            DocumentsContract.createDocument(app.contentResolver, parent, EPUB_MIME, fileName)
        }.getOrNull() ?: return false
        val wrote = runCatching {
            app.contentResolver.openOutputStream(created, "w")?.use { it.write(bytes) } != null
        }.getOrDefault(false)
        // A half-written book is worse than none: take the empty shell back out.
        if (!wrote) runCatching { DocumentsContract.deleteDocument(app.contentResolver, created) }
        return wrote
    }

    private companion object {
        const val EPUB_MIME = "application/epub+zip"
    }
}

class PrefsLibraryState(context: Context) : LibraryState {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override val fetched: MutableSet<Long> = object : AbstractMutableSet<Long>() {
        private fun ids() =
            prefs.getStringSet(KEY_FETCHED, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

        override val size get() = ids().size

        override fun iterator(): MutableIterator<Long> = ids().toMutableSet().iterator()

        override fun contains(element: Long) = element in ids()

        override fun add(element: Long): Boolean {
            if (element in ids()) return false
            prefs.edit().putStringSet(KEY_FETCHED, (ids() + element).map(Long::toString).toSet()).apply()
            return true
        }
    }

    override var note: String?
        get() = prefs.getString(KEY_NOTE, null)
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY_NOTE) else putString(KEY_NOTE, value) }.apply()
        }

    private companion object {
        const val PREFS = "books_library_sync"
        const val KEY_FETCHED = "fetched_ids"
        const val KEY_NOTE = "note"
    }
}

fun libraryRunner(context: Context): LibrarySyncRunner {
    val app = context.applicationContext
    val http = EngineHttp(EngineConfig(app))
    val backend: LibraryBackend = if (http.isUsable()) DjangoLibraryBackend(http) else NoEngineLibrary
    return LibrarySyncRunner(backend, SafLibraryFolder(app), PrefsLibraryState(app))
}

class BookLibraryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (libraryRunner(applicationContext).run()) {
        SyncOutcome.COMPLETE -> Result.success()
        SyncOutcome.RETRY -> Result.retry()
    }
}

/**
 * Downloads wait for an unmetered network (Wi-Fi): a book is megabytes. Enqueued when the Books sheet
 * opens, and once a day in the background, both under one name so they never run twice at once.
 */
object BookLibraryScheduler {
    const val WORK_NAME = "books-library-download"
    const val PERIODIC_NAME = "books-library-download-daily"
    private const val BACKOFF_MINUTES = 5L
    private val wifi = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

    fun enqueue(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        val once = OneTimeWorkRequestBuilder<BookLibraryWorker>()
            .setConstraints(wifi)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        manager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, once)
        val daily = PeriodicWorkRequestBuilder<BookLibraryWorker>(1, TimeUnit.DAYS).setConstraints(wifi).build()
        manager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, daily)
    }

    /** The sheet's line: what the last run said, from preferences (no network). */
    suspend fun sheetLine(context: Context): String? =
        withContext(Dispatchers.IO) { PrefsLibraryState(context).note }
}
