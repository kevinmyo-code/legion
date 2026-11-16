package com.kevin.legion.service.tools.day

/**
 * Where each kind of list lives, and what a spoken word for it routes to: the ONE place
 * (2026-11-15 voice eval, flash-lite: "what's on my to-do list" read the reminders, "add X to my
 * to-do list" made a reminder, "mark the quiz done" ticked a reminder that did not exist).
 *
 * The data model, read from the controllers:
 *
 * - a **reminder**: a `ListItem` on the one notes list with a trigger (a date/time, a place or a
 *   repeat). Voice: `manage_item`; `read_list` reads them.
 * - the **to-do list**: the checklist named [TODO] (dateless to-dos moved there, one-today ticket 10
 *   slice C). Voice: `manage_checklist list=Todo`.
 * - a **checklist**: any named `Checklist` ("bio", Groceries, Todo). Voice: `manage_checklist`.
 * - a **calendar task**: an `Event` with `kind = task` (Canvas coursework, homework). Voice:
 *   `day complete_task`; read by `day read_calendar`.
 * - **what is due**: calendar tasks AND dated reminders, one merge
 *   ([com.kevin.legion.calendar.DueReader]). Voice: `day read_calendar`.
 *
 * "To-do list" is the one genuinely ambiguous phrase: the reminder list was called the to-do list
 * for months, and the phone card that shows reminders is still titled "To-dos". So a READ of either
 * carries the other ([REMINDERS_KEY] on a Todo read, [TODO_KEY] on a reminder read) and the model
 * never has to guess. A WRITE is made robust in code instead of in the prompt: an undated add lands
 * on [TODO], and a tick that matches nothing on the list it was sent to tries the others (a reminder,
 * a Todo line, a calendar task), saying in its result which one it changed.
 */
internal object ListRouting {
    /** The plain to-do checklist's name. */
    const val TODO = "Todo"

    /** On a Todo read: his open reminders, also part of "my to-do list". */
    const val REMINDERS_KEY = "reminders"

    /** On a reminder read: the open lines of the Todo checklist. */
    const val TODO_KEY = "todo_list"

    private val NON_WORD = Regex("[^a-z0-9]+")
    private val TODO_NAMES = setOf("todo", "todos", "todolist", "mytodolist", "mytodo", "mytodos")

    /** "Todo", "to-do", "to do list", "my to-dos": the Todo checklist. */
    fun isTodoName(name: String): Boolean = name.lowercase().replace(NON_WORD, "") in TODO_NAMES

    /**
     * The base instruction's paragraph on lists, said always (it costs nothing per tool call and is
     * in the cached prefix). Kept short: the results carry the rest.
     */
    const val PROMPT =
        "Lists: his to-do list is the Todo checklist (manage_checklist list=Todo: read, add, tick). A " +
            "reminder has a date, time, place or repeat (manage_item; read_list reads them). A calendar " +
            "task (assignment, homework) is marked done with day complete_task. Anything due (what do I " +
            "have due, what's due today, tomorrow or this week) is day read_calendar over those dates: its " +
            "`due` block holds school work and dated reminders in one list, overdue ones marked."

    /** The rule riding on a Todo checklist read that carries [REMINDERS_KEY]. */
    const val TODO_READ_NOTE =
        "`items` is the Todo checklist; `reminders` are his dated or repeating reminders, also part of " +
            "his to-do list: say both."

    /** The rule riding on a reminder read that carries [TODO_KEY]. */
    const val REMINDER_READ_NOTE =
        "`items` are his reminders; `todo_list` is the separate Todo checklist: say it too when he " +
            "asked about to-dos or his list in general. For what is due, call day read_calendar."
}
