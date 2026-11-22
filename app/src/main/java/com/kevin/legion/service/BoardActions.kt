package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.ai.VerbatimSpeech
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.assistant.cards.ConversationCards
import com.kevin.legion.assistant.cards.RowStatus
import com.kevin.legion.assistant.cards.StatusRow
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.board.BoardBackend
import com.kevin.legion.board.BoardBrief
import com.kevin.legion.board.BoardOutcome
import com.kevin.legion.board.ControlRoomBackend
import com.kevin.legion.board.EngineControlRoomBackend
import com.kevin.legion.board.EngineBoardBackend
import com.kevin.legion.board.IdeaSaveGate
import org.json.JSONObject

/**
 * `claude_session`'s factory board actions (backlog intake 1122), kept out of
 * [ClaudeSessionToolbox] so it stays one job per file.
 *
 * - `where` ("where are we"): the engine's short brief, SPOKEN BY CODE word for word
 *   ([VerbatimSpeech]); the model's copy holds the freshness and counts only (the spoken words are
 *   stripped before it is handed over, [VerbatimSpeech.PRIVATE]), and the whole board goes on a
 *   card.
 * - `idea` ("add to the backlog"): the engine scores it and writes the read-back; the phone speaks
 *   it and holds the draft in [gate]. `yes: true` saves it only when [IdeaSaveGate] says this is
 *   his explicit yes in a later turn; `yes: false` drops it. Silence saves nothing.
 */
object BoardActions {

    /** Test seam: swapped for a fake backend. */
    internal var backendFactory: (Context) -> BoardBackend = { context ->
        EngineBoardBackend(EngineHttp(EngineConfig(context.applicationContext)))
    }

    internal var gate = IdeaSaveGate()

    private const val CARD_ROWS = 30

    /** Test seam: the control room's queue (`GET /api/board/queue`). */
    internal var queueFactory: (Context) -> ControlRoomBackend = { context ->
        EngineControlRoomBackend(EngineHttp(EngineConfig(context.applicationContext)))
    }

    /** `where`, `needs` or `idea` from `claude_session`'s arguments. */
    suspend fun dispatch(context: Context, action: String, args: JSONObject, userText: String?): JSONObject =
        when (action) {
            "where" -> where(context)
            "needs" -> needs(context)
            else -> {
                val yes = if (args.has("yes")) args.optBoolean("yes") else null
                idea(context, args.optString("request"), yes, userText)
            }
        }

    /**
     * `needs` ("what needs me?", control room C4): the engine's counts in its own code-built words,
     * SPOKEN BY CODE. A read only: voice never approves a deploy, a spend or a send from here; the
     * control room is where he taps.
     */
    suspend fun needs(context: Context): JSONObject =
        when (val out = queueFactory(context).queue()) {
            is BoardOutcome.Failed -> result(false, out.sentence)
            is BoardOutcome.Ok -> {
                val said = out.value.spoken.joinToString(" ").ifBlank { out.value.line }
                VerbatimSpeech.mark(
                    result(
                        true,
                        "LEGION's own voice said what waits on him, word for word; that is the answer. Answers, " +
                            "approvals and allows happen on the control room screen, never by voice.",
                    ).put("sessions", out.value.sessions)
                        .put("approvals", out.value.approvals)
                        .put("decisions", out.value.decisions),
                    said,
                    ClaudeSessionToolbox.TOOL_NAME,
                )
            }
        }

    suspend fun where(context: Context): JSONObject =
        when (val out = backendFactory(context).brief()) {
            is BoardOutcome.Failed -> result(false, out.sentence)
            is BoardOutcome.Ok -> {
                publishCard(out.value)
                val said = out.value.lines.joinToString(" ")
                VerbatimSpeech.markPrivate(
                    result(
                        true,
                        "${out.value.forModel} LEGION's own voice read the board aloud, word for word; that " +
                            "is the answer. The cards' own words are not shown to you; never describe them.",
                    ),
                    said,
                    ClaudeSessionToolbox.TOOL_NAME,
                )
            }
        }

    /**
     * [yes] is null for a new idea ([request] holds it), true or false for his answer to the one
     * read back. [userText] is the words of the turn making this call ([IdeaSaveGate]).
     */
    suspend fun idea(context: Context, request: String, yes: Boolean?, userText: String?): JSONObject {
        val backend = backendFactory(context)
        val waiting = gate.current()
        return when {
            yes == false && waiting == null -> result(false, IdeaSaveGate.NOTHING_WAITING)
            yes == false && waiting != null -> spoken(backend.dropIdea(waiting.id)) { gate.clear() }
            yes == true -> when (val decision = gate.check(userText)) {
                is IdeaSaveGate.Decision.Refused -> result(false, decision.message)
                is IdeaSaveGate.Decision.Go -> spoken(backend.confirmIdea(decision.id)) { gate.clear() }
            }
            request.isBlank() -> result(false, "Say the idea in words. Nothing was added.")
            else -> drafted(backend.draftIdea(request), userText)
        }
    }

    private fun drafted(out: BoardOutcome<com.kevin.legion.board.IdeaDraft>, userText: String?): JSONObject =
        when (out) {
            is BoardOutcome.Failed -> result(false, out.sentence)
            is BoardOutcome.Ok -> {
                gate.register(out.value.id, userText)
                VerbatimSpeech.mark(
                    result(
                        true,
                        "Read the backlog idea back and asked whether to add it; nothing is saved. Only on his " +
                            "yes in his next message call claude_session idea with yes true; on a no, yes false.",
                    ).put("saved", false),
                    out.value.readback,
                    ClaudeSessionToolbox.TOOL_NAME,
                )
            }
        }

    private inline fun spoken(out: BoardOutcome<String>, onOk: () -> Unit): JSONObject = when (out) {
        is BoardOutcome.Failed -> result(false, out.sentence)
        is BoardOutcome.Ok -> {
            onOk()
            VerbatimSpeech.mark(result(true, out.value), out.value, ClaudeSessionToolbox.TOOL_NAME)
        }
    }

    private fun publishCard(brief: BoardBrief) {
        if (brief.cards.isEmpty()) return
        val rows = brief.cards.take(CARD_ROWS).map { c ->
            val status = when (c.col) {
                "needs_kevin" -> RowStatus.ATTENTION
                "queued" -> RowStatus.UNKNOWN
                else -> RowStatus.OK
            }
            val note = listOf(COLUMN_WORDS[c.col] ?: c.col, c.detail).filter { it.isNotBlank() }
            StatusRow(c.title, status, note.joinToString(": "))
        }
        ConversationCards.publish(
            ConversationCard.StatusList(
                title = "Factory board",
                items = rows,
                more = (brief.cards.size - CARD_ROWS).coerceAtLeast(0),
            ),
        )
    }

    private val COLUMN_WORDS = mapOf(
        "needs_kevin" to "Waiting on you",
        "working" to "Working",
        "testing" to "Testing or shipping",
        "queued" to "Queued",
        "done" to "Done today",
    )

    private fun result(success: Boolean, message: String): JSONObject =
        JSONObject().put("success", success).put("message", message)
}
