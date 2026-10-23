@file:Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.

package com.kevin.legion.books

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.home.BooksTone

/**
 * What the reader says about PDF conversion (ticket 05), in words: on the EPUB copy, once per open,
 * that it was converted and what that costs, with the original one tap away; on the PDF, why there
 * is no resizable copy yet. Neither is furniture to hide behind a help row: it is a disclosure
 * about what the text is, so it is dismissed by the reader, never collapsed by default.
 */
@Composable
internal fun ConversionNotices(state: ReaderUiState, palette: ReaderPalette, actions: ReaderActions) {
    var dismissed by rememberSaveable { mutableStateOf(false) }
    if (state.convertedFromPdf && !dismissed) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(palette.chip)
                .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
        ) {
            Text(
                PdfConversionSentences.CONVERTED_NOTICE,
                style = MaterialTheme.typography.bodySmall,
                color = palette.text,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = actions.onOpenOriginal, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(PdfConversionSentences.OPEN_ORIGINAL, color = BooksTone.onContainer)
                }
                TextButton(onClick = { dismissed = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Got it", color = palette.muted)
                }
            }
        }
    }
    state.conversionNote?.let { note ->
        Text(
            note,
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
}

/**
 * The one line reading sync says on open (ticket 09): the book moved to a place another device
 * recorded, "Picked up where you left off on the web", or "near" when only the progress applied.
 * Said once per open and dismissed by the reader; it is a statement that the page is not where this
 * phone left it, so it is never hidden by default.
 */
@Composable
internal fun SyncNotice(state: ReaderUiState, palette: ReaderPalette) {
    var dismissed by rememberSaveable { mutableStateOf(false) }
    val note = state.syncNote
    if (note != null && !dismissed) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(palette.chip)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = palette.text,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { dismissed = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Got it", color = palette.muted)
            }
        }
    }
}
