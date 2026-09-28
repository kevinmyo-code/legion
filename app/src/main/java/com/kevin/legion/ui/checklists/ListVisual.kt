package com.kevin.legion.ui.checklists

import androidx.annotation.DrawableRes
import com.kevin.legion.R
import com.kevin.legion.ui.theme.soft.AreaAccent

/**
 * A list's icon card colour, resolved from its own [AreaAccent] pair (home-launcher ticket 04).
 * Every [MsIcon][com.kevin.legion.ui.theme.soft.MsIcon] on a card is drawn in [icon] against
 * [containerColor]'s tone, matching ticket 02's "coloured icon chip" convention every HOME tile
 * already uses - a list is not a special case of that pattern, it is another instance of it.
 */
data class ListVisual(@DrawableRes val icon: Int, val accent: AreaAccent)

/**
 * One keyword group: the substrings that match (checked lowercase, `contains`, first group in
 * [KEYWORD_TABLE] order wins), the icon it draws, and the fixed [AreaAccent] it is always drawn
 * in. **The colour-per-keyword mapping is this file's own call, not specified by the ticket** -
 * ticket 04 says only "keyword matches get a fixed pair", not which pair. Six of twelve are lifted
 * straight from the prototype canvas's own seed data (`research/prototype-canvas/ListsC.dc.html`:
 * Groceries/MONEY, Todo/CALENDAR, Bio(fitness)/BODY, Morning/LISTS, Packing/RECORDINGS, and its
 * own "New list" default/NEWS) so a card a person actually saw in the picked prototype renders in
 * the same colour here; the remaining six have no prototype precedent and are assigned by hand,
 * reusing accents rather than inventing a second palette.
 */
private data class KeywordGroup(val keywords: List<String>, @DrawableRes val icon: Int, val accent: AreaAccent)

private val KEYWORD_TABLE: List<KeywordGroup> = listOf(
    KeywordGroup(listOf("groceries", "grocery", "shopping"), R.drawable.ms_shopping_cart, AreaAccent.MONEY),
    KeywordGroup(listOf("todo", "to do", "tasks"), R.drawable.ms_task_alt, AreaAccent.CALENDAR),
    KeywordGroup(listOf("bio", "workout", "gym", "fitness"), R.drawable.ms_fitness_center, AreaAccent.BODY),
    KeywordGroup(listOf("morning"), R.drawable.ms_wb_twilight, AreaAccent.LISTS),
    KeywordGroup(listOf("evening", "night", "bed"), R.drawable.ms_bedtime, AreaAccent.FLEET),
    KeywordGroup(listOf("packing", "travel", "trip"), R.drawable.ms_luggage, AreaAccent.RECORDINGS),
    KeywordGroup(listOf("house", "home", "chores", "cleaning"), R.drawable.ms_home, AreaAccent.REPORTS),
    KeywordGroup(listOf("meds", "medicine", "pills", "vitamin"), R.drawable.ms_medication, AreaAccent.BODY),
    KeywordGroup(listOf("reading", "books"), R.drawable.ms_menu_book, AreaAccent.NEWS),
    KeywordGroup(listOf("work"), R.drawable.ms_work, AreaAccent.REPORTS),
    KeywordGroup(listOf("school", "study", "class"), R.drawable.ms_school, AreaAccent.CALENDAR),
    KeywordGroup(listOf("car"), R.drawable.ms_directions_car, AreaAccent.FLEET),
)

/** The eight [AreaAccent] values in their declared order - the fixed cycle [listVisual] indexes
 * into via `id mod 8` for a name that matched no keyword, so the fallback colour is stable for a
 * given id (never re-rolled) without depending on iteration order elsewhere. */
private val FALLBACK_ACCENTS: List<AreaAccent> = AreaAccent.entries

/**
 * A list's icon and colour, from its own [name] via [KEYWORD_TABLE] (first match wins, case
 * insensitive), falling back to the plain `checklist` glyph and a colour picked by `id mod 8` -
 * stable for a given list (same id always yields the same fallback colour) rather than a random
 * or a name-hash pick that could collide oddly. [id] is a [Checklist.id][com.kevin.legion.data.local.Checklist.id];
 * always non-negative (Room `autoGenerate` primary key), so a plain `mod` is safe with no need to
 * guard a negative remainder.
 */
fun listVisual(name: String, id: Long): ListVisual {
    val lower = name.lowercase()
    val match = KEYWORD_TABLE.firstOrNull { group -> group.keywords.any { lower.contains(it) } }
    if (match != null) return ListVisual(match.icon, match.accent)
    val fallbackAccent = FALLBACK_ACCENTS[(id % FALLBACK_ACCENTS.size).toInt()]
    return ListVisual(R.drawable.ms_checklist, fallbackAccent)
}
