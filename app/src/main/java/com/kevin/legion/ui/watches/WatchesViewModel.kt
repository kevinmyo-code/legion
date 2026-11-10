package com.kevin.legion.ui.watches

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kevin.legion.watches.KIND_CHARGE_OVER
import com.kevin.legion.watches.SOURCE_SCREEN
import com.kevin.legion.watches.Watch
import com.kevin.legion.watches.WatchFiring
import com.kevin.legion.watches.WatchKind
import com.kevin.legion.watches.WatchOutcome
import com.kevin.legion.watches.WatchWording
import com.kevin.legion.watches.WatchesController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the screen shows: the engine's answer, or why there is none (unreadable is not empty). */
sealed interface WatchesView {
    data object Loading : WatchesView
    data class Listed(val watches: List<Watch>, val kinds: List<WatchKind>, val found: List<WatchFiring>) : WatchesView
    data class Unavailable(val sentence: String) : WatchesView
}

data class WatchesUiState(
    val view: WatchesView = WatchesView.Loading,
    val message: String? = null,
    val busy: Boolean = false,
    val addKind: String = KIND_CHARGE_OVER,
    val addAmount: String = "",
    val addText: String = "",
)

/**
 * The Watches screen's state (ADR 0083), over [WatchesController], the controller the `watch` voice
 * tool calls (ADR 0035). Every sentence comes from [WatchWording], so the screen and the voice say
 * the same thing about the same outcome.
 */
class WatchesViewModel(
    application: Application,
    private val controller: WatchesController,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(application, WatchesController.forContext(application))

    private val _state = MutableStateFlow(WatchesUiState())
    val state: StateFlow<WatchesUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(view = WatchesView.Loading) }
            val view = when (val out = controller.list()) {
                is WatchOutcome.Ok -> {
                    val found = (controller.recent() as? WatchOutcome.Ok)?.value.orEmpty()
                    WatchesView.Listed(out.value.watches, out.value.kinds, found)
                }
                else -> WatchesView.Unavailable(WatchWording.failed(out, "read"))
            }
            _state.update { it.copy(view = view) }
        }
    }

    fun setAddKind(kind: String) = _state.update { it.copy(addKind = kind) }
    fun setAddAmount(text: String) = _state.update { it.copy(addAmount = text) }
    fun setAddText(text: String) = _state.update { it.copy(addText = text) }

    fun add() = act {
        val s = _state.value
        when (val out = controller.set(s.addKind, s.addAmount, s.addText, label = null, source = SOURCE_SCREEN)) {
            is WatchOutcome.Ok -> {
                _state.update { it.copy(addAmount = "", addText = "") }
                WatchWording.set(out.value)
            }
            else -> WatchWording.failed(out, "set")
        }
    }

    fun setPaused(watch: Watch, paused: Boolean) = act {
        when (val out = controller.pause(watch.id, paused)) {
            is WatchOutcome.Ok -> WatchWording.paused(out.value)
            else -> WatchWording.failed(out, "changed")
        }
    }

    fun remove(watch: Watch) = act {
        when (val out = controller.remove(watch.id)) {
            is WatchOutcome.Ok -> WatchWording.removed(watch.label)
            else -> WatchWording.failed(out, "changed")
        }
    }

    private fun act(block: suspend () -> String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val message = block()
            _state.update { it.copy(busy = false, message = message) }
            refresh()
        }
    }
}
