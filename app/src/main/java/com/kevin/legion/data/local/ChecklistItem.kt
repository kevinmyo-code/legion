package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One line inside a [Checklist] - "3 sets goblet squats" under "bio". Carries no `done` state of
 * its own, deliberately: "done" is a fact about a `(item, day)` PAIR, stored once per tick in
 * [ChecklistTick], never a column here - see that class's own doc comment for why one tick model
 * serves both recurring and non-recurring checklists.
 *
 * **Soft-delete only, and this is the second trap this ticket's brief names by hand: editing or
 * deleting an item must not rewrite the past.** Drop "goblet squats" next month and last week must
 * still show it as done - so [deleted] tombstones the item (matches this schema's tombstone
 * posture everywhere else, e.g. [ListItem.deleted]) and its [ChecklistTick] rows are never
 * cascaded away. A history read resolves a soft-deleted item's [text] exactly like a live one;
 * `ChecklistDao` and `ChecklistItemDao` simply have no method that reads through a JOIN filtering
 * `deleted = 0` on the ITEM side of a tick lookup - see `ChecklistController.checklistHistory`'s
 * own doc comment for the read that depends on this.
 */
@Entity(tableName = "checklist_items", indices = [Index("checklistId"), Index(value = ["syncId"], unique = true)])
data class ChecklistItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val checklistId: Long,
    val text: String,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val syncId: String = java.util.UUID.randomUUID().toString(),
    val serverId: String? = null,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)
