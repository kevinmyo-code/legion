package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * What became of one PDF's conversion to a resizable EPUB (books ticket 05, v76 to v77).
 *
 * **Local-only, never synced, never sent to the engine as a record** (ADR 0061's 2026-10-23
 * amendment: the PDF itself passes through the engine and nothing is kept there). The key is the
 * SHA-256 of the PDF's bytes, so a renamed or moved file reuses its EPUB and an edited one converts
 * anew; the EPUB lives at `filesDir/books/converted/<sha256>.epub`.
 *
 * [status] is a [com.kevin.legion.books.ConversionStatus] name, TEXT with no CHECK, so a new status
 * is not a migration. [message] is the server's own sentence (or the phone's, for a file too big to
 * upload), kept so the reader can say it in words. [pages] is the page count the server reported.
 */
@Entity(tableName = "book_conversions")
data class BookConversionRow(
    @PrimaryKey val sha256: String,
    val status: String,
    val attempts: Int,
    val message: String?,
    val updatedAt: Long,
    val pages: Int?,
)

@Dao
interface BookConversionDao {
    @Query("SELECT * FROM book_conversions WHERE sha256 = :sha256")
    suspend fun get(sha256: String): BookConversionRow?

    @Query("SELECT * FROM book_conversions")
    suspend fun all(): List<BookConversionRow>

    @Upsert
    suspend fun upsert(row: BookConversionRow)

    @Query("DELETE FROM book_conversions WHERE sha256 = :sha256")
    suspend fun delete(sha256: String)
}
