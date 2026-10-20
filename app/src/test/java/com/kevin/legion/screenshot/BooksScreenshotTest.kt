package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.books.Book
import com.kevin.legion.books.BookFormat
import com.kevin.legion.books.BookItem
import com.kevin.legion.books.ReadAloudUi
import com.kevin.legion.books.ReaderActions
import com.kevin.legion.books.ReaderContent
import com.kevin.legion.books.ReaderPalette
import com.kevin.legion.books.ReaderPanel
import com.kevin.legion.books.ReaderScreen
import com.kevin.legion.books.ReaderSettings
import com.kevin.legion.books.ReaderTheme
import com.kevin.legion.books.ReaderUiState
import com.kevin.legion.ui.home.BooksFolderState
import com.kevin.legion.ui.home.BooksHome
import com.kevin.legion.ui.home.BooksSheetContent
import com.kevin.legion.ui.home.BooksUiState
import com.kevin.legion.ui.home.ContinueUi
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Books ticket 02's screenshot step: the sheet in each of its states (populated, no folder, lost
 * permission, empty folder) and the reader in Day, Sepia and Night. The sheet is drawn from its
 * content composable in a sheet-shaped container, as [GamesSheetScreenshotTest] does, because a real
 * ModalBottomSheet is its own window. The reader body is static prototype text standing in for
 * Readium's WebView, which a JVM test cannot draw; everything around it is the real screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
class BooksScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun item(
        title: String,
        format: BookFormat,
        author: String? = null,
        progress: Double = 0.0,
        chapter: String? = null,
        lastReadAtMs: Long? = null,
    ) = BookItem(
        Book("primary:Books/$title", "$title.${format.extension}", format), title, author, progress, chapter,
        if (format == BookFormat.EPUB) 0 else 11, if (format == BookFormat.EPUB) 0 else 40, null, lastReadAtMs,
    )

    private fun solid(color: Color): ImageBitmap {
        val bitmap = ImageBitmap(192, 268)
        Canvas(bitmap).drawRect(0f, 0f, 192f, 268f, Paint().apply { this.color = color })
        return bitmap
    }

    private val moby = item("Moby-Dick", BookFormat.EPUB, "Melville", 0.38, "Ch. 41", lastReadAtMs = 1_000L)

    private val library = listOf(
        moby,
        item("Pride and Prejudice", BookFormat.EPUB, "Austen"),
        item("The Art of War", BookFormat.EPUB, "Sun Tzu", 1.0),
        item("Outlander manual", BookFormat.PDF, progress = 0.12),
        item("Lease 2026", BookFormat.PDF),
        item("Little Nemo", BookFormat.CBZ, progress = 0.6),
        item("Krazy Kat", BookFormat.CBR),
    )

    private val populated = BooksUiState(
        folder = BooksFolderState.Granted("/sdcard/Books"),
        scanned = true,
        items = library,
        resume = ContinueUi(moby, "Ch. 41 · 38% · 2 h ago"),
    )

    /** Covers for a few; null for the rest, so the sheet shows both a cover and the words fallback. */
    private val covers: suspend (Book) -> ImageBitmap? = { book ->
        when {
            book.fileName.startsWith("Moby") -> solid(Color(0xFF3A4A6B))
            book.fileName.startsWith("Pride") -> solid(Color(0xFF6B3A4F))
            book.fileName.startsWith("Little") -> solid(Color(0xFF7A5A1F))
            else -> null
        }
    }

    private fun sheet(fileName: String, state: BooksUiState) {
        composeTestRule.setContent {
            SoftTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF000000))) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .fillMaxHeight(0.93f)
                            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                            .background(SoftColors.card),
                    ) { BooksSheetContent(BooksHome(state = state, cover = covers), onClose = {}) }
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(fileName)
    }

    @Test
    fun `books sheet - continue card and the three shelves`() {
        sheet("books-sheet.png", populated)
    }

    @Test
    fun `books sheet - no folder granted says so with a button`() {
        sheet("books-sheet-no-folder.png", BooksUiState())
    }

    @Test
    fun `books sheet - folder permission lost says so with the fix`() {
        sheet("books-sheet-lost.png", BooksUiState(folder = BooksFolderState.Lost("/sdcard/Books")))
    }

    @Test
    fun `books sheet - empty folder says so with the fix`() {
        sheet("books-sheet-empty.png", BooksUiState(folder = BooksFolderState.Granted("/sdcard/Books"), scanned = true))
    }

    @Test
    fun `books sheet - a refused listen is said in words`() {
        sheet(
            "books-sheet-notice.png",
            populated.copy(notice = com.kevin.legion.ui.home.BookNotice(BookFormat.LISTEN_EBOOKS_ONLY, failed = true)),
        )
    }

    // ---- the reader --------------------------------------------------------------------------

    private fun epubState(theme: ReaderTheme, panel: ReaderPanel = ReaderPanel.NONE, listening: Boolean = false) =
        ReaderUiState(
            title = "Moby-Dick",
            subtitle = "Ch. 41 · Moby Dick",
            format = BookFormat.EPUB,
            content = ReaderContent.Epub,
            settings = ReaderSettings(theme = theme),
            panel = panel,
            positionLabel = "Page 212 of 560",
            percentLabel = "38%",
            timeLeft = "14 min left in chapter",
            progress = 0.38f,
            hasPosition = true,
            readAloud = if (listening) ReadAloudUi(active = true, playing = true) else ReadAloudUi(),
        )

    @Composable
    private fun PrototypePage(palette: ReaderPalette, listening: Boolean) {
        val mark = if (listening) palette.ttsMark else Color.Transparent
        val text: AnnotatedString = buildAnnotatedString {
            append(
                "I, Ishmael, was one of that crew; my shouts had gone up with the rest; my oath had been " +
                    "welded with theirs; and stronger I shouted, and more did I hammer and clinch my oath, " +
                    "because of the dread in my soul.\n\n",
            )
            withStyle(SpanStyle(background = mark)) {
                append("A wild, mystical, sympathetical feeling was in me; Ahab's quenchless feud seemed mine.")
            }
            append(
                " With greedy ears I learned the history of that murderous monster against whom I and all the " +
                    "others had taken our oaths of violence and revenge.\n\n" +
                    "For some time past, though at intervals only, the unaccompanied, secluded White Whale had " +
                    "haunted those uncivilized seas mostly frequented by the Sperm Whale fishermen.",
            )
        }
        Text(
            text,
            color = palette.text,
            fontFamily = FontFamily.Serif,
            fontSize = 17.sp,
            lineHeight = 27.sp,
            modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 12.dp),
        )
    }

    private fun reader(fileName: String, state: ReaderUiState) {
        composeTestRule.setContent {
            SoftTheme {
                ReaderScreen(state, ReaderActions()) {
                    PrototypePage(ReaderPalette.of(state.settings.theme), state.readAloud.active)
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(fileName)
    }

    @Test
    fun `reader - Day`() {
        reader("books-reader-day.png", epubState(ReaderTheme.DAY))
    }

    @Test
    fun `reader - Sepia with the text settings open`() {
        reader("books-reader-sepia.png", epubState(ReaderTheme.SEPIA, ReaderPanel.SETTINGS))
    }

    @Test
    fun `reader - Night while reading aloud`() {
        reader("books-reader-night.png", epubState(ReaderTheme.NIGHT, listening = true))
    }
}
