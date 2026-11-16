package com.kevin.legion.vehicle.diag

/**
 * Adapter identity and the AT commands a CAN module scan needs, set explicitly and checked.
 *
 * The ELM327 datasheet documents `AT CRA` (receive filter) and `AT FC SH/SD/SM` (flow control), but
 * not which id automatic flow control (mode 0) answers with, nor which ids auto-receive accepts for
 * a non-OBD header. So the scanner never relies on either: it sets the receive filter and, where the
 * adapter accepts it, the flow-control header for every module. A clone that answers `?` to the
 * flow-control commands is noted and falls back to the adapter's automatic flow control.
 */
class ElmSetup(private val link: DiagLink, private val bus: BusKind) {
    /** False when the adapter refused the user flow-control commands (some clones). */
    var manualFlowControl: Boolean = false
        private set

    /** True once an AT command got nothing at all back: the link, not the adapter, failed. */
    var linkSilent: Boolean = false
        private set

    private var stretchedTimeout = false

    /** Headers on, spaces on, CAN auto-formatting on; then the flow-control data. False = cannot scan. */
    suspend fun begin(): Boolean {
        val required = listOf("ATH1", "ATS1", "ATCAF1").all { ok(it) }
        if (!required) return false
        manualFlowControl = ok("ATFCSD 30 00 00")
        return true
    }

    /** Points the adapter at [c]: transmit header, receive filter, flow-control header. */
    suspend fun address(c: Candidate): Boolean {
        val headerOk = if (bus == BusKind.CAN_29) {
            ok("ATCP ${Hex.byte((c.requestId shr PRIO_SHIFT).toInt())}") &&
                ok("ATSH ${Hex.id(c.requestId).substring(2).chunked(2).joinToString(" ")}")
        } else {
            ok("ATSH ${Hex.id(c.requestId)}")
        }
        if (!headerOk || !ok("ATCRA ${Hex.id(c.responseId)}")) return false
        if (manualFlowControl) {
            manualFlowControl = ok("ATFCSH ${Hex.id(c.requestId)}") && ok("ATFCSM 1")
        }
        return true
    }

    /** Points the adapter at the functional (broadcast) OBD address, accepting every OBD responder. */
    suspend fun functional(): Boolean = if (bus == BusKind.CAN_29) {
        ok("ATCP 18") && ok("ATSH DB 33 F1") && ok("ATCRA 18DAF1XX")
    } else {
        ok("ATSH 7DF") && ok("ATCRA 7EX")
    }

    /** Longest standard response wait (`ATST FF`, about 1 s) for a module that answered "pending". */
    suspend fun stretchTimeout(): Boolean = ok("ATST FF").also { stretchedTimeout = stretchedTimeout || it }

    /** Back to the default wait after a stretched one, so silent addresses stay cheap to probe. */
    suspend fun normalTimeout() {
        if (ok("ATST $DEFAULT_ST")) stretchedTimeout = false
    }

    /**
     * Puts back what the telemetry poller relies on: automatic flow control and receive, headers
     * off, the default functional header, the default response timeout. Every command is sent even
     * if one fails; the caller runs this in a `finally` under `NonCancellable`.
     */
    suspend fun restore() {
        val commands = buildList {
            add("ATFCSM 0")
            add("ATAR")
            add("ATH0")
            if (stretchedTimeout) add("ATST $DEFAULT_ST")
            if (bus == BusKind.CAN_29) {
                add("ATCP 18")
                add("ATSH DB 33 F1")
            } else if (bus == BusKind.CAN_11) {
                add("ATSH 7DF")
            }
        }
        for (cmd in commands) runCatching { link.send(cmd, AT_TIMEOUT_MS) }
    }

    private suspend fun ok(cmd: String): Boolean {
        val reply = link.send(cmd, AT_TIMEOUT_MS)
        if (reply.isBlank()) linkSilent = true
        return "OK" in reply.uppercase()
    }

    companion object {
        const val AT_TIMEOUT_MS = 2_000L
        private const val PRIO_SHIFT = 24
        private const val DEFAULT_ST = "32"

        /** `ATI` and `STI` read once: an STN chip (OBDLink class) answers STI, an ELM327 or clone says `?`. */
        suspend fun identify(link: DiagLink): AdapterSummary {
            val ati = link.send("ATI", AT_TIMEOUT_MS).let(ElmReply::lines).joinToString(" ").ifBlank { "(no answer)" }
            val stiLines = ElmReply.lines(link.send("STI", AT_TIMEOUT_MS))
            val sti = stiLines.joinToString(" ").takeUnless { stiLines.isEmpty() || stiLines.all { it == "?" } }
            return summarize(ati, sti)
        }

        fun summarize(ati: String, sti: String?): AdapterSummary = when {
            sti != null -> AdapterSummary(
                kind = "STN",
                ati = ati,
                sti = sti,
                words = "STN-based adapter (OBDLink class, \"$sti\"): can be told to use a second CAN network; " +
                    "this build reads the main one only",
            )
            "ELM327" in ati.uppercase() -> AdapterSummary(
                kind = "ELM327",
                ati = ati,
                words = "ELM327-compatible adapter (\"$ati\"); clones often print v1.5 or v2.1 whatever is inside, " +
                    "and plain ELM327s reach only the main CAN network on pins 6 and 14",
            )
            else -> AdapterSummary(
                kind = "UNKNOWN",
                ati = ati,
                words = "adapter did not identify itself as ELM327 ($ati)",
            )
        }
    }
}
