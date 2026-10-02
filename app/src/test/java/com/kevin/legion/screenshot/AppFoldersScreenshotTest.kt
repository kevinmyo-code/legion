package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.ui.apps.AppIconGrid
import com.kevin.legion.ui.apps.DrawerApp
import com.kevin.legion.ui.apps.FolderDialog
import com.kevin.legion.ui.apps.FolderGrid
import com.kevin.legion.ui.apps.filterDrawer
import com.kevin.legion.ui.apps.iconKey
import com.kevin.legion.ui.apps.letterFolders
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Home-launcher ticket 07's verification step 2: the Apps letter-folder grid, an open folder, and
 * the flat search grid. Real launcher icons need a device, so each fake app gets a solid-colour
 * square from [fakeIcon]; what these shots prove is layout, labels and the WORK wording, not icon art.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
class AppFoldersScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val palette = listOf(
        Color(0xFF7EDBA5), Color(0xFFA8C8FF), Color(0xFFFFD36B),
        Color(0xFFFFB1C3), Color(0xFF77DCE5), Color(0xFFCDBDFF),
    )

    private fun fakeIcon(color: Color): ImageBitmap {
        val bitmap = ImageBitmap(48, 48)
        Canvas(bitmap).drawRect(0f, 0f, 48f, 48f, Paint().apply { this.color = color })
        return bitmap
    }

    private val labels = listOf(
        "Amazon", "Authenticator", "BofA", "Calculator", "Camera", "Chrome", "Clock", "Contacts", "DBS digibank",
        "Discord", "Drive", "Éclair", "Fitbit", "Gmail", "Google Maps", "H-E-B", "Instagram", "Keep Notes",
        "Messages", "Netflix", "Outlook", "Outlook", "Phone", "Photos", "Spotify", "Teams", "Uber", "Waze",
        "WhatsApp", "YouTube", "1Password", "Zelle",
    )

    private val apps: List<DrawerApp> = labels.mapIndexed { i, label ->
        // The second Outlook, Authenticator and Teams are work-profile apps.
        val work = (label == "Outlook" && i == labels.lastIndexOf("Outlook")) ||
            label == "Authenticator" || label == "Teams"
        DrawerApp(label, "com.fake.p$i", "com.fake.p$i.Main", work, if (work) 10 else 0)
    }

    private val icons: Map<String, ImageBitmap> =
        apps.mapIndexed { i, a ->
            iconKey(a.profileKey, a.packageName, a.className) to fakeIcon(palette[i % palette.size])
        }.toMap()

    private fun capture(fileName: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        composeTestRule.setContent {
            SoftTheme {
                Column(Modifier.fillMaxSize().background(SoftColors.ground).padding(16.dp)) { content() }
            }
        }
        composeTestRule.onRoot().captureRoboImage(fileName)
    }

    @Test
    fun `folder grid - one tile per letter, hash last`() {
        capture("apps-folders.png") {
            FolderGrid(folders = letterFolders(apps), icons = icons, onOpenFolder = {})
        }
    }

    @Test
    fun `open folder - letter, count, three-column icons, WORK in words`() {
        // Outlook (personal and work) plus Teams, hand-filed under "O" so the dialog shows WORK twice.
        val oFolder = letterFolders(apps).first { it.letter == "O" }
        val folder = oFolder.copy(apps = oFolder.apps + apps.filter { it.label == "Teams" })
        capture("apps-folder-open.png") {
            FolderGrid(folders = letterFolders(apps), icons = icons, onOpenFolder = {})
            FolderDialog(
                folder = folder, icons = icons, workPaused = false,
                onDismiss = {}, onOpen = {}, onLongPress = {},
            )
        }
    }

    @Test
    fun `search - a flat four-column grid`() {
        capture("apps-search.png") {
            AppIconGrid(
                apps = filterDrawer(apps, "ma"), icons = icons, workPaused = false, columns = 4,
                onOpen = {}, onLongPress = {}, modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
