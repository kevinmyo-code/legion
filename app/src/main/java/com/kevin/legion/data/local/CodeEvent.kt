package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A DTC event: the set of codes active at a given moment, plus the ELM327
 * Mode 02 freeze-frame snapshot captured at the same time (JSON blob of
 * the PID readings that were live when the code tripped). Freeze frames
 * enable intermittent-code pattern detection — the same P0420 that only
 * trips on cold mornings shows the coolant temp that correlates.
 *
 * codesJson: JSON array of code strings, e.g. ["P0420","P0128"]
 * freezeFrameJson: JSON object of pidâ†’value pairs from Mode 02, or "" if
 *   the adapter returned no freeze frame (some older ELM327 clones skip it).
 */
@Entity(tableName = "code_events")
data class CodeEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: String,
    val timestamp: Long,
    val mileage: Int? = null,
    val codesJson: String,          // JSON array: ["P0420","P0128"]
    val freezeFrameJson: String = "",
    // Portable cross-device identity for sync (S1) - see MemoryEntry.syncId.
    @ColumnInfo(defaultValue = "''") val syncId: String = java.util.UUID.randomUUID().toString(),
    /** `null` until [com.kevin.legion.vehicle.FleetEngineStore.syncCodeEventToServer] first
     * succeeds - bookkeeping only, mirroring [com.kevin.legion.data.local.Drive.serverId]'s own
     * shape (backend-erp ticket 26 step 4). NOT the identity key the server upsert matches on -
     * that is [syncId], already unique server-side (`public.code_events.sync_id`, `ON CONFLICT`
     * in [com.kevin.legion.backend.SupabaseFleetBackend.upsertCodeEvent]) - a code event is never
     * edited once written (this table has no update path), so a repost is always free by
     * construction and this field is never consulted to decide insert vs. update. */
    @ColumnInfo(defaultValue = "NULL") val serverId: String? = null,
)
