package com.kevin.legion.ui.settings

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.update.AndroidUpdatePlatform
import com.kevin.legion.update.DjangoUpdateBackend
import com.kevin.legion.update.ReleaseHistorySource
import com.kevin.legion.update.WhatsNew
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
            val source = DjangoUpdateBackend(EngineHttp(EngineConfig(app)))
            return WhatsNewViewModel(source) { platform.installed().versionCode } as T
        }
    }
}
