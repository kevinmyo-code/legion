package com.kevin.legion.books

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.data.local.BookBookmark
import com.kevin.legion.data.local.BookDao
import com.kevin.legion.data.local.BookRow
import com.kevin.legion.data.local.BookSyncOutboxDao
import com.kevin.legion.data.local.BookSyncOutboxRow
import com.kevin.legion.data.local.CarDatabase
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// The Android side of reading sync (books ticket 09): Room, preferences, the Django backend and the
// WorkManager shell. The state machine is ReadingSyncRunner.

/** [SyncLocal] over Room. */
class RoomSyncLocal(private val dao: BookDao) : SyncLocal {
    override suspend fun books(): List<BookRow> = dao.all()

    override suspend fun row(documentId: String): BookRow? = dao.get(documentId)

    override suspend fun applyPosition(
        readId: String,
        progress: Double,
        chapterLabel: String?,
        pageIndex: Int,
        locatorJson: String?,
        atMs: Long,
    ) {
        if (dao.get(readId) == null) {
            // An EPUB copy of a PDF has no row until it is first opened; make it the way the reader does.
            val pdf = dao.get(ConvertedBooks.originalId(readId)) ?: return
            dao.insertIfAbsent(
                listOf(
                    pdf.copy(
                        documentId = readId, format = BookFormat.EPUB.name, progress = 0.0, chapterLabel = null,
                        pageIndex = 0, pageCount = 0, locatorJson = null, lastReadAtMs = null,
                    ),
                ),
            )
        }
        val pageCount = dao.get(readId)?.pageCount ?: 0
        dao.savePosition(readId, progress, chapterLabel, pageIndex, pageCount, locatorJson, atMs)
    }

    override suspend fun bookmarksWithoutUuid(): List<BookBookmark> = dao.bookmarksWithoutUuid()

    override suspend fun setBookmarkUuid(id: Long, uuid: String) = dao.setBookmarkUuid(id, uuid)

    override suspend fun clearBookmarkUuid(uuid: String) = dao.clearBookmarkUuid(uuid)

    override suspend fun bookmarkByUuid(uuid: String): BookBookmark? = dao.bookmarkByUuid(uuid)

    override suspend fun insertBookmark(bookmark: BookBookmark) {
        dao.addBookmark(bookmark)
    }

    override suspend fun updateBookmark(bookmark: BookBookmark) = dao.updateBookmark(bookmark)

    override suspend fun deleteBookmarkByUuid(uuid: String) = dao.deleteBookmarkByUuid(uuid)
}

/** [SyncOutbox] over Room. */
class RoomSyncOutbox(private val dao: BookSyncOutboxDao) : SyncOutbox {
    override suspend fun all(): List<BookSyncOutboxRow> = dao.all()

    override suspend fun count(): Int = dao.count()

    override suspend fun upsert(row: BookSyncOutboxRow) = dao.upsert(row)

    override suspend fun done(row: BookSyncOutboxRow) = dao.deleteIfUnchanged(row.key, row.queuedAtMs)
}

/**
 * [SyncState] in a small private preferences file: cursors and notes are per device and rebuildable by pulling again.
 */
class PrefsSyncState(context: Context) : SyncState {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override var positionsCursor: String?
        get() = prefs.getString(KEY_POSITIONS, null)
        set(value) = put(KEY_POSITIONS, value)

    override var bookmarksCursor: String?
        get() = prefs.getString(KEY_BOOKMARKS, null)
        set(value) = put(KEY_BOOKMARKS, value)

    override var backfilled: Boolean
        get() = prefs.getBoolean(KEY_BACKFILLED, false)
        set(value) = prefs.edit().putBoolean(KEY_BACKFILLED, value).apply()

    override var waiting: Boolean
        get() = prefs.getBoolean(KEY_WAITING, false)
        set(value) = prefs.edit().putBoolean(KEY_WAITING, value).apply()

    override var note: String?
        get() = prefs.getString(KEY_NOTE, null)
        set(value) = put(KEY_NOTE, value)

    override fun markPickedUp(readId: String, device: String?) = put("$PICKED_PREFIX$readId", device.orEmpty())

    override fun takePickedUp(readId: String): PickedUp? {
        val key = "$PICKED_PREFIX$readId"
        val device = prefs.getString(key, null) ?: return null
        put(key, null)
        return PickedUp(device.ifEmpty { null })
    }

    private fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }

    private companion object {
        const val PREFS = "books_reading_sync"
        const val KEY_POSITIONS = "positions_cursor"
        const val KEY_BOOKMARKS = "bookmarks_cursor"
        const val KEY_BACKFILLED = "backfilled"
        const val KEY_WAITING = "waiting"
        const val KEY_NOTE = "note"
        const val PICKED_PREFIX = "picked:"
    }
}

