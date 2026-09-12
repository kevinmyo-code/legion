package com.kevin.legion.outstanding

import android.content.Context
import com.kevin.legion.checklists.ChecklistController
import com.kevin.legion.notes.NotesController

/**
 * Gathers "what needs doing" from the three stores and hands it to [rankOutstanding].
 *
 * All the judgement lives in `Outstanding.kt`, which is pure and tested. This file only fetches, and
 * it exists so that neither the advisor nor a screen has to know that the answer spans three tables.
 *
 * **Every read here already exists and is already synced.** Nothing new is written, no table is
 * added, no aspect is created - which is why `.scratch/chief-of-staff/issues/05-*.md` calls this the
 * cheapest real step on that map.
 */
object OutstandingController {

    /**
     * Everything outstanding, ranked.
     *
     * [includeDone] carries today's already-ticked checklist lines. They are listed LAST rather than
     * dropped, because a finished day should read as finished rather than as empty - the same
     * distinction CLAUDE.md §1 draws between empty and unreadable, applied to a day's work.
     */
    suspend fun all(
        context: Context,
        day: Int = ChecklistController.today(),
        now: Long = System.currentTimeMillis(),
        includeDone: Boolean = true,
    ): List<OutstandingItem> {
        val items = mutableListOf<OutstandingItem>()
        items += deadlines(context, now)
        items += reminders(context, now)
        items += checklistLines(context, day, includeDone)
        return rankOutstanding(items)
    }

    /**
     * `EventKind.TASK` rows - coursework and anything else imported with a real moment.
     *
     * Reuses [NotesController.openAppointments], which is TASK-filtered and undone-filtered and
     * carries its own doc comment on why an `EventKind.EVENT` must never surface here: an event
     * passes whether or not you engage with it, so it is not outstanding work and marking it done
     * means nothing.
     */
    private suspend fun deadlines(context: Context, now: Long): List<OutstandingItem> =
        NotesController.openAppointments(context).map { row ->
            OutstandingItem(
                id = "task:${row.id}",
                title = row.text,
                kind = OutstandingKind.DEADLINE,
                dueAtMs = row.startsAt,
                overdue = row.startsAt != null && row.startsAt < now,
                done = false,
                source = courseOf(row.text),
            )
        }

    /**
     * `list_items` reminders and one-off todos.
     *
     * [NotesController.allItems] is the filtered stream - it already excludes the retired
     * `"Plan: "`-prefixed rows the advisor used to write, which is what every other surface reads,
     * so this cannot resurrect them.
     */
    private suspend fun reminders(context: Context, now: Long): List<OutstandingItem> =
        NotesController.allItems(context)
            .filterNot { it.done }
            .map { row ->
                OutstandingItem(
                    id = "item:${row.id}",
                    title = row.text,
                    kind = OutstandingKind.REMINDER,
                    dueAtMs = row.startsAt,
                    overdue = row.startsAt != null && row.startsAt < now,
                    done = false,
                    source = row.triggerPlaceLabel,
                )
            }

    /**
     * Today's lines from every checklist scheduled to run today.
     *
     * A line is never [OutstandingItem.overdue]: it resets tonight, so it cannot be late, only
     * unticked. Conflating the two would put a bio-plan line it is still 9am to do in the same
     * bucket as a coursework deadline that passed - which is exactly the flattening this whole
     * module exists to avoid.
     */
    private suspend fun checklistLines(
        context: Context,
        day: Int,
        includeDone: Boolean,
    ): List<OutstandingItem> {
        val out = mutableListOf<OutstandingItem>()
        for (checklist in ChecklistController.checklistsForDay(context, day)) {
            val result = ChecklistController.itemsWithTickState(context, checklist.id, day)
            // A checklist that could not be read is SKIPPED, never rendered as an empty one. An
            // unreadable list and a finished list are different facts (CLAUDE.md §1) and a caller
            // counting rows must not be handed the second when the first is true.
            val states = (result as? ChecklistController.ChecklistItemsResult.Loaded)?.items ?: continue
            for (state in states) {
                if (state.ticked && !includeDone) continue
                out += OutstandingItem(
                    id = "line:${state.item.id}",
                    title = state.item.text,
                    kind = OutstandingKind.CHECKLIST_LINE,
                    dueAtMs = null,
                    overdue = false,
                    done = state.ticked,
                    source = checklist.name,
                )
            }
        }
        return out
    }

    /** `COSC 4320 Software Engineering · Module 2: Assignment 2` -> the course.
     * Same middle-dot split the Canvas import writes and the web client already uses; a title
     * without one carries no course rather than a guessed one. */
    private fun courseOf(title: String): String? {
        val index = title.indexOf('·')
        if (index <= 0) return null
        return title.substring(0, index).trim().takeIf { it.isNotBlank() }
    }

    /** Kept so a caller can count without loading the database twice. */
    suspend fun sentence(context: Context): String = outstandingSentence(all(context))
}
