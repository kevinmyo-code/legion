@file:Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.

package com.kevin.legion.ui.brief

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kevin.legion.brief.BriefLine
import com.kevin.legion.brief.BriefSection
import com.kevin.legion.brief.BriefView
import com.kevin.legion.ui.theme.soft.SoftColors

/**
 * The Brief button's modal, owning its [BriefViewModel]. Drawn while [open]; every open reads the
 * brief again, and dismissing it drops the newsletter summary. Opened only by a tap (ticket 02,
 * ruling 2: never a notification, never unsolicited speech). [linksOpen] false shows links as text
 * instead of opening them. Nothing passes false since 2026-10-24, when Matrix mode stopped hiding
 * the browser (it used to be false there); the parameter stays as the switch.
 */
@Composable
fun BriefSheetHost(open: Boolean, onDismiss: () -> Unit, linksOpen: Boolean = true) {
    val context = LocalContext.current
    val viewModel: BriefViewModel = viewModel(factory = BriefViewModel.Factory(context))
    LaunchedEffect(open) { if (open) viewModel.open() }
    if (!open) return
    val state by viewModel.state.collectAsStateWithLifecycle()
    BriefSheet(
        state = state,
        callbacks = BriefCallbacks(viewModel::refresh, viewModel::checkNewsletters),
        linksOpen = linksOpen,
        onDismiss = {
            viewModel.closed()
            onDismiss()
        },
    )
}

/** A bottom sheet over HOME, as the Books and Games sheets are. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BriefSheet(state: BriefUiState, callbacks: BriefCallbacks, linksOpen: Boolean, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SoftColors.card,
    ) {
        BriefSheetContent(state, callbacks, linksOpen, onDismiss)
    }
}

/**
 * The modal's body: header, Refresh, the engine's sections as it sent them, then the phone's live
 * calendar and weather, then newsletters when that module is on. Every line is drawn: nothing is
 * collapsed, and an unverified figure is marked in words.
 */
@Composable
fun BriefSheetContent(state: BriefUiState, callbacks: BriefCallbacks, linksOpen: Boolean, onClose: () -> Unit) {
    var linkNote by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val openLink: (String) -> Unit = { url ->
        linkNote = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        }.getOrElse { "That link could not be opened: no app on this phone opens web links." }
    }
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 20.dp)) {
        BriefHeader(state, callbacks, onClose)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(end = 8.dp)) {
            ServerHalf(state, linksOpen, openLink)
            linkNote?.let { Note(it, SoftColors.caution) }
            PhoneHalf(state, callbacks)
        }
    }
}

@Composable
private fun BriefHeader(state: BriefUiState, callbacks: BriefCallbacks, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "Brief",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = SoftColors.text,
            )
            val header = (state.view as? BriefView.Showing)?.header
                ?: if (state.loading || state.view == null) "Reading the brief..." else "No brief to show"
            Text(header, style = MaterialTheme.typography.bodySmall, color = SoftColors.text2)
        }
        TextButton(
            onClick = callbacks.onRefresh,
            enabled = !state.refreshing,
            modifier = Modifier.heightIn(min = 44.dp),
        ) {
            Text(if (state.refreshing) "Refreshing..." else "Refresh", color = SoftColors.primary)
        }
        TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 44.dp)) {
            Text("Done", color = SoftColors.text)
        }
    }
    state.refreshNote?.let { Note(it, SoftColors.caution) }
}

@Composable
private fun ServerHalf(state: BriefUiState, linksOpen: Boolean, openLink: (String) -> Unit) {
    when (val view = state.view) {
        null -> Waiting("Reading the brief from the engine...")
        is BriefView.NoneYet -> Note(view.sentence, SoftColors.text)
        is BriefView.Unavailable -> Note(view.sentence, SoftColors.caution)
        is BriefView.Showing -> {
            view.savedCopy?.let { Note(it, SoftColors.caution) }
            view.brief.notes.forEach { Note(it, SoftColors.text2) }
            view.brief.sections.forEach { ServerSection(it, linksOpen, openLink) }
            val anyLinks = view.brief.sections.any { s -> s.lines.any { it.links.isNotEmpty() } }
            if (!linksOpen && anyLinks) Note("Links do not open in matrix mode.", SoftColors.text3)
        }
    }
}

@Composable
private fun ServerSection(section: BriefSection, linksOpen: Boolean, openLink: (String) -> Unit) {
    SectionTitle(section.title, section.status)
    section.lines.forEach { BriefLineRow(it, linksOpen, openLink) }
    section.note?.let { Note(it, if (section.failed) SoftColors.caution else SoftColors.text2) }
}

@Composable
private fun BriefLineRow(line: BriefLine, linksOpen: Boolean, openLink: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(line.text, style = MaterialTheme.typography.bodyMedium, color = SoftColors.text)
        if (line.unverified) {
            Text(
                "Unverified",
                style = MaterialTheme.typography.labelMedium,
                color = SoftColors.onAlert,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .background(SoftColors.alertContainer, MaterialTheme.shapes.small)
                    .padding(horizontal = 8.dp, vertical = 1.dp),
            )
        }
        line.links.forEach { link ->
            if (linksOpen) {
                Text(
                    link.title,
                    style = MaterialTheme.typography.labelLarge.copy(textDecoration = TextDecoration.Underline),
                    color = SoftColors.primary,
                    modifier = Modifier
                        .heightIn(min = 32.dp)
                        .clickable(role = Role.Button, onClickLabel = "Open ${link.title} in the browser") {
                            openLink(link.url)
                        }
                        .padding(vertical = 6.dp),
                )
            } else {
                Text(
                    "${link.title}: ${link.url}",
                    style = MaterialTheme.typography.labelMedium,
                    color = SoftColors.text3,
                )
            }
        }
    }
}

@Composable
private fun PhoneHalf(state: BriefUiState, callbacks: BriefCallbacks) {
    Text(
        "On this phone, live",
        style = MaterialTheme.typography.labelLarge,
        color = SoftColors.text3,
        modifier = Modifier.padding(top = 16.dp),
    )
    if (state.phoneLoading && state.phone.isEmpty()) Waiting("Reading the calendar and weather...")
    state.phone.forEach { section ->
        SectionTitle(section.title, null)
        Text(section.text, style = MaterialTheme.typography.bodyMedium, color = SoftColors.text)
    }
    if (state.phoneLoading || !state.newslettersOn) return
    SectionTitle("Newsletters", null)
    when (val news = state.newsletters) {
        NewslettersUi.NotChecked -> {
            Note(
                "Not checked. A check reads newsletter mail from your Gmail and summarizes it. Nothing is saved.",
                SoftColors.text2,
            )
            TextButton(onClick = callbacks.onCheckNewsletters, modifier = Modifier.heightIn(min = 44.dp)) {
                Text("Check newsletters", color = SoftColors.primary)
            }
        }
        NewslettersUi.Checking -> Waiting("Checking your newsletters...")
        is NewslettersUi.Ready -> {
            Text(news.text, style = MaterialTheme.typography.bodyMedium, color = SoftColors.text)
            Note("Read from your mail just now. Not saved.", SoftColors.text3)
        }
    }
}

@Composable
private fun SectionTitle(title: String, status: String?) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = SoftColors.text)
        status?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = SoftColors.text3) }
    }
}

@Composable
private fun Note(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun Waiting(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = SoftColors.primary)
        Text(text, style = MaterialTheme.typography.bodySmall, color = SoftColors.text2)
    }
}
