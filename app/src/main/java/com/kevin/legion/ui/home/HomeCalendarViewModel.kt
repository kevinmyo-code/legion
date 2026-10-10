package com.kevin.legion.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kevin.legion.ui.notes.InboxRowView
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 *
 * **Nothing here works on the main thread (2026-10-09, HOME speed pass).** Source reads run on [io];
 * everything derived from a read (the inbox merge, the markers, the agenda, the grid cells) runs on
 * [default]; Main only swaps the finished result into the state. A month is read ONCE (one query for
 * its events, tasks and suggestions) and a day inside it is derived from memory.
 */
// One method per user action on the calendar; a fifth of them would not be clearer in a second class.
@Suppress("TooManyFunctions") // see the line above
class HomeCalendarViewModel(
    private val source: HomeCalendarSource,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val zone: ZoneId = ZoneId.systemDefault(),
    scope: CoroutineScope? = null,
    /** Where [source] reads run (Room, files). Injected so a test can see the hop. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Where everything derived from a read runs. Never Main. */
    private val default: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val scope: CoroutineScope = scope ?: viewModelScope

    // The grid shell is part of the very first state (month header, 42 empty cells), so the first
    // frame draws the calendar's frame at once and the dots and agenda fill in when the read lands.
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<HomeCalendarUiState> = _state.asStateFlow()

    /** One month's read with everything derived from it. Kept so a day change inside the month
     * derives its agenda from memory with NO query, and so swiping back to a month shows its dots
     * at once while the fresh read runs. */
    private class Loaded(val read: MonthRead, val inbox: List<InboxRowView>, val markers: MonthMarkers)

    private val loadedMonths = object : LinkedHashMap<YearMonth, Loaded>(CACHED_MONTHS, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<YearMonth, Loaded>?) = size > CACHED_MONTHS
    }

    // Loads are requests, not coroutines: every trigger (resume, sheet close, a tick, a month swipe)
    // sets what it wants re-read and wakes ONE worker, so a burst of triggers is one read, not N
    // reads fighting for the database's connection pool. Only touched from the scope's thread.
    private var pendingMonth = false
    private var pendingPanels = false
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        this.scope.launch {
            for (ignored in wake) {
                val month = pendingMonth
                val panels = pendingPanels
                pendingMonth = false
                pendingPanels = false
                if (month) loadMonth()
                if (panels) loadPanels()
            }
        }
    }

    private fun request(month: Boolean = false, panels: Boolean = false) {
        pendingMonth = pendingMonth || month
        pendingPanels = pendingPanels || panels
        wake.trySend(Unit)
    }

    private fun initialState(): HomeCalendarUiState {
        val t = today()
        val month = YearMonth.from(t)
        return HomeCalendarUiState(
            today = t,
            month = month,
            selectedDay = t,
            cells = buildHomeCells(month, t, t, emptyMap()),
        )
    }

    /** `ON_RESUME`: re-reads everything, keeping the month and day the user is looking at - except
     * that a new calendar day moves `today`. */
    fun refresh() {
        val t = today()
        _state.update { it.copy(today = t) }
        request(month = true, panels = true)
    }

    fun selectDay(day: LocalDate) {
        if (YearMonth.from(day) != _state.value.month) return
        _state.update { it.copy(selectedDay = day) }
        // The agenda comes from the month already in memory: no query.
        scope.launch { publish() }
    }

    fun showMonth(month: YearMonth) {
        val current = _state.value
        // The selection follows the month: today when it is this month, else the 1st.
        val day = if (YearMonth.from(current.today) == month) current.today else month.atDay(1)
        _state.update { it.copy(month = month, selectedDay = day) }
        // A month seen before shows its dots and agenda at once; a new one shows the empty grid.
        scope.launch { publish() }
        request(month = true)
    }

    fun previousMonth() = showMonth(_state.value.month.minusMonths(1))

    fun nextMonth() = showMonth(_state.value.month.plusMonths(1))

    fun openSheet(sheet: PanelSheet) {
        _state.update { it.copy(sheet = sheet) }
        request(panels = true)
    }

    fun closeSheet() {
        _state.update { it.copy(sheet = null) }
        // The sheet may have changed things the grid and panel show.
        request(month = true, panels = true)
    }

    /** Ticks or unticks through the existing controller. A refusal stays on screen in words, and the
     * row keeps the state the controller left it in. */
    fun setTodoDone(todo: TodoRowUi, done: Boolean) {
        scope.launch {
            val ok = guarded { withContext(io) { source.setTodoDone(todo.ref, done) } } ?: false
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
            request(month = true)
        }
    }

    fun addIdeaToPlans(idea: IdeaRowUi) = actOnIdea(idea) { source.addIdeaToPlans(idea.id) }

    fun dropIdea(idea: IdeaRowUi) = actOnIdea(idea) { source.dropIdea(idea.id) }

    private fun actOnIdea(idea: IdeaRowUi, act: suspend () -> String) {
        scope.launch {
            val sentence = guarded { withContext(io) { act() } }
                ?: "Couldn't change ${idea.title}. Nothing was changed."
            _state.update { s ->
                s.copy(ideas = s.ideas.map { if (it.id == idea.id) it.copy(outcome = sentence) else it })
            }
            request(month = true)
        }
    }

    // ------------------------------------------------------------------------------------ reads

    private suspend fun loadMonth() {
        val month = _state.value.month
        val failed = MonthRead(month, emptyList(), emptyList(), listOf("the month"))
        val read = guarded { withContext(io) { source.readMonth(month, zone) } } ?: failed
        // A month the user has already left must not overwrite the one now showing.
        if (_state.value.month != month) return
        val loaded = guarded {
            withContext(default) {
                val inbox = inboxOf(read, now())
                Loaded(read, inbox, deriveMonthMarkers(read, inbox, zone))
            }
        } ?: Loaded(failed, emptyList(), MonthMarkers(emptyMap(), failed.unreadable))
        loadedMonths[month] = loaded
        publish()
    }

    /**
     * Builds the grid cells and the selected day's agenda from the month in memory and puts them in
     * the state. Every change of the month, the selection or the data calls this; a result that was
     * overtaken (the selection moved, a newer read landed) is dropped, because whatever overtook it
     * published too.
     */
    private suspend fun publish() {
        val s = _state.value
        val loaded = loadedMonths[s.month]
        if (loaded == null) {
            // Nothing read for this month yet: the empty grid shell, not the previous month's cells.
            _state.update { cur ->
                if (cur.month != s.month) {
                    cur
                } else {
                    cur.copy(
                        cells = buildHomeCells(cur.month, cur.today, cur.selectedDay, emptyMap()),
                        dayRows = emptyList(),
                    )
                }
            }
            return
        }
        val built = guarded {
            withContext(default) {
                buildHomeCells(s.month, s.today, s.selectedDay, loaded.markers.byDay) to
                    deriveDay(loaded.read, loaded.inbox, s.selectedDay, zone)
            }
        }
        val cur = _state.value
        val overtaken = cur.month != s.month || cur.selectedDay != s.selectedDay || cur.today != s.today ||
            loadedMonths[s.month] !== loaded
        if (overtaken) return
        val note = readNote(loaded.read.unreadable, built?.second?.unreadable ?: listOf("this day"))
        _state.update {
            it.copy(
                cells = built?.first ?: it.cells,
                dayRows = built?.second?.rows.orEmpty(),
                readNote = note,
                loading = false,
            )
        }
    }

    private suspend fun loadPanels() {
        val t = _state.value.today
        val (from, to) = weekendWindow(t)
        // One hop to the IO pool for the three panel reads, one after the other.
        val (todos, lists, ideas) = withContext(io) {
            Triple(
                guarded { source.openTodos(zone) },
                guarded { source.lists() },
                guarded { source.ideas(from, to, zone) },
            )
        }
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

// Previous, current and next month, plus one: enough for a swipe back and forth without a blink.
private const val CACHED_MONTHS = 4
private const val LOAD_FACTOR = 0.75f

/** One sentence naming what could not be read, or null when everything was. */
internal fun readNote(month: List<String>, day: List<String>): String? {
    val kinds = (month + day).distinct()
    return if (kinds.isEmpty()) null else "Couldn't read ${kinds.joinToString(", ")}. What is shown may be incomplete."
}
