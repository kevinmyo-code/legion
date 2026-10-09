package com.kevin.legion.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.kevin.legion.ui.apps.DockPin
import com.kevin.legion.ui.apps.DrawerApp
import com.kevin.legion.ui.apps.HomeCategory
import com.kevin.legion.ui.apps.Loaded
import com.kevin.legion.ui.apps.iconKey
import com.kevin.legion.ui.navigation.LocalNavEntryPoints
import com.kevin.legion.ui.navigation.NavEntryPoints
import com.kevin.legion.ui.theme.soft.SoftTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * One-home ticket 11, dock changes. (1) A bucket with exactly one app wears that app's icon.
 * (2) Google Maps can be added to the Map bucket: it was unreachable because commit 3d5376a6 made
 * the Maps button's tap AND long-press both open LEGION's navigation, so the chooser never opened.
 * ADR 0054 forbids handing navigation to another map app; a launcher entry is not that.
 */
@RunWith(RobolectricTestRunner::class)
class CategoryMapsAndIconTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private companion object {
        const val MAPS_UNSET = "Navigation, opens LEGION Navigation. Long-press to add a map app."
        const val MAPS_ONE_PICKED = "Navigation, LEGION Navigation or 1 picked app, asks which to open"
    }

    private val gmail = DrawerApp("Gmail", "com.google.android.gm", "gm.Main", isWork = false, profileKey = 0)
    private val googleMaps =
        DrawerApp("Google Maps", "com.google.android.apps.maps", "maps.Main", isWork = false, profileKey = 0)
    private val waze = DrawerApp("Waze", "com.waze", "waze.Main", isWork = false, profileKey = 0)
    private val apps = listOf(gmail, googleMaps, waze)

    private fun icon(): ImageBitmap = ImageBitmap(8, 8).also { Canvas(it) }

    private fun loaded(withIcons: Boolean = true): Loaded {
        val icons = if (withIcons) {
            apps.associate { iconKey(it.profileKey, it.packageName, it.className) to icon() }
        } else {
            emptyMap()
        }
        return Loaded(apps, icons, emptyMap(), null, false)
    }

    private fun pin(app: DrawerApp) = DockPin(app.packageName, app.profileKey)

    private fun ui(category: HomeCategory, picks: List<DrawerApp>, withIcons: Boolean = true) =
        CategoryUi(category, buildDockSlots(picks.map(::pin), loaded(withIcons)))

    // ----------------------------------------------------------------- single-app icon rule

    @Test
    fun `exactly one installed pick shows that app's own icon`() {
        assertNotNull(singleAppIcon(ui(HomeCategory.MAIL, listOf(gmail))))
    }

    @Test
    fun `two picks keep the bucket glyph`() {
        assertNull(singleAppIcon(ui(HomeCategory.MAIL, listOf(gmail, waze))))
    }

    @Test
    fun `no picks keep today's look`() {
        assertNull(singleAppIcon(ui(HomeCategory.MAIL, emptyList())))
    }

    @Test
    fun `one pick whose icon has not loaded keeps the glyph rather than a blank`() {
        assertNull(singleAppIcon(ui(HomeCategory.MAIL, listOf(gmail), withIcons = false)))
    }

    @Test
    fun `one pick that is no longer installed keeps the glyph`() {
        val gone = CategoryUi(HomeCategory.MAIL, buildDockSlots(listOf(DockPin("com.gone", 0)), loaded()))
        assertNull(singleAppIcon(gone))
    }

    @Test
    fun `a cold-start snapshot that has not loaded shows no icon`() {
        val loading = CategoryUi(HomeCategory.MAIL, buildDockSlots(listOf(pin(gmail)), null))
        assertNull(singleAppIcon(loading))
    }

    // ----------------------------------------------------------------- maps

    @Test
    fun `maps with nothing picked opens navigation, with a picked app it asks which`() {
        assertEquals(MapsTap.OpenNavigation, mapsTap(emptyList()))
        assertEquals(MapsTap.AskWhich, mapsTap(ui(HomeCategory.MAPS, listOf(googleMaps)).slots))
    }

    @Test
    fun `a missing picked map app never leaves the Maps button dead or dimmed`() {
        val gone = CategoryUi(HomeCategory.MAPS, buildDockSlots(listOf(DockPin("com.gone", 0)), loaded()))
        val spec = categoryButtonSpec(gone)
        assertEquals(false, spec.dimmed)
        assertTrue(spec.description.contains("LEGION Navigation"))
    }

    private var navOpened = 0
    private val saved = mutableListOf<Pair<HomeCategory, List<DockPin>>>()
    private val launched = mutableListOf<DockSlotUi>()

    private fun show(picks: Map<HomeCategory, List<DrawerApp>>) {
        val categories = HomeCategory.entries.map { ui(it, picks[it].orEmpty()) }
        rule.setContent {
            CompositionLocalProvider(LocalNavEntryPoints provides NavEntryPoints(open = { navOpened++ })) {
                SoftTheme {
                    CategoryRow(
                        categories = categories,
                        chooserRows = buildChooserRows(loaded()),
                        callbacks = CategoryCallbacks(
                            onLaunch = { launched += it },
                            onSave = { c, p -> saved += c to p },
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun `tapping Maps with nothing picked still opens LEGION navigation`() {
        show(emptyMap())
        rule.onNodeWithContentDescription(MAPS_UNSET).performClick()
        assertEquals(1, navOpened)
    }

    @Test
    fun `long-pressing Maps opens the chooser and Google Maps can be picked and saved`() {
        show(emptyMap())
        rule.onNodeWithContentDescription("Navigation, opens LEGION Navigation. Long-press to add a map app.")
            .performTouchInput { longClick() }
        assertEquals(0, navOpened)
        rule.onNodeWithText("Apps for Navigation").assertIsDisplayed()
        rule.onNodeWithText("Google Maps").performClick()
        rule.onNodeWithText("Save").performClick()
        assertEquals(listOf(HomeCategory.MAPS to listOf(pin(googleMaps))), saved)
    }

    @Test
    fun `with Google Maps picked, a tap offers LEGION Navigation and Google Maps, and neither is forced`() {
        show(mapOf(HomeCategory.MAPS to listOf(googleMaps)))
        rule.onNodeWithContentDescription(MAPS_ONE_PICKED).performClick()
        rule.onNodeWithText("LEGION Navigation").assertIsDisplayed()
        rule.onNodeWithText("Google Maps").assertIsDisplayed()
        // Choosing the built-in one opens navigation; the picked app is only launched if chosen.
        rule.onNodeWithText("LEGION Navigation").performClick()
        assertEquals(1, navOpened)
        assertTrue(launched.isEmpty())
    }

    @Test
    fun `choosing the picked map app from the sheet launches it as an ordinary app`() {
        show(mapOf(HomeCategory.MAPS to listOf(googleMaps)))
        rule.onNodeWithContentDescription(MAPS_ONE_PICKED).performClick()
        rule.onNodeWithText("Google Maps").performClick()
        assertEquals(listOf(pin(googleMaps)), launched.map { it.pin })
        assertEquals(0, navOpened)
    }
}
