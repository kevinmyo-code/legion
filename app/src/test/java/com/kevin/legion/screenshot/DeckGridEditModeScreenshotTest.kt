package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.grid.DeckGrid
import com.kevin.legion.ui.grid.GridItem
import com.kevin.legion.ui.grid.GridPreset
import com.kevin.legion.ui.theme.LegionTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Baseline 2 of hardening ticket 01: [DeckGrid] in edit mode - the visible dotted cell-boundary
 * grid, jiggling cards, and the per-card chrome (remove chip, size chip) that only draws when
 * `editMode = true` (see [DeckGrid]'s own file doc, "the grid becomes visible in edit mode").
 *
 * **No Room, no database, no `WidgetPagerScreen` wiring** - [DeckGrid]'s own doc states its
 * "persistence boundary": `items` in, `onLayoutChange` out, both plain `List<GridItem>`, zero Room
 * import anywhere in `DeckGrid.kt`/`GridModel.kt`. A handful of hand-typed [GridItem] fixtures over
 * a trivial `itemContent` is the SMALLEST contract that proves this component's own visual
 * behaviour, matching the vendored android-testing skill's "choosing the test shape" table (a
 * plain UI Compose test, no graph, for "text rendered, conditional content... callback wiring").
 *
 * **Determinism - the jiggle animation.** `DeckGrid.gridJiggle` is a [androidx.compose.animation.core.rememberInfiniteTransition]
 * driving `rotationZ` between -1.2f and 1.2f. `composeTestRule.mainClock.autoAdvance = false`
 * (set BEFORE `setContent`, per the vendored android-testing skill's own "Animation tests need
 * `mainClock.autoAdvance = false` set *before* `setContent`" rule) freezes every card at its
 * animation's `initialValue` - a fixed angle, not a random phase - and the test never calls
 * `advanceTimeBy`, so the capture always lands on that same frozen first frame.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
class DeckGridEditModeScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    /** Four fixed cards - one of each [GridPreset] shape - laid out with no overlap so the fixture
     * itself needs no drag/displace logic to already be valid; [DeckGrid]'s own `normalize` call on
     * first composition would otherwise silently repack an overlapping fixture, which would make
     * this baseline assert against a layout the fixture never actually specified. */
    private val fixtureItems = listOf(
        GridItem(id = "stat-1", row = 0, col = 0, rowSpan = GridPreset.SMALL.rowSpan, colSpan = GridPreset.SMALL.colSpan),
        GridItem(id = "stat-2", row = 0, col = GridPreset.SMALL.colSpan, rowSpan = GridPreset.SMALL.rowSpan, colSpan = GridPreset.SMALL.colSpan),
        GridItem(id = "wide-1", row = GridPreset.SMALL.rowSpan, col = 0, rowSpan = GridPreset.WIDE.rowSpan, colSpan = GridPreset.WIDE.colSpan),
        GridItem(id = "large-1", row = GridPreset.SMALL.rowSpan + GridPreset.WIDE.rowSpan, col = 0, rowSpan = GridPreset.LARGE.rowSpan, colSpan = GridPreset.LARGE.colSpan),
    )

    @Test
    fun `edit mode shows grid lines, jiggle chrome, and size chips`() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            LegionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    FixtureGrid()
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage("deckgrid-edit-mode.png")
    }

    @Composable
    private fun FixtureGrid() {
        DeckGrid(
            items = fixtureItems,
            columnCount = 4,
            editMode = true,
            onEnterEditMode = {},
            onLayoutChange = {},
            onRemove = {},
            presetsFor = { item ->
                when (item.id) {
                    "stat-1", "stat-2" -> listOf(GridPreset.SMALL)
                    "wide-1" -> listOf(GridPreset.WIDE, GridPreset.SMALL)
                    else -> listOf(GridPreset.LARGE, GridPreset.WIDE)
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { item ->
            DeckPane(header = item.id.uppercase(), modifier = Modifier.fillMaxWidth()) {
                Text(item.id)
            }
        }
    }
}
