package com.kevin.legion.ui.dumb

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything the Matrix calendar draws. Three honest outcomes that never share a rendering:
 * [loading], [readFailed] (Room threw: say so, never show a clear week) and a successful read, which
 * may be empty. [notice] is the sentence from the last action, success or the failure that did NOT
 * happen. [form] is non-null while an event is being added or edited.
 */
internal data class MatrixCalendarUiState(
    val today: LocalDate,
    val loading: Boolean = true,
    val readFailed: Boolean = false,
    val rows: List<MatrixAgendaRow> = emptyList(),
    val notice: String? = null,
    val form: MatrixEventForm? = null,
)

/**
 * Matrix mode's calendar ViewModel (matrix-open ticket 04): today and the next seven days, add / edit /
 * delete an event, tick a task or reminder. All reads and writes go through [MatrixCalendarSource],
 * whose real implementation calls the same controllers the Soft calendar does, so no write is
 * implemented twice. [scope] and [io] are test seams.
 */
internal class MatrixCalendarViewModel(
    private val source: MatrixCalendarSource,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
    scope: CoroutineScope? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope

    private fun today(): LocalDate = Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()

    private val _state = MutableStateFlow(MatrixCalendarUiState(today = today()))
    val state: StateFlow<MatrixCalendarUiState> = _state.asStateFlow()

    /** Reads today and the next seven days again. The rows on screen stay until the read lands. */
    fun refresh() {
        val day = today()
        scope.launch {
            val read = withContext(io) {
                runCatching { source.agenda(day, day.plusDays(HORIZON_DAYS), zone) }
            }
            _state.update { current ->
                read.fold(
                    onSuccess = { current.copy(today = day, loading = false, readFailed = false, rows = it) },
                    onFailure = { current.copy(today = day, loading = false, readFailed = true) },
                )
            }
        }
    }

    /** A tap on a row: an event opens its form; a task or reminder is ticked or unticked. */
    fun tapRow(row: MatrixAgendaRow) {
        if (row.kind == MatrixAgendaKind.EVENT) {
            _state.update { it.copy(form = MatrixEventForm.of(row, zone), notice = null) }
            return
        }
        if (!row.tickable) {
            val why = "\"${row.title}\" repeats, so it can't be ticked off here. Nothing changed."
            _state.update { it.copy(notice = why) }
            return
        }
        scope.launch {
            val ok = withContext(io) { runCatching { source.setDone(row, !row.done) }.getOrDefault(false) }
            val verb = if (row.done) "unticked" else "ticked"
            _state.update {
                it.copy(
                    notice = if (ok) "\"${row.title}\" $verb." else "\"${row.title}\" was not $verb. Nothing changed.",
                )
            }
            if (ok) refresh()
        }
    }

    fun startAdd(day: LocalDate) = _state.update { it.copy(form = MatrixEventForm.blank(day), notice = null) }

    fun editForm(change: (MatrixEventForm) -> MatrixEventForm) =
        _state.update { s -> s.copy(form = s.form?.let { change(it).copy(error = null) }) }

    fun cancelForm() = _state.update { it.copy(form = null) }

    fun save() {
        val form = _state.value.form ?: return
        when (val parsed = form.parse(zone)) {
            is MatrixEventForm.Parsed.Bad -> _state.update { it.copy(form = form.copy(error = parsed.sentence)) }
            is MatrixEventForm.Parsed.Ok -> scope.launch {
                val ok = withContext(io) {
                    runCatching { source.saveEvent(form.id, parsed.title, parsed.times) }.getOrDefault(false)
                }
                if (ok) {
                    _state.update { it.copy(form = null, notice = "Saved \"${parsed.title}\".") }
                    refresh()
                } else {
                    _state.update {
                        it.copy(form = form.copy(error = "\"${parsed.title}\" was not saved. Nothing was changed."))
                    }
                }
            }
        }
    }

    /** First tap asks; the second ([confirmDelete]) deletes. */
    fun askDelete() = _state.update { s -> s.copy(form = s.form?.copy(confirmingDelete = true, error = null)) }

    fun confirmDelete() {
        val form = _state.value.form ?: return
        val id = form.id ?: return
        scope.launch {
            val ok = withContext(io) { runCatching { source.deleteEvent(id) }.getOrDefault(false) }
            if (ok) {
                _state.update { it.copy(form = null, notice = "Deleted \"${form.title}\".") }
                refresh()
            } else {
                _state.update {
                    it.copy(form = form.copy(error = "\"${form.title}\" was not deleted. Nothing was changed."))
                }
            }
        }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val app = context.applicationContext

        @Suppress("UNCHECKED_CAST") // the only type this factory is asked for
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MatrixCalendarViewModel(AndroidMatrixCalendarSource(app)) as T
    }

    private companion object {
        /** Today plus the next seven days. */
        const val HORIZON_DAYS = 8L
    }
}
