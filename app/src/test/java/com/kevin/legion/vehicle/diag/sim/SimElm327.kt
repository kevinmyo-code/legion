package com.kevin.legion.vehicle.diag.sim

import com.kevin.legion.vehicle.diag.BusKind

/**
 * Which adapter is in the car. [rejects] are AT/ST command prefixes the chip answers `?` to (cheap
 * clones drop whole groups, flow control most often). [autoFlowControlBroken] models a clone whose
 * automatic flow control goes to the wrong id, so a long reply is cut after its first frame unless
 * the scanner sets the flow-control header itself.
 */
data class Personality(
    val ati: String = "ELM327 v1.5",
    val sti: String? = null,
    val rejects: Set<String> = emptySet(),
    val autoFlowControlBroken: Boolean = false,
    /** After this many commands the adapter returns nothing at all (a dropped link); null = never. */
    val dieAfter: Int? = null,
)

/**
 * A simulated ELM327 (idea from xj-scanner's DEMO mode; written from the ELM327 datasheet, no code
 * copied). It answers AT commands from the datasheet's command set and plays a [SimCar] on the
 * bus. Its adapter state (echo, headers, spaces, receive filter, flow control, timeout) follows
 * the commands it was sent, not what the scanner expects, so a scanner that forgets a step fails.
 *
 * Two behaviours the datasheet does not specify are modelled conservatively and labelled:
 *  - auto-receive (no `AT CRA`) accepts only the OBD reply ids 7E8-7EF (29-bit: 18DAF1xx);
 *  - automatic flow control (`AT FC SM 0`) answers to the reply id minus 8 (29-bit: the swapped
 *    addresses), which is right for the usual +8 pairs.
 */
class SimElm327(var car: SimCar, val personality: Personality = Personality()) {
    var nowMs: Long = 0
    val log = mutableListOf<String>()

    internal var echo = true
    internal var linefeeds = true
    var headers = false
        internal set
    internal var spaces = true
    var cra: String? = null
        internal set
    var fcMode = 0
        internal set
    internal var fcHeader: Long? = null
    internal var fcData: List<Int>? = null
    var st = DEFAULT_ST
        internal set
    internal var priority = 0x18L
    internal var header: Long = FUNCTIONAL_11
    internal var detected: BusKind? = null

    private val bus = SimBus(this)

    /** One command line in, the reply out (without the trailing prompt). */
    fun handle(line: String): String {
        val cmd = line.trim().uppercase().replace(" ", "")
        log += cmd
        if (cmd.isEmpty() || personality.dieAfter?.let { log.size > it } == true) return ""
        val body = when {
            personality.rejects.any { cmd.startsWith(it) } -> "?"
            cmd.startsWith("AT") -> at(cmd.removePrefix("AT"))
            cmd == "STI" -> personality.sti ?: "?"
            cmd.startsWith("ST") -> "?"
            cmd.all { it.isDigit() || it in 'A'..'F' } && cmd.length % 2 == 0 ->
                bus.request(cmd.chunked(2).map { it.toInt(16) })
            else -> "?"
        }
        val eol = if (linefeeds) "\r\n" else "\r"
        val shown = (if (echo) line.trim() + eol else "") + body.replace("\r", eol)
        return shown
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun at(c: String): String = when {
        c == "Z" -> { reset(); "\r\r${personality.ati}" }
        c == "D" -> { defaults(); "OK" }
        c == "I" -> personality.ati
        c == "RV" -> "12.4V"
        c == "E0" || c == "E1" -> { echo = c == "E1"; "OK" }
        c == "L0" || c == "L1" -> { linefeeds = c == "L1"; "OK" }
        c == "H0" || c == "H1" -> { headers = c == "H1"; "OK" }
        c == "S0" || c == "S1" -> { spaces = c == "S1"; "OK" }
        c == "CAF0" || c == "CAF1" || c == "AT0" || c == "AT1" || c == "AT2" || c == "PC" -> "OK"
        c.startsWith("SP") -> { detected = null; "OK" }
        c == "DPN" -> dpn()
        c == "DP" -> detected?.words ?: "AUTO"
        c == "AR" || c == "CRA" -> { cra = null; "OK" }
        c.startsWith("CRA") -> { cra = c.removePrefix("CRA"); "OK" }
        c.startsWith("CP") -> { priority = c.removePrefix("CP").toLong(16); "OK" }
        c.startsWith("SH") -> setHeader(c.removePrefix("SH"))
        c.startsWith("FCSH") -> { fcHeader = c.removePrefix("FCSH").toLong(16); "OK" }
        c.startsWith("FCSD") -> { fcData = c.removePrefix("FCSD").chunked(2).map { it.toInt(16) }; "OK" }
        c.startsWith("FCSM") -> setFcMode(c.removePrefix("FCSM").toInt())
        c.startsWith("ST") -> { st = c.removePrefix("ST").toInt(16); "OK" }
        else -> "?"
    }

    private fun setHeader(hex: String): String = when (hex.length) {
        3 -> { header = hex.toLong(16); "OK" }
        6 -> { header = (priority shl 24) or hex.toLong(16); "OK" }
        8 -> { header = hex.toLong(16); "OK" }
        else -> "?"
    }

    private fun setFcMode(mode: Int): String = when {
        mode == 0 -> { fcMode = 0; "OK" }
        mode == 1 && fcHeader != null && fcData != null -> { fcMode = 1; "OK" }
        else -> "?"
    }

    private fun dpn(): String = when (detected) {
        null -> "A0"
        BusKind.J1850_PWM -> "A1"
        BusKind.J1850_VPW -> "A2"
        BusKind.ISO_9141 -> "A3"
        BusKind.KWP_KLINE -> "A5"
        BusKind.CAN_11 -> "A6"
        BusKind.CAN_29 -> "A7"
        else -> "A0"
    }

    private fun reset() {
        defaults()
        echo = true
        linefeeds = true
        detected = null
    }

    private fun defaults() {
        headers = false
        spaces = true
        cra = null
        fcMode = 0
        fcHeader = null
        fcData = null
        st = DEFAULT_ST
        priority = 0x18L
        header = if (detected == BusKind.CAN_29) FUNCTIONAL_29 else FUNCTIONAL_11
    }

    companion object {
        const val DEFAULT_ST = 0x32
        const val FUNCTIONAL_11 = 0x7DFL
        const val FUNCTIONAL_29 = 0x18DB33F1L
    }
}
