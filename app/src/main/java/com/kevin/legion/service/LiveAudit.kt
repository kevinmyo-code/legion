package com.kevin.legion.service

import com.kevin.legion.service.conversationlog.ConversationLog
import com.kevin.legion.service.conversationlog.SavedToolCall
import com.kevin.legion.service.conversationlog.SavedTurn
import com.kevin.legion.service.tools.LiveSurface
import java.util.UUID
import org.json.JSONObject

/**
 * The conversation audit log (async-lookups ticket 05, 2026-10-28, Kevin: "cant u like record what
 * i say in the logs too so u can see everything and audit"). One `LIVE_AUDIT` logcat line per
 * conversation event: what the user said, what the assistant said, what each tool was asked and
 * returned. The `fast` build is release-shaped, so Room is not readable over ADB; this is how a run
 * is audited. `tools/voice_audit.py` reads it, so change both together.
 *
 * Format: `<event> key=value ...`, with the one free-text field always LAST, so a parser can take
 * the fixed keys as `\S+` tokens and the rest of the line as the text. Text is truncated at
 * [MAX_TEXT] characters with a `...` marker, then quoted with JSON escaping (so a newline or a quote
 * in speech cannot break a line). `args=` and `response=` are compact JSON, truncated the same way,
 * newline-free. [scrub] runs on every free-text field first: no API key, token or Authorization
 * header ever reaches the log.
 *
 * **CLAUDE.md sec 7, third-party content is read-through only.** On a turn that ran a tool in
 * [LiveToolbox.EPISODIC_EXCLUDED_TOOLS] (mail) or that the session marked read-through (a timed
 * sitrep), every assistant chunk from that point on is replaced by ONE
 * `assistant text="[redacted: third-party content]"` line, and that tool's own `tool_result` carries
 * `response="[redacted: third-party content]"`. Chunks spoken before the mail tool was asked for
 * stay in clear: no mail content exists yet when they are logged. The user's own words always log.
 *
 * Writes through [sink], which is [LiveLatency.audit] in production (logcat and/or the engine spool,
 * never the on-screen probe buffer). Off ([enabled] false) means no line at all. [onSession] hears
 * every session start and end whatever [enabled] says, so the engine spool can seal and upload.
 * Thread-safe: the socket thread and the tool dispatcher both call in.
 *
 * The turn being held for saving lives only in memory, from his first words to turn_complete (seconds):
 * an app killed in that window loses that one turn. Accepted (ticket 450); everything already queued is on disk.
 */
