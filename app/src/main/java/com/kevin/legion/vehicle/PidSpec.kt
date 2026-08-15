package com.kevin.legion.vehicle

/**
 * The standard Mode-01 PID table, as DATA rather than as one hand-written function per reading.
 *
 * **This is the answer to "how does LEGION read a car it has never seen".** Adding a PID is a row
 * here, not a new `getWhatever()` on [ObdBluetoothManager]; adding a CAR is nothing at all. Every
 * OBD-II vehicle publishes a bitmask of the PIDs it answers (Mode 01 PIDs 0x00/0x20/0x40/0x60), so
 * the car itself tells us its capabilities and we intersect that with this table. A 2017 F-150 and
 * a 1998 XJ run the same code path and simply resolve to different sets.
 *
 * That is why there is no per-vehicle branching anywhere in this file, and why there should never
 * be. The moment a reading needs `if (vehicle is F150)` it is manufacturer-specific and does not
 * belong in the standard registry at all.
 *
 * **[decode] may be null on purpose.** Several PIDs in the 0x61-0x7F turbo/emissions range are
 * composite: a leading support byte followed by several conditionally-present fields, with layouts
 * that vary in ways worth confirming against a real response rather than guessing from memory. A
 * null [decode] means "we know this PID's number and name, and we can report that the car SUPPORTS
 * it, but we do not claim to read it yet". Reporting a supported-but-undecoded PID honestly is
 * useful (it tells us what is worth implementing against real hardware); inventing a formula for it
 * and rendering a confident wrong number is the failure this codebase keeps having to undo.
 *
 * Values are SI - Celsius, kPa, km/h, litres - matching the existing decoders in
 * [ObdResponseParser]. Display conversion stays in the UI layer, as `ObdGauge` already does.
 */
data class PidSpec(
    /** Mode-01 PID number, e.g. 0x05 for coolant temperature. */
    val pid: Int,
    /** Stable machine key, used as [com.kevin.legion.data.local.OdbSample.pid]'s value and in tools. */
    val key: String,
    /** Short human label for a gauge caption. */
    val label: String,
    /** Longer description for the voice layer and the capability report. */
    val description: String,
    /** SI unit label, or "" for dimensionless/enumerated values. */
    val unit: String,
    /** How many data bytes the response carries after the `41 XX` header. */
    val bytes: Int,
    /** Decodes the data bytes to a value, or null if this PID is discoverable but not yet decoded. */
    val decode: ((List<Int>) -> Double?)?,
    /** Grouping for the capability report and gauge picker. */
    val group: PidGroup,
    /**
     * True for values that change many times a second (RPM, MAF, throttle). Kept as a property
     * rather than a hardcoded exclusion list so a caller can choose a cadence per PID - see
     * `ObdGauges.kt` for the head-unit-era blanket ban this replaces.
     */
    val fast: Boolean = false,
) {
    /** True when this PID has a real decoder, as opposed to being merely discoverable. */
    val readable: Boolean get() = decode != null

    /** The Mode-01 request string for this PID, e.g. "0105". */
    val command: String get() = "01%02X".format(pid)

    /** The response header this PID's reply carries, e.g. "41 05". */
    val responsePrefix: String get() = "41 %02X".format(pid)
}

enum class PidGroup { CORE, FUEL, TEMPERATURE, AIR, EMISSIONS, ELECTRICAL, TURBO, DIAGNOSTIC, TORQUE }

// Byte helpers. `a`/`b` name the first two data bytes the way every SAE J1979 formula does, so a
// formula here reads the same as the spec it came from.
private fun a(d: List<Int>) = d[0].toDouble()
private fun ab(d: List<Int>) = (d[0] * 256 + d[1]).toDouble()
private fun pct255(d: List<Int>) = a(d) * 100.0 / 255.0
private fun tempC(d: List<Int>) = a(d) - 40.0
private fun trimPct(d: List<Int>) = (a(d) - 128.0) * 100.0 / 128.0

