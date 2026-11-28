package com.kevin.legion.memory

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** One past conversation's summary, written by the engine when the chat ended (issue #451). */
data class ChatSummary(val session: String, val endedAtMs: Long, val text: String)

/** One saved turn of a conversation (issue #450). [usedTools] is true when the turn called a tool. */
data class ChatTurn(
    val session: String,
    val turn: Int,
    val userText: String,
    val assistantText: String,
    val usedTools: Boolean,
    val spokenAtMs: Long,
)

/** Where [RecentChats] reads from. Null means the read failed, never "there is nothing". */
interface RecentChatsSource {
    /** The caller's newest summaries, the most recently ended first. */
    suspend fun summaries(limit: Int): List<ChatSummary>?

    /** The caller's newest turns, newest first. */
    suspend fun turns(limit: Int): List<ChatTurn>?
}

/** What a new chat is told about the last ones: a few summaries, and how the newest chat ended. */
data class RecentChatsRead(
    val summaries: List<ChatSummary> = emptyList(),
    val lastChatLines: List<String> = emptyList(),
    val lastChatEndedAtMs: Long? = null,
)

/**
 * Issue #452 (Kevin, 2026-11-25: "a new chat starts with almost no memory of recent
 * conversations"). A new chat opens with the last [SUMMARY_COUNT] conversation summaries and, when
 * the newest chat ended within [LAST_CHAT_WINDOW_MS], its last few turns, so "what did we talk
 * about today?" has an answer minutes after a chat closed.
 *
 * **Where it goes:** the live context ([com.kevin.legion.ai.AriaBrain.buildLiveContext]), which is
 * the first turn's preamble on the cascade path, after the cached prefix. Never the cached base
 * instruction: it changes with every chat.
 *
 * **What it may say (CLAUDE.md section 7):** the note labels these as past conversations, never
 * as facts from the record, so [com.kevin.legion.ai.RECORD_FACTS] still holds: an appointment or a
 * figure is still read from its tool. Only words the engine kept are read, and of those, a reply
 * only from a turn that called no tool, the same rule the engine's summary follows
 * (`server/member_memory/summaries.py`). It never states how long ago anything was in words, and
 * tells the model never to comment on it (the compulsion test's clause (c)).
 *
 * The head unit's `ai/RecentChatsNote.kt` writes the same note from its own store, so Alfred and
 * Moose start a chat the same way (Kevin's voice-parity rule, 2026-11-24).
 */
object RecentChats {
    const val SUMMARY_COUNT = 2
    const val SUMMARY_LOOKBACK_MS = 7 * 24 * 3_600_000L
    const val LAST_CHAT_WINDOW_MS = 2 * 3_600_000L
    const val LAST_CHAT_TURNS = 3
    const val TURNS_READ = 20
    const val LINE_MAX_CHARS = 200
    const val SUMMARY_MAX_CHARS = 400
    const val NOTE_MAX_CHARS = 1_600
    const val WAIT_MS = 1_200L

    private val STAMP = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)

    /** Reads both at once, each bounded by [waitMs]: a slow engine never holds a chat's start. */
    suspend fun read(source: RecentChatsSource, nowMs: Long, waitMs: Long = WAIT_MS): RecentChatsRead =
        coroutineScope {
            val summaries = async { withTimeoutOrNull(waitMs) { source.summaries(SUMMARY_COUNT + 1) } }
            val turns = async { withTimeoutOrNull(waitMs) { source.turns(TURNS_READ) } }
            pick(summaries.await().orEmpty(), turns.await().orEmpty(), nowMs)
        }

    /** The summaries and last-chat lines a note carries, chosen from what the engine returned. */
    fun pick(summaries: List<ChatSummary>, turns: List<ChatTurn>, nowMs: Long): RecentChatsRead {
        val kept = summaries
            .filter { it.text.isNotBlank() && nowMs - it.endedAtMs <= SUMMARY_LOOKBACK_MS }
            .sortedByDescending { it.endedAtMs }
            .take(SUMMARY_COUNT)
        val newest = turns.maxByOrNull { it.spokenAtMs }
        if (newest == null || nowMs - newest.spokenAtMs > LAST_CHAT_WINDOW_MS) {
            return RecentChatsRead(summaries = kept)
        }
        val lines = turns.filter { it.session == newest.session }
            .sortedBy { it.turn }
            .takeLast(LAST_CHAT_TURNS)
            .flatMap(::linesOf)
        return RecentChatsRead(kept, lines, newest.spokenAtMs.takeIf { lines.isNotEmpty() })
    }

    /** A turn's lines: the person's words, and the reply only when the turn called no tool. */
    private fun linesOf(turn: ChatTurn): List<String> = buildList {
        if (turn.userText.isNotBlank()) add("User: ${clip(turn.userText, LINE_MAX_CHARS)}")
        if (!turn.usedTools && turn.assistantText.isNotBlank()) {
            add("You: ${clip(turn.assistantText, LINE_MAX_CHARS)}")
        }
    }

    /** The note, or null when there is nothing to carry. [who] names the person ("the user"). */
    fun note(read: RecentChatsRead, zone: ZoneId, who: String = "the user"): String? {
        if (read.summaries.isEmpty() && read.lastChatLines.isEmpty()) return null
        val sb = StringBuilder(intro(who))
        if (read.summaries.isNotEmpty()) {
            sb.append(" Earlier conversations: ")
            sb.append(
                read.summaries.joinToString("; ") {
                    "(${stamp(it.endedAtMs, zone)}) ${clip(it.text, SUMMARY_MAX_CHARS)}"
                },
            )
            sb.append('.')
        }
        val endedAt = read.lastChatEndedAtMs
        if (read.lastChatLines.isNotEmpty() && endedAt != null) {
            sb.append(" The last conversation ended at ${stamp(endedAt, zone)} with: ")
            sb.append(read.lastChatLines.joinToString(" / "))
        }
        return clip(sb.toString(), NOTE_MAX_CHARS)
    }

    private fun intro(who: String): String =
        "Notes on your recent conversations with $who, so you can pick up where you left off. " +
            "They describe past conversations, not facts from the record: use them when $who asks " +
            "what you talked about or comes back to an earlier topic, and still call the tool before " +
            "you state an appointment, a figure or any other fact from the record. Never comment on " +
            "how long it has been since you talked."

    private fun stamp(ms: Long, zone: ZoneId): String = STAMP.format(Instant.ofEpochMilli(ms).atZone(zone))

    internal fun clip(text: String, max: Int): String {
        val flat = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        return if (flat.length <= max) flat else flat.take(max - ELLIPSIS.length).trimEnd() + ELLIPSIS
    }

    private const val ELLIPSIS = "..."
}
