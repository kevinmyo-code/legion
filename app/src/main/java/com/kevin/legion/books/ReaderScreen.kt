@file:Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.

package com.kevin.legion.books

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.ui.home.BooksTone
import com.kevin.legion.ui.theme.soft.MsIcon

/** What the reader chrome asks of its owner. */
data class ReaderActions(
    val onBack: () -> Unit = {},
    val onToggleSettings: () -> Unit = {},
    val onToggleBookmarks: () -> Unit = {},
    val onToggleContents: () -> Unit = {},
    val onClosePanel: () -> Unit = {},
    val onTheme: (ReaderTheme) -> Unit = {},
    val onBigger: () -> Unit = {},
    val onSmaller: () -> Unit = {},
    val onListen: () -> Unit = {},
    val onStopListening: () -> Unit = {},
    val onSpeed: () -> Unit = {},
    val onAddBookmark: () -> Unit = {},
    val onDeleteBookmark: (com.kevin.legion.data.local.BookBookmark) -> Unit = {},
    val onGoBookmark: (com.kevin.legion.data.local.BookBookmark) -> Unit = {},
    val onGoToc: (TocEntry) -> Unit = {},
    val onGoPage: (Int) -> Unit = {},
)

/**
 * The reader's one screen (prototype `Reader.dc.html`): header, the page, the read-aloud bar, and
 * the bottom bar. [body] is the page itself (Readium's navigator, or the paged viewer), so the same
 * chrome serves all three formats and a screenshot can draw it with static text.
 */
@Composable
fun ReaderScreen(
    state: ReaderUiState,
    actions: ReaderActions,
    modifier: Modifier = Modifier,
    body: @Composable () -> Unit,
) {
    val palette = ReaderPalette.of(state.settings.theme)
    Column(modifier.fillMaxSize().background(palette.page).statusBarsPadding()) {
        Header(state, palette, actions)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            body()
            if (state.panel == ReaderPanel.SETTINGS) SettingsCard(state, actions, Modifier.align(Alignment.TopCenter))
        }
        state.readAloud.message?.let { Notice(it, palette) }
        state.listenNote?.let { Notice(it, palette) }
        if (state.readAloud.active) ListenBar(state.readAloud, actions)
        BottomBar(state, palette, actions)
    }
    when (state.panel) {
        ReaderPanel.CONTENTS -> ContentsSheet(state, actions)
        ReaderPanel.BOOKMARKS -> BookmarksSheet(state, actions)
        else -> Unit
    }
}

