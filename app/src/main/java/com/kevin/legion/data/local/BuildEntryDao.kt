package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

/**
 * Data Access Object for [BuildEntry] - the build sheet / spend ledger.
 */
@Dao
interface BuildEntryDao {
    @Insert
    suspend fun insert(entry: BuildEntry): Long

    // Dormant: no caller wires a user-facing delete here yet. build_entries is a
    // UNION (append-only) sync table (B19 scope note) - a hard DELETE would be
    // invisible to the sync SELECT and get resurrected on the next pull, same
    // bug as B19. Wiring a delete flow here needs its own tombstone design
    // first, not a copy of the LWW car_tasks/places fix.
    @Delete
    suspend fun delete(entry: BuildEntry)

    /** Full build history for a vehicle, newest first. */
    @Query("SELECT * FROM build_entries WHERE vehicleId = :vehicleId ORDER BY date DESC")
    suspend fun getForVehicle(vehicleId: String): List<BuildEntry>

    /** History of one category (e.g. "mod"), newest first. */
    @Query("SELECT * FROM build_entries WHERE vehicleId = :vehicleId AND type = :type ORDER BY date DESC")
    suspend fun getForVehicleByType(vehicleId: String, type: String): List<BuildEntry>

    /** Total logged spend across build entries (ignores null costs). */
    @Query("SELECT COALESCE(SUM(cost), 0) FROM build_entries WHERE vehicleId = :vehicleId")
    suspend fun totalSpend(vehicleId: String): Double

    /** Logged spend for one category. */
    @Query("SELECT COALESCE(SUM(cost), 0) FROM build_entries WHERE vehicleId = :vehicleId AND type = :type")
    suspend fun spendByType(vehicleId: String, type: String): Double

    /** Total entries for a vehicle - ticket 09's FLEET "NOT BUILT YET" block needs a real count, not a hardcoded one. */
    @Query("SELECT COUNT(*) FROM build_entries WHERE vehicleId = :vehicleId")
    suspend fun countForVehicle(vehicleId: String): Int

    /** Every build entry on file, across all vehicles - the [com.kevin.legion.backend.FleetReconcile]
     * upload source, mirroring `CodeEventDao.getAllForUpload`'s role for a table with no
     * engine-record counterpart. */
    @Query("SELECT * FROM build_entries")
    suspend fun getAllForUpload(): List<BuildEntry>

    /** Looks a build entry up by its portable [BuildEntry.syncId] - the insert-if-absent replica
     * check [com.kevin.legion.backend.FleetReconcile] uses, same role `CodeEventDao.getBySyncId`
     * plays for `CodeEvent`. A build entry is never edited once logged (this DAO's own doc comment
     * on why `delete` is dormant), so "already present" is reason enough to skip re-inserting. */
    @Query("SELECT * FROM build_entries WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): BuildEntry?

    /** Looks a build entry up by its Room row id - what a fresh [FleetEngineStore.recordBuildEntry]
     * call reads back to push, mirroring `DriveDao.getById`'s role for [Drive]. */
    @Query("SELECT * FROM build_entries WHERE id = :id")
    suspend fun getById(id: Long): BuildEntry?

    /** Records the server's uuid after a first successful push - see [BuildEntry.serverId]'s own
     * doc comment. Mirrors [DriveDao.setServerId]/[CodeEventDao.setServerId] exactly. */
    @Query("UPDATE build_entries SET serverId = :serverId WHERE id = :id")
    suspend fun setServerId(id: Long, serverId: String)

    /** `FleetSync`'s pull-side tombstone handling - the tombstone design [delete]'s own dormant-code
     * comment says was needed first: a `deleted_at` set server-side is the trigger, honoured here as
     * a real local delete rather than a soft one, same posture as [DriveDao.deleteBySyncId]. */
    @Query("DELETE FROM build_entries WHERE syncId = :syncId")
    suspend fun deleteBySyncId(syncId: String)
}
