package com.kevin.legion.ui.claudesession

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kevin.legion.claude.ClaudeOutcome
import com.kevin.legion.claude.ClaudeSendResult
import com.kevin.legion.claude.ClaudeSessionController
import com.kevin.legion.claude.ClaudeTaskDraft
import com.kevin.legion.claude.ClaudeWatch
import com.kevin.legion.claude.ClaudeWatchStore
import com.kevin.legion.claude.ClaudeWording
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Claude session screen's state as ONE [StateFlow] (CLAUDE.md section 8). Every read and write
 * is a [ClaudeSessionController] call, the controller the `claude_session` voice tool calls (ADR
 * 0035), and every sentence comes from [ClaudeWording], so the screen and the voice say the same.
 * Online only: a read that fails is [ClaudeSessionUiState.unavailable], never an empty list.
 */
@Suppress("TooManyFunctions") // one small function per thing the screen can do
class ClaudeSessionViewModel(
    application: Application,
    private val controller: ClaudeSessionController,
    private val watch: ClaudeWatch,
) : AndroidViewModel(application) {

    constructor(application: Application) :
        this(application, ClaudeSessionController.forContext(application), ClaudeWatchStore(application))

    private val _state = MutableStateFlow(ClaudeSessionUiState())
    val state: StateFlow<ClaudeSessionUiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { _state.update { it.copy(announcementsOn = watch.announcementsOn()) } }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            when (val out = controller.overview()) {
                is ClaudeOutcome.Ok -> _state.update {
                    it.copy(
                        loading = false,
                        unavailable = null,
                        machines = ClaudeSessionRows.machines(out.value.status.machines, controller.wording),
                        tasks = ClaudeSessionRows.tasks(out.value),
                        sessions = ClaudeSessionRows.sessions(out.value.status.sessions),
                    )
                }
                else -> _state.update {
                    it.copy(loading = false, unavailable = ClaudeWording.failed(out, "read the Claude session"))
                }
            }
        }
    }

    fun setDraft(text: String) = _state.update { it.copy(draft = text, message = null) }

    fun pickMachine(name: String?) = _state.update { it.copy(pickedMachine = name) }

    fun setEmailMe(on: Boolean) = _state.update { it.copy(emailMe = on) }

    fun toggleTask(id: Int) = _state.update {
        it.copy(expandedTask = if (it.expandedTask == id) null else id, answer = "")
    }

    fun setAnswer(text: String) = _state.update { it.copy(answer = text) }

    fun setAnnouncements(on: Boolean) {
        _state.update { it.copy(announcementsOn = on) }
        viewModelScope.launch { watch.setAnnouncements(on) }
    }

    /** A typed send is Kevin naming the Claude session by hand: origin `named`. */
    fun send() {
        val s = _state.value
        if (s.sending) return
        act(clearDraft = true) {
            controller.send(s.draft, s.pickedMachine, ClaudeTaskDraft.ORIGIN_NAMED, s.emailMe)
        }
    }

    /** [yes] is the "Yes, go ahead" button: `confirms` only when the task is waiting for it. */
    fun answer(taskId: Int, yes: Boolean) {
        val text = _state.value.answer
        act(clearDraft = false) { controller.answer(taskId, text, yes, emailMe = false) }
    }

    fun assign(taskId: Int, machine: String) = act(clearDraft = false) { controller.assign(taskId, machine) }

    fun sendFile(taskId: Int, fileId: Int) = act(clearDraft = false) { controller.sendFile(taskId, fileId) }

    private fun act(clearDraft: Boolean, call: suspend () -> ClaudeSendResult) {
        _state.update { it.copy(sending = true, message = null) }
        viewModelScope.launch {
            val result = call()
            val sent = result is ClaudeSendResult.Sent
            _state.update {
                it.copy(
                    sending = false,
                    message = result.sentence,
                    draft = if (sent && clearDraft) "" else it.draft,
                    answer = if (sent) "" else it.answer,
                )
            }
            refresh()
        }
    }
}
