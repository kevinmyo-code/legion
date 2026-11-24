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

    @Test fun `the driver's goodbye ends the conversation instead of handing the mic back`() {
        // Without the dismissal branch this is OPEN_MIC: the socket would listen on through the
        // sign-off and only the idle timer would ever end the chat.
        assertEquals(
            Action.PARK_WARM,
            TurnEndDecision.decide(suppressMic = false, toolsPending = false, vadMode = true, dismissed = true),
        )
    }

    @Test fun `a goodbye said while a tool is running waits for the answer`() {
        // "Look up the recalls, then that'll be all": the call turn completes first and the spoken
        // answer is still owed. Parking here would cut it off before it is generated.
        assertEquals(
            Action.HOLD_FOR_TOOL,
            TurnEndDecision.decide(suppressMic = false, toolsPending = true, vadMode = true, dismissed = true),
        )
    }

    @Test fun `a goodbye outranks the proactive branches it could collide with`() {
        // The driver's explicit instruction wins over a state the code inferred, and a suppressed
        // turn with a tool pending still holds, so the two agree where they overlap.
        assertEquals(
            Action.PARK_WARM,
            TurnEndDecision.decide(suppressMic = true, toolsPending = false, vadMode = true, dismissed = true),
        )
        assertEquals(
            Action.HOLD_FOR_TOOL,
            TurnEndDecision.decide(suppressMic = true, toolsPending = true, vadMode = true, dismissed = true),
        )
    }

    @Test fun `not dismissed is the old behaviour exactly`() {
        listOf(true, false).forEach { suppress ->
            listOf(true, false).forEach { pending ->
                listOf(true, false).forEach { vad ->
                    assertEquals(
                        "suppress=$suppress pending=$pending vad=$vad",
                        TurnEndDecision.decide(suppress, pending, vad),
                        TurnEndDecision.decide(suppress, pending, vad, dismissed = false),
                    )
                }
            }
        }
    }
}
