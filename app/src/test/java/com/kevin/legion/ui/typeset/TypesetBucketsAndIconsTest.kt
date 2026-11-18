package com.kevin.legion.ui.typeset

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.kevin.legion.ui.apps.DockPin
import com.kevin.legion.ui.apps.DrawerApp
import com.kevin.legion.ui.apps.letterFolders
import com.kevin.legion.ui.home.DockSlotUi
import com.kevin.legion.ui.theme.legionDay
import com.kevin.legion.ui.theme.soft.SoftTheme
import com.kevin.legion.ui.theme.soft.TypesetEdition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Home-ui 2026-11-17: the All-apps letter buckets, the icon-over-label contents, and the day paper
 * rule for `LegionTheme`. Made-up apps only.
 */
@RunWith(RobolectricTestRunner::class)
class TypesetBucketsAndIconsTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun app(label: String, pkg: String, work: Boolean = false) =
        DrawerApp(label, pkg, "$pkg.Main", work, if (work) 1 else 0)

    private val apps = listOf(
        app("Atlas", "example.atlas"), app("Abacus", "example.abacus"), app("Beacon", "example.beacon"),
        app("Mail", "example.mail"), app("Mail", "example.mail", work = true), app("9 Lives", "example.nine"),
        app("Scroller", "example.feed.scroller"),
    )

    @Test
    fun `day paper is only an explicit DAY edition with Matrix off`() {
        assertTrue(legionDay(matrix = false, edition = TypesetEdition.DAY))
        assertFalse(legionDay(matrix = false, edition = TypesetEdition.NIGHT))
        assertFalse(legionDay(matrix = true, edition = TypesetEdition.DAY))
        // No edition provided (previews, every committed baseline): the old dark look.
        assertFalse(legionDay(matrix = false, edition = null))
    }

    @Test
    fun `every contents entry has its own glyph`() {
        val glyphs = TypesetHomeDerive.CONTENTS.map(::contentsIcon)
        assertEquals(glyphs.size, glyphs.toSet().size)
    }

    @Test
    fun `a pinned app carries its icon and says why it cannot open`() {
        val pin = DockPin("example.mail", 1)
        val paused = DockSlotUi(pin, apps[4], "Mail", icon = null, paused = true)
        assertEquals("Paused", slotTile(paused).status)
        val loading = DockSlotUi(pin, null, "", icon = null, paused = false, loading = true)
        assertNull(slotTile(loading).status)
        val gone = DockSlotUi(pin, null, "example.mail", icon = null, paused = false)
        assertEquals("Not installed", slotTile(gone).status)
    }

    private fun tray(query: String = "", onOpen: (DrawerApp) -> Unit = {}, onLong: (DrawerApp) -> Unit = {}) {
        composeTestRule.setContent {
            var open by mutableStateOf<String?>(null)
            SoftTheme {
                TypesetAppsIndex(
                    folders = letterFolders(apps.filter { it.label.contains(query, ignoreCase = true) }),
                    query = query,
                    onQueryChange = {},
                    blocked = { it.packageName.startsWith("example.feed.") },
                    onOpen = onOpen,
                    onLongPress = onLong,
                    workPaused = false,
                    openFolder = open,
                    onOpenFolder = { open = it },
                )
            }
        }
    }

    @Test
    fun `the tray is letter folders, A to Z then hash, with counts`() {
        tray()
        composeTestRule.onNodeWithContentDescription("Folder A, 2 apps").assertHasClickAction()
        composeTestRule.onNodeWithContentDescription("Folder B, 1 app").assertExists()
        composeTestRule.onNodeWithContentDescription("Folder M, 2 apps").assertExists()
        composeTestRule.onNodeWithContentDescription("Folder #, 1 app").assertExists()
        // Closed folders do not list their apps.
        composeTestRule.onNodeWithText("Atlas").assertDoesNotExist()
    }

    @Test
    fun `a folder opens over the page, its apps open and long press pins`() {
        var opened: DrawerApp? = null
        var pinned: DrawerApp? = null
        tray(onOpen = { opened = it }, onLong = { pinned = it })
        composeTestRule.onNodeWithContentDescription("Folder A, 2 apps").performClick()
        composeTestRule.onNodeWithText("2 apps").assertExists()
        composeTestRule.onNodeWithText("Atlas").performClick()
        assertEquals("example.atlas", opened?.packageName)
        composeTestRule.onNodeWithText("Abacus").performTouchInput { longClick() }
        assertEquals("example.abacus", pinned?.packageName)
        composeTestRule.onNodeWithText("Close").performClick()
        composeTestRule.onNodeWithText("Atlas").assertDoesNotExist()
    }

    @Test
    fun `finding an app shows every match flat, with work and blocked in words`() {
        tray(query = "l")
        composeTestRule.onNodeWithText("Atlas").assertExists()
        composeTestRule.onNodeWithText("work").assertExists()
        composeTestRule.onNodeWithText("Scroller").assertExists()
        composeTestRule.onNodeWithText("blocked").assertExists()
    }
}
