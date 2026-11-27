package com.kevin.legion.books

import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

/** What [LibraryFolder.status] found under a file name. */
enum class FileStatus { ABSENT, SAME, DIFFERENT }

/** The granted Books folder, as far as a download needs it. */
interface LibraryFolder {
    /** A live grant that allows writing (the first Books grants were read-only). */
    fun canWrite(): Boolean

    /** Whether [fileName] is absent, this very book (size and hash match) or something else. */
    fun status(fileName: String, size: Long, sha256: String): FileStatus

    /** True only when [fileName] now exists with every byte, written whole or not at all. */
    fun write(fileName: String, bytes: ByteArray): Boolean

    /** Removes half-written files an earlier, killed run left behind. */
    fun sweepPartials()
}

/** What the phone remembers: which library ids it already fetched, and the last run's sentence. */
interface LibraryState {
    val fetched: MutableSet<Long>
    var note: String?
}

object LibrarySha {
    fun of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

object LibraryFileNames {
    private const val MAX_TITLE = 80
    private const val MAX_AUTHOR = 40
    private val unsafe = Regex("[\\/:*?\"<>|\u0000-\u001f]")

    fun of(book: LibraryBook): String {
        val title = clean(book.title).take(MAX_TITLE).trim()
        val author = clean(book.author).take(MAX_AUTHOR).trim()
        val stem = when {
            title.isEmpty() -> "Book ${book.id}"
            author.isEmpty() -> title
            else -> "$title - $author"
        }
        return "$stem.epub"
    }

    fun withId(book: LibraryBook): String = of(book).removeSuffix(".epub") + " (${book.id}).epub"

    private fun clean(text: String) = text.replace(unsafe, " ").replace(Regex("""\s+"""), " ").trim()
}

class LibrarySyncRunner(
    private val backend: LibraryBackend,
    private val folder: LibraryFolder,
    private val state: LibraryState,
) {
    /** One run at a time in this process: the daily job and a sheet-open run never overlap. */
    suspend fun run(): SyncOutcome = lock.withLock { runLocked() }

    @Suppress("ReturnCount") // each early return is one named way a run ends
    private suspend fun runLocked(): SyncOutcome {
        val books = when (val reply = backend.list()) {
            is SyncReply.Ok -> reply.value
            is SyncReply.Unreachable -> return retry("The library could not be reached, so nothing was downloaded.")
            is SyncReply.Unavailable -> return done(reply.sentence)
            is SyncReply.Rejected -> return done(reply.sentence)
        }
        val canWrite = folder.canWrite()
        if (canWrite) folder.sweepPartials()
        val fresh = books.filter { it.id !in state.fetched }
        if (fresh.isEmpty()) return done(null)
        if (!canWrite) {
            return done(
                "${fresh.size} library book(s) are waiting, but the Books folder cannot be written to. " +
                    "Reconnect the Books folder to allow saving.",
            )
        }
        var saved = 0
        val failed = mutableListOf<String>()
        for (book in fresh) {
            when (val result = fetchOne(book)) {
                is Fetch.Saved -> saved++
                is Fetch.Present -> Unit
                is Fetch.Failed -> failed += book.title
                is Fetch.Stop -> return if (result.retry) retry(result.sentence) else done(result.sentence)
            }
        }
        return done(summary(saved, failed))
    }

    private sealed interface Fetch {
        data object Saved : Fetch

        data object Present : Fetch

        data object Failed : Fetch

        data class Stop(val sentence: String, val retry: Boolean) : Fetch
    }

    /** Fetches one book. A book-level failure is [Fetch.Failed] (skipped); only transport stops the run. */
    @Suppress("ReturnCount") // each early return is one named outcome for this book
    private suspend fun fetchOne(book: LibraryBook): Fetch {
        val name = nameFor(book) ?: return Fetch.Failed
        if (name.second == FileStatus.SAME) {
            state.fetched += book.id // already here, whole: recognised, never copied
            return Fetch.Present
        }
        val bytes = when (val reply = backend.download(book.id)) {
            is SyncReply.Ok -> reply.value
            is SyncReply.Unreachable ->
                return Fetch.Stop("\"${book.title}\" was not downloaded: the engine could not be reached.", true)
            is SyncReply.Unavailable -> return Fetch.Stop(reply.sentence, false)
            is SyncReply.Rejected -> return Fetch.Failed
        }
        if (LibrarySha.of(bytes) != book.sha256.lowercase() || !folder.write(name.first, bytes)) return Fetch.Failed
        state.fetched += book.id
        return Fetch.Saved
    }

    /** The file name to use and its status, or null when both candidate names hold other files. */
    private fun nameFor(book: LibraryBook): Pair<String, FileStatus>? {
        val plain = LibraryFileNames.of(book)
        val first = folder.status(plain, book.size, book.sha256.lowercase())
        if (first != FileStatus.DIFFERENT) return plain to first
        val withId = LibraryFileNames.withId(book)
        val second = folder.status(withId, book.size, book.sha256.lowercase())
        return if (second == FileStatus.DIFFERENT) null else withId to second
    }

    private fun summary(saved: Int, failed: List<String>): String? {
        val parts = mutableListOf<String>()
        if (saved > 0) parts += "Library: $saved new book${if (saved == 1) "" else "s"} saved to the Books folder."
        if (failed.isNotEmpty()) {
            val noun = if (failed.size == 1) "book" else "books"
            parts += "${failed.size} $noun could not be saved: ${failed.joinToString(", ")}."
        }
        return parts.joinToString(" ").ifEmpty { null }
    }

    private fun done(note: String?): SyncOutcome {
        state.note = note
        return SyncOutcome.COMPLETE
    }

    private fun retry(note: String): SyncOutcome {
        state.note = note
        return SyncOutcome.RETRY
    }

    private companion object {
        val lock = Mutex()
    }
}
