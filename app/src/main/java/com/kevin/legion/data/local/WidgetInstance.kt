package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One placed widget on the pager (aspect-engine map, "the whole app is a widget pager", charter
 * decision 9/10). Layouts are **per-device and deliberately unsynced** (map decisions list, ticket
 * 08's widget contract) - two phones sharing the household's data may want a different arrangement
 * of the same aspects, and unlike [EngineRecord] this table has no `syncId`/sync story at all on
 * purpose, so [deviceId] is the only thing that scopes a row rather than a cross-device identity.
 *
 * [aspectId]/[recordTypeId] are both nullable: a home-page widget (mission control's own summary
 * tiles) is not scoped to one aspect, and an aspect-level widget (a whole-aspect summary) is not
 * scoped to one record type - only a record-type-specific widget (a list, a total) sets both.
 *
 * [widgetType]/[config] are deliberately loose (`TEXT`) rather than an enum locked at this ticket -
 * ticket 16's scope is the engine core (schema + [com.kevin.legion.engine.RecordStore]), not the
 * widget contract itself (ticket 08, already resolved but not this ticket's build target); the
 * eight widget types that answer names it are read/written as plain strings so the widget-contract
 * build ticket can add its own vocabulary in Kotlin with zero schema change, the same "widening a
 * TEXT-stored enum is not a migration" convention this file's siblings already rely on. See
 * [com.kevin.legion.engine.WidgetKind] for the Kotlin enum ticket 18 actually reads/writes here.
 *
 * **v35 ([MIGRATION_34_35], ticket 18): [gridRow]/[gridCol]/[rowSpan]/[colSpan] added.** These are
 * the [com.kevin.legion.ui.grid.GridItem] geometry fields, promoted to real columns rather than
 * folded into [config] - `ui/grid/GridModel.kt`'s whole persistence-boundary contract (see that
 * file's own doc) is "a plain `List<GridItem>` in, a plain `List<GridItem>` out", and a caller
 * reading this table back into that shape needs those four numbers queryable, not buried inside a
 * widget-kind-specific opaque blob only that kind's own parser understands. [position] (the old
 * "placement order" field) is UNCHANGED and still orders widgets that tie on `(gridRow, gridCol)` -
 * in practice this never happens once a row round-trips through [com.kevin.legion.ui.grid.GridEngine],
 * which never produces two items at the identical cell, but the column stays rather than being
 * repurposed, since removing it would be exactly the kind of silent field-repurposing this schema's
 * conventions warn against elsewhere (see [FieldDef.config]'s own "entity stays opaque" doc).
 */
@Entity(tableName = "widget_instances", indices = [Index(value = ["deviceId"])])
data class WidgetInstance(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Scopes a layout to the phone that placed it - see this entity's doc comment. */
    val deviceId: String,
    /** Null = the home page; non-null = the one aspect page this widget lives on. There is
     * deliberately no separate "page" column - a page IS an aspect (or the absence of one), per
     * ticket 18's "home page one, one page per aspect" brief. */
    val aspectId: Long? = null,
    val recordTypeId: Long? = null,
    val widgetType: String,
    /** JSON, shape owned by [widgetType] - opaque to this entity and to Room. */
    val config: String = "{}",
    /** Placement order on the page this widget lives on - see this entity's v35 doc for why this
     * survives alongside the new geometry columns rather than being replaced by them. */
    val position: Int = 0,
    /** [com.kevin.legion.ui.grid.GridItem.row] - see this entity's v35 doc comment. `defaultValue`
     * set (senior review, 2026-08-23) so [MIGRATION_34_35]'s `ADD COLUMN ... DEFAULT 0` and Room's
     * own expected `createSql` for this column agree - without it Room infers no `DEFAULT` clause
     * at all for the generated schema, which would silently diverge from what the migration's SQL
     * actually produces on a real upgrade even though both "read" as zero for a fresh row. */
    @ColumnInfo(defaultValue = "0") val gridRow: Int = 0,
    /** [com.kevin.legion.ui.grid.GridItem.col]. See [gridRow]'s doc for why `defaultValue` is set. */
    @ColumnInfo(defaultValue = "0") val gridCol: Int = 0,
    /** [com.kevin.legion.ui.grid.GridItem.rowSpan], floored at 1 by [com.kevin.legion.ui.grid.GridEngine]
     * the same way the in-memory type is. See [gridRow]'s doc for why `defaultValue` is set. */
    @ColumnInfo(defaultValue = "1") val rowSpan: Int = 1,
    /** [com.kevin.legion.ui.grid.GridItem.colSpan]. See [gridRow]'s doc for why `defaultValue` is set. */
    @ColumnInfo(defaultValue = "1") val colSpan: Int = 1,
    val createdAt: Long,
    val updatedAt: Long,
)