/**
 * Every standard Mode-01 PID LEGION knows about.
 *
 * Ordered by PID number. Sourced from SAE J1979's standard table - deliberately NOT extended with
 * manufacturer-specific PIDs, which belong behind their own seam (see the file doc comment).
 */
val PID_REGISTRY: List<PidSpec> = listOf(
    // --- core engine ---
    PidSpec(0x04, "engine_load", "LOAD", "Calculated engine load", "%", 1, ::pct255, PidGroup.CORE, fast = true),
    PidSpec(0x05, "coolant_temp", "TEMP", "Engine coolant temperature", "°C", 1, ::tempC, PidGroup.TEMPERATURE),
    PidSpec(0x06, "stft_b1", "STFT", "Short term fuel trim, bank 1", "%", 1, ::trimPct, PidGroup.FUEL, fast = true),
    PidSpec(0x07, "ltft_b1", "LTFT", "Long term fuel trim, bank 1", "%", 1, ::trimPct, PidGroup.FUEL),
    PidSpec(0x08, "stft_b2", "STFT2", "Short term fuel trim, bank 2", "%", 1, ::trimPct, PidGroup.FUEL, fast = true),
    PidSpec(0x09, "ltft_b2", "LTFT2", "Long term fuel trim, bank 2", "%", 1, ::trimPct, PidGroup.FUEL),
    PidSpec(0x0A, "fuel_pressure", "FPRES", "Fuel pressure (gauge)", "kPa", 1, { a(it) * 3.0 }, PidGroup.FUEL),
    PidSpec(0x0B, "map", "MAP", "Intake manifold absolute pressure - on a turbo engine this is boost", "kPa", 1, ::a, PidGroup.AIR, fast = true),
    PidSpec(0x0C, "rpm", "RPM", "Engine speed", "rpm", 2, { ab(it) / 4.0 }, PidGroup.CORE, fast = true),
    PidSpec(0x0D, "speed", "SPEED", "Vehicle speed", "km/h", 1, ::a, PidGroup.CORE, fast = true),
    PidSpec(0x0E, "timing_advance", "TIMING", "Ignition timing advance", "°", 1, { a(it) / 2.0 - 64.0 }, PidGroup.CORE, fast = true),
    PidSpec(0x0F, "intake_air_temp", "IAT", "Intake air temperature", "°C", 1, ::tempC, PidGroup.TEMPERATURE),
    PidSpec(0x10, "maf", "MAF", "Mass air flow rate", "g/s", 2, { ab(it) / 100.0 }, PidGroup.AIR, fast = true),
    PidSpec(0x11, "throttle", "THROT", "Throttle position", "%", 1, ::pct255, PidGroup.CORE, fast = true),

    // --- run time and distance ---
    PidSpec(0x1F, "run_time", "RUNTIME", "Engine run time since start", "s", 2, ::ab, PidGroup.DIAGNOSTIC),
    PidSpec(0x21, "distance_mil_on", "MIL DIST", "Distance travelled with the check-engine light on", "km", 2, ::ab, PidGroup.DIAGNOSTIC),
    PidSpec(0x22, "fuel_rail_pressure_vac", "FRP VAC", "Fuel rail pressure relative to manifold vacuum", "kPa", 2, { ab(it) * 0.079 }, PidGroup.FUEL),
    PidSpec(0x23, "fuel_rail_gauge_pressure", "FRP", "Fuel rail gauge pressure - direct injection health", "kPa", 2, { ab(it) * 10.0 }, PidGroup.FUEL),

    // --- EGR, evap, fuel level ---
    PidSpec(0x2C, "egr_commanded", "EGR", "Commanded EGR", "%", 1, ::pct255, PidGroup.EMISSIONS),
    PidSpec(0x2D, "egr_error", "EGR ERR", "EGR error", "%", 1, ::trimPct, PidGroup.EMISSIONS),
    PidSpec(0x2E, "evap_purge", "PURGE", "Commanded evaporative purge", "%", 1, ::pct255, PidGroup.EMISSIONS),
    PidSpec(0x2F, "fuel_level", "FUEL", "Fuel tank level", "%", 1, ::pct255, PidGroup.FUEL),
    PidSpec(0x30, "warmups_since_cleared", "WARMUPS", "Warm-ups since codes cleared", "", 1, ::a, PidGroup.DIAGNOSTIC),
    PidSpec(0x31, "distance_since_cleared", "DIST CLR", "Distance travelled since codes cleared", "km", 2, ::ab, PidGroup.DIAGNOSTIC),
    PidSpec(0x33, "barometric_pressure", "BARO", "Absolute barometric pressure", "kPa", 1, ::a, PidGroup.AIR),

    // --- catalyst ---
    PidSpec(0x3C, "cat_temp_b1s1", "CAT11", "Catalyst temperature, bank 1 sensor 1", "°C", 2, { ab(it) / 10.0 - 40.0 }, PidGroup.TEMPERATURE),
    PidSpec(0x3D, "cat_temp_b2s1", "CAT21", "Catalyst temperature, bank 2 sensor 1", "°C", 2, { ab(it) / 10.0 - 40.0 }, PidGroup.TEMPERATURE),

    // --- electrical and load ---
    PidSpec(0x42, "control_module_voltage", "ECU V", "Control module voltage - the ECU's own reading, not the adapter's", "V", 2, { ab(it) / 1000.0 }, PidGroup.ELECTRICAL),
    PidSpec(0x43, "absolute_load", "ABS LOAD", "Absolute load value", "%", 2, { ab(it) * 100.0 / 255.0 }, PidGroup.CORE, fast = true),
    PidSpec(0x44, "equivalence_ratio", "LAMBDA", "Commanded air-fuel equivalence ratio", "", 2, { ab(it) / 32768.0 }, PidGroup.FUEL, fast = true),
    PidSpec(0x45, "throttle_relative", "RTHROT", "Relative throttle position", "%", 1, ::pct255, PidGroup.CORE, fast = true),
    PidSpec(0x46, "ambient_air_temp", "AMBIENT", "Ambient air temperature, from the vehicle", "°C", 1, ::tempC, PidGroup.TEMPERATURE),
    PidSpec(0x47, "throttle_abs_b", "THROT B", "Absolute throttle position B", "%", 1, ::pct255, PidGroup.CORE),
    PidSpec(0x48, "throttle_abs_c", "THROT C", "Absolute throttle position C", "%", 1, ::pct255, PidGroup.CORE),
    PidSpec(0x49, "pedal_d", "PEDAL D", "Accelerator pedal position D - what the driver asked for", "%", 1, ::pct255, PidGroup.CORE, fast = true),
    PidSpec(0x4A, "pedal_e", "PEDAL E", "Accelerator pedal position E", "%", 1, ::pct255, PidGroup.CORE, fast = true),
    PidSpec(0x4B, "pedal_f", "PEDAL F", "Accelerator pedal position F", "%", 1, ::pct255, PidGroup.CORE, fast = true),
    PidSpec(0x4C, "throttle_commanded", "CMD THR", "Commanded throttle actuator", "%", 1, ::pct255, PidGroup.CORE, fast = true),
    PidSpec(0x4D, "time_mil_on", "MIL TIME", "Time run with the check-engine light on", "min", 2, ::ab, PidGroup.DIAGNOSTIC),
    PidSpec(0x4E, "time_since_cleared", "TIME CLR", "Time since codes cleared", "min", 2, ::ab, PidGroup.DIAGNOSTIC),

    // --- fuel composition, oil, consumption ---
    PidSpec(0x51, "fuel_type", "FUELTYPE", "Fuel type code", "", 1, ::a, PidGroup.FUEL),
    PidSpec(0x52, "ethanol_pct", "ETHANOL", "Ethanol fuel percentage", "%", 1, ::pct255, PidGroup.FUEL),
    PidSpec(0x5A, "pedal_relative", "RPEDAL", "Relative accelerator pedal position", "%", 1, ::pct255, PidGroup.CORE, fast = true),
    PidSpec(0x5B, "hybrid_battery_life", "HV BATT", "Hybrid/EV battery pack remaining life", "%", 1, ::pct255, PidGroup.ELECTRICAL),
    PidSpec(0x5C, "oil_temp", "OIL", "Engine oil temperature", "°C", 1, ::tempC, PidGroup.TEMPERATURE),
    PidSpec(0x5D, "injection_timing", "INJ TIME", "Fuel injection timing", "°", 2, { ab(it) / 128.0 - 210.0 }, PidGroup.FUEL),
    PidSpec(0x5E, "fuel_rate", "FUELRATE", "Engine fuel rate - the basis for true live economy", "L/h", 2, { ab(it) / 20.0 }, PidGroup.FUEL, fast = true),

    // --- torque ---
    PidSpec(0x61, "torque_demanded", "TQ DEM", "Driver's demanded engine torque", "%", 1, { a(it) - 125.0 }, PidGroup.TORQUE, fast = true),
    PidSpec(0x62, "torque_actual", "TQ ACT", "Actual engine torque", "%", 1, { a(it) - 125.0 }, PidGroup.TORQUE, fast = true),
    PidSpec(0x63, "torque_reference", "TQ REF", "Engine reference torque", "Nm", 2, ::ab, PidGroup.TORQUE),

    // --- turbo / charge air. Composite layouts: discoverable, not yet decoded (see file doc). ---
    PidSpec(0x70, "boost_control", "BOOST", "Boost pressure control", "kPa", 9, null, PidGroup.TURBO),
    PidSpec(0x71, "vgt_control", "VGT", "Variable geometry turbo control", "%", 6, null, PidGroup.TURBO),
    PidSpec(0x72, "wastegate_control", "WGATE", "Wastegate control", "%", 5, null, PidGroup.TURBO),
    PidSpec(0x73, "exhaust_pressure", "EXH P", "Exhaust pressure", "kPa", 5, null, PidGroup.TURBO),
    PidSpec(0x74, "turbo_rpm", "TURBO", "Turbocharger RPM", "rpm", 5, null, PidGroup.TURBO),
    PidSpec(0x75, "turbo_temp_a", "TURBO TA", "Turbocharger temperature A", "°C", 7, null, PidGroup.TURBO),
    PidSpec(0x76, "turbo_temp_b", "TURBO TB", "Turbocharger temperature B", "°C", 7, null, PidGroup.TURBO),
    PidSpec(0x77, "charge_air_cooler_temp", "CACT", "Charge air cooler temperature - intercooler heat soak", "°C", 5, null, PidGroup.TURBO),
    PidSpec(0x78, "egt_b1", "EGT1", "Exhaust gas temperature, bank 1", "°C", 9, null, PidGroup.TURBO),
    PidSpec(0x79, "egt_b2", "EGT2", "Exhaust gas temperature, bank 2", "°C", 9, null, PidGroup.TURBO),
    PidSpec(0x7C, "dpf_temp", "DPF T", "Diesel particulate filter temperature", "°C", 9, null, PidGroup.EMISSIONS),
)

