package com.kevin.legion.ai

import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * One upload, a transcript and a summary out (voice-notes ticket 03,
 * `.scratch/voice-notes/issues/03-transcribe-and-summarize.md`). Given the `.m4a` file
 * [com.kevin.legion.voice.VoiceNoteRecorder] wrote, this uploads it through the Files API,
 * waits for it to become usable, and asks Gemini for `{title, summary, transcript}` in ONE
 * structured-output call - never three separate ones, because a summary produced from a
 * different pass than the transcript it claims to summarize is exactly the disagreement ADR
 * 0041's anchor chain forbids.
 *
 * **Deliberately narrow.** This object never touches [com.kevin.legion.data.local.VoiceNoteDao]
 * or [com.kevin.legion.data.local.VoiceNote] - it takes a path on disk and hands back a [Result],
 * nothing more. Ticket 04's `voice/VoiceNoteController.kt` is where a [Result] is turned into a
 * Room write; deciding that here would mean this object also owning retry/interruption state that
 * belongs to the controller, the same separation [com.kevin.legion.data.local.VoiceNoteStore]'s
 * own doc comment draws for the delete cascade.
 *
 * **The facts this file stands on are settled, not re-derived** -
 * `.scratch/voice-notes/research/gemini-audio-upload.md`, fetched 2026-09-01. In particular:
 * the session URL for the resumable upload's first leg arrives in the `x-goog-upload-url`
 * RESPONSE HEADER (see [SubAgent.uploadFile]'s own doc comment), the MIME type is `audio/m4a`
 * (NOT `audio/mp4` - not on Google's accepted list), and the uploaded file is deleted the moment
 * a transcript is actually in hand rather than left to the 48-hour auto-expiry.
 */
object VoiceNoteAgent {
    private const val TAG = "VoiceNoteAgent"

    /** The model the research file settles on: GA, the blended $0.75/1M rate that covers audio,
     * and the model every current Google audio example is written against. Not
     * [SubAgent.DEFAULT_MODEL] - that constant is tuned for cheap, fast domain workers, and a
     * meeting-length transcription is neither of those things. */
    const val MODEL = "gemini-3.7-flash"

    /**
     * The honesty posture this whole ticket exists to enforce (CLAUDE.md §4 rule 5, ADR 0041's
     * anchor chain, and the ticket's own "What it must not do" section). Every clause below traces
     * to one of those three sources - see [VoiceNoteAgentPromptTest] for the pin.
     */
    internal val SYSTEM_INSTRUCTION = "You transcribe and summarize a recorded audio note - a " +
        "solo thought or a meeting. You are not being asked for advice, only an honest account of " +
        "what is on the recording.\n\n" +
        "TRANSCRIPT RULES:\n" +
        "- Write what was actually said, as close to verbatim as you can make out. Do not clean up, " +
        "paraphrase, or correct the speakers.\n" +
        "- If a stretch of audio is inaudible, garbled, or drowned out, write [inaudible] at that " +
        "point rather than guessing at words that fit. Never invent what you could not make out.\n" +
        "- If you cannot confidently tell speakers apart, do not assign a name or a label to who " +
        "said what - write the words without a speaker attribution rather than guessing one.\n\n" +
        "SUMMARY RULES:\n" +
        "- The summary must come from what you actually transcribed, not from what a meeting like " +
        "this one would typically be about. If the recording is vague, meandering, or reaches no " +
        "conclusion, the summary must say exactly that - a summary that sounds decisive about a " +
        "meeting that decided nothing is worse than no summary at all.\n" +
        "- Never invent a decision, an action item, or an agreement that was not actually stated.\n\n" +
        "FIGURES AND FACTS - THE MOST IMPORTANT RULE HERE:\n" +
        "- Any number, date, name, or figure spoken in the recording is what a SPEAKER SAID, not " +
        "something you are confirming as true. Report it in the transcript and, if central to the " +
        "gist, in the summary - but never state it as a verified fact, and never treat it as more " +
        "certain than 'someone in this recording said X'.\n" +
        "- You are not writing a ledger entry, a reminder, or a goal. Do not phrase anything as an " +
        "instruction to act, a confirmed appointment, or a committed number - however clearly someone " +
        "said it out loud. That is a decision for a human, made through that domain's own ingestion " +
        "path, never through this note.\n\n" +
        "Respond with ONLY one JSON object, no prose outside it, no markdown code fence."

    private const val PROMPT = "Transcribe and summarize this recording. Respond with ONLY a raw " +
        "JSON object (no markdown, no commentary, no code fences) with this exact shape:\n" +
        "{\"title\": string (a short, specific title for this recording, not a generic label), " +
        "\"summary\": string (an honest account of what was said - see the summary rules above), " +
        "\"transcript\": string (as close to verbatim as you can make out - see the transcript rules above)}"

    /** OpenAPI-3.0-SUBSET schema for the one call, matching [StructuredOutputRequest]'s own
     * accepted field list. `propertyOrdering` puts `summary` before `transcript` deliberately: if
     * generation is cut off by [AgentResult.Success.truncated] mid-way through the (much longer)
     * verbatim transcript, `title` and `summary` are the fields most likely to already be complete
     * in the model's own generation order, which is exactly what [parseResponse]'s salvage path
     * depends on. */
    private fun responseSchema(): JSONObject = JSONObject().apply {
        put("type", "OBJECT")
        put("properties", JSONObject().apply {
            put("title", JSONObject().apply {
                put("type", "STRING")
                put("description", "A short, specific title for this recording.")
            })
            put("summary", JSONObject().apply {
                put("type", "STRING")
                put("description", "An honest account of what was said, including if nothing was decided.")
            })
            put("transcript", JSONObject().apply {
                put("type", "STRING")
                put("description", "As close to verbatim as legible, with [inaudible] marking any gap.")
            })
        })
        put("required", JSONArray().put("title").put("summary").put("transcript"))
        put("propertyOrdering", JSONArray().put("title").put("summary").put("transcript"))
    }

    sealed interface Result {
        /** [transcriptPartial] is true only when the model's own output cap was hit mid-transcript
         * (research file: 65,536 output tokens, "three hours plus will not" fit) and
         * [parseResponse] salvaged what it could - never true for an ordinary complete pass. A
         * caller must say so in words wherever this transcript is shown; see [transcript]'s own
         * leading `[Transcript truncated ...]` marker for the exact wording used. */
        data class Success(
            val title: String,
            val summary: String,
            val transcript: String,
            val transcriptPartial: Boolean,
        ) : Result

        /** Nothing to persist - CLAUDE.md §7's outcome-verb rule applies at the data layer here
         * exactly as it does to speech: [reason] is worded so a caller can surface it directly,
         * and the audio the caller passed in is entirely untouched by this failure (this object
         * never deletes or modifies the source file). */
        data class Failed(val reason: String) : Result
    }

    /**
     * Reads [audioPath], uploads it, waits for it to become usable, and asks for the one
     * structured-output pass. Returns [Result.Failed] - never throws - on any failure: a missing
     * file, an upload that never finishes, or a call the transcription service itself refused.
     * **Never touches the file at [audioPath]** - a failure here leaves the audio exactly where the
     * recorder left it, retryable by whatever calls this again.
     */
    suspend fun transcribeAndSummarize(audioPath: String, mimeType: String = "audio/m4a"): Result {
        val file = File(audioPath)
        val bytes = try {
            if (!file.exists()) return Result.Failed("No audio file found at $audioPath.")
            file.readBytes()
        } catch (e: Exception) {
            Log.w(TAG, "transcribeAndSummarize: couldn't read $audioPath: ${e.message}")
            return Result.Failed("Couldn't read the recording off disk: ${e.message}")
        }

        val agent = SubAgent(systemInstruction = SYSTEM_INSTRUCTION, useSearch = false, model = MODEL)

        val uploaded = when (val u = agent.uploadFile(bytes, mimeType, displayName = file.name)) {
            is FileUploadResult.Failed -> return Result.Failed("Couldn't upload the recording: ${u.reason}")
            is FileUploadResult.Uploaded -> u
        }

        val fileUri = when (val active = agent.awaitFileActive(uploaded.name)) {
            is FileActiveResult.Active -> active.uri
            is FileActiveResult.Failed ->
                return Result.Failed("The upload didn't finish processing: ${active.reason}")
            FileActiveResult.TimedOut ->
                return Result.Failed("The upload is still processing after waiting - try again shortly.")
        }

        val response = agent.askTyped(
            context = "",
            question = PROMPT,
            structuredOutput = StructuredOutputRequest(responseSchema()),
            fileUri = fileUri,
            fileMimeType = mimeType,
        )

        return when (response) {
            is AgentResult.Success -> {
                val parsed = parseResponse(response.text, response.truncated)
                // "call it once the transcript is in hand" (research file) - read literally: only
                // once [parseResponse] actually produced one. A Success here means a transcript
                // really did come back (whole or salvaged-partial); a Failed means the call
                // answered but nothing usable came out of it, in which case the file is left for
                // Google's own 48-hour expiry rather than guessing this was safe to discard.
                if (parsed is Result.Success) agent.deleteFile(uploaded.name)
                parsed
            }
            AgentResult.Offline ->
                Result.Failed("Couldn't reach the transcription service - the recording is still on the phone.")
            AgentResult.RateLimited ->
                Result.Failed("Rate limited by the transcription service - the recording is still on the phone.")
            AgentResult.KeyInvalid ->
                Result.Failed("The Gemini key isn't valid for transcription.")
            AgentResult.Overloaded ->
                Result.Failed("The transcription service is overloaded right now - the recording is still on the phone.")
            AgentResult.Failed ->
                Result.Failed("The transcription call failed.")
        }
    }

    /**
     * Network-free: the model's raw text plus [truncated] (from [AgentResult.Success.truncated])
     * in, a [Result] out. Unit-tested directly, same pattern as
     * [com.kevin.legion.pantry.PantryReceiptAgent.parseAndReconcile].
     *
     * The ordinary path is a clean [JSONObject] parse. When [truncated] is true AND that clean
     * parse fails - the expected shape when the model's own output cap cut it off mid-transcript -
     * this falls back to [salvageTruncated] rather than reporting a bare parse failure, so a
     * three-hour recording still yields a title and a summary instead of nothing at all (the
     * ticket's own "chunk or fall back to summary-plus-segments" instruction, done here as
     * "salvage the fields that finished before the cutoff" rather than a second network round
     * trip). A parse failure with [truncated] false is always reported as [Result.Failed] - there
     * is no cutoff to blame, so guessing at a salvage would be inventing structure the model never
     * actually produced.
     */
    internal fun parseResponse(raw: String, truncated: Boolean): Result {
        val stripped = stripFence(raw)
        parseFullObject(stripped)?.let { return it }

        if (!truncated) {
            return Result.Failed("The transcription came back in an unreadable shape.")
        }
        return salvageTruncated(stripped)
            ?: Result.Failed(
                "This recording is long enough that the transcript hit the model's output limit, " +
                    "and not enough came back before the cutoff to salvage even a summary. Try a " +
                    "shorter recording, or expect this one to need re-processing in pieces."
            )
    }

    private fun parseFullObject(stripped: String): Result? = try {
        val obj = JSONObject(stripped)
        val title = obj.optString("title").takeIf { it.isNotBlank() } ?: return null
        val summary = obj.optString("summary").takeIf { it.isNotBlank() } ?: return null
        val transcript = obj.optString("transcript").takeIf { it.isNotBlank() } ?: return null
        Result.Success(title, summary, transcript, transcriptPartial = false)
    } catch (e: Exception) {
        null
    }

    /**
     * Best-effort recovery from an unterminated JSON document - the shape Gemini leaves behind
     * when `finishReason` is `MAX_TOKENS` mid-way through the (much longer) `transcript` field.
     * `title` and `summary` sit before `transcript` in [responseSchema]'s `propertyOrdering`, so
     * they are usually complete by the time the cutoff lands; [transcript] itself is marked
     * partial in words rather than ever being handed back looking whole. Returns null when even
     * `title`/`summary` didn't finish - nothing worth keeping came back at all.
     */
    private fun salvageTruncated(stripped: String): Result.Success? {
        val title = extractStringField(stripped, "title") ?: return null
        val summary = extractStringField(stripped, "summary") ?: return null
        val partialTranscript = extractPartialTranscript(stripped).orEmpty()
        val transcript = "[Transcript truncated - this recording is long enough that the verbatim " +
            "transcript exceeded the model's output limit. What follows is only the portion produced " +
            "before the cutoff and is NOT the full recording.]\n\n$partialTranscript"
        return Result.Success(title, summary, transcript, transcriptPartial = true)
    }

    /** Pulls `"field": "value"` out of a possibly-truncated JSON document via [scanJsonString] -
     * not a general JSON parser, only good enough to salvage a field that DID finish before the
     * cutoff. Returns null if the field's closing quote is never found (the field itself was
     * mid-flight when the cutoff hit, or never started). */
    private fun extractStringField(text: String, field: String): String? {
        val start = openingQuoteIndex(text, field) ?: return null
        val (content, endIndex) = scanJsonString(text, start)
        return if (endIndex != null) content else null
    }

    /** Like [extractStringField] but for `transcript`, the LAST field in [responseSchema]'s
     * `propertyOrdering` - when the cutoff lands inside it there is no closing quote to find, so
     * this takes everything [scanJsonString] managed to decode up to the cutoff instead of
     * returning null the way [extractStringField] correctly does for a field that has a closing
     * quote to wait for. */
    private fun extractPartialTranscript(text: String): String? {
        val start = openingQuoteIndex(text, "transcript") ?: return null
        return scanJsonString(text, start).first
    }

    /** Index of the first character AFTER `"field":"` 's opening quote, or null if the key or its
     * opening quote is never found. Shared by [extractStringField]/[extractPartialTranscript] so
     * both agree on how a field's value starts. */
    private fun openingQuoteIndex(text: String, field: String): Int? {
        val keyIndex = text.indexOf("\"$field\"")
        if (keyIndex == -1) return null
        val colonIndex = text.indexOf(':', keyIndex)
        if (colonIndex == -1) return null
        var i = colonIndex + 1
        while (i < text.length && text[i].isWhitespace()) i++
        return if (i < text.length && text[i] == '"') i + 1 else null
    }

    /**
     * Scans a JSON string's content starting right after its opening quote. Returns the decoded
     * text so far, plus the index just past the closing quote - or null for that second value if
     * the buffer ran out before one was found, which is the EXPECTED shape for `transcript` when
     * `finishReason` is `MAX_TOKENS` mid-string (see [extractPartialTranscript]'s own doc
     * comment). Handles the standard JSON escapes plus four-hex-digit u-escapes; an incomplete one
     * right at the cutoff (fewer than 4 hex digits left in the buffer) stops decoding there rather
     * than guessing at the missing digits.
     *
     * The escape sequence is spelled out in words above rather than written literally: kapt copies
     * KDoc verbatim into its generated Java stubs, and javac's unicode preprocessor decodes escapes
     * ANYWHERE in a source file, comments included. A literal backslash-u followed by non-hex
     * characters is a hard javac error, which fails kapt and therefore the whole module - from
     * inside a comment. Found 2026-09-01 when it blocked an unrelated build.
     */
    private fun scanJsonString(text: String, start: Int): Pair<String, Int?> {
        val sb = StringBuilder()
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (c == '"') return sb.toString() to (i + 1)
            if (c == '\\') {
                if (i + 1 >= text.length) return sb.toString() to null // dangling backslash at the cutoff
                when (val esc = text[i + 1]) {
                    'u' -> {
                        val hex = text.drop(i + 2).take(4)
                        val code = if (hex.length == 4) hex.toIntOrNull(16) else null
                        if (code == null) return sb.toString() to null // \u cut off mid-sequence
                        sb.append(code.toChar())
                        i += 6
                    }
                    else -> {
                        sb.append(unescapeOne(esc))
                        i += 2
                    }
                }
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString() to null
    }

    private fun unescapeOne(escaped: Char): String = when (escaped) {
        'n' -> "\n"
        't' -> "\t"
        'r' -> "\r"
        '"' -> "\""
        '\\' -> "\\"
        '/' -> "/"
        else -> escaped.toString()
    }

    /** Strips a leading/trailing ```json or plain ``` fence, if present - same pattern as
     * [com.kevin.legion.advisor.AdvisorAnswer.stripFence]. */
    private fun stripFence(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```json").removePrefix("```").trim()
            if (text.endsWith("```")) text = text.removeSuffix("```").trim()
        }
        return text
    }
}
