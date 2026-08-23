package com.kevin.legion.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One page of the widget pager (aspect-engine map, charter decision 9/1) - "fleet", "ledger",
 * "pantry", and every user-authored aspect are all a row here, not a hardcoded package. The
 * engine core (`.scratch/aspect-engine/issues/16-build-engine-core.md`) exists precisely so a
 * new aspect is a row insert, never a new `@Entity`.
 *
 * [icon]/[color] are opaque strings the UI layer interprets (an icon key, a hex/token string) -
 * this entity does not know or care about mission-control's token vocabulary, matching the
 * engine's "engine owns record types, fields, CRUD... never user-authorable from the phone" split
 * for the native side (charter decision 2) applied the other way: the SCHEMA layer must not know
 * about UI decisions either.
 *
 * **Aspect delete = archive** (ticket 03 answer point 4): [archived]/[archivedAt] are the only
 * delete state this entity carries. There is no hard-delete path on this table at all - restoring
 * clears [archived] and [archivedAt] back to false/null, and a 30-day-later hard purge (symmetric
 * with [EngineRecord]'s trash) is a job that reads [archivedAt], never a row this entity deletes
 * itself.
 */
@Entity(tableName = "aspects")
data class Aspect(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String = "",
    val color: String = "",
    /** Pager ordering - "home is page one", every other aspect sorts after it by this column. */
    val position: Int = 0,
    val archived: Boolean = false,
    val archivedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