private val BY_PID: Map<Int, PidSpec> = PID_REGISTRY.associateBy { it.pid }
private val BY_KEY: Map<String, PidSpec> = PID_REGISTRY.associateBy { it.key }

fun pidSpec(pid: Int): PidSpec? = BY_PID[pid]

fun pidSpecByKey(key: String): PidSpec? = BY_KEY[key.trim().lowercase()]

/**
 * The support-bitmask probe commands, in order. **`0160` matters and was missing until 2026-08-12**:
 * without it every PID from 0x61 to 0x80 is invisible, which is the entire turbo and torque range -
 * the app could not even report whether a truck answered them, because it never asked.
 *
 * Each response's base is the PID number it reports FROM, so `0100` describes 0x01-0x20 and so on.
 * Probing stops early when a window reports that the next window is unsupported, which every
 * conforming ECU signals via the top bit of its own reply.
 */
val SUPPORT_PROBES: List<Pair<String, Int>> = listOf(
    "0100" to 0x00,
    "0120" to 0x20,
    "0140" to 0x40,
    "0160" to 0x60,
)

/**
 * What one vehicle can actually do: the intersection of what it reports supporting and what this
 * registry knows how to name.
 *
 * [undecodedPids] is deliberately surfaced rather than hidden. "Your truck answers 4 PIDs we don't
 * decode yet" is an actionable fact that tells us what to implement against real hardware; silently
 * dropping them would make the app's coverage look complete when it is not.
 */
