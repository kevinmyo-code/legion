package com.kevin.legion.vehicle.diag

/**
 * The few ISO 14229 (UDS) and ISO 14230 (KWP2000) facts this read-only scanner uses. Read services
 * only, plus the clear service used solely behind Kevin's confirmation ([ModuleClearer]). Session
 * control (0x10), security access (0x27), writes (0x2E), I/O control (0x2F) and routines (0x31)
 * are deliberately absent: nothing here can send them.
 */
object Uds {
    const val NEGATIVE = 0x7F
    const val POSITIVE_OFFSET = 0x40
    const val TESTER_PRESENT = 0x3E
    const val READ_DTC = 0x19
    const val REPORT_BY_STATUS = 0x02
    const val STATUS_MASK_ALL = 0xFF
    const val READ_DID = 0x22
    const val CLEAR_DTC = 0x14
    const val KWP_READ_DTC_BY_STATUS = 0x18
    const val KWP_READ_DTC = 0x13
    const val KWP_READ_LOCAL_ID = 0x21
    const val NRC_PENDING = 0x78
    const val NRC_NOT_SUPPORTED = 0x11
    const val NRC_SUBFUNCTION = 0x12
    const val NRC_LENGTH = 0x13
    const val NRC_OUT_OF_RANGE = 0x31
    const val NRC_NOT_IN_SESSION = 0x7F
    const val NRC_SUB_NOT_IN_SESSION = 0x7E

    /** Negative response codes in plain words (ISO 14229-1 table A.1, the ones a reader meets). */
    @Suppress("MagicNumber") // a protocol table: each number is the standard's own code
    private val NRC_WORDS = mapOf(
        0x10 to "general reject",
        NRC_NOT_SUPPORTED to "service not supported",
        NRC_SUBFUNCTION to "sub-function not supported",
        NRC_LENGTH to "wrong message length",
        0x14 to "response too long",
        0x21 to "busy, repeat request",
        0x22 to "conditions not correct",
        0x24 to "request sequence error",
        NRC_OUT_OF_RANGE to "request out of range",
        0x33 to "security access denied",
        NRC_PENDING to "response pending",
        NRC_SUB_NOT_IN_SESSION to "sub-function not supported in this session",
        NRC_NOT_IN_SESSION to "service not supported in this session",
    )

    fun nrcWords(nrc: Int): String = NRC_WORDS[nrc] ?: "negative response 0x${Hex.byte(nrc)}"

    /** True when a negative response means "this service is not how to ask me", so a fallback may try another. */
    fun meansTryAnotherService(nrc: Int): Boolean =
        nrc == NRC_NOT_SUPPORTED || nrc == NRC_SUBFUNCTION || nrc == NRC_NOT_IN_SESSION ||
            nrc == NRC_SUB_NOT_IN_SESSION || nrc == NRC_LENGTH || nrc == NRC_OUT_OF_RANGE

    /** The answer to [service]: positive payload, negative code, or neither. */
    sealed interface Answer {
        data class Positive(val payload: List<Int>) : Answer
        data class Negative(val nrc: Int) : Answer
        data object Other : Answer
    }

    fun classify(message: CanMessage, service: Int): Answer {
        val d = message.data
        return when {
            d.size >= NEG_LEN && d[0] == NEGATIVE && d[1] == service -> Answer.Negative(d[2])
            d.isNotEmpty() && d[0] == service + POSITIVE_OFFSET -> Answer.Positive(d.drop(1))
            else -> Answer.Other
        }
    }

    private const val NEG_LEN = 3
}

/**
 * The DTC status byte of ISO 14229-1 (D.2), in the words a person reads. Only bits that say a test
 * FAILED make a code a fault; a code carrying only "test not completed" bits is a record, not a fault.
 */
object DtcStatus {
    private const val TEST_FAILED = 0x01
    private const val FAILED_THIS_CYCLE = 0x02
    private const val PENDING = 0x04
    private const val CONFIRMED = 0x08
    private const val FAILED_SINCE_CLEAR = 0x20
    private const val WARNING_LAMP = 0x80
    private const val FAULT_BITS = TEST_FAILED or FAILED_THIS_CYCLE or PENDING or CONFIRMED or
        FAILED_SINCE_CLEAR or WARNING_LAMP

    fun isFault(status: Int): Boolean = status and FAULT_BITS != 0

    fun words(status: Int): List<String> = buildList {
        if (status and CONFIRMED != 0) add("confirmed")
        if (status and PENDING != 0) add("pending")
        if (status and TEST_FAILED != 0) add("failing now")
        if (status and FAILED_THIS_CYCLE != 0) add("test failed this cycle")
        if (status and FAILED_SINCE_CLEAR != 0 && status and TEST_FAILED == 0) add("history")
        if (status and WARNING_LAMP != 0) add("warning light requested")
        if (isEmpty()) add("not failed (test not complete)")
    }
}
