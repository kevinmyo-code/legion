package com.kevin.legion.books

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The temp-then-rename writer against an in-memory document store that can die mid-write. */
class BookLibraryFilesTest {
    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class FakeDocs : DocOps {
        val docs = linkedMapOf<String, ByteArray>()
        var dieMidWrite = false
        var failRename = false

        override fun names() = docs.keys.toList()

        override fun create(name: String): Boolean {
            docs[name] = ByteArray(0)
            return true
        }

        override fun write(name: String, bytes: ByteArray): Boolean {
            docs[name] = if (dieMidWrite) bytes.copyOf(bytes.size / 2) else bytes
            return true
        }

        override fun digest(name: String): DocDigest? =
            docs[name]?.let { DocDigest(it.size.toLong(), LibrarySha.of(it)) }

        override fun rename(from: String, to: String): Boolean {
            if (failRename) return false
            docs[to] = docs.remove(from) ?: return false
            return true
        }

        override fun delete(name: String) = docs.remove(name) != null
    }

    private val bytes = "a whole book".toByteArray()

    @Test
    fun `a good write ends with only the final file`() {
        val docs = FakeDocs()
        assertTrue(PartFileWriter(docs).write("B.epub", bytes))
        assertEquals(listOf("B.epub"), docs.names())
        assertTrue(bytes.contentEquals(docs.docs["B.epub"]))
    }

    @Test
    fun `a write that lands short is refused and leaves nothing behind`() {
        val docs = FakeDocs().apply { dieMidWrite = true }
        assertFalse(PartFileWriter(docs).write("B.epub", bytes))
        assertTrue(docs.names().isEmpty())
    }

    @Test
    fun `a failed rename leaves nothing behind`() {
        val docs = FakeDocs().apply { failRename = true }
        assertFalse(PartFileWriter(docs).write("B.epub", bytes))
        assertTrue(docs.names().isEmpty())
    }

    @Test
    fun `a partial left by a killed run is swept and a retry replaces it`() {
        val docs = FakeDocs().apply { docs["B.epub.part"] = "half".toByteArray() }
        val writer = PartFileWriter(docs)
        writer.sweep()
        assertTrue(docs.names().isEmpty())
        docs.docs["B.epub.part"] = "half".toByteArray()
        assertTrue(writer.write("B.epub", bytes))
        assertEquals(listOf("B.epub"), docs.names())
    }

    @Test
    fun `status tells absent, same and different by size and hash`() {
        val docs = FakeDocs().apply { docs["B.epub"] = bytes }
        val writer = PartFileWriter(docs)
        assertEquals(FileStatus.SAME, writer.status("B.epub", bytes.size.toLong(), sha(bytes)))
        assertEquals(FileStatus.DIFFERENT, writer.status("B.epub", 3, sha(bytes)))
        assertEquals(FileStatus.ABSENT, writer.status("C.epub", 1, sha(bytes)))
    }
}