data class VehicleCapabilities(
    val supportedPids: Set<Int>,
    /** Supported AND decodable here - what can actually be read and stored today. */
    val readable: List<PidSpec>,
    /** Supported and named, but with no decoder yet - see [PidSpec.decode]. */
    val undecodedPids: List<PidSpec>,
    /** Supported by the car but absent from this registry entirely (manufacturer-specific, or new). */
    val unknownPids: List<Int>,
)

/** Intersects a car's reported [supported] set with [PID_REGISTRY]. Pure - no hardware, no Context. */
fun capabilitiesFor(supported: Set<Int>): VehicleCapabilities {
    val known = supported.mapNotNull { pidSpec(it) }
    return VehicleCapabilities(
        supportedPids = supported,
        readable = known.filter { it.readable }.sortedBy { it.pid },
        undecodedPids = known.filter { !it.readable }.sortedBy { it.pid },
        // The bitmask windows themselves (0x00/0x20/0x40/0x60) are "supported" by definition and
        // are not readings - excluding them keeps the unknown list to genuine mysteries.
        unknownPids = supported
            .filter { pidSpec(it) == null && it !in setOf(0x00, 0x20, 0x40, 0x60) }
            .sorted(),
    )
}

/**
 * Matches a spoken sensor name against [PID_REGISTRY] - "oil temp", "boost", "how much fuel".
 *
 * Three tiers, most confident first, mirroring `notes/NotesLogic.matchItem`'s shape and its
 * refuse-rather-than-guess posture: an ambiguous name comes back as multiple candidates for the
 * caller to ask about, never as a silent pick. Reading out the wrong sensor's number under the
 * right sensor's name is worse than admitting the ambiguity.
 *
 * [candidates] should be the vehicle's READABLE specs, not the whole registry, so a car is never
 * offered a sensor it cannot answer.
 */
