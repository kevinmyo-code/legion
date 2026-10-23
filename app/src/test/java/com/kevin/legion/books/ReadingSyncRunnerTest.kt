package com.kevin.legion.books

import com.kevin.legion.data.local.BookBookmark
import com.kevin.legion.data.local.BookRow
import com.kevin.legion.data.local.BookSyncOutboxRow
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading sync's state machine against fakes for every port (books ticket 09): queue and retry,
 * newest-wins on pull, tombstones, variant mapping, the "near" fallback and the offline sentence.
 * No WorkManager, no Android, no network.
 */
class ReadingSyncRunnerTest {
    private val sha = "a".repeat(64)

    private class FakeBackend : ReadingSyncBackend {
        var positionReply: SyncReply<Fetched<RemotePosition>> = SyncReply.Ok(Fetched(emptyList(), null))
        var bookmarkReply: SyncReply<Fetched<RemoteBookmark>> = SyncReply.Ok(Fetched(emptyList(), null))
        var putReply: () -> SyncReply<PutPositionResult> = { SyncReply.Ok(PutPositionResult(false, null)) }
        var bookmarkPutReply: SyncReply<Unit> = SyncReply.Ok(Unit)
        var deleteReply: SyncReply<Unit> = SyncReply.Ok(Unit)
        val calls = mutableListOf<String>()
        val positionPuts = mutableListOf<Triple<String, SyncVariant, PositionWrite>>()
        val bookmarkPuts = mutableListOf<Pair<String, BookmarkWrite>>()
        val asked = mutableListOf<String?>()

        override suspend fun positions(since: String?): SyncReply<Fetched<RemotePosition>> {
            calls += "positions"
            asked += since
            return positionReply
        }

        override suspend fun putPosition(sha256: String, variant: SyncVariant, write: PositionWrite):
            SyncReply<PutPositionResult> {
            calls += "putPosition"
            positionPuts += Triple(sha256, variant, write)
            return putReply()
        }

        override suspend fun bookmarks(since: String?): SyncReply<Fetched<RemoteBookmark>> {
            calls += "bookmarks"
            return bookmarkReply
        }

        override suspend fun putBookmark(uuid: String, write: BookmarkWrite): SyncReply<Unit> {
            calls += "putBookmark"
            bookmarkPuts += uuid to write
            return bookmarkPutReply
        }

        override suspend fun deleteBookmark(uuid: String): SyncReply<Unit> {
            calls += "deleteBookmark"
            return deleteReply
        }
    }

    private class FakeOutbox : SyncOutbox {
        val rows = linkedMapOf<String, BookSyncOutboxRow>()

        override suspend fun all() = rows.values.sortedBy { it.queuedAtMs }

        override suspend fun count() = rows.size

        override suspend fun upsert(row: BookSyncOutboxRow) {
            rows[row.key] = row
        }

        override suspend fun done(row: BookSyncOutboxRow) {
            if (rows[row.key]?.queuedAtMs == row.queuedAtMs) rows.remove(row.key)
        }
    }

    private class FakeState : SyncState {
        override var positionsCursor: String? = null
        override var bookmarksCursor: String? = null
        override var backfilled = false
        override var waiting = false
        override var note: String? = null
        val picked = mutableMapOf<String, String?>()

        override fun markPickedUp(readId: String, device: String?) {
            picked[readId] = device
        }

        override fun takePickedUp(readId: String): PickedUp? =
            if (picked.containsKey(readId)) PickedUp(picked.remove(readId)) else null
    }

    private class FakeIdentities(val byDocument: Map<String, String>, var hashedNow: Int = 0) : BookIdentities {
        override suspend fun identityOf(readId: String): BookIdentity? {
            val sha = byDocument[ConvertedBooks.originalId(readId)] ?: return null
            return BookIdentity(
                sha,
                if (ConvertedBooks.isDerived(readId)) SyncVariant.RESIZABLE else SyncVariant.ORIGINAL,
            )
        }

        override suspend fun hashAll() = hashedNow

        override fun documentsFor(sha256: String) = byDocument.filterValues { it == sha256 }.keys.toList()
    }

