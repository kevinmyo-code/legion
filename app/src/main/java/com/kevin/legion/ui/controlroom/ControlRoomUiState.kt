package com.kevin.legion.ui.controlroom

import com.kevin.legion.board.ActionRecord
import com.kevin.legion.board.BacklogIdea
import com.kevin.legion.board.BoardQuestion
import com.kevin.legion.board.BoardView
import com.kevin.legion.claude.ClaudeCodeSession
import com.kevin.legion.claude.ClaudeOverview
import com.kevin.legion.claude.ClaudeTask
import com.kevin.legion.ui.claudesession.ClaudeSessionRows
import com.kevin.legion.ui.claudesession.TaskRowUi
import java.time.Duration
import java.time.Instant

enum class ControlTab(val label: String) {
    SESSIONS("Sessions"),
    APPROVALS("Approvals"),
    BOARD("Board"),
    DECISIONS("Decisions"),
}

data class SessionUi(
    val sessionId: String,
    val name: String,
    val folder: String,
    val machine: String,
    val stateText: String,
    val waiting: Boolean,
    /** "waiting 12 min", or blank when not waiting. */
    val waited: String,
    /** Summary runs a worker task; never on a summaries-only machine or without an id. */
    val canSummarise: Boolean,
)

/**
 * A message, mail or ship card from a Claude task: its question and one Yes. [confirm] is true for
 * every card but a message to a Claude session: a worker ship card merges, deploys or publishes on
 * its yes, so its Yes asks for the fingerprint or PIN like an Allow (orchestrator default
 * 2026-11-22, pending Kevin). Only a message card, whose exact text the worker wrote from its
 * stored plan, stays one tap.
 */
data class TaskCardUi(val id: Int, val machine: String?, val question: String, val confirm: Boolean = true)

/** An action record, rendered by code from typed fields ([ActionWords]). */
data class ActionCardUi(
    val id: String,
    val recordSha256: String,
    val headline: String,
    /** Each typed fact on its own line; payee and purpose as quoted data. */
    val facts: List<String>,
    val statusText: String,
    val open: Boolean,
)

data class DecisionUi(
    val id: String,
    val title: String,
    val ask: String,
    val options: List<String>,
    val freeText: Boolean,
    val askSha256: String,
    /** Answered in chat instead: a deploy, publish, flag or spend, or a question marked approval. */
    val chatOnly: Boolean,
    val pending: Boolean,
)

data class BoardRowUi(val title: String, val detail: String, val stale: Boolean)

data class BacklogRowUi(val id: String, val title: String, val meta: String, val vote: String)

data class BoardSectionUi(
    val freshness: String,
    val inFlight: List<BoardRowUi>,
    val testing: List<BoardRowUi>,
    val doneToday: List<BoardRowUi>,
    val backlog: List<BacklogRowUi>,
    /** "Showing 300 of 412 ideas.", or blank when the whole backlog is here. */
    val backlogMore: String,
)

/** Everything the control room can ask for, one bag so the stateless content renders with fakes. */
data class ControlRoomActions(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onTab: (ControlTab) -> Unit = {},
    val onSummary: (SessionUi) -> Unit = {},
    /** Tasks and workflows: the full Claude session screen. */
    val onOpenTasks: () -> Unit = {},
    val onYes: (TaskCardUi) -> Unit = {},
    val onAllow: (ActionCardUi) -> Unit = {},
    val onDecline: (ActionCardUi) -> Unit = {},
    /** (decision, choice): null choice sends the typed text. */
    val onAnswer: (DecisionUi, String?) -> Unit = { _, _ -> },
    val onType: (String, String) -> Unit = { _, _ -> },
    val onVote: (String, String) -> Unit = { _, _ -> },
)

/** Everything the control room draws, as one value (CLAUDE.md section 8). */
data class ControlRoomUiState(
    val tab: ControlTab = ControlTab.SESSIONS,
    val loading: Boolean = true,
    /** Why the Claude half could not be read, in words; never drawn as an empty list. */
    val claudeNote: String? = null,
    /** Why the board half could not be read (switched off, unreachable), in words. */
    val boardNote: String? = null,
    val sessions: List<SessionUi> = emptyList(),
    val tasks: List<TaskCardUi> = emptyList(),
    val actions: List<ActionCardUi> = emptyList(),
    val board: BoardSectionUi? = null,
    val decisions: List<DecisionUi> = emptyList(),
    /** Typed answers per decision id. */
    val typed: Map<String, String> = emptyMap(),
    /** The last action's sentence, from the engine or the controller. */
    val message: String? = null,
    val busy: Boolean = false,
) {
    fun count(tab: ControlTab): Int = when (tab) {
        ControlTab.SESSIONS -> sessions.count { it.waiting }
        ControlTab.APPROVALS -> tasks.size + actions.count { it.open }
        ControlTab.BOARD -> board?.backlog?.size ?: 0
        ControlTab.DECISIONS -> decisions.count { !it.chatOnly && !it.pending }
    }
}

