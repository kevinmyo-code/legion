@file:Suppress("MaxLineLength")

package com.kevin.legion.service.tools.day

import com.kevin.legion.backend.EventsAppointmentWriter
import com.kevin.legion.checklists.TickHistoryController
import com.kevin.legion.data.local.Event
import com.kevin.legion.location.ReminderController
import com.kevin.legion.notes.NotesController
import com.kevin.legion.service.tools.fn
import com.kevin.legion.service.tools.obj
import com.kevin.legion.service.tools.schema
import org.json.JSONObject

/**
 * The day tools' Live declarations, split out of `LiveToolbox.allDeclarations()`
 * (architecture review 2026-11-10, item 8). Moved verbatim; the order the setup message
 * carries them in is [com.kevin.legion.service.tools.DECLARATION_ORDER], not this file's.
 * Dispatch is still `LiveToolbox.dispatch`.
 */
// Moved verbatim from LiveToolbox.allDeclarations(), where the detekt baseline already carried
// these long description lines and the one long function: same text, so the same exemption.
@Suppress("LongMethod", "MaxLineLength")
internal object Declarations {
    fun declarations(): List<JSONObject> = buildList {
        // `remember` and `recall_memory` moved to MemoryToolbox's one `memory` tool (memory-and-vault
        // ticket 09, ADR 0075): memory lives on the engine now, and one action-dispatched declaration
        // is smaller than the two were.

        // ----------------------------------------------------------------------------------
        // Notes / lists / calendar (`.scratch/notes-lists-calendar/`, phase 1). Absorbs the car
        // to-do list (now the "Car" list) and place reminders (now the "Reminders" list) - ticket
        // 10 retires add_car_task/complete_car_task/remove_car_task/list_car_tasks entirely, and
        // this domain's whole surface is THREE tools rather than one per verb (create/tick/
        // untick/remove/schedule/repeat/skip/archive/unarchive/copy/delete-list are all `action`
        // parameters, not separate registrations) - ticket 05/10/11's shared instruction: "every
        // tool is prompt tokens on every single live session, on Kevin's own key." Net -1 against
        // the four retired tools.
        //
        // Alarms/notifications (ticket 03/12, phase 2a) now schedule for real on startsAt - see
        // NotesController.setTime/setRepeat/setExact and notes/AlarmScheduler.kt. A place-triggered
        // item is still created only via the pre-existing set_reminder tool (ReminderController,
        // now writing triggerPlaceLabel onto the notes model instead of the legacy place_reminders
        // table - see that controller's doc comment) - ticket 05's own resolved tool list names no
        // place-trigger verb for the new model, so none is added here.
        add(fn(
            name = "manage_item",
            // Trimmed 2026-08-17 (~1,004-token declaration was the single largest of the 79):
            // condensed the top-level description and cut the repeated "Only for 'schedule' with"
            // prefix off every repeat_* param (the field names and this description already carry
            // that context) without dropping any distinct rule, enum value, or capability - notes/
            // lists/calendar stays genuinely multi-shaped, this only removes restated words.
            // REWRITTEN one-today ticket 10 slice C, 2026-09-05: this used to describe "the user's
            // ONE PERSISTENT list" - that screen (`ui/NotesScreen.kt`) is gone, and a dateless
            // to-do (no date, no place, no repeat) migrated onto a checklist named "Todo"
            // (`notes/ReminderChecklistMigration.kt`). This tool is now scoped to a REMINDER - one
            // with a real trigger (a due date/time, a place, or a repeat) - and a plain todo with
            // none of those redirects to manage_checklist instead.
            description = "Add, tick, untick, remove, schedule (time and/or repeat), or skip one " +
                "occurrence of a REMINDER - a to-do tied to a real trigger: a due date/time, a " +
                "place, or a repeat rule. NOT for a plain to-do with none of those - that's a " +
                "checklist line now, route it to manage_checklist (list=Todo, create it first with " +
                "action=create if it doesn't exist yet). NOT for shopping: the grocery/shopping " +
                "list is a checklist named \"Groceries\" now, so 'add tums to the grocery list', " +
                "'put milk on the shopping list' and anything else naming groceries or shopping " +
                "goes to manage_checklist (list=Groceries) - never here. A " +
                "dated appointment (see 'kind') goes on the user's calendar instead of a reminder, " +
                // Trimmed 2026-11-10 (worker-workflows ticket 06 paid for claude_session's
                // workflow action): ", the same way you would for a class on a school schedule"
                // was an analogy after the rule, not a rule.
                "and it just passes at its time - it is NEVER tickable or untickable (say so " +
                "plainly if asked to mark one done or not done), and it is not removable, " +
                "schedulable or skippable " +
                "that way either (edit it on the calendar day view instead). " +
                "Pass any date/time the user gives on the SAME 'add' call via date/time - never add " +
                "first and schedule in a second call, which stores an appointment with no date at " +
                "all. Use 'schedule' only to change an existing item's date or set up a repeat. " +
                "For every action but 'add', 'item' fuzzily matches the existing item's text, " +
                "never a position like 'the third one'. A recurring item can't be ticked - edit " +
                "its repeat instead.",
            params = obj(
                "action" to schema("string", "What to do.",
                    enum = listOf("add", "tick", "untick", "remove", "schedule", "skip")),
                "item" to schema("string", "For 'add', the new item's text. For every other action, which existing item to match, in the user's words."),
                "date" to schema("string", "Calendar date, yyyy-MM-dd. For 'add'/'schedule': the item's due date - always pass it on 'add' if the user said one. For 'skip': which occurrence to skip."),
                "time" to schema("string", "24-hour HH:mm. For 'add'/'schedule' only if a specific time was given - omit for an all-day item. Requires 'date'."),
                "kind" to schema("string", "Only for 'add' with a date. 'appointment' goes on the " +
                    "user's calendar (e.g. 'dentist Tuesday at 3', 'lunch with Sam Friday'); " +
                    "'reminder' stays local and private (e.g. 'remind me to change the oil', " +
                    "'don't forget to call mom'). Omit if genuinely unclear - defaults to reminder.",
                    enum = listOf("appointment", "reminder")),
                "repeat_kind" to schema("string", "Only for 'schedule'. Omit to leave any existing repeat untouched; 'none' clears one.",
                    enum = listOf("none", "daily", "weekly", "monthly_on_date", "yearly")),
                "repeat_every" to schema("integer", "Every N days/weeks/months for repeat_kind daily/weekly/monthly_on_date. Defaults to 1."),
                "repeat_days" to schema("string", "Comma-separated days for repeat_kind weekly, e.g. 'MON,WED,FRI'."),
                "repeat_month" to schema("integer", "Month, 1-12, for repeat_kind yearly."),
                "repeat_day" to schema("integer", "Day of month, 1-31, for repeat_kind monthly_on_date or yearly. Past the month's end lands on its last day."),
                "repeat_end_kind" to schema("string", "Only with a repeat_kind set. Defaults to 'never'.",
                    enum = listOf("never", "on_date", "after_count")),
                "repeat_end_date" to schema("string", "For repeat_end_kind on_date: yyyy-MM-dd, the series' last possible date."),
                "repeat_end_count" to schema("integer", "For repeat_end_kind after_count: total occurrences."),
                // alfred-clock ticket 06: the old "wake me up at exactly 6am" example routed wake-ups
                // here; they are the `clock` tool now. Trimmed to pay for that declaration.
                "exact" to schema("boolean", "Only for 'schedule': on the minute, not within the hour. " +
                    "Wake-up alarms are the clock tool."),
            ),
            required = listOf("action"),
        ))

        // manage_grocery (the grocery trip's own tool, 2026-08-11) RETIRED one-today ticket 10
        // slice B, 2026-09-05: "everything is a checklist now". The trip surface it managed
        // (`ui/notes/GroceryScreen.kt`, `LogMode.GROCERY`) is gone - a shopping list is a checklist
        // named "Groceries" now, managed through `manage_checklist` below (see its own description's
        // added clause). A model that still calls `manage_grocery` by name (a cached old session,
        // for instance) gets an explicit retirement message off `dispatch`'s own named branch below
        // (§7: nothing claims success unless the action ran), never a silent "Unknown tool".

        // Checklists (one-today ticket 09's voice slice, 2026-09-05): user-authored named lists -
        // "bio", "morning routine" - each optionally on a daily/weekly schedule, each line
        // optionally MEASURED (a number, not just a tick). ONE tool with an `action` parameter,
        // same rationale as manage_item above. Deliberately separate from it: manage_item is the
        // one persistent list of car-tasks/errands/reminders/notes, a checklist is a named,
        // repeatable routine the user builds themselves, and folding the two together would force
        // the model to guess a destination the same way a fuzzy-destination guess once filed an
        // F150 recall onto the "Car" list (see manage_item's own doc comment).
        //
        // **The grocery/shopping list is a checklist too, one-today ticket 10 slice B, 2026-09-05**
        // ("everything is a checklist now") - `manage_grocery`'s own short-lived trip retired, and
        // any open trip was carried onto a checklist named "Groceries" by
        // `grocery/GroceryChecklistMigration.kt`. The description below says so explicitly so a
        // "add milk to the grocery list" request routes here, `list = "Groceries"`, rather than
        // the model reaching for the now-retired tool by name.
        add(fn(
            name = "manage_checklist",
            description = "Manage a user-authored, reusable CHECKLIST like \"bio\" or \"morning " +
                "routine\" - named lines ticked off, optionally daily/weekly, optionally MEASURED " +
                "(a number against a target, e.g. '8,400 of 10,000 steps') instead of a plain tick. " +
                "NOT the one persistent list (manage_item) - never route a to-do here, or a " +
                "checklist line there. The grocery/shopping list is a checklist named " +
                "\"Groceries\": 'add milk to the grocery list' is action=add, list=Groceries, " +
                "text=milk (it is built in and always exists - never create it); 'the list' with " +
                "food or a household item means Groceries. " +
                "No schedule ('none') means done once ever, the first tick; 'daily' resets each " +
                "day, 'weekly' only applies on its named days - a list created today has nothing " +
                "for yesterday, that is expected. A measured line needs a real number to tick - " +
                "with none given, refuse and ask, via 'value'; never invent one. 'item' fuzzily " +
                "matches an existing line, never a position - on 'add' it is accepted as an alias " +
                "for 'text'. 'lists' reads back every name and schedule.",
            params = obj(
                "action" to schema("string", "What to do.",
                    enum = listOf("create", "add", "tick", "untick", "remove", "read", "lists")),
                "list" to schema("string", "The checklist's name, e.g. 'bio' - required except for 'lists'."),
                "schedule" to schema("string", "Only for 'create'. Omit or 'none' for a plain, done-once list.",
                    enum = listOf("none", "daily", "weekly")),
                "days" to schema("string", "'create' with schedule 'weekly' only: comma-separated days, e.g. 'MON,WED,FRI'."),
                "text" to schema("string", "'add' only: the new line's text, e.g. '3 sets goblet squats'."),
                "unit" to schema("string", "'add' only, if MEASURED: the unit, e.g. 'steps', 'kg', 'min'. Omit for a plain tick line."),
                "target" to schema("number", "'add' with 'unit' set only, if a goal number was given."),
                "direction" to schema("string", "'add' with 'target' set: at least it, or at most it.",
                    enum = listOf("at_least", "at_most")),
                "item" to schema("string", "'tick'/'untick'/'remove': which existing line to " +
                    "match, in the user's words. On 'add', an alias for 'text'."),
                "value" to schema("number", "'tick' on a MEASURED line only: the number reported, e.g. 8400 for '8,400 steps'. Required for a measured line - omitting it refuses the tick."),
                "day" to schema("string", "'tick'/'untick' only. Omit for today.",
                    enum = listOf("today", "yesterday")),
            ),
            required = listOf("action"),
        ))

        // web-calendar-and-lists ticket 04, Kevin 2026-09-16: "i tick off toothpaste today after
        // my trip > i ask the ai, hey when was the last time i bought toothpaste > it looks back
        // at when it was ticked." Reads ChecklistTick.tickedAt (TickHistoryController), never
        // pantry - a tick carries no price and nothing reconciled it (CLAUDE.md §4 rule 5), so
        // this can only ever answer WHEN a line was tapped, never how much or whether it was
        // actually bought. The description below is the only automated grip on that rule
        // (LiveToolboxLastTickedWordingTest pins it, and ai/AriaBrainHonestyClauseTest guards the
        // clause's presence but not its obedience, so the wording has to be said here in words).
        add(fn(
            name = "get_last_ticked",
            description = "When was a checklist line last TICKED - reads tick history, across " +
                "every checklist (including one you have since deleted; deleting a checklist " +
                "never deletes its ticks). A tick is only evidence of a tap, nothing more - it " +
                "carries no price and nothing reconciled it against a receipt or a statement. " +
                "Always say \"ticked\" in your reply, and do not claim a purchase happened. If " +
                "nothing matches, that is an absent RECORD, not proof nothing was acquired - say " +
                "plainly that you have no record of it being ticked, and leave it there. Matches " +
                "on the exact line text (case/whitespace only) - 'toothpaste' will not match " +
                "'Colgate toothpaste'. For 'when did we last buy X' use bought_log.",
            params = obj(
                "item" to schema("string", "The line to look for, e.g. 'toothpaste'."),
            ),
            required = listOf("item"),
        ))

        add(fn(
            name = "read_list",
            // REWRITTEN one-today ticket 10 slice C, 2026-09-05 - see manage_item's own comment for
            // why: this now reads back REMINDERS only, never a plain todo (that's manage_checklist
            // list=Todo, "lists" action).
            description = "Read back the user's open REMINDERS - each with the date, place or " +
                "repeat that triggers it, soonest due first. NOT a plain to-do with none of those - " +
                "use manage_checklist (list=Todo, action=read) for that instead. Use for 'what " +
                "reminders do I have', 'what's left to do for the car', 'what have I got coming up'.",
            params = obj(),
            required = listOf(),
        ))

        // Voice-called modals (ADR 0040: "voice is a LAUNCHER, not a renderer"). Each brings an
        // existing, hand-reachable screen to the foreground as an interactive bottom sheet -
        // dispatch() returns null for all three, same UI-scoped contract as show_saved_places/
        // import_receipt just above; LiveSessionController owns the screen.
        add(fn(
            name = "show_agenda_modal",
            // Note added one-today ticket 10 slice C, 2026-09-05: "any to-do with a date today" was
            // already reminder-shaped by definition (a dateless todo has no "today" to fall on) -
            // added the explicit checklist redirect the other three notes tools now carry too.
            description = "Bring up today's due items - reminders, appointments, and any to-do " +
                "with a date today - as an on-screen list. Does not include checklist lines (a " +
                "plain to-do, or a routine like \"bio\") - use manage_checklist for those. Use for " +
                "'show me my agenda', 'what's on today'. Never for Day A/B or 'DA' (ask_body).",
            params = obj(),
            required = listOf(),
        ))

        add(fn(
            name = "read_calendar",
            // **Rewritten 2026-09-11.** It said "Kevin's GOOGLE CALENDAR" and "This does NOT read
            // LEGION's own list", and both had stopped being true: one-today ticket 01 cut Google
            // entirely ("One calendar, and it is ours"), so this reads LEGION's own `events` table
            // and nothing else. A description that names a system the app no longer talks to is not
            // cosmetic - it steers the model away from the tool for exactly the questions the tool
            // now answers.
            description = "Read Kevin's calendar over a date range - LEGION's own calendar, the " +
                "only one there is. Returns TWO kinds of row and the difference matters: a " +
                "`kind` of \"event\" is something that HAPPENS at a time and simply passes " +
                "whether or not he engages with it (a class, a birthday), while \"task\" is " +
                "something DUE that he has to do - an assignment, a quiz, a homework deadline - " +
                "and carries `done` saying whether it is finished. **School and coursework " +
                "deadlines are tasks, so use this tool for 'what's due', 'what do I have due " +
                "Sunday', 'what's due for school this week', not only for 'what's on my " +
                "calendar'.** Each row gives title, start, end, all_day, kind, and done for a " +
                "task. This does not return reminders or checklist items - those are " +
                "`read_list` and `manage_checklist`. Read-only: use manage_item to change " +
                "anything. A separate `suggestions` list is ideas, not plans.",
            params = obj(
                "from" to schema("string", "First day of the window, yyyy-MM-dd. Call " +
                    "get_current_time first if you need to know what 'today' is."),
                "to" to schema("string", "Last day of the window, yyyy-MM-dd, inclusive. For a " +
                    "single day, pass the same value as 'from'."),
                "city" to schema("string", "Suggestions in this city only."),
            ),
            required = listOf("from", "to"),
        ))

        // **complete_task, 2026-10-03.** Kevin tried "mark my maths assignments done" by voice and
        // was told there was no way to; no tool wrote Event.done. Same writer as the calendar UI's
        // tick (EventsAppointmentWriter.setDone, ADR 0035).
        add(fn(
            name = "complete_task",
            description = "Mark calendar TASKS (assignments, homework) done/not done: 'mark X " +
                "done', 'I finished X', 'untick X'. Not reminders. Several matches change nothing " +
                "and return a list - ask which. Claim only what the result says changed.",
            params = obj(
                "title" to schema("string", "Title words or course code, e.g. 'MATH 2413'."),
                "date" to schema("string", "Optional yyyy-MM-dd due day. Default: overdue + 7 days."),
                "done" to schema("boolean", "False to untick. Default true."),
                "all_matches" to schema("boolean", "True only if they meant every match."),
            ),
            required = listOf("title"),
        ))
    }
}
