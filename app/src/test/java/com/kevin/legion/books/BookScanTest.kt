package com.kevin.legion.books

import com.kevin.legion.games.RomTree
import com.kevin.legion.games.TreeChild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookScanTest {
    private fun tree(vararg folders: Pair<String, List<TreeChild>>): RomTree {
        val map = folders.toMap()
        return RomTree { id -> map[id] ?: error("no such folder $id") }
    }

    private fun file(id: String, name: String) = TreeChild(id, name, isDirectory = false)

    private fun dir(id: String, name: String) = TreeChild(id, name, isDirectory = true)

    @Test fun `only the four book formats are books and the extension is case-insensitive`() {
        val books = BookScan.books(
            listOf(
                BookEntry("1", "Moby-Dick.EPUB"),
                BookEntry("2", "manual.pdf"),
                BookEntry("3", "nemo.cbz"),
                BookEntry("4", "kat.cbr"),
                BookEntry("5", "cover.jpg"),
                BookEntry("6", "notes.txt"),
                BookEntry("7", "noextension"),
            ),
        )
        assertEquals(listOf(BookFormat.EPUB, BookFormat.PDF, BookFormat.CBZ, BookFormat.CBR).toSet(), books.map { it.format }.toSet())
        assertEquals(4, books.size)
    }

    @Test fun `books sort by title in natural order`() {
        val books = BookScan.books(
            listOf(
                BookEntry("a", "Volume 10.cbz"),
                BookEntry("b", "volume 2.cbz"),
                BookEntry("c", "Alpha.epub"),
            ),
        )
        assertEquals(listOf("Alpha", "volume 2", "Volume 10"), books.map { it.fileTitle })
    }

    @Test fun `natural order handles leading zeros and mixed text`() {
        val pages = listOf("p10.jpg", "p2.jpg", "p01.jpg", "p1a.jpg").sortedWith(NaturalOrder)
        assertEquals(listOf("p01.jpg", "p1a.jpg", "p2.jpg", "p10.jpg"), pages)
    }

    @Test fun `underscores in a file name read as spaces in the title`() {
        assertEquals("The Art of War", Book("1", "The_Art_of_War.epub", BookFormat.EPUB).fileTitle)
    }

    @Test fun `a cbr is listed but not readable and not listenable`() {
        assertFalse(BookFormat.CBR.readable)
        assertTrue(BookFormat.CBZ.readable)
        assertEquals(BookShelf.COMICS, BookFormat.CBR.shelf)
        assertTrue(BookFormat.EPUB.listenable)
        assertFalse(BookFormat.PDF.listenable)
        assertFalse(BookFormat.CBZ.listenable)
    }

    @Test fun `the walk finds books in nested folders down to the depth limit and no further`() {
        val library = BookLibrary(
            tree(
                "root" to listOf(file("f1", "a.epub"), dir("d1", "Comics")),
                "d1" to listOf(file("f2", "b.cbz"), dir("d2", "Old")),
                "d2" to listOf(file("f3", "c.pdf"), dir("d3", "Deeper")),
                "d3" to listOf(file("f4", "d.epub"), dir("d4", "Deepest")),
                "d4" to listOf(file("f5", "too-deep.epub")),
            ),
        )
        val result = library.scan("root")
        assertFalse(result.unreadable)
        assertEquals(setOf("a", "b", "c", "d"), result.books.map { it.fileTitle }.toSet())
    }

    @Test fun `an unreadable root is not an empty folder`() {
        val result = BookLibrary { error("denied") }.scan("root")
        assertTrue(result.unreadable)
        assertTrue(result.books.isEmpty())
    }

    @Test fun `one unreadable subfolder does not hide the books beside it`() {
        val library = BookLibrary(
            RomTree { id ->
                when (id) {
                    "root" -> listOf(file("f1", "a.epub"), dir("bad", "Broken"))
                    else -> error("provider died")
                }
            },
        )
        assertEquals(listOf("a"), library.scan("root").books.map { it.fileTitle })
    }

    @Test fun `an empty folder is empty and readable`() {
        val result = BookLibrary(tree("root" to emptyList())).scan("root")
        assertFalse(result.unreadable)
        assertTrue(result.books.isEmpty())
    }
}
