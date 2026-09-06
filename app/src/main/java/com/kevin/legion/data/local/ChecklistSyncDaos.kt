package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/**
 * The sync half of the three checklist tables' data access - the reads and writes
 * `backend/ChecklistsSync.kt` and `backend/ChecklistsOutbox.kt` need and nothing else does.
 *
 * **Why these are separate DAOs rather than four more methods on [ChecklistDao]/[ChecklistItemDao]/
 * [ChecklistTickDao].** [ChecklistDao] already declares exactly 11 functions, which is detekt's
 * `TooManyFunctions.allowedFunctionsPerInterface` ceiling to the digit (`config/detekt/detekt.yml`);
 * adding the sync methods there would go over it, and the only ways past that are a baseline entry
 * or a `@Suppress` - both of which are a check switched off, and this ticket's brief forbids the
 * first by name. The split is not merely a way around a lint rule either: these queries exist for
 * one caller (the sync layer) and deliberately read rows the ordinary DAOs deliberately hide -
 * soft-deleted ones, archived ones - so a screen that reaches for the wrong DAO gets the wrong
 * answer loudly rather than quietly.
 *
 * **No schema change.** These are new `@Dao` interfaces over EXISTING tables and columns
 * ([Checklist.syncId]/[Checklist.serverId]/[Checklist.deleted] and their siblings have been on all
 * three entities since v64 - see each entity's own "no sync code is wired yet" doc comment). Room's
 * exported schema JSON describes entities and indices, not DAOs, so nothing here needs a migration
 * or a version bump, and `app/schemas/` stays byte-identical.
 */
@Dao
interface ChecklistSyncDao {
    /** Every checklist this device holds, INCLUDING soft-deleted and archived ones - the merge
     * has to be able to match a server row against a local row this device already tombstoned, or
     * that tombstoned row looks exactly like "no local match" and gets resurrected as a fresh
     * insert (the 88-row bug [com.kevin.legion.backend.EventsSync.pull] documents by name). */
    @Query("SELECT * FROM checklists")
    suspend fun getAll(): List<Checklist>

    @Query("SELECT * FROM checklists WHERE id = :id")
    suspend fun getByIdIncludingDeleted(id: Long): Checklist?

    @Insert
    suspend fun insert(checklist: Checklist): Long

    @Update
    suspend fun update(checklist: Checklist)

    /** Records the id the engine minted for this row. Written the moment a push succeeds, never
     * client-minted - the same "a serverId is earned by a real round trip, never guessed" rule
     * [Checklist.serverId]'s own doc comment states. Load-bearing beyond bookkeeping here: every
     * item and tick route is nested under the parent checklist's server uuid
     * (`checklists/urls.py`), so a child cannot be pushed at all until this has been written. */
    @Query("UPDATE checklists SET serverId = :serverId WHERE id = :id")
    suspend fun setServerId(id: Long, serverId: String)
}

/** The sync half of [ChecklistItemDao] - see [ChecklistSyncDao]'s own doc comment for why this is
 * a separate interface. [ChecklistItemDao.getByIdIncludingDeleted] already exists there (the
 * history read needs it) and is not duplicated here. */
@Dao
interface ChecklistItemSyncDao {
    @Query("SELECT * FROM checklist_items")
    suspend fun getAll(): List<ChecklistItem>

    @Insert
    suspend fun insert(item: ChecklistItem): Long

    @Update
    suspend fun update(item: ChecklistItem)

    @Query("UPDATE checklist_items SET serverId = :serverId WHERE id = :id")
    suspend fun setServerId(id: Long, serverId: String)
}

/** The sync half of [ChecklistTickDao] - see [ChecklistSyncDao]'s own doc comment. */
@Dao
interface ChecklistTickSyncDao {
    @Query("SELECT * FROM checklist_ticks")
    suspend fun getAll(): List<ChecklistTick>

    @Insert
    suspend fun insert(tick: ChecklistTick): Long

    @Update
    suspend fun update(tick: ChecklistTick)

    @Query("UPDATE checklist_ticks SET serverId = :serverId WHERE id = :id")
    suspend fun setServerId(id: Long, serverId: String)
}
