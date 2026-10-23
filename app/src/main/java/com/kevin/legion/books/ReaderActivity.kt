package com.kevin.legion.books

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.ui.theme.soft.SoftTheme

/**
 * LEGION's own reader (ADR 0061). A [FragmentActivity] because Readium's EPUB navigator is a
 * Fragment (decided 2026-10-19 over making MainActivity one: the reader is its own task-level
 * screen, MainActivity stays untouched, and the fragment factory it needs is set on THIS activity
 * only). It reopens at the saved place, and saves on every page turn and on leaving.
 */
class ReaderActivity : FragmentActivity() {
    private val documentId: String? get() = intent.getStringExtra(EXTRA_DOCUMENT_ID)

    private val model: ReaderViewModel by viewModels {
        ReaderViewModel.Factory(
            this,
            requireNotNull(documentId) { "ReaderActivity needs a book" },
            intent.getBooleanExtra(EXTRA_LISTEN, false),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // No saved state on purpose: a restored EpubNavigatorFragment needs the fragment factory that
        // only exists after the book is open. The reader reopens at its own saved position instead.
        super.onCreate(null)
        setContent {
            SoftTheme {
                val state by model.state.collectAsStateWithLifecycle()
                LaunchedEffect(state.settings.fullScreen) { applyFullScreen(state.settings.fullScreen) }
                Box(Modifier.fillMaxSize()) {
                    ReaderScreen(state, readerActions()) { ReaderBody(state) }
                    WarmOverlay(state.settings)
                }
            }
        }
        ReaderPresence.showing.value = documentId
    }

    private fun readerActions() = ReaderActions(
        onBack = ::finish,
        onToggleSettings = { model.showPanel(ReaderPanel.SETTINGS) },
        onToggleBookmarks = { model.showPanel(ReaderPanel.BOOKMARKS) },
        onToggleContents = { model.showPanel(ReaderPanel.CONTENTS) },
        onClosePanel = model::closePanel,
        onTheme = model::setTheme,
        onBigger = model::biggerText,
        onSmaller = model::smallerText,
        onListen = model::toggleListen,
        onStopListening = model::stopListening,
        onSpeed = model::cycleSpeed,
        onAddBookmark = model::addBookmark,
        onDeleteBookmark = model::deleteBookmark,
        onGoBookmark = model::goToBookmark,
        onGoToc = model::goToToc,
        onGoPage = model::goToPage,
        onSpreadSplit = model::setSpreadSplit,
        onNextTheme = model::nextTheme,
        onToggleWarm = model::toggleWarm,
        onWarmStrength = model::setWarmStrength,
        onWarmAuto = model::setWarmAuto,
        onFullScreen = model::setFullScreen,
        onToggleChrome = model::toggleChrome,
    )

    /** Immersive: hide the system bars, and let a swipe from an edge peek at them. */
    private fun applyFullScreen(on: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val bars = WindowInsetsCompat.Type.systemBars()
        if (on) controller.hide(bars) else controller.show(bars)
    }

    @Composable
    private fun ReaderBody(state: ReaderUiState) {
        val palette = ReaderPalette.of(state.settings.theme)
        val pageRequest by model.pageRequest.collectAsStateWithLifecycle()
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        LaunchedEffect(landscape) { model.setLandscape(landscape) }
        when (val content = state.content) {
            ReaderContent.Loading -> Centered("Opening...", palette)
            is ReaderContent.Failed -> Centered(content.sentence, palette)
            ReaderContent.Epub -> {
                val session = model.epubSession
                if (session != null) {
                    EpubNavigatorHost(
                        activity = this,
                        session = session,
                        startLocatorJson = model.epubStart,
                        settings = state.settings,
                        palette = palette,
                        onLocator = model::onEpubLocator,
                        onControl = model::attach,
                        modifier = Modifier.fillMaxSize(),
                        onTapCenter = model::toggleChrome,
                    )
                }
            }
            is ReaderContent.Paged -> PagedViewer(
                source = content.source,
                spread = state.spread,
                landscape = landscape,
                startPage = state.pageIndex,
                palette = palette,
                pageRequest = pageRequest,
                onPage = model::onPage,
                onPageRequestHandled = model::onPageRequestHandled,
                // Comics are art: never recoloured, whatever the theme says.
                colorMatrix = if (state.format == BookFormat.CBZ) null else PageColorMatrix.of(state.settings.theme),
                onTapCenter = model::toggleChrome,
            )
        }
    }

    @Composable
    private fun Centered(text: String, palette: ReaderPalette) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, color = palette.text, modifier = Modifier.padding(24.dp))
        }
    }

    override fun onStop() {
        model.flush()
        super.onStop()
    }

    override fun onDestroy() {
        if (ReaderPresence.showing.value == documentId) ReaderPresence.showing.value = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_DOCUMENT_ID = "documentId"
        private const val EXTRA_LISTEN = "listen"

        fun intent(context: Context, book: Book, listen: Boolean): Intent =
            Intent(context, ReaderActivity::class.java)
                .putExtra(EXTRA_DOCUMENT_ID, book.documentId)
                .putExtra(EXTRA_LISTEN, listen)
    }
}
