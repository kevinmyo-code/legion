package com.kevin.legion.ui.grades

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kevin.legion.grades.CourseGrade
import com.kevin.legion.grades.GradesBackend
import com.kevin.legion.grades.GradesOutcome
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What "what do I need" last said: the engine's sentence verbatim, or why there is none. */
sealed interface NeedResult {
    data class Answer(val words: String, val estimate: Boolean) : NeedResult
    data class Failed(val sentence: String) : NeedResult
}

/**
 * The Grades screen's one state (daily-loops ticket 03). [courses] is null until a read succeeds;
 * after that it is the LAST GOOD read, kept when a later read fails, and [error] then says in words
 * what did not load and [lastReadAt] when the data on screen was read. Unreadable is never empty.
 */
data class GradesUiState(
    val loading: Boolean = true,
    val courses: List<CourseGrade>? = null,
    /** When the last successful read finished, as the phone's clock said, formatted for display. */
    val lastReadAt: String? = null,
    val error: String? = null,
    val selectedCourseId: Long? = null,
    val needTarget: String = "",
    val needAssignment: String = "",
    val needGroup: String = "",
    val needOthersAt: String = "",
    val needBusy: Boolean = false,
    val need: NeedResult? = null,
) {
    val selected: CourseGrade? get() = courses?.firstOrNull { it.courseId == selectedCourseId }
}

/**
 * Over [GradesBackend], the `/api/grades/` routes the `canvas_grades` and `what_i_need` engine tools
 * also call through `ask_engine` (ADR 0035: this screen is their hands path). The phone computes no
 * grade; every figure and every "what do I need" sentence is the engine's.
 *
 * A plain [ViewModel] with the backend through the constructor, so a JVM test drives it with a fake
 * and no Robolectric. Hilt is not in the build yet (CLAUDE.md section 8 is the plan), so
 * [GradesScreen] supplies the backend through a ViewModel factory.
 */
class GradesViewModel(
    private val backend: GradesBackend,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val _state = MutableStateFlow(GradesUiState())
    val state: StateFlow<GradesUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            when (val out = backend.courses()) {
                is GradesOutcome.Ok -> _state.update {
                    it.copy(
                        loading = false,
                        courses = out.value,
                        lastReadAt = formatStamp(nowMs()),
                        error = null,
                        // A course that vanished from the engine must not leave a dead drilldown.
                        selectedCourseId = it.selectedCourseId
                            ?.takeIf { id -> out.value.any { c -> c.courseId == id } },
                    )
                }
                else -> _state.update { it.copy(loading = false, error = didNotLoad(out, it.lastReadAt)) }
            }
        }
    }

    fun select(courseId: Long?) = _state.update {
        it.copy(selectedCourseId = courseId, need = null, needBusy = false)
    }

    fun setTarget(text: String) = _state.update { it.copy(needTarget = text) }
    fun setAssignment(text: String) = _state.update { it.copy(needAssignment = text) }
    fun setGroup(text: String) = _state.update { it.copy(needGroup = text) }
    fun setOthersAt(text: String) = _state.update { it.copy(needOthersAt = text) }

    /** Asks the engine; a blank target goes too, and the engine's own 400 sentence comes back. */
    fun askNeed() {
        val s = _state.value
        val courseId = s.selectedCourseId ?: return
        viewModelScope.launch {
            _state.update { it.copy(needBusy = true, need = null) }
            val result = when (
                val out = backend.whatINeed(courseId, s.needTarget, s.needAssignment, s.needGroup, s.needOthersAt)
            ) {
                is GradesOutcome.Ok -> NeedResult.Answer(out.value.words, out.value.estimate)
                // The engine's own refusal (400, 403, 404) is already a sentence of what did not happen;
                // an unreachable or undecodable one gets that said in front of it.
                is GradesOutcome.Refused -> NeedResult.Failed(out.sentence)
                else -> NeedResult.Failed("Nothing was worked out. ${out.failureSentence.orEmpty()}")
            }
            _state.update { it.copy(needBusy = false, need = result) }
        }
    }

    private fun didNotLoad(out: GradesOutcome<*>, lastReadAt: String?): String {
        val why = out.failureSentence.orEmpty().trim()
        val kept = if (lastReadAt != null) {
            "Showing what was last read, at $lastReadAt."
        } else {
            "No grades have been read yet."
        }
        return "Grades did not load: $why $kept".replace("  ", " ")
    }

    private fun formatStamp(ms: Long): String = STAMP.format(Instant.ofEpochMilli(ms).atZone(zone))

    private companion object {
        /** 24-hour, as everything Typeset shows (ADR 0081). */
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
