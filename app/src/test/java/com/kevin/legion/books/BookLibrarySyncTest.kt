package com.kevin.legion.books

import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The library download's state machine against fakes for every port: no Android, no network. */
class BookLibrarySyncTest {
    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val blob = "epub-bytes".toByteArray()
    private val book = LibraryBook(7, "Dracula", "Bram Stoker", sha(blob), blob.size.toLong(), "public domain")

    private class FakeBackend(var list: SyncReply<List<LibraryBook>>, var file: SyncReply<ByteArray>) : LibraryBackend {
        val downloads = mutableListOf<Long>()

        override suspend fun list() = list

        override suspend fun download(id: Long): SyncReply<ByteArray> {
            downloads += id
            return file
        }
    }

    private class FakeFolder(var writable: Boolean = true) : LibraryFolder {
        val files = mutableMapOf<String, ByteArray>()
        var failWrite = false

        override fun canWrite() = writable

        override fun has(fileName: String) = fileName in files

        override fun write(fileName: String, bytes: ByteArray): Boolean {
            if (failWrite) return false
            files[fileName] = bytes
            return true
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
        assertEquals(SyncOutcome.RETRY, run(backend, folder, state))
        assertTrue(folder.files.isEmpty())
        assertTrue(state.fetched.isEmpty())
        assertTrue(state.note!!.contains("did not match"))
        assertTrue(state.note!!.contains("Dracula"))
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
    fun `a failed folder write is not marked fetched`() {
        val backend = FakeBackend(SyncReply.Ok(listOf(book)), SyncReply.Ok(blob))
        val folder = FakeFolder().apply { failWrite = true }
        val state = FakeState()
        assertEquals(SyncOutcome.RETRY, run(backend, folder, state))
        assertTrue(state.fetched.isEmpty())
        assertTrue(state.note!!.contains("could not be saved"))
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
