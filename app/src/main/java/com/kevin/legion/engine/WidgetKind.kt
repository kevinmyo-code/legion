package com.kevin.legion.engine

import com.kevin.legion.ui.grid.GridPreset

/**
 * The eight v1 widget types (aspect-engine ticket 08, resolved 2026-08-23: "Widget types v1, all
 * eight: stat tile, record list, next-due, quick-add, single-record card, agenda, chart, photo.").
 * The production twin of `prototype/PrototypeData.kt`'s `PrototypeWidgetKind` - same enum shape,
 * same [supportedPresets] posture, but read from a real [com.kevin.legion.data.local.WidgetInstance.widgetType]
 * string rather than an in-memory fixture. Stored as plain TEXT on that column (ticket 16's own
 * entity doc: "widening a TEXT-stored enum is not a migration"), never a Room-mapped enum column -
 * [name]/[valueOf] round-trip it.
 */
enum class WidgetKind {
    STAT_TILE,
    RECORD_LIST,
    NEXT_DUE,
    QUICK_ADD,
    SINGLE_RECORD_CARD,
    AGENDA,
    CHART,
    PHOTO,
}

/**
 * The fixed [GridPreset] subset each [WidgetKind] supports - the production widget-pager's answer
 * to the same "what sizes does this widget come in, like an Android home-screen widget" contract
 * `PrototypeWidgetKind.supportedPresets` answered for the throwaway harness. Order is CYCLE order
 * for [com.kevin.legion.ui.grid.GridEngine.nextPreset], not a ranking.
 *
 * - [WidgetKind.STAT_TILE] / [WidgetKind.QUICK_ADD]: a single short row, never more - [GridPreset.SMALL] only.
 * - [WidgetKind.NEXT_DUE] / [WidgetKind.SINGLE_RECORD_CARD]: fit half-width or can stretch full-width
 *   for more breathing room - [GridPreset.SMALL] and [GridPreset.WIDE].
 * - [WidgetKind.RECORD_LIST] / [WidgetKind.AGENDA]: always full-width (rows too wide for a half
 *   4-column grid), toggling between a compact 2-row view and a taller 3-4-row view -
 *   [GridPreset.WIDE] and [GridPreset.LARGE].
 * - [WidgetKind.CHART]: needs vertical room to plot - [GridPreset.WIDE] (a compact trend line) and
 *   [GridPreset.LARGE] (the full drilldown-height chart).
 * - [WidgetKind.PHOTO]: a single image reads at any of the four shapes - the full catalog, so a
 *   photo widget can sit as a half-width thumbnail or a full-width hero.
 */
val WidgetKind.supportedPresets: List<GridPreset>
    get() = when (this) {
        WidgetKind.STAT_TILE, WidgetKind.QUICK_ADD -> listOf(GridPreset.SMALL)
        WidgetKind.NEXT_DUE, WidgetKind.SINGLE_RECORD_CARD -> listOf(GridPreset.SMALL, GridPreset.WIDE)
        WidgetKind.RECORD_LIST, WidgetKind.AGENDA -> listOf(GridPreset.WIDE, GridPreset.LARGE)
        WidgetKind.CHART -> listOf(GridPreset.WIDE, GridPreset.LARGE)
        WidgetKind.PHOTO -> GridPreset.entries
    }

/** Parses a [com.kevin.legion.data.local.WidgetInstance.widgetType] string, or `null` for anything
 * unrecognised (a widget kind from a future app version this build predates) - callers render an
 * explicit "unknown widget" error state rather than crashing on [WidgetKind.valueOf]. */
fun parseWidgetKind(raw: String): WidgetKind? = runCatching { WidgetKind.valueOf(raw) }.getOrNull()