    private class FakeLocal(books: List<BookRow>) : SyncLocal {
        val rows = books.associateBy { it.documentId }.toMutableMap()
        val marks = mutableListOf<BookBookmark>()
        var nextId = 1L

        override suspend fun books() = rows.values.toList()

        override suspend fun row(documentId: String) = rows[documentId]

        override suspend fun applyPosition(
            readId: String,
            progress: Double,
            chapterLabel: String?,
            pageIndex: Int,
            locatorJson: String?,
            atMs: Long,
        ) {
            val base = rows[readId] ?: rows[ConvertedBooks.originalId(readId)]?.copy(
                documentId = readId, format = BookFormat.EPUB.name,
            ) ?: return
            rows[readId] = base.copy(
                progress = progress, chapterLabel = chapterLabel, pageIndex = pageIndex,
                locatorJson = locatorJson, lastReadAtMs = atMs,
            )
        }

        override suspend fun bookmarksWithoutUuid() = marks.filter { it.syncUuid == null }

        override suspend fun setBookmarkUuid(id: Long, uuid: String) {
            marks.replaceAll { if (it.id == id) it.copy(syncUuid = uuid) else it }
        }

        override suspend fun clearBookmarkUuid(uuid: String) {
            marks.replaceAll { if (it.syncUuid == uuid) it.copy(syncUuid = null) else it }
        }

        override suspend fun bookmarkByUuid(uuid: String) = marks.firstOrNull { it.syncUuid == uuid }

        override suspend fun insertBookmark(bookmark: BookBookmark) {
            marks += bookmark.copy(id = nextId++)
        }

        override suspend fun updateBookmark(bookmark: BookBookmark) {
            marks.replaceAll { if (it.id == bookmark.id) bookmark else it }
        }

        override suspend fun deleteBookmarkByUuid(uuid: String) {
            marks.removeAll { it.syncUuid == uuid }
        }
    }

    private fun row(
        id: String,
        format: BookFormat = BookFormat.EPUB,
        at: Long? = null,
        progress: Double = 0.0,
        locator: String? = null,
        pageCount: Int = 0,
    ) = BookRow(
        documentId = id, fileName = "$id.${format.extension}", format = format.name, title = id, author = null,
        metadataRead = true, progress = progress, chapterLabel = null, pageIndex = 0, pageCount = pageCount,
        locatorJson = locator, lastReadAtMs = at, firstSeenAtMs = 0L,
    )

    private class Rig(
        val backend: FakeBackend = FakeBackend(),
        val outbox: FakeOutbox = FakeOutbox(),
        val state: FakeState = FakeState(),
        val local: FakeLocal,
        val identities: FakeIdentities,
    ) {
        val runner = ReadingSyncRunner(
            backend, outbox, local, identities, state,
            device = "phone", now = { 1_000L }, newUuid = { "uuid-${++n}" },
        )
        private var n = 0
    }

    private fun rig(books: List<BookRow> = listOf(row("doc")), docs: Map<String, String> = mapOf("doc" to sha)) =
        Rig(local = FakeLocal(books), identities = FakeIdentities(docs)).also { it.state.backfilled = true }

    private fun position(
        at: Long,
        progress: Double = 0.5,
        variant: SyncVariant = SyncVariant.ORIGINAL,
        device: String? = "web",
        locator: String? = """{"href":"c1.xhtml"}""",
    ) =
        RemotePosition(sha, variant, locator, progress, "Ch 3", at, device)

    private fun queuePosition(
        rig: Rig,
        at: Long,
        id: BookIdentity = BookIdentity(sha, SyncVariant.ORIGINAL),
    ) = runBlocking {
        rig.outbox.upsert(
            OutboxKeys.positionRow(id, PositionWrite("""{"href":"c1.xhtml"}""", 0.25, "Ch 1", at, "phone"), at),
        )
    }

    // ---- queue and retry ---------------------------------------------------------------------

