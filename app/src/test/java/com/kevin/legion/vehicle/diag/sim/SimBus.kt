package com.kevin.legion.vehicle.diag.sim

import com.kevin.legion.vehicle.diag.BusKind

/** The bus side of [SimElm327]: protocol search, routing a request, and printing the frames. */
internal class SimBus(private val elm: SimElm327) {

    @Suppress("ReturnCount") // a scripted fake: early outs read clearest
    fun request(bytes: List<Int>): String {
        val car = elm.car
        val prefix = StringBuilder()
        if (elm.detected == null) {
            prefix.append("SEARCHING...\r")
            if (!car.ignitionOn) {
                elm.nowMs += SEARCH_MS
                return prefix.append("UNABLE TO CONNECT").toString()
            }
            if (car.bus == BusKind.ISO_9141) prefix.append("BUS INIT: ...OK\r")
            elm.detected = car.bus
            if (car.bus == BusKind.CAN_29) elm.header = SimElm327.FUNCTIONAL_29
        }
        if (!car.ignitionOn) return noData()
        val out = if (car.bus.isCan) can(bytes) else legacy(bytes)
        return prefix.append(out).toString()
    }

    private fun noData(): String {
        elm.nowMs += elm.st * ST_UNIT_MS
        return "NO DATA"
    }

    // ---------------------------------------------------------------- legacy (J1850, ISO 9141)

    @Suppress("ReturnCount")
    private fun legacy(bytes: List<Int>): String {
        val obd = elm.car.legacyObd ?: return noData()
        val lines: List<List<Int>> = when (bytes.first()) {
            0x01 -> mode01(bytes)?.let { listOf(it) } ?: return noData()
            0x03 -> legacyCodes(0x43, obd.stored)
            0x07 -> legacyCodes(0x47, obd.pending)
            0x04 -> {
                elm.car.legacyObd = obd.copy(stored = emptyList(), pending = emptyList())
                listOf(listOf(0x44))
            }
            else -> return noData()
        }
        elm.nowMs += elm.car.latencyMs * lines.size
        return lines.joinToString("\r") { line ->
            val shown = if (elm.headers) listOf(0x48, 0x6B, 0x10) + line + 0x00 else line
            fmt(shown)
        }
    }

    private fun legacyCodes(sid: Int, codes: List<String>): List<List<Int>> {
        val chunks = codes.chunked(3).ifEmpty { listOf(emptyList()) }
        return chunks.map { chunk ->
            val data = chunk.flatMap { SimCodes.bytes(it) }
            listOf(sid) + data + List(6 - data.size) { 0 }
        }
    }

    private fun mode01(bytes: List<Int>): List<Int>? {
        val car = elm.car
        return when (bytes.getOrNull(1)) {
            0x00 -> listOf(0x41, 0x00, 0xBE, 0x3F, 0xA8, 0x13)
            0x0C -> (car.rpm * 4).let { listOf(0x41, 0x0C, it shr 8, it and 0xFF) }
            0x0D -> listOf(0x41, 0x0D, car.speedKmh)
            else -> null
        }
    }

    // ---------------------------------------------------------------- CAN

    private fun isFunctional() = elm.header == SimElm327.FUNCTIONAL_11 || elm.header == SimElm327.FUNCTIONAL_29

    private fun can(bytes: List<Int>): String {
        val answers = mutableListOf<Pair<SimModule, SimAnswer>>()
        if (isFunctional()) {
            for (m in elm.car.modules) obdAnswer(m, bytes)?.let { answers += m to it }
        } else {
            elm.car.modules.firstOrNull { it.requestId == elm.header }?.let { m ->
                m.received += bytes
                answers += m to m.handler(bytes)
            }
        }
        val lines = mutableListOf<String>()
        for ((m, a) in answers) {
            if (!accepts(m.responseId)) continue
            lines += when (a) {
                is SimAnswer.Bytes -> frames(m, a.bytes)
                is SimAnswer.PendingThen -> {
                    val pending = frames(m, listOf(0x7F, bytes.first(), 0x78))
                    if (elm.st >= LONG_WAIT_ST) pending + frames(m, a.bytes) else pending
                }
                SimAnswer.Silent -> emptyList()
            }
        }
        if (lines.isEmpty()) return noData()
        elm.nowMs += elm.car.latencyMs
        return lines.joinToString("\r")
    }

