package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * One reading-sync write waiting to reach the engine (books ticket 09, v77 to v78).
 *
 * The primary key is what the write is ABOUT, so queuing again replaces instead of piling up: a
 * position is `pos:<sha256>:<variant>` (only the latest place in a book matters, so a hundred page
 * turns offline are one row) and a bookmark is `bm:<uuid>` (a create followed by a delete before
 * the phone reconnects collapses to the delete, which the engine takes as idempotent).
 *
 * [kind] is a [com.kevin.legion.books.OutboxKind] name, TEXT with no CHECK, so a new kind is not a
 * migration. [target] is the tail of the engine path (`<sha256>/<variant>` or `<uuid>`) and [payload]
 * the exact JSON body of the PUT, null for a delete. No title, filename or book text is ever here:
 * the same promise the engine's own tables make (ticket 07).
 */
@Entity(tableName = "book_sync_outbox")
data class BookSyncOutboxRow(
    @PrimaryKey val key: String,
    val kind: String,
    val target: String,
    val payload: String?,
    val queuedAtMs: Long,
)

@Dao
interface BookSyncOutboxDao {
    @Query("SELECT * FROM book_sync_outbox ORDER BY queuedAtMs ASC, `key` ASC")
    suspend fun all(): List<BookSyncOutboxRow>

    @Query("SELECT COUNT(*) FROM book_sync_outbox")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(row: BookSyncOutboxRow)

    @Query("DELETE FROM book_sync_outbox WHERE `key` = :key AND queuedAtMs = :queuedAtMs")
    suspend fun deleteIfUnchanged(key: String, queuedAtMs: Long)
}
