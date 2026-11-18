package com.kevin.legion.vehicle

import com.kevin.legion.vehicle.diag.DiagVoice
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A clear-codes question dies with the connection it was asked on (2026-11-17 review): before this a
 * "yes" within three minutes could land on whatever car the adapter was plugged into next.
 */
class ClearAskDisconnectTest {
    @Test
    fun `a pending clear question is void once the adapter disconnects`() {
        DtcClearController.voiceGate.asked("clear the codes on the invented truck")
        DiagVoice.gate.asked("clear the codes in every module")
        DtcClearController.onAdapterDisconnected()
        assertEquals(ClearCodesVoiceGate.NO_QUESTION, DtcClearController.voiceGate.checkConfirmed("yes"))
        assertEquals(ClearCodesVoiceGate.NO_QUESTION, DiagVoice.gate.checkConfirmed("yes"))
    }
}
