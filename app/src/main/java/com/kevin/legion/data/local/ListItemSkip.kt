package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single skipped occurrence of a recurring [ListItem] (ticket 04: "skip a single occurrence,
 * never move one" - moving an occurrence does not exist; skip it and add a one-off item instead).
 * This is the ONLY per-occurrence state ticket 04's design keeps - one row per skip, not one row
 * per occurrence, because occurrences are never materialised (see [com.kevin.legion.notes.Recurrence]'s
 * doc comment). Subtracted DURING expansion, never filtered after the fact - see
 * [com.kevin.legion.notes.Recurrence.occurrencesInWindow].
 */
@Entity(
    tableName = "list_item_skips",
    indices = [Index("itemId")],
)
data class ListItemSkip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    /** UTC-midnight epoch ms of the calendar date being skipped - same convention as [LedgerTransaction.txnDate]. */
    val skippedDate: Long,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val syncId: String = java.util.UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)
