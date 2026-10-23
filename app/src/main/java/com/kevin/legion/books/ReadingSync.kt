package com.kevin.legion.books

import com.kevin.legion.data.local.BookBookmark
import com.kevin.legion.data.local.BookRow
import com.kevin.legion.data.local.BookSyncOutboxRow
import org.json.JSONObject

// Reading position and bookmark sync (books ticket 09, rulings in ticket 07, the API is ticket 08's
// contract). This file is the pure half: wire shapes, identity, the queue's keys, the sentences and
// the runner's state machine, all behind ports so every rule is a test with no Android in it.

/** Which text of a book a position belongs to. A PDF read as its EPUB copy syncs as [RESIZABLE]. */
enum class SyncVariant(val wire: String) {
    ORIGINAL("original"),
    RESIZABLE("resizable"),
    ;

    companion object {
        fun of(wire: String): SyncVariant? = entries.firstOrNull { it.wire == wire }
    }
}

/** A book as the engine knows it: the SHA-256 of its file bytes and which variant is being read (ticket 07). */
data class BookIdentity(val sha256: String, val variant: SyncVariant)

/** What the phone sends for one book's place; the hash and variant ride in the path. */
data class PositionWrite(
    /** Readium Locator JSON for an EPUB, `{"page": n}` for a PDF or comic, null when there is none. */
    val locator: String?,
    val totalProgression: Double,
    val chapterLabel: String?,
    val recordedAtMs: Long,
    val device: String,
)

data class RemotePosition(
    val sha256: String,
    val variant: SyncVariant,
    val locator: String?,
    val totalProgression: Double,
    val chapterLabel: String?,
    val recordedAtMs: Long,
    val device: String?,
)

/** What the phone sends to create a bookmark. The note is not here: the phone has no way to type one. */
data class BookmarkWrite(
    val sha256: String,
    val variant: SyncVariant,
    val locator: String?,
    val totalProgression: Double,
    val label: String?,
    val createdAtMs: Long,
)

data class RemoteBookmark(
    val uuid: String,
    val sha256: String,
    val variant: SyncVariant,
    val locator: String?,
    val totalProgression: Double,
    val label: String?,
    val createdAtMs: Long,
    /** Non-null is a tombstone: the bookmark is gone and a phone that has it deletes it. */
    val deletedAtMs: Long?,
)

/** Rows the engine returned, and the cursor to ask `since` next time (the newest `updated_at` seen). */
data class Fetched<T>(val rows: List<T>, val cursor: String?)

/**
 * What the engine's PUT of a position answered: [keptNewer] means a newer one was already stored and [stored] is it.
 */
data class PutPositionResult(val keptNewer: Boolean, val stored: RemotePosition?)

/** What one call to the engine came to. Every non-[Ok] branch is a failure; nothing here reads as success. */
sealed interface SyncReply<out T> {
    data class Ok<T>(val value: T) : SyncReply<T>

    /** Nothing reached the engine, or it failed on its own side: the write waits and is retried. */
    data class Unreachable(val sentence: String) : SyncReply<Nothing>

    /** No engine address or no signed-in token: nothing was sent, and retrying does not help until that is fixed. */
    data class Unavailable(val sentence: String) : SyncReply<Nothing>

    /** The engine said no in words (a 4xx). Retrying the same write would be refused forever. */
    data class Rejected(val sentence: String) : SyncReply<Nothing>
}

/**
 * The seam to the household engine (ticket 08's API). Django in production ([DjangoReadingSyncBackend]), a fake in
 * tests.
 */
interface ReadingSyncBackend {
    suspend fun positions(since: String?): SyncReply<Fetched<RemotePosition>>

    suspend fun putPosition(sha256: String, variant: SyncVariant, write: PositionWrite): SyncReply<PutPositionResult>

    suspend fun bookmarks(since: String?): SyncReply<Fetched<RemoteBookmark>>

    suspend fun putBookmark(uuid: String, write: BookmarkWrite): SyncReply<Unit>

    /** Idempotent: a bookmark the engine never had counts as deleted. */
    suspend fun deleteBookmark(uuid: String): SyncReply<Unit>
}

enum class OutboxKind { POSITION, BOOKMARK_PUT, BOOKMARK_DELETE }

/** The queue's keys: what a write is ABOUT, so queuing again replaces it (see [BookSyncOutboxRow]). */
object OutboxKeys {
    fun position(identity: BookIdentity) = "pos:${identity.sha256}:${identity.variant.wire}"

