package com.kevin.midnightai.ai

import android.content.Context
import android.content.SharedPreferences
import com.kevin.midnightai.data.local.EpisodicTurn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** One finished drive conversation as the head unit keeps it after consolidation (issue #452). */
data class RecentChat(
    val vehicleId: String,
    val endedAtMs: Long,
    val summary: String?,
    /** The driver's own last few lines, oldest first, each already clipped. */
    val driverLines: List<String>,
)

/**
 * Issue #452 for Moose: a new chat opens with the last [SUMMARY_COUNT] chat summaries and, when the
 * newest chat ended within [LAST_CHAT_WINDOW_MS], the driver's last few lines of it. The phone's
 * `memory/RecentChats.kt` writes the same note from the engine, so Alfred and Moose start a chat the
 * same way (Kevin's voice-parity rule, 2026-11-24).
 *
 * **Where the head unit gets it.** Its memory is local: [MemoryConsolidator] distils each finished
 * chat within minutes and then deletes the raw turns, so the summary and the driver's last lines are
 * kept here ([RecentChatStore]) at that moment. A chat not yet consolidated is read straight from
 * the raw buffer.
 *
 * **Only the driver's words are carried from the raw turns.** This unit cannot mark which replies
 * called a tool or read third-party text, so it gives the driver's words only, exactly as the root CLAUDE.md
 * section 7 says of a source that cannot mark turns. A crisis chat is dropped before it is distilled
 * ([CrisisMemoryGate]), so it never reaches the store.
 *
 * The note labels these as past conversations, never as facts, never states a gap in words and
 * tells the model never to comment on how long it has been (the compulsion test's clause (c)).
 */
object RecentChatsNote {
    const val SUMMARY_COUNT = 2
    const val SUMMARY_LOOKBACK_MS = 7 * 24 * 3_600_000L
    const val LAST_CHAT_WINDOW_MS = 2 * 3_600_000L
    const val LAST_CHAT_LINES = 3
    const val LINE_MAX_CHARS = 200
    const val SUMMARY_MAX_CHARS = 400
    const val NOTE_MAX_CHARS = 1_600

    private val STAMP = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)
    private const val ELLIPSIS = "..."

    /** The driver's last few lines of a raw session, oldest first, clipped. */
    fun driverLines(turns: List<EpisodicTurn>): List<String> =
        turns.sortedBy { it.timestamp }
            .filter { it.role == EpisodicTurn.Role.DRIVER && it.text.isNotBlank() }
            .takeLast(LAST_CHAT_LINES)
            .map { clip(it.text, LINE_MAX_CHARS) }

    /**
     * The note for [vehicleId]: [stored] chats from the store, plus [pending], the newest raw
     * session not yet consolidated (null when there is none). Null when there is nothing to carry.
     */
    fun note(stored: List<RecentChat>, pending: RecentChat?, vehicleId: String, nowMs: Long, zone: ZoneId): String? {
        val chats = (stored + listOfNotNull(pending)).filter { it.vehicleId == vehicleId }
        val summaries = chats
            .filter { !it.summary.isNullOrBlank() && nowMs - it.endedAtMs <= SUMMARY_LOOKBACK_MS }
            .sortedByDescending { it.endedAtMs }
            .take(SUMMARY_COUNT)
        val last = chats.maxByOrNull { it.endedAtMs }
            ?.takeIf { it.driverLines.isNotEmpty() && nowMs - it.endedAtMs <= LAST_CHAT_WINDOW_MS }
        if (summaries.isEmpty() && last == null) return null
        val sb = StringBuilder(INTRO)
        if (summaries.isNotEmpty()) {
            sb.append(" Earlier conversations: ")
            sb.append(summaries.joinToString("; ") { "(${stamp(it.endedAtMs, zone)}) ${summaryText(it)}" })
            sb.append('.')
        }
        if (last != null) {
            sb.append(" The last conversation ended at ${stamp(last.endedAtMs, zone)}; the driver's last words in it: ")
            sb.append(last.driverLines.joinToString(" / ") { "Driver: $it" })
        }
        return clip(sb.toString(), NOTE_MAX_CHARS)
    }

    private const val INTRO =
        "Notes on your recent conversations with the driver, so you can pick up where you left off. " +
            "They describe past conversations, not facts from the record: use them when the driver asks " +
            "what you talked about or comes back to an earlier topic, and still call the tool before " +
            "you state an appointment, a figure or any other fact from the record. Never comment on " +
            "how long it has been since you talked."

    private fun summaryText(chat: RecentChat): String = clip(chat.summary.orEmpty(), SUMMARY_MAX_CHARS)

    private fun stamp(ms: Long, zone: ZoneId): String = STAMP.format(Instant.ofEpochMilli(ms).atZone(zone))

    internal fun clip(text: String, max: Int): String {
        val flat = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        return if (flat.length <= max) flat else flat.take(max - ELLIPSIS.length).trimEnd() + ELLIPSIS
    }
}

/**
 * Where [RecentChatsNote]'s finished chats are kept: the last [KEEP] of them, in this unit's own
 * preferences, none older than [RecentChatsNote.SUMMARY_LOOKBACK_MS]. Written by
 * [MemoryConsolidator] just before it deletes a chat's raw turns; cleared by every memory reset
 * ([CompanionReset]), since it is part of what the companion remembers.
 */
class RecentChatStore(private val prefs: SharedPreferences, private val now: () -> Long) {

    fun read(): List<RecentChat> = decode(prefs.getString(KEY, null), now())

    fun record(chat: RecentChat) {
        val kept = (read() + chat).sortedByDescending { it.endedAtMs }.take(KEEP)
        prefs.edit().putString(KEY, encode(kept)).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        const val KEEP = 4
        private const val PREFS = "recent_chats"
        private const val KEY = "chats"

        fun forContext(context: Context): RecentChatStore = RecentChatStore(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
            com.kevin.midnightai.time.TrustedClock::nowMs,
        )

        internal fun encode(chats: List<RecentChat>): String = JSONArray().apply {
            for (c in chats) {
                put(
                    JSONObject()
                        .put("vehicleId", c.vehicleId)
                        .put("endedAtMs", c.endedAtMs)
                        .put("summary", c.summary ?: JSONObject.NULL)
                        .put("driverLines", JSONArray(c.driverLines)),
                )
            }
        }.toString()

        /** The stored chats, dropping any older than the lookback; an unreadable value is none. */
        internal fun decode(raw: String?, nowMs: Long): List<RecentChat> {
            if (raw.isNullOrBlank()) return emptyList()
            val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val lines = o.optJSONArray("driverLines")
                RecentChat(
                    vehicleId = o.optString("vehicleId"),
                    endedAtMs = o.optLong("endedAtMs"),
                    summary = if (o.isNull("summary")) null else o.optString("summary"),
                    driverLines = (0 until (lines?.length() ?: 0)).map { lines!!.optString(it) },
                )
            }.filter { nowMs - it.endedAtMs <= RecentChatsNote.SUMMARY_LOOKBACK_MS }
        }
    }
}
