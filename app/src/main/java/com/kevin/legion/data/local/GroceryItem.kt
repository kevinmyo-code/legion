package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One line on the CURRENT grocery trip (Kevin, 2026-08-11: "a grocery list, made once and torn
 * down once grocery is complete").
 *
 * **There is no trip entity. The rows ARE the trip.** A trip is "in progress" exactly when this
 * table is non-empty, and completing one deletes every row - see
 * [com.kevin.legion.grocery.GroceryController.completeTrip]. That is the whole lifecycle, and
 * modelling it with a `GroceryTrip` row carrying an `active` flag would add a state that can
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
    /** Ticked means IN THE BASKET. What [com.kevin.legion.grocery.GroceryController.completeTrip]
     * counts toward [GroceryStaple.timesBought] - an item left unticked at DONE was not bought, and
     * counting it would teach the suggestions the opposite of the truth. */
    val done: Boolean = false,
    val doneAt: Long? = null,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val syncId: String = java.util.UUID.randomUUID().toString(),
)
