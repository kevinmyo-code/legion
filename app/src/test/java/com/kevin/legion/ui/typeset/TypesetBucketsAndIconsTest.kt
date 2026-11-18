package com.kevin.legion.ui.typeset

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
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
            var open by remember { mutableStateOf<String?>(null) }
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
    fun `contents keep a 48dp cell, seven across 360dp, six across 320dp, five stay five`() {
        assertEquals(7, contentsColumns(7, 360.dp))
        assertEquals(6, contentsColumns(7, 320.dp))
        assertEquals(5, contentsColumns(5, 320.dp))
    }

    @Test
    fun `an open folder is modal, a tap on its blank space keeps it open`() {
        tray()
        composeTestRule.onNodeWithContentDescription("Folder A, 2 apps").performClick()
        // The page behind is out of reach while the folder is open.
        composeTestRule.onNodeWithContentDescription("Folder B, 1 app").assertDoesNotExist()
        // "2 apps" is plain text on the panel: tapping it is a tap on blank panel, not the scrim.
        composeTestRule.onNodeWithText("2 apps").performClick()
        composeTestRule.onNodeWithText("Atlas").assertExists()
    }

    @Test
    fun `a folder whose letter goes away closes itself`() {
        var apps by mutableStateOf(this.apps)
        var open by mutableStateOf<String?>("B")
        composeTestRule.setContent {
            SoftTheme {
                TypesetAppsIndex(
                    folders = letterFolders(apps),
                    query = "",
                    onQueryChange = {},
                    blocked = { false },
                    onOpen = {},
                    onLongPress = {},
                    openFolder = open,
                    onOpenFolder = { open = it },
                )
            }
        }
        composeTestRule.onNodeWithText("Beacon").assertExists()
        apps = apps.filter { it.label != "Beacon" }
        composeTestRule.waitForIdle()
        assertNull(open)
    }

    @Test
    fun `the pin confirmation offers Add and Cancel, and a tap on the panel cancels nothing`() {
        var added = 0
        var cancelled = 0
        composeTestRule.setContent {
            SoftTheme {
                com.kevin.legion.ui.apps.PinShortcutSheet(
                    "Weather board", "Browser", mark = null, onAdd = { added++ }, onCancel = { cancelled++ },
                )
            }
        }
        // Its name and its maker before Add (review 2026-11-18); tapping them is not a cancel.
        composeTestRule.onNodeWithText("Weather board").assertExists()
        composeTestRule.onNodeWithText("A shortcut from Browser").performClick()
        assertEquals(0, cancelled)
        composeTestRule.onNodeWithText("Add").performClick()
        assertEquals(1, added)
        composeTestRule.onNodeWithText("Cancel").performClick()
        assertEquals(1, cancelled)
    }

    @Test
    fun `a pinned web shortcut says who made it, under its name, in All apps`() {
        val shortcut = com.kevin.legion.ui.apps.PinnedShortcuts.asDrawerApp(
            "example.browser", "w1", "Bank login", false, 0, origin = "Browser",
        )
        composeTestRule.setContent {
            SoftTheme {
                TypesetAppsIndex(
                    folders = letterFolders(apps + shortcut),
                    query = "bank",
                    onQueryChange = {},
                    blocked = { false },
                    onOpen = {},
                    onLongPress = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Bank login").assertExists()
        composeTestRule.onNodeWithText("Browser").assertExists()
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
