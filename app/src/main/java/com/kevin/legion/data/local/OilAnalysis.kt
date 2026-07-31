package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A Blackstone (or equivalent) used-oil analysis result, voice-entered or
 * typed in the ControlPanel. All wear-metal values in parts-per-million (ppm).
 * Null = lab did not report that element (older reports omit some metals).
 *
 * Killer synergy: trend iron ppm rising against chassis_quirks entry for
 * S54 rod-bearing wear â†’ proactive pre-failure warning before a $12k engine.
 */
@Entity(tableName = "oil_analyses")
data class OilAnalysis(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: String,
    val date: Long,
    val mileage: Int? = null,
    val oilBrand: String = "",
    val oilGrade: String = "",          // e.g. "5W-30"
    val drainIntervalMiles: Int? = null,
    // Wear metals (ppm)
    val iron: Int? = null,
    val copper: Int? = null,
    val lead: Int? = null,
    val tin: Int? = null,
    val aluminum: Int? = null,
    val chromium: Int? = null,
    val nickel: Int? = null,
    // Contaminants (ppm)
    val sodium: Int? = null,
    val potassium: Int? = null,
    val silicon: Int? = null,
    val boron: Int? = null,
    val magnesium: Int? = null,
    // Oil condition
    val fuelPercent: Double? = null,    // fuel dilution %
    val waterPercent: Double? = null,
    val tbn: Double? = null,            // total base number (alkalinity reserve)
    val viscosityCst: Double? = null,   // kinematic viscosity at 100°C
    val labNotes: String = "",          // verbatim lab comment field
    // Portable cross-device identity for sync (S1) - see MemoryEntry.syncId.
    @ColumnInfo(defaultValue = "''") val syncId: String = java.util.UUID.randomUUID().toString(),
)