    fun bookmark(uuid: String) = "bm:$uuid"

    fun positionRow(identity: BookIdentity, write: PositionWrite, atMs: Long) = BookSyncOutboxRow(
        key = position(identity),
        kind = OutboxKind.POSITION.name,
        target = "${identity.sha256}/${identity.variant.wire}",
        payload = ReadingSyncJson.positionBody(write),
        queuedAtMs = atMs,
    )

    fun bookmarkPutRow(uuid: String, write: BookmarkWrite, atMs: Long) = BookSyncOutboxRow(
        key = bookmark(uuid),
        kind = OutboxKind.BOOKMARK_PUT.name,
        target = uuid,
        payload = ReadingSyncJson.bookmarkBody(write),
        queuedAtMs = atMs,
    )

    fun bookmarkDeleteRow(uuid: String, atMs: Long) = BookSyncOutboxRow(
        key = bookmark(uuid),
        kind = OutboxKind.BOOKMARK_DELETE.name,
        target = uuid,
        payload = null,
        queuedAtMs = atMs,
    )
}

/** The JSON bodies of ticket 08's PUTs, built here so the queue can keep them as text. */
object ReadingSyncJson {
    fun positionBody(write: PositionWrite): String = JSONObject().apply {
        put("locator", write.locator?.let { parseLocator(it) } ?: JSONObject.NULL)
        put("total_progression", write.totalProgression)
        put("chapter_label", write.chapterLabel ?: JSONObject.NULL)
        put("recorded_at", java.time.Instant.ofEpochMilli(write.recordedAtMs).toString())
        put("device", write.device)
    }.toString()

    fun bookmarkBody(write: BookmarkWrite): String = JSONObject().apply {
        put("book_sha256", write.sha256)
        put("variant", write.variant.wire)
        put("locator", write.locator?.let { parseLocator(it) } ?: JSONObject.NULL)
        put("total_progression", write.totalProgression)
        put("label", write.label ?: JSONObject.NULL)
        put("note", JSONObject.NULL)
        put("created_at", java.time.Instant.ofEpochMilli(write.createdAtMs).toString())
    }.toString()

    /**
     * A locator is JSON on the wire, not a string holding JSON. Text that is not JSON is sent as null, never mangled.
     */
    private fun parseLocator(text: String): JSONObject? = runCatching { JSONObject(text) }.getOrNull()
}

/**
 * The locator of a PDF or comic position: its source page. The engine's contract says "page index" and
 * pins no shape; `{"page": n}` is this phone's proposal, recorded as an amendment in ticket 08.
 */
object PagedLocator {
    fun of(page: Int): String = JSONObject().put("page", page).toString()

    fun pageOf(locator: String?): Int? =
        locator?.let { runCatching { JSONObject(it).getInt("page") }.getOrNull() }?.takeIf { it >= 0 }
}

/** Every sentence reading sync says, in one place so each is a test. */
object ReadingSyncSentences {
    const val WAITING = "Reading progress will sync when the server is reachable."

    /**
     * The Books sheet's line about reading sync, or null when nothing is waiting. Only an unreachable
     * engine earns "will sync when it is reachable"; a refusal or a signed-out device says its own words,
     * because promising a later sync that cannot happen is a false statement.
     */
    fun sheetLine(pending: Int, waiting: Boolean, note: String?): String? = when {
        pending <= 0 -> null
        waiting -> WAITING
        else -> note
    }

    /** "Picked up where you left off on the web", or near it when only the progress could be applied. */
    fun pickedUp(device: String?, near: Boolean): String {
        val where = when (device?.lowercase()) {
            null, "", "phone" -> "on your other phone"
            "web" -> "on the web"
            else -> "on $device"
        }
        return if (near) "Picked up near where you left off $where." else "Picked up where you left off $where."
    }
}

/**
 * Whether a locator saved by another device can be applied to THIS file as it is. The web reader's
 * locators come from a different toolkit and a converter may have laid a PDF's EPUB copy out
 * differently, so a locator whose chapter file is not in this book is not trusted: the reader falls
 * back to the total progression and says "near". Pure on the hrefs, so it is a test without Readium.
 */
object LocatorFit {
    fun fits(locatorJson: String?, readingOrderHrefs: Set<String>): Boolean {
        val href = locatorJson?.let { runCatching { JSONObject(it).getString("href") }.getOrNull() } ?: return false
        return href.substringBefore('#') in readingOrderHrefs || href in readingOrderHrefs
    }
}

