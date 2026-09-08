package com.kevin.legion.location

import android.content.Context
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

    /**
     * What one [add] call did. Same shape and same reason as
     * [com.kevin.legion.ai.AriaBrain.RememberOutcome] and
     * [com.kevin.legion.workouts.WorkoutController.WriteOutcome].
     *
     * **[add] used to return a bare `String` and `LiveToolbox`'s `set_reminder` dispatch hardcoded
     * `success = true` over it**, so both of the branches below that write nothing reached the
     * model as `{"success": true, "message": "<a failure>"}` - and §7's outcome-verb clause is
     * conditioned on the tool RESULT, so a lying flag defeats it outright. Corrected 2026-09-07
     * alongside the identical hole in `remember`.
     *
     * `AdvisorProposalExecutor.setReminder`'s own doc comment named this exact problem in writing
     * ("signal failure by RETURNING A SPOKEN FAILURE SENTENCE as a normal `String`... nothing was
     * written, but the string alone is indistinguishable from a success message without
     * string-matching it") and worked around it with a DAO read-back. That read-back stays: it
     * proves the row landed, which a flag from this function only asserts.
     */
    data class AddOutcome(val success: Boolean, val message: String)

    /** Stores a reminder for [placeLabel]; returns a short spoken acknowledgement and whether
     * anything was actually written. */
    suspend fun add(context: Context, placeLabel: String, text: String): AddOutcome {
        val label = normalizeLabel(placeLabel)
        val body = text.trim()
        if (label.isBlank() || body.isBlank()) {
            return AddOutcome(false, "I need both a place and what to remind you about.")
        }
        val list = NotesController.theList(context)
        val item = NotesController.addItem(context, list.id, body)
        // setPlaceTrigger returns null on a failed write ([NotesController.setTime]'s documented
        // null-on-failure contract, shared by every field write in that file). The reminder ROW
        // exists either way, but with no trigger on it the arrival monitor will never see it - so
        // "I'll remind you when you reach X" would be a promise about a mechanism that is not
        // wired. Said in words instead, and the item is left in place rather than deleted: it is
        // a real to-do the user asked for, just not a place-triggered one.
        //
        // A `when` rather than a second early return, to stay under detekt's `ReturnCount`.
        val triggered = NotesController.setPlaceTrigger(context, item, label) != null
        return if (triggered) {
            AddOutcome(true, "Got it. I'll remind you to $body when you reach ${displayLabel(label)}.")
        } else {
            AddOutcome(
                false,
                "I saved \"$body\" as a to-do, but I couldn't attach it to ${displayLabel(label)} - " +
                    "so I won't be able to raise it when you get there.",
            )
        }
    }

    /** Active (not-yet-done) reminders bound to [label], across every list - a reminder isn't
     * required to live on "Reminders" specifically (a driver can mark any item place-triggered).
     * Cutover 1: rewired off `ListItemDao` onto [NotesController], which is now engine-backed. */
    suspend fun activeFor(context: Context, label: String): List<ListItem> =
        NotesController.openWithPlaceTrigger(context, normalizeLabel(label))

    /** All active reminders across every place. */
    suspend fun allActive(context: Context): List<ListItem> =
        NotesController.openWithAnyPlaceTrigger(context)

    /** Marks a reminder acknowledged so it stops surfacing - [NotesController.tick], the same
     * verb any other item's completion goes through. */
    suspend fun markDone(context: Context, id: Long) {
        val item = NotesController.itemById(context, id) ?: return
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
