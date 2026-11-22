package com.kevin.legion.ui.controlroom

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.board.BoardOutcome
import com.kevin.legion.board.ControlRoomBackend
import com.kevin.legion.board.EngineControlRoomBackend
import com.kevin.legion.claude.ClaudeOutcome
import com.kevin.legion.claude.ClaudeSessionController
import com.kevin.legion.claude.ClaudeTaskDraft
import com.kevin.legion.claude.ClaudeWording
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The control room's one state (CLAUDE.md section 8). Two sources, each read on its own so one
 * failing never blanks the other:
 * - Claude sessions and task cards through [ClaudeSessionController], the controller the voice tool
 *   calls (ADR 0035), so a message card's one yes is the same yes either way.
 * - The board, decisions, votes and action records through [ControlRoomBackend] (dark on the
 *   engine: off, the board half says so in words and the Claude half still works).
 *
 * An Allow on an action record reaches the engine ONLY after [ActionConfirmer] said yes.
 */
@Suppress("TooManyFunctions") // one small function per thing the screen can do
class ControlRoomViewModel(
    application: Application,
    private val controller: ClaudeSessionController,
    private val backend: ControlRoomBackend,
    private val now: () -> Instant = Instant::now,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        ClaudeSessionController.forContext(application),
        EngineControlRoomBackend(EngineHttp(EngineConfig(application))),
    )

    private val _state = MutableStateFlow(ControlRoomUiState())
    val state: StateFlow<ControlRoomUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            loadClaude()
            loadBoard()
            _state.update { it.copy(loading = false) }
        }
    }

    private suspend fun loadClaude() {
        when (val out = controller.overview()) {
            is ClaudeOutcome.Ok -> _state.update {
                it.copy(
                    claudeNote = null,
                    sessions = ControlRoomRows.sessions(out.value, now()),
                    tasks = ControlRoomRows.taskCards(out.value),
                )
            }
            else -> _state.update { it.copy(claudeNote = ClaudeWording.failed(out, "read your Claude sessions")) }
        }
    }

    private suspend fun loadBoard() {
        when (val out = backend.board()) {
            is BoardOutcome.Ok -> _state.update {
                it.copy(
                    boardNote = null,
                    board = ControlRoomRows.board(out.value),
                    decisions = ControlRoomRows.decisions(out.value),
                )
            }
            is BoardOutcome.Failed -> _state.update {
                it.copy(boardNote = out.sentence, board = null, decisions = emptyList())
            }
        }
        // Action records have their own switch; off is simply none to show.
        val actions = (backend.actions() as? BoardOutcome.Ok)?.value.orEmpty()
        _state.update { it.copy(actions = ControlRoomRows.actions(actions)) }
    }

    fun pick(tab: ControlTab) = _state.update { it.copy(tab = tab) }

    fun type(decisionId: String, text: String) = _state.update { it.copy(typed = it.typed + (decisionId to text)) }

    /** "Summary": a worker task built by code from the session's id; only its summary comes back. */
    fun summarise(session: SessionUi) {
        if (!session.canSummarise) return
        act {
            val request = summaryRequest(session.sessionId)
            controller.send(request, session.machine, ClaudeTaskDraft.ORIGIN_NAMED, emailMe = false).sentence
        }
    }

    /** The one Yes on a message, mail or ship card: one tap, the controller's own rule. */
    fun yes(taskId: Int) = act { controller.answer(taskId, "", yes = true, emailMe = false).sentence }

    /** His answer to a board question: a choice, or the typed text when [choice] is null. */
    fun answer(decision: DecisionUi, choice: String?) {
        if (decision.chatOnly) return
        val text = if (choice == null) _state.value.typed[decision.id].orEmpty().trim() else ""
        act { words(backend.answer(decision.id, decision.askSha256, choice.orEmpty(), text)) }
    }

    fun vote(ideaId: String, vote: String) = act { words(backend.vote(ideaId, vote)) }

    /**
     * Allow on an action record: the confirm first; only its yes reaches the engine. A refusal or a
     * cancel says so and sends nothing.
     */
    fun allow(card: ActionCardUi, confirmer: ActionConfirmer) {
        if (!card.open) return
        confirmer.confirm(
            ActionWords.confirmTitle(card),
            { act { words(backend.decide(card.id, card.recordSha256, allow = true)) } },
            { sentence -> _state.update { it.copy(message = sentence) } },
        )
    }

    /** Decline needs no confirm: it can only stop something. */
    fun decline(card: ActionCardUi) {
        if (!card.open) return
        act { words(backend.decide(card.id, card.recordSha256, allow = false)) }
    }

    private fun words(out: BoardOutcome<String>): String = when (out) {
        is BoardOutcome.Ok -> out.value
        is BoardOutcome.Failed -> out.sentence
    }

    private fun act(block: suspend () -> String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val said = block()
            _state.update { it.copy(busy = false, message = said) }
            loadClaude()
            loadBoard()
        }
    }

    companion object {
        /** The worker reads the session (`sessions.py read`) and sends only a capped summary back. */
        fun summaryRequest(sessionId: String): String =
            "Read my Claude session $sessionId and summarise it in under 400 characters: what it is " +
                "working on and what it is waiting on. Change nothing and send it nothing."
    }
}