/** documentId to SHA-256 and back, hashing a file the first time it is asked about. */
interface BookIdentities {
    /**
     * What the engine knows [readId] as. A [ConvertedBooks.derivedId] is the PDF's hash, variant resizable. Null when
     * no file can be hashed.
     */
    suspend fun identityOf(readId: String): BookIdentity?

    /** Hashes every book not yet hashed (or changed since), smallest first. Returns how many were hashed now. */
    suspend fun hashAll(): Int

    /** The document ids whose cached hash is [sha256]. */
    fun documentsFor(sha256: String): List<String>
}

/** What the runner reads and writes in Room. */
interface SyncLocal {
    suspend fun books(): List<BookRow>

    suspend fun row(documentId: String): BookRow?

    /** Writes a position pulled from the engine under [readId] (creating an EPUB copy's row when it has none). */
    suspend fun applyPosition(
        readId: String,
        progress: Double,
        chapterLabel: String?,
        pageIndex: Int,
        locatorJson: String?,
        atMs: Long,
    )

    suspend fun bookmarksWithoutUuid(): List<BookBookmark>

    suspend fun setBookmarkUuid(id: Long, uuid: String)

    /** Takes a sync id back off a bookmark that could not be queued, so the next run assigns and queues it. */
    suspend fun clearBookmarkUuid(uuid: String)

    suspend fun bookmarkByUuid(uuid: String): BookBookmark?

    suspend fun insertBookmark(bookmark: BookBookmark)

    suspend fun updateBookmark(bookmark: BookBookmark)

    suspend fun deleteBookmarkByUuid(uuid: String)
}

interface SyncOutbox {
    suspend fun all(): List<BookSyncOutboxRow>

    suspend fun count(): Int

    suspend fun upsert(row: BookSyncOutboxRow)

    /** Removes the row only if nothing replaced it while it was being sent. */
    suspend fun done(row: BookSyncOutboxRow)
}

/** The cursors, the one-time backfill flag and what the last run learned, kept small and per device. */
interface SyncState {
    var positionsCursor: String?
    var bookmarksCursor: String?
    var backfilled: Boolean

    /** True when the last run could not reach the engine and writes are waiting: the sheet says so. */
    var waiting: Boolean

    /** The engine could not be used or refused something, as a sentence; null when the last run was clean. */
    var note: String?

    /** Remembers that a pull moved [readId] to a place another device recorded, so the reader can say so once. */
    fun markPickedUp(readId: String, device: String?)

    /** The device a pull moved [readId] from, once; null when nothing is waiting to be said. */
    fun takePickedUp(readId: String): PickedUp?
}

/** A book that opened at a place another device recorded. [device] is that device's own name for itself. */
data class PickedUp(val device: String?)

/** How a run ended, for the worker. */
enum class SyncOutcome { COMPLETE, RETRY }

/**
 * One sync pass: hash, backfill, send what waits, then pull. Pull only runs after the queue drained
 * completely, so a bookmark deleted while offline can never be resurrected by an older remote copy,
 * and a pull never races a local write it has not yet sent.
 */