/** Pure mappings from the engine's rows; every word here is code-made or quoted data. */
object ControlRoomRows {

    fun sessions(overview: ClaudeOverview, now: Instant): List<SessionUi> =
        overview.status.sessions.map { s ->
            val machine = overview.status.machines.firstOrNull { it.name.equals(s.machine, ignoreCase = true) }
            SessionUi(
                sessionId = s.sessionId,
                name = s.spokenName,
                folder = s.project,
                machine = s.machine,
                stateText = if (s.waitingOnYou) "Waiting on you" else s.state.replaceFirstChar(Char::uppercase),
                waiting = s.waitingOnYou,
                waited = waited(s, now),
                canSummarise = SESSION_ID.matches(s.sessionId) && machine?.summariesOnly != true,
            )
        }

    private fun waited(s: ClaudeCodeSession, now: Instant): String {
        val since = s.waitingSince?.takeIf { s.waitingOnYou } ?: return ""
        val min = Duration.between(since, now).toMinutes().coerceAtLeast(0)
        val hours = min / MINUTES_PER_HOUR
        return if (hours == 0L) "waiting $min min" else "waiting $hours h ${min % MINUTES_PER_HOUR} min"
    }

    /** Claude task cards that want his yes, after the one-yes rule ([ClaudeSessionRows.tasks]). */
    fun taskCards(overview: ClaudeOverview): List<TaskCardUi> =
        ClaudeSessionRows.tasks(overview).filter { it.needsConfirmation }.map(::taskCard)

    private fun taskCard(t: TaskRowUi): TaskCardUi {
        val question = t.question ?: t.request
        return TaskCardUi(t.id, t.machine, question, confirm = !question.startsWith(ClaudeTask.SESSION_SEND_PREFIX))
    }

    fun actions(list: List<ActionRecord>): List<ActionCardUi> = list.map { a ->
        ActionCardUi(
            id = a.id,
            recordSha256 = a.recordSha256,
            headline = ActionWords.headline(a),
            facts = ActionWords.facts(a),
            statusText = ActionWords.status(a.status),
            open = a.open,
        )
    }

    fun decisions(view: BoardView): List<DecisionUi> =
        view.cards.filter { it.ask.isNotBlank() && it.answered == null }.map { c ->
            DecisionUi(
                id = c.id,
                title = c.title,
                ask = c.ask,
                options = c.options,
                freeText = c.freeText,
                askSha256 = c.askSha256,
                chatOnly = !c.answerable,
                pending = c.answerPending,
            )
        }

    fun board(view: BoardView): BoardSectionUi {
        fun rows(col: String) = view.cards.filter { it.col == col }.map(::boardRow)
        return BoardSectionUi(
            freshness = freshness(view),
            inFlight = rows("working") + rows("needs_kevin"),
            testing = rows("testing"),
            doneToday = rows("done"),
            backlog = view.backlog.map(::backlogRow),
            backlogMore = if (view.backlogTotal > view.backlog.size) {
                "Showing ${view.backlog.size} of ${view.backlogTotal} ideas."
            } else {
                ""
            },
        )
    }

    private fun boardRow(c: BoardQuestion) =
        BoardRowUi(c.title, c.status.ifBlank { c.ask }, c.stale)

    private fun backlogRow(i: BacklogIdea): BacklogRowUi {
        val score = i.score?.let { "RICE ${"%.1f".format(java.util.Locale.US, it)}" } ?: "Not scored"
        val tier = i.blast.ifBlank { "tier unknown" }
        return BacklogRowUi(i.id, i.title, "$score, $tier", i.vote)
    }

    fun freshness(view: BoardView): String {
        val age = view.ageS
        return when {
            !view.sent || age == null -> "The laptop has never sent its board."
            view.live -> "Live: sent ${ago(age)}."
            view.laptopOn -> "The laptop is on; its board last changed ${ago(age)}."
            else -> "The laptop is off; this board is from ${ago(age)}."
        }
    }

    private fun ago(seconds: Long): String {
        val min = seconds / SECONDS_PER_MINUTE
        return when {
            min < 2 -> "just now"
            min < MINUTES_PER_HOUR -> "$min min ago"
            else -> "${min / MINUTES_PER_HOUR} h ago"
        }
    }

    /**
     * The id shape `tools/claude_session/sessions.py` accepts: the id goes into the worker's task
     * text, so anything else (an engine row with odd text in it) offers no Summary at all.
     */
    private val SESSION_ID = Regex("[0-9a-fA-F-]{8,64}")
    private const val MINUTES_PER_HOUR = 60L
    private const val SECONDS_PER_MINUTE = 60L
}
