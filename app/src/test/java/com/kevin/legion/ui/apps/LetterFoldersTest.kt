package com.kevin.legion.ui.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterFoldersTest {

    private fun app(label: String, pkg: String = "com.x.${label.hashCode()}", work: Boolean = false) =
        DrawerApp(label, pkg, "$pkg.Main", work, if (work) 10 else 0)

    @Test
    fun `an empty list gives no folders`() {
        assertTrue(letterFolders(emptyList()).isEmpty())
    }

    @Test
    fun `one folder per used letter, A to Z in order, with counts`() {
        val folders = letterFolders(listOf(app("Waze"), app("Amazon"), app("Authenticator"), app("Maps")))
        assertEquals(listOf("A", "M", "W"), folders.map { it.letter })
        assertEquals(listOf(2, 1, 1), folders.map { it.apps.size })
        assertEquals(listOf("Amazon", "Authenticator"), folders.first().apps.map { it.label })
    }

    @Test
    fun `lowercase labels file under the uppercase letter`() {
        assertEquals(listOf("A"), letterFolders(listOf(app("authenticator"))).map { it.letter })
    }

    @Test
    fun `digits, symbols and non-Latin go in one hash folder sorted last`() {
        val folders = letterFolders(
            listOf(app("1Password"), app("Zelle"), app("Карты"), app("@home"), app("Amazon")),
        )
        assertEquals(listOf("A", "Z", "#"), folders.map { it.letter })
        assertEquals(3, folders.last().apps.size)
    }

    @Test
    fun `accented Latin letters fold to their base letter`() {
        val folders = letterFolders(listOf(app("École"), app("Email"), app("élan")))
        assertEquals(listOf("E"), folders.map { it.letter })
        assertEquals(3, folders.single().apps.size)
    }

    @Test
    fun `a blank label is not dropped`() {
        assertEquals(listOf("#"), letterFolders(listOf(app("  "))).map { it.letter })
    }

    @Test
    fun `a personal app and its work twin share a folder, personal first`() {
        val folders = letterFolders(
            listOf(app("Outlook", "com.ms.outlook", work = true), app("Outlook", "com.ms.outlook")),
        )
        val outlooks = folders.single().apps
        assertEquals("O", folders.single().letter)
        assertEquals(listOf(false, true), outlooks.map { it.isWork })
    }
}