@Suppress("TooManyFunctions") // one step of the pass each
class ReadingSyncRunner(
    private val backend: ReadingSyncBackend,
    private val outbox: SyncOutbox,
    private val local: SyncLocal,
    private val identities: BookIdentities,
    private val state: SyncState,
    private val device: String = DEVICE,
    private val now: () -> Long = System::currentTimeMillis,
    private val newUuid: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    /**
     * The last sentence a refused write came back with, kept to the end of the run so a clean finish does not erase it.
     */
    private var rejection: String? = null

    @Suppress("ReturnCount") // a pass ends the moment a step says stop
    suspend fun run(): SyncOutcome {
        rejection = null
        if (identities.hashAll() > 0) {
            // A newly hashed book may match rows the last pull skipped; ask for everything again.
            state.positionsCursor = null
            state.bookmarksCursor = null
        }
        backfill()
        val drained = drain()
        if (drained != null) return drained
        val pulled = pull()
        if (pulled != null) return pulled
        state.waiting = false
        state.note = rejection
        return SyncOutcome.COMPLETE
    }

    /**
     * The reader opening [readId]: send what waits (so a pull never undoes it), pull positions, apply
     * what is newer, and say whether this book moved. Never hashes the whole library, so it is quick
     * enough to sit in front of a book opening; the caller bounds it with a timeout anyway.
     */
    @Suppress("ReturnCount") // quits at the first step that cannot reach the engine
    suspend fun pickUp(readId: String): PickedUp? {
        if (drain() != null) return null
        val reply = backend.positions(state.positionsCursor) as? SyncReply.Ok ?: return null
        reply.value.rows.forEach { applyRemote(it) }
        reply.value.cursor?.let { state.positionsCursor = it }
        return state.takePickedUp(readId)
    }

    /** Ids for bookmarks made before sync existed, and every place read before sync existed, queued once. */
    private suspend fun backfill() {
        for (mark in local.bookmarksWithoutUuid()) {
            val identity = identities.identityOf(mark.documentId) ?: continue
            val uuid = newUuid()
            local.setBookmarkUuid(mark.id, uuid)
            outbox.upsert(OutboxKeys.bookmarkPutRow(uuid, bookmarkWrite(mark, identity), now()))
        }
        if (state.backfilled) return
        @Suppress("LoopWithTooManyJumpStatements") // each continue is one reason a book cannot be queued
        for (row in local.books()) {
            val at = row.lastReadAtMs ?: continue
            val identity = identities.identityOf(row.documentId) ?: continue
            outbox.upsert(OutboxKeys.positionRow(identity, positionWrite(row, at), now()))
        }
        state.backfilled = true
    }

    private fun positionWrite(row: BookRow, at: Long): PositionWrite {
        return PositionWrite(
            locator = if (row.format == BookFormat.EPUB.name) row.locatorJson else PagedLocator.of(row.pageIndex),
            totalProgression = row.progress.coerceIn(0.0, 1.0),
            chapterLabel = row.chapterLabel,
            recordedAtMs = at,
            device = device,
        )
    }

    private fun bookmarkWrite(mark: BookBookmark, identity: BookIdentity) = BookmarkWrite(
        sha256 = identity.sha256,
        variant = identity.variant,
        locator = mark.locatorJson ?: PagedLocator.of(mark.pageIndex),
        totalProgression = mark.progress.coerceIn(0.0, 1.0),
        label = mark.label,
        createdAtMs = mark.createdAtMs,
    )

    /** Sends the queue in order. Null when it is empty, else the outcome that stops the run. */
    @Suppress("ReturnCount") // one early exit per way the engine can refuse to take the queue
    private suspend fun drain(): SyncOutcome? {
        for (row in outbox.all()) {
            val reply = send(row)
            when (reply) {
                is SyncReply.Ok -> {
                    if (row.kind == OutboxKind.POSITION.name) applyKept(reply.value)
                    outbox.done(row)
                }
                // A refused write is dropped: it would be refused forever, and the words reach the sheet.
                is SyncReply.Rejected -> {
                    outbox.done(row)
                    rejection = reply.sentence
                }
                is SyncReply.Unreachable -> return stop(reply.sentence, waiting = true, retry = true)
                is SyncReply.Unavailable -> return stop(reply.sentence, waiting = false, retry = false)
            }
        }
        return null
    }

    private fun stop(sentence: String, waiting: Boolean, retry: Boolean): SyncOutcome {
        state.waiting = waiting
        state.note = sentence
        return if (retry) SyncOutcome.RETRY else SyncOutcome.COMPLETE
    }

    /**
     * One queued write sent. A position's reply is carried as [PutPositionResult] so a kept-newer answer can be
     * applied.
     */
    private suspend fun send(row: BookSyncOutboxRow): SyncReply<Any?> = when (OutboxKind.valueOf(row.kind)) {
        OutboxKind.POSITION -> {
            val (sha, variant) = row.target.split('/').let { it[0] to SyncVariant.of(it.getOrElse(1) { "" }) }
            val write = row.payload?.let(ReadingSyncWire::positionWrite)
            if (variant == null || write == null) {
                SyncReply.Rejected("A queued reading position was unreadable and was dropped.")
            } else {
                backend.putPosition(sha, variant, write)
            }
        }
        OutboxKind.BOOKMARK_PUT -> {
            val write = row.payload?.let(ReadingSyncWire::bookmarkWrite)
            if (write == null) {
                SyncReply.Rejected("A queued bookmark was unreadable and was dropped.")
            } else {
                backend.putBookmark(row.target, write)
            }
        }
        OutboxKind.BOOKMARK_DELETE -> backend.deleteBookmark(row.target)
    }

    private suspend fun applyKept(result: Any?) {
        val kept = result as? PutPositionResult ?: return
        if (kept.keptNewer) kept.stored?.let { applyRemote(it) }
    }

    /** Pulls positions then bookmarks since each cursor; null when both landed. */
    @Suppress("ReturnCount") // one early exit per failed fetch, for each of two fetches
    private suspend fun pull(): SyncOutcome? {
        when (val reply = backend.positions(state.positionsCursor)) {
            is SyncReply.Ok -> {
                reply.value.rows.forEach { applyRemote(it) }
                reply.value.cursor?.let { state.positionsCursor = it }
            }
            is SyncReply.Unreachable -> return stop(reply.sentence, waiting = true, retry = true)
            is SyncReply.Unavailable -> return stop(reply.sentence, waiting = false, retry = false)
            is SyncReply.Rejected -> return stop(reply.sentence, waiting = false, retry = false)
        }
        when (val reply = backend.bookmarks(state.bookmarksCursor)) {
            is SyncReply.Ok -> {
                reply.value.rows.forEach { mergeBookmark(it) }
                reply.value.cursor?.let { state.bookmarksCursor = it }
            }
            is SyncReply.Unreachable -> return stop(reply.sentence, waiting = true, retry = true)
            is SyncReply.Unavailable -> return stop(reply.sentence, waiting = false, retry = false)
            is SyncReply.Rejected -> return stop(reply.sentence, waiting = false, retry = false)
        }
        return null
    }

    /** The ids a remote row's book is read under on this phone: one per local copy of the file. */
    private fun readIds(sha: String, variant: SyncVariant): List<String> =
        identities.documentsFor(sha).map { if (variant == SyncVariant.RESIZABLE) ConvertedBooks.derivedId(it) else it }

    /** Newest wins: a remote place is applied only when it is later than the one this phone recorded. */
    private suspend fun applyRemote(remote: RemotePosition) {
        @Suppress("LoopWithTooManyJumpStatements") // one continue per reason a copy is skipped
        for (readId in readIds(remote.sha256, remote.variant)) {
            val row = local.row(readId)
            // A row for an EPUB copy may not exist yet; applyPosition makes it from the PDF's row.
            val known = row ?: local.row(ConvertedBooks.originalId(readId)) ?: continue
            val localAt = row?.lastReadAtMs ?: 0L
            if (remote.recordedAtMs <= localAt) continue
            val epub = if (row != null) row.format == BookFormat.EPUB.name else true
            val page = if (epub) 0 else PagedLocator.pageOf(remote.locator) ?: pageFromProgress(known, remote)
            local.applyPosition(
                readId,
                remote.totalProgression.coerceIn(0.0, 1.0),
                remote.chapterLabel,
                page,
                if (epub) remote.locator else null,
                remote.recordedAtMs,
            )
            state.markPickedUp(readId, remote.device)
        }
    }

    private fun pageFromProgress(row: BookRow, remote: RemotePosition): Int {
        val last = (row.pageCount - 1).coerceAtLeast(0)
        return (remote.totalProgression * last).toInt().coerceIn(0, last)
    }

    /** Merge by uuid: a tombstone deletes here, a new one is added, a known one is refreshed. */
    private suspend fun mergeBookmark(remote: RemoteBookmark) {
        if (remote.deletedAtMs != null) {
            local.deleteBookmarkByUuid(remote.uuid)
            return
        }
        val existing = local.bookmarkByUuid(remote.uuid)
        for (readId in readIds(remote.sha256, remote.variant)) {
            if (local.row(readId) == null && local.row(ConvertedBooks.originalId(readId)) == null) continue
            val epub = remote.variant == SyncVariant.RESIZABLE || local.row(readId)?.format == BookFormat.EPUB.name
            val page = if (epub) 0 else PagedLocator.pageOf(remote.locator) ?: 0
            val mark = BookBookmark(
                id = existing?.id ?: 0,
                documentId = readId,
                label = remote.label ?: "Bookmark · ${(remote.totalProgression * PERCENT).toInt()}%",
                locatorJson = if (epub) remote.locator else null,
                pageIndex = page,
                progress = remote.totalProgression.coerceIn(0.0, 1.0),
                createdAtMs = remote.createdAtMs,
                syncUuid = remote.uuid,
            )
            if (existing == null) local.insertBookmark(mark) else local.updateBookmark(mark)
            return
        }
    }

    companion object {
        /** What this phone calls itself on the engine; the web reader says "your other phone" for it. */
        const val DEVICE = "phone"
        private const val PERCENT = 100
    }
}

