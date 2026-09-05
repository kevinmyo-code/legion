package com.kevin.legion.notes

import android.content.Context
import com.kevin.legion.checklists.ChecklistController

/**
 * Ticket 10 slice C's one-time carry: any DATELESS open reminder sitting in `events`
 * (`NotesController.allItems`'s own stream) when the persistent-list surface (`ui/NotesScreen.kt`,
 * `LogMode.ITEMS`, `show_list_modal`) retires is moved onto a non-recurring checklist named
 * [CHECKLIST_NAME] rather than silently discarded - `.scratch/one-today/issues/10-*.md` names this
 * as the migration slice C owes.
 *
 * **"Dateless" means no [com.kevin.legion.data.local.ListItem.startsAt], no
 * [com.kevin.legion.data.local.ListItem.triggerPlaceLabel] and no
 * [com.kevin.legion.data.local.ListItem.repeatKind] - a real reminder (a due time, an alarm, a
 * place trigger, or a repeat) is excluded and stays exactly where it is.** Those still live on the
 * calendar, editable from `ui/CalendarScreen.kt`'s day view (that screen's own file doc comment has
 * the full account of the slice C precondition this migration follows). [NotesController.allItems]
 * already excludes [com.kevin.legion.advisor.GoalChecklistSync.ITEM_PREFIX] ("Plan: ") lines - see
 * that function's own doc comment - so a goal's own materialized plan line is never swept up here
 * either, with no second filter needed on this side.
 *
 * **Data movement, not ingestion and not a Room migration.** No schema changed and no version
 * bumped - this reads one existing table (through [NotesController.allItems], the exact stream the
 * now-deleted `ui/notes/InboxScreen.kt` rendered) and writes through the existing
 * [ChecklistController] API, the same door a screen or a voice call would use. §4's reconciliation
 * gate does not apply: this is the user's own already-typed data changing which table it lives in,
 * not a document being extracted.
 *
 * **The source reminder is soft-deleted through [NotesController.removeItem] - never a raw DAO
 * delete.** That function already branches correctly for this purpose: on the configured
 * (Supabase) path it calls [com.kevin.legion.backend.EventsBackend.softDelete] and only clears the
 * local row on a genuine ACK, so the tombstone syncs to a second device the same way any other
 * hands-path delete does; on the unconfigured path it hard-deletes the local row (there is nothing
 * to sync). Either way this migration never invents its own write path for the delete half.
 *
 * **Idempotence**, same two-layer shape [com.kevin.legion.engine.migration.EngineDataMigrationWave1]/
 * `grocery/GroceryChecklistMigration.kt` already use: a SharedPreferences completion flag is the
 * fast path once the whole sweep finishes with no exception. There is no per-row identity check on
 * top, for the same reason `GroceryChecklistMigration`'s own doc comment gives: a crash between the
 * checklist writes and the reminder deletes is the one window this cannot fully close, and the
 * accepted cost is a possible duplicate line on `Todo` if the app dies in that exact window and is
 * reopened before the flag is set - nothing is ever silently lost, only possibly doubled, and a
 * doubled checklist line is a one-tap `remove` away.
 */
object ReminderChecklistMigration {
    private const val PREFS = "reminder_checklist_migration"
    private const val KEY_COMPLETED = "reminder_to_checklist_completed_v1"

    /** The checklist's name - fixed, not user-chosen, since this is a migration of an existing
     * concept ("the persistent list") onto the new one, not a fresh list the user is naming. */
    const val CHECKLIST_NAME = "Todo"

    /** [migrated] is the number of dateless open reminders carried over (0 when there were none, or
     * when [alreadyDone] short-circuited). [alreadyDone] is true only when the SharedPreferences
     * flag skipped the sweep entirely without touching `events` at all. */
    data class Result(val migrated: Int, val alreadyDone: Boolean)

    suspend fun migrateIfNeeded(context: Context): Result {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_COMPLETED, false)) return Result(migrated = 0, alreadyDone = true)

        // NotesController.allItems already excludes GoalChecklistSync's own "Plan: " lines (see
        // that function's own doc comment) - so this reads exactly the same stream the retired
        // InboxScreen rendered, never a second query that could disagree with it about what "the
        // list" held.
        val candidates = NotesController.allItems(context).filter { item ->
            !item.done && item.startsAt == null && item.triggerPlaceLabel == null && item.repeatKind == null
        }

        if (candidates.isNotEmpty()) {
            // Reuse an existing "Todo" checklist if one already exists (a user could have created
            // their own before this migration ever ran) rather than making a second, confusingly
            // identical one - includeArchived so a since-archived "Todo" still absorbs these rather
            // than a silent duplicate appearing alongside it. Same posture
            // `grocery/GroceryChecklistMigration.kt` already uses for "Groceries".
            val existing = ChecklistController.allChecklists(context, includeArchived = true)
                .firstOrNull { it.name.trim().equals(CHECKLIST_NAME, ignoreCase = true) }
            val checklist = existing ?: ChecklistController.createChecklist(
                context,
                name = CHECKLIST_NAME,
                scheduleKind = null, // non-recurring, per the ticket's own instruction
            )

            val startSortOrder = ChecklistController.itemsFor(context, checklist.id).size
            candidates.forEachIndexed { index, item ->
                ChecklistController.addItem(
                    context,
                    checklistId = checklist.id,
                    text = item.text,
                    sortOrder = startSortOrder + index,
                )
                // Soft-deleted through NotesController's OWN write funnel - see this object's own
                // class doc for why this is never a raw DAO delete. A dated/place-triggered/
                // repeating reminder never reaches here at all (the filter above excludes them) -
                // they stay on the calendar, untouched by this migration.
                NotesController.removeItem(context, item)
            }
        }

        prefs.edit().putBoolean(KEY_COMPLETED, true).apply()
        return Result(migrated = candidates.size, alreadyDone = false)
    }
}
