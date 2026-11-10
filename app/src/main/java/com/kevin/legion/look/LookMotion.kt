package com.kevin.legion.look

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.location.LocationController
import com.kevin.legion.vehicle.ActiveVehicle
import com.kevin.legion.vehicle.ObdBluetoothManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the two motion signals the phone has for [LookRules.motion] (ADR 0084, traced 2026-11-10):
 * `LocationController`'s last fix (GPS while the look screen holds fast mode; network or passive,
 * usually without speed, otherwise) and the newest OBD vehicle-speed sample when the dongle is
 * connected (samples are keyed by `ActiveVehicle.current`, as every per-vehicle writer is).
 */
internal object LookMotion {

    private const val PID_SPEED = "010D"

    suspend fun read(context: Context): LookRules.Motion {
        val fix = LocationController.state.value
        val now = System.currentTimeMillis()
        val gpsSpeed = fix?.takeIf { it.hasSpeed() }?.speed
        val gpsAge = fix?.let { now - it.time }
        val sample = if (ObdBluetoothManager.isConnected) {
            withContext(Dispatchers.IO) {
                runCatching {
                    CarDatabase.getDatabase(context).odbSampleDao()
                        .getLatest(ActiveVehicle.current(context), PID_SPEED, 1).firstOrNull()
                }.getOrNull()
            }
        } else {
            null
        }
        return LookRules.motion(gpsSpeed, gpsAge, sample?.value, sample?.let { now - it.timestamp })
    }
}
