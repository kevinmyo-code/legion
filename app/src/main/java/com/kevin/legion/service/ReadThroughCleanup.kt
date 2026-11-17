package com.kevin.legion.service

import android.content.Context
import android.util.Log
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.ConversationAudit
import com.kevin.legion.data.local.READ_THROUGH_REDACTED
import com.kevin.legion.memory.FileMemoryCache
import com.kevin.legion.memory.MemberMemory
import com.kevin.legion.service.conversationlog.ConversationLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * One-off cleanup of the copies the cascade kept in clear before the ADR 0090 fix (2026-11-17,
 * Kevin: "Blank them", counts first, nothing changed until he says go). From 2026-11-10 the
 * cascade logged a folded mail, brief, parcel or flight read (`look_up` with `domain: ask_mail`,
 * `day` with `get_sitrep`, `track_package` or `flight_status`) under the called name, so its
 * result and the reply that followed were stored unredacted in Room `conversation_audit` and in
 * the conversation-log spool.
 *
 * - **Dry run** ([run] with `real = false`): counts only, logged as
 *   `readthrough_cleanup dry=true table=<table> rows=<n>`, plus the ids of memories the engine's
 *   consolidation may have made from those turns, for Kevin to review. Runs once by itself after
 *   the update ([maybeDryRunOnce]). Changes nothing.
 * - **Real run** (`real = true`): only from Kevin's button in Assistant settings, after a confirm.
 *   Blanks the text with the marker new rows get ([READ_THROUGH_REDACTED] in Room,
 *   [LiveAudit.REDACTED] in the spool). Never deletes a row, never touches a memory.
 *
 * Not reached from here: rows and log batches already uploaded to the engine (see the ADR 0090
 * phone report for the engine-side plan).
 */
object ReadThroughCleanup {
    const val TAG = "READTHROUGH_CLEANUP"

    /** Half a day before 2026-11-10 UTC, so the first cascade day is covered in any zone. */
    const val SINCE_MS = 1_794_225_600_000L // 2026-11-09T12:00:00Z

    /** A COMPANION row this long after its tool row still belongs to that turn. */
    const val TURN_SPAN_MS = 10 * 60_000L

    private const val PREFS = "readthrough_cleanup"
    private const val KEY_DRY_DONE = "dry_done_v1"
    private const val KEY_SUMMARY = "summary"
    private const val SOURCE_CONSOLIDATION = "consolidation"
    private const val BATCH = 500

    /** The names the cascade logged a folded read under. */
    private val FOLDED = setOf("look_up", "day")

    private val DOMAIN = Regex("\"domain\"\\s*:\\s*\"([A-Za-z_]+)\"")
    private val ACTION = Regex("\"action\"\\s*:\\s*\"([A-Za-z_]+)\"")
    private val REDACTED_QUOTED = LiveAudit.quote(LiveAudit.REDACTED)

    /** Whether a call logged as [name] with [argsText] stood for a read-through tool. */
    internal fun foldedReadThrough(name: String, argsText: String): Boolean {
        if (name !in FOLDED) return false
        // Args may be cut at 400 characters in the log, so read the two keys, not the whole JSON.
        val args = JSONObject()
        DOMAIN.find(argsText)?.let { args.put("domain", it.groupValues[1]) }
        ACTION.find(argsText)?.let { args.put("action", it.groupValues[1]) }
        return ReadThroughWriteGuard.isReadThroughCall(name, args)
    }

    /** The Room rows to blank: each unredacted folded read-through result, and the reply of its turn. */
    internal fun auditTargets(rows: List<ConversationAudit>, sinceMs: Long = SINCE_MS): List<Long> {
        val sorted = rows.filter { it.at >= sinceMs && !it.redacted }.sortedBy { it.id }
        val tools = sorted.filter {
            it.kind == ConversationAudit.Kind.TOOL_RESULT && foldedReadThrough(it.toolName, it.args)
        }
        val taken = HashSet<Long>()
        for (tool in tools) {
            // turn_seq restarts with every conversation, so it is matched with order and time too.
            sorted.firstOrNull {
                it.kind == ConversationAudit.Kind.COMPANION && it.turnSeq == tool.turnSeq &&
                    it.id > tool.id && it.at - tool.at in 0..TURN_SPAN_MS && it.id !in taken
            }?.let { taken.add(it.id) }
        }
        return (tools.map { it.id } + taken).sorted()
    }

