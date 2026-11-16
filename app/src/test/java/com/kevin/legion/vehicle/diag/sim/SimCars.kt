package com.kevin.legion.vehicle.diag.sim

import com.kevin.legion.vehicle.diag.BusKind

/**
 * The scripted vehicles every diagnostics test runs against. Made-up VINs and part numbers only.
 * Module addresses follow the public naming the scanner's make tables use, so the naming and
 * clear-eligibility rules meet realistic inputs; nothing here was captured from a real car.
 */
object SimCars {
    const val FAKE_VIN = "1FTEW1EG0HFA00042"

    /** A UDS module: 19 02 answers [dtcs] (cleared by 14), 22 answers [dids], everything else NRC 11. */
    fun uds(request: Long, dtcs: List<Triple<String, Int, Int>>, dids: Map<Int, String> = emptyMap()): SimModule {
        var stored = dtcs
        return SimModule(request, request + 8) { req ->
            when (req.first()) {
                0x3E -> SimAnswer.Bytes(listOf(0x7E, 0x00))
                0x19 -> SimAnswer.Bytes(SimCodes.uds(stored))
                0x14 -> { stored = emptyList(); SimAnswer.Bytes(listOf(0x54)) }
                0x22 -> did(req, dids)
                else -> SimAnswer.Bytes(listOf(0x7F, req.first(), 0x11))
            }
        }
    }

    private fun did(req: List<Int>, dids: Map<Int, String>): SimAnswer {
        val id = (req[1] shl 8) or req[2]
        val text = dids[id] ?: return SimAnswer.Bytes(listOf(0x7F, 0x22, 0x31))
        return SimAnswer.Bytes(listOf(0x62, req[1], req[2]) + SimCodes.ascii(text))
    }

    /** A KWP2000-style module: refuses UDS 19 (NRC 11), answers 18 with [dtcs] (2-byte code + status). */
    fun kwp(request: Long, dtcs: List<String>): SimModule = SimModule(request, request + 8) { req ->
        when (req.first()) {
            0x3E -> SimAnswer.Bytes(listOf(0x7E, 0x00))
            0x18 -> SimAnswer.Bytes(listOf(0x58, dtcs.size) + dtcs.flatMap { SimCodes.bytes(it) + 0x60 })
            else -> SimAnswer.Bytes(listOf(0x7F, req.first(), 0x11))
        }
    }

    /** Answers TesterPresent, then refuses everything with [nrc]. */
    fun refusing(request: Long, nrc: Int): SimModule = SimModule(request, request + 8) { req ->
        if (req.first() == 0x3E) {
            SimAnswer.Bytes(listOf(0x7E, 0x00))
        } else {
            SimAnswer.Bytes(listOf(0x7F, req.first(), nrc))
        }
    }

    /** Answers TesterPresent, then says nothing. */
    fun mute(request: Long): SimModule = SimModule(request, request + 8) { req ->
        if (req.first() == 0x3E) SimAnswer.Bytes(listOf(0x7E, 0x00)) else SimAnswer.Silent
    }

    /** Says "response pending" to 19 before answering. */
    fun slow(request: Long, dtcs: List<Triple<String, Int, Int>>): SimModule = SimModule(request, request + 8) { req ->
        when (req.first()) {
            0x3E -> SimAnswer.Bytes(listOf(0x7E, 0x00))
            0x19 -> SimAnswer.PendingThen(SimCodes.uds(dtcs))
            else -> SimAnswer.Bytes(listOf(0x7F, req.first(), 0x11))
        }
    }

    /**
     * A CAN truck laid out like a Ford: PCM with generic codes, ABS with a long (multi-frame) list,
     * the airbag module, a BCM that refuses, a KWP-style cluster, a slow SYNC, a mute HVAC.
     */
    fun canTruck(): SimCar {
        val pcm = uds(
            0x7E0,
            listOf(Triple("P0420", 0x00, 0x2F), Triple("P0171", 0x00, 0x04), Triple("P0300", 0x00, 0x50)),
            mapOf(0xF190 to FAKE_VIN, 0xF188 to "TESTSW-0001"),
        ).apply { obd = SimObd(stored = listOf("P0420"), pending = listOf("P0171"), permanent = listOf("P0420")) }
        return SimCar(
            BusKind.CAN_11,
            listOf(
                pcm,
                uds(0x760, listOf(
                    Triple("C0040", 0x12, 0x2F), Triple("C0045", 0x11, 0x2F),
                    Triple("U0415", 0x00, 0x28), Triple("C1095", 0x00, 0x09),
                )),
                uds(0x737, listOf(Triple("B0092", 0x00, 0x2F), Triple("U0232", 0x00, 0x2F))),
                refusing(0x726, 0x22),
                kwp(0x720, listOf("U0100")),
                slow(0x7D0, listOf(Triple("U0184", 0x00, 0x08))),
                mute(0x733),
            ),
        )
    }

    /** A Mitsubishi-like car: engine and a CVT module that also answers KWP 21 local ids. */
    fun outlanderLike(): SimCar {
        val engine = uds(0x7E0, emptyList()).apply { obd = SimObd() }
        val cvt = SimModule(0x7E1, 0x7E9) { req ->
            when (req.first()) {
                0x3E -> SimAnswer.Bytes(listOf(0x7E, 0x00))
                0x19 -> SimAnswer.Bytes(SimCodes.uds(listOf(Triple("P0841", 0x00, 0x08))))
                0x21 -> when (req[1]) {
                    0x03 -> SimAnswer.Bytes(listOf(0x61, 0x03, 0x5A, 0x5B, 0x00, 0x01))
                    0x10 -> SimAnswer.Bytes(listOf(0x61, 0x10, 0x00, 0x01, 0x86, 0xA0))
                    else -> SimAnswer.Bytes(listOf(0x7F, 0x21, 0x31))
                }
                else -> SimAnswer.Bytes(listOf(0x7F, req.first(), 0x11))
            }
        }
        return SimCar(BusKind.CAN_11, listOf(engine, cvt))
    }

    /** A 29-bit car: engine at 10, a module at 58 that names itself an SRS unit. */
    fun can29(): SimCar {
        fun m29(target: Int, dtcs: List<Triple<String, Int, Int>>, dids: Map<Int, String>): SimModule {
            val base = uds(0, dtcs, dids)
            val request = 0x18DA00F1L or (target.toLong() shl 8)
            return SimModule(request, 0x18DAF100L or target.toLong(), handler = base.handler)
        }
        val engine = m29(0x10, listOf(Triple("P0128", 0x00, 0x2F)), emptyMap())
            .apply { obd = SimObd(stored = listOf("P0128")) }
        val srs = m29(0x58, listOf(Triple("B1234", 0x00, 0x2F)), mapOf(0xF197 to "SRS"))
        return SimCar(BusKind.CAN_29, listOf(engine, srs))
    }

    /** The XJ shape: pre-CAN, generic OBD only, slow. */
    fun preCan(bus: BusKind): SimCar = SimCar(
        bus,
        legacyObd = SimObd(
            stored = listOf("P0442", "P1391", "P0135", "P0455"),
            pending = listOf("P0171"),
            permanentSupported = false,
        ),
        latencyMs = 400,
    )
}