    @Test fun `a queued position is sent and leaves the queue`() = runBlocking {
        val rig = rig()
        queuePosition(rig, 500)
        assertEquals(SyncOutcome.COMPLETE, rig.runner.run())
        val (sentSha, variant, write) = rig.backend.positionPuts.single()
        assertEquals(sha, sentSha)
        assertEquals(SyncVariant.ORIGINAL, variant)
        assertEquals(0.25, write.totalProgression, 0.0)
        assertEquals(500L, write.recordedAtMs)
        assertTrue(rig.outbox.rows.isEmpty())
        assertFalse(rig.state.waiting)
    }

    @Test fun `an unreachable engine keeps the queue, says waiting, retries and pulls nothing`() = runBlocking {
        val rig = rig()
        queuePosition(rig, 500)
        rig.backend.putReply = { SyncReply.Unreachable("no route") }
        assertEquals(SyncOutcome.RETRY, rig.runner.run())
        assertEquals(1, rig.outbox.rows.size)
        assertTrue(rig.state.waiting)
        assertEquals("no route", rig.state.note)
        assertFalse("a pull before the queue drained could undo a local change", "positions" in rig.backend.calls)
        // The next run, with the engine back, sends the same write and clears the flag.
        rig.backend.putReply = { SyncReply.Ok(PutPositionResult(false, null)) }
        assertEquals(SyncOutcome.COMPLETE, rig.runner.run())
        assertTrue(rig.outbox.rows.isEmpty())
        assertFalse(rig.state.waiting)
        assertNull(rig.state.note)
    }

    @Test fun `queuing the same book again replaces the place instead of piling up`() = runBlocking {
        val rig = rig()
        queuePosition(rig, 100)
        queuePosition(rig, 200)
        assertEquals(1, rig.outbox.rows.size)
        assertEquals(200L, rig.outbox.rows.values.single().queuedAtMs)
    }

    @Test fun `a write queued while it was being sent survives`() = runBlocking {
        val rig = rig()
        queuePosition(rig, 100)
        val first = rig.outbox.all().single()
        queuePosition(rig, 300)
        rig.outbox.done(first)
        assertEquals("the newer place is still waiting", 300L, rig.outbox.rows.values.single().queuedAtMs)
    }

    @Test fun `a refusal in words drops that write and the words survive the run`() = runBlocking {
        val rig = rig()
        queuePosition(rig, 100)
        rig.backend.putReply = { SyncReply.Rejected("That hash is not valid.") }
        assertEquals(SyncOutcome.COMPLETE, rig.runner.run())
        assertTrue(rig.outbox.rows.isEmpty())
        assertEquals("That hash is not valid.", rig.state.note)
    }

    @Test fun `a signed out device stops without promising a later sync`() = runBlocking {
        val rig = rig()
        queuePosition(rig, 100)
        rig.backend.putReply = { SyncReply.Unavailable("Sign in again.") }
        assertEquals(SyncOutcome.COMPLETE, rig.runner.run())
        assertEquals(1, rig.outbox.rows.size)
        assertFalse(rig.state.waiting)
        assertEquals("Sign in again.", rig.state.note)
        assertEquals("Sign in again.", ReadingSyncSentences.sheetLine(1, rig.state.waiting, rig.state.note))
    }

    @Test fun `a corrupt queued row is dropped in words, never retried forever`() = runBlocking {
        val rig = rig()
        rig.outbox.upsert(BookSyncOutboxRow("pos:x:original", OutboxKind.POSITION.name, "$sha/original", "not json", 1))
        assertEquals(SyncOutcome.COMPLETE, rig.runner.run())
        assertTrue(rig.outbox.rows.isEmpty())
        assertTrue(rig.state.note!!.contains("dropped"))
    }

    // ---- variant mapping ---------------------------------------------------------------------

    @Test fun `a PDF read as its EPUB copy is the resizable variant under the PDF's hash`() = runBlocking {
        val identities = FakeIdentities(mapOf("pdf" to sha))
        assertEquals(BookIdentity(sha, SyncVariant.ORIGINAL), identities.identityOf("pdf"))
        assertEquals(BookIdentity(sha, SyncVariant.RESIZABLE), identities.identityOf(ConvertedBooks.derivedId("pdf")))
        assertEquals("resizable", SyncVariant.RESIZABLE.wire)
        assertEquals(SyncVariant.ORIGINAL, SyncVariant.of("original"))
        assertNull(SyncVariant.of("other"))
    }

