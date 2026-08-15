package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * What survives a torn-down grocery trip: **how often a thing gets bought, and nothing else**
 * (Kevin's call, 2026-08-11 - the list vanishes, a staples memory persists).
 *
 * This is the one piece of trip history the app keeps, and it is kept deliberately thin. There is
 * no trip archive, no per-trip line items, no dates beyond [lastBoughtAt]: what was actually bought
 * is recorded from the RECEIPT by the pantry aspect, off a real document with a real total that
 * passes the reconciliation gate (CLAUDE.md §4). A shopping list is a plan, not a record - it says
 * what someone intended to buy, which the receipt may contradict. Storing it as trip history would
 * put an unverifiable second account of the same shopping trip next to the verified one, and the
 * two would drift.
 *
 * [timesBought] is therefore honestly named: it counts times an item was **ticked** on a completed
 * trip, not times it appeared on a list. See [GroceryItem.done].
 *
 * [name] is the primary key in NORMALISED form (trimmed, lowercased) so "Milk", "milk" and " milk "
 * are one staple rather than three that each look infrequent. [displayName] keeps the driver's own
 * capitalisation for reading back - the same "keep item content text as the user typed it"
 * discipline `ui/notes/NotesRows.kt` follows.
 */
@Entity(tableName = "grocery_staples")
data class GroceryStaple(
    @PrimaryKey val name: String,
    val displayName: String,
    /** Times this was ticked on a COMPLETED trip - never times it merely appeared on a list. */
    val timesBought: Int = 1,
    val lastBoughtAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val syncId: String = java.util.UUID.randomUUID().toString(),
)
