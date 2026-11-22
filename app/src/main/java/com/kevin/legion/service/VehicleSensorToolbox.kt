package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.service.LiveToolbox.result
import com.kevin.legion.service.LiveToolbox.withResolvedVehicle
import com.kevin.legion.util.Temp
import com.kevin.legion.vehicle.ActiveVehicle
import com.kevin.legion.vehicle.ObdBluetoothManager
import com.kevin.legion.vehicle.PID_REGISTRY
import com.kevin.legion.vehicle.VehicleController
import com.kevin.legion.vehicle.capabilitiesFor
import com.kevin.legion.vehicle.matchPid
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/**
 * The live car reads (`get_vehicle_data`, `read_vehicle_sensor`), split out of [LiveToolbox]
 * (shrink pass, 2026-11-22) along the [EngineToolbox.dispatch] pattern: [LiveToolbox.dispatch]
 * runs its guards first (the connected-car check for `get_vehicle_data` included), then asks this
 * object, which answers the names it owns and returns null for every other.
 */
object VehicleSensorToolbox {

    /** Null when [name] is not one of these tools, so [LiveToolbox.dispatch] can fall through. */
    suspend fun dispatch(context: Context, name: String, args: JSONObject): JSONObject? = when (name) {
        "get_vehicle_data" -> getVehicleData(context, args.optString("metric"))
        "read_vehicle_sensor" -> readVehicleSensor(context, args)
        else -> null
    }

    /**
     * The PIDs `read_vehicle_sensor` answers from. [unread] is the connected car with no support
     * bitmask live or stored: its sensor list was never read, which is not "not profiled" (the
     * 2026-11-09 audit's 11-07 case, said while get_vehicle_data read the same car live).
     */
    internal data class SensorProfile(val pids: Set<Int>, val unread: Boolean)

    internal fun sensorProfile(connectedToThisCar: Boolean, live: Set<Int>, stored: Set<Int>): SensorProfile =
        when {
            connectedToThisCar && live.isNotEmpty() -> SensorProfile(live, unread = false)
            stored.isNotEmpty() -> SensorProfile(stored, unread = false)
            else -> SensorProfile(emptySet(), unread = connectedToThisCar)
        }

    /**
     * `read_vehicle_sensor` on the connected car whose sensor list was never read: a named sensor
     * is tried against the whole registry (a car that does not report it simply does not answer),
     * and a list request says the list is unread rather than that the car is unprofiled.
     */
    private suspend fun readUnprofiledSensor(vehicle: Vehicle, sensorArg: String): JSONObject {
        val label = VehicleController.label(vehicle)
        val matches = if (sensorArg.isBlank()) emptyList() else matchPid(sensorArg, PID_REGISTRY.filter { it.readable })
        return when {
            sensorArg.isBlank() -> result(
                false,
                "The adapter is connected to $label, but it has not reported which sensors the car " +
                    "supports, so there is no list to read yet. Live readings still work: coolant, RPM, " +
                    "speed, fuel, battery voltage, engine load and intake air come from get_vehicle_data.",
            )
            matches.isEmpty() -> result(false, "I don't know a sensor called \"$sensorArg\".")
            matches.size > 1 -> result(false, "Which one? " + matches.joinToString(", ") { it.description })
            else -> {
                val spec = matches.first()
                val value = ObdBluetoothManager.readPid(spec)
                if (value == null) {
                    result(false, "$label didn't answer for ${spec.label} just now; it may not report that sensor.")
                } else {
                    JSONObject()
                        .put("success", true)
                        .put("vehicle", label)
                        .put("sensor", spec.key)
                        .put("description", spec.description)
                        .put("value", (value * 100).roundToInt() / 100.0)
                        .put("unit", spec.unit)
                }
            }
        }
    }

