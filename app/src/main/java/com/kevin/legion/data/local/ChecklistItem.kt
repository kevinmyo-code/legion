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
 *
 * **Measured items, added one-today ticket 09's second build (2026-09-04, Kevin: "walk 10k steps
 * filled everyday" as the worked example).** All three of [measureUnit]/[measureTarget]/
 * [measureDirection] are nullable and travel together: null [measureUnit] means a plain binary
 * item exactly as before, a non-null [measureUnit] means every tick against this item must carry
 * an actual number (`ChecklistController.tick` refuses a null-valued tick on a measured item
 * outright - "a number is the point", Kevin's ruling - rather than silently recording it done with
 * nothing measured). [measureTarget] is nullable independently of [measureUnit]: a unit with no
 * target means "just record the number, no goal to compare against". [measureDirection] is only
 * meaningful alongside a [measureTarget] - null there with a unit set is the "just record it" case
 * this sentence just described.
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
    /** Free text the user typed - "steps", "kg", "min", "kcal". Null means a plain binary item;
     * see this class's own doc comment for how this travels with [measureTarget]/[measureDirection]. */
    val measureUnit: String? = null,
    /** The number to aim at, in [measureUnit]. Null even with [measureUnit] set means "just record
     * the number, no target to compare against". */
    val measureTarget: Double? = null,
    /** [MeasureDirection.AT_LEAST] or [MeasureDirection.AT_MOST], stored as its [Enum.name]. Null
     * with a unit set means "just record it, no target" - see this class's own doc comment. */
    val measureDirection: String? = null,
)

/** [ChecklistItem.measureDirection]'s two values, spelled out so a caller never hand-types the
 * literal string - stored as TEXT with no CHECK constraint (matches this schema's usual posture,
 * e.g. [Goal.aspect]'s own doc comment), so widening this later needs no migration. */
enum class MeasureDirection { AT_LEAST, AT_MOST }
