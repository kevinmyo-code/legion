package com.kevin.legion.ui.companions

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kevin.legion.memory.MemberMemory
import com.kevin.legion.memory.MemoryController
import com.kevin.legion.memory.MemoryOutcome
import com.kevin.legion.memory.MemoryWording
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the list area shows. A failed read is [Unavailable] with the reason, never an empty list. */
sealed interface MemoryView {
    data object Loading : MemoryView
    data class Unavailable(val sentence: String) : MemoryView
    data class Listed(
        val memories: List<MemberMemory>,
        /** Non-null when this is the phone's copy because the engine was out of reach. */
        val cachedNote: String? = null,
    ) : MemoryView
}

/** One dialog at a time: adding, editing a memory's text, or confirming a forget. */
sealed interface MemoryDialog {
    data class Add(val text: String = "", val shared: Boolean = false, val error: String? = null) : MemoryDialog
    data class Edit(val memory: MemberMemory, val text: String, val error: String? = null) : MemoryDialog
    data class ConfirmForget(val memory: MemberMemory) : MemoryDialog
}

data class MemoryUiState(
    val view: MemoryView = MemoryView.Loading,
    val query: String = "",
    val dialog: MemoryDialog? = null,
    val busy: Boolean = false,
    /** The last write's result in words ("Deleted for good.", or why nothing changed). */
    val message: String? = null,
) {
    /** The listed memories narrowed by [query] (text, kind or source), on the phone. */
    val shown: List<MemberMemory>
        get() {
            val listed = (view as? MemoryView.Listed)?.memories ?: return emptyList()
            val q = query.trim()
            if (q.isEmpty()) return listed
            return listed.filter {
                it.text.contains(q, ignoreCase = true) || it.kind.equals(q, ignoreCase = true) ||
                    it.source.equals(q, ignoreCase = true)
            }
        }
}

/**
 * The Memory screen's state as ONE [StateFlow] (CLAUDE.md section 8; the [com.kevin.legion.ui.bought.BoughtViewModel]
 * shape, since Hilt is not in the build). Every read and write is a [MemoryController] call, the
 * controller the `memory` voice tool calls (ADR 0035), and every sentence comes from [MemoryWording].
 *
 * **Forget is a hard delete and asks first** (memory-and-vault ticket 11): [askForget] opens the
 * confirm, [confirmForget] deletes and says "Deleted for good." only from the engine's 204.
 */
@Suppress("TooManyFunctions") // one function per screen action, the same shape as VaultViewModel
class MemoryViewModel(
    application: Application,
    private val controller: MemoryController,
) : AndroidViewModel(application) {

    /** What `viewModel()` can construct: the production controller over the saved engine. */
    constructor(application: Application) : this(application, MemoryController.forContext(application))

    private val _state = MutableStateFlow(MemoryUiState())
    val state: StateFlow<MemoryUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(view = MemoryView.Loading) }
            val view = when (val outcome = controller.list()) {
                is MemoryOutcome.Ok -> MemoryView.Listed(
                    outcome.value.memories,
                    cachedNote = outcome.value.takeIf { it.fromCache }?.let(MemoryWording::cachedNote),
                )
                else -> MemoryView.Unavailable(MemoryWording.readFailed(outcome))
            }
            _state.update { it.copy(view = view) }
        }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun dismissDialog() = _state.update { it.copy(dialog = null) }

    fun openAdd() = _state.update { it.copy(dialog = MemoryDialog.Add(), message = null) }

    fun openEdit(memory: MemberMemory) =
        _state.update { it.copy(dialog = MemoryDialog.Edit(memory, memory.text), message = null) }

    fun askForget(memory: MemberMemory) =
        _state.update { it.copy(dialog = MemoryDialog.ConfirmForget(memory), message = null) }

    /** Typing in the add or edit dialog. */
    fun editDialogText(text: String) = _state.update { s ->
        s.copy(
            dialog = when (val d = s.dialog) {
                is MemoryDialog.Add -> d.copy(text = text, error = null)
                is MemoryDialog.Edit -> d.copy(text = text, error = null)
                else -> d
            },
        )
    }

    fun setAddShared(shared: Boolean) = _state.update { s ->
        s.copy(dialog = (s.dialog as? MemoryDialog.Add)?.copy(shared = shared) ?: s.dialog)
    }

    /** Saves the add dialog by hand (source `manual`). The dialog stays open, with why, on failure. */
    fun saveAdd() {
        val dialog = _state.value.dialog as? MemoryDialog.Add ?: return
        runWrite {
            when (val outcome = controller.remember(dialog.text, dialog.shared, MemoryController.SOURCE_MANUAL)) {
                is MemoryOutcome.Ok -> done(MemoryWording.saved(outcome.value))
                else -> _state.update {
                    it.copy(dialog = dialog.copy(error = MemoryWording.writeFailed(outcome, "saved")))
                }
            }
        }
    }

    fun saveEdit() {
        val dialog = _state.value.dialog as? MemoryDialog.Edit ?: return
        runWrite {
            when (val outcome = controller.edit(dialog.memory, dialog.text)) {
                is MemoryOutcome.Ok -> done("Changed.")
                else -> _state.update {
                    it.copy(dialog = dialog.copy(error = MemoryWording.writeFailed(outcome, "changed")))
                }
            }
        }
    }

    fun confirmForget() {
        val dialog = _state.value.dialog as? MemoryDialog.ConfirmForget ?: return
        runWrite {
            when (val outcome = controller.forget(dialog.memory.id)) {
                is MemoryOutcome.Ok -> done(MemoryWording.DELETED)
                else -> _state.update {
                    it.copy(dialog = null, message = MemoryWording.writeFailed(outcome, "deleted"))
                }
            }
        }
    }

    /** The shared/private switch; offered only on the member's own memories. */
    fun setShared(memory: MemberMemory, shared: Boolean) {
        runWrite {
            when (val outcome = controller.setShared(memory, shared)) {
                is MemoryOutcome.Ok -> done(MemoryWording.sharedChanged(outcome.value))
                else -> _state.update { it.copy(message = MemoryWording.writeFailed(outcome, "changed")) }
            }
        }
    }

    private fun runWrite(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** A write landed: close the dialog, say so, and show the engine's list as it now stands. */
    private suspend fun done(message: String) {
        val cached = controller.cached()
        _state.update {
            it.copy(
                dialog = null,
                message = message,
                view = cached?.let { c -> MemoryView.Listed(c.memories) } ?: it.view,
            )
        }
    }
}
