package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.engine.DefaultArrangementSeeder
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.widgets.WidgetPagerRoot
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Baseline 1 of hardening ticket 01: the widget pager's HOME page with the seeded default
 * arrangement (agenda / next-due / two stat tiles / quick-add, every one of them in its own
 * honest "not configured yet" / "nothing scheduled" / "nothing due" state - [DefaultArrangementSeeder]
 * never fabricates data).
 *
 * **Cutover 5 (`docs/architecture/cutover5-2026-08-24.md`): hosted over a bare [ComponentActivity]
 * now, not `WidgetPagerActivity`** - that Activity is deleted, [WidgetPagerRoot] is an ordinary
 * `composable(LegionRoute.DASHBOARD)` destination inside `MainActivity`'s own `NavHost` now, same
 * shape [com.kevin.legion.screenshot.DeckGridEditModeScreenshotTest]/
 * [com.kevin.legion.screenshot.GeneratedScreensScreenshotTest]/
 * [com.kevin.legion.screenshot.EngineWidgetStatesScreenshotTest] already use for a composable that
 * does not need a real, permission-requesting, foreground-service-touching host Activity (see
 * `MainActivity.onResume`'s own doc comment for everything a REAL launch through it would touch -
 * none of it is this screen's concern, and none of it is Robolectric-safe to exercise
 * incidentally). The render itself is byte-identical either way: this composable is the exact same
 * production code MainActivity's `composable(LegionRoute.DASHBOARD)` mounts, so the committed PNG
 * baseline is unchanged by this cutover.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
class WidgetPagerHomeScreenshotTest {

    /** Must run BEFORE [composeTestRule] launches its host Activity - a JUnit `@Rule`'s own setup
     * runs outside (earlier than) the class's `@Before` methods, and [WidgetPagerRoot]'s own
     * composition touches `CarDatabase.getDatabase` immediately (see
     * [MirrorSyncNoFolderScreenshotTest]'s identical rule for the same reasoning). `order = 0`
     * (lower runs outer/first) guarantees the reset happens before `order = 1`'s activity launch. */
    @get:Rule(order = 0)
    val resetDatabase: TestRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                RoomTestReset.resetCarDatabaseSingleton()
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `pager HOME renders the seeded default arrangement`() {
        composeTestRule.setContent {
            LegionTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    WidgetPagerRoot()
                }
            }
        }
        // The five seeded widgets each resolve async (LaunchedEffect + a Room read); waitUntil
        // polls rather than a single waitForIdle() because that Room read runs on Room's own
        // background executor thread, outside Compose's recomposition clock - see the
        // vendored android-testing skill's screenshot-determinism note. "LOADING" is this screen's
        // own literal loading copy (WidgetPagerScreen.kt).
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("LOADING").fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.onRoot().captureRoboImage("pager-home-seeded-arrangement.png")
    }
}
