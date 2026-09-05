package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * What survives a torn-down grocery trip: **how often a thing gets bought, and nothing else**
 * (Kevin's call, 2026-08-11 - the list vanishes, a staples memory persists).
 *
 * **WRITE-DEAD as of one-today ticket 10 slice B (2026-09-05).** The only writer was
 * [com.kevin.legion.grocery.GroceryController.completeTrip], and the trip surface that called it
 * (`ui/notes/GroceryScreen.kt`, `manage_grocery`) is retired - "everything is a checklist now", and
 * a checklist has no completed-trip event to fold in. This table stays as HISTORY, not deleted: §5
 * forbids a destructive change here, existing rows are still real purchase-frequency data, and
 * [com.kevin.legion.grocery.GroceryController.suggestions]/[forgetStaple] still read/prune it - they
 * simply never grow again from a fresh trip. The ticket's own "what is knowingly lost" section
 * accepts this: a "Groceries" checklist has no history-derived suggestions until a later analysis
 * slice reads this table (or a successor) some other way.
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
 *
 * live-sync ticket (v62 -> v63, [MIGRATION_62_63]): [serverId]/[updatedAtMs]/[deleted] added.
 * **[syncId] is REUSED as the sync identity**, same posture as [Goal]'s own v63 doc comment - this
 * table already carried a portable identity column, so no fresh `guid` is minted.
 * [com.kevin.legion.grocery.GroceryController.completeTrip] already reuses the prior row's
 * [syncId] across an upsert (see that function's own comment) so a staple keeps one identity
 * across trips even though the PK ([name]) participates in an `OnConflictStrategy.REPLACE`, which
 * is exactly the same reason [serverId] must be reused there too - see
 * `backend/LastAspectsOutbox.kt`'s own `upsertStaple` doc comment.
 */
@Entity(tableName = "grocery_staples", indices = [Index(value = ["syncId"], unique = true)])
data class GroceryStaple(
    @PrimaryKey val name: String,
    val displayName: String,
    /** Times this was ticked on a COMPLETED trip - never times it merely appeared on a list. */
    val timesBought: Int = 1,
    val lastBoughtAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val syncId: String = java.util.UUID.randomUUID().toString(),
    val serverId: String? = null,
    @ColumnInfo(defaultValue = "0") val updatedAtMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)
