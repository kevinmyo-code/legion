package com.kevin.legion.ui.vault

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kevin.legion.vault.BiometricUnlock
import com.kevin.legion.vault.VaultController
import com.kevin.legion.vault.VaultItem
import com.kevin.legion.vault.VaultOutcome
import com.kevin.legion.vault.VaultSecret
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface VaultView {
    data object Loading : VaultView
    data class Unavailable(val sentence: String) : VaultView
    data class Listed(val items: List<VaultItem>) : VaultView
}

/** The add or edit form. [value] is what is being typed, held only while the form is open. */
data class VaultForm(
    val editing: VaultItem? = null,
    val label: String = "",
    val kind: String = "other",
    val value: String = "",
    val error: String? = null,
) {
    // Never print the typed value: a form in a log line or a crash report says only its label.
    override fun toString(): String = "VaultForm(label=$label, kind=$kind, value=[hidden])"
}

/** One value on screen, until [hideAtMs] or until the screen leaves the foreground. */
class Shown(val itemId: String, val secret: VaultSecret, val hideAtMs: Long) {
    override fun toString(): String = "Shown($itemId, [vault value])"
}

data class VaultUiState(
    val view: VaultView = VaultView.Loading,
    val shown: Shown? = null,
    val form: VaultForm? = null,
    val confirmDelete: VaultItem? = null,
    /** The item `show_vault_item` asked for, highlighted and offered the fingerprint on arrival. */
    val focusId: String? = null,
    /** True once the arrival prompt for [focusId] was shown, so a rotation does not prompt again. */
    val focusPrompted: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
)

/**
 * The ID vault screen's one state (ADR 0076). Every call goes through [VaultController], the
 * controller `show_vault_item` also uses. **A revealed value lives only in [VaultUiState.shown]**:
 * hidden after [SHOW_MS], and wiped by [hide] when the activity stops, so it never outlives the
 * screen it was revealed on. Nothing here logs.
 */
@Suppress("TooManyFunctions") // one function per screen action, the same shape as the other screens' ViewModels
class VaultViewModel(
    application: Application,
    private val controller: VaultController,
    private val now: () -> Long = System::currentTimeMillis,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(application, VaultController.forContext(application))

    private val _state = MutableStateFlow(VaultUiState())
    val state: StateFlow<VaultUiState> = _state.asStateFlow()

    private var focusLabel: String? = null
    private var hideJob: Job? = null

    init {
        refresh()
    }

    /** The label `show_vault_item` asked to open; matched once the list is read. */
    fun focus(label: String?) {
        if (label.isNullOrBlank() || label == focusLabel) return
        focusLabel = label
        _state.update { it.copy(focusPrompted = false) }
        matchFocus()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(view = VaultView.Loading) }
            val view = when (val outcome = controller.list()) {
                is VaultOutcome.Ok -> VaultView.Listed(outcome.value)
                else -> VaultView.Unavailable(VaultController.failed(outcome, "read"))
            }
            _state.update { it.copy(view = view) }
            matchFocus()
        }
    }

    private fun matchFocus() {
        val items = (_state.value.view as? VaultView.Listed)?.items ?: return
        val label = focusLabel ?: return
        _state.update { it.copy(focusId = VaultController.match(items, label)?.id) }
    }

    fun focusPromptShown() = _state.update { it.copy(focusPrompted = true) }

    /** The fingerprint passed: ask the engine for the value and show it. */
    fun reveal(item: VaultItem, unlock: BiometricUnlock) {
        busy {
            when (val outcome = controller.reveal(item, unlock)) {
                is VaultOutcome.Ok -> show(item.id, outcome.value.secret)
                else -> _state.update { it.copy(message = VaultController.failed(outcome, "revealed")) }
            }
        }
    }

    /** The prompt was cancelled or could not be shown. */
    fun say(sentence: String) = _state.update { it.copy(message = sentence) }

    /** Hides and wipes the value on screen. Called by the activity's onStop and by the Hide button. */
    fun hide() {
        hideJob?.cancel()
        _state.value.shown?.secret?.wipe()
        _state.update { it.copy(shown = null) }
    }

    fun openAdd() = _state.update { it.copy(form = VaultForm(), message = null) }

    fun openEdit(item: VaultItem) =
        _state.update {
            it.copy(form = VaultForm(editing = item, label = item.label, kind = item.kind), message = null)
        }

    fun editForm(transform: (VaultForm) -> VaultForm) =
        _state.update { s -> s.copy(form = s.form?.let(transform)?.copy(error = null)) }

    fun closeForm() = _state.update { it.copy(form = null) }

    /** Saves the form. A blank value on an edit keeps the stored value. The form stays, with why, on failure. */
    fun saveForm() {
        val form = _state.value.form ?: return
        busy {
            val typed = form.value.trim()
            val editing = form.editing
            val outcome = if (editing == null) {
                controller.add(form.label, form.kind, VaultSecret(typed))
            } else {
                controller.edit(editing, form.label, form.kind, typed.takeIf { it.isNotEmpty() }?.let(::VaultSecret))
            }
            when (outcome) {
                is VaultOutcome.Ok -> {
                    _state.update { it.copy(form = null, message = "Saved ${outcome.value.label}.") }
                    refresh()
                }
                else -> _state.update { it.copy(form = form.copy(error = VaultController.failed(outcome, "saved"))) }
            }
        }
    }

    fun askDelete(item: VaultItem) = _state.update { it.copy(confirmDelete = item, message = null) }

    fun cancelDelete() = _state.update { it.copy(confirmDelete = null) }

    fun confirmDelete() {
        val item = _state.value.confirmDelete ?: return
        busy {
            val message = when (val outcome = controller.delete(item)) {
                is VaultOutcome.Ok -> "Deleted ${item.label} for good."
                else -> VaultController.failed(outcome, "deleted")
            }
            if (_state.value.shown?.itemId == item.id) hide()
            _state.update { it.copy(confirmDelete = null, message = message) }
            refresh()
        }
    }

    private fun show(itemId: String, secret: VaultSecret) {
        hide()
        _state.update { it.copy(shown = Shown(itemId, secret, now() + SHOW_MS), message = null) }
        hideJob = viewModelScope.launch {
            delay(SHOW_MS)
            hide()
        }
    }

    private fun busy(block: suspend () -> Unit) {
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

    override fun onCleared() {
        hide()
        super.onCleared()
    }

    companion object {
        /** How long a revealed value stays on screen before it hides itself. */
        const val SHOW_MS = 60_000L
    }
}
