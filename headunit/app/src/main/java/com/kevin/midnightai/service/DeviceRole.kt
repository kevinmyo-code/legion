package com.kevin.midnightai.service

import android.content.Context
import com.kevin.midnightai.vehicle.ActiveVehicle

/**
 * What job THIS device does in the GPS beacon link, **per car** (CLAUDE.md §14).
 *
 * **Why this exists.** Touching the GPS antenna connector on the Cherokee's head
 * unit browns out its shared WiFi/BT rail - both radios drop, and they come back
 * the moment it is unplugged. The unit therefore has no usable GPS of its own, and
 * without a fix the drive monitor, weather, places/arrival, the odometer estimate
 * and every recap are all dead. A phone on the same hotspot supplies the fix
 * instead.
 *
 * One APK covers both ends - the app is already dual-target (see the manifest's
 * 2026-07-12 note) - so this is a role switch, not a second app to install.
 *
 * **Why it is keyed by car (changed 2026-07-25).** Kevin runs two cars, one dongle,
 * and one spare Android phone. In the Cherokee the phone is the beacon, because the
 * bolted-in head unit is the app host. In the Outlander that same phone IS the app
 * host, in a landscape cradle, using its own GPS - so it must NOT be a beacon there.
 * A single global role meant flipping this by hand on every car swap, and forgetting
 * it fails silently in the direction that matters: left on OFF in the Cherokee, the
 * head unit simply has no position and nothing says why.
 *
 * Note the role cannot be derived from the car alone - the Cherokee's head unit also
 * selects "Cherokee" and must be [Role.HEAD_UNIT] there, while the phone selecting
 * the same car must be [Role.GPS_BEACON]. It is a per-device answer to a per-car
 * question, which is why it is stored rather than computed. Same shape
 * `CompanionProfile` took when identity went per-car (§9, 2026-07-16).
 *
 * **This is per-device state and MUST NOT sync**, for exactly the reason spelled
 * out in [ActiveVehicle]: the phone and the head unit legitimately hold different
 * values for the same car, and that disagreement is the whole point. If it synced,
 * setting the phone to BEACON would flip the head unit to BEACON too and the link
 * would have two senders and no receiver.
 *
 * Defaults to [Role.OFF] so an install that never opens the setting behaves exactly
 * as it does today - no socket opened, no datagram sent on whatever network the
 * device happens to be joined to.
 */
object DeviceRole {
    private const val PREFS = "device_role"

    /**
     * Pre-2026-07-25 single global role, kept forever as the fallback for any car
     * with no per-car answer yet.
     *
     * **Deliberately not a one-shot migration.** The obvious version - copy this
     * onto the active car once, set a `migrated` flag - loses a race it cannot win.
     * The first thing to ask for a role on a cold boot is `BootReceiver`, then
     * `AriaForegroundService.onCreate`, both of which run before the OBD dongle has
     * finished its Bluetooth handshake. Until it does,
     * [ActiveVehicle.current] resolves to `DEFAULT_VEHICLE_ID`, so a one-shot
     * migration would deterministically stamp the old role onto the wrong car, burn
     * its flag, and leave the real car reading OFF - silently killing the beacon on
     * exactly the upgrade the migration existed to protect. As a standing fallback
     * instead, a wrong guess is impossible: there is no guess.
     */
    private const val KEY_LEGACY_ROLE = "role"
    private const val KEY_PREFIX = "role_"

    enum class Role {
        /** No beacon link. Location comes from this device's own GPS, or not at all. */
        OFF,

        /** Receives fixes from a phone beacon over the hotspot. The car's head unit. */
        HEAD_UNIT,

        /** Sends this device's GPS to the head unit. The phone riding along. */
        GPS_BEACON,
    }

    /** The role for whichever car this device is currently in. */
    fun current(context: Context): Role = forVehicle(context, ActiveVehicle.current(context))

    fun forVehicle(context: Context, vehicleId: String): Role {
        val prefs = prefs(context)
        val raw = prefs.getString(KEY_PREFIX + vehicleId, null)
            ?: prefs.getString(KEY_LEGACY_ROLE, null)
        return Role.entries.firstOrNull { it.name == raw } ?: Role.OFF
    }

    /** Sets the role for the car this device is currently in. */
    fun set(context: Context, role: Role) = setForVehicle(context, ActiveVehicle.current(context), role)

    fun setForVehicle(context: Context, vehicleId: String, role: Role) {
        // The legacy key is also cleared on the first explicit choice. Leaving it
        // would keep answering for every OTHER car - so a driver who sets the Oppo
        // to PHONE for the Cherokee would find it still claiming PHONE in the
        // Outlander, which is the exact confusion per-car roles exist to end.
        prefs(context).edit()
            .putString(KEY_PREFIX + vehicleId, role.name)
            .remove(KEY_LEGACY_ROLE)
            .apply()
    }

    /**
     * Starts and stops the beacon ends to match the current car's role.
     *
     * The single place that turns a stored role into running sockets, called both
     * from the Setup screen and from [ActiveVehicle.notifyResolutionChanged] when
     * the driver switches cars. Always tears both ends down first: the roles are
     * mutually exclusive (the beacon binds a fixed port, the head unit talks to it),
     * and a device left running both would answer its own HELLOs.
     */
    @Synchronized
    fun applyCurrent(context: Context) {
        val appContext = context.applicationContext
        val role = current(appContext)

        // Idempotent, and that is load-bearing rather than tidy.
        // ActiveVehicle.notifyResolutionChanged fires on every dongle
        // connect/disconnect EDGE, not only when the resolved car actually changes -
        // and a cheap ELM327 drops on a single failed AT command mid-drive. Without
        // this guard every blip would tear the GPS beacon link down and rebuild it,
        // losing fixes for no reason, on a driver who never switched cars at all.
        if (role == lastAppliedRole) return
        lastAppliedRole = role

        com.kevin.midnightai.location.BeaconClient.stop()
        BeaconService.stop(appContext)
        when (role) {
            Role.OFF -> Unit
            Role.HEAD_UNIT -> com.kevin.midnightai.location.BeaconClient.start(appContext)
            Role.GPS_BEACON ->
                // Skipped without the grant rather than started-and-crashed: the
                // service declares foregroundServiceType="location", which Android 14
                // refuses to run without it. See BeaconService.onCreate.
                if (com.kevin.midnightai.location.LocationController.hasPermission(appContext)) {
                    BeaconService.start(appContext)
                }
        }
    }

    /**
     * The role [applyCurrent] last actually acted on. Null means "never applied", so
     * the first call always runs even when the stored role is OFF - the sockets may
     * be up from a previous role and still need stopping.
     */
    @Volatile private var lastAppliedRole: Role? = null

    /** Forces the next [applyCurrent] to act, for a caller that changed the stored role. */
    @Synchronized
    fun invalidateApplied() {
        lastAppliedRole = null
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