    @Test fun `a resizable position pulled from the engine lands on the EPUB copy and not the PDF`() = runBlocking {
        val rig = rig(books = listOf(row("pdf", BookFormat.PDF, pageCount = 10)), docs = mapOf("pdf" to sha))
        rig.backend.positionReply = SyncReply.Ok(
            Fetched(listOf(position(at = 5_000, variant = SyncVariant.RESIZABLE)), "c1"),
        )
        rig.runner.run()
        val copy = rig.local.rows.getValue(ConvertedBooks.derivedId("pdf"))
        assertEquals(0.5, copy.progress, 0.0)
        assertEquals(5_000L, copy.lastReadAtMs)
        assertNull("the PDF's own place is untouched", rig.local.rows.getValue("pdf").lastReadAtMs)
    }

    // ---- newest wins -------------------------------------------------------------------------

    @Test fun `a newer remote place is applied and remembered as picked up`() = runBlocking {
        val rig = rig(books = listOf(row("doc", at = 1_000)))
        rig.backend.positionReply = SyncReply.Ok(Fetched(listOf(position(at = 2_000)), "cursor-1"))
        rig.runner.run()
        val applied = rig.local.rows.getValue("doc")
        assertEquals(2_000L, applied.lastReadAtMs)
        assertEquals("""{"href":"c1.xhtml"}""", applied.locatorJson)
        assertEquals("web", rig.state.picked["doc"])
        assertEquals("cursor-1", rig.state.positionsCursor)
    }

    @Test fun `an older remote place never overwrites a newer local one`() = runBlocking {
        val rig = rig(books = listOf(row("doc", at = 9_000, progress = 0.9)))
        rig.backend.positionReply = SyncReply.Ok(Fetched(listOf(position(at = 2_000)), "c"))
        rig.runner.run()
        assertEquals(0.9, rig.local.rows.getValue("doc").progress, 0.0)
        assertTrue(rig.state.picked.isEmpty())
    }

    @Test fun `a reply that kept a newer place applies it`() = runBlocking {
        val rig = rig(books = listOf(row("doc", at = 1_000)))
        queuePosition(rig, 1_500)
        rig.backend.putReply = { SyncReply.Ok(PutPositionResult(true, position(at = 4_000, progress = 0.8))) }
        rig.runner.run()
        assertEquals(0.8, rig.local.rows.getValue("doc").progress, 0.0)
    }

    @Test fun `a remote place for a book not on this phone is skipped without error`() = runBlocking {
        val rig = rig(books = listOf(row("doc")), docs = mapOf("doc" to "b".repeat(64)))
        rig.backend.positionReply = SyncReply.Ok(Fetched(listOf(position(at = 2_000)), "c"))
        assertEquals(SyncOutcome.COMPLETE, rig.runner.run())
        assertNull(rig.local.rows.getValue("doc").lastReadAtMs)
    }

    @Test fun `a PDF place is applied as its page, from the locator or else the progress`() = runBlocking {
        val rig = rig(books = listOf(row("doc", BookFormat.PDF, pageCount = 11)))
        val seven = position(at = 2_000, locator = PagedLocator.of(7))
        rig.backend.positionReply = SyncReply.Ok(Fetched(listOf(seven), "c"))
        rig.runner.run()
        assertEquals(7, rig.local.rows.getValue("doc").pageIndex)
        assertNull("a page has no Readium locator", rig.local.rows.getValue("doc").locatorJson)

        val other = rig(books = listOf(row("doc", BookFormat.PDF, pageCount = 11)))
        val byProgress = position(at = 2_000, progress = 0.5, locator = null)
        other.backend.positionReply = SyncReply.Ok(Fetched(listOf(byProgress), "c"))
        other.runner.run()
        assertEquals(5, other.local.rows.getValue("doc").pageIndex)
    }

