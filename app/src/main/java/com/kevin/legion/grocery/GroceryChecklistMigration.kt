package com.kevin.legion.grocery

import android.content.Context
import com.kevin.legion.checklists.ChecklistController
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.GroceryItem

/**
 * Ticket 10 slice B's one-time carry: any OPEN grocery trip sitting in `grocery_items` when the
 * trip surface (`ui/notes/GroceryScreen.kt`, `LogMode.GROCERY`, `manage_grocery`,
 * `show_groceries_modal`) retires is moved onto a non-recurring checklist named
 * the household's built-in Groceries list ([CHECKLIST_NAME], `system_key = "groceries"`) rather than
 * silently discarded - `.scratch/one-today/issues/10-*.md` names this
 * as the migration slice B owes.
 *
 * **Data movement, not ingestion and not a Room migration.** No schema changed and no version
 * bumped - this reads one existing table and writes through the existing
 * [ChecklistController]/[com.kevin.legion.checklists.ChecklistController.tick] API, the same door
 * a screen or a voice call would use. §4's reconciliation gate does not apply: this is the user's
 * own already-typed data changing which table it lives in, not a document being extracted.
 *
 * **Deliberately does NOT fold into `grocery_staples`.** [GroceryController.completeTrip] is the
 * only place that fold happens, and it exists to feed [GroceryController.suggestions] - a read
 * path this migration is not trying to preserve (the ticket's own "what is knowingly lost" section
 * accepts that a `Groceries` checklist has no history-derived suggestions). Folding here would grow
 * a table nothing reads suggestions from anymore, for a benefit nobody gets.
 *
 * **Idempotence**, same two-layer shape [com.kevin.legion.engine.migration.EngineDataMigrationWave1]
 * already uses: a SharedPreferences completion flag is the fast path once the whole sweep finishes
 * with no exception. There is no per-row identity check on top (unlike Wave 1's engine-record
 * `guid` backstop) because this migration also DELETES its own source rows in the same pass - a
 * crash between the checklist writes and the `grocery_items` clear is the one window this cannot
 * fully close, and the accepted cost is a possible duplicate line on `Groceries` if the app dies in
 * that exact window and is then reopened before the flag is set; nothing is ever silently lost, only
 * possibly doubled, and a doubled checklist line is a one-tap `remove` away.
 */
object GroceryChecklistMigration {
    private const val PREFS = "grocery_checklist_migration"
    private const val KEY_COMPLETED = "grocery_to_checklist_completed_v1"

    /** The built-in list's display name. Kept for callers that print it; the list itself is found
     * by [com.kevin.legion.data.local.Checklist.SYSTEM_KEY_GROCERIES], never by this name. */
    const val CHECKLIST_NAME = "Groceries"

    /** [migrated] is the number of `grocery_items` rows carried over (0 when there was no open
     * trip, or when [alreadyDone] short-circuited). [alreadyDone] is true only when the
     * SharedPreferences flag skipped the sweep entirely without touching `grocery_items` at all. */
    data class Result(val migrated: Int, val alreadyDone: Boolean)

    suspend fun migrateIfNeeded(context: Context): Result {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_COMPLETED, false)) return Result(migrated = 0, alreadyDone = true)

        val db = CarDatabase.getDatabase(context)
        val items = db.groceryItemDao().getAll()

        // Not carried (the engine's list is not here yet): the flag stays unset and nothing is
        // reported as migrated, so the next start tries again.
        val carried = items.isEmpty() || carryOver(context, db, items)
        if (carried) prefs.edit().putBoolean(KEY_COMPLETED, true).apply()
        return Result(migrated = if (carried) items.size else 0, alreadyDone = false)
    }

    /** Moves [items] onto the built-in list and clears the trip. False, having touched nothing,
     * when the engine's list has not synced to this phone yet. */
    private suspend fun carryOver(context: Context, db: CarDatabase, items: List<GroceryItem>): Boolean {
        // The household's BUILT-IN Groceries list (Kevin, 2026-10-05), found by its system
        // key. This used to create a "Groceries" checklist when none existed; that is exactly
        // what produced six hand-made copies on the engine, so it must never create one now.
        // When the engine's list has not synced to this phone yet, the trip stays in
        // `grocery_items` (nothing lost, nothing flagged done) and the next app start tries
        // again - a missing list is "not here yet", never a reason to make a second.
        val checklist = ChecklistController.builtInGroceries(context) ?: return false

        val startSortOrder = ChecklistController.itemsFor(context, checklist.id).size
        items.forEachIndexed { index, groceryItem ->
            val added = ChecklistController.addItem(
                context,
                checklistId = checklist.id,
                text = groceryItem.text,
                sortOrder = startSortOrder + index,
            )
            if (groceryItem.done) {
                // Ticked today, USER_REPORTED (ChecklistController.tick's own default source) -
                // this is a statement that the item is done, not a claim about WHEN it was
                // bought. grocery_items carries [GroceryItem.doneAt] but a checklist's tick is
                // keyed to a local epoch DAY (see ChecklistTick's own class doc for why [day] and
                // [tickedAt] are different facts), so "today" is the honest day to write, not a
                // derived one that could land on the wrong side of a timezone.
                // (On the built-in Groceries list the engine's hook reads a tick as a purchase, so
                // a done line carried over here logs as bought today; stated, not hidden.)
                ChecklistController.tick(context, added.id)
            }
        }

        // The teardown half - matches GroceryController.completeTrip's own clearAll call, minus
        // the staples fold (see this object's own class doc for why that fold does not belong
        // here).
        db.groceryItemDao().clearAll()
        return true
    }
}
