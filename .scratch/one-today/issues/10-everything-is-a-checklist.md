---
map: one-today
ticket: "10"
title: "Retire the persistent list and the grocery trip: everything is a checklist"
type: build
status: open
status-detail: "Slice A (checklist voice tools) building 2026-09-05. B and C follow in order."
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Retire the persistent list and the grocery trip: everything is a checklist

**Kevin, 2026-09-05:** *"why dont we retire persistent list? and groceries trip? since everything is a
custom checklist now?"*

Yes. Both are modes of ONE screen (`ui/NotesScreen.kt`, `LogMode.ITEMS` and `LogMode.GROCERY`),
reached from two rows on the METERS LISTS pane, so they retire together. Everything else that reads
reminders - the calendar day view, `notes/AlarmScheduler.kt`, place triggers, the digest - calls
`NotesController` directly and keeps working. The grocery trip has no connection to receipt ingestion
(`pantry/` never references it), so the §4 side is untouched.

## The constraint that orders the work

Both surfaces have voice tools (`manage_item`, `read_list`, `show_list_modal`, `manage_grocery`,
`show_groceries_modal`). Checklists have none. Retire the screens first and voice keeps writing into
surfaces nobody can see - ADR 0035 inverted. So:

| Slice | Work |
|---|---|
| **A** | `manage_checklist` voice tool: create / add / tick (with value) / untick / remove / read / lists, by list name. Voice guide copy. |
| **B** | Retire the grocery trip. One-time migration of open `grocery_items` into a non-recurring `Groceries` checklist. Remove `LogMode.GROCERY`, the METERS row, `manage_grocery`, `show_groceries_modal`. `grocery_staples` goes write-dead and stays as history. |
| **C** | Retire the persistent list. Dateless open reminders (not `Plan:` lines) migrate into a non-recurring `Todo` checklist. Dated / alarmed / place-triggered reminders stay on the calendar. `manage_item` / `read_list` stay but their descriptions say REMINDER, never list. Remove `LogMode.ITEMS`, the row, `show_list_modal`. **Precondition: a reminder's time, repeat and place must be editable from the calendar day view once the screen is gone** - verify or build that first. |

## What is knowingly lost

- **Staple suggestions** ("you usually buy milk"). `GroceryController.suggestions` reads
  `grocery_staples`; a `Groceries` checklist has no history-derived suggestions until the analysis
  slice of ticket 09's plan exists. Kevin accepted this.
- **Trip teardown.** A trip emptied itself on `finish`. A checklist is durable; a bought item is
  ticked, and the list is either cleared by hand or the ticks are read as this week's shop.

## What is deliberately kept

`GoalChecklistSync`'s `Plan:` lines render in `ui/goals/GoalChecklistPanel.kt` on the calendar,
independent of `NotesScreen`; they retire onto checklists in ticket 09's slice 6, not here.
`InboxScreen`'s "Lists stay on this phone only" notice is stale against the events dual-path sync and
goes with the screen.