    @Test fun `a changed book re-asks for everything because a skipped row may now match`() = runBlocking {
        val rig = rig()
        rig.state.positionsCursor = "old"
        rig.identities.hashedNow = 2
        rig.runner.run()
        assertEquals(listOf<String?>(null), rig.backend.asked)
    }

    @Test fun `with nothing newly hashed the cursor is used`() = runBlocking {
        val rig = rig()
        rig.state.positionsCursor = "old"
        rig.runner.run()
        assertEquals(listOf<String?>("old"), rig.backend.asked)
    }

    // ---- pickUp ------------------------------------------------------------------------------

    @Test fun `opening a book reports where it was picked up from, once`() = runBlocking {
        val rig = rig(books = listOf(row("doc", at = 1_000)))
        rig.backend.positionReply = SyncReply.Ok(Fetched(listOf(position(at = 2_000, device = "web")), "c"))
        assertEquals(PickedUp("web"), rig.runner.pickUp("doc"))
        assertNull(rig.runner.pickUp("doc"))
    }

    @Test fun `opening a book offline picks nothing up and sends nothing it cannot`() = runBlocking {
        val rig = rig()
        queuePosition(rig, 100)
        rig.backend.putReply = { SyncReply.Unreachable("offline") }
        assertNull(rig.runner.pickUp("doc"))
        assertFalse("positions" in rig.backend.calls)
        assertEquals(1, rig.outbox.rows.size)
    }

    // ---- bookmarks ---------------------------------------------------------------------------

    @Test fun `a tombstone from the engine deletes the bookmark here`() = runBlocking {
        val rig = rig()
        rig.local.marks += BookBookmark(1, "doc", "Page 3", null, 2, 0.1, 5, syncUuid = "u1")
        rig.backend.bookmarkReply = SyncReply.Ok(
            Fetched(
                listOf(RemoteBookmark("u1", sha, SyncVariant.ORIGINAL, null, 0.1, "Page 3", 5, deletedAtMs = 9)),
                "c",
            ),
        )
        rig.runner.run()
        assertTrue(rig.local.marks.isEmpty())
    }

    @Test fun `a tombstone for a bookmark this phone never had changes nothing`() = runBlocking {
        val rig = rig()
        rig.backend.bookmarkReply = SyncReply.Ok(
            Fetched(listOf(RemoteBookmark("zz", sha, SyncVariant.ORIGINAL, null, 0.1, null, 5, deletedAtMs = 9)), "c"),
        )
        assertEquals(SyncOutcome.COMPLETE, rig.runner.run())
        assertTrue(rig.local.marks.isEmpty())
    }

    @Test fun `a new remote bookmark merges in by uuid and a repeat does not duplicate it`() = runBlocking {
        val rig = rig()
        val remote = RemoteBookmark("u9", sha, SyncVariant.ORIGINAL, """{"href":"c2.xhtml"}""", 0.4, null, 7, null)
        rig.backend.bookmarkReply = SyncReply.Ok(Fetched(listOf(remote), "c"))
        rig.runner.run()
        rig.runner.run()
        val mark = rig.local.marks.single()
        assertEquals("u9", mark.syncUuid)
        assertEquals("""{"href":"c2.xhtml"}""", mark.locatorJson)
        assertEquals("a bookmark with no label is named for its place", "Bookmark · 40%", mark.label)
    }

    @Test fun `a locally deleted bookmark is not resurrected because the delete goes first`() = runBlocking {
        val rig = rig()
        rig.outbox.upsert(OutboxKeys.bookmarkDeleteRow("u1", 10))
        rig.backend.bookmarkReply = SyncReply.Ok(
            Fetched(listOf(RemoteBookmark("u1", sha, SyncVariant.ORIGINAL, null, 0.1, "x", 5, null)), "c"),
        )
        // Offline: the delete cannot be sent, so no pull happens at all and nothing comes back.
        rig.backend.deleteReply = SyncReply.Unreachable("offline")
        rig.runner.run()
        assertTrue(rig.local.marks.isEmpty())
        assertFalse("bookmarks" in rig.backend.calls)
    }

