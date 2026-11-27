package com.kevin.legion.books

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The library download's state machine against fakes for every port: no Android, no network. */
class BookLibrarySyncTest {
    private val blob = "epub-bytes".toByteArray()
    private val book =
        LibraryBook(7, "Dracula", "Bram Stoker", LibrarySha.of(blob), blob.size.toLong(), "public domain")

    private class FakeBackend(var list: SyncReply<List<LibraryBook>>, var file: SyncReply<ByteArray>) : LibraryBackend {
        val downloads = mutableListOf<Long>()
        var pick: ((Long) -> SyncReply<ByteArray>)? = null
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun list() = list

        override suspend fun download(id: Long): SyncReply<ByteArray> {
            downloads += id
            gate?.await()
            return pick?.invoke(id) ?: file
        }
    }

    private class FakeFolder(var writable: Boolean = true) : LibraryFolder {
        val files = mutableMapOf<String, ByteArray>()
        var failWrite = false
        var swept = 0

        override fun canWrite() = writable

        override fun status(fileName: String, size: Long, sha256: String): FileStatus {
            val have = files[fileName] ?: return FileStatus.ABSENT
            val same = have.size.toLong() == size && LibrarySha.of(have) == sha256
            return if (same) FileStatus.SAME else FileStatus.DIFFERENT
        }

        override fun write(fileName: String, bytes: ByteArray): Boolean {
            if (failWrite) return false
            files[fileName] = bytes
            return true
        }

        override fun sweepPartials() {
            swept++
        }
    }

    private class FakeState : LibraryState {
        override val fetched = mutableSetOf<Long>()
        override var note: String? = null
    }

    private fun run(backend: FakeBackend, folder: FakeFolder, state: FakeState) =
        runBlocking { LibrarySyncRunner(backend, folder, state).run() }

    @Test
    fun `a new book lands in the folder once and is never fetched again`() {
        val backend = FakeBackend(SyncReply.Ok(listOf(book)), SyncReply.Ok(blob))
        val folder = FakeFolder()
        val state = FakeState()
        assertEquals(SyncOutcome.COMPLETE, run(backend, folder, state))
        assertEquals(setOf("Dracula - Bram Stoker.epub"), folder.files.keys)
        assertTrue(blob.contentEquals(folder.files.values.single()))
        assertEquals(setOf(7L), state.fetched)
        run(backend, folder, state)
        assertEquals(listOf(7L), backend.downloads)
    }

    @Test
    fun `a book the reader deleted from the folder is not brought back`() {
        val backend = FakeBackend(SyncReply.Ok(listOf(book)), SyncReply.Ok(blob))
        val folder = FakeFolder()
        val state = FakeState().apply { fetched += 7L }
        run(backend, folder, state)
        assertTrue(backend.downloads.isEmpty())
        assertTrue(folder.files.isEmpty())
    }

    @Test
    fun `bytes that do not match the stated hash are never written`() {
        val backend = FakeBackend(SyncReply.Ok(listOf(book)), SyncReply.Ok("tampered".toByteArray()))
        val folder = FakeFolder()
        val state = FakeState()
        assertEquals(SyncOutcome.COMPLETE, run(backend, folder, state))
        assertTrue(folder.files.isEmpty())
        assertTrue(state.fetched.isEmpty())
        assertEquals("1 book could not be saved: Dracula.", state.note)
    }

    @Test
    fun `a read-only folder grant says so and downloads nothing`() {
        val backend = FakeBackend(SyncReply.Ok(listOf(book)), SyncReply.Ok(blob))
        val state = FakeState()
        assertEquals(SyncOutcome.COMPLETE, run(backend, FakeFolder(writable = false), state))
        assertTrue(backend.downloads.isEmpty())
        assertTrue(state.note!!.contains("Reconnect the Books folder"))
    }

    @Test
    fun `an unreachable engine retries and says nothing was downloaded`() {
        val backend = FakeBackend(SyncReply.Unreachable("no route"), SyncReply.Ok(blob))
        val state = FakeState()
        assertEquals(SyncOutcome.RETRY, run(backend, FakeFolder(), state))
        assertTrue(state.note!!.contains("nothing was downloaded"))
    }

    @Test
    fun `no engine set up is complete not retried`() {
        val backend = FakeBackend(SyncReply.Unavailable("No engine"), SyncReply.Ok(blob))
        assertEquals(SyncOutcome.COMPLETE, run(backend, FakeFolder(), FakeState()))
    }

    @Test
    fun `a failed folder write is skipped, reported, and not marked fetched`() {
        val backend = FakeBackend(SyncReply.Ok(listOf(book)), SyncReply.Ok(blob))
        val folder = FakeFolder().apply { failWrite = true }
        val state = FakeState()
        assertEquals(SyncOutcome.COMPLETE, run(backend, folder, state))
        assertTrue(state.fetched.isEmpty())
        assertEquals("1 book could not be saved: Dracula.", state.note)
    }

