package com.kevin.legion.ui.settings

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.update.AndroidUpdatePlatform
import com.kevin.legion.update.DjangoUpdateBackend
import com.kevin.legion.update.ReleaseHistorySource
import com.kevin.legion.update.WhatsNew
import com.kevin.legion.update.WhatsNewSeen
import com.kevin.legion.update.WhatsNewState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What's new's ViewModel: one [StateFlow] of [WhatsNewState]; reload on demand when the engine was out of reach. */
class WhatsNewViewModel(
    private val source: ReleaseHistorySource,
    private val installedCode: () -> Long,
) : ViewModel() {
    private val _state = MutableStateFlow<WhatsNewState>(WhatsNewState.Loading)
    val state: StateFlow<WhatsNewState> = _state.asStateFlow()

    init { reload() }

    fun reload() {
        _state.value = WhatsNewState.Loading
        viewModelScope.launch { _state.value = WhatsNew.load(source, installedCode()) }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val app = context.applicationContext as Application

        @Suppress("UNCHECKED_CAST") // the only type this factory is asked for
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val platform = AndroidUpdatePlatform(app)
            return WhatsNewViewModel(DjangoUpdateBackend(EngineHttp(EngineConfig(app)))) { platform.installed().versionCode } as T
        }
    }
}

/** Settings, What's new: every release's notes, newest first, the installed one marked. */
@Composable
fun WhatsNewScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: WhatsNewViewModel = viewModel(factory = WhatsNewViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Opening the screen, by hand or by itself after an update, is what counts as seeing it.
    LaunchedEffect(Unit) {
        WhatsNewSeen(context).code = AndroidUpdatePlatform(context.applicationContext as Application).installed().versionCode
    }
    WhatsNewContent(state = state, onBack = onBack, onRetry = viewModel::reload)
}

/** Stateless, so it renders with any [WhatsNewState]. */
@Composable
fun WhatsNewContent(state: WhatsNewState, onBack: () -> Unit, onRetry: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DeckScreenHeader(title = "What's new", onBack = onBack)
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                when (state) {
                    WhatsNewState.Loading -> Text("Loading the release notes.", style = MaterialTheme.typography.bodyMedium)
                    is WhatsNewState.Unavailable -> {
                        Text(state.sentence, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        DeckButton(text = "Try again", onClick = onRetry)
                    }
                    is WhatsNewState.Loaded -> {
                        if (state.releases.isEmpty()) Text(WhatsNew.NO_RELEASES, style = MaterialTheme.typography.bodyMedium)
                        state.releases.forEach { note ->
                            val installed = note.versionCode == state.installedCode
                            Text(
                                if (installed) "${note.versionName} (installed)" else note.versionName,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(note.publishedAt.take(DATE_CHARS), style = LegionType.stamp, color = sem.faint)
                            Text(WhatsNew.notesLine(note), style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
    }
}

private const val DATE_CHARS = 10 // "2026-11-26" off an ISO timestamp