private val NON_WORD = Regex("[^A-Za-z0-9]+")

fun matchPid(query: String, candidates: List<PidSpec>): List<PidSpec> {
    val q = query.trim().lowercase()
    if (q.isBlank() || candidates.isEmpty()) return emptyList()

    val exact = candidates.filter { it.key == q || it.label.lowercase() == q }
    if (exact.isNotEmpty()) return exact

    // Key/label substring, either direction - "oil" finds "oil_temp", "oil temperature" finds it too.
    val normalized = q.replace(' ', '_')
    val substring = candidates.filter {
        it.key.contains(normalized) || normalized.contains(it.key) || it.label.lowercase().contains(q)
    }
    if (substring.isNotEmpty()) return substring

    // Last resort: content words against the description, so "intercooler" reaches CACT and
    // "check engine light" reaches the MIL distance/time PIDs.
    val words = q.split(NON_WORD).filter { it.length > 2 }.toSet()
    if (words.isEmpty()) return emptyList()
    val scored = candidates
        .map { it to (it.description.lowercase().split(NON_WORD).toSet() intersect words).size }
        .filter { it.second > 0 }
    if (scored.isEmpty()) return emptyList()
    val best = scored.maxOf { it.second }
    return scored.filter { it.second == best }.map { it.first }
}
