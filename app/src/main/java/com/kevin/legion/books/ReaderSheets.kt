@file:Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.

package com.kevin.legion.books

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.home.BooksTone
import com.kevin.legion.ui.theme.soft.SoftColors

/** The reader's two list panels, as bottom sheets over the page: contents (or page jump) and bookmarks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContentsSheet(state: ReaderUiState, actions: ReaderActions) {
    ModalBottomSheet(
        onDismissRequest = actions.onClosePanel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SoftColors.card,
    ) {
        if (state.format == BookFormat.EPUB) {
            Text(
                "Contents",
                style = MaterialTheme.typography.titleLarge,
                color = SoftColors.text,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            if (state.toc.isEmpty()) {
                Text(
                    "This book has no contents list.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SoftColors.text2,
                    modifier = Modifier.padding(20.dp),
                )
            }
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                items(state.toc, key = { it.index }) { entry ->
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = SoftColors.text,
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
    Column(
        Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Pages", style = MaterialTheme.typography.titleLarge, color = SoftColors.text)
        Text(
            "Page ${target.toInt() + 1} of ${state.pageCount}",
            style = MaterialTheme.typography.bodyMedium,
            color = SoftColors.text2,
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
internal fun BookmarksSheet(state: ReaderUiState, actions: ReaderActions) {
    ModalBottomSheet(
        onDismissRequest = actions.onClosePanel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SoftColors.card,
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Bookmarks", style = MaterialTheme.typography.titleLarge, color = SoftColors.text)
            TextButton(onClick = actions.onAddBookmark, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Add a bookmark here (${state.positionLabel})", color = BooksTone.onContainer)
            }
            if (state.bookmarks.isEmpty()) {
                Text(
                    "No bookmarks in this book yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SoftColors.text2,
                )
            }
            state.bookmarks.forEach { bookmark ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        bookmark.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = SoftColors.text,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(role = Role.Button) { actions.onGoBookmark(bookmark) }
                            .padding(vertical = 12.dp),
                    )
                    TextButton(onClick = { actions.onDeleteBookmark(bookmark) }) {
                        Text("Delete", color = SoftColors.text2)
                    }
                }
            }
        }
    }
}
