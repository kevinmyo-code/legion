package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.ui.apps.DrawerApp
import com.kevin.legion.ui.apps.iconKey
import com.kevin.legion.ui.apps.letterFolders
import com.kevin.legion.ui.theme.soft.LocalTypesetEdition
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme
import com.kevin.legion.ui.theme.soft.TypesetEdition
import com.kevin.legion.ui.typeset.TypesetAppsIndex
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Typeset app tray (ADR 0081) in letter buckets again (home-ui 2026-11-17): the folder grid by
 * day and by night, a folder open over the page, and the find line's flat matches with "work" and
 * "blocked" in words. Real launcher icons need a device, so each made-up app gets a solid square;
 * one app has no icon, to show the monogram it falls back to.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TypesetAppsIndexScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun app(label: String, pkg: String, work: Boolean = false) =
        DrawerApp(
            label = label, packageName = pkg, className = "$pkg.Main", isWork = work, profileKey = if (work) 1 else 0,
        )

    // Made-up apps and packages; the two "feed" ones are what the blocker would mark.
    private val apps = listOf(
        app("Authenticator", "example.auth"),
        app("Atlas", "example.atlas"),
        app("Books", "example.books"),
        app("Browser", "example.browser"),
        app("Camera", "example.camera"),
        app("Calendar", "example.calendar"),
        app("Clockwork", "example.clockwork"),
        app("Contacts", "example.contacts"),
        app("Courier", "example.courier"),
        app("Feedly", "example.feed.one"),
        app("Files", "example.files"),
        app("Mail", "example.mail"),
        app("Mail", "example.mail", work = true),
        app("Maps", "example.maps"),
        app("Scroller", "example.feed.two"),
        app("Weather", "example.weather"),
        app("7 Minute", "example.seven"),
    )

    private val palette = listOf(
        Color(0xFF3F7D5C), Color(0xFF4A6AA8), Color(0xFFB3862A), Color(0xFFA0475E), Color(0xFF2F7F86),
    )

    private val icons: Map<String, ImageBitmap> = apps.filter { it.label != "Courier" }.mapIndexed { i, a ->
        val bitmap = ImageBitmap(48, 48)
        Canvas(bitmap).drawRect(0f, 0f, 48f, 48f, Paint().apply { color = palette[i % palette.size] })
        iconKey(a.profileKey, a.packageName, a.className) to bitmap
    }.toMap()

    private fun shot(file: String, query: String, edition: TypesetEdition, open: String? = null) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalTypesetEdition provides edition) {
                SoftTheme {
                    Column(Modifier.fillMaxSize().background(SoftColors.ground)) {
                        TypesetAppsIndex(
                            folders = letterFolders(apps.filter { it.label.contains(query, ignoreCase = true) }),
                            query = query,
                            onQueryChange = {},
                            blocked = { it.packageName.startsWith("example.feed.") },
                            onOpen = {},
                            onLongPress = {},
                            workPaused = false,
                            icons = icons,
                            openFolder = open,
                        )
                    }
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(file)
    }

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
    fun `the letter folders, day`() = shot("typeset-apps-index.png", "", TypesetEdition.DAY)

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS + "-night")
    fun `the letter folders, night`() = shot("typeset-apps-index-night.png", "", TypesetEdition.NIGHT)

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
    fun `folder C open over the page, one app without an icon, day`() =
        shot("typeset-apps-folder-open-day.png", "", TypesetEdition.DAY, open = "C")

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS + "-night")
    fun `folder M open, a work app says so, night`() =
        shot("typeset-apps-folder-open-night.png", "", TypesetEdition.NIGHT, open = "M")

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
    fun `the find line's flat matches, day`() = shot("typeset-apps-index-find-day.png", "ma", TypesetEdition.DAY)

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS + "-night")
    fun `the index narrowed by the find line, night`() =
        shot("typeset-apps-index-find-night.png", "ma", TypesetEdition.NIGHT)
}
