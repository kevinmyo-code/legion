package com.kevin.legion.screenshot

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.ui.mirror.MirrorSyncActivity
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Baseline 5 of hardening ticket 01: [MirrorSyncActivity] before any mirror folder has ever been
 * connected - "No folder connected." plus the two disclosure paragraphs (the dropdowns-are-a-
 * convenience notice, the bounded-staleness explainer) that this screen ALWAYS shows regardless of
 * connection state.
 *
 * Renders through the real production Activity, same posture as [WidgetPagerHomeScreenshotTest] -
 * `MirrorSyncScreen()` itself is `private` to `MirrorSyncActivity.kt` and stays that way; no
 * visibility seam was needed or added.
 *
 * **Why this state is reachable with zero setup.** `MirrorFolderPreferences.treeUri` is a
 * process-lifetime `MutableStateFlow<Uri?>` seeded from a `SharedPreferences` file that
 * [com.kevin.legion.MidnightApplication.onCreate] calls `MirrorFolderPreferences.init` against on
 * every process start (real production wiring, unconditionally, not gated under Robolectric) - a
 * fresh Robolectric process with nothing ever written to that prefs file resolves to `null`, which
 * is exactly the "no folder" state this test wants and never itself calls `connect(...)` to reach.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
class MirrorSyncNoFolderScreenshotTest {

    /** Must run BEFORE [composeTestRule] launches [MirrorSyncActivity] - a JUnit `@Rule`'s own
     * setup runs outside (earlier than) the class's `@Before` methods, so resetting the
     * [CarDatabase][com.kevin.legion.data.local.CarDatabase] singleton from an `@Before` here would
     * be too late: `MirrorSyncScreen`'s own `LaunchedEffect` (reading `CarDatabase.getDatabase`)
     * fires the moment the activity's `onCreate` composes, which a plain `@Before` cannot preempt.
     * `order = 0` (lower runs outer/first) guarantees this fires before `order = 1`'s activity
     * launch - same cross-test-leak concern [RoomTestReset]'s own doc comment describes. */
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
    val composeTestRule = createAndroidComposeRule<MirrorSyncActivity>()

    @Test
    fun `mirror sync screen with no folder connected`() {
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage("mirror-sync-no-folder.png")
    }
}
