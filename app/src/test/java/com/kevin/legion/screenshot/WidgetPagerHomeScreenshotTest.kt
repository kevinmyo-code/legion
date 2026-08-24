package com.kevin.legion.screenshot

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.engine.DefaultArrangementSeeder
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.ui.widgets.WidgetPagerActivity
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
 * never fabricates data). Renders through the REAL production entry point,
 * [WidgetPagerActivity] - same posture as [com.kevin.legion.engine.dates.DatesAspectSeederTest]'s
 * "through the real path, not a mock" - so this catches a real wiring break, not just a change to
 * a hand-built harness.
 *
 * **No manual seeding here** - [WidgetPagerActivity]'s own composition
 * ([com.kevin.legion.ui.widgets.WidgetPagerRoot]) calls `seeder.seedHomeIfEmpty(deviceId)` itself,
 * on every mount, against a freshly-reset, genuinely-empty device; a second, hand-rolled seed call
 * from this test would be redundant at best and racy at worst (see [resetDatabase]'s own doc for
 * why an explicit `@Before` cannot run early enough to seed ahead of this Activity's `onCreate`
 * anyway). **Determinism** holds regardless: [DefaultArrangementSeeder]'s own doc states every
 * seeded widget starts deliberately unconfigured, so nothing this screen renders is time-shaped by
 * the seeder's internal (wall-clock) timestamp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
class WidgetPagerHomeScreenshotTest {

    /** Must run BEFORE [composeTestRule] launches [WidgetPagerActivity] - a JUnit `@Rule`'s own
     * setup runs outside (earlier than) the class's `@Before` methods, and this Activity's own
     * `onCreate` composition touches `CarDatabase.getDatabase` immediately (see
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
    val composeTestRule = createAndroidComposeRule<WidgetPagerActivity>()

    @Test
    fun `pager HOME renders the seeded default arrangement`() {
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
