package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * Data Access Object for [Goal]. Every read here is the copy-forward "latest row per lineage"
 * shape [Goal]'s doc comment describes - nothing is ever updated or deleted in place, a material
 * change is a fresh [insert] with [Goal.supersedesId] pointed at the prior row.
 */
@Dao
interface GoalDao {
    /** Inserts a new goal, or a new revision of an existing [Goal.lineageId]. Never REPLACE - a
     * revision is a new row by construction, see [Goal]'s doc comment. Returns the new row's id,
     * which a caller minting the FIRST row of a lineage typically also uses as [Goal.lineageId]. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(goal: Goal): Long

    /**
     * The current row of every ACTIVE lineage for [aspect] - "latest revision, still open". For
     * each [Goal.lineageId] this is the row with the highest [Goal.id] (revisions only ever move
     * forward), filtered to `status = 'active'` so an achieved or abandoned lineage drops out
     * without needing a separate close-tracking column.
     */
    @Query(
        "SELECT * FROM goals g WHERE g.aspect = :aspect AND g.status = 'active' AND g.id = (" +
            "SELECT MAX(g2.id) FROM goals g2 WHERE g2.lineageId = g.lineageId" +
            ")"
    )
    suspend fun currentGoals(aspect: String): List<Goal>

    /** Every ACTIVE goal across every aspect - one read for the HOME advisor (answer call 1). */
    @Query(
        "SELECT * FROM goals g WHERE g.status = 'active' AND g.id = (" +
            "SELECT MAX(g2.id) FROM goals g2 WHERE g2.lineageId = g.lineageId" +
            ")"
    )
    suspend fun allCurrentGoals(): List<Goal>

    /** Every revision of one lineage, oldest first - the full falsifiable trail (answer call 4). */
    @Query("SELECT * FROM goals WHERE lineageId = :lineageId ORDER BY id ASC")
    suspend fun history(lineageId: Long): List<Goal>

    /**
     * Closes a lineage by writing [status]/[closedAt] onto its CURRENT row - the only mutation
     * this DAO performs in place, because closing is not a material change to the goal's content
     * (statement/number/deadline), it is a status flip on whichever row is already current. A
     * later reopen or restatement still goes through [insert] as a fresh revision.
     *
     * live-sync ticket: also bumps [Goal.updatedAt] to [closedAt] - [Goal] has no separate
     * `updatedAtMs` sync clock (it reuses [Goal.updatedAt], see that entity's own v63 doc comment),
     * and this in-place UPDATE is the one write this DAO makes that does not go through a fresh
     * [insert] (whose own `System.currentTimeMillis()` default would otherwise stamp it). Without
     * this, a close() would never look "changed" to [com.kevin.legion.backend.LastAspectsSync]'s
     * merge, and the write-through push that follows it in
     * [com.kevin.legion.backend.LastAspectsWriteThrough.closeGoal] would be the only thing telling
     * the server anything happened at all.
     */
    @Query(
        "UPDATE goals SET status = :status, closedAt = :closedAt, updatedAt = :closedAt WHERE id = (" +
            "SELECT MAX(id) FROM goals WHERE lineageId = :lineageId" +
            ")"
    )
    suspend fun close(lineageId: Long, status: String, closedAt: Long)

    /** By-PK REPLACE, added for [com.kevin.legion.backend.LastAspectsSync]'s pull merge alone -
     * every other in-app write goes through [insert] (a new revision) or [close] (the one in-place
     * status flip), never this. A remote row is matched to a local one by [Goal.syncId] first (the
     * merge's own `localByGuid` lookup), so this always overwrites the SAME row the match already
     * resolved to - it is not a second way to create or renumber a revision. */
    @Update
    suspend fun update(goal: Goal)

    /** Every row regardless of lineage/status, INCLUDING tombstoned - [LastAspectsSync]/
     * [LastAspectsBackfill]'s merge/push read, same role [CategoryDao.getAllIncludingDeleted]
     * plays for categories. */
    @Query("SELECT * FROM goals")
    suspend fun getAllIncludingDeleted(): List<Goal>

    /** Exists for interface symmetry with every other synced table's soft-delete path - see
     * [LedgerConfigWriteThrough.addCategory]'s own "no delete counterpart" doc comment for the
     * precedent. Nothing in [com.kevin.legion.goals.GoalController] deletes a goal today (a goal is
     * closed, never removed - see [Goal]'s class doc), so this is unused-but-present rather than
     * wired to a caller. */
    @Query("UPDATE goals SET deleted = 1, updatedAt = :at WHERE syncId = :syncId")
    suspend fun softDeleteBySyncId(syncId: String, at: Long)
}
