package com.kevin.legion.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.kevin.legion.ui.apps.DockPin
import com.kevin.legion.ui.apps.DrawerApp
import com.kevin.legion.ui.apps.HomeCategory
import com.kevin.legion.ui.apps.Loaded
import com.kevin.legion.ui.theme.soft.SoftTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Home-launcher ticket 07 section 3, driven through a real composition: what a tap, a long-press,
 * the "Open with" sheet and the chooser do, and that the words for unset / not installed are on
 * screen. The wiring that persists picks is `CategoryPicksStore`; here [CategoryCallbacks.onSave]
 * is a recorder.
 */
@RunWith(RobolectricTestRunner::class)
class CategoryRowTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val gmail = DrawerApp("Gmail", "com.google.android.gm", "gm.Main", isWork = false, profileKey = 0)
    private val outlookWork =
        DrawerApp("Outlook", "com.microsoft.office.outlook", "o.Main", isWork = true, profileKey = 10)
    private val spotify = DrawerApp("Spotify", "com.spotify.music", "s.Main", isWork = false, profileKey = 0)
    private val apps = listOf(gmail, outlookWork, spotify)
    private val loaded = Loaded(apps, emptyMap(), emptyMap(), null, false)

    private fun pin(app: DrawerApp) = DockPin(app.packageName, app.profileKey)

    private val launched = mutableListOf<DockSlotUi>()
    private val saved = mutableListOf<Pair<HomeCategory, List<DockPin>>>()

    private fun show(picks: Map<HomeCategory, List<DockPin>>) {
        val categories = HomeCategory.entries.map { CategoryUi(it, buildDockSlots(picks[it].orEmpty(), loaded)) }
        rule.setContent {
            SoftTheme {
                CategoryRow(
                    categories = categories,
                    chooserRows = buildChooserRows(loaded),
                    callbacks = CategoryCallbacks(onLaunch = { launched += it }, onSave = { c, p -> saved += c to p }),
                )
            }
        }
    }

    @Test
    fun `an unset category says so and a tap opens the chooser, not an app`() {
        show(emptyMap())
        rule.onNodeWithContentDescription("Banking, not set up. Tap to choose apps.").performClick()
        rule.onNodeWithText("Apps for Banking").assertIsDisplayed()
        rule.onNodeWithText(
            "Pick one or more. One app opens straight away; more than one asks which.",
        ).assertIsDisplayed()
        assertTrue(launched.isEmpty())
    }

    @Test
    fun `one pick launches straight away with no sheet`() {
        show(mapOf(HomeCategory.MUSIC to listOf(pin(spotify))))
        rule.onNodeWithContentDescription("Music, opens Spotify").performClick()
        assertEquals(listOf(pin(spotify)), launched.map { it.pin })
        assertEquals(0, rule.onAllNodesWithText("Open with").fetchSemanticsNodes().size)
    }

    @Test
    fun `several picks ask which, WORK in words, and a row launches that app`() {
        show(mapOf(HomeCategory.MAIL to listOf(pin(gmail), pin(outlookWork))))
        rule.onNodeWithContentDescription("Email, 2 apps, asks which to open").performClick()
        rule.onNodeWithText("Open with").assertIsDisplayed()
        rule.onNodeWithText("WORK").assertIsDisplayed()
        rule.onNodeWithText("Outlook").performClick()
        assertEquals(listOf(pin(outlookWork)), launched.map { it.pin })
        assertEquals(0, rule.onAllNodesWithText("Open with").fetchSemanticsNodes().size)
    }

    @Test
    fun `Choose apps in the Open with sheet goes to the chooser`() {
        show(mapOf(HomeCategory.MAIL to listOf(pin(gmail), pin(outlookWork))))
        rule.onNodeWithContentDescription("Email, 2 apps, asks which to open").performClick()
        rule.onNodeWithText("Choose apps").performClick()
        rule.onNodeWithText("Apps for Email").assertIsDisplayed()
        assertTrue(launched.isEmpty())
    }

    @Test
    fun `long-press opens the chooser even when one app is picked`() {
        show(mapOf(HomeCategory.MUSIC to listOf(pin(spotify))))
        rule.onNodeWithContentDescription("Music, opens Spotify").performTouchInput { longClick() }
        rule.onNodeWithText("Apps for Music").assertIsDisplayed()
        assertTrue(launched.isEmpty())
    }

    @Test
    fun `ticking two apps and saving hands back both, in tick order`() {
        show(emptyMap())
        rule.onNodeWithContentDescription("Email, not set up. Tap to choose apps.").performClick()
        rule.onNodeWithText("Outlook").performClick()
        rule.onNodeWithText("Gmail").performClick()
        rule.onNodeWithText("Save").performClick()
        assertEquals(listOf(HomeCategory.MAIL to listOf(pin(outlookWork), pin(gmail))), saved)
    }

    @Test
    fun `saving with nothing ticked is allowed and says it leaves the button unset`() {
        show(emptyMap())
        rule.onNodeWithContentDescription("Banking, not set up. Tap to choose apps.").performClick()
        rule.onNodeWithText("Nothing ticked. Saving leaves this button as not set up.").assertIsDisplayed()
        rule.onNodeWithText("Save").performClick()
        assertEquals(listOf(HomeCategory.BANK to emptyList<DockPin>()), saved)
    }

    @Test
    fun `cancel saves nothing`() {
        show(emptyMap())
        rule.onNodeWithContentDescription("Banking, not set up. Tap to choose apps.").performClick()
        rule.onNodeWithText("Gmail").performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `the search field narrows the chooser`() {
        show(emptyMap())
        rule.onNodeWithContentDescription("Banking, not set up. Tap to choose apps.").performClick()
        rule.onNodeWithText("Search apps").performTextInput("spot")
        assertEquals(0, rule.onAllNodesWithText("Gmail").fetchSemanticsNodes().size)
        rule.onNodeWithText("Spotify").assertIsDisplayed()
    }

    @Test
    fun `a pick that is no longer installed is listed dimmed and worded, and unticking drops it`() {
        val gone = DockPin("com.gone.bank", 0)
        show(mapOf(HomeCategory.BANK to listOf(gone)))
        // The button itself says it, in words, instead of silently looking set up.
        rule.onNodeWithText("Not installed").assertIsDisplayed()
        rule.onNodeWithContentDescription("Banking, the picked app is not installed").performTouchInput { longClick() }
        rule.onNodeWithText("com.gone.bank").assertIsDisplayed()
        rule.onNodeWithText("com.gone.bank").performClick() // untick
        rule.onNodeWithText("Save").performClick()
        assertEquals(listOf(HomeCategory.BANK to emptyList<DockPin>()), saved)
    }

    @Test
    fun `a tap on a picked app that is not installed still reaches the owner, who says why`() {
        show(mapOf(HomeCategory.BANK to listOf(DockPin("com.gone.bank", 0))))
        rule.onNodeWithContentDescription("Banking, the picked app is not installed").performClick()
        assertEquals(1, launched.size)
        assertEquals(null, launched.single().app)
    }
}