@Composable
private fun Header(state: ReaderUiState, palette: ReaderPalette, actions: ReaderActions) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RoundButton("Back to HOME", actions.onBack) {
            MsIcon(R.drawable.ms_arrow_back, null, palette.text, size = 20.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(
                state.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                state.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RoundButton("Text settings", actions.onToggleSettings) {
            Text("Aa", color = palette.text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
        RoundButton("Bookmarks", actions.onToggleBookmarks) {
            MsIcon(R.drawable.ms_bookmark, null, palette.text, size = 20.dp)
        }
    }
}

@Composable
private fun RoundButton(description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun Notice(text: String, palette: ReaderPalette) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = palette.text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.chip)
            .padding(12.dp),
    )
}

/** The prototype's settings card: text size, the three themes, and the one-line note. */
@Composable
private fun SettingsCard(state: ReaderUiState, actions: ReaderActions, modifier: Modifier) {
    val isEpub = state.format == BookFormat.EPUB
    Column(
        modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(com.kevin.legion.ui.theme.soft.SoftColors.cardHigh)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val ink = com.kevin.legion.ui.theme.soft.SoftColors.text
        if (isEpub) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Text size", style = MaterialTheme.typography.titleSmall, color = ink)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallRound("Smaller text", "A-", actions.onSmaller)
                    SmallRound("Bigger text", "A+", actions.onBigger)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderTheme.entries.forEach { theme ->
                val p = ReaderPalette.of(theme)
                val selected = theme == state.settings.theme
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(p.page)
                        .border(if (selected) 2.dp else 1.dp, if (selected) BooksTone.onContainer else p.line, RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button) { actions.onTheme(theme) },
                    contentAlignment = Alignment.Center,
                ) {
                    // The selection is said in words as well as drawn: "Night, selected".
                    Text(
                        if (selected) "${theme.label} ✓" else theme.label,
                        color = p.text,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        Text(
            if (isEpub) "EPUB reflows. PDF and comics zoom instead, with page turning." else "PDF and comics zoom instead of reflowing.",
            style = MaterialTheme.typography.bodySmall,
            color = com.kevin.legion.ui.theme.soft.SoftColors.text3,
        )
    }
}

@Composable
private fun SmallRound(description: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(com.kevin.legion.ui.theme.soft.SoftColors.cardHighest)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = com.kevin.legion.ui.theme.soft.SoftColors.text, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun ListenBar(readAloud: ReadAloudUi, actions: ReaderActions) {
    Row(
        Modifier
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(BooksTone.container)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(BooksTone.onContainer)
                .clickable(role = Role.Button, onClick = actions.onListen)
                .semantics { contentDescription = if (readAloud.playing) "Pause reading aloud" else "Resume reading aloud" },
            contentAlignment = Alignment.Center,
        ) {
            MsIcon(if (readAloud.playing) R.drawable.ms_pause else R.drawable.ms_play_arrow, null, BooksTone.container, size = 18.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(
                if (readAloud.playing) "Reading aloud" else "Paused",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = com.kevin.legion.ui.theme.soft.SoftColors.text,
            )
            Text("Device voice · offline", style = MaterialTheme.typography.labelSmall, color = BooksTone.onContainer)
        }
        Box(
            Modifier
                .heightIn(min = 36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(BooksTone.onContainer.copy(alpha = 0.25f))
                .clickable(role = Role.Button, onClick = actions.onSpeed)
                .padding(horizontal = 12.dp)
                .semantics { contentDescription = "Reading speed ${readAloud.speedLabel}" },
            contentAlignment = Alignment.Center,
        ) { Text(readAloud.speedLabel, color = com.kevin.legion.ui.theme.soft.SoftColors.text, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun BottomBar(state: ReaderUiState, palette: ReaderPalette, actions: ReaderActions) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, palette.line)
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.hasPosition) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(state.positionLabel, style = MaterialTheme.typography.bodySmall, color = palette.muted)
                Text(
                    listOfNotNull(state.percentLabel, state.timeLeft).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                )
            }
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(palette.line)) {
                Box(Modifier.fillMaxWidth(state.progress.coerceIn(0f, 1f)).height(4.dp).background(BooksTone.onContainer, RoundedCornerShape(2.dp)))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(if (state.format == BookFormat.EPUB) "Contents" else "Pages", palette.chip, palette.text, Modifier.weight(1f), actions.onToggleContents)
            PillButton(
                if (state.readAloud.active) "Stop listening" else "Listen",
                BooksTone.onContainer,
                BooksTone.container,
                Modifier.weight(1f),
            ) { if (state.readAloud.active) actions.onStopListening() else actions.onListen() }
        }
    }
}

@Composable
private fun PillButton(label: String, background: Color, content: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(background)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = content, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentsSheet(state: ReaderUiState, actions: ReaderActions) {
    ModalBottomSheet(
        onDismissRequest = actions.onClosePanel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = com.kevin.legion.ui.theme.soft.SoftColors.card,
    ) {
        val ink = com.kevin.legion.ui.theme.soft.SoftColors.text
        if (state.format == BookFormat.EPUB) {
            Text("Contents", style = MaterialTheme.typography.titleLarge, color = ink, modifier = Modifier.padding(horizontal = 20.dp))
            if (state.toc.isEmpty()) {
                Text(
                    "This book has no contents list.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = com.kevin.legion.ui.theme.soft.SoftColors.text2,
                    modifier = Modifier.padding(20.dp),
                )
            }
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                items(state.toc, key = { it.index }) { entry ->
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = ink,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Button) { actions.onGoToc(entry) }
                            .padding(start = (20 + 16 * entry.depth).dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
                    )
                }
            }
        } else {
            PageJump(state, actions)
        }
    }
}

/** PDF and comics have no contents list; "Pages" jumps to a page number. */
@Composable
private fun PageJump(state: ReaderUiState, actions: ReaderActions) {
    var target by remember(state.pageIndex) { mutableFloatStateOf(state.pageIndex.toFloat()) }
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Pages", style = MaterialTheme.typography.titleLarge, color = com.kevin.legion.ui.theme.soft.SoftColors.text)
        Text(
            "Page ${target.toInt() + 1} of ${state.pageCount}",
            style = MaterialTheme.typography.bodyMedium,
            color = com.kevin.legion.ui.theme.soft.SoftColors.text2,
        )
        if (state.pageCount > 1) {
            Slider(value = target, onValueChange = { target = it }, valueRange = 0f..(state.pageCount - 1).toFloat())
        }
        TextButton(onClick = { actions.onGoPage(target.toInt()) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Go to page ${target.toInt() + 1}", color = BooksTone.onContainer)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarksSheet(state: ReaderUiState, actions: ReaderActions) {
    ModalBottomSheet(
        onDismissRequest = actions.onClosePanel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = com.kevin.legion.ui.theme.soft.SoftColors.card,
    ) {
        val ink = com.kevin.legion.ui.theme.soft.SoftColors.text
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Bookmarks", style = MaterialTheme.typography.titleLarge, color = ink)
            TextButton(onClick = actions.onAddBookmark, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Add a bookmark here (${state.positionLabel})", color = BooksTone.onContainer)
            }
            if (state.bookmarks.isEmpty()) {
                Text("No bookmarks in this book yet.", style = MaterialTheme.typography.bodyMedium, color = com.kevin.legion.ui.theme.soft.SoftColors.text2)
            }
            state.bookmarks.forEach { bookmark ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        bookmark.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = ink,
                        modifier = Modifier.weight(1f).clickable(role = Role.Button) { actions.onGoBookmark(bookmark) }.padding(vertical = 12.dp),
                    )
                    TextButton(onClick = { actions.onDeleteBookmark(bookmark) }) {
                        Text("Delete", color = com.kevin.legion.ui.theme.soft.SoftColors.text2)
                    }
                }
            }
        }
    }
}
