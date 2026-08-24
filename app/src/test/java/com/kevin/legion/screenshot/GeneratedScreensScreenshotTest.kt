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
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.dates.DatesAspectSeeder
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.ui.generated.GeneratedDetailScreen
import com.kevin.legion.ui.generated.GeneratedFormScreen
import com.kevin.legion.ui.generated.GeneratedListScreen
import com.kevin.legion.ui.theme.LegionTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Baseline 3 of hardening ticket 01: the generated list/detail/form trio (`ui/generated/`) over
 * one seeded record type - [DatesAspectSeeder]'s "Event" (7 fields: TEXT/DATETIME/DATETIME/TEXT/
 * TEXT/CHOICE/TEXT), chosen because it is the engine's own smallest real record type (no
 * REFERENCE/COMPUTED/PHOTO fields to fake a second record type for) and is already exercised by
 * [com.kevin.legion.engine.dates.DatesAspectSeederTest] through the same real `ensureSeeded` path.
 *
 * **Determinism**: [DatesAspectSeeder.ensureSeeded] itself stamps `createdAt`/`updatedAt` with
 * `System.currentTimeMillis()` internally (it has no `now` parameter - unlike [RecordStore.create],
 * which does), but neither timestamp is rendered anywhere in list/detail/form - both screens read
 * `FieldDef.name`/`FieldDef.type`/`EngineRecord.payload`, none of which the seeder's own wall-clock
 * read touches. The RECORD itself, whose `title`/`start`/`source` fields DO render, is created
 * through [RecordStore.create]'s own `now` seam with a fixed literal, per the ticket's "no
 * `Date.now` in fixtures" rule.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
class GeneratedScreensScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private var recordTypeId: Long = 0
    private var recordId: Long = 0

    @Before
    fun seedOneDatesEvent() {
        RoomTestReset.resetCarDatabaseSingleton()
        val context = RuntimeEnvironment.getApplication()
        val db = CarDatabase.getDatabase(context)
        val store = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        runBlocking {
            val schema = DatesAspectSeeder.ensureSeeded(context)
            recordTypeId = schema.recordTypeId
            val result = store.create(
                recordTypeId = schema.recordTypeId,
                fieldValues = mapOf(
                    schema.fieldIds.getValue(DatesAspectSeeder.FIELD_TITLE) to "Quarterly review",
                    schema.fieldIds.getValue(DatesAspectSeeder.FIELD_START) to FIXED_NOW,
                    schema.fieldIds.getValue(DatesAspectSeeder.FIELD_LOCATION) to "Conference room B",
                    schema.fieldIds.getValue(DatesAspectSeeder.FIELD_SOURCE) to DatesAspectSeeder.SOURCE_LEGION,
                ),
                provenance = RecordProvenance.USER,
                now = FIXED_NOW,
            )
            recordId = (result as RecordStore.WriteResult.Success).recordId
        }
    }

    @Test
    fun `generated list shows the seeded event`() {
        composeTestRule.setContent {
            LegionTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    GeneratedListScreen(recordTypeId = recordTypeId, onBack = {}, onOpenRecord = {}, onAddRecord = {})
                }
            }
        }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("LOADING").fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.onRoot().captureRoboImage("generated-list.png")
    }

    @Test
    fun `generated detail shows every field and its provenance`() {
        composeTestRule.setContent {
            LegionTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    GeneratedDetailScreen(recordId = recordId, onBack = {}, onOpenChildRecord = {}, onEdit = {})
                }
            }
        }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("LOADING").fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.onRoot().captureRoboImage("generated-detail.png")
    }

    @Test
    fun `generated form (add mode) renders one editor per field type`() {
        composeTestRule.setContent {
            LegionTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    GeneratedFormScreen(recordTypeId = recordTypeId, recordId = null, onBack = {}, onSaved = {})
                }
            }
        }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("LOADING").fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.onRoot().captureRoboImage("generated-form-add.png")
    }

    private companion object {
        const val FIXED_NOW = 1_735_000_000_000L
    }
}
