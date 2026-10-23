@file:Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.

package com.kevin.legion.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.legion.ui.theme.soft.AreaAccent
import com.kevin.legion.ui.theme.soft.SoftColors

/** The Books tone: the Recordings area's violet pair, the closest existing tokens to the prototype's chip. */
internal val BooksTone = AreaAccent.RECORDINGS

private const val FORMATS_LINE = "EPUB, PDF and CBZ"

/**
 * The Books sheet (ADR 0061, prototype A): a bottom sheet over HOME. A window around
 * [BooksSheetContent], split so a screenshot can draw the content without a window.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BooksSheet(books: BooksHome, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SoftColors.card,
    ) {
        BooksSheetContent(books, onDismiss)
    }
}

@Composable
fun BooksSheetContent(books: BooksHome, onClose: () -> Unit) {
    val state = books.state
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Books",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = SoftColors.text,
                )
                Text(subtitle(state), style = MaterialTheme.typography.bodySmall, color = SoftColors.text3)
            }
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 44.dp)) {
                Text("Done", color = SoftColors.text)
            }
        }
        state.notice?.takeIf { it.failed }?.let {
            Text(
                it.text,
                style = MaterialTheme.typography.bodyMedium,
                color = SoftColors.caution,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        // Fills the sheet's height and scrolls past it; HOME itself never scrolls.
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            when (val folder = state.folder) {
                BooksFolderState.None -> FolderPrompt(
                    "Pick your Books folder",
                    "LEGION lists the $FORMATS_LINE files in the folder you choose, and the folders inside it, " +
                        "and nothing else. A CBR comic is listed but cannot be read yet.",
                    "Pick your Books folder",
                    books.callbacks.onPickFolder,
                )
                is BooksFolderState.Lost -> FolderPrompt(
                    "LEGION can no longer read ${folder.label}",
                    "Android took the folder permission back. Pick the folder again to see your books.",
                    "Pick your Books folder",
                    books.callbacks.onPickFolder,
                )
                is BooksFolderState.Granted -> GrantedBody(books, folder.label)
            }
            if (state.folder is BooksFolderState.Granted && state.items.isNotEmpty()) {
                TextButton(
                    onClick = books.callbacks.onPickFolder,
                    modifier = Modifier.padding(horizontal = 12.dp).heightIn(min = 44.dp),
                ) { Text("Change folder", color = SoftColors.text2) }
            }
        }
    }
}

private fun subtitle(state: BooksUiState): String = when (val folder = state.folder) {
    is BooksFolderState.Granted -> listOfNotNull(
        state.chipSub, folder.label, state.pdfLine, state.syncLine,
    ).joinToString(" · ")
    is BooksFolderState.Lost -> "Folder access lost · ${folder.label}"
    BooksFolderState.None -> "No folder picked yet"
}

@Composable
private fun FolderPrompt(title: String, body: String, button: String, onPick: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = SoftColors.text)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = SoftColors.text2)
        Button(
            onClick = onPick,
            modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = BooksTone.onContainer,
                contentColor = BooksTone.container,
            ),
        ) { Text(button, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun GrantedBody(books: BooksHome, folderLabel: String) {
    val state = books.state
    when {
        state.unreadable -> FolderPrompt(
            "Couldn't read $folderLabel",
            "The folder is still granted but Android would not list it. Pick it again to refresh the permission.",
            "Pick your Books folder",
            books.callbacks.onPickFolder,
        )
        state.items.isEmpty() && !state.scanned -> Note("Looking for books in $folderLabel...")
        state.items.isEmpty() -> FolderPrompt(
            "No books found in $folderLabel",
            "LEGION looks for .epub, .pdf, .cbz and .cbr files in the folder and the folders inside it. " +
                "Copy some in, or pick a different folder.",
            "Pick a different folder",
            books.callbacks.onPickFolder,
        )
        else -> {
            state.resume?.let { ContinueCard(it, books) }
            state.shelves.forEach { BookShelfRow(it, books) }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = SoftColors.text2,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
    )
}

@Composable
private fun ContinueCard(resume: ContinueUi, books: BooksHome) {
    val item = resume.item
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SoftColors.cardHigh)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BookCover(item, books, Modifier.width(64.dp).height(92.dp), small = true)
        Column(Modifier.weight(1f)) {
            Text(
                "CONTINUE READING",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.06.sp),
                color = BooksTone.onContainer,
            )
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                color = SoftColors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(resume.line, style = MaterialTheme.typography.bodySmall, color = SoftColors.text2)
            ProgressBar(item.progress.toFloat(), Modifier.padding(top = 6.dp), 4.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = { books.callbacks.onContinue(false) },
                modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "Read ${item.title}" },
                colors = ButtonDefaults.buttonColors(
                    containerColor = BooksTone.onContainer,
                    contentColor = BooksTone.container,
                ),
            ) { Text("Read", fontWeight = FontWeight.Bold) }
            Button(
                onClick = { books.callbacks.onContinue(true) },
                modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "Listen to ${item.title}" },
                colors = ButtonDefaults.buttonColors(
                    containerColor = SoftColors.cardHighest,
                    contentColor = SoftColors.text,
                ),
            ) { Text("Listen", fontWeight = FontWeight.SemiBold) }
        }
    }
}
