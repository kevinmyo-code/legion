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
    // (voice register, onboarding, or the AI Profile facts form). The no-OBD
    // default seed is a placeholder 1998 Jeep Cherokee; without this flag,
    // year/make/model-keyed lookups (e.g. NHTSA recalls) would report on the
    // mascot car the driver never claimed. Added v11->v12; DEFAULT '0' mirrors
    // the migration so a migrated row validates identically to a fresh one.
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
)