/** [BookIdentities] over the granted folder and ticket 05's hash cache. */
class AndroidBookIdentities(
    context: Context,
    private val folders: BooksFolderStore = BooksFolderStore(context),
    private val state: PrefsConversionState = PrefsConversionState(context),
) : BookIdentities {
    private val app = context.applicationContext
    private val dao get() = CarDatabase.getDatabase(app).bookDao()

    override suspend fun identityOf(readId: String): BookIdentity? {
        val documentId = ConvertedBooks.originalId(readId)
        val sha = shaOf(documentId) ?: return null
        return BookIdentity(
            sha,
            if (ConvertedBooks.isDerived(readId)) SyncVariant.RESIZABLE else SyncVariant.ORIGINAL,
        )
    }

    /** Cached when the file is unchanged, else hashed now off the main thread (and cached). */
    private suspend fun shaOf(documentId: String): String? = withContext(Dispatchers.IO) {
        val row = dao.get(documentId)
        val format = row?.let { runCatching { BookFormat.valueOf(it.format) }.getOrNull() }
        val tree = folders.treeUri()
        if (row == null || format == null || tree == null) return@withContext state.lastKnown(documentId)
        val source = SafPdfSource(app, folders, onlyPdf = false)
        val ref = source.stat(tree, Book(documentId, row.fileName, format))
        state.get(ref) ?: runCatching {
            source.open(ref)?.use { PdfIdentity.read(it, cap = 0L) }
        }.getOrNull()?.also { state.put(ref, it.sha256, it.sizeBytes) }?.sha256
    }

    override suspend fun hashAll(): Int = withContext(Dispatchers.IO) {
        BookHashPass(SafPdfSource(app, folders, onlyPdf = false), state).run()
    }

    override fun documentsFor(sha256: String): List<String> = state.documentsFor(sha256)
}

/**
 * Ticket 08's API over [EngineHttp]. Field names are the contract's; a body that does not decode is a refusal in words.
 */
@Suppress("TooManyFunctions") // the five API calls plus the row parsers that belong to them
class DjangoReadingSyncBackend(private val http: EngineHttp) : ReadingSyncBackend {
    override suspend fun positions(since: String?): SyncReply<Fetched<RemotePosition>> =
        fetchAll("/api/books/positions", since, ::position)

    override suspend fun putPosition(
        sha256: String,
        variant: SyncVariant,
        write: PositionWrite,
    ): SyncReply<PutPositionResult> {
        val reply = http.put("/api/books/positions/$sha256/${variant.wire}", ReadingSyncJson.positionBody(write))
        return reply.fold(
            onSuccess = { ok ->
                val json = runCatching { JSONObject(ok.body) }.getOrNull()
                val kept = json?.optString("kept") == "newer"
                SyncReply.Ok(PutPositionResult(kept, json?.let(::position).takeIf { kept }))
            },
            onFailure = ::failure,
        )
    }

    override suspend fun bookmarks(since: String?): SyncReply<Fetched<RemoteBookmark>> =
        fetchAll("/api/books/bookmarks", since, ::bookmark)

    override suspend fun putBookmark(uuid: String, write: BookmarkWrite): SyncReply<Unit> =
        http.put("/api/books/bookmarks/$uuid", ReadingSyncJson.bookmarkBody(write))
            .fold(onSuccess = { SyncReply.Ok(Unit) }, onFailure = ::failure)

    override suspend fun deleteBookmark(uuid: String): SyncReply<Unit> =
        http.delete("/api/books/bookmarks/$uuid").fold(
            onSuccess = { SyncReply.Ok(Unit) },
            onFailure = { e ->
                // The engine never having had it is as deleted as it gets.
                val refused = (e as? EngineHttpException)?.failure as? EngineFailure.Refused
                if (refused?.status == NOT_FOUND) SyncReply.Ok(Unit) else failure(e)
            },
        )

