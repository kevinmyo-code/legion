package com.kevin.legion.engine

import androidx.room.withTransaction
import com.kevin.legion.data.local.AspectDao
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.ui.grid.GridItem
import com.kevin.legion.ui.grid.GridPreset

/**
 * Seeds a first-run home arrangement so a fresh device's HOME page is never blank (aspect-engine
 * ticket 18 build item 4, the item ticket 08's own answer deferred here: "enumerate which existing
 * mission-control screens map to which widget arrangement, ship those as the defaults").
 *
 * **The mapping, stated once, in full - the owed item from ticket 08:**
 *
 * | Mission-control screen (`LegionRoute`) | Widget-pager equivalent |
 * |---|---|
 * | `TODAY` (home: BIO/CRED/FLEET/LOG half-tiles, ALERTS pane, agenda) | HOME page - this seeder's own arrangement below |
 * | `FLEET` (vehicles, drives, maintenance) | the "fleet" aspect page, once a `fleet` row exists in `aspects` |
 * | `MONEY` (ledger transactions, budget) | the "ledger" aspect page, once a `ledger` row exists in `aspects` |
 * | `MONEY_PANTRY` (grocery receipts, macros) | the "pantry" aspect page, once a `pantry` row exists in `aspects` |
 * | `BODY` (workouts, meals, sleep) | a future "body" aspect page - not yet migrated onto the engine |
 * | `NOTES` (lists, calendar, reminders) | a future "notes" aspect page - not yet migrated onto the engine |
 *
 * **The honest state this seeder actually runs against, stated rather than assumed away**: CLAUDE.md
 * §10 and the engine-core ticket (16) are both explicit that "nothing existing migrates onto this
 * schema yet" - fleet/ledger/pantry/body/notes stay on their own typed Room entities until the
 * migration-wave tickets (ticket 21's order) cut each one over. That means [AspectDao.listActive]
 * returns EMPTY on every device today, so [seedHomeIfEmpty] can only ever produce the HOME page (the
 * pager's own "+" page and, later, one page per row this seeder or a migration wave inserts into
 * `aspects`, come from [com.kevin.legion.ui.widgets.WidgetPagerScreen] reading that table live, not
 * from anything hardcoded here). The five widgets below are chosen specifically because every one of
 * them degrades honestly to an empty state with zero aspects/record types on the device - see each
 * widget composable's own "no data yet" copy in `ui/widgets/EngineWidgets.kt` - rather than seeding
 * a widget that would need a record type this ticket cannot assume exists.
 */
class DefaultArrangementSeeder(
    private val db: CarDatabase,
    private val widgetStore: WidgetInstanceStore,
    private val aspectDao: AspectDao,
) {

    /**
     * Seeds the HOME page's default arrangement for [deviceId] - and ONLY the home page - if and
     * only if [WidgetInstanceStore.isDeviceEmpty] is true for that device. **Idempotent**: a second
     * call against a device that already has widgets (whether seeded by an earlier call or
     * hand-arranged since) is a no-op, because the emptiness check is against the WHOLE device, not
     * this page alone - a device that already has widgets on some OTHER page (a future aspect page)
     * must not have HOME re-seeded on top of a layout the user has since edited.
     *
     * **Atomic (senior review, 2026-08-23).** The empty-check plus five inserts used to be plain
     * sequential `suspend` calls with no lock around them - two callers racing `seedHomeIfEmpty` for
     * the SAME device (a cold start firing this from two composables, or a retry after a slow first
     * read) could both observe `isDeviceEmpty() == true` before either had inserted anything, and
     * both would then seed, leaving ten rows instead of five. The whole body now runs inside one
     * [androidx.room.withTransaction] block on [db] - the same primitive [com.kevin.legion.ledger.IngestPipeline]
     * and [com.kevin.legion.ledger.LedgerController] already use for their own check-then-act writes
     * - so a second racing call's own emptiness check does not even begin until the first call's
     * transaction (check AND all five inserts) has fully committed, at which point it correctly
     * observes a non-empty device and returns without writing anything.
     */
    suspend fun seedHomeIfEmpty(deviceId: String, now: Long = System.currentTimeMillis()) {
        db.withTransaction {
            if (!widgetStore.isDeviceEmpty(deviceId)) return@withTransaction

            // AGENDA first, full-width and tall - the single most information-dense default, and
            // the one TODAY's own "coming up" pane already leads with. Empty today (no `dueAt`
            // records exist anywhere on a fresh engine) - the widget's own empty-state copy says so
            // in words.
            widgetStore.addWidget(
                deviceId = deviceId,
                aspectId = null,
                recordTypeId = null,
                kind = WidgetKind.AGENDA,
                config = "{}",
                position = 0,
                item = GridItem(id = "seed-agenda", row = 0, col = 0, rowSpan = GridPreset.LARGE.rowSpan, colSpan = GridPreset.LARGE.colSpan),
                now = now,
            )
            // NEXT-DUE, full-width row directly under the agenda - a maintenance item, a bill,
            // whatever the engine's `dueAt` promoted column surfaces first once a real aspect
            // starts writing it.
            widgetStore.addWidget(
                deviceId = deviceId,
                aspectId = null,
                recordTypeId = null,
                kind = WidgetKind.NEXT_DUE,
                config = "{}",
                position = 1,
                item = GridItem(id = "seed-next-due", row = GridPreset.LARGE.rowSpan, col = 0, rowSpan = GridPreset.WIDE.rowSpan, colSpan = GridPreset.WIDE.colSpan),
                now = now,
            )
            // Two half-width STAT_TILEs, side by side under NEXT-DUE - TODAY's own BIO/CRED/FLEET/LOG
            // half-tile row is exactly this shape (EqualHeightRow's two-up grammar), so the seed
            // mirrors it rather than inventing a new density. A tile with no `fieldId`/`aggregateOp`
            // configured (`config = "{}"`) is a DELIBERATE seed state: it reads "not configured yet"
            // in words (`ui/widgets/EngineWidgets.kt`'s `StatTileWidget`) rather than a fabricated
            // number.
            val statRow = GridPreset.LARGE.rowSpan + GridPreset.WIDE.rowSpan
            widgetStore.addWidget(
                deviceId = deviceId, aspectId = null, recordTypeId = null, kind = WidgetKind.STAT_TILE,
                config = "{}", position = 2,
                item = GridItem(id = "seed-stat-1", row = statRow, col = 0, rowSpan = GridPreset.SMALL.rowSpan, colSpan = GridPreset.SMALL.colSpan),
                now = now,
            )
            widgetStore.addWidget(
                deviceId = deviceId, aspectId = null, recordTypeId = null, kind = WidgetKind.STAT_TILE,
                config = "{}", position = 3,
                item = GridItem(id = "seed-stat-2", row = statRow, col = GridPreset.SMALL.colSpan, rowSpan = GridPreset.SMALL.rowSpan, colSpan = GridPreset.SMALL.colSpan),
                now = now,
            )
            // One QUICK-ADD, unconfigured - same "not configured yet, in words" posture as the stat
            // tiles. Once a real record type exists this becomes a real "log a drive"/"add receipt"
            // button; today it honestly says there is nothing to add yet.
            widgetStore.addWidget(
                deviceId = deviceId, aspectId = null, recordTypeId = null, kind = WidgetKind.QUICK_ADD,
                config = "{}", position = 4,
                item = GridItem(id = "seed-quick-add", row = statRow + GridPreset.SMALL.rowSpan, col = 0, rowSpan = GridPreset.SMALL.rowSpan, colSpan = GridPreset.SMALL.colSpan),
                now = now,
            )
        }
    }
}
