@file:Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.

package com.kevin.legion.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kevin.legion.books.BookFormat
import com.kevin.legion.books.BookItem
import com.kevin.legion.ui.theme.soft.SoftColors

// Shelves, tiles and covers for the Books sheet. Split from BooksSheet.kt (the window, the folder
// prompts and the Continue card) to stay under detekt's functions-per-file limit.

internal val BOOK_TILE_WIDTH = 96.dp
internal val BOOK_TILE_HEIGHT = 134.dp

@Composable
internal fun BookShelfRow(shelf: BookShelfUi, books: BooksHome) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(shelf.shelf.title, style = MaterialTheme.typography.titleSmall, color = SoftColors.text)
        Text(shelf.countLabel, style = MaterialTheme.typography.bodySmall, color = SoftColors.text3)
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(shelf.items, key = { it.book.documentId }) { item -> BookTile(item, books) }
    }
}

@Composable
private fun BookTile(item: BookItem, books: BooksHome) {
    Column(
        Modifier
            .width(BOOK_TILE_WIDTH)
            .clickable(role = Role.Button, onClickLabel = "Read ${item.title}") { books.callbacks.onRead(item) },
    ) {
        BookCover(item, books, Modifier.width(BOOK_TILE_WIDTH).height(BOOK_TILE_HEIGHT), small = false)
        ProgressBar(item.progress.toFloat(), Modifier.padding(top = 6.dp), 3.dp)
        Text(
            item.title,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = SoftColors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp).heightIn(min = 30.dp),
        )
    }
}

/** A thin progress bar. Always drawn with its percentage said elsewhere in words, never by the bar alone. */
@Composable
internal fun ProgressBar(fraction: Float, modifier: Modifier, height: Dp) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(2.dp)).background(SoftColors.outline)) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(BooksTone.onContainer, RoundedCornerShape(2.dp)),
        )
    }
}

/**
 * A book's cover: the cached image if there is one, else a flat colour that says the title and the
 * format in words (a cover that cannot be read is never a blank tile). The format badge sits top-left
 * either way, in words: "EPUB", "PDF", "CBZ", "CBR".
 */
@Composable
internal fun BookCover(item: BookItem, books: BooksHome, modifier: Modifier, small: Boolean) {
    val art by produceState<ImageBitmap?>(null, item.book.documentId, books.state.coverVersion) {
        value = books.cover(item.book)
    }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(flatColor(item))) {
        val image = art
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (!small) {
            Text(
                item.title,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = SoftColors.text,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 8.dp),
            )
        }
        Text(
            item.book.format.label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(5.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(5.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

/** The no-cover colour: one of the cards' tones by format, so the shelf is not a row of identical slabs. */
private fun flatColor(item: BookItem): Color = when (item.book.format) {
    BookFormat.EPUB -> BooksTone.container
    BookFormat.PDF -> SoftColors.cardHighest
    BookFormat.CBZ, BookFormat.CBR -> SoftColors.primaryContainer
}
