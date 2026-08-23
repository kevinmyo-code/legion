package com.kevin.legion.engine

import com.kevin.legion.data.local.WidgetInstance
import com.kevin.legion.data.local.WidgetInstanceDao
import com.kevin.legion.ui.grid.GridItem

/**
 * The write door for [WidgetInstance] layout edits (aspect-engine ticket 18) - the pager's own twin
 * of [RecordStore], but far thinner: a widget instance carries no reference/computed/provenance
 * concerns, only geometry and a config blob, so this is CRUD plus the one non-trivial job -
 * converting between this table's promoted geometry columns and the plain [GridItem] shape
 * `ui/grid/DeckGrid.kt` actually manipulates - rather than a second full write-door class.
 *
 * `id.toString()` is used as [GridItem.id] throughout - [GridItem]'s own doc calls its `id` "the
 * caller's own stable identity (a `widget_instances` row id, eventually)"; this is that "eventually"
 * arriving. A brand-new widget that has not been inserted yet has no row id and therefore no
 * [GridItem] representation - [addWidget] inserts first, then the caller re-reads the page to get a
 * [GridItem] for it, rather than this class inventing a placeholder id that would collide with a
 * different real row.
 */
class WidgetInstanceStore(private val dao: WidgetInstanceDao) {

    /** Every widget on one page - `aspectId = null` for home, a real aspect id otherwise - as plain
     * [WidgetInstance] rows, position-ordered. Callers needing the [GridItem] shape for [com.kevin.legion.ui.grid.DeckGrid]
     * call [toGridItems] on the result; callers needing to resolve a widget's own kind/config for
     * rendering read the [WidgetInstance] fields directly - both views come from one query so they
     * can never disagree mid-frame. */
    suspend fun layoutForPage(deviceId: String, aspectId: Long?): List<WidgetInstance> =
        dao.forDevicePage(deviceId, aspectId)

    /** True when [deviceId] has never had a single widget placed anywhere - the ONE condition
     * [DefaultArrangementSeeder] checks before seeding (its own doc states why this must be a
     * whole-device check, not a per-page one). */
    suspend fun isDeviceEmpty(deviceId: String): Boolean = dao.countForDevice(deviceId) == 0

    /** Adds one widget to a page at [position] (defaulting to "after everything else already
     * there" is the CALLER's job - this function does not scan the page to compute one, since the
     * seeder inserts a whole page's worth in one deliberate order and a hand-add from the pager's
     * own "+" chrome always knows its own intended slot). Returns the new row's id, which becomes
     * that widget's [GridItem.id] once the caller re-reads the page. */
    suspend fun addWidget(
        deviceId: String,
        aspectId: Long?,
        recordTypeId: Long?,
        kind: WidgetKind,
        config: String = "{}",
        position: Int,
        item: GridItem,
        now: Long = System.currentTimeMillis(),
    ): Long {
        val clamped = normalizedSpan(item)
        return dao.insert(
            WidgetInstance(
                deviceId = deviceId,
                aspectId = aspectId,
                recordTypeId = recordTypeId,
                widgetType = kind.name,
                config = config,
                position = position,
                gridRow = clamped.row,
                gridCol = clamped.col,
                rowSpan = clamped.rowSpan,
                colSpan = clamped.colSpan,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /** Removes a widget outright - a widget instance has no trash tombstone (unlike [com.kevin.legion.data.local.EngineRecord]):
     * it is per-device layout state, not the user's actual data, so "delete" here is a hard delete,
     * matching [WidgetInstanceDao.delete]'s own plain-CRUD posture. A no-op if [id] is already gone
     * (a caller racing a remove-chip tap against a concurrent page reload). */
    suspend fun removeWidget(id: Long) {
        dao.getById(id)?.let { dao.delete(it) }
    }

    /**
     * Writes back the geometry [com.kevin.legion.ui.grid.DeckGrid.onLayoutChange] reports after a
     * drag, a preset-cycle tap, or a remove - the exact moment [com.kevin.legion.ui.grid.GridEngine.displaceForPlacement]
     * found a workable arrangement and the caller committed it. [items] is the WHOLE new layout for
     * this page (every id [DeckGrid] currently knows about, per its own `items` parameter contract),
     * so this both updates every row whose geometry changed and leaves alone any row whose id is not
     * present in [items] at all - a caller passing a page's FULL committed list, never a partial one,
     * is what keeps that safe; nothing here infers "not present = delete".
     */
    suspend fun saveLayout(items: List<GridItem>, now: Long = System.currentTimeMillis()) {
        for (item in items) {
            val id = item.id.toLongOrNull() ?: continue
            val existing = dao.getById(id) ?: continue
            val clamped = normalizedSpan(item)
            if (existing.gridRow == clamped.row && existing.gridCol == clamped.col &&
                existing.rowSpan == clamped.rowSpan && existing.colSpan == clamped.colSpan
            ) {
                continue // nothing actually moved - skip the write, same "no-op write" discipline RecordStore's callers already follow
            }
            dao.update(
                existing.copy(
                    gridRow = clamped.row,
                    gridCol = clamped.col,
                    rowSpan = clamped.rowSpan,
                    colSpan = clamped.colSpan,
                    updatedAt = now,
                ),
            )
        }
    }

    /** [row]/[col] floored at 0, [rowSpan]/[colSpan] floored at 1 - the same floor
     * [com.kevin.legion.ui.grid.GridEngine.clampToBounds] enforces, applied here so a widget row can
     * never round-trip through this store holding an illegal geometry even if a caller constructed
     * a [GridItem] by hand rather than through [com.kevin.legion.ui.grid.GridEngine]. */
    private fun normalizedSpan(item: GridItem): GridItem = item.copy(
        row = item.row.coerceAtLeast(0),
        col = item.col.coerceAtLeast(0),
        rowSpan = item.rowSpan.coerceAtLeast(1),
        colSpan = item.colSpan.coerceAtLeast(1),
    )

    companion object {
        /** [WidgetInstance] rows for one page, converted to the plain [GridItem] shape
         * [com.kevin.legion.ui.grid.DeckGrid] reads - a pure function (no DAO access) so a caller
         * that already has the rows (e.g. from [layoutForPage]) never pays a second query for this. */
        fun toGridItems(rows: List<WidgetInstance>): List<GridItem> = rows.map { row ->
            GridItem(
                id = row.id.toString(),
                row = row.gridRow,
                col = row.gridCol,
                rowSpan = row.rowSpan,
                colSpan = row.colSpan,
            )
        }
    }
}