    /**
     * Blanks spool lines one at a time, in file order: a folded read-through call's result, and every
     * assistant line of that turn. [changed] counts the lines it blanked.
     */
    internal class SpoolBlanker {
        private val ids = HashSet<String>()
        private var tainted = false
        var changed = 0
            private set

        fun line(line: String): String {
            val at = line.indexOf("${LiveAudit.TAG}: ")
            if (at < 0) return line
            val head = line.substring(0, at + LiveAudit.TAG.length + 2)
            val msg = line.substring(head.length)
            val out = when {
                msg.startsWith("session_start") -> {
                    ids.clear()
                    tainted = false
                    msg
                }
                msg.startsWith("turn_complete") -> {
                    tainted = false
                    msg
                }
                msg.startsWith("tool_call ") -> {
                    val id = field(msg, "id")
                    val name = field(msg, "name")
                    if (id != null && name != null && foldedReadThrough(name, msg.substringAfter(" args=", ""))) {
                        ids.add(id)
                        tainted = true
                    }
                    msg
                }
                msg.startsWith("tool_result ") && field(msg, "id") in ids -> blankAfter(msg, " response=")
                msg.startsWith("assistant ") && tainted -> blankAfter(msg, " text=")
                else -> msg
            }
            if (out != msg) changed++
            return head + out
        }

        private fun blankAfter(msg: String, key: String): String {
            val at = msg.indexOf(key)
            if (at < 0 || msg.substring(at + key.length) == REDACTED_QUOTED) return msg
            return msg.substring(0, at + key.length) + REDACTED_QUOTED
        }

        private fun field(msg: String, key: String): String? =
            Regex("(?:^|\\s)$key=(\\S+)").find(msg)?.groupValues?.get(1)
    }

    /** Consolidated memories made since the window opened: possibly from those turns. Ids only. */
    internal fun memoryCandidates(memories: List<MemberMemory>, sinceMs: Long = SINCE_MS): List<String> =
        memories.filter { it.source == SOURCE_CONSOLIDATION && it.createdAtMs >= sinceMs && it.retiredReason == null }
            .map { it.id }
            .sorted()

    data class Report(val real: Boolean, val auditRows: Int, val spoolLines: Int, val memoryIds: List<String>) {
        /** The log lines: counts and memory ids only, never content. */
        fun lines(): List<String> = listOf(
            "readthrough_cleanup dry=${!real} table=conversation_audit rows=$auditRows",
            "readthrough_cleanup dry=${!real} table=conversation_log_spool rows=$spoolLines",
            "readthrough_cleanup memories_to_review count=${memoryIds.size} ids=" +
                memoryIds.joinToString(",").ifEmpty { "none" },
        )

        /** What the settings row says. */
        fun summary(): String {
            val verb = if (real) "Blanked" else "Found"
            val memories = if (memoryIds.isEmpty()) {
                "no memories to review"
            } else {
                "${memoryIds.size} memories to review (${memoryIds.joinToString(", ")})"
            }
            return "$verb $auditRows conversation record rows and $spoolLines unsent log lines; $memories."
        }
    }

    /** One pass. [real] false counts only; true blanks. Never deletes, never touches a memory. */
    suspend fun run(context: Context, real: Boolean): Report {
        val app = context.applicationContext
        val dao = CarDatabase.getDatabase(app).conversationAuditDao()
        val targets = auditTargets(dao.since(SINCE_MS))
        val audit = if (real) {
            targets.chunked(BATCH).sumOf { dao.redactContent(it, READ_THROUGH_REDACTED) }
        } else {
            targets.size
        }
        val spool = ConversationLog.rewriteSpool(write = real, line = SpoolBlanker()::line)
        val memories = runCatching { FileMemoryCache.forContext(app).read()?.memories.orEmpty() }
            .getOrDefault(emptyList())
        val report = Report(real, audit, spool, memoryCandidates(memories))
        report.lines().forEach { Log.i(TAG, it) }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SUMMARY, report.summary()).apply()
        return report
    }

    /** The once-only dry run after the update. Counts only; marks itself done first, so it never repeats. */
    fun maybeDryRunOnce(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DRY_DONE, false)) return
        prefs.edit().putBoolean(KEY_DRY_DONE, true).apply()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { run(app, real = false) }.onFailure { Log.w(TAG, "readthrough_cleanup dry=true failed") }
        }
    }

    /** The last pass's summary for the settings row, or null before any ran. */
    fun lastSummary(context: Context): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SUMMARY, null)
}
