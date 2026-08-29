package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One `clear_codes` transaction's outcome (`.scratch/hands-and-senses/issues/01-clear-dtc.md`,
 * D2/D3, resolved 2026-08-16) - the record [CodeEvent] cannot hold, because that table has no
 * update, no delete, and no field that can mean "cleared" (see [CodeEvent]'s own doc comment for
 * the snapshot shape it DOES own). A clear is an event at a moment in time, not a retroactive
 * property of rows written weeks earlier, so it gets its own table rather than a `clearedAt`
 * column bolted onto [CodeEvent].
 *
 * **This row is written ONLY for the three outcomes where Mode 04 was actually sent** -
 * `CLEARED`/`RETURNED`/`UNVERIFIED`. `NOTHING_TO_CLEAR` and `REFUSED` never send anything (D2's
 * own "Command sent?" column), so there is no "moment of the send" to timestamp and no row lands
 * here for either - see [com.kevin.legion.vehicle.DtcClearController.recordOutcome]. Diagnostic
 * breadcrumbs for ALL FIVE outcomes still go to [com.kevin.legion.MidnightEvents.dtcCleared] and
 * [com.kevin.legion.car.CarProbeLog] regardless (D8) - only the DURABLE row is gated this way.
 *
 * **The `44` ack ([ackRaw]) is diagnostic only and never upgrades [outcome].** D1's whole reason
 * for existing: `sendCommand` returns `""` on failure and a quiet link answers exactly like a
 * successful ack at the raw-string layer, so "cleared" is only ever asserted off the POST-SEND
 * re-read, never off this field.
 *
 * **[mileage] carries the exact same unlabelled-estimate caveat [CodeEvent.mileage] already does**
 * (see `vehicle/CarToolbelt.kt`'s `codeHistory` doc comment, roughly lines 86-104) - a frozen `Int`
 * snapshot from [com.kevin.legion.vehicle.VehicleController.currentMileage] at the moment of the
 * send, with nothing captured alongside it to prove how stale the estimate was. Not fixed here;
 * this ticket does not render mileage on any surface, so the gap is inherited, not introduced.
 *
 * **Sync `Mode.UNION` on [syncId], no `deleted` tombstone** - append-only falsifiable facts about
 * the car, same posture [CodeEvent] already carries (see `sync/SyncEngine.kt`'s `REGISTRY`).
 */
@Entity(tableName = "code_clear_events")
data class CodeClearEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: String,
    /** Epoch ms at the moment Mode 04 was actually sent - see the class doc for why a row only
     * ever exists for an outcome where that happened. */
    val timestamp: Long,
    val mileage: Int? = null,
    /** JSON array - the call-2 snapshot immediately before the send (D4.2: NOT the call-1 prompt
     * read, which can be a minute stale by the time the driver confirms). */
    val codesBeforeJson: String,
    /** JSON object, Mode 02 freeze frame captured alongside the before-snapshot; `""` if the
     * adapter returned none (same convention [CodeEvent.freezeFrameJson] uses). */
    val freezeFrameJson: String = "",
    /** JSON array from the post-send re-read - empty array `"[]"` means CLEARED (the re-read
     * proved clean), a non-empty array names RETURNED's survivors, and `""` (never attempted/
     * never completed) means the outcome is UNVERIFIED. This three-way distinction is why `""`
     * and `"[]"` are NOT interchangeable here, unlike [CodeEvent.freezeFrameJson]'s simpler
     * present-or-absent convention. */
    val codesAfterJson: String = "",
    /** [com.kevin.legion.vehicle.DtcClearController.ClearOutcome.name] - always one of
     * CLEARED/RETURNED/UNVERIFIED for a row that exists at all (see the class doc). */
    val outcome: String,
    /** Raw Mode 04 response, diagnostic only - see the class doc's D1 warning. */
    val ackRaw: String = "",
    // Portable cross-device identity for sync (S1) - see MemoryEntry.syncId.
    @ColumnInfo(defaultValue = "''") val syncId: String = java.util.UUID.randomUUID().toString(),
    /** `null` until [com.kevin.legion.vehicle.FleetEngineStore.syncCodeClearEventToServer] first
     * succeeds - bookkeeping only, same shape and same reasoning as [CodeEvent.serverId]
     * (backend-erp ticket 26 step 4): [syncId] is the identity key the server upsert matches on,
     * this field is never consulted to decide insert vs. update. */
    @ColumnInfo(defaultValue = "NULL") val serverId: String? = null,
)
