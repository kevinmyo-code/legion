package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing a vehicle service record.
 */
@Entity(tableName = "service_records")
data class ServiceRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: String, // Vehicle.obdMac
    val serviceName: String,
    // Nullable as of v46->v47 (engine retirement step 3/ticket 16) - see [kind]'s own doc comment
    // for why: an ASSERTED row (a driver-stated anchor with no backing event) can legitimately
    // state only one axis ("did the oil change around 50,000 miles, not sure when" has a mileage
    // and no date). Every OBSERVED row - a real logged service - still carries both, non-null, by
    // construction (every write path that creates one, FleetEngineStore.insertObserved, always
    // supplies them); the type only widened to let the OTHER kind this table now holds be honest
    // about what it does not know, not because a logged service became less certain.
    val mileage: Int?,
    val date: Long?, // Timestamp in milliseconds. Same nullability reasoning as [mileage].
    // Cents, never dollars - CLAUDE.md §4 rule 3. Migrated from `cost: Double?` at
    // v19->v20 (ticket 11, `.scratch/fleet-maintenance/issues/11-*`): the column had
    // NO writer anywhere in the app and was null on both of Kevin's real records, so
    // the migration is a straight rename-and-retype with nothing to convert - see
    // MIGRATION_19_20's doc comment for the create/copy/drop/rename mechanics this
    // needed (SQLite cannot ALTER a column's type in place) and CLAUDE.md §5 for why
    // this is the one non-additive exception on this map. null = no figure logged
    // (feeds the build-sheet total and fleet spend, which must say how many records
    // they cover rather than silently treating a cost-less row as $0).
    val costCents: Long? = null,
    // Portable cross-device identity for sync (S1) - see MemoryEntry.syncId.
    @ColumnInfo(defaultValue = "''") val syncId: String = java.util.UUID.randomUUID().toString(),
    // The soft-delete tombstone (ticket 11 §2, v20->v21). Every ORDINARY reader
    // (ServiceRecordDao's list/count/total queries, CarToolbelt, the UI) filters
    // `deleted = 0`; only sync's raw-SQL snapshot sees a tombstoned row at all -
    // same shape as [MaintenanceItem.deleted].
    //
    // **UNLIKE [MaintenanceItem.deleted], this tombstone is LOCAL ONLY and CANNOT
    // propagate across devices.** `maintenance_items` gets away with the tombstone
    // pattern because it syncs `Mode.LWW` on a natural key - a newer `deleted = 1`
    // simply wins the merge like any other edit (`sync/SyncEngine.kt:46-52`).
    // `service_records` syncs `Mode.UNION` on the portable `syncId`
    // (`sync/SyncEngine.kt:175`), and UNION **never updates an existing local row**
    // (`SyncMerge.Action` is Insert | Update, and the merge only ever inserts a
    // syncId it has not seen before) - so setting `deleted = 1` here, on THIS
    // device, never reaches the other device's copy, which keeps `deleted = 0`
    // and goes right on showing the "deleted" record forever. `SyncEngine.kt:222-226`
    // states this explicitly for `ledger_transactions`' own near-identical case:
    // "UNION and delete-propagation are mutually exclusive." A real cross-device
    // tombstone for a UNION table would need its own mechanism (a deleted-ids
    // list shipped as its own row, the way ticket 07's own question 3 framed the
    // option ticket 07 ultimately didn't need) - not built here, because Kevin's
    // sync/ has never actually run on this device (memory/MEMORY.md) and building
    // an untestable cross-device mechanism on spec was judged worse than shipping
    // a plainly-labelled local-only delete. **Every surface that offers this
    // delete must say so in words** - "deletes on this phone only" - never imply
    // it is a global delete the way [MaintenanceItem.deleted]'s genuinely is.
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    // Engine retirement step 3 (`.scratch/backend-erp/issues/16-*`, ticket 15's option 1, ruled
    // 2026-08-27): this table now holds BOTH a real logged service (`kind = "OBSERVED"`) and a
    // driver-stated anchor with no backing event (`kind = "ASSERTED"`) - the exact unification
    // cutover 4's engine `ServiceHistory` record type built (`FleetAspectSeeder.FIELD_SH_KIND`),
    // reproduced here so [FleetRecordBridge.projectAnchorLegacy] has exactly ONE place to derive
    // "last done" from, never two independently-writable stores that can drift apart (the original
    // pre-cutover-4 bug this whole design exists to not repeat). DEFAULT 'OBSERVED' is correct for
    // every pre-v47 row without exception: this table held nothing else before this column existed.
    @ColumnInfo(defaultValue = "OBSERVED") val kind: String = "OBSERVED",
    // Last-modified epoch ms - the SAME role [MaintenanceItem.updatedAt] already plays, needed here
    // for the identical reason: [FleetRecordBridge.projectAnchorLegacy] picks the single MOST
    // RECENTLY STATED row for a (vehicleId, serviceName) pair and takes BOTH its axes together,
    // never blending mileage from one row with date from another (see that function's own doc for
    // why "most recently stated," not "most recent date," is the correct axis). DEFAULT 0 mirrors
    // the migration's own column default; [data.local.MIGRATION_46_47] backfills every existing row
    // to its own `date`, the closest fact on file for when an already-migrated OBSERVED row was
    // last true, and every row this table gains from here on stamps a real value at write time.
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    // v51->v52 (backend-erp ticket 26 step 2, `.scratch/backend-erp/issues/26-the-fleet-cutover-for-real.md`
    // "Step 2: service_history"). The identity bridge from THIS legacy row to its
    // `public.service_history` counterpart - null means "never pushed" (a plain INSERT is correct
    // next sync), non-null names the exact server uuid to PATCH instead of re-inserting. Same role
    // as [com.kevin.legion.data.local.VehicleSidecar.serverId] plays for [Vehicle], but co-located on
    // the row itself rather than a separate sidecar table: unlike Vehicle, this row has no
    // phone-only/server-owned column split to keep apart (every field here is meant to reach the
    // server), and `service_records.id` (the local autoincrement) is genuinely load-bearing
    // elsewhere (`editMileageAndCost`/`softDelete`/`getById` all address a row by it), so - unlike
    // `service_history_replica.id` - it can never be treated as an interchangeable cache surrogate.
    // `FleetEngineStore.syncServiceHistoryToServer` is the only writer of this column.
    @ColumnInfo(defaultValue = "NULL") val serverId: String? = null,
)
