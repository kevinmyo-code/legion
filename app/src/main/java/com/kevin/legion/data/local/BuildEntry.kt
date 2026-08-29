package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single line in the car's build sheet / spend ledger - the unified record
 * behind the logbook. Covers mods, parts, repairs, consumables, and general
 * spend (routine maintenance with a schedule still lives in [ServiceRecord] /
 * [MaintenanceItem]; the logbook view merges both into one timeline).
 *
 * [cost] is nullable on purpose: the driver can log *what* was done without a
 * dollar figure, and the money layer is hidden/gated separately. Cost is in
 * dollars (this is a personal spend log, not accounting).
 *
 * **[cost] is the deliberate odd one out.** Ticket 11 (`.scratch/fleet-maintenance/
 * issues/11-*`) migrated [ServiceRecord.costCents] to `Long` cents per CLAUDE.md §4
 * rule 3, because that column was provably empty (no writer, null on every row) and
 * the migration was free. This column is NOT empty in the same sense - it has real,
 * if sparse, driver-entered data - and ticket 11 scoped the fix to `service_records`
 * only. Left as `Double` on purpose, not a second convention nobody chose.
 */
@Entity(tableName = "build_entries")
data class BuildEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: String,          // Vehicle.obdMac
    val type: String,               // mod | part | repair | consumable | other
    val title: String,
    val vendor: String = "",        // where bought / shop
    val partNumber: String = "",
    val cost: Double? = null,       // dollars; null = no figure logged
    val date: Long,                 // when (epoch ms)
    val mileage: Int? = null,
    val notes: String = "",
    // Portable cross-device identity for sync (S1) - see MemoryEntry.syncId.
    @ColumnInfo(defaultValue = "''") val syncId: String = java.util.UUID.randomUUID().toString(),
    /** The server's `build_entries.id` uuid, recorded after a first successful Supabase push -
     * bookkeeping only, mirroring [com.kevin.legion.data.local.Drive.serverId]'s own shape.
     * [syncId] above (not this column) is what [com.kevin.legion.backend.SupabaseFleetBackend.upsertBuildEntry]
     * actually matches on (`ON CONFLICT (sync_id)`), so a re-push after a lost [serverId] would
     * still update the same server row rather than duplicate it - this column exists purely so a
     * caller can tell "already synced once" from "never synced" without a round trip. */
    @ColumnInfo(defaultValue = "NULL") val serverId: String? = null,
)
