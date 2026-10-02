package com.kevin.legion.ui.apps

import android.content.Context

/**
 * HOME's five category buttons (home-launcher ticket 07). A fixed list for now - Kevin, 2026-10-02:
 * the five "work for now"; making it user-editable is a later ticket. [id] is the storage key and
 * must never change once shipped, [title] is the full name the sheets use, [short] the label under
 * the round button.
 */
enum class HomeCategory(val id: String, val title: String, val short: String) {
    BANK("bank", "Banking", "Bank"),
    MUSIC("music", "Music", "Music"),
    MAPS("maps", "Navigation", "Maps"),
    MAIL("mail", "Email", "Mail"),
    CHAT("chat", "Messages", "Chat"),
}

/** What a tap on a category button does, decided from how many apps are picked. */
sealed interface CategoryTap {
    /** Nothing picked: go straight to the chooser, which says so in words. */
    data object Choose : CategoryTap

    /** Exactly one picked: launch it, no sheet. */
    data class Launch(val pin: DockPin) : CategoryTap

    /** Several picked: ask which, "Open with". */
    data class Ask(val pins: List<DockPin>) : CategoryTap
}

/**
 * The picks behind one category button: an ordered list of `(packageName, userSerial)` - the same
 * [DockPin] shape the dock stores, for the same reason (a personal app and its work twin share a
 * package name). Pure over a `List<DockPin>` like [DockPins], so `CategoryPicksTest` needs no
 * `SharedPreferences`. An uninstalled pick is not removed here: it stays in the list and
 * `buildDockSlots` resolves it to a dimmed "Not installed" slot, so nothing disappears unasked.
 */
object CategoryPicks {
    fun tap(picks: List<DockPin>): CategoryTap = when (picks.size) {
        0 -> CategoryTap.Choose
        1 -> CategoryTap.Launch(picks.single())
        else -> CategoryTap.Ask(picks)
    }

    /** Ticks [app] if it is not picked, unticks it if it is. A tick appends, so the order the
     * user picked in is the order "Open with" lists; a re-tick never makes a second copy. */
    fun toggle(current: List<DockPin>, app: DockPin): List<DockPin> =
        if (app in current) current.filterNot { it == app } else current + app
}

private const val CATEGORY_PREFS_NAME = "home_categories"

/**
 * [CategoryPicks]' own persistence, ticket 07's own "stored like `DockPins`": device-local
 * `SharedPreferences`, one key per category, never Room, never synced - which apps a phone has is a
 * fact about that phone. Reuses [formatDockPins]/[parseDockPins] so there is one pin wire format.
 * An absent or unreadable key reads as no picks (the button shows as not set up) rather than a crash.
 */
object CategoryPicksStore {
    fun read(context: Context, category: HomeCategory): List<DockPin> {
        val raw = prefs(context).getString(category.id, null) ?: return emptyList()
        return parseDockPins(raw)
    }

    fun readAll(context: Context): Map<HomeCategory, List<DockPin>> =
        HomeCategory.entries.associateWith { read(context, it) }

    fun write(context: Context, category: HomeCategory, picks: List<DockPin>) {
        prefs(context).edit().putString(category.id, formatDockPins(picks)).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(CATEGORY_PREFS_NAME, Context.MODE_PRIVATE)
}
