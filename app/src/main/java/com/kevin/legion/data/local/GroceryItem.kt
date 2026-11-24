package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One line on the CURRENT grocery trip (Kevin, 2026-08-11: "a grocery list, made once and torn
 * down once grocery is complete").
 *
 * **READ-DEAD and WRITE-DEAD as of the lists redesign slice 1a (2026-11-24).** The only reader and
 * writer left was `grocery/GroceryController.kt`, which had no caller of its own outside doc
 * comments and is deleted. The one remaining reader of this table is
 * [com.kevin.legion.grocery.GroceryChecklistMigration], the one-time carry onto the "Groceries"
 * checklist, which is still wired at app start. The table stays registered until that migrator
 * retires; see that object's own class doc.
 *
 * **There is no trip entity. The rows ARE the trip.** A trip was "in progress" exactly when this
 * table was non-empty, and completing one deleted every row. That was the whole lifecycle, and
 * modelling it with a `GroceryTrip` row carrying an `active` flag would have added a state that can
 * disagree with its own items (an active trip with no items, an item on a completed trip) for no
 * capability in return.
 *
 * **Deliberately NOT a [ListItem].** The notes model was just collapsed to one list on the grounds
 * that named buckets hide things ([MIGRATION_12_13]), and re-introducing "the grocery list" as
 * another bucket in that table would undo it. The justification for a separate table is lifecycle,
 * not category: a [ListItem] is kept until the driver removes it, while every row here is expected
 * to be destroyed within the hour. Those two are not the same kind of thing, and a `done` flag
 * meaning "bought, delete me shortly" next to one meaning "finished, keep the record" is how a
 * cleanup routine eventually eats somebody's actual to-do list.
 *
 * No `deleted` tombstone and no soft delete: this table is not synced (grocery trips are local and
 * short-lived, the same accepted cost `LISTS_DO_NOT_SYNC_NOTICE` states for lists), and a tombstone
 * exists to stop a peer resurrecting a row - which cannot happen for data no peer ever sees. It
 * still carries [syncId] so that adding sync later does not need a migration.
 */
@Entity(tableName = "grocery_items")
data class GroceryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    /** Ticked means IN THE BASKET. What the deleted `GroceryController.completeTrip` counted
     * toward [GroceryStaple.timesBought] - an item left unticked at DONE was not bought, and
     * counting it would have taught the suggestions the opposite of the truth. */
    val done: Boolean = false,
    val doneAt: Long? = null,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val syncId: String = java.util.UUID.randomUUID().toString(),
)