/**
 * Reads back the bodies [ReadingSyncJson] built, so the queue can hold text and the backend still gets typed values.
 */
object ReadingSyncWire {
    fun positionWrite(body: String): PositionWrite? = runCatching {
        val json = JSONObject(body)
        PositionWrite(
            locator = json.optJSONObject("locator")?.toString(),
            totalProgression = json.getDouble("total_progression"),
            chapterLabel = if (json.isNull("chapter_label")) null else json.getString("chapter_label"),
            recordedAtMs = java.time.Instant.parse(json.getString("recorded_at")).toEpochMilli(),
            device = json.getString("device"),
        )
    }.getOrNull()

    fun bookmarkWrite(body: String): BookmarkWrite? = runCatching {
        val json = JSONObject(body)
        BookmarkWrite(
            sha256 = json.getString("book_sha256"),
            variant = SyncVariant.of(json.getString("variant")) ?: error("variant"),
            locator = json.optJSONObject("locator")?.toString(),
            totalProgression = json.getDouble("total_progression"),
            label = if (json.isNull("label")) null else json.getString("label"),
            createdAtMs = java.time.Instant.parse(json.getString("created_at")).toEpochMilli(),
        )
    }.getOrNull()
}

/**
 * What the reader asks of reading sync. Production is [AndroidReaderSync]; [None] is for tests and
 * anywhere sync is not wired. Writes only queue (and kick the worker): a page turn never waits on the network.
 */
