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
import com.kevin.legion.data.local.Aspect
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.data.local.RecordType
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.WidgetDataSource
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.widgets.RecordListWidget
import com.kevin.legion.ui.widgets.StatTileWidget
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Baseline 4 of hardening ticket 01: the widget layer's own worded-state vocabulary
 * (`EngineWidgets.kt`'s doc comment: "not configured yet" / "deleted" / "nothing here yet" are
 * three different sentences, never collapsed into a shared blank"). Four states, per the ticket:
 *
 * - **not-configured**: [StatTileWidget] with `recordTypeId = null`.
 * - **error**: [StatTileWidget] configured against a `fieldId` that has since been deleted from
 *   `field_defs` - [WidgetDataSource.statTile]'s own `Error("the configured field was deleted")`
 *   branch, exercised for real rather than asserted by reading the source.
 * - **data**: [StatTileWidget] with one active record - a real `Count(1)`.
 * - **empty-in-words**: no single [com.kevin.legion.engine.WidgetKind] carries all four states
 *   ([StatTileWidget]'s own zero-record case is still a real `Count(0)`, rendered as data, not as
 *   worded emptiness) - [RecordListWidget] is used here instead, configured against a real,
 *   zero-record type, which renders its own explicit "NO RECORDS YET" copy. Two widget kinds
 *   across four tests, not one kind across four, and said so here rather than silently substituted
 *   - the ticket's own "whichever the code allows without production changes" clause is what
 *   permits this rather than inventing a production seam neither widget has today.
 *
 * Every state is driven through [WidgetDataSource] over real, seeded Room rows - no fakes were
 * needed or added, since [WidgetDataSource]'s constructor already takes plain DAOs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
class EngineWidgetStatesScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun freshDataSource(): WidgetDataSource {
        RoomTestReset.resetCarDatabaseSingleton()
        val context = RuntimeEnvironment.getApplication()
        val db = CarDatabase.getDatabase(context)
        return WidgetDataSource(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao(), db.aspectDao())
    }

    @Test
    fun `stat tile - not configured`() {
        val dataSource = freshDataSource()
        capture("stat-tile-not-configured.png") {
            StatTileWidget(dataSource = dataSource, recordTypeId = null, fieldId = null)
        }
    }

    @Test
    fun `stat tile - error, the configured field was deleted`() {
        val dataSource = freshDataSource()
        val context = RuntimeEnvironment.getApplication()
        val db = CarDatabase.getDatabase(context)
        var recordTypeId = 0L
        val deletedFieldId = 999_999L // never inserted - stands in for "this field def is gone"
        runBlocking {
            val now = FIXED_NOW
            val aspectId = db.aspectDao().insert(Aspect(name = "Fixture", position = 0, createdAt = now, updatedAt = now))
            recordTypeId = db.recordTypeDao().insert(RecordType(aspectId = aspectId, name = "Fixture Type", createdAt = now, updatedAt = now))
            // At least one real field, so the record type is not itself empty - the widget config
            // below still points at `deletedFieldId`, which forRecordType() will never return.
            db.fieldDefDao().insert(
                FieldDef(recordTypeId = recordTypeId, name = "note", type = FieldType.TEXT, required = false, position = 0, createdAt = now, updatedAt = now),
            )
        }
        capture("stat-tile-error-deleted-field.png") {
            StatTileWidget(dataSource = dataSource, recordTypeId = recordTypeId, fieldId = deletedFieldId)
        }
    }

    @Test
    fun `stat tile - data, a real count`() {
        val dataSource = freshDataSource()
        val context = RuntimeEnvironment.getApplication()
        val db = CarDatabase.getDatabase(context)
        var recordTypeId = 0L
        runBlocking {
            val now = FIXED_NOW
            val aspectId = db.aspectDao().insert(Aspect(name = "Fixture", position = 0, createdAt = now, updatedAt = now))
            recordTypeId = db.recordTypeDao().insert(RecordType(aspectId = aspectId, name = "Fixture Type", createdAt = now, updatedAt = now))
            db.fieldDefDao().insert(
                FieldDef(recordTypeId = recordTypeId, name = "note", type = FieldType.TEXT, required = false, position = 0, createdAt = now, updatedAt = now),
            )
            val store = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
            store.create(recordTypeId, emptyMap(), RecordProvenance.USER, now = now)
        }
        capture("stat-tile-data-count.png") {
            StatTileWidget(dataSource = dataSource, recordTypeId = recordTypeId, fieldId = null)
        }
    }

    @Test
    fun `record list - empty in words, no records yet`() {
        val dataSource = freshDataSource()
        val context = RuntimeEnvironment.getApplication()
        val db = CarDatabase.getDatabase(context)
        var recordTypeId = 0L
        runBlocking {
            val now = FIXED_NOW
            val aspectId = db.aspectDao().insert(Aspect(name = "Fixture", position = 0, createdAt = now, updatedAt = now))
            recordTypeId = db.recordTypeDao().insert(RecordType(aspectId = aspectId, name = "Fixture Type", createdAt = now, updatedAt = now))
            // Deliberately no records inserted - this is the "configured, genuinely zero rows" case.
        }
        capture("record-list-empty-in-words.png") {
            RecordListWidget(dataSource = dataSource, recordTypeId = recordTypeId, limit = 20, maxRows = 4)
        }
    }

    private fun capture(fileName: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        composeTestRule.setContent {
            LegionTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    content()
                }
            }
        }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("LOADING").fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.onRoot().captureRoboImage(fileName)
    }

    private companion object {
        const val FIXED_NOW = 1_735_000_000_000L
    }
}
