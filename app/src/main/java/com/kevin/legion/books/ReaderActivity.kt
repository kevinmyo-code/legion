package com.kevin.legion.books

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    private val model: ReaderViewModel by viewModels {
        ReaderViewModel.Factory(
            this,
            requireNotNull(intent.getStringExtra(EXTRA_DOCUMENT_ID)) { "ReaderActivity needs a book" },
            intent.getBooleanExtra(EXTRA_LISTEN, false),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // No saved state on purpose: a restored EpubNavigatorFragment needs the fragment factory that
        // only exists after the book is open. The reader reopens at its own saved position instead.
        super.onCreate(null)
        val documentId = intent.getStringExtra(EXTRA_DOCUMENT_ID)
        setContent {
            SoftTheme {
                val state by model.state.collectAsStateWithLifecycle()
                val pageRequest by model.pageRequest.collectAsStateWithLifecycle()
                ReaderScreen(
                    state = state,
                    actions = ReaderActions(
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
                    ),
                ) {
                    val palette = ReaderPalette.of(state.settings.theme)
                    when (val content = state.content) {
                        ReaderContent.Loading -> Centered("Opening...")
                        is ReaderContent.Failed -> Centered(content.sentence)
                        ReaderContent.Epub -> {
                            val session = model.epubSession
                            val start by produceState<String?>(null) { value = model.epubStart() }
                            if (session != null) {
                                EpubNavigatorHost(
                                    activity = this@ReaderActivity,
                                    session = session,
                                    startLocatorJson = start,
                                    settings = state.settings,
                                    palette = palette,
                                    onLocator = model::onEpubLocator,
                                    onControl = model::attach,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        is ReaderContent.Paged -> PagedViewer(
                            source = content.source,
                            startPage = content.startPage,
                            palette = palette,
                            pageRequest = pageRequest,
                            onPage = model::onPage,
                            onPageRequestHandled = model::onPageRequestHandled,
                        )
                    }
                }
            }
        }
        ReaderPresence.showing.value = documentId
    }

    override fun onStop() {
        model.flush()
        super.onStop()
    }

    override fun onDestroy() {
        if (ReaderPresence.showing.value == intent.getStringExtra(EXTRA_DOCUMENT_ID)) ReaderPresence.showing.value = null
        super.onDestroy()
    }

    @androidx.compose.runtime.Composable
    private fun Centered(text: String) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, color = ReaderPalette.of(model.state.value.settings.theme).text, modifier = Modifier.padding(24.dp))
        }
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
