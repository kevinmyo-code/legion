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

    // 2026-10-27: closing mid-sentence because the transcript had not arrived yet.

    @Test fun recentLocalSpeechExtendsInsteadOfClosing() {
        assertEquals(
            listOf(Action.NONE, Action.ARM, Action.ARM),
            run(Event.TurnComplete, Event.MicOpened, Event.Elapsed(0, msSinceLocalSpeech = 300)),
        )
    }

    @Test fun oldLocalSpeechStillCloses() {
        val stale = FollowUpWindow.LOCAL_SPEECH_RECENT_MS + 1
        assertEquals(
            listOf(Action.NONE, Action.ARM, Action.CLOSE),
            run(Event.TurnComplete, Event.MicOpened, Event.Elapsed(0, msSinceLocalSpeech = stale)),
        )
    }

    @Test fun extensionsAreBoundedSoNoiseCannotHoldTheWindowOpen() {
        val noisy = Event.Elapsed(0, msSinceLocalSpeech = 0)
        assertEquals(
            listOf(Action.NONE, Action.ARM, Action.ARM, Action.ARM, Action.CLOSE),
            run(Event.TurnComplete, Event.MicOpened, noisy, noisy, noisy),
        )
        assertEquals(2, FollowUpWindow.MAX_EXTENSIONS)
    }

    @Test fun transcriptDuringAnExtensionCancels() {
        assertEquals(
            listOf(Action.NONE, Action.ARM, Action.ARM, Action.CANCEL, Action.NONE),
            run(
                Event.TurnComplete, Event.MicOpened, Event.Elapsed(0, msSinceLocalSpeech = 0),
                Event.UserSpeech, Event.Elapsed(0),
            ),
        )
    }

    @Test fun nextAnswerResetsTheExtensionBudget() {
        val noisy = Event.Elapsed(0, msSinceLocalSpeech = 0)
        assertEquals(
            listOf(
                Action.NONE, Action.ARM, Action.ARM, Action.ARM,
                Action.CANCEL, Action.ARM, Action.ARM,
            ),
            run(
                Event.TurnComplete, Event.MicOpened, noisy, noisy,
                Event.TurnComplete, Event.MicOpened, noisy,
            ),
        )
    }

    // 2026-10-27, barge-in keeps the mic open: the reply draining is the arming moment.
    @Test fun playbackDrainedAfterTurnCompleteArms() {
        assertEquals(listOf(Action.NONE, Action.ARM), run(Event.TurnComplete, Event.PlaybackDrained))
    }

    @Test fun playbackDrainedWithoutAnAnswerNeverArms() {
        assertEquals(listOf(Action.NONE), run(Event.PlaybackDrained))
    }

    @Test fun localSpeechAfterDrainCancels() {
        assertEquals(
            listOf(Action.NONE, Action.ARM, Action.CANCEL),
            run(Event.TurnComplete, Event.PlaybackDrained, Event.UserSpeech),
        )
    }

    // ADR 0065: a NON_BLOCKING lookup. The holding line ends (TurnComplete), the mic opens and the
    // window arms while the lookup is still in flight, and the answer arrives with nobody speaking.

    @Test fun lookupOutlivingTheWindowReArmsWhenItsResultIsSent() {
        assertEquals(
            listOf(
                Action.NONE, Action.ARM, // holding line done, mic open
                Action.NONE, // 8 s, lookup still running: stand down, do not close
                Action.ARM, // result sent: a full window for the answer to begin in
                Action.CANCEL, // the answer starts playing
                Action.NONE, Action.ARM, Action.CLOSE, // the answer's own turn re-arms normally
            ),
            run(
                Event.TurnComplete, Event.MicOpened, Event.Elapsed(1), Event.ToolResponse,
                Event.ModelSpeech, Event.TurnComplete, Event.MicOpened, Event.Elapsed(0),
            ),
        )
    }

    @Test fun resultInsideTheWindowRestartsItsClock() {
        // Without this, a lookup finishing at 7.9 s left 0.1 s for the answer to start.
        assertEquals(
            listOf(Action.NONE, Action.ARM, Action.ARM, Action.CANCEL),
            run(Event.TurnComplete, Event.MicOpened, Event.ToolResponse, Event.ModelSpeech),
        )
    }

    @Test fun answerThatNeverComesStillEndsTheConversation() {
        // If WHEN_IDLE behaved like SILENT (ticket 04's gate), the session must not stay open.
        assertEquals(
            listOf(Action.NONE, Action.ARM, Action.NONE, Action.ARM, Action.CLOSE),
            run(Event.TurnComplete, Event.MicOpened, Event.Elapsed(1), Event.ToolResponse, Event.Elapsed(0)),
        )
    }

    @Test fun modelSpeechNeverLetsTheWindowCloseMidReply() {
        assertEquals(
            listOf(Action.NONE, Action.ARM, Action.CANCEL, Action.NONE),
            run(Event.TurnComplete, Event.MicOpened, Event.ModelSpeech, Event.Elapsed(0)),
        )
    }

    @Test fun resultWithNoWindowChangesNothing() {
        // A lookup that finished before its holding line did: the line's own turn arms as usual.
        assertEquals(
            listOf(Action.NONE, Action.NONE, Action.ARM),
            run(Event.ToolResponse, Event.TurnComplete, Event.MicOpened),
        )
    }

    @Test fun speechWhileTheLookupRunsClearsTheHold() {
        assertEquals(
            listOf(Action.NONE, Action.ARM, Action.NONE, Action.NONE, Action.NONE),
            run(Event.TurnComplete, Event.MicOpened, Event.Elapsed(1), Event.UserSpeech, Event.ToolResponse),
        )
    }
}