    /**
     * Dispatches `read_vehicle_sensor`: list a car's capabilities, or read one sensor live.
     *
     * Deliberately NOT in [CATEGORY_A_TOOLS]. The capability half is a database read that must work
     * with no dongle present, and blanket-refusing the whole tool when nothing is connected would
     * hide the one answer that never needed hardware - the same mistake `get_code_history` was added
     * to fix. The live-read half checks the connection itself and says so in words.
     */
    private suspend fun readVehicleSensor(context: Context, args: JSONObject): JSONObject =
        withResolvedVehicle(context, args) { vehicle ->
            val storedPids = CarDatabase.getDatabase(context).vehicleCapabilityDao()
                .pidsForVehicle(vehicle.obdMac).toSet()
            // Prefer the LIVE bitmask when this is the connected car - it is fresher than the stored
            // profile and covers a first-ever connect where nothing has been persisted yet.
            val live = ObdBluetoothManager.supportedPids.value
            // Compared against ActiveVehicle.current for the same reason persistCapabilities uses it:
            // a hand-added car's id is synthetic, so comparing the dongle's MAC to vehicle.obdMac
            // would read as "not this car" for every vehicle Kevin added by hand.
            val connectedToThisCar = ActiveVehicle.connectedCarId(context) == vehicle.obdMac
            val profile = sensorProfile(connectedToThisCar, live, storedPids)
            val supported = profile.pids

            if (profile.unread) {
                // 2026-11-09 audit (11-07 10:13): "I haven't profiled the Jeep" while get_vehicle_data
                // read live coolant from it. Connected with no support bitmask in hand means the
                // list was never read, not that the car reports nothing: try the PID itself.
                return@withResolvedVehicle readUnprofiledSensor(vehicle, args.optString("sensor").trim())
            }

            if (supported.isEmpty()) {
                return@withResolvedVehicle result(
                    false,
                    // Ticket 04's label rule: the one rule, every surface - see
                    // VehicleController.label's own doc. This whole function used raw Vehicle.name
                    // throughout (the ticket's "capability-probe replies" surface).
                    "I haven't profiled ${VehicleController.label(vehicle)} yet - plug the adapter into it once and I'll " +
                        "record what it can report.",
                )
            }

            val caps = capabilitiesFor(supported)
            val sensorArg = args.optString("sensor").trim()

            // No sensor named: report the capability profile. Works parked and unplugged.
            if (sensorArg.isBlank()) {
                val byGroup = JSONObject()
                for ((group, specs) in caps.readable.groupBy { it.group }) {
                    byGroup.put(group.name.lowercase(), JSONArray(specs.map { it.key }))
                }
                return@withResolvedVehicle JSONObject()
                    .put("success", true)
                    .put("vehicle", VehicleController.label(vehicle))
                    .put("readable_count", caps.readable.size)
                    .put("readable_by_group", byGroup)
                    // Surfaced, never hidden: "supported but we don't decode it yet" is a real and
                    // useful state, and reporting zero of them would overstate our coverage.
                    .put("supported_but_not_yet_decoded", JSONArray(caps.undecodedPids.map { it.key }))
                    .put("unrecognised_pid_count", caps.unknownPids.size)
                    .put("profile_source", if (connectedToThisCar) "live" else "stored from a previous connection")
            }

            val matches = matchPid(sensorArg, caps.readable)
            when {
                matches.isEmpty() -> {
                    // Distinguish "this car can't" from "I don't know that word" - they need
                    // different answers and merging them would misinform.
                    val elsewhere = matchPid(sensorArg, PID_REGISTRY.filter { it.readable })
                    if (elsewhere.isNotEmpty()) {
                        result(false, "${VehicleController.label(vehicle)} doesn't report ${elsewhere.first().description.lowercase()} - " +
                            "it's not in the set of sensors that car answers.")
                    } else {
                        result(false, "I don't know a sensor called \"$sensorArg\".")
                    }
                }
                matches.size > 1 -> result(
                    false,
                    "Which one? " + matches.joinToString(", ") { it.description },
                )
                else -> {
                    val spec = matches.first()
                    if (!connectedToThisCar) {
                        return@withResolvedVehicle result(
                            false,
                            "${VehicleController.label(vehicle)} does report ${spec.description.lowercase()}, but I need the " +
                                "adapter plugged into it to read the current value.",
                        )
                    }
                    val value = ObdBluetoothManager.readPid(spec)
                        ?: return@withResolvedVehicle result(
                            false, "${VehicleController.label(vehicle)} didn't answer for ${spec.label} just now.",
                        )
                    JSONObject()
                        .put("success", true)
                        .put("vehicle", VehicleController.label(vehicle))
                        .put("sensor", spec.key)
                        .put("description", spec.description)
                        .put("value", (value * 100).roundToInt() / 100.0)
                        .put("unit", spec.unit)
                }
            }
        }