    @Test fun `a create then a delete before reconnecting collapses to the delete`() = runBlocking {
        val rig = rig()
        rig.outbox.upsert(
            OutboxKeys.bookmarkPutRow("u1", BookmarkWrite(sha, SyncVariant.ORIGINAL, null, 0.1, "x", 5), 10),
        )
        rig.outbox.upsert(OutboxKeys.bookmarkDeleteRow("u1", 11))
        rig.runner.run()
        assertEquals(listOf("deleteBookmark"), rig.backend.calls.filter { it.contains("ookmark") && it != "bookmarks" })
    }

    @Test fun `a bookmark made before sync gets an id and is queued once`() = runBlocking {
        val rig = rig()
        rig.local.marks += BookBookmark(1, "doc", "Page 3", null, 2, 0.1, 5)
        rig.runner.run()
        assertEquals("uuid-1", rig.local.marks.single().syncUuid)
        val (uuid, write) = rig.backend.bookmarkPuts.single()
        assertEquals("uuid-1", uuid)
        assertEquals(sha, write.sha256)
        assertEquals(PagedLocator.of(2), write.locator)
        assertEquals(5L, write.createdAtMs)
    }

    @Test fun `places read before sync existed are queued once, and only once`() = runBlocking {
        val rig = rig(books = listOf(row("doc", at = 777, progress = 0.3)))
        rig.state.backfilled = false
        rig.runner.run()
        assertEquals(777L, rig.backend.positionPuts.single().third.recordedAtMs)
        assertTrue(rig.state.backfilled)
        rig.runner.run()
        assertEquals(1, rig.backend.positionPuts.size)
    }

    // ---- sentences, wire, locator fit --------------------------------------------------------

    @Test fun `the offline sentence is said only while writes wait on an unreachable engine`() {
        assertEquals(
            "Reading progress will sync when the server is reachable.",
            ReadingSyncSentences.sheetLine(pending = 2, waiting = true, note = "x"),
        )
        assertNull(ReadingSyncSentences.sheetLine(pending = 0, waiting = true, note = "x"))
        assertNull(ReadingSyncSentences.sheetLine(pending = 2, waiting = false, note = null))
    }

    @Test fun `picked up says where from and says near when only the progress applied`() {
        assertEquals("Picked up where you left off on the web.", ReadingSyncSentences.pickedUp("web", near = false))
        assertEquals("Picked up near where you left off on the web.", ReadingSyncSentences.pickedUp("web", near = true))
        assertEquals("Picked up where you left off on your other phone.", ReadingSyncSentences.pickedUp("phone", false))
        assertEquals("Picked up where you left off on Pixel.", ReadingSyncSentences.pickedUp("Pixel", false))
    }

    @Test fun `a locator fits only when its chapter file is in this book`() {
        val hrefs = setOf("OEBPS/c1.xhtml", "OEBPS/c2.xhtml")
        assertTrue(LocatorFit.fits("""{"href":"OEBPS/c2.xhtml#p3","type":"x"}""", hrefs))
        assertFalse(LocatorFit.fits("""{"href":"chapter9.xhtml"}""", hrefs))
        assertFalse(LocatorFit.fits("""{"type":"x"}""", hrefs))
        assertFalse(LocatorFit.fits("not json", hrefs))
        assertFalse(LocatorFit.fits(null, hrefs))
    }

    @Test fun `the near fallback picks the last position not past the progress`() {
        val p = listOf(0.0, 0.25, 0.5, 0.75)
        assertEquals(2, EpubStart.nearestIndex(p, 0.6))
        assertEquals(1, EpubStart.nearestIndex(p, 0.25))
        assertEquals(0, EpubStart.nearestIndex(p, -0.1))
        assertEquals(3, EpubStart.nearestIndex(p, 1.0))
        assertNull(EpubStart.nearestIndex(emptyList(), 0.5))
    }

