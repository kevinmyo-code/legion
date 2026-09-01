package com.kevin.legion.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards that [VoiceNoteAgent.SYSTEM_INSTRUCTION] still carries the honesty rules ticket 03's
 * "What it must not do" section spells out, the same posture
 * [AriaBrainHonestyClauseTest] takes for the Live prompt's cannot-clause.
 *
 * **What this does NOT do, same caveat as that test.** Presence in the prompt is all that is
 * checked. Nothing here can verify the model actually marks a real inaudible stretch or actually
 * refuses to invent a decision - that is unverifiable on this machine, and a green run here is not
 * evidence the transcript or summary told the truth about a real recording.
 */
class VoiceNoteAgentPromptTest {

    @Test
    fun `forbids asserting a spoken figure or fact as verified`() {
        assertTrue(
            "the prompt must say a spoken number/date/name is what was SAID, not confirmed true",
            VoiceNoteAgent.SYSTEM_INSTRUCTION.contains("not something you are confirming as true"),
        )
    }

    @Test
    fun `forbids writing to another aspect through the note`() {
        assertTrue(
            "the prompt must forbid phrasing anything as a ledger entry, reminder or goal",
            VoiceNoteAgent.SYSTEM_INSTRUCTION.contains("not writing a ledger entry, a reminder, or a goal"),
        )
    }

    @Test
    fun `requires marking inaudible stretches rather than inventing them`() {
        assertTrue(VoiceNoteAgent.SYSTEM_INSTRUCTION.contains("[inaudible]"))
        assertTrue(
            "the prompt must forbid guessing at words it could not make out",
            VoiceNoteAgent.SYSTEM_INSTRUCTION.contains("Never invent what you could not make out"),
        )
    }

    @Test
    fun `requires an honest summary of a meeting that decided nothing`() {
        assertTrue(
            VoiceNoteAgent.SYSTEM_INSTRUCTION.contains("reaches no conclusion, the summary must say exactly that"),
        )
        assertTrue(
            "the prompt must forbid inventing a decision or action item that was not stated",
            VoiceNoteAgent.SYSTEM_INSTRUCTION.contains("Never invent a decision, an action item, or an agreement"),
        )
    }

    @Test
    fun `never assigns speaker labels it cannot support`() {
        assertTrue(
            "diarization is unresolved (map's own Not yet specified section) - the prompt must not " +
                "ask the model to guess at who said what",
            VoiceNoteAgent.SYSTEM_INSTRUCTION.contains("do not assign a name or a label to who said what"),
        )
    }

    @Test
    fun `does not enumerate a fixed list of aspects, staying resilient to a new one`() {
        // Loose sanity check mirroring AriaBrainHonestyClauseTest's own "no capability list"
        // test - the rule is stated generally ("that domain's own ingestion path"), not by naming
        // ledger/pantry/dates one by one in a way a seventh aspect could slip past.
        assertFalse(
            "the prompt should state the rule generally rather than naming a fixed aspect list",
            VoiceNoteAgent.SYSTEM_INSTRUCTION.contains("pantry") || VoiceNoteAgent.SYSTEM_INSTRUCTION.contains("fleet"),
        )
    }
}
