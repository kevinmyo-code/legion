@file:Suppress("MaxLineLength")

package com.kevin.legion.service.tools.voicenotes

import com.kevin.legion.service.tools.fn
import com.kevin.legion.service.tools.obj
import com.kevin.legion.service.tools.schema
import com.kevin.legion.voice.VoiceNoteController
import org.json.JSONObject

/**
 * The voicenotes tools' Live declarations, split out of `LiveToolbox.allDeclarations()`
 * (architecture review 2026-11-10, item 8). Moved verbatim; the order the setup message
 * carries them in is [com.kevin.legion.service.tools.DECLARATION_ORDER], not this file's.
 * Dispatch is still `LiveToolbox.dispatch`.
 */
// Moved verbatim from LiveToolbox.allDeclarations(), where the detekt baseline already carried
// these long description lines and the one long function: same text, so the same exemption.
@Suppress("LongMethod", "MaxLineLength")
internal object Declarations {
    fun declarations(): List<JSONObject> = buildList {
        // Voice notes (`.scratch/voice-notes/`, ADR 0041): a recording the user deliberately
        // starts and stops. All four tools below call `voice/VoiceNoteController.kt` - the SAME
        // controller `ui/voicenotes/VoiceNotesScreen.kt` calls (ADR 0035: one controller, not two
        // implementations).
        add(fn(
            name = "start_voice_note",
            description = "Start recording a voice note - a meeting or a solo thought the user " +
                "wants kept as audio, a transcript and a summary. Use for 'start recording', " +
                "'take a voice note', 'record this meeting'. Refuses in words, with nothing " +
                "started, if a voice note is already recording or the microphone is held by " +
                "something higher priority (an active conversation). Starting this does NOT " +
                "interrupt an ongoing conversation with the user and is itself refused if one is " +
                "in progress - tell the user to finish talking first.",
            params = obj(
                "kind" to schema("string",
                    "\"solo\" for a private thought, \"meeting\" for a conversation with other " +
                        "people. Defaults to \"solo\" if not said.",
                    enum = listOf("solo", "meeting")),
                "title" to schema("string",
                    "A short title for this recording, if the user gave one. Optional - a title " +
                        "is generated from the content once it's transcribed if this is left out."),
            ),
            required = listOf(),
        ))

        add(fn(
            name = "stop_voice_note",
            description = "Stop the currently-recording voice note. IMPORTANT: this only means " +
                "the recording is saved and is now being transcribed in the background - " +
                "transcription has NOT happened yet when this returns, so never say the note is " +
                "ready, summarized, or that you can read it back yet. Tell the user it's saved and " +
                "being transcribed; they can ask to hear it back in a bit with read_voice_note. If " +
                "nothing was recording, say so plainly rather than claiming anything was stopped.",
            params = obj(),
            required = listOf(),
        ))

        add(fn(
            name = "read_voice_note",
            description = "Read back a voice note's summary out loud. If the user names it " +
                "(by title), finds the closest match; otherwise reads the most recent one. The " +
                "summary is model-generated from the transcript, not a verbatim account - say so " +
                "if the user asks how sure you are, and never state a number, date, name, or " +
                "figure it contains as a confirmed fact, only as something that was said in the " +
                "recording. If the recording was interrupted partway through, or transcription " +
                "hasn't finished yet (no summary present), say so instead of inventing one.",
            params = obj(
                "title" to schema("string",
                    "Which recording, by title or part of its title. Leave empty for the most " +
                        "recent one."),
            ),
            required = listOf(),
        ))

        add(fn(
            name = "list_voice_notes",
            description = "List recent voice notes - titles, when they were recorded, and whether " +
                "each is solo or a meeting. Use for 'what recordings do I have', 'list my voice " +
                "notes'. Read-only; use read_voice_note to hear one's summary.",
            params = obj(
                "limit" to schema("integer", "How many to list. Defaults to 10."),
                "kind" to schema("string", "Only list this kind, if the user asked for one.",
                    enum = listOf("solo", "meeting")),
            ),
            required = listOf(),
        ))
    }
}
