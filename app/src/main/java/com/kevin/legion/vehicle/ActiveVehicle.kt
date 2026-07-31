package com.kevin.legion.vehicle

import android.content.Context
import android.content.Intent
import com.kevin.legion.ai.AriaBrain
import com.kevin.legion.service.AriaForegroundService
import com.kevin.legion.ui.AppBackground
import java.util.UUID

/**
 * Which car this DEVICE is currently in (CLAUDE.md §5 car profiles, 2026-07-16).
 *
 * **The problem this solves.** Vehicle identity used to be the OBD dongle's
 * Bluetooth MAC, on the assumption in [VehicleController]'s doc that "each car has
 * its own dongle". With one dongle moved between two cars, both cars collapse into
 * one vehicle id: their telemetry interleaves, and worse, `vehicles` is LWW on
 * that id across sync, so registering car B overwrites car A's row - including
 * `odometerBaseline`, which drives every maintenance interval. The dongle stops
 * being the identity; the driver's explicit choice is.
 *
 * **This is per-device state and MUST NOT sync.** It is deliberately absent from
 * [com.kevin.legion.sync.SyncEngine]'s registry. The phone can be in the
 * Outlander at the same moment the head unit is bolted in the Cherokee - they
 * legitimately disagree, and that disagreement is correct. If this synced,
 * choosing a car on the phone would flip the head unit mid-drive.
 *
 * A null selection means "auto": fall back to the connected dongle's MAC, which is
 * exactly the old behaviour, so an install that never touches the picker is
 * unaffected.
 */
object ActiveVehicle {
    private const val PREFS = "active_vehicle"
    private const val KEY_ID = "vehicle_id"

    /** Prefix for ids we mint ourselves, as opposed to a dongle MAC. */
    private const val SYNTHETIC_PREFIX = "car:"

    /** The driver's explicit choice, or null for "auto" (use the connected dongle). */
    fun selected(context: Context): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ID, null)?.takeIf { it.isNotBlank() }

    /** Sets the active car, or null to go back to auto (dongle-derived). */
    fun select(context: Context, vehicleId: String?) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (vehicleId.isNullOrBlank()) prefs.edit().remove(KEY_ID).apply()
        else prefs.edit().putString(KEY_ID, vehicleId).apply()
    }

    /**
     * The vehicle id everything should key on: the driver's choice if they made
     * one, else the connected dongle's MAC, else [VehicleController.DEFAULT_VEHICLE_ID].
     *
     * The MAC fallback is what keeps existing installs working untouched - their
     * rows are already keyed by it, and it stays their id forever (see
     * [newVehicleId] for why that is safe).
     */
    fun current(context: Context): String =
        selected(context)
            ?: ObdBluetoothManager.connectedDeviceAddress
            ?: VehicleController.DEFAULT_VEHICLE_ID

    /**
     * Mints an id for a car the driver added by hand rather than by plugging in a
     * dongle.
     *
     * **Why this needs no Room migration:** `vehicle.obdMac` is the primary key but
     * it is just a `String` - nothing anywhere enforces that it looks like a MAC.
     * So a synthetic id lives in the same column beside real MACs, existing rows
     * keep the MAC they already have as their permanent id, and the schema does not
     * change. The column name is now a misnomer; that is the price of not
     * rewriting every key in the database and every synced snapshot on Drive.
     */
    fun newVehicleId(): String = SYNTHETIC_PREFIX + UUID.randomUUID().toString()

    /** True if [vehicleId] was minted by [newVehicleId] rather than being a dongle MAC. */
    fun isSynthetic(vehicleId: String): Boolean = vehicleId.startsWith(SYNTHETIC_PREFIX)

    /**
     * Everything that must happen when [current] can resolve to a different vehicle
     * id than it did a moment ago - shared by [CarsScreen]'s explicit picker AND the
     * "auto" dongle-connect/disconnect fallback in [ObdBluetoothManager]. Extracted
     * here (rather than duplicated) after the auto-resolve path was found missing all
     * three steps, which is exactly the drive-notes ticket 02 bug ("old avatar/voice
     * flashes on startup, corrects itself once you revisit Settings"): a cold app
     * start composes Cruise's avatar/persona against [VehicleController.DEFAULT_VEHICLE_ID]
     * before the OBD dongle finishes connecting, and nothing told the UI, the cached
     * base system instruction, or a prewarmed voice socket that the resolved identity
     * changed once it did.
     *
     * - [AriaBrain.invalidateBase] - identity is per-car and the base instruction is
     *   cached for 2 minutes, so without this the companion speaks with the previous
     *   car's persona until the TTL lapses.
     * - `ACTION_CAR_SWITCHED` - a warm Live socket holds the previous car's voice
     *   (same shape as the "default voice after onboarding" field bug).
     * - [AppBackground.notifyChanged] - wallpaper + avatar are decoded at render, so
     *   the surfaces need telling to re-read.
     */
    fun notifyResolutionChanged(context: Context) {
        // The GPS beacon role is per-car (see DeviceRole): this same device is the
        // beacon in the car with a bolted-in head unit and the app host in the car
        // without one. Applying it here, rather than making the driver remember a
        // Setup toggle on every swap, is the point of storing it per car - and this
        // is the one place that already knows the resolved car changed.
        com.kevin.legion.service.DeviceRole.applyCurrent(context)
        AriaBrain.get(context).invalidateBase()
        runCatching {
            context.startService(
                Intent(context, AriaForegroundService::class.java)
                    .setAction(AriaForegroundService.ACTION_CAR_SWITCHED)
            )
        }
        AppBackground.notifyChanged()
    }
}
