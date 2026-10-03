package com.kevin.legion.service

import com.kevin.legion.service.FollowUpWindow.Action
import com.kevin.legion.service.FollowUpWindow.Event
import com.kevin.legion.service.FollowUpWindow.State
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ticket 17: arm / cancel / expire over event sequences. Pure, no socket. */
class FollowUpWindowTest {
    private fun run(vararg events: Event): List<Action> {
        var state = State()
        return events.map {
            val d = FollowUpWindow.step(state, it)
            state = d.state
            d.action
        }
    }

    @Test fun windowIsEightSeconds() = assertEquals(8_000L, FollowUpWindow.WINDOW_MS)

    @Test fun turnCompleteAloneDoesNotStartTheClock() {
        assertEquals(listOf(Action.NONE), run(Event.TurnComplete))
    }

    @Test fun micOpenAfterTurnCompleteArms() {
        assertEquals(listOf(Action.NONE, Action.ARM), run(Event.TurnComplete, Event.MicOpened))
    }

    @Test fun micOpenWithoutAnAnswerNeverArms() {
        // First open of a bare tap-to-listen: no assistant turn behind it, so no window.
        assertEquals(listOf(Action.NONE), run(Event.MicOpened))
    }

    @Test fun silenceClosesTheConversation() {
        assertEquals(
            listOf(Action.NONE, Action.ARM, Action.CLOSE),
            run(Event.TurnComplete, Event.MicOpened, Event.Elapsed(0)),
        )
    }

    @Test fun speechCancelsAndNextAnswerReArms() {
        assertEquals(
            listOf(Action.NONE, Action.ARM, Action.CANCEL, Action.NONE, Action.ARM, Action.CLOSE),
            run(
                Event.TurnComplete, Event.MicOpened, Event.UserSpeech,
                Event.TurnComplete, Event.MicOpened, Event.Elapsed(0),
            ),
        )
    }

    @Test fun speechBeforeMicOpensCancelsThePendingArm() {
        assertEquals(
            listOf(Action.NONE, Action.CANCEL, Action.NONE),
            run(Event.TurnComplete, Event.UserSpeech, Event.MicOpened),
        )
    }

    @Test fun lateElapsedAfterSpeechDoesNotClose() {
        assertEquals(
            listOf(Action.NONE, Action.ARM, Action.CANCEL, Action.NONE),
            run(Event.TurnComplete, Event.MicOpened, Event.UserSpeech, Event.Elapsed(0)),
        )
    }

    @Test fun runningToolIsNotSilence() {
        assertEquals(
            listOf(Action.NONE, Action.ARM, Action.NONE, Action.NONE),
            run(Event.TurnComplete, Event.MicOpened, Event.Elapsed(1), Event.Elapsed(0)),
        )
    }

    @Test fun toolCallCancelsAndTurnAfterItReArms() {
        assertEquals(
            listOf(Action.NONE, Action.ARM, Action.CANCEL, Action.NONE, Action.ARM, Action.CLOSE),
            run(
                Event.TurnComplete, Event.MicOpened, Event.ToolCall,
                Event.TurnComplete, Event.MicOpened, Event.Elapsed(0),
            ),
        )
    }

    @Test fun newAnswerReplacesARunningWindow() {
        assertEquals(
            listOf(Action.NONE, Action.ARM, Action.CANCEL),
            run(Event.TurnComplete, Event.MicOpened, Event.TurnComplete),
        )
    }

    @Test fun closeIsNotAnErrorNorAResumeHandle() {
        // The close reason is "stopped": the owner raises no notice and carries no handle.
        assertEquals(false, LiveSessionController.shouldNotifyThreadLoss(true, "stopped", false))
    }
}
