package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.ui.apps.DockPin
import com.kevin.legion.ui.apps.DrawerApp
import com.kevin.legion.ui.apps.HomeCategory
import com.kevin.legion.ui.apps.Loaded
import com.kevin.legion.ui.apps.iconKey
import com.kevin.legion.ui.home.CategoryUi
import com.kevin.legion.ui.home.ChooserSheetContent
import com.kevin.legion.ui.home.OpenWithSheetContent
import com.kevin.legion.ui.home.buildChooserRows
import com.kevin.legion.ui.home.buildDockSlots
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Home-launcher ticket 07's verification step 2: the chooser sheet and the "Open with" sheet.
 * Both are drawn from their content composables inside a sheet-shaped container, over a scrim: a
 * real `ModalBottomSheet` is its own window, which Robolectric cannot place or capture reliably
 * (the same reason the settings dropdown shot draws its rows directly). Sheet open/close behaviour
 * is covered by `CategoryRowTest`; how the real window sits on the phone is owed there.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
class CategorySheetsScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val palette = listOf(
        Color(0xFF7EDBA5), Color(0xFFA8C8FF), Color(0xFFFFD36B),
        Color(0xFFFFB1C3), Color(0xFF77DCE5), Color(0xFFCDBDFF),
    )

    private fun fakeIcon(i: Int): ImageBitmap {
        val bitmap = ImageBitmap(48, 48)
        Canvas(bitmap).drawRect(0f, 0f, 48f, 48f, Paint().apply { color = palette[i % palette.size] })
        return bitmap
    }

    private fun app(label: String, pkg: String, work: Boolean = false) =
        DrawerApp(label, pkg, "$pkg.Main", work, if (work) 10 else 0)

    private val apps = listOf(
        app("Authenticator", "com.azure.authenticator", work = true),
        app("BofA", "com.infonow.bofa"),
        app("Calculator", "com.calc"),
        app("Camera", "com.cam"),
        app("DBS digibank", "com.dbs"),
        app("Gmail", "com.google.android.gm"),
        app("Google Maps", "com.google.android.apps.maps"),
        app("Messages", "com.google.android.apps.messaging"),
        app("Outlook", "com.microsoft.office.outlook"),
        app("Outlook", "com.microsoft.office.outlook", work = true),
        app("Spotify", "com.spotify.music"),
        app("Zelle", "com.zellepay.zelle"),
    )

    private val icons = apps.mapIndexed { i, a ->
        iconKey(a.profileKey, a.packageName, a.className) to fakeIcon(i)
    }.toMap()
    private val loaded = Loaded(apps, icons, emptyMap(), null, false)

    private fun pin(a: DrawerApp) = DockPin(a.packageName, a.profileKey)

    private fun sheet(
        fileName: String,
        heightFraction: Float,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        composeTestRule.setContent {
            SoftTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF000000))) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .fillMaxHeight(heightFraction)
                            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                            .background(SoftColors.card),
                    ) { content() }
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(fileName)
    }

    @Test
    fun `chooser - Email with Gmail and the work Outlook ticked, one stale pick`() {
        val gmail = apps.first { it.label == "Gmail" }
        val outlookWork = apps.first { it.label == "Outlook" && it.isWork }
        val gone = DockPin("com.old.mailclient", 0)
        val ui = CategoryUi(HomeCategory.MAIL, buildDockSlots(listOf(pin(gmail), pin(outlookWork), gone), loaded))
        sheet("category-chooser.png", heightFraction = 0.85f) {
            ChooserSheetContent(
                category = HomeCategory.MAIL,
                original = ui.slots,
                working = ui.slots.map { it.pin },
                rows = buildChooserRows(loaded),
                onToggle = {}, onCancel = {}, onSave = {},
            )
        }
    }

    @Test
    fun `chooser - nothing ticked says Save leaves the button unset`() {
        sheet("category-chooser-empty.png", heightFraction = 0.85f) {
            ChooserSheetContent(
                category = HomeCategory.BANK,
                original = emptyList(),
                working = emptyList(),
                rows = buildChooserRows(loaded),
                onToggle = {}, onCancel = {}, onSave = {},
            )
        }
    }

    @Test
    fun `open with - two mail apps, one of them WORK`() {
        val gmail = apps.first { it.label == "Gmail" }
        val outlookWork = apps.first { it.label == "Outlook" && it.isWork }
        val ui = CategoryUi(HomeCategory.MAIL, buildDockSlots(listOf(pin(gmail), pin(outlookWork)), loaded))
        sheet("category-open-with.png", heightFraction = 0.34f) {
            OpenWithSheetContent(ui = ui, onLaunch = {}, onChoose = {})
        }
    }
}