    private suspend fun getVehicleData(context: Context, metric: String): JSONObject {
        if (!ObdBluetoothManager.isConnected) {
            return JSONObject().put("connected", false)
                .put("note", "OBD adapter not connected; tell the user to check it's plugged in.")
        }
        // "This car does not report fuel level" and "the read failed" are
        // different answers and the driver deserves the right one. supportedPids
        // comes off the car's own Mode-01 support bitmask at connect, so when it
        // is populated and lacks the PID, the absence is a FACT about the car
        // rather than a failure - most notably fuel level (0x2F), which a large
        // share of vehicles simply do not publish over OBD-II. An empty set means
        // the bitmask never came back, so nothing can be concluded and the read
        // is attempted anyway.
        fun unsupported(pid: Int): Boolean {
            val supported = ObdBluetoothManager.supportedPids.value
            return supported.isNotEmpty() && pid !in supported
        }

        fun reading(pid: Int?, value: Any?, extras: JSONObject.() -> Unit = {}): JSONObject =
            JSONObject().put("connected", true).apply {
                when {
                    pid != null && unsupported(pid) -> put("available", false)
                        .put("supportedByCar", false)
                        .put("note", "This car does not report that over OBD-II. Say so plainly - " +
                            "it is not a fault and not a failed read.")
                    value == null -> put("available", false)
                        .put("note", "The read came back empty. Do not report a value.")
                    else -> put("available", true).apply(extras)
                }
            }

        return when (metric) {
            "fuel_level" -> {
                val pct = ObdBluetoothManager.getFuelLevel()
                reading(0x2F, pct) { put("fuelLevelPercent", "%.0f".format(pct)) }
            }
            "coolant_temp" -> {
                val c = ObdBluetoothManager.getCoolantTemp()
                val unit = Temp.unit(context)
                reading(0x05, c) { put("temperature", Temp.convert(c!!.toDouble(), unit)).put("temperatureUnit", unit.symbol) }
            }
            "rpm" -> {
                val rpm = ObdBluetoothManager.getRpm()
                reading(0x0C, rpm) { put("rpm", rpm) }
            }
            "speed" -> {
                val kmh = ObdBluetoothManager.getSpeedKmh()
                reading(0x0D, kmh) {
                    put("kmh", kmh).put("mph", (kmh!! * 0.621371).roundToInt())
                }
            }
            "battery_voltage" -> {
                // ATRV is answered by the adapter itself, not the ECU, so there is
                // no PID to check support for - it works with the engine off.
                val v = ObdBluetoothManager.getBatteryVoltage()
                reading(null, v) { put("volts", "%.1f".format(v)) }
            }
            "engine_load" -> {
                val load = ObdBluetoothManager.getEngineLoad()
                reading(0x04, load) { put("loadPercent", "%.0f".format(load)) }
            }
            "intake_air_temp" -> {
                val iat = ObdBluetoothManager.getIntakeAirTemp()
                val unit = Temp.unit(context)
                reading(0x0F, iat) { put("temperature", Temp.convert(iat!!.toDouble(), unit)).put("temperatureUnit", unit.symbol) }
            }
            "maf" -> {
                val maf = ObdBluetoothManager.getMaf()
                reading(0x10, maf) { put("gramsPerSecond", "%.1f".format(maf)) }
            }
            "short_fuel_trim" -> {
                val t = ObdBluetoothManager.getShortFuelTrim()
                reading(0x06, t) { put("percent", "%+.1f".format(t)) }
            }
            "long_fuel_trim" -> {
                val t = ObdBluetoothManager.getLongFuelTrim()
                reading(0x07, t) { put("percent", "%+.1f".format(t)) }
            }
            "trouble_codes" -> {
                val codes = ObdBluetoothManager.getDtcCodes()
                JSONObject().put("connected", true)
                    .put("codes", JSONArray(codes))
                    .put("count", codes.size)
            }
            else -> result(success = false, message = "Unknown metric: $metric")
        }
    }
}
