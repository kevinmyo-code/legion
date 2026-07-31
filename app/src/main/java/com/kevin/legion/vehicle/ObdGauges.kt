package com.kevin.legion.vehicle

import kotlin.math.roundToInt

/**
 * A slow-changing OBD-II reading the driver can put on the Cruise instrument
 * strip and the Lights Out footer, chosen in Settings from the set the car
 * actually supports ([ObdBluetoothManager.supportedPids]).
 *
 * Deliberately excludes fast PIDs (RPM, engine load, MAF, throttle): polling
 * those every few seconds starves the head unit's single Bluetooth radio and
 * makes phone A2DP music stutter (field call 2026-07-12). Only readings that
 * move slowly enough to poll on the gentle ~15s Cruise cadence belong here.
 *
 * [label] is the strip caption. [supportPid] is the Mode-01 PID number matched
 * against the car's support bitmask; null means the value comes from the
 * adapter itself (ATRV system voltage), so it's always available regardless of
 * car. [read] performs the live read and formats it for display, or null if
 * the adapter didn't answer.
 */
enum class ObdGauge(
    val key: String,
    val label: String,
    val supportPid: Int?,
    private val reader: suspend () -> String?,
) {
    COOLANT("coolant", "TEMP", 0x05, {
        ObdBluetoothManager.getCoolantTemp()?.let { "${(it * 9 / 5) + 32}°" }
    }),
    VOLTS("volts", "VOLTS", null, {
        ObdBluetoothManager.getBatteryVoltage()?.let { "%.1f".format(it) }
    }),
    FUEL("fuel", "FUEL", 0x2F, {
        ObdBluetoothManager.getFuelLevel()?.let { "${it.roundToInt()}%" }
    }),
    INTAKE_AIR("iat", "IAT", 0x0F, {
        ObdBluetoothManager.getIntakeAirTemp()?.let { "${(it * 9 / 5) + 32}°" }
    }),
    LONG_FUEL_TRIM("ltft", "LTFT", 0x07, {
        ObdBluetoothManager.getLongFuelTrim()?.let { "%+.0f%%".format(it) }
    });

    /** Live-reads this gauge and returns a display string, or null if unavailable. */
    suspend fun read(): String? = reader()

    /**
     * True if this gauge should be offered/read for a car whose supported-PID
     * set is [supported]. Adapter-level gauges (null [supportPid]) are always
     * available; when [supported] is empty (disconnected, or the adapter never
     * returned a bitmask) we optimistically allow everything and let the read
     * itself come back null for anything the car really doesn't answer.
     */
    fun isSupported(supported: Set<Int>): Boolean =
        supportPid == null || supported.isEmpty() || supportPid in supported

    companion object {
        /** Enabled by default on a fresh install (the essentials, where supported). */
        val DEFAULT_KEYS: Set<String> = setOf(COOLANT.key, VOLTS.key, FUEL.key)

        fun fromKey(key: String): ObdGauge? = entries.firstOrNull { it.key == key }
    }
}