    @Test fun `bodies carry the contract's fields and no title or filename`() {
        val body = ReadingSyncJson.positionBody(
            PositionWrite(
                """{"href":"c1.xhtml","locations":{"progression":0.5}}""", 0.4, "Ch 2", 1_700_000_000_000, "phone",
            ),
        )
        val json = org.json.JSONObject(body)
        val fields = setOf("locator", "total_progression", "chapter_label", "recorded_at", "device")
        assertEquals(fields, json.keys().asSequence().toSet())
        assertEquals("c1.xhtml", json.getJSONObject("locator").getString("href"))
        assertEquals("2023-11-14T22:13:20Z", json.getString("recorded_at"))
        val back = ReadingSyncWire.positionWrite(body)!!
        assertEquals(1_700_000_000_000, back.recordedAtMs)
        assertEquals("Ch 2", back.chapterLabel)

        val write = BookmarkWrite(sha, SyncVariant.RESIZABLE, null, 0.1, null, 5)
        val mark = org.json.JSONObject(ReadingSyncJson.bookmarkBody(write))
        assertEquals("resizable", mark.getString("variant"))
        assertTrue(mark.isNull("locator"))
        assertTrue(mark.isNull("note"))
    }

    @Test fun `a paged locator round trips and a bad one reads as no page`() {
        assertEquals(12, PagedLocator.pageOf(PagedLocator.of(12)))
        assertNull(PagedLocator.pageOf("""{"page":-1}"""))
        assertNull(PagedLocator.pageOf("nope"))
        assertNull(PagedLocator.pageOf(null))
    }

    // ---- the hash pass -----------------------------------------------------------------------

    private class Hashing(val files: Map<String, ByteArray>, val modified: Long = 1L) {
        val opened = mutableListOf<String>()
        val cache = mutableMapOf<String, Triple<String, Long, Long?>>()

        val source = object : PdfSource {
            override fun list() = files.map { (id, bytes) ->
                PdfRef(Book(id, "$id.epub", BookFormat.EPUB), bytes.size.toLong(), modified)
            }

            override fun open(ref: PdfRef): InputStream {
                opened += ref.book.documentId
                return ByteArrayInputStream(files.getValue(ref.book.documentId))
            }
        }

        val hashes = object : PdfHashCache {
            override fun get(ref: PdfRef): String? = cache[ref.book.documentId]
                ?.takeIf { it.second == ref.sizeBytes && it.third == ref.modifiedMs }?.first

            override fun lastKnown(documentId: String) = cache[documentId]?.first

            override fun put(ref: PdfRef, sha256: String, sizeBytes: Long) {
                cache[ref.book.documentId] = Triple(sha256, sizeBytes, ref.modifiedMs)
            }
        }
    }

    @Test fun `every book is hashed smallest first, once, and a cached file is not read again`() = runBlocking {
        val h = Hashing(
            mapOf("big" to ByteArray(900) { 1 }, "small" to ByteArray(10) { 2 }, "mid" to ByteArray(300) { 3 }),
        )
        val pass = BookHashPass(h.source, h.hashes)
        assertEquals(3, pass.run())
        assertEquals(listOf("small", "mid", "big"), h.opened)
        assertEquals(64, h.cache.getValue("small").first.length)
        assertEquals(0, pass.run())
        assertEquals("no second read", 3, h.opened.size)
    }

    @Test fun `a file that changed size is hashed again`() = runBlocking {
        val h = Hashing(mapOf("a" to ByteArray(10) { 1 }))
        BookHashPass(h.source, h.hashes).run()
        val before = h.cache.getValue("a").first
        val changed = Hashing(mapOf("a" to ByteArray(11) { 1 })).also { it.cache.putAll(h.cache) }
        assertEquals(1, BookHashPass(changed.source, changed.hashes).run())
        assertTrue(changed.cache.getValue("a").first != before)
    }

    @Test fun `no folder grant hashes nothing`() = runBlocking {
        val none = object : PdfSource {
            override fun list(): List<PdfRef>? = null

            override fun open(ref: PdfRef): InputStream? = null
        }
        assertEquals(0, BookHashPass(none, Hashing(emptyMap()).hashes).run())
    }
}
