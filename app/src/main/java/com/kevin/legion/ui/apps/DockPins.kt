package com.kevin.legion.ui.apps

import android.content.Context

/**
 * One pinned dock slot - the app's package plus which profile it belongs to. `packageName` alone
 * cannot tell a personal app from its work-profile twin (the same "Outlook" label lives in both),
 * same reason [DrawerApp.profileKey] exists - so a pin needs both, exactly as [DrawerApp] already
 * carries them (home-launcher ticket 06).
 */
data class DockPin(val packageName: String, val userSerial: Int)

/**
 * Five manually-pinned slots, ticket 06's own ruling: *"actually just let me manually pin apps to
 * home screen like the usual android home does"* - no usage counting, no ranking, nothing derived.
 * Every function here is pure over a `List<DockPin>` so [DockPinsTest] can drive it without
 * [android.content.SharedPreferences] or Robolectric; [DockPinsStore] below is the only thing that
 * touches storage, per the ticket's own "unit-tested as pure logic over an in-memory store."
 */
object DockPins {
    const val MAX_SLOTS = 5

    /** [PinOutcome.Ok] when there was room (or [app] was already pinned - a no-op, never a second
     * copy of the same slot); [PinOutcome.Full] when the dock was already at [MAX_SLOTS] - the
     * caller states "Dock is full. Unpin one first." (ticket's own wording) rather than silently
     * replacing a slot. */
    sealed interface PinOutcome {
        data class Ok(val pins: List<DockPin>) : PinOutcome
        data class Full(val pins: List<DockPin>) : PinOutcome
    }

    fun pin(current: List<DockPin>, app: DockPin): PinOutcome {
        if (app in current) return PinOutcome.Ok(current)
        if (current.size >= MAX_SLOTS) return PinOutcome.Full(current)
        return PinOutcome.Ok(current + app)
    }

    fun unpin(current: List<DockPin>, app: DockPin): List<DockPin> = current.filterNot { it == app }

    /** Adjacent swap only ([delta] of -1/+1), matching
     * [com.kevin.legion.ui.checklists.ListsViewModel.moveUp]/`moveDown`'s own convention. Out of
     * range (already leftmost/rightmost) clamps to a no-op rather than wrapping around. Not
     * pinned at all is also a no-op - nothing to move. */
    fun move(current: List<DockPin>, app: DockPin, delta: Int): List<DockPin> {
        val index = current.indexOf(app)
        if (index < 0) return current
        val target = index + delta
        if (target < 0 || target >= current.size) return current
        val mutable = current.toMutableList()
        val moved = mutable.removeAt(index)
        mutable.add(target, moved)
        return mutable
    }
}

private const val DOCK_PREFS_NAME = "home_dock"
private const val DOCK_PINS_KEY = "pins"

/**
 * [DockPins]' own persistence - ticket 06's own "Storage" section: pins are device-local config,
 * not household data (which apps exist is a fact about THIS phone), so this is
 * [android.content.SharedPreferences], never Room, never synced, never in any backup-restore
 * meaning beyond what `SharedPreferences` already gets.
 */
object DockPinsStore {
    fun read(context: Context): List<DockPin> {
        val raw = prefs(context).getString(DOCK_PINS_KEY, null) ?: return emptyList()
        return parseDockPins(raw)
    }

    fun write(context: Context, pins: List<DockPin>) {
        prefs(context).edit().putString(DOCK_PINS_KEY, formatDockPins(pins)).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(DOCK_PREFS_NAME, Context.MODE_PRIVATE)
}

/** "pkg:serial,pkg:serial" - pure so [DockPinsTest] can round-trip it with no
 * [android.content.SharedPreferences] involved. */
internal fun formatDockPins(pins: List<DockPin>): String =
    pins.joinToString(",") { "${it.packageName}:${it.userSerial}" }

internal fun parseDockPins(raw: String): List<DockPin> =
    raw.split(",").mapNotNull { entry ->
        val parts = entry.split(":")
        val serial = parts.getOrNull(1)?.toIntOrNull()
        if (parts.size == 2 && serial != null && parts[0].isNotBlank()) DockPin(parts[0], serial) else null
    }