    private fun obdAnswer(m: SimModule, bytes: List<Int>): SimAnswer? {
        val obd = m.obd ?: return null
        fun codes(sid: Int, list: List<String>) =
            SimAnswer.Bytes(listOf(sid, list.size) + list.flatMap { SimCodes.bytes(it) })
        return when (bytes.first()) {
            0x01 -> mode01(bytes)?.let { SimAnswer.Bytes(it) }
            0x03 -> codes(0x43, obd.stored)
            0x07 -> codes(0x47, obd.pending)
            0x0A -> if (obd.permanentSupported) codes(0x4A, obd.permanent) else null
            0x04 -> {
                m.received += bytes
                m.obd = obd.copy(stored = emptyList(), pending = emptyList())
                SimAnswer.Bytes(listOf(0x44))
            }
            else -> null
        }
    }

    private fun accepts(id: Long): Boolean {
        val pattern = elm.cra
        if (pattern != null) {
            val text = if (id <= 0x7FF) "%03X".format(id) else "%08X".format(id)
            return pattern.length == text.length &&
                pattern.indices.all { pattern[it] == 'X' || pattern[it] == text[it] }
        }
        return if (id <= 0x7FF) id in 0x7E8..0x7EF else (id and 0xFFFFFF00L) == 0x18DAF100L
    }

    /** The id automatic or manual flow control is sent to, after a first frame from [responseId]. */
    private fun flowControlTo(responseId: Long): Long? = when {
        elm.fcMode == 1 -> elm.fcHeader
        elm.personality.autoFlowControlBroken -> SimElm327.FUNCTIONAL_11
        responseId <= 0x7FF -> responseId - 8
        else -> (responseId and 0xFFFF0000L) or ((responseId and 0xFF) shl 8) or ((responseId shr 8) and 0xFF)
    }

    @Suppress("ReturnCount")
    private fun frames(m: SimModule, data: List<Int>): List<String> {
        val id = m.responseId
        if (data.size <= SF_MAX) {
            val frame = listOf(data.size) + data
            return listOf(if (elm.headers) idText(id) + fmt(pad(frame)) else fmt(data))
        }
        val ff = listOf(0x10 or (data.size shr 8), data.size and 0xFF) + data.take(FF_DATA)
        val cfs = data.drop(FF_DATA).chunked(CF_DATA).mapIndexed { i, chunk -> listOf(0x20 or ((i + 1) % 16)) + chunk }
        val reached = flowControlTo(id) == m.requestId
        val sent = if (reached) listOf(ff) + cfs else listOf(ff)
        if (elm.headers) return sent.map { idText(id) + fmt(pad(it)) }
        val lines = mutableListOf("%03X".format(data.size), "0: " + fmt(data.take(FF_DATA)))
        if (reached) cfs.forEachIndexed { i, cf -> lines += "${(i + 1) % 16}: " + fmt(cf.drop(1)) }
        return lines
    }

    private fun idText(id: Long): String =
        if (id <= 0x7FF) {
            "%03X ".format(id)
        } else {
            fmt(listOf(id shr 24, id shr 16, id shr 8, id).map { (it and 0xFF).toInt() }) + " "
        }

    private fun pad(frame: List<Int>) = frame + List((8 - frame.size).coerceAtLeast(0)) { 0x00 }

    private fun fmt(bytes: List<Int>): String = bytes.joinToString(if (elm.spaces) " " else "") { "%02X".format(it) }

    companion object {
        const val ST_UNIT_MS = 4L
        const val SEARCH_MS = 4_000L
        const val LONG_WAIT_ST = 0xFF
        const val SF_MAX = 7
        const val FF_DATA = 6
        const val CF_DATA = 7
    }
}