interface ReaderSync {
    /**
     * Pulls and applies a newer place for [readId] before it opens; null when nothing moved or the engine was not
     * reachable.
     */
    suspend fun pickUp(readId: String): PickedUp?

    /** A place was saved. [wireLocator] is Readium's JSON for an EPUB, [PagedLocator.of] for a PDF or comic. */
    suspend fun positionSaved(readId: String, progress: Double, chapterLabel: String?, wireLocator: String?, atMs: Long)

    suspend fun bookmarkAdded(readId: String, bookmark: BookBookmark)

    suspend fun bookmarkDeleted(bookmark: BookBookmark)

    /** Starts a send now (the reader is leaving, or a bookmark changed). */
    fun kick()

    companion object {
        val None: ReaderSync = object : ReaderSync {
            override suspend fun pickUp(readId: String): PickedUp? = null

            override suspend fun positionSaved(
                readId: String,
                progress: Double,
                chapterLabel: String?,
                wireLocator: String?,
                atMs: Long,
            ) = Unit

            override suspend fun bookmarkAdded(readId: String, bookmark: BookBookmark) = Unit

            override suspend fun bookmarkDeleted(bookmark: BookBookmark) = Unit

            override fun kick() = Unit
        }
    }
}

/**
 * Where an EPUB opens: [locatorJson] to hand the navigator, and [near] when it is the best guess from the progress
 * alone.
 */
data class StartPlace(val locatorJson: String?, val near: Boolean)

/** The pure half of choosing an EPUB's starting place, so the "near" fallback is a test without Readium. */
object EpubStart {
    /**
     * The index of the position whose progression is the latest not past [target]; 0 when all are past; null for none.
     */
    fun nearestIndex(progressions: List<Double>, target: Double): Int? {
        if (progressions.isEmpty()) return null
        val after = progressions.indexOfFirst { it > target }
        return when (after) {
            -1 -> progressions.lastIndex
            0 -> 0
            else -> after - 1
        }
    }
}

/**
 * The hash pass (ticket 09): every book gets a SHA-256, smallest first, once per file. The cache is
 * ticket 05's [PdfHashCache], so a PDF the conversion runner already hashed is not read again and a
 * file whose size or modified time changed is.
 */
class BookHashPass(private val source: PdfSource, private val hashes: PdfHashCache) {
    /** Hashes what the cache cannot vouch for and returns how many files were read. */
    suspend fun run(): Int {
        var hashed = 0
        @Suppress("LoopWithTooManyJumpStatements") // a cached or unreadable file is skipped, nothing else
        for (ref in (source.list() ?: return 0).sortedWith(compareBy(nullsLast()) { it.sizeBytes })) {
            if (hashes.get(ref) != null) continue
            val read = readOf(ref) ?: continue
            hashes.put(ref, read.sha256, read.sizeBytes)
            hashed++
        }
        return hashed
    }

    // a provider can fail to open or read a file in several ways; any of them skips that file this pass
    @Suppress("SwallowedException") // skipped, not lost: the file is tried again on the next pass
    private fun readOf(ref: PdfRef): PdfRead? = try {
        source.open(ref)?.use { PdfIdentity.read(it, cap = 0L) }
    } catch (e: java.io.IOException) {
        null
    } catch (e: SecurityException) {
        null
    }
}
