package com.kevin.legion.ui.settings

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kevin.legion.ai.AlfredPack
import com.kevin.legion.ai.AlfredPackSource
import com.kevin.legion.ai.LoadedPack
import com.kevin.legion.update.AndroidUpdatePlatform
import com.kevin.legion.update.Consent
import com.kevin.legion.update.InstalledApp
import com.kevin.legion.update.UpdateController
import com.kevin.legion.update.UpdateGraph
import com.kevin.legion.update.UpdateRules
import com.kevin.legion.update.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the Updates screen draws (alfred-ota ticket 06). Every line is a sentence. */
data class UpdatesUiState(
    val installed: String = "",
    val status: String = "Not checked yet.",
    val notes: String? = null,
    val working: Boolean = false,
    val canInstall: Boolean = false,
    val allowMobileData: Boolean = false,
    val mayInstallPackages: Boolean = true,
    val packLine: String = "",
    val note: String? = null,
)

/**
 * The Updates screen's ViewModel: one [StateFlow] of [UpdatesUiState] over the process's one
 * [UpdateController], the same one the `app_update` voice tool calls (ADR 0035). No Hilt in the
 * build yet ([com.kevin.legion.dumb.DumbModeGraph]'s note), so [Factory] is the injection point;
 * every dependency is a constructor parameter so the JVM test passes fakes.
 */
class UpdatesViewModel(
    private val controller: UpdateController,
    private val installed: () -> InstalledApp,
    private val mayInstallPackages: () -> Boolean,
    private val pack: () -> LoadedPack,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope

    private val _state = MutableStateFlow(UpdatesUiState())
    val state: StateFlow<UpdatesUiState> = _state.asStateFlow()

    init {
        this.scope.launch { controller.state.collect { s -> _state.update { render(it, s) } } }
        refreshGrants()
    }

    /** The screen came back to the front: the install grant and the pack may have changed. */
    fun refreshGrants() {
        _state.update {
            it.copy(
                installed = installedLine(installed()),
                allowMobileData = controller.allowMobileData,
                mayInstallPackages = mayInstallPackages(),
                packLine = packLine(pack()),
            )
        }
    }

    fun checkNow() {
        _state.update { it.copy(note = null) }
        scope.launch { controller.check() }
    }

    fun install() {
        scope.launch {
            val outcome = controller.install(Consent.TAP)
            _state.update { it.copy(note = outcome.sentence) }
        }
    }

    fun setAllowMobileData(on: Boolean) {
        controller.allowMobileData = on
        _state.update { it.copy(allowMobileData = on) }
    }

    private fun render(ui: UpdatesUiState, s: UpdateState): UpdatesUiState = when (s) {
        UpdateState.Idle -> ui.copy(status = "Not checked yet.", working = false, canInstall = false, notes = null)
        UpdateState.Checking -> ui.copy(status = "Checking for an update.", working = true, canInstall = false)
        UpdateState.DebugBuild -> ui.copy(
            status = "This is a debug build (version code 1). It never updates itself.",
            working = false,
            canInstall = false,
            notes = null,
        )
        is UpdateState.UpToDate -> ui.copy(status = "Up to date.", working = false, canInstall = false, notes = null)
        is UpdateState.Available -> ui.copy(
            status = "Newest: ${s.release.versionName}. ${s.why}",
            notes = s.release.notes.ifBlank { null },
            working = false,
            canInstall = false,
        )
        is UpdateState.Downloading -> ui.copy(
            status = "Downloading ${s.release.versionName}: ${mb(s.bytes)} of ${mb(s.release.sizeBytes)} MB.",
            notes = s.release.notes.ifBlank { null },
            working = true,
            canInstall = false,
        )
        is UpdateState.Ready -> ui.copy(
            status = "Ready to install: ${s.release.versionName}, downloaded and checked. Nothing is installed yet.",
            notes = s.release.notes.ifBlank { null },
            working = false,
            canInstall = true,
        )
        is UpdateState.Waiting -> ui.copy(
            status = "${UpdateRules.describe(s.release)} installs as soon as the conversation ends.",
            working = false,
            canInstall = false,
        )
        is UpdateState.Installing -> ui.copy(
            status = "Installing ${s.release.versionName}. LEGION closes and comes back on its own.",
            working = true,
            canInstall = false,
        )
        is UpdateState.Failed -> ui.copy(status = s.sentence, working = false, canInstall = false)
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val app = context.applicationContext as Application

        @Suppress("UNCHECKED_CAST") // the only type this factory is asked for
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val platform = AndroidUpdatePlatform(app)
            return UpdatesViewModel(
                controller = UpdateGraph.controller(app),
                installed = platform::installed,
                mayInstallPackages = platform::mayInstallPackages,
                pack = AlfredPack::current,
            ) as T
        }
    }

    companion object {
        fun installedLine(app: InstalledApp): String =
            "This phone runs LEGION ${app.versionName.ifBlank { "(unknown)" }} (version code ${app.versionCode})."

        /** "Alfred's wording: pack v<N> from engine|cache|built-in" (ticket 06). */
        fun packLine(loaded: LoadedPack): String {
            val from = when (loaded.source) {
                AlfredPackSource.REMOTE -> "engine"
                AlfredPackSource.CACHE -> "cache"
                AlfredPackSource.BUNDLED -> "built-in"
            }
            return "Alfred's wording: pack v${loaded.pack.version} from $from."
        }

        private fun mb(bytes: Long): String = "%.0f".format(bytes / 1_048_576.0)
    }
}