    @Test
    fun `the same book already in the folder is recognised and not copied`() {
        val backend = FakeBackend(SyncReply.Ok(listOf(book)), SyncReply.Ok(blob))
        val folder = FakeFolder().apply { files["Dracula - Bram Stoker.epub"] = blob }
        val state = FakeState()
        run(backend, folder, state)
        assertEquals(setOf("Dracula - Bram Stoker.epub"), folder.files.keys)
        assertEquals(setOf(7L), state.fetched)
        assertTrue(backend.downloads.isEmpty())
    }

    @Test
    fun `one failing book is skipped and reported while the rest continue`() {
        val good = "other-bytes".toByteArray()
        val second = LibraryBook(8, "Emma", "Jane Austen", LibrarySha.of(good), good.size.toLong(), "public domain")
        val backend = FakeBackend(SyncReply.Ok(listOf(book, second)), SyncReply.Ok(blob))
        backend.pick = { id -> if (id == 7L) SyncReply.Rejected("gone") else SyncReply.Ok(good) }
        val state = FakeState()
        assertEquals(SyncOutcome.COMPLETE, run(backend, FakeFolder(), state))
        assertEquals(setOf(8L), state.fetched)
        assertEquals(
            "Library: 1 new book saved to the Books folder. 1 book could not be saved: Dracula.",
            state.note,
        )
    }

    @Test
    fun `a transport failure stops the run and retries`() {
        val backend = FakeBackend(SyncReply.Ok(listOf(book)), SyncReply.Unreachable("no route"))
        assertEquals(SyncOutcome.RETRY, run(backend, FakeFolder(), FakeState()))
    }

    @Test
    fun `two runs at once never download the same book twice`() {
        val gate = CompletableDeferred<Unit>()
        val backend = FakeBackend(SyncReply.Ok(listOf(book)), SyncReply.Ok(blob)).apply { this.gate = gate }
        val folder = FakeFolder()
        val state = FakeState()
        runBlocking {
            val a = async { LibrarySyncRunner(backend, folder, state).run() }
            val b = async { LibrarySyncRunner(backend, folder, state).run() }
            yield()
            gate.complete(Unit)
            a.await()
            b.await()
        }
        assertEquals(listOf(7L), backend.downloads)
        assertEquals(1, folder.files.size)
    }

    @Test
    fun `a run sweeps leftover partial files first`() {
        val folder = FakeFolder()
        run(FakeBackend(SyncReply.Ok(emptyList()), SyncReply.Ok(blob)), folder, FakeState())
        assertEquals(1, folder.swept)
    }

    @Test
    fun `long names are cut to a safe length and keep the extension`() {
        val name = LibraryFileNames.of(LibraryBook(1, "T".repeat(500), "A".repeat(500), "x", 1, "n"))
        assertTrue(name.length <= 130)
        assertTrue(name.endsWith(".epub"))
        assertTrue(LibraryFileNames.withId(LibraryBook(12, "T".repeat(500), "", "x", 1, "n")).length <= 140)
    }

    @Test
    fun `a taken file name gets the id so no book overwrites another`() {
        val backend = FakeBackend(SyncReply.Ok(listOf(book)), SyncReply.Ok(blob))
        val folder = FakeFolder().apply { files["Dracula - Bram Stoker.epub"] = "other".toByteArray() }
        run(backend, folder, FakeState())
        assertEquals(setOf("Dracula - Bram Stoker.epub", "Dracula - Bram Stoker (7).epub"), folder.files.keys)
        assertEquals("other", String(folder.files["Dracula - Bram Stoker.epub"]!!))
    }

    @Test
    fun `file names lose path characters and fall back when empty`() {
        assertEquals("A B C - D.epub", LibraryFileNames.of(LibraryBook(1, "A/B:C", "D", "x", 1, "n")))
        assertEquals("Dracula.epub", LibraryFileNames.of(LibraryBook(1, "Dracula", "", "x", 1, "n")))
        assertEquals("Book 9.epub", LibraryFileNames.of(LibraryBook(9, "///", "", "x", 1, "n")))
    }

    @Test
    fun `a saved book leaves a calm note and the next run clears it`() {
        val backend = FakeBackend(SyncReply.Ok(listOf(book)), SyncReply.Ok(blob))
        val state = FakeState()
        run(backend, FakeFolder(), state)
        assertEquals("Library: 1 new book saved to the Books folder.", state.note)
        run(backend, FakeFolder(), state)
        assertNull(state.note)
        assertFalse(state.fetched.isEmpty())
    }
}
