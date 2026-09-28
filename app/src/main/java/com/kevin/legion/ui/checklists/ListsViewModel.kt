package com.kevin.legion.ui.checklists

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kevin.legion.backend.ChecklistsOutboxDrain
import com.kevin.legion.checklists.ChecklistController
import com.kevin.legion.checklists.ChecklistController.ChecklistItemsResult
import com.kevin.legion.checklists.ChecklistController.TickOutcome
import com.kevin.legion.checklists.checklistScheduleLabel
import com.kevin.legion.data.local.Checklist
import com.kevin.legion.data.local.ChecklistItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How far back [openHistory] looks - unchanged from the pre-04 screen's own constant. */
private const val HISTORY_WINDOW_DAYS = 30

/**
 * Home-launcher ticket 04's own `ListsViewModel` - the Lists page, one open list, and that list's
 * history, as ONE [StateFlow], per the ticket's own brief ("page state, the open list's state,
 * session-only UI flags"). [refresh] re-reads whichever of the three [ListsMode] is current, and
 * every write below calls it afterward - matching the app's first `AndroidViewModel`
 * (CLAUDE.md §8's Hilt/ViewModel architecture note; this is the first screen actually built on it).
 *
 * **Every write is a [ChecklistController] call - no DAO reference lives in this class.** This
 * mirrors the pre-04 screen's own doc comment: this object owns no re-derivation of a rule the
 * controller already decided.
 */
class ListsViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<Application>()

    private val _state = MutableStateFlow(ListsUiState())
    val state: StateFlow<ListsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Re-reads whichever of [ListsMode] is current - called from `ON_RESUME` and after every
     * write, per the ticket's own brief. */
    fun refresh() {
        viewModelScope.launch {
            when (val mode = _state.value.mode) {
                is ListsMode.Page -> loadPage()
                is ListsMode.Detail -> loadDetail(mode.checklistId)
                is ListsMode.History -> loadHistory(mode.checklistId)
            }
        }
    }

    // ---------------------------------------------------------------------------------- page

    private suspend fun loadPage() {
        val today = ChecklistController.today()
        val all = ChecklistController.allChecklists(app, includeArchived = true)
        val applyingToday = ChecklistController.checklistsForDay(app, today, includeArchived = true)
            .map { it.id }.toSet()
        val cards = all.map { checklist -> buildCard(checklist, today, applyingToday) }
        val live = cards.filter { !it.checklist.archived }
        _state.update {
            it.copy(
                page = it.page.copy(
                    loading = false,
                    routines = live.filter { c -> c.checklist.scheduleKind != null },
                    plainLists = live.filter { c -> c.checklist.scheduleKind == null },
                    archivedLists = cards.filter { c -> c.checklist.archived },
                ),
            )
        }
    }

    private suspend fun buildCard(checklist: Checklist, today: Int, applyingToday: Set<Long>): ListCardUi {
        val isRoutine = checklist.scheduleKind != null
        val appliesToday = !isRoutine || checklist.id in applyingToday
        val progress = if (isRoutine && !appliesToday) {
            // Not today - nothing to fetch, there is no day-scoped state to show.
            listProgress(isRoutine = true, appliesToday = false, items = emptyList(), loadFailed = false)
        } else {
            when (val result = ChecklistController.itemsWithTickState(app, checklist.id, today)) {
                is ChecklistItemsResult.Loaded -> listProgress(isRoutine, appliesToday = true, result.items, loadFailed = false)
                is ChecklistItemsResult.Failed -> listProgress(isRoutine, appliesToday = true, emptyList(), loadFailed = true)
            }
        }
        return ListCardUi(checklist = checklist, visual = listVisual(checklist.name, checklist.id), progress = progress)
    }

    fun toggleShowArchived() {
        _state.update { it.copy(page = it.page.copy(showArchived = !it.page.showArchived)) }
        refresh()
    }

    fun showCreateDialog(show: Boolean) {
        _state.update { it.copy(page = it.page.copy(showCreateDialog = show)) }
    }

    fun createList(name: String, scheduleKind: String?, scheduleDaysOfWeek: String?) {
        viewModelScope.launch {
            val created = ChecklistController.createChecklist(
                app,
                name,
                scheduleKind = scheduleKind,
                scheduleEvery = if (scheduleKind != null) 1 else null,
                scheduleDaysOfWeek = scheduleDaysOfWeek,
            )
            _state.update { it.copy(page = it.page.copy(showCreateDialog = false)) }
            openList(created.id)
        }
    }

    // -------------------------------------------------------------------------------- detail

    fun openList(checklistId: Long) {
        _state.update { it.copy(mode = ListsMode.Detail(checklistId), detail = ListDetailState()) }
        refresh()
    }

    fun backToPage() {
        _state.update { it.copy(mode = ListsMode.Page, detail = null, history = null) }
        refresh()
    }

    private suspend fun loadDetail(checklistId: Long) {
        val checklist = ChecklistController.getChecklist(app, checklistId)
        if (checklist == null) {
            // Deleted from underneath (this class's own delete button below) - back out rather
            // than keep rendering a detail view for a row that is gone.
            backToPage()
            return
        }
        val today = ChecklistController.today()
        val appliesToday = checklist.scheduleKind == null ||
            ChecklistController.checklistsForDay(app, today, includeArchived = true).any { it.id == checklistId }
        when (val result = ChecklistController.itemsWithTickState(app, checklistId, today)) {
            is ChecklistItemsResult.Loaded -> {
                // Audit finding 3: a lookup scoped to ONLY [today] missed a plain-list item whose
                // live tick sits on an earlier [ChecklistController.ItemState.tickDay] - the badge
                // silently vanished for exactly that row. One lookup per DISTINCT day this list's
                // own rows actually carry, [today] always included (a fresh tick/untick this
                // session lands on today, whatever day the item's PRIOR tick was on).
                val queued = queuedIdsForRows(app, result.items.map { it.tickDay }, today)
                val rows = result.items
                    .sortedBy { it.item.sortOrder }
                    .map { st -> ListItemUi(st.item, st.ticked, st.value, st.tickDay, queued = st.item.id in queued) }
                val (ticked, unticked) = rows.partition { it.ticked }
                _state.update {
                    it.copy(
                        detail = (it.detail ?: ListDetailState()).copy(
                            checklist = checklist,
                            loading = false,
                            loadFailed = false,
                            unticked = unticked,
                            ticked = ticked,
                            appliesToday = appliesToday,
                            scheduleLabel = checklistScheduleLabel(checklist),
                        ),
                    )
                }
            }
            is ChecklistItemsResult.Failed -> {
                _state.update {
                    it.copy(
                        detail = (it.detail ?: ListDetailState()).copy(
                            checklist = checklist,
                            loading = false,
                            loadFailed = true,
                        ),
                    )
                }
            }
        }
    }

    fun setDraft(text: String) {
        _state.update { it.copy(detail = it.detail?.copy(draft = text)) }
    }

    fun addItem() {
        viewModelScope.launch {
            val d = _state.value.detail ?: return@launch
            val checklistId = d.checklist?.id ?: return@launch
            val text = d.draft.trim()
            if (text.isBlank()) return@launch
            guardedWrite("add that item") {
                ChecklistController.addItem(app, checklistId, text, sortOrder = d.unticked.size + d.ticked.size)
                _state.update { it.copy(detail = it.detail?.copy(draft = "")) }
                refresh()
            }
        }
    }

    fun setInputValue(itemId: Long, value: String) {
        _state.update { it.copy(detail = it.detail?.copy(inputValues = it.detail.inputValues + (itemId to value))) }
    }

    /** Ticks a plain (unmeasured) item - [ChecklistItem.measureUnit] null. Always for TODAY (this
     * screen's own "opened" scope, never an arbitrary day). */
    fun tick(itemId: Long) = applyTickOutcome(itemId, value = null)

    /** Ticks a measured item using whatever is currently typed into [ListDetailState.inputValues]
     * for [itemId] - an empty or unparseable field is passed straight through as `null`, so
     * [ChecklistController.tick] itself refuses the write (Kevin's own "a number is the point"
     * ruling) rather than this class pre-validating it away. */
    fun tickMeasured(itemId: Long) {
        val raw = _state.value.detail?.inputValues?.get(itemId).orEmpty()
        applyTickOutcome(itemId, value = raw.trim().toDoubleOrNull())
    }

    private fun applyTickOutcome(itemId: Long, value: Double?) {
        viewModelScope.launch {
            guardedWrite("record that tick") {
                val day = ChecklistController.today()
                when (val outcome = ChecklistController.tick(app, itemId, day, value = value)) {
                    is TickOutcome.Ticked ->
                        _state.update {
                            it.copy(
                                detail = it.detail?.copy(
                                    refusals = it.detail.refusals - itemId,
                                    inputValues = it.detail.inputValues - itemId,
                                ),
                            )
                        }
                    is TickOutcome.Refused ->
                        _state.update { it.copy(detail = it.detail?.copy(refusals = it.detail.refusals + (itemId to outcome.message))) }
                }
                refresh()
            }
        }
    }

    /** Unticks [itemId] against [tickDay] - the day the tick actually lives on
     * ([ChecklistController.ItemState.tickDay]'s own doc comment), never
     * [ChecklistController.today] blindly. This is the untick-trap fix ticket 04 names: a plain
     * item ticked yesterday has no `(item, today)` row to clear, only a `(item, tickDay)` one. */
    fun untick(itemId: Long, tickDay: Int) {
        viewModelScope.launch {
            guardedWrite("untick that item") {
                ChecklistController.untick(app, itemId, tickDay)
                refresh()
            }
        }
    }

    fun toggleShowTicked() {
        _state.update { it.copy(detail = it.detail?.copy(showTicked = it.detail.showTicked.not())) }
    }

    fun startEdit(item: ChecklistItem) {
        _state.update { it.copy(detail = it.detail?.copy(editingItem = item, longPressItem = null)) }
    }

    fun dismissEdit() {
        _state.update { it.copy(detail = it.detail?.copy(editingItem = null)) }
    }

    fun saveEdit(text: String, unit: String?, target: Double?, direction: String?) {
        viewModelScope.launch {
            val item = _state.value.detail?.editingItem ?: return@launch
            ChecklistController.editItem(app, item.id, text)
            ChecklistController.setMeasure(app, item.id, unit, target, direction)
            _state.update { it.copy(detail = it.detail?.copy(editingItem = null)) }
            refresh()
        }
    }

    fun longPress(item: ChecklistItem) {
        _state.update { it.copy(detail = it.detail?.copy(longPressItem = item)) }
    }

    fun dismissLongPress() {
        _state.update { it.copy(detail = it.detail?.copy(longPressItem = null)) }
    }

    /** Adjacent [ChecklistItem.sortOrder] swap over EVERY live item (ticked and unticked alike,
     * ordered by [ChecklistItem.sortOrder]) - unchanged semantics from the pre-04 screen, just
     * read off the combined [ListDetailState.unticked]/[ListDetailState.ticked] rows rather than a
     * flat `checklistItems` list. */
    fun moveUp(itemId: Long) = reorder(itemId, delta = -1)
    fun moveDown(itemId: Long) = reorder(itemId, delta = 1)

    private fun reorder(itemId: Long, delta: Int) {
        viewModelScope.launch {
            val d = _state.value.detail ?: return@launch
            val ordered = (d.unticked + d.ticked).map { it.item }.sortedBy { it.sortOrder }
            val index = ordered.indexOfFirst { it.id == itemId }
            val neighborIndex = index + delta
            if (index < 0 || neighborIndex < 0 || neighborIndex >= ordered.size) return@launch
            val current = ordered[index]
            val neighbor = ordered[neighborIndex]
            guardedWrite("reorder that item") {
                ChecklistController.reorderItem(app, current.id, neighbor.sortOrder)
                ChecklistController.reorderItem(app, neighbor.id, current.sortOrder)
                _state.update { it.copy(detail = it.detail?.copy(longPressItem = null)) }
                refresh()
            }
        }
    }

    fun deleteItem(itemId: Long) {
        viewModelScope.launch {
            ChecklistController.deleteItem(app, itemId)
            _state.update { it.copy(detail = it.detail?.copy(longPressItem = null)) }
            refresh()
        }
    }

    fun showRenameDialog(show: Boolean) {
        _state.update { it.copy(detail = it.detail?.copy(showRenameDialog = show, showOverflowMenu = false)) }
    }

    fun rename(text: String) {
        viewModelScope.launch {
            val id = _state.value.detail?.checklist?.id ?: return@launch
            guardedWrite("rename the list") {
                ChecklistController.renameChecklist(app, id, text)
                _state.update { it.copy(detail = it.detail?.copy(showRenameDialog = false)) }
                refresh()
            }
        }
    }

    fun showSchedulePicker(show: Boolean) {
        _state.update { it.copy(detail = it.detail?.copy(showSchedulePicker = show, showOverflowMenu = false)) }
    }

    fun setSchedule(scheduleKind: String?, scheduleDaysOfWeek: String?) {
        viewModelScope.launch {
            val id = _state.value.detail?.checklist?.id ?: return@launch
            ChecklistController.setSchedule(app, id, scheduleKind, if (scheduleKind != null) 1 else null, scheduleDaysOfWeek)
            _state.update { it.copy(detail = it.detail?.copy(showSchedulePicker = false)) }
            refresh()
        }
    }

    fun toggleOverflowMenu(show: Boolean) {
        _state.update { it.copy(detail = it.detail?.copy(showOverflowMenu = show)) }
    }

    fun archiveToggle() {
        viewModelScope.launch {
            val checklist = _state.value.detail?.checklist ?: return@launch
            guardedWrite("archive that list") {
                if (checklist.archived) {
                    ChecklistController.unarchiveChecklist(app, checklist.id)
                } else {
                    ChecklistController.archiveChecklist(app, checklist.id)
                }
                _state.update { it.copy(detail = it.detail?.copy(showOverflowMenu = false)) }
                refresh()
            }
        }
    }

    fun showDeleteConfirm(show: Boolean) {
        _state.update { it.copy(detail = it.detail?.copy(showDeleteConfirm = show, showOverflowMenu = false)) }
    }

    /** **Delete always confirms now** - the ticket's own fix: the old DELETE LIST stamp deleted on
     * one tap, this requires [showDeleteConfirm] to have been set first by the caller's own
     * confirm dialog. Soft delete via [ChecklistController.deleteChecklist], then back to the page. */
    fun confirmDelete() {
        viewModelScope.launch {
            val id = _state.value.detail?.checklist?.id ?: return@launch
            guardedWrite("delete that list") {
                ChecklistController.deleteChecklist(app, id)
                backToPage()
            }
        }
    }

    // ------------------------------------------------------------------------------- history

    fun openHistory() {
        val d = _state.value.detail ?: return
        val id = d.checklist?.id ?: return
        _state.update {
            it.copy(mode = ListsMode.History(id), history = ListHistoryState(checklistName = d.checklist.name, loading = true))
        }
        refresh()
    }

    fun backFromHistory() {
        val id = (_state.value.mode as? ListsMode.History)?.checklistId ?: return
        _state.update { it.copy(mode = ListsMode.Detail(id)) }
        refresh()
    }

    private suspend fun loadHistory(checklistId: Long) {
        val checklist = ChecklistController.getChecklist(app, checklistId)
        val toDay = ChecklistController.today()
        val fromDay = toDay - HISTORY_WINDOW_DAYS
        val lines = ChecklistController.checklistHistory(app, checklistId, fromDay, toDay)
        _state.update {
            it.copy(
                history = ListHistoryState(
                    checklistName = checklist?.name ?: it.history?.checklistName.orEmpty(),
                    loading = false,
                    lines = lines,
                ),
            )
        }
    }

    // ------------------------------------------------------------------------ write guard (finding 6)

    /**
     * Runs a [ChecklistController] write [block] wrapped so a thrown call leaves this screen alive
     * (audit finding 6) - before this, tick/untick/addItem/rename/archiveToggle/confirmDelete/
     * reorder had no guard at all, so a thrown controller call would crash whatever screen was
     * open (ADR 0050's "HOME must never crash" extends to every screen it can launch from). A
     * thrown [block] sets [ListDetailState.writeError] to a one-line sentence naming what did NOT
     * happen, rendered on the screen itself, never a toast. [actionName] is a short verb phrase
     * ("add that item"), not a full sentence - the sentence is built here so every caller reads the
     * same shape.
     */
    private suspend fun guardedWrite(actionName: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            _state.update {
                it.copy(detail = it.detail?.copy(writeError = "Couldn't $actionName - ${e.message ?: "unknown error"}."))
            }
        }
    }
}

/**
 * Which of [items]' own [ChecklistController.ItemState.tickDay] values (plus [today], always) have
 * a tick/untick still queued - audit finding 3. [ChecklistsOutboxDrain.queuedItemIdsForDay] is
 * scoped to ONE day, so a lookup that only ever asked about [today] was invisible to a plain
 * list's tick made on an EARLIER day: [ChecklistController.itemsWithTickState] says such an item
 * is ticked (any live tick, any day), but its "Not synced yet" badge lives on the outbox entry for
 * its OWN [ChecklistController.ItemState.tickDay], not for today. One lookup per distinct day
 * actually present among [items], unioned - an item's tick is queued for its own tick day
 * regardless of what day happens to be "today" when this list is opened.
 */
internal suspend fun queuedIdsForRows(
    context: Context,
    tickDays: Iterable<Int?>,
    today: Int,
): Set<Long> {
    val days = tickDays.filterNotNull().toMutableSet()
    days += today
    return days.flatMap { day -> ChecklistsOutboxDrain.queuedItemIdsForDay(context, day) }.toSet()
}
