package com.kevin.legion.ui.brief

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kevin.legion.brief.BriefController
import com.kevin.legion.brief.BriefView
import com.kevin.legion.brief.PhoneSection
import com.kevin.legion.brief.RefreshOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The modal's two actions, so `BriefSheetContent` stays stateless and renderable with fakes. */
data class BriefCallbacks(val onRefresh: () -> Unit = {}, val onCheckNewsletters: () -> Unit = {})

/** The Gmail newsletter half: checked only on a tap, held only in memory, dropped when the modal closes. */
sealed interface NewslettersUi {
    data object NotChecked : NewslettersUi

    data object Checking : NewslettersUi

    data class Ready(val text: String) : NewslettersUi
}

/**
 * Everything the Brief modal draws. [view] is null only before the first read lands, and the modal
 * says "Reading the brief" then, so it is never blank. [refreshNote] is the sentence from a Refresh
 * that did not happen; the brief on screen stays as it was.
 */
data class BriefUiState(
    val loading: Boolean = false,
    val view: BriefView? = null,
    val refreshing: Boolean = false,
    val refreshNote: String? = null,
    val phoneLoading: Boolean = false,
    val phone: List<PhoneSection> = emptyList(),
    val newslettersOn: Boolean = false,
    val newsletters: NewslettersUi = NewslettersUi.NotChecked,
)

/**
 * The Brief modal's ViewModel (ai-upgrade ticket 02): one [StateFlow] of [BriefUiState] over the
 * shared [BriefController], the same one `get_sitrep` calls. No Hilt yet, matching [com.kevin.legion
 * .ui.home.BooksViewModel]; [Factory] is the injection point. [scope] and [io] are test seams.
 */
class BriefViewModel(
    private val controller: BriefController,
    scope: CoroutineScope? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope

    private val _state = MutableStateFlow(BriefUiState())
    val state: StateFlow<BriefUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var newsletterJob: Job? = null

    /** The modal opened: read the brief and the phone's sections again. The last brief stays up meanwhile. */
    fun open() {
        loadJob?.cancel()
        _state.update { it.copy(loading = true, phoneLoading = true, refreshNote = null) }
        loadJob = scope.launch {
            launch {
                val view = withContext(io) { controller.load() }
                _state.update { it.copy(loading = false, view = view) }
            }
            val phone = withContext(io) {
                runCatching { controller.phoneSections() to controller.newslettersEnabled() }
            }
            _state.update { current ->
                phone.fold(
                    onSuccess = { (sections, on) ->
                        current.copy(phoneLoading = false, phone = sections, newslettersOn = on)
                    },
                    onFailure = { e ->
                        val why = "Calendar and weather could not be read: ${e.message}"
                        current.copy(phoneLoading = false, phone = listOf(PhoneSection("On this phone", why)))
                    },
                )
            }
        }
    }

    /** The Refresh button. Ignored while one is already running (the button is disabled then too). */
    fun refresh() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true, refreshNote = null) }
        scope.launch {
            val outcome = withContext(io) { controller.refresh() }
            _state.update { current ->
                when (outcome) {
                    is RefreshOutcome.Refreshed -> current.copy(refreshing = false, view = outcome.view)
                    is RefreshOutcome.NotRefreshed -> current.copy(refreshing = false, refreshNote = outcome.sentence)
                }
            }
        }
    }

    /** "Check newsletters": the Gmail summary, live, on this tap only. */
    fun checkNewsletters() {
        if (_state.value.newsletters == NewslettersUi.Checking) return
        _state.update { it.copy(newsletters = NewslettersUi.Checking) }
        newsletterJob = scope.launch {
            val text = withContext(io) {
                runCatching { controller.newsletters() }
                    .getOrElse { "Newsletters could not be checked: ${it.message}" }
            }
            _state.update { it.copy(newsletters = NewslettersUi.Ready(text)) }
        }
    }

    /** The modal closed. The newsletter summary is third-party mail, so it is dropped here (read-through). */
    fun closed() {
        newsletterJob?.cancel()
        _state.update { it.copy(newsletters = NewslettersUi.NotChecked, refreshNote = null) }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val app = context.applicationContext

        @Suppress("UNCHECKED_CAST") // the only type this factory is asked for
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BriefViewModel(BriefController.of(app)) as T
    }
}