    /**
     * Every page since [since]. The envelope is not pinned by ticket 08, so a bare array and
     * `{"results": [...], "next": ...}` are both read; `next` is followed when present and a repeated
     * one stops the loop. The returned cursor is the newest `updated_at`, kept as the engine wrote it.
     */
    @Suppress("ReturnCount") // a failed or unreadable page ends the loop, as does the last page
    private suspend fun <T> fetchAll(
        path: String,
        since: String?,
        row: (JSONObject) -> T?,
    ): SyncReply<Fetched<T>> {
        val rows = mutableListOf<T>()
        var newest: Pair<Instant, String>? = null
        var from = since
        repeat(MAX_PAGES) {
            val ask = from
            val reply = http.get(path, if (ask == null) emptyMap() else mapOf("since" to ask))
            val ok = reply.getOrElse { return failure(it) }
            val (items, next) = runCatching { page(ok.body) }.getOrElse {
                return SyncReply.Rejected("The engine's reading sync answer could not be read, so nothing was applied.")
            }
            for (i in 0 until items.length()) {
                val json = items.optJSONObject(i) ?: continue
                row(json)?.let(rows::add)
                val stamp = json.optString("updated_at").takeIf { it.isNotEmpty() }
                val at = stamp?.let { runCatching { Instant.parse(it) }.getOrNull() }
                val best = newest
                val stamped = if (at != null && stamp != null) at to stamp else null
                if (stamped != null && (best == null || stamped.first.isAfter(best.first))) newest = stamped
            }
            if (next == null || next == from) return SyncReply.Ok(Fetched(rows, newest?.second))
            from = next
        }
        return SyncReply.Ok(Fetched(rows, newest?.second))
    }

    private fun page(body: String): Pair<JSONArray, String?> {
        val trimmed = body.trim()
        if (trimmed.startsWith("[")) return JSONArray(trimmed) to null
        val json = JSONObject(trimmed)
        val next = json.optString("next").takeIf { it.isNotEmpty() && it != "null" }
        return (json.optJSONArray("results") ?: JSONArray()) to next
    }

    private fun failure(e: Throwable): SyncReply<Nothing> = when (val failure = (e as? EngineHttpException)?.failure) {
        is EngineFailure.Unreachable -> SyncReply.Unreachable(
            "The engine could not be reached, so reading progress was not synced: ${failure.message}",
        )
        is EngineFailure.Unauthorized -> SyncReply.Unavailable(failure.sentence)
        is EngineFailure.Refused ->
            // A 5xx reached the engine but failed there; retrying later can work. A 4xx never will.
            if (failure.status >= SERVER_ERROR) {
                SyncReply.Unreachable(
                    "The engine failed on its side (HTTP ${failure.status}), so reading progress waits.",
                )
            } else {
                SyncReply.Rejected(
                    said(failure.body).ifBlank { "The engine refused the reading sync (HTTP ${failure.status})." },
                )
            }
        is EngineFailure.Malformed -> SyncReply.Rejected(failure.sentence)
        null -> SyncReply.Rejected("Reading sync failed for a reason the engine did not give.")
    }

    /** The engine refuses as `{"detail": "<sentence>"}`; show the sentence, not the JSON around it. */
    private fun said(body: String): String =
        runCatching { JSONObject(body).optString("detail") }.getOrNull()?.takeIf { it.isNotBlank() } ?: body

    private fun locator(json: JSONObject): String? = json.optJSONObject("locator")?.toString()

    private fun text(json: JSONObject, key: String): String? =
        json.optString(key).takeIf { it.isNotEmpty() && it != "null" }

    private fun millis(json: JSONObject, key: String): Long? =
        text(json, key)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    @Suppress("ReturnCount") // a row missing a required field is skipped, one exit per field
    private fun position(json: JSONObject): RemotePosition? {
        val variant = SyncVariant.of(json.optString("variant")) ?: return null
        val sha = json.optString("book_sha256").takeIf { it.length == SHA_LENGTH } ?: return null
        return RemotePosition(
            sha256 = sha,
            variant = variant,
            locator = locator(json),
            totalProgression = json.optDouble("total_progression", 0.0),
            chapterLabel = text(json, "chapter_label"),
            recordedAtMs = millis(json, "recorded_at") ?: return null,
            device = text(json, "device"),
        )
    }

    @Suppress("ReturnCount") // a row missing a required field is skipped, one exit per field
    private fun bookmark(json: JSONObject): RemoteBookmark? {
        val variant = SyncVariant.of(json.optString("variant")) ?: return null
        val sha = json.optString("book_sha256").takeIf { it.length == SHA_LENGTH } ?: return null
        val uuid = text(json, "id") ?: return null
        return RemoteBookmark(
            uuid = uuid,
            sha256 = sha,
            variant = variant,
            locator = locator(json),
            totalProgression = json.optDouble("total_progression", 0.0),
            label = text(json, "label"),
            createdAtMs = millis(json, "created_at") ?: return null,
            deletedAtMs = millis(json, "deleted_at"),
        )
    }

    private companion object {
        const val SHA_LENGTH = 64
        const val NOT_FOUND = 404
        const val SERVER_ERROR = 500
        const val MAX_PAGES = 40
    }
}

/** No engine address or token on this device: nothing is sent, and the sentence says why. */
private object NoEngineReadingSync : ReadingSyncBackend {
    private const val SENTENCE = "No engine is set up on this device, so reading progress was not synced."

    override suspend fun positions(since: String?) = SyncReply.Unavailable(SENTENCE)