@Suppress("TooManyFunctions") // one function per LIVE_AUDIT event, the format's whole surface
internal class LiveAudit(
    private val sink: (String) -> Unit = { LiveLatency.audit(TAG, it) },
    private val isReadThroughTool: (String) -> Boolean = { GeminiLiveSession.isEpisodicExcludedTool(it) },
    private val onSession: (Boolean) -> Unit = { ConversationLog.onSession(it) },
    private val onTurn: (SavedTurn) -> Unit = { ConversationLog.saveTurn(it) },
    private val now: () -> Long = System::currentTimeMillis,
    private val newChatId: () -> String = { UUID.randomUUID().toString() },
    /** Who is signed in now; stamped on the turn when it is first spoken, not when it is queued. */
    private val identity: () -> String? = { ConversationLog.identity() },
) {
    @Volatile var enabled = false

    private val toolNames = HashMap<String, String>()

    // The turn in progress, handed to [onTurn] at turn_complete or session end (memory chain
    // ticket 450). Built from the same redacted inputs as the lines above, never from the logged
    // (truncated) text. Only his words and the tool NAMES are kept: the assistant's reply and any tool
    // result are never collected, because either can carry text other people wrote (CLAUDE.md sec 7).
    // The chat id and the turn counter live in [thread], which a resumed socket shares.
    private var thread: ChatThread? = null
    private var pendingChat: ChatThread? = null
    private val turnUser = ArrayList<String>()
    private val turnTools = ArrayList<SavedToolCall>()
    private var turnTyped = false
    private var crisisHeard = false
    private var turnOwner: String? = null

    // Ids of calls whose result is redacted because the call stands for a read-through tool under
    // a folded name (`look_up` with `domain: ask_mail`, `day` with `get_sitrep`): the result line
    // carries only the called name (2026-11-17, ADR 0090 gap 1, CLAUDE.md §7).
    private val readThroughIds = HashSet<String>()

    private var readThroughToolThisTurn = false
    private var redactionLoggedThisTurn = false
    private val readThroughLoggedThisTurn = HashSet<String>()

    /**
     * [pack] is the Alfred pack the session's prompt was built from, `<version>/<remote|cache|bundled>`
     * (alfred-ota ticket 02), so every conversation record says which wording it ran. Null leaves
     * the token off, as before the pack existed.
     *
     * The chat is the one [continueChat] left for this socket, else a fresh one (ticket 450).
     */
    @Synchronized
    fun sessionStart(model: String, asyncSet: Set<String>, pack: String? = null) {
        toolNames.clear()
        readThroughIds.clear()
        resetTurn()
        startChat(pendingChat)
        pendingChat = null
        val async = asyncSet.sorted().joinToString(",").ifEmpty { "none" }
        val packToken = pack?.let { " pack=${token(it)}" }.orEmpty()
        emit("session_start model=${token(model)} async_set=$async$packToken")
        onSession(true)
    }

    @Synchronized
    fun sessionEnd(reason: String) {
        flushTurn(interrupted = true) // anything still held never reached its turn_complete
        emit("session_end reason=${quote(reason)}")
        onSession(false)
    }

    /**
     * [final] is the transcription's own `finished` flag, or true for a whole-turn line. [whole] marks
     * that whole-turn line: it is the heard turn in full, so it replaces the chunks already taken for
     * the saved turn rather than joining them (the same words would be saved twice).
     */
    @Synchronized
    fun user(text: String, final: Boolean, typed: Boolean = false, whole: Boolean = false, save: Boolean = true) {
        if (save && final) takeWords(text, typed, whole)
        emit("user final=$final" + (if (typed) " typed=true" else "") + " text=${quote(text)}")
    }

    /**
     * Joins [text] to the saved turn's words, for a caller that logged the line with `save = false` and
     * has since seen the turn go to the model (ticket 450: a crisis line or a refused one is never saved).
     */
    @Synchronized
    fun keepWords(text: String, typed: Boolean = false) = takeWords(text, typed, whole = false)

    /**
     * The cascade's version of [keepWords]: [text] is the whole heard turn. A turn still held was sent to
     * the model and never reached turn_complete (he talked over it, tapped it, a write tool stopped it,
     * the voice was lost, or he went on and it MERGED): it happened, so it is saved, marked interrupted,
     * before the new one begins. The one exception is a merge: its words are carried into [text], so when
     * [text] contains them they are replaced, not saved twice. A merge never calls anything: the words
     * stay held until the next heard line takes them over, a stop or a refusal flushes them
     * ([flushHeld]), or the session ends. Words that were never sent are gone ([discardWords]).
     */
    @Synchronized
    fun replaceWords(text: String) {
        val held = turnUser.joinToString(" ")
        val carried = held.isNotEmpty() && collapse(scrub(text)).contains(held)
        if (carried) {
            turnUser.clear()
            turnTools.clear()
            turnTyped = false
        } else if (turnUser.isNotEmpty() || turnTools.isNotEmpty()) {
            flushTurn(interrupted = true)
        }
        takeWords(text, typed = false, whole = false)
    }

    /**
     * Saves what is held as an interrupted turn now: the conversation is ending or the next line was
     * refused, and the held turn (possibly a merged one whose words only live here) must not be lost.
     */
    @Synchronized
    fun flushHeld() {
        if (turnUser.isNotEmpty() || turnTools.isNotEmpty()) flushTurn(interrupted = true)
    }

    /**
     * Drops the words held for the turn in progress and takes no more until it ends (a crisis was
     * heard: it is not "what we talked about", and the later transcript chunks of the same turn are not
     * either).
     */
    @Synchronized
    fun discardWords() {
        turnUser.clear()
        turnTools.clear()
        turnTyped = false
        crisisHeard = true
    }

    private fun takeWords(text: String, typed: Boolean, whole: Boolean) {
        if (!enabled || crisisHeard) return
        if (turnOwner == null) turnOwner = identity()
        val words = collapse(scrub(text))
        if (whole && words.isNotEmpty()) turnUser.clear()
        if (words.isNotEmpty()) turnUser += words
        if (typed) turnTyped = true
    }

    /** [readThrough] is the session's own per-turn flag (`mailToolCalledThisTurn`). */
    @Synchronized
    fun assistant(chunk: String, readThrough: Boolean) {
        if (readThrough || readThroughToolThisTurn) {
            if (!redactionLoggedThisTurn) emit("assistant text=${quote(REDACTED)}")
            redactionLoggedThisTurn = true
        } else {
            emit("assistant text=${quote(chunk)}")
        }
    }

    @Synchronized
    fun toolCall(id: String, name: String, async: Boolean, argsJson: String, via: String? = null) {
        toolNames[id] = name
        val args = runCatching { JSONObject(argsJson) }.getOrNull() ?: JSONObject()
        if (isReadThroughTool(name) || isReadThroughTool(LiveSurface.resolve(name, args).name)) {
            readThroughToolThisTurn = true
            readThroughIds.add(id)
        }
        // ADR 0086: an email's body never reaches this log; EmailToolbox.redactArgs keeps its length.
        val logged = EmailToolbox.redactArgs(name, argsJson)
        // `via` (live cost diet, 2026-11-10): the action tool the model called when [name] is the
        // tool it resolved to. Before args, which stays the last field.
        val viaField = via?.let { " via=${token(it)}" } ?: ""
        emit("tool_call id=${token(id)} name=${token(name)} async=$async$viaField args=${json(logged)}")
    }

    /**
     * Voice cost audit 2026-11-10, F7a: one line per `usageMetadata` message, raw (not folded),
     * counts and modality names only ([com.kevin.legion.ai.LiveUsage.auditFields]). Raw on purpose:
     * how the reports line up against turn_complete and tool_call lines is what settles whether
     * Live bills per generation, per turn, or cumulatively.
     */
    fun usage(fields: String) = emit("usage $fields")

    /**
     * Every whole-turn farewell decision (2026-11-23, goodbye hardening: "that'll be all" and "bye bye" sometimes
     * did not close the conversation, and nothing in a log said whether the words were heard, whether
     * the rule matched them, or whether the voice floor then refused the stop). [matched] is the rule's
     * verdict on the words, [honoured] what the turn did with it; they differ exactly when the stop was
     * refused for want of voice. [text] is his turn as the rule normalised it: his own words, which
     * `user` logs anyway, and nothing else.
     */
    @Synchronized
    fun goodbye(text: String, matched: Boolean, afterQuestion: Boolean, voicedMs: Long?, honoured: Boolean) =
        emit(
            "goodbye matched=$matched honoured=$honoured after_question=$afterQuestion " +
                "voiced_ms=${voicedMs ?: -1} text=${quote(text)}",
        )

    /** The scripted opening line played on a tap or wake word (F2): a clip id, or `earcon[:why]`. */
    fun listenCue(label: String) = emit("listen_cue clip=${token(label)}")

    /**
     * `scheduling=WHEN_IDLE` when the result went back non-blocking (ADR 0065), else `none`.
     * `sent=false` is a response the socket dropped, or one held back after a cancellation.
     */
    @Synchronized
    fun toolResult(result: ToolResult) {
        toolNames.remove(result.id)
        val folded = readThroughIds.remove(result.id)
        val withheld = folded || isReadThroughTool(result.name)
        // The name and whether it worked; never the result (it may be mail or the web).
        if (enabled) {
            if (turnOwner == null) turnOwner = identity()
            // A tool name is the model's free text: one that is not a plain tool name must never cost his words.
            if (TOOL_NAME.matches(result.name)) turnTools += SavedToolCall(result.name, result.success)
        }
        val body = if (withheld) quote(REDACTED) else json(result.responseJson)
        emit(
            "tool_result id=${token(result.id)} name=${token(result.name)} success=${result.success} " +
                "ms=${result.ms} scheduling=${if (result.async) "WHEN_IDLE" else "none"} " +
                "sent=${result.sent} response=$body",
        )
    }

    @Synchronized
    fun toolCancelled(id: String) =
        emit("tool_cancelled id=${token(id)} name=${token(toolNames[id] ?: "unknown")}")

    /**
     * The turn was just marked read-through (`remember` refused for the rest of it), and why:
     * [source] is the read-through tool's name, `grounding` (the Live model's own Google Search),
     * `fleet_web` (a sub-agent behind [tool] got web text back, [com.kevin.legion.ai.WebReadMark]),
     * `proactive` (a timed raise carrying third-party text) or `carry` (a NON_BLOCKING web result
     * spoken in this turn, marked in the last). Names only: never a query, URL or any content. Once
     * per source and tool per turn: grounding metadata can ride on many messages of one turn.
     */
    @Synchronized
    fun readThrough(source: String, tool: String? = null) {
        val line = "read_through source=${token(source)}" + (tool?.let { " tool=${token(it)}" } ?: "")
        if (readThroughLoggedThisTurn.add(line)) emit(line)
    }

    /** Logs the turn's end, then clears the per-turn read-through state. */
    @Synchronized
    fun turnComplete() {
        flushTurn()
        emit("turn_complete")
        resetTurn()
    }

    /**
     * The next [sessionStart] belongs to [chat] instead of a fresh chat. A socket resumed from the last
     * one's handle calls this with the same [ChatThread], so its turns keep the chat id (and count on)
     * (one conversation is one chat, so the summary the engine makes when it ends covers it all;
     * ticket 450).
     */
    @Synchronized
    fun continueChat(chat: ChatThread) {
        pendingChat = chat
    }

    /**
     * Begins a chat: the next turn is turn 0 of a fresh chat id, or the next turn of [chat] when a
     * resumed socket brings the thread it belongs to. A Live session does this at its start; typed
     * chat calls it on "New conversation" ([TypedChatAudit]), the one moment its history is cleared.
     */
    @Synchronized
    fun startChat(chat: ChatThread? = null) {
        turnUser.clear()
        turnTools.clear()
        turnTyped = false
        crisisHeard = false
        turnOwner = null
        thread = chat ?: ChatThread(newChatId())
    }

    /** Hands the turn in progress to [onTurn] when it holds anything, then clears it. Caller holds the lock. */
    private fun flushTurn(interrupted: Boolean = false) {
        val hasContent = turnUser.isNotEmpty() || turnTools.isNotEmpty()
        // A crisis turn leaves no row at all: no words, no tool names, nothing to say it happened.
        if (enabled && hasContent && !crisisHeard) {
            val chat = thread ?: ChatThread(newChatId()).also { thread = it }
            onTurn(
                SavedTurn(
                    chatId = chat.id,
                    seq = chat.nextSeq++,
                    surface = if (turnTyped) "typed" else "voice",
                    userText = turnUser.joinToString(" ").ifEmpty { null },
                    toolCalls = turnTools.toList(),
                    spokenAtMs = now(),
                    owner = turnOwner,
                    interrupted = interrupted,
                ),
            )
        }
        turnUser.clear()
        turnTools.clear()
        turnTyped = false
        crisisHeard = false
        turnOwner = null
    }

    /** [source] is `server` (the Live API's `interrupted`) or `barge_in` (the phone's own gate). */
    @Synchronized
    fun interrupted(source: String) = emit("interrupted source=${token(source)}")

    /**
     * A filler clip that played (2026-11-19): [kind] `backchannel` (an "Mm." while he waits) or `task`
     * (a tool's holding line). Its own line, never an `assistant` line: it is not the answer, never
     * history, and memory never reads it; the next drive audit counts them.
     */
    @Synchronized
    fun filler(kind: String, text: String) = emit("filler kind=${token(kind)} text=${quote(text)}")

    @Synchronized
    fun activityStart() = emit("activity_start")

    @Synchronized
    fun activityEnd() = emit("activity_end")

    /**
     * A system note the session sent the model mid-lookup (async-lookups ticket 07): [reason] is
     * `still_checking` (with [afterMs], how long the call had run) or `late_holding_line`.
     */
    @Synchronized
    fun nudge(reason: String, tool: String, afterMs: Long? = null) =
        emit("nudge reason=${token(reason)} tool=${token(tool)}" + (afterMs?.let { " after_ms=$it" } ?: ""))

    @Synchronized
    fun micOpen(reason: String) = emit("mic_open reason=${token(reason)}")

    @Synchronized
    fun micClosed(reason: String) = emit("mic_closed reason=${token(reason)}")

    private fun resetTurn() {
        readThroughToolThisTurn = false
        redactionLoggedThisTurn = false
        readThroughLoggedThisTurn.clear()
    }

    private fun emit(line: String) {
        if (enabled) sink(line)
    }

    /** One tool response as it went back to the socket. */
    data class ToolResult(
        val id: String,
        val name: String,
        val success: Boolean,
        val ms: Long,
        val async: Boolean,
        val sent: Boolean,
        val responseJson: String,
    )

    companion object {
        /**
         * The audit of a test conversation (a `tools/phone_voice_test.py` or eval run): its lines go to
         * [sink] only. It never saves a turn, so a scripted line cannot become "what we talked about".
         */
        fun forTestConversation(sink: (String) -> Unit) = LiveAudit(sink = sink, onSession = {}, onTurn = {})

        const val TAG = "LIVE_AUDIT"
        const val MAX_TEXT = 400
        const val REDACTED = "[redacted: third-party content]"

        private val GOOGLE_KEY = Regex("AIza[0-9A-Za-z_\\-]{20,}")

        /** A Mapbox public or secret token (`pk.eyJ...` / `sk.eyJ...`); a router error can echo its URL (NAV_AUDIT). */
        private val MAPBOX_TOKEN = Regex("\\b[ps]k\\.eyJ[A-Za-z0-9._\\-]+")
        private val BEARER = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=\\-]+")
        private val JSON_SECRET = Regex(
            "(?i)(\"(?:api_?key|key|token|access_token|refresh_token|id_token|authorization|password|secret)\"" +
                "\\s*:\\s*)\"[^\"]*\"",
        )
        private val QUERY_SECRET = Regex("(?i)([?&](?:key|token|access_token)=)[^&\\s\"]+")
        private val WHITESPACE = Regex("\\s+")
        private val TOOL_NAME = Regex("[a-z0-9_]{1,64}")

        private fun collapse(text: String): String = text.trim().replace(WHITESPACE, " ")

        /** `success` from the response's own field, else true unless it carries an `error`. */
        fun successOf(hasSuccess: Boolean, success: Boolean, hasError: Boolean): Boolean =
            if (hasSuccess) success else !hasError

        /** Removes anything shaped like a credential. Runs before truncation. */
        fun scrub(text: String): String = text
            .replace(GOOGLE_KEY, "[key]")
            .replace(MAPBOX_TOKEN, "[key]")
            .replace(BEARER, "Bearer [redacted]")
            .replace(JSON_SECRET, "$1\"[redacted]\"")
            .replace(QUERY_SECRET, "$1[redacted]")

        /** At most [MAX_TEXT] characters, then `...` when anything was cut. */
        fun truncate(text: String): String = if (text.length > MAX_TEXT) text.take(MAX_TEXT) + "..." else text

        /** Scrubbed, truncated, and JSON-quoted: always one line, always parseable by `json.loads`. */
        fun quote(text: String): String {
            val s = truncate(scrub(text))
            val out = StringBuilder(s.length + 2).append('"')
            for (c in s) out.append(escape(c))
            return out.append('"').toString()
        }

        private fun escape(c: Char): String = when (c) {
            '"' -> "\\\""
            '\\' -> "\\\\"
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\t' -> "\\t"
            else -> if (c < ' ' || c == '\u2028' || c == '\u2029') "\\u%04x".format(c.code) else c.toString()
        }

        /** A compact JSON field: scrubbed, truncated, never containing a line break. */
        fun json(raw: String): String = truncate(scrub(raw))
            .replace("\r", "\\r").replace("\n", "\\n").replace('\u2028', ' ').replace('\u2029', ' ')

        /** A `key=value` value: no whitespace, never empty (the parser reads `\S+`). */
        fun token(value: String): String = value.trim().replace(WHITESPACE, "_").ifEmpty { "none" }
    }
}

/**
 * One conversation's chat id and the number its next saved turn takes. The phone owns one per
 * conversation, not per socket: a socket resumed from a handle shares it, so a resumed chat keeps its
 * id, so the engine can group a conversation's turns (memory chain ticket 450).
 */
class ChatThread(val id: String = UUID.randomUUID().toString()) {
    @Volatile var nextSeq = 0
}
