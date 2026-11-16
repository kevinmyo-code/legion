package com.kevin.legion.vehicle.diag

/** One whole diagnostic message from one sender, ISO-TP reassembled. [complete] false = cut off. */
data class CanMessage(val sender: Long, val data: List<Int>, val complete: Boolean)

/** What one ELM327 reply amounts to, before any service-level meaning is read into it. */
sealed interface ElmReply {
    /** At least one frame came back. */
    data class Messages(val messages: List<CanMessage>) : ElmReply

    /** The adapter waited and nothing answered (`NO DATA`). */
    data object NoData : ElmReply

    /** The adapter did not understand the command (`?`). */
    data object NotUnderstood : ElmReply

    /** Nothing at all before the prompt: a timeout or a dropped link. */
    data object Silent : ElmReply

    /** The adapter reported a bus or link failure, in its own word. */
    data class BusError(val word: String) : ElmReply

    companion object {
        private val ERROR_WORDS = listOf(
            "UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR", "BUS BUSY", "DATA ERROR", "FB ERROR",
            "RX ERROR", "BUFFER FULL", "STOPPED", "LV RESET", "ACT ALERT", "ERR",
        )
        private const val ID_11_CHARS = 3
        private const val ID_29_TOKENS = 4
        private const val HEX = 16

        /** The reply's meaningful lines: upper case, no prompt, no `SEARCHING...` or bus-init chatter. */
        fun lines(raw: String): List<String> = raw.uppercase()
            .split('\r', '\n')
            .map { it.trim().trimStart('>').trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("SEARCHING") || (it.startsWith("BUS INIT") && "ERR" !in it) }

        /** The failure this reply is, or null when it holds frames. */
        fun failure(lines: List<String>): ElmReply? {
            val joined = lines.joinToString(" ")
            return when {
                lines.isEmpty() -> Silent
                "NO DATA" in joined -> NoData
                lines.all { it == "?" } -> NotUnderstood
                else -> ERROR_WORDS.firstOrNull { it in joined }?.let { BusError(it) }
            }
        }

        /**
         * Parses a CAN reply sent with headers on (`ATH1`) and spaces on (`ATS1`): `7E8 03 7F 19 11`
         * for 11-bit ids, `18 DA F1 10 03 7F 19 11` for 29-bit. The first data byte of each frame is
         * the ISO-TP PCI byte, which [IsoTp] uses to reassemble multi-frame answers per sender.
         */
        fun parseCan(raw: String, bus: BusKind): ElmReply {
            val lines = lines(raw)
            failure(lines)?.let { return it }
            val frames = lines.mapNotNull { frame(it, bus) }
            return if (frames.isEmpty()) BusError("unreadable reply") else Messages(IsoTp.assemble(frames))
        }

        private fun frame(line: String, bus: BusKind): IsoTp.Frame? {
            val tokens = line.split(Regex("\\s+"))
            val idTokens = if (bus == BusKind.CAN_29) ID_29_TOKENS else 1
            val idText = tokens.take(idTokens).joinToString("")
            val shaped = tokens.size > idTokens && (bus == BusKind.CAN_29 || idText.length == ID_11_CHARS)
            val id = idText.toLongOrNull(HEX)
            val bytes = tokens.drop(idTokens).map { t -> t.toIntOrNull(HEX)?.takeIf { it in 0..Hex.BYTE_MAX } }
            val whole = shaped && bytes.none { it == null }
            return if (whole && id != null) IsoTp.Frame(id, bytes.filterNotNull()) else null
        }

        /** The data bytes of a reply sent with headers OFF (legacy and generic reads): every 2-hex token. */
        fun dataLines(raw: String): List<List<Int>> = lines(raw).map { line ->
            line.split(Regex("\\s+"))
                .filter { it.length == 2 }
                .mapNotNull { it.toIntOrNull(HEX) }
        }.filter { it.isNotEmpty() }
    }
}

/** Hex formatting shared by the scanner, the report and the simulator. */
object Hex {
    const val BYTE_MAX = 0xFF
    const val ID_11_MAX = 0x7FF

    fun byte(b: Int): String = "%02X".format(b and BYTE_MAX)

    fun bytes(list: List<Int>): String = list.joinToString(" ") { byte(it) }

    /** A CAN id as the adapter prints it: 3 digits for 11-bit, 8 for 29-bit. */
    fun id(id: Long): String = if (id <= ID_11_MAX) "%03X".format(id) else "%08X".format(id)
}
