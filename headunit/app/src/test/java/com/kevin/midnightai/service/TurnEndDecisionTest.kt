package com.kevin.midnightai.service

import com.kevin.midnightai.service.TurnEndDecision.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class TurnEndDecisionTest {

    @Test fun `a proactive line with nothing pending parks warm`() {
        assertEquals(Action.PARK_WARM, TurnEndDecision.decide(suppressMic = true, toolsPending = false, vadMode = true))
        assertEquals(Action.PARK_WARM, TurnEndDecision.decide(suppressMic = true, toolsPending = false, vadMode = false))
    }

    @Test fun `a proactive answered with a tool call holds instead of parking or opening the mic`() {
        // vadMode is true on a prewarmed socket: without the hold branch this fell into OPEN_MIC.
        assertEquals(Action.HOLD_FOR_TOOL, TurnEndDecision.decide(suppressMic = true, toolsPending = true, vadMode = true))
        assertEquals(Action.HOLD_FOR_TOOL, TurnEndDecision.decide(suppressMic = true, toolsPending = true, vadMode = false))
    }

    @Test fun `a conversation turn hands the mic back, tool pending or not`() {
        assertEquals(Action.OPEN_MIC, TurnEndDecision.decide(suppressMic = false, toolsPending = false, vadMode = true))
        assertEquals(Action.OPEN_MIC, TurnEndDecision.decide(suppressMic = false, toolsPending = true, vadMode = true))
    }

    @Test fun `a cold speak-only proactive restores audio and closes`() {
        assertEquals(Action.RESTORE_AND_CLOSE, TurnEndDecision.decide(suppressMic = false, toolsPending = false, vadMode = false))
    }
}
