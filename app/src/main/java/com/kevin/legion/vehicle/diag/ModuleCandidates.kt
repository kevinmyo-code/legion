package com.kevin.legion.vehicle.diag

/** A module address to probe. [responseId] is where its answers come from. */
data class Candidate(val requestId: Long, val responseId: Long) {
    val address: String get() = Hex.id(requestId)
}

/** A module name a make's table gives an address, with where that came from. */
data class KnownModule(val requestId: Long, val name: String, val restraint: Boolean = false)

/**
 * Which addresses a scan probes, and the names a make's table gives some of them.
 *
 * **Names are labels, never permission.** A name here comes from public community lists or a
 * published bulletin, not from the car; [ModuleScanner] says so in each module's `nameSource`. The
 * one place a name matters is clearing: a module may be cleared only when it is positively named
 * and not a restraint module ([ModuleClearer]). Every address not in a table stays unidentified,
 * so an airbag module at an address nobody listed is never cleared.
 */
object ModuleCandidates {
    private const val REPLY_OFFSET_11 = 0x8L
    private const val OBD_FIRST = 0x7E0L
    private const val OBD_LAST = 0x7E7L
    private const val SWEEP_FIRST = 0x700L
    private const val SWEEP_LAST = 0x7F7L
    private const val FUNCTIONAL_11 = 0x7DFL
    private const val PRIO_29 = 0x18L shl 24
    private const val PHYS_29 = 0xDAL shl 16
    private const val TESTER_29 = 0xF1L
    private const val BYTE_BITS = 8
    private const val LAST_29_TARGET = 0xFEL

    const val SOURCE_FORD =
        "Community naming (FORScan as-built ids <addr>-<block>-<line>, forum.forscan.org/viewtopic.php?p=13410) " +
            "and NHTSA bulletin MC-10251477 (PCM 7E0, ABS 760); not verified on this car"
    const val SOURCE_MITSUBISHI =
        "Community Torque PID table using header 7E1 for the CVT " +
            "(torque-bhp.com/community/main-forum/07-mitsubishi-outlander/); not verified on this car"
    const val SOURCE_STANDARD = "ISO 15765-4 reserves 7E0-7E7 for emissions-related ECUs"

    /** Ford HS-CAN addresses as the community and Ford's own bulletin name them. */
    @Suppress("MagicNumber") // address table
    val FORD: List<KnownModule> = listOf(
        KnownModule(0x7E0, "PCM (engine and transmission)"),
        KnownModule(0x760, "ABS (brakes)"),
        KnownModule(0x737, "RCM (airbag / restraints)", restraint = true),
        KnownModule(0x765, "OCSM (occupant classification)", restraint = true),
        KnownModule(0x726, "BCM (body)"),
        KnownModule(0x720, "IPC (instrument cluster)"),
        KnownModule(0x7D0, "APIM (SYNC)"),
        KnownModule(0x733, "HVAC (climate control)"),
        KnownModule(0x730, "PSCM (power steering)"),
        KnownModule(0x727, "ACM (audio)"),
        KnownModule(0x716, "GWM (gateway)"),
        KnownModule(0x724, "SCCM (steering column)"),
    )

    /** Mitsubishi: only the two addresses a public source names. Everything else stays unidentified. */
    @Suppress("MagicNumber") // address table
    val MITSUBISHI: List<KnownModule> = listOf(
        KnownModule(0x7E0, "Engine ECU"),
        KnownModule(0x7E1, "CVT ECU (transmission)"),
    )

    /** Names for [make] (lower case, as decoded from the VIN or the car's record). */
    fun known(make: String?): List<KnownModule> = when (make?.lowercase()?.trim()) {
        "ford", "lincoln" -> FORD
        "mitsubishi" -> MITSUBISHI
        else -> emptyList()
    }

    fun sourceFor(make: String?): String? = when (make?.lowercase()?.trim()) {
        "ford", "lincoln" -> SOURCE_FORD
        "mitsubishi" -> SOURCE_MITSUBISHI
        else -> null
    }

    /** Extra addresses seen across makes; probing one costs a silent wait, nothing more. */
    @Suppress("MagicNumber") // address table
    private val COMMON_11: List<Long> = listOf(
        0x710, 0x714, 0x716, 0x720, 0x724, 0x726, 0x727, 0x730, 0x733, 0x736, 0x737, 0x740, 0x744,
        0x750, 0x754, 0x760, 0x764, 0x765, 0x770, 0x775, 0x7A0, 0x7C0, 0x7D0,
    )

    /** Common 29-bit target addresses (engine 10, transmission 18, ABS 28, body 40, restraint 58...). */
    @Suppress("MagicNumber") // address table
    private val COMMON_29: List<Long> = listOf(0x10, 0x11, 0x17, 0x18, 0x19, 0x1A, 0x28, 0x29, 0x40, 0x58, 0x60)

    fun candidate11(request: Long) = Candidate(request, request + REPLY_OFFSET_11)

    fun candidate29(target: Long) = Candidate(
        PRIO_29 or PHYS_29 or (target shl BYTE_BITS) or TESTER_29,
        PRIO_29 or PHYS_29 or (TESTER_29 shl BYTE_BITS) or target,
    )

    fun isFunctional(requestId: Long) = requestId == FUNCTIONAL_11

    /**
     * The quick list: the reserved OBD range, the make's named modules, then common addresses.
     * The full sweep: every 11-bit request id 700-7F7 (or every 29-bit target 00-FE).
     */
    fun plan(bus: BusKind, make: String?, full: Boolean): List<Candidate> = when (bus) {
        BusKind.CAN_11 -> {
            val ids = if (full) {
                (SWEEP_FIRST..SWEEP_LAST).filter { it != FUNCTIONAL_11 }
            } else {
                (OBD_FIRST..OBD_LAST) + known(make).map { it.requestId } + COMMON_11
            }
            ids.distinct().map(::candidate11)
        }
        BusKind.CAN_29 -> {
            val targets = if (full) (0L..LAST_29_TARGET).toList() else COMMON_29
            targets.distinct().map(::candidate29)
        }
        else -> emptyList()
    }

    fun rangesWords(bus: BusKind, make: String?, full: Boolean): String = when {
        bus == BusKind.CAN_11 && full -> "every 11-bit request id 700-7F7"
        bus == BusKind.CAN_11 -> "7E0-7E7, " + (if (known(make).isEmpty()) "" else "the ${make}'s named modules, ") +
            "and ${COMMON_11.size} common addresses"
        bus == BusKind.CAN_29 && full -> "every 29-bit target 00-FE"
        bus == BusKind.CAN_29 -> "${COMMON_29.size} common 29-bit targets"
        else -> "none (no module addressing on this bus)"
    }
}
