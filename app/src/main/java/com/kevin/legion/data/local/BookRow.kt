package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One book the Books launcher has seen, and where the reader left off (ADR 0061, v75 to v76).
 *
 * **Local-only, no sync columns, never sent to the engine** (ADR 0061: reading position, bookmarks
 * and settings stay on the phone). It follows `feed_subscriptions` and the sitrep settings: a small
 * piece of one person's own state, with no engine leg. The key is the SAF document id of the file,
 * the same key a ROM uses, so a book that moves out of the folder starts a fresh row rather than
 * guessing at identity.
 *
 * The title and author are CACHES of what the file says (an EPUB's own metadata, read once by the
 * cover pass); [title] starts as the file name. [metadataRead] says whether that pass has run, so a
 * book whose metadata could not be read is not retried on every scan.
 */
@Entity(tableName = "books")
data class BookRow(
    @PrimaryKey val documentId: String,
    val fileName: String,
    /** [com.kevin.legion.books.BookFormat] name; TEXT with no CHECK, so a new format is not a migration. */
    val format: String,
    val title: String,
    val author: String?,
    val metadataRead: Boolean,
    /** 0.0 to 1.0 through the whole book. 0 when never opened. */
    val progress: Double,
    /** An EPUB's chapter title at the saved position, or null. */
    val chapterLabel: String?,
    /** PDF and comic: the 0-based page last shown. EPUB: 0. */
    val pageIndex: Int,
    /** PDF and comic: the page count, 0 until known. */
    val pageCount: Int,
    /** EPUB: Readium's `Locator` as JSON, the exact place to reopen at. Null for PDF and comics. */
    val locatorJson: String?,
    /** Null until the book is first opened; the Continue card is the newest non-null. */
    val lastReadAtMs: Long?,
    val firstSeenAtMs: Long,
)

/** A saved place in a book. [label] is what the Bookmarks list says in words. */
@Entity(tableName = "book_bookmarks", indices = [Index(value = ["documentId"])])
data class BookBookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: String,
    val label: String,
    val locatorJson: String?,
    val pageIndex: Int,
    val progress: Double,
    val createdAtMs: Long,
)

@Dao
interface BookDao {
    @Query("SELECT * FROM books")
    suspend fun all(): List<BookRow>

    @Query("SELECT * FROM books WHERE documentId = :documentId")
    suspend fun get(documentId: String): BookRow?

    /** A book seen for the first time. An existing row keeps its position: a rescan never resets it. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(rows: List<BookRow>)

    @Query("UPDATE books SET title = :title, author = :author, metadataRead = 1 WHERE documentId = :documentId")
    suspend fun setMetadata(documentId: String, title: String, author: String?)

    @Query("UPDATE books SET metadataRead = 1 WHERE documentId = :documentId")
    suspend fun markMetadataRead(documentId: String)

    @Query("UPDATE books SET pageCount = :pageCount WHERE documentId = :documentId")
    suspend fun setPageCount(documentId: String, pageCount: Int)

    @Suppress("LongParameterList") // one column per saved fact; a wrapper type would only rename them
    @Query(
        "UPDATE books SET progress = :progress, chapterLabel = :chapterLabel, pageIndex = :pageIndex, " +
            "pageCount = :pageCount, locatorJson = :locatorJson, lastReadAtMs = :atMs WHERE documentId = :documentId",
    )
    suspend fun savePosition(
        documentId: String,
        progress: Double,
        chapterLabel: String?,
        pageIndex: Int,
        pageCount: Int,
        locatorJson: String?,
        atMs: Long,
    )

    @Query("SELECT * FROM book_bookmarks WHERE documentId = :documentId ORDER BY progress ASC, id ASC")
    suspend fun bookmarks(documentId: String): List<BookBookmark>

    @Insert
    suspend fun addBookmark(bookmark: BookBookmark): Long

    @Query("DELETE FROM book_bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)
}
