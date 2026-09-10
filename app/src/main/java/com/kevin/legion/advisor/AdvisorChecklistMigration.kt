package com.kevin.legion.advisor

import android.content.Context
import com.kevin.legion.checklists.ChecklistController
import com.kevin.legion.notes.NotesController

/**
 * One-time carry of `GoalChecklistSync`'s retired `"Plan: "`-prefixed rows onto the recurring
 * `checklists` row [AdvisorProposalExecutor]'s `create_checklist` op now owns - one-home ticket 04
 * decision 3, the consequence the ticket flagged as the one that must not be chosen silently:
 * *"Existing `Plan: ` rows are MIGRATED, not left to age out."*
 *
 * **Undone rows migrate; already-ticked ones are left alone**, per ticket 04's resolution verbatim:
 * *"A done row is a record of a day that already happened, and rewriting history to make a
 * migration tidy is worse than a slightly untidy migration."* A ticked `"Plan: "` line is simply
 * left where it is, forever - it is never read by anything anymore ([GoalChecklistSync] itself is
 * deleted, and `NotesController.allItems` never rendered these lines even before that, per its own
 * "excluded from the inbox stream" doc comment), but it is not this migration's place to delete a
 * record of a day the user actually completed something.
 *
 * **Reuses [AdvisorProposalExecutor.BIO_CHECKLIST_SOURCE_KEY]-keyed lookup** so a checklist created
 * by this migration (nobody has run the new `create_checklist` proposal yet) is the SAME row a
 * later advisor proposal finds and updates in place - never a second, orphaned checklist sitting
 * alongside whatever the advisor writes tomorrow.
 *
 * **Data movement, not ingestion and not a Room migration** - same posture
 * `notes/ReminderChecklistMigration.kt`/`grocery/GroceryChecklistMigration.kt` already establish:
 * no schema changed here, no version bumped by this file, §4's reconciliation gate does not apply
 * (this is the user's own already-accepted plan text changing which table it lives in, not a
 * document being extracted). The schema change this ticket DOES make (`checklists.sourceKey`) is a
 * real Room migration, entirely separate, covered by its own migration test.
 *
 * **Idempotence**: the same SharedPreferences-flag fast path
 * `ReminderChecklistMigration`/`GroceryChecklistMigration` use. No per-row identity check on top -
 * same accepted cost those two files' own doc comments name: a crash between the checklist writes
 * and the `list_items` removals is the one window this cannot fully close, and the worst case is a
 * possible duplicate checklist line, a one-tap `deleteItem` away, never a silently lost one.
 */
object AdvisorChecklistMigration {
    private const val PREFS = "advisor_checklist_migration"
    private const val KEY_COMPLETED = "goal_checklist_sync_to_checklists_completed_v1"

    /** The retired `GoalChecklistSync.ITEM_PREFIX` - kept here, and ONLY here, as a plain string
     * literal purely to find old rows one last time. [GoalChecklistSync] itself is deleted; this
     * migration is the one piece of code in the codebase still allowed to know that prefix ever
     * existed. */
    const val RETIRED_ITEM_PREFIX = "Plan: "

    /** Default name for a checklist this migration creates from scratch (no `create_checklist`
     * proposal has ever run) - renamed for free the first time the advisor's own proposal supplies
     * a name, via [AdvisorProposalExecutor.createChecklist]'s existing rename-on-mismatch path. */
    private const val DEFAULT_CHECKLIST_NAME = "BIO Plan"

    data class Result(val migrated: Int, val alreadyDone: Boolean)

    suspend fun migrateIfNeeded(context: Context): Result {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_COMPLETED, false)) return Result(migrated = 0, alreadyDone = true)

        // Undone only - ticket 04's binding rule. A ticked "Plan: " row is left exactly where it
        // is, forever; see this file's own class doc for why rewriting it would be worse than
        // leaving it be.
        val candidates = NotesController.allItemsIncludingChecklistLines(context)
            .filter { !it.done && it.text.startsWith(RETIRED_ITEM_PREFIX) }

        if (candidates.isNotEmpty()) {
            val existing = ChecklistController.getChecklistBySourceKey(
                context,
                AdvisorProposalExecutor.BIO_CHECKLIST_SOURCE_KEY,
            )
            val checklist = existing ?: ChecklistController.createChecklist(
                context,
                name = DEFAULT_CHECKLIST_NAME,
                scheduleKind = "DAILY",
                scheduleEvery = 1,
                sourceKey = AdvisorProposalExecutor.BIO_CHECKLIST_SOURCE_KEY,
            )

            val startSortOrder = ChecklistController.itemsFor(context, checklist.id).size
            candidates.forEachIndexed { index, item ->
                // Labelled the same way a fresh create_checklist proposal labels its own items
                // (see AdvisorProposalExecutor.createChecklist's own doc comment) - these lines
                // were GoalChecklistSync's own derived set/rep text, the same model-composed
                // content class, so they get the same "(estimate)" word rather than reading as a
                // measurement Kevin recorded once they land on the new table.
                ChecklistController.addItem(
                    context,
                    checklistId = checklist.id,
                    text = DigestText.estimate(item.text.removePrefix(RETIRED_ITEM_PREFIX)),
                    sortOrder = startSortOrder + index,
                )
                // Soft-deleted through NotesController's own write funnel - never a raw DAO
                // delete, same reasoning ReminderChecklistMigration's own class doc gives.
                NotesController.removeItem(context, item)
            }
        }

        prefs.edit().putBoolean(KEY_COMPLETED, true).apply()
        return Result(migrated = candidates.size, alreadyDone = false)
    }
}
