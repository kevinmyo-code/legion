package com.kevin.legion.vehicle.diag

/**
 * One ELM327 command exchange for the module scanner: the command text in, the adapter's raw reply
 * out (everything before the `>` prompt, "" when nothing came back). The real one holds the OBD
 * link exclusively for a whole scan ([com.kevin.legion.vehicle.ObdBluetoothManager.withDiagnosticLink]);
 * tests drive the simulated car through the real [com.kevin.legion.vehicle.Elm327Io].
 */
fun interface DiagLink {
    suspend fun send(command: String, timeoutMs: Long): String
}

/** Which bus the adapter negotiated, from `ATDPN`. Only the two CAN kinds allow module addressing here. */
enum class BusKind(val words: String) {
    CAN_11("CAN, 11-bit ids"),
    CAN_29("CAN, 29-bit ids"),
    J1850_PWM("SAE J1850 PWM"),
    J1850_VPW("SAE J1850 VPW"),
    ISO_9141("ISO 9141-2 (K-line)"),
    KWP_KLINE("ISO 14230 KWP2000 (K-line)"),
    J1939("SAE J1939"),
    UNKNOWN("no protocol detected"),
    ;

    val isCan: Boolean get() = this == CAN_11 || this == CAN_29

    companion object {
        /**
         * `ATDPN` answers a digit, prefixed `A` when auto-selected. Codes per the ELM327 datasheet:
         * 1 J1850 PWM, 2 J1850 VPW, 3 ISO 9141-2, 4 and 5 KWP, 6 and 8 CAN 11-bit, 7 and 9 CAN
         * 29-bit, A J1939; B and C are user CAN settings this app never selects.
         */
        fun fromDpn(reply: String): BusKind {
            val digit = reply.trim().uppercase().removePrefix("A").trim().firstOrNull()
            return when (digit) {
                '1' -> J1850_PWM
                '2' -> J1850_VPW
                '3' -> ISO_9141
                '4', '5' -> KWP_KLINE
                '6', '8' -> CAN_11
                '7', '9' -> CAN_29
                else -> if (reply.trim().uppercase() == "AA") J1939 else UNKNOWN
            }
        }
    }
}
