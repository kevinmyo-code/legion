package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One physical car. Identified by the Bluetooth MAC of the OBD-II adapter
 * plugged into it, so plugging in a different car's dongle automatically
 * switches Aria's persona, odometer, and maintenance schedule.
 */
@Entity(tableName = "vehicles")
data class Vehicle(
    @PrimaryKey val obdMac: String,
    val name: String,
    val make: String,
    val model: String,
    val year: Int,
    val personaPrompt: String,
    // Driver-reported odometer reading and when it was given. Between
    // readings, mileage is estimated as baseline + tripMilesSinceBaseline.
    val odometerBaseline: Int = 0,
    val odometerBaselineAt: Long = 0L,
    val tripMilesSinceBaseline: Double = 0.0,
    // Last time Aria asked the driver to confirm the odometer, so the
    // monthly check-in doesn't nag more than once per interval.
    val lastOdometerPromptAt: Long = 0L,
    // False until the one-time online lookup has populated default
    // maintenance intervals for this make/model/year.
    val onboarded: Boolean = false,
    // Per-car companion presentation, set during onboarding. voiceName is the
    // chosen prebuilt Gemini voice (blank = app default); personaTraits is the
    // JSON of the personality-picker selections so the picker can round-trip for
    // later edits (the assembled text lives in personaPrompt). Added in v6->v7;
    // the defaultValue mirrors the migration's ADD COLUMN ... DEFAULT '' so a
    // migrated row validates identically to a freshly created one.
    @ColumnInfo(defaultValue = "''") val voiceName: String = "",
    @ColumnInfo(defaultValue = "''") val personaTraits: String = "",
    // Driver-entered trim/variant (e.g. "330i ZHP", "Type R") - we ask, never
    // guess from OBD. Added v8->v9. Sharpens diagnostics/maintenance grounding.
    @ColumnInfo(defaultValue = "''") val trim: String = "",
    // True once the driver has actually stated/confirmed this car's identity
    // (voice register, onboarding, or the AI Profile facts form). Originally the
    // gate on year/make/model-keyed lookups (e.g. NHTSA recalls), so those never
    // reported on the no-OBD placeholder seed (a mascot 1998 Jeep Cherokee) the
    // driver never claimed. That premise expired when seeding went blank for
    // every id (a09aa68, 2026-08-15) - a blank row cannot pass an
    // identity-present test either, so the recall gate is now
    // com.kevin.legion.vehicle.identityPresent(vehicle) instead (ticket 12,
    // `.scratch/fleet-maintenance/issues/12-a-recall-button.md`), applied the
    // same way whether the identity arrived from the driver or from a VIN
    // decode. This flag is NOT retired: it still records that the driver
    // themself stated the identity, which a VIN write-back (ticket 04)
    // deliberately does not claim on their behalf, and other callers may still
    // want that distinction even though recalls no longer do. Added v11->v12;
    // DEFAULT '0' mirrors the migration so a migrated row validates identically
    // to a fresh one.
    @ColumnInfo(defaultValue = "0") val confirmed: Boolean = false,
    // Last-modified epoch ms for cross-device sync last-write-wins (S1, BYO-cloud
    // Google Drive sync). The Kotlin default stamps new rows; an EDIT must re-stamp
    // via copy(updatedAt = System.currentTimeMillis()) or LWW can't tell which side
    // is newer. DEFAULT '0' mirrors the migration for raw/migrated rows.
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    // Hidden from the CARS roster and the picker, without destroying anything
    // (car manager, 2026-07-16). Deliberately ARCHIVE and not DELETE: obd_samples
    // shards by month across ALL cars, so there is no per-car file to remove, and
    // UNION resurrects anything deleted locally - a truthful per-car delete needs
    // the same machinery as DriveReassignment, for an operation nobody runs. The
    // real nuclear option already exists at the platform level: Drive's own
    // "Manage apps -> Delete hidden app data". Rides the LWW path on `updatedAt`
    // like any other edit, so archiving propagates across devices for free.
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
    // Driver-entered engine (e.g. "4.0L I6", "2.5L I4") - ticket 14
    // (`.scratch/fleet-maintenance/issues/14-*`): a factory maintenance schedule
    // can differ by engine on the same year/make/model/trim (a 4.0L XJ and a 2.5L
    // XJ differ on plugs and capacities), so the populate-from-factory-schedule
    // flow's manual-input path asks for it alongside year/make/model/trim/mileage.
    // Added v19->v20 alongside MaintenanceItem's intervalSource/deleted columns -
    // a different table, riding the same version bump per the ticket's own
    // instruction not to hold 06/07 for it. DEFAULT '' mirrors the migration.
    @ColumnInfo(defaultValue = "''") val engine: String = "",
)
