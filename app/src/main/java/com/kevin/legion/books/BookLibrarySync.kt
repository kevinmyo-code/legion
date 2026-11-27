package com.kevin.legion.books

import java.security.MessageDigest

// The phone half of the household book library (alfred-builds ticket 06). The engine lists the
// EPUBs it holds; this pulls the ones the phone has not fetched into the Books folder. Pure state
// machine behind three ports, so every rule is a test with no Android in it.

/** One row of `GET /api/books/library`: just what the phone needs to fetch and check a file. */
data class LibraryBook(
    val id: Long,
    val title: String,
    val author: String,
    val sha256: String,
    val size: Long,
    val licenceNote: String,
)

/** The seam to the engine's library routes. */
interface LibraryBackend {
    suspend fun list(): SyncReply<List<LibraryBook>>

    suspend fun download(id: Long): SyncReply<ByteArray>
}

/** The granted Books folder, as far as a download needs it. */
interface LibraryFolder {
    /** A live grant that allows writing (the first Books grants were read-only). */
    fun canWrite(): Boolean

    fun has(fileName: String): Boolean

    /** True only when the file now exists with every byte. */
    fun write(fileName: String, bytes: ByteArray): Boolean
}

/** What the phone remembers: which library ids it already fetched, and the last run's sentence. */
interface LibraryState {
    val fetched: MutableSet<Long>
    var note: String?
}

object LibraryFileNames {
    private val unsafe = Regex("""[\\/:*?"<>|\u0000-\u001f]""")

    fun of(book: LibraryBook): String {
        val title = clean(book.title)
        val author = clean(book.author)
        val stem = when {
            title.isEmpty() -> "Book ${book.id}"
            author.isEmpty() -> title
            else -> "$title - $author"
        }
        return "$stem.epub"
    }

    fun withId(book: LibraryBook): String = of(book).removeSuffix(".epub") + " (${book.id}).epub"

    private fun clean(text: String) = text.replace(unsafe, " ").replace(Regex("\\s+"), " ").trim()
}

class LibrarySyncRunner(
    private val backend: LibraryBackend,
    private val folder: LibraryFolder,
    private val state: LibraryState,
) {
    @Suppress("ReturnCount") // each early return is one named way a run ends
    suspend fun run(): SyncOutcome {
        val books = when (val reply = backend.list()) {
            is SyncReply.Ok -> reply.value
            is SyncReply.Unreachable -> return retry("The library could not be reached, so nothing was downloaded.")
            is SyncReply.Unavailable -> return done(reply.sentence)
            is SyncReply.Rejected -> return done(reply.sentence)
        }
        val fresh = books.filter { it.id !in state.fetched }
        if (fresh.isEmpty()) return done(null)
        if (!folder.canWrite()) {
            return done(
                "${fresh.size} library book(s) are waiting, but the Books folder cannot be written to. " +
                    "Reconnect the Books folder to allow saving.",
            )
        }
        var saved = 0
        for (book in fresh) {
            fetchOne(book)?.let { return retry(it) }
            saved++
        }
        return done("Library: $saved new book${if (saved == 1) "" else "s"} saved to the Books folder.")
    }

    /** Null when the book is now in the folder; else the sentence saying what did NOT happen. */
    private suspend fun fetchOne(book: LibraryBook): String? {
        val bytes = when (val reply = backend.download(book.id)) {
            is SyncReply.Ok -> reply.value
            is SyncReply.Unreachable -> return "\"${book.title}\" was not downloaded: the engine could not be reached."
            is SyncReply.Unavailable -> return reply.sentence
            is SyncReply.Rejected -> return "\"${book.title}\" was not downloaded: ${reply.sentence}"
        }
        if (sha256(bytes) != book.sha256.lowercase()) {
            return "\"${book.title}\" was not saved: the downloaded file did not match the library's checksum."
        }
        val plain = LibraryFileNames.of(book)
        val name = if (folder.has(plain)) LibraryFileNames.withId(book) else plain
        if (folder.has(name)) {
            state.fetched += book.id // already here under its own name
            return null
        }
        if (!folder.write(name, bytes)) return "\"${book.title}\" could not be saved to the Books folder."
        state.fetched += book.id
        return null
    }

    private fun done(note: String?): SyncOutcome {
        state.note = note
        return SyncOutcome.COMPLETE
    }

    private fun retry(note: String): SyncOutcome {
        state.note = note
        return SyncOutcome.RETRY
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
