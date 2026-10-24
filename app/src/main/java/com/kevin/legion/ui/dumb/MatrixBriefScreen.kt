package com.kevin.legion.ui.dumb

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kevin.legion.ui.brief.BriefViewModel
import com.kevin.legion.ui.brief.NewslettersUi

/**
 * Matrix mode's Brief (matrix-open ticket 04): the daily brief as plain text sections. The same
 * [BriefViewModel] and [com.kevin.legion.brief.BriefController] as the Soft modal and the `get_sitrep`
 * voice tool, so a Refresh is the same call and its failure arrives as the same sentence. Opening reads
 * the brief again; leaving drops the newsletter summary (third-party mail is read-through).
 */
@Composable
internal fun MatrixBriefScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: BriefViewModel = viewModel(factory = BriefViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    var linkNote by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        viewModel.open()
        onDispose { viewModel.closed() }
    }
    val openLink: (String) -> Unit = { url ->
        linkNote = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        }.getOrElse { "That link could not be opened: no app on this phone opens web links." }
    }
    MatrixScreen("Brief", onBack) {
        MatrixNote(MatrixBriefText.header(state), MatrixTone.DIM)
        MatrixRow(
            if (state.refreshing) "Refreshing..." else "Refresh",
            { if (!state.refreshing) viewModel.refresh() },
            tone = MatrixTone.HEADING,
        )
        state.refreshNote?.let { MatrixNote(it, MatrixTone.WARNING) }
        linkNote?.let { MatrixNote(it, MatrixTone.WARNING) }
        MatrixBriefLines(MatrixBriefText.serverLines(state), openLink)
        MatrixBriefLines(MatrixBriefText.phoneLines(state), openLink)
        MatrixBriefLines(MatrixBriefText.newsletterLines(state), openLink)
        if (state.newslettersOn && !state.phoneLoading && state.newsletters == NewslettersUi.NotChecked) {
            MatrixRow("Check newsletters", viewModel::checkNewsletters)
        }
    }
}

@Composable
private fun MatrixBriefLines(lines: List<MatrixBriefLine>, openLink: (String) -> Unit) {
    lines.forEach { line ->
        val url = line.url
        if (url != null) MatrixRow(line.text, { openLink(url) }) else MatrixNote(line.text, line.tone)
    }
}
