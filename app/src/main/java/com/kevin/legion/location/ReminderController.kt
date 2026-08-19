package com.kevin.legion.location

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.ItemList
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.notes.NotesController

/**
 * Location-triggered reminders: the driver binds a reminder to a saved place
 * ("remind me to grab my gym bag when I get to the gym"), and it's surfaced on
 * arrival by the arrival monitor in [com.kevin.legion.service.AriaForegroundService].
 *
 * Place labels are folded onto the same canonical forms [PlaceController] uses
 * (home/work synonyms), so a reminder for "the office" matches arrival at the
 * place saved as "work".
 *
 * **Rewired onto the notes/lists/calendar model** (`.scratch/notes-lists-calendar/issues/01-*`,
 * finishing the absorption phase 1 left split-brained): a reminder is now a
 * [com.kevin.legion.data.local.ListItem] with [com.kevin.legion.data.local.ListItem.triggerPlaceLabel]
 * set, living in the "Reminders" list the v9->v10 migration created, NOT a row in the legacy
 * `place_reminders` table - phase 1's `MIGRATION_9_10` copied existing rows OUT of that table into
 * the new model, but until now this controller kept writing new ones back INTO the old table,
 * where the notes model would never see them again. The `set_reminder` tool's name, parameters,
 * and spoken behaviour are all unchanged - only where the write lands.
 */
object ReminderController {

    /** Stores a reminder for [placeLabel]; returns a short spoken acknowledgement. */
    suspend fun add(context: Context, placeLabel: String, text: String): String {
        val label = normalizeLabel(placeLabel)
        val body = text.trim()
        if (label.isBlank() || body.isBlank()) {
            return "I need both a place and what to remind you about."
        }
        val list = NotesController.theList(context)
        val item = NotesController.addItem(context, list.id, body)
        NotesController.setPlaceTrigger(context, item, label)
        return "Got it. I'll remind you to $body when you reach ${displayLabel(label)}."
    }

    /** Active (not-yet-done) reminders bound to [label], across every list - a reminder isn't
     * required to live on "Reminders" specifically (a driver can mark any item place-triggered). */
    suspend fun activeFor(context: Context, label: String): List<ListItem> =
        CarDatabase.getDatabase(context).listItemDao().openWithPlaceTrigger(normalizeLabel(label))

    /** All active reminders across every place. */
    suspend fun allActive(context: Context): List<ListItem> =
        CarDatabase.getDatabase(context).listItemDao().openWithAnyPlaceTrigger()

    /** Marks a reminder acknowledged so it stops surfacing - [NotesController.tick], the same
     * verb any other item's completion goes through. */
    suspend fun markDone(context: Context, id: Long) {
        val item = CarDatabase.getDatabase(context).listItemDao().getById(id) ?: return
        NotesController.tick(context, item)
    }

    private fun displayLabel(label: String): String =
        if (label == "home" || label == "work") label else "\"$label\""

    /** Folds spoken place words onto canonical labels, mirroring PlaceController. */
    private fun normalizeLabel(raw: String): String {
        var s = raw.lowercase()
            .replace(Regex("\\b(location|place|spot|address)\\b"), " ")
            .replace(Regex("[.!?,]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        s = s.removePrefix("the ").removePrefix("my ").removePrefix("a ").trim()
        return when (s) {
            "work", "office", "job", "where i work" -> "work"
            "home", "house", "where i live", "live" -> "home"
            else -> s
        }
    }

}
