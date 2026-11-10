package com.kevin.legion.ui.conditions

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kevin.legion.conditions.ConditionsController
import com.kevin.legion.conditions.ConditionsOutcome
import com.kevin.legion.conditions.ConditionsPoint
import com.kevin.legion.conditions.ConditionsSection
import com.kevin.legion.location.LocationController
import com.kevin.legion.location.PlaceController
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One choice of where to check: "Here" (the phone's fix) or a saved place by label. */
data class ConditionsChoice(val label: String, val point: ConditionsPoint?)

/** The Conditions screen's whole state (round 5). [sections] come from the engine, worded. */
data class ConditionsUiState(
    val choices: List<ConditionsChoice> = emptyList(),
    val selected: String = HERE,
    val loading: Boolean = false,
    val sections: List<ConditionsSection> = emptyList(),
    /** A sentence when nothing could be read (offline, engine down, no fix); never blank on failure. */
    val problem: String? = null,
) {
    companion object {
        const val HERE = "Here"
    }
}

/**
 * The Conditions screen (round 5, conditions): the hands path to the engine's `check_conditions`,
 * through [ConditionsController], the same door the severe-alert watcher uses (ADR 0035).
 * Online only: with no engine or no signal it says nothing could be read, never "all clear".
 */
class ConditionsViewModel(
    application: Application,
    private val controller: ConditionsController,
    private val fix: () -> ConditionsPoint.Here?,
    private val savedLabels: suspend () -> List<String>,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        ConditionsController.forContext(application),
        { LocationController.state.value?.let { ConditionsPoint.Here(it.latitude, it.longitude) } },
        { PlaceController.all(application).filter { !it.deleted }.map { it.label } },
    )

    private val _state = MutableStateFlow(ConditionsUiState())
    val state: StateFlow<ConditionsUiState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            val here = ConditionsChoice(ConditionsUiState.HERE, fix())
            val places = runCatching { savedLabels() }.getOrDefault(emptyList())
                .map { ConditionsChoice(it, ConditionsPoint.Place(it)) }
            _state.update { it.copy(choices = listOf(here) + places) }
            refresh()
        }
    }

    fun select(label: String) {
        _state.update { it.copy(selected = label) }
        refresh()
    }

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            val chosen = _state.value.selected
            val point = if (chosen == ConditionsUiState.HERE) {
                fix()
            } else {
                _state.value.choices.firstOrNull { it.label == chosen }?.point
            }
            if (point == null) {
                _state.update {
                    it.copy(
                        loading = false,
                        sections = emptyList(),
                        problem = "No GPS fix yet, so nothing was checked here. Pick a saved place, or " +
                            "try again once the phone has a fix.",
                    )
                }
                return@launch
            }
            _state.update { it.copy(loading = true, problem = null) }
            when (val outcome = controller.check(point)) {
                is ConditionsOutcome.Read ->
                    _state.update { it.copy(loading = false, sections = outcome.sections, problem = null) }
                is ConditionsOutcome.Failed ->
                    _state.update { it.copy(loading = false, sections = emptyList(), problem = outcome.sentence) }
            }
        }
    }
}