    override suspend fun putPosition(sha256: String, variant: SyncVariant, write: PositionWrite) =
        SyncReply.Unavailable(SENTENCE)

    override suspend fun bookmarks(since: String?) = SyncReply.Unavailable(SENTENCE)

    override suspend fun putBookmark(uuid: String, write: BookmarkWrite) = SyncReply.Unavailable(SENTENCE)

    override suspend fun deleteBookmark(uuid: String) = SyncReply.Unavailable(SENTENCE)
}

/** Production wiring of the runner for this device. With no engine set up every call answers "unavailable" in words. */
fun readingSyncRunner(context: Context): ReadingSyncRunner {
    val app = context.applicationContext
    val db = CarDatabase.getDatabase(app)
    val http = EngineHttp(EngineConfig(app))
    val backend: ReadingSyncBackend = if (http.isUsable()) DjangoReadingSyncBackend(http) else NoEngineReadingSync
    return ReadingSyncRunner(
        backend = backend,
        outbox = RoomSyncOutbox(db.bookSyncOutboxDao()),
        local = RoomSyncLocal(db.bookDao()),
        identities = AndroidBookIdentities(app),
        state = PrefsSyncState(app),
    )
}

/** The WorkManager shell around [ReadingSyncRunner]: all the logic is there, none here. */
class ReadingSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (readingSyncRunner(applicationContext).run()) {
        SyncOutcome.COMPLETE -> Result.success()
        SyncOutcome.RETRY -> Result.retry()
    }
}

/** Unique work `books-reading-sync`: any network, enqueued when the Books sheet opens and when the reader leaves. */
object ReadingSyncScheduler {
    const val WORK_NAME = "books-reading-sync"
    private const val BACKOFF_MINUTES = 1L

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<ReadingSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        // KEEP: a request while one is queued or running must not restart it; the next open syncs again.
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** The sheet's line: what is waiting and why, from the queue and the last run (no network, no hashing). */
    suspend fun sheetLine(context: Context): String? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val state = PrefsSyncState(app)
        val pending = CarDatabase.getDatabase(app).bookSyncOutboxDao().count()
        ReadingSyncSentences.sheetLine(pending, state.waiting, state.note)
    }
}

/** [ReaderSync] for the reader: queues into Room, pulls through the runner, and keeps one identity lookup per book. */
class AndroidReaderSync(context: Context) : ReaderSync {
    private val app = context.applicationContext
    private val identities = AndroidBookIdentities(app)
    private val outbox get() = RoomSyncOutbox(CarDatabase.getDatabase(app).bookSyncOutboxDao())
    private val known = mutableMapOf<String, BookIdentity>()

    private suspend fun identity(readId: String): BookIdentity? =
        known[readId] ?: identities.identityOf(readId)?.also { known[readId] = it }

    override suspend fun pickUp(readId: String): PickedUp? = readingSyncRunner(app).pickUp(readId)

    override suspend fun positionSaved(
        readId: String,
        progress: Double,
        chapterLabel: String?,
        wireLocator: String?,
        atMs: Long,
    ) {
        // No hashable file (no folder grant): the place is still saved locally, there is just nothing to key it by.
        val id = identity(readId) ?: return
        val clamped = progress.coerceIn(0.0, 1.0)
        val write = PositionWrite(wireLocator, clamped, chapterLabel, atMs, ReadingSyncRunner.DEVICE)
        outbox.upsert(OutboxKeys.positionRow(id, write, atMs))
    }

    override suspend fun bookmarkAdded(readId: String, bookmark: BookBookmark) {
        val uuid = bookmark.syncUuid ?: return
        val id = identity(readId)
        if (id == null) {
            // Nothing to key it by yet. Without an id the next sync run assigns one and queues it, so take this one
            // back.
            RoomSyncLocal(CarDatabase.getDatabase(app).bookDao()).clearBookmarkUuid(uuid)
            return
        }
        val write = BookmarkWrite(
            sha256 = id.sha256,
            variant = id.variant,
            locator = bookmark.locatorJson ?: PagedLocator.of(bookmark.pageIndex),
            totalProgression = bookmark.progress.coerceIn(0.0, 1.0),
            label = bookmark.label,
            createdAtMs = bookmark.createdAtMs,
        )
        outbox.upsert(OutboxKeys.bookmarkPutRow(uuid, write, System.currentTimeMillis()))
    }

    override suspend fun bookmarkDeleted(bookmark: BookBookmark) {
        val uuid = bookmark.syncUuid ?: return // never reached the engine, so there is nothing to delete there
        outbox.upsert(OutboxKeys.bookmarkDeleteRow(uuid, System.currentTimeMillis()))
    }

    override fun kick() = ReadingSyncScheduler.enqueue(app)
}
