package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.ui.apps.DrawerApp
import com.kevin.legion.ui.apps.letterFolders
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme
import com.kevin.legion.ui.typeset.TypesetAppsIndex
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Typeset app tray (ADR 0081): an index by letter, names in full, "blocked" and "work" in words. */
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
        app("Books", "example.books"),
        app("Browser", "example.browser"),
        app("Camera", "example.camera"),
        app("Calendar", "example.calendar"),
        app("Feedly", "example.feed.one"),
        app("Files", "example.files"),
        app("Mail", "example.mail"),
        app("Mail", "example.mail", work = true),
        app("Maps", "example.maps"),
        app("Scroller", "example.feed.two"),
        app("Weather", "example.weather"),
    )

    private fun shot(file: String, query: String) {
        composeTestRule.setContent {
            SoftTheme {
                Column(Modifier.fillMaxSize().background(SoftColors.ground)) {
                    TypesetAppsIndex(
                        folders = letterFolders(apps.filter { it.label.contains(query, ignoreCase = true) }),
                        query = query,
                        onQueryChange = {},
                        blocked = { it.packageName.startsWith("example.feed.") },
                        onOpen = {},
                        onLongPress = {},
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(file)
    }

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
    fun `the index, day, two feed apps read blocked and a work app says so`() =
        shot("typeset-apps-index.png", "")

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS + "-night")
    fun `the index narrowed by the find line, night`() = shot("typeset-apps-index-find-night.png", "ma")
}
