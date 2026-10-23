package com.kevin.legion.dumb

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kevin.legion.MidnightApplication
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** One digest line: the app's name, the package to open, and how many were held. No content. */
data class DigestRowUi(val packageName: String, val appName: String, val count: Int)

/** What the digest screen draws. */
data class DigestUi(val rows: List<DigestRowUi> = emptyList(), val lastRun: String = "")

object DigestRows {
    /** Most notifications first; ties by app name so the order is stable. */
    fun build(entries: List<DigestEntry>, labelOf: (String) -> String): List<DigestRowUi> =
        entries.map { DigestRowUi(it.packageName, labelOf(it.packageName), it.count) }
            .sortedWith(compareByDescending<DigestRowUi> { it.count }.thenBy { it.appName.lowercase() })
}

/**
 * The digest shown when dumb mode ends (ADR 0062, prototype `Digest.dc.html` minus the gist line):
 * app name and count, tap to open the app, Clear all, Go back to dumb mode.
 */
class DumbDigestViewModel(
    private val digest: HeldDigest,
    private val controller: DumbModeController,
    labelOf: (String) -> String,
    viewModelScopeOverride: kotlinx.coroutines.CoroutineScope? = null,
) : ViewModel() {
    private val scope = viewModelScopeOverride ?: viewModelScope

    val ui: StateFlow<DigestUi> = digest.state
        .map { s ->
            DigestUi(
                rows = DigestRows.build(s.entries, labelOf),
                lastRun = controller.history().lastOrNull()
                    ?.let { "dumb mode off · was on ${DumbFormat.elapsed(it.endMs - it.startMs)}" }
                    .orEmpty(),
            )
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DigestUi())

    /** A tapped app leaves the list; its notifications were never kept, so there is nothing else to drop. */
    fun opened(packageName: String) = digest.remove(packageName)

    fun clearAll() = digest.clear()

    /** Back to the short list. The held counts are dropped: they were just shown. */
    fun backToDumb() {
        digest.dismiss()
        controller.turnOn()
    }

    /** Leave the digest without clearing it. */
    fun dismiss() = digest.dismiss()

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val app = context.applicationContext as MidnightApplication

        @Suppress("UNCHECKED_CAST") // the only type this factory is asked for
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DumbDigestViewModel(app.dumb.digest, app.dumbMode, app.dumb::appLabel) as T
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
