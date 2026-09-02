package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A named list - a checklist, a note, or (via items carrying [ListItem.startsAt]) a run of
 * reminders (fire a local alert at T, never a Google Calendar event -
 * `.scratch/google-account-integration/issues/04-what-happens-to-local-timed-items.md` reversed
 * that claim: Google owns appointments, this table never mirrors them).
 * `tickable = false` makes every item in it a note line rather than something
 * to check off (`.scratch/notes-lists-calendar/issues/01-entity-model-and-cartask-migration.md`,
 * "one model: a list owns items; a note is a list whose items do not tick").
 *
 * Absorbs [CarTask] (one list named "Car") and [PlaceReminder] (one list named "Reminders") - the
 * v9->v10 migration ([com.kevin.legion.data.local.MIGRATION_9_10]) copies both without dropping
 * either source table. See that migration's doc comment for why the source tables survive one
 * more version.
 *
 * **This table (and [ListItem]) are now FROZEN from the live app's own point of view** -
 * `notes/NotesController.kt`'s own class doc records that its read/write path was repointed onto
 * the `events` table entirely (backend-erp ticket 15 step 4, "notes gets ONE local table"), and
 * `item_lists`/`list_items` keep exactly the rows that existed before that cutover. live-sync's own
 * map still gives this table a server home (`.scratch/live-sync/map.md`'s "Lists" row) because
 * those rows are real content with no other surviving copy - "not a duplicate of `events`", per the
 * map's own ruling - even though nothing in the running app writes a NEW one anymore. See
 * [serverId]'s own doc comment for what that means for write-through here.
 *
 * live-sync ticket (v62 -> v63, [MIGRATION_62_63]): [serverId] added. **[syncId] is REUSED as the
 * sync identity**, same posture as [Goal]/[GroceryStaple]'s own v63 doc comments - this table
 * already carried [syncId]/[updatedAt]/[deleted], so nothing else was missing.
 */
@Entity(tableName = "item_lists", indices = [Index(value = ["syncId"], unique = true)])
data class ItemList(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** False makes every item in this list a note line, never tickable (ticket 01). */
    val tickable: Boolean = true,
    /** Manual ordering in the list-of-lists screen (ticket 07 - not built this phase). */
    val sortOrder: Int = 0,
    /**
     * Touched on every add/tick/edit, by voice and by hand alike - drives the "most recently
     * used" default a voice command with no named list falls back to (ticket 05). Reading it
     * alone is not enough to pick a default: [archived] must be checked too, and the DAO's
     * `mostRecentlyUsed()` query enforces that in SQL rather than trusting every caller to filter.
     */
    val lastUsedAt: Long = System.currentTimeMillis(),
    /**
     * Hidden from the list-of-lists screen, kept whole, reachable behind a SHOW ARCHIVED toggle
     * (ticket 11, matching `ui/CarsScreen.kt`'s existing pattern for archived vehicles). Distinct
     * from [deleted] - an archived list is very much alive, just out of the way.
     */
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    // Last-modified epoch ms for cross-device sync last-write-wins, carried per ticket 09 even
    // though nothing syncs yet - see MIGRATION_9_10's doc comment.
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val syncId: String = java.util.UUID.randomUUID().toString(),
    /** live-sync ticket - null until a real round trip earns one. Not client-minted (CLAUDE.md
     * live-sync ruling 5: "do not trust a client-minted id"). */
    val serverId: String? = null,
    // Soft-delete tombstone (mirrors CarTask's - see its doc comment for why this survives
    // instead of a hard DELETE).
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)
