package com.kevin.legion.ui.bought

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kevin.legion.purchases.PurchaseFailures
import com.kevin.legion.purchases.PurchaseOutcome
import com.kevin.legion.purchases.PurchaseWording
import com.kevin.legion.purchases.PurchasesController
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How long typing pauses before the search runs, so a word being typed is one request, not six. */
private const val SEARCH_DEBOUNCE_MS = 300L

/**
 * The bought log's screen state as ONE [StateFlow] (CLAUDE.md section 8: a ViewModel per screen).
 * Every read and write is a [PurchasesController] call - the same controller the `bought_log` voice
 * tool calls (ADR 0035) - and this class owns no wording: every sentence comes from
 * [PurchaseWording] so the screen and the assistant say the same thing.
 *
 * **Online only (purchase-log ticket 04).** A read that fails becomes [BoughtView.Unavailable] with
 * the reason in words, never an empty list; a save that fails keeps the form as typed and says
 * nothing was logged. A save is only ever reported done from a 2xx.
 */
class BoughtViewModel(
    application: Application,
    private val controller: PurchasesController,
) : AndroidViewModel(application) {

    /** What `viewModel()` can construct: the production controller over the saved engine. */
    constructor(application: Application) : this(application, PurchasesController.forContext(application))

    private val _state = MutableStateFlow(BoughtUiState(today = controller.today()))
    val state: StateFlow<BoughtUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        refresh()
    }

    /** Re-reads whatever the search box currently asks for; also the retry after "can't reach". */
    fun refresh() {
        search(_state.value.query, debounce = false)
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query, savedMessage = null) }
        search(query, debounce = true)
    }

    private fun search(query: String, debounce: Boolean) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (debounce) delay(SEARCH_DEBOUNCE_MS)
            _state.update { it.copy(view = BoughtView.Loading) }
            val q = query.trim()
            val view = if (q.isEmpty()) loadRecent() else loadAnswer(q)
            _state.update { it.copy(view = view, today = controller.today()) }
        }
    }

    private suspend fun loadRecent(): BoughtView = when (val outcome = controller.recent()) {
        is PurchaseOutcome.Ok ->
            BoughtView.Recent(outcome.value.entries, outcome.value.truncated, outcome.value.message)
        else -> BoughtView.Unavailable(PurchaseFailures.readFailed(outcome, "read the entries"))
    }

    private suspend fun loadAnswer(q: String): BoughtView = when (val outcome = controller.lastBought(q)) {
        is PurchaseOutcome.Ok -> BoughtView.Answer(
            query = q,
            headline = PurchaseWording.lastBoughtAnswer(q, outcome.value, controller.today()),
            matches = outcome.value,
        )
        else -> BoughtView.Unavailable(PurchaseFailures.readFailed(outcome, "look up $q"))
    }

    // ------------------------------------------------------------------------- the form

    fun openLog(prefillItem: String? = null) {
        _state.update {
            it.copy(
                mode = BoughtMode.LOG,
                savedMessage = null,
                form = LogFormState(
                    item = prefillItem?.trim().orEmpty(),
                    dateText = LocalDate.ofEpochDay(controller.today().toLong()).toString(),
                ),
            )
        }
    }

    fun closeLog() {
        _state.update { it.copy(mode = BoughtMode.SEARCH) }
        refresh()
    }

    fun editForm(transform: (LogFormState) -> LogFormState) {
        _state.update { it.copy(form = transform(it.form).copy(error = null)) }
    }

    /**
     * Saves the form. The text is validated here only far enough to say what is unreadable (a date,
     * a price); everything else - a blank item, a refusal - comes back from [PurchasesController].
     * **The form stays as typed on every failure**, with [LogFormState.error] saying in words that
     * nothing was logged.
     */
    fun save() {
        val form = _state.value.form
        if (form.saving) return
        val boughtOn = parseDay(form.dateText)
        val price = PurchasesController.parsePrice(form.price)
        val problem = when {
            boughtOn == null -> "That date isn't YYYY-MM-DD, so nothing was logged."
            !price.valid -> "That price isn't a number like 4.99, so nothing was logged."
            else -> null
        }
        if (problem != null) {
            _state.update { it.copy(form = form.copy(error = problem)) }
            return
        }
        _state.update { it.copy(form = form.copy(saving = true, error = null)) }
        viewModelScope.launch {
            val outcome = controller.log(
                item = form.item,
                boughtOn = boughtOn,
                store = form.store,
                priceCents = price.cents,
                note = form.note,
                isPrivate = form.isPrivate,
            )
            when (outcome) {
                is PurchaseOutcome.Ok -> {
                    val said = PurchaseWording.logged(outcome.value, controller.today())
                    _state.update { it.copy(mode = BoughtMode.SEARCH, savedMessage = said, query = "") }
                    refresh()
                }
                // A blank item is refused by the controller before anything is sent, and its own
                // sentence already says nothing was logged; every other failure gets the shared
                // wording that says so.
                is PurchaseOutcome.Refused -> failSave(
                    form,
                    if (form.item.isBlank()) {
                        outcome.sentence
                    } else {
                        PurchaseFailures.logFailed(outcome, form.item.trim())
                    },
                )
                else -> failSave(form, PurchaseFailures.logFailed(outcome, form.item.trim().ifEmpty { "that" }))
            }
        }
    }

    private fun failSave(form: LogFormState, sentence: String) {
        _state.update { it.copy(form = form.copy(saving = false, error = sentence)) }
    }

    private fun parseDay(text: String): Int? = try {
        LocalDate.parse(text.trim()).toEpochDay().toInt()
    } catch (e: DateTimeParseException) {
        null
    }
}
