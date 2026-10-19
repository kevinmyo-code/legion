package com.kevin.legion.books

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookMatcherTest {
    private fun item(title: String, author: String? = null, file: String = "$title.epub", format: BookFormat = BookFormat.EPUB) =
        BookItem(Book("id-$title", file, format), title, author, 0.0, null, 0, 0, null, null)

    private val shelf = listOf(
        item("Moby-Dick", "Melville"),
        item("Pride and Prejudice", "Austen"),
        item("Outlander manual", file = "Outlander manual.pdf", format = BookFormat.PDF),
        item("Volume 1", file = "Volume 1.cbz", format = BookFormat.CBZ),
        item("Volume 2", file = "Volume 2.cbz", format = BookFormat.CBZ),
    )

    @Test fun `a spoken name finds the book despite punctuation and case`() {
        val match = BookMatcher.find(shelf, "moby dick") as BookMatch.One
        assertEquals("Moby-Dick", match.item.title)
    }

    @Test fun `the author finds the book`() {
        val match = BookMatcher.find(shelf, "the one by Austen") as? BookMatch.One
        // "one" and "by" are not in the haystack, so a loose phrase must not match by luck
        assertEquals(null, match)
        assertEquals("Pride and Prejudice", ((BookMatcher.find(shelf, "austen")) as BookMatch.One).item.title)
    }

    @Test fun `two equally good matches are named, not guessed`() {
        val match = BookMatcher.find(shelf, "volume") as BookMatch.Several
        assertEquals(setOf("Volume 1", "Volume 2"), match.items.map { it.title }.toSet())
    }

    @Test fun `an exact title beats a partial one`() {
        val items = shelf + item("Volume 1 Special")
        assertEquals("Volume 1", (BookMatcher.find(items, "volume 1") as BookMatch.One).item.title)
    }

    @Test fun `no match and an empty query are None`() {
        assertEquals(BookMatch.None, BookMatcher.find(shelf, "dune"))
        assertEquals(BookMatch.None, BookMatcher.find(shelf, "   "))
        assertTrue(BookMatcher.find(emptyList(), "moby") is BookMatch.None)
    }
}
