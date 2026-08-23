package com.kevin.legion.data.local

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
 * TEXT-stored enum is not a migration" convention this file's siblings already rely on.
 */
@Entity(tableName = "widget_instances", indices = [Index(value = ["deviceId"])])
data class WidgetInstance(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Scopes a layout to the phone that placed it - see this entity's doc comment. */
    val deviceId: String,
    val aspectId: Long? = null,
    val recordTypeId: Long? = null,
    val widgetType: String,
    /** JSON, shape owned by [widgetType] - opaque to this entity and to Room. */
    val config: String = "{}",
    /** Placement order on the page this widget lives on. */
    val position: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)
