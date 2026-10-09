package com.kevin.legion.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The ViewModel behind HOME's calendar (one-home ticket 11): one [StateFlow] of
 * [HomeCalendarUiState], constructor-injected [source] and [today] (CLAUDE.md sec 8).
 *
 * Hilt is not in this build yet (sec 8's migration order, step 2), so [Factory] is the injection
 * point until it lands; the constructor is already the shape Hilt wants.
 *
 * **HOME must never crash (ADR 0050).** Every read is guarded on its own and a failure becomes a
 * sentence in the state (`readNote`, `todosNote`...), never an empty-looking panel. [scope] is
 * `viewModelScope` in the app; a test passes its own so no Main dispatcher is needed.
 */
// One method per user action on the calendar; a fifth of them would not be clearer in a second class.
@Suppress("TooManyFunctions") // see the line above
class HomeCalendarViewModel(
    private val source: HomeCalendarSource,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val zone: ZoneId = ZoneId.systemDefault(),
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = scope ?: viewModelScope

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<HomeCalendarUiState> = _state.asStateFlow()

    // The last read of the displayed month; the grid is re-derived from it whenever the selection moves.
    private var markers = MonthMarkers(emptyMap())

    private fun initialState(): HomeCalendarUiState {
        val t = today()
        return HomeCalendarUiState(today = t, month = YearMonth.from(t), selectedDay = t)
    }

    /** `ON_RESUME`: re-reads everything, keeping the month and day the user is looking at - except
     * that a new calendar day moves `today`. */
    fun refresh() {
        val t = today()
        _state.update { it.copy(today = t) }
        scope.launch {
            loadMonth()
            loadDay()
            loadPanels()
        }
    }

    fun selectDay(day: LocalDate) {
        if (YearMonth.from(day) != _state.value.month) return
        _state.update { it.copy(selectedDay = day) }
        rebuildCells()
        scope.launch { loadDay() }
    }

    fun showMonth(month: YearMonth) {
        val current = _state.value
        // The selection follows the month: today when it is this month, else the 1st.
        val day = if (YearMonth.from(current.today) == month) current.today else month.atDay(1)
        _state.update { it.copy(month = month, selectedDay = day) }
        scope.launch {
            loadMonth()
            loadDay()
        }
    }

    fun previousMonth() = showMonth(_state.value.month.minusMonths(1))

    fun nextMonth() = showMonth(_state.value.month.plusMonths(1))

    fun openSheet(sheet: PanelSheet) {
        _state.update { it.copy(sheet = sheet) }
        scope.launch { loadPanels() }
    }

    fun closeSheet() {
        _state.update { it.copy(sheet = null) }
        // The sheet may have changed things the grid and panel show.
        scope.launch {
            loadMonth()
            loadDay()
            loadPanels()
        }
    }

    /** Ticks or unticks through the existing controller. A refusal stays on screen in words, and the
     * row keeps the state the controller left it in. */
    fun setTodoDone(todo: TodoRowUi, done: Boolean) {
        scope.launch {
            val ok = guarded { source.setTodoDone(todo.ref, done) } ?: false
            if (!ok) {
                val verb = if (done) "tick" else "untick"
                _state.update { it.copy(todosNote = "Couldn't $verb ${todo.title}. Nothing was changed.") }
                return@launch
            }
            _state.update { s ->
                // Keep the row in the sheet with its new state until the sheet closes, so an
                // accidental tick can be undone from the same place.
                val todos = s.todos.map { if (it.key == todo.key) it.copy(done = done) else it }
                s.copy(
                    todos = todos,
                    openTodoCount = todos.count { !it.done },
                    todosNote = null,
                )
            }
            loadMonth()
            loadDay()
        }
    }

    fun addIdeaToPlans(idea: IdeaRowUi) = actOnIdea(idea) { source.addIdeaToPlans(idea.id) }

    fun dropIdea(idea: IdeaRowUi) = actOnIdea(idea) { source.dropIdea(idea.id) }

    private fun actOnIdea(idea: IdeaRowUi, act: suspend () -> String) {
        scope.launch {
            val sentence = guarded { act() } ?: "Couldn't change ${idea.title}. Nothing was changed."
            _state.update { s ->
                s.copy(ideas = s.ideas.map { if (it.id == idea.id) it.copy(outcome = sentence) else it })
            }
            loadMonth()
            loadDay()
        }
    }

    // ------------------------------------------------------------------------------------ reads

    private suspend fun loadMonth() {
        val month = _state.value.month
        val read = guarded { source.monthMarkers(month, zone) }
        // A month the user has already left must not overwrite the one now showing.
        if (_state.value.month != month) return
        markers = read ?: MonthMarkers(emptyMap(), listOf("the month"))
        rebuildCells()
    }

    private fun rebuildCells() {
        _state.update { s ->
            s.copy(
                cells = buildHomeCells(s.month, s.today, s.selectedDay, markers.byDay),
                readNote = readNote(markers.unreadable, dayUnreadable),
                loading = false,
            )
        }
    }

    private var dayUnreadable: List<String> = emptyList()

    private suspend fun loadDay() {
        val day = _state.value.selectedDay
        val read = guarded { source.dayRows(day, zone) }
        if (_state.value.selectedDay != day) return
        dayUnreadable = read?.unreadable ?: listOf("this day")
        _state.update { s ->
            s.copy(dayRows = read?.rows.orEmpty(), readNote = readNote(markers.unreadable, dayUnreadable))
        }
    }

    private suspend fun loadPanels() {
        val t = _state.value.today
        val (from, to) = weekendWindow(t)
        val todos = guarded { source.openTodos(zone) }
        val lists = guarded { source.lists() }
        val ideas = guarded { source.ideas(from, to, zone) }
        _state.update { s ->
            // A row ticked inside the open sheet stays listed (done) until the sheet closes.
            val keptDone = if (s.sheet == PanelSheet.TODOS) {
                s.todos.filter { it.done && todos.orEmpty().none { fresh -> fresh.key == it.key } }
            } else {
                emptyList()
            }
            val merged = todos.orEmpty() + keptDone
            s.copy(
                todos = merged,
                openTodoCount = merged.count { !it.done },
                todosNote = if (todos == null) "Couldn't read your to-dos." else null,
                lists = lists.orEmpty(),
                listsNote = if (lists == null) "Couldn't read your lists." else null,
                ideas = ideas.orEmpty(),
                ideasNote = if (ideas == null) "Couldn't read this weekend's ideas." else null,
            )
        }
    }

    // ADR 0050: HOME must never crash; the caller words the null.
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // see the line above
    private suspend fun <T> guarded(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST") // the only type this factory is asked for
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeCalendarViewModel(ContextHomeCalendarSource(appContext)) as T
    }
}

/** One sentence naming what could not be read, or null when everything was. */
internal fun readNote(month: List<String>, day: List<String>): String? {
    val kinds = (month + day).distinct()
    return if (kinds.isEmpty()) null else "Couldn't read ${kinds.joinToString(", ")}. What is shown may be incomplete."
}
