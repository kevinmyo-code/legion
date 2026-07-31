package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface EpisodicTurnDao {
    @Insert
    suspend fun insert(turn: EpisodicTurn)

    /** All turns for one Live connection, oldest first - what ticket 02's consolidation pass reads. */
    @Query("SELECT * FROM episodic_turns WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun forSession(sessionId: String): List<EpisodicTurn>

    /** Distinct session ids with at least one turn, oldest first - what consolidation iterates. */
    @Query("SELECT sessionId FROM episodic_turns GROUP BY sessionId ORDER BY MIN(timestamp) ASC")
    suspend fun pendingSessionIds(): List<String>

    /** Clears one session's turns once consolidation has distilled them - the buffer stays small. */
    @Query("DELETE FROM episodic_turns WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)

    /** Safety-valve purge: anything older than this survives long past any plausible drive length. */
    @Query("DELETE FROM episodic_turns WHERE timestamp < :beforeMs")
    suspend fun purgeOlderThan(beforeMs: Long)

    /** Clears the entire raw buffer - part of the "Forget memories" reset (unconsolidated but real). */
    @Query("DELETE FROM episodic_turns")
    suspend fun deleteAll()

    /** Latest turn timestamp for a car, across any session (consolidated or not) - continuity (ticket 06). */
    @Query("SELECT MAX(timestamp) FROM episodic_turns WHERE vehicleId = :vehicleId")
    suspend fun latestTimestamp(vehicleId: String): Long?
}
