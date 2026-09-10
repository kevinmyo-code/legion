# Today screen — research notes

Status: research only, ticket 05 blocked on 03/04. No screen built yet. This file exists so
whoever builds `/` Today later starts from evidence, not a blank page.

## What I looked at

1. **Cozi (cozi.com)**, marketing pages only — no account created (account creation is off-limits
   for me; Cozi requires signup to see the live app). Pages read:
   - `https://www.cozi.com/calendar/` — the "Shared Family Calendar" feature page. Screenshot of
     the shared calendar week view: `docs/design/refs/cozi-calendar-week-view.jpg`.
   - Same page, further down: the **"Cozi Today"** feature callout, with a phone screenshot of the
     actual agenda screen: `docs/design/refs/cozi-today-agenda.jpg`.
   Cozi is the closest existing product to LEGION's Today screen in audience and intent: a family
   organizer app, non-technical users, calendar + to-dos + shopping list in one shared view.

2. **Google Calendar** — I navigated to `calendar.google.com` and it loaded the operator's own
   logged-in account with real personal data (course schedule, personal events). I did not
   screenshot or explore this further; closed it out as soon as I recognized it was a live personal
   account, not a demo. **No evidence taken from this source.** Noted here so the gap is honest
   rather than silently absent.

3. Apple's Reminders marketing page (`apple.com/ios/reminders/`) 404'd — not reachable, no evidence
   taken.

## What I could not reach

- Cozi's actual in-app Today screen (behind signup, which I cannot do per the account-creation
  rule).
- Apple Reminders "Today" smart list (page 404, did not find a working alternate URL in budget).
- Todoist / TickTick "Today" views — not attempted, time budget went to Cozi (closest audience
  match) plus the join/members research the ticket also asked for. **Named gap**, not a described-
  from-memory guess.

## Decisions observed (Cozi)

- **"Cozi Today" is a distinct, named view from the calendar itself.** It is not the calendar's
  first tab reused — it is a purpose-built agenda screen. Reading `docs/design/refs/cozi-today-agenda.jpg`:
  - Header: "Upcoming events"
  - **Two labelled groups: "Today" and "Tomorrow"**, each a flat list of `time — title`, with
    small coloured dots against each item identifying which family member it belongs to (no
    avatars, no photos in the list itself — colour-coded initials/dots only, keyed to a legend
    elsewhere in the app).
  - Below that, a **separate card: "Groceries"**, showing the first ~4 items and then
    `plus 45 more items` — the list is not exhaustively rendered on Today, it is preview + count.
  - Nothing past "Tomorrow" appears on this screen. The marketing copy says Cozi Today shows
    "everything you've got planned for each day" but the actual screenshot caps at Today +
    Tomorrow, not a full week. That is a real product decision worth noting: a week of rows is a
    wall of rows, and Cozi didn't try to fit it on the first screen.
  - The week/month calendar (`cozi-calendar-week-view.jpg`) is a **separate screen**, reached by
    switching from "Today" tabs to "Week"/"Month" tabs at the top. To-dos are interleaved into the
    day's rows there too ("To Do — Return library books by 7/11" sits above the timed events for
    that day), not in a separate list.

## What's worth stealing, and why

- **Today + Tomorrow, not Today + next 7 days, as the primary view.** Ticket 05 specifies "events
  today + 7 days" for Today's reads. Cozi's own flagship "at a glance" screen caps at two days.
  Recommendation: read 7 days (the ticket's data contract doesn't need to change) but **render only
  Today expanded**, with tomorrow as a single collapsed/secondary group, and the rest of the week
  reachable as a tap-through to `/lists`-style full agenda — not rendered as rows on first load.
  This directly answers the brief's question ("what belongs on first load vs one tap away").
- **A checklist item lives inside the day's agenda, not a separate section**, when it's due that
  day (Cozi's "To Do" row sits above the timed events). LEGION's checklist items due today should
  sit at the top of Today's list, visually distinct (no time badge) but in the same flow — not in
  a second card the user has to scroll past events to reach.
- **Preview + count for a longer list, never a full render.** Cozi's Groceries card shows 4 items
  and "plus 45 more." LEGION's `/lists` groceries preview on Today (if it appears here at all — see
  below) should do the same, not dump the whole pantry list onto the day screen.
- **Colour-coding by person, not by category.** Since Kevin's parents are a two- or three-person
  household, a small colour dot or initial per person on shared events is legible at that scale and
  answers "whose thing is this" without opening the event.

## What would be wrong here, and why

- **Cozi's screen has no visible empty state** in anything I could reach — every marketing
  screenshot is stocked with fake data. LEGION's Today must say **"Nothing due today"** in words
  when the checklist is empty and **"Nothing on the calendar today"** when events are empty — these
  are two different sentences for two different sources, not one blended empty state, because they
  come from different reads (events vs checklist) and a parent should be able to tell which is
  actually empty versus which the app simply couldn't reach (ticket 05's own note: a stale/offline
  read must say so, not render as "nothing due").
- **Cozi's list has no visible tick/skip affordance** in the marketing shot — it's read-only
  promotional art. Ticket 05 requires tick AND skip as separate actions (a skip is not a tick — it's
  "not doing this today," which the checklist row must be able to say back in words if the parent
  re-opens the day). Cozi gives no model for this; it has to be designed here, not copied.
- **Cozi's calendar mixes to-dos into the same rows as timed events with no visual distinction
  beyond a "To Do" label prefix.** For LEGION, a checklist item is a genuinely different kind of
  thing from a calendar event (it has a tick/skip lifecycle, an event does not) and should carry a
  distinct control (checkbox) rather than just a text label, so the action is visible without
  reading the row.
- **Nothing in Cozi carries a trust disclosure**, because Cozi has no reconciliation gate — it's a
  pure user-entered calendar/list app, nothing is ingested or estimated. LEGION's Today itself
  carries no figures (per the ticket), so this doesn't bite yet, but Today must not set a visual
  tone (e.g., an all-green "everything's fine" dashboard chrome) that phase-2 screens then have to
  break out of to make room for `unverified`/`estimate` labels. Keep Today plain: text rows, a
  checkbox, a time — no card chrome implying a polish level the ledger/pantry screens can't match
  once they're covered in disclosure words.

## Recommended layout for LEGION's Today

Two zones, no tabs, no wall of rows:

1. **Checklist due today** — flat list, checkbox left, label, optional time-of-day if the item has
   one. Skip renders as a secondary action (icon or small text link) next to each row, not a swipe
   gesture (a parent on a phone browser, not a native app, may not discover swipe). Empty state:
   plain sentence, "Nothing due today."
2. **Today's events**, then a single collapsed **"Tomorrow"** disclosure/heading below it (not
   auto-expanded) showing the same row shape. The rest of the 7-day read backs a "View this week"
   link rather than being rendered as rows. Empty state: "Nothing on the calendar today."

A small "+" affordance (add event / add checklist item) sits at the top of the screen, not buried
per-section — ticket 05 lists add as one of Today's three actions and it should be reachable in one
tap regardless of which zone the parent is looking at.

```
+----------------------------------------------------+
| [Household name]                 [+ Add]  [profile]|
+----------------------------------------------------+
| To do today                                          |
|  [ ] Call the pharmacy                      skip     |
|  [x] Water the plants                                |
|  Nothing else due today.                              |
+----------------------------------------------------+
| Today, Sep 10                                         |
|  9:00a  Dentist — Mom                                 |
|  2:00p  Grandkids visit — everyone                    |
+----------------------------------------------------+
| Tomorrow  (Sep 11)                          [expand] |
|  3 events — tap to see                                |
+----------------------------------------------------+
|  View this week ->                                    |
+----------------------------------------------------+
```

Narrow viewport: single column, this shape unchanged (it already reads top-to-bottom, no rail
needed). Wide viewport: this content stays a single centered column — Today does not need a
multi-column dashboard; ticket 05's left-rail shell wraps around it, not inside it.

## Assumptions ledger

- Cozi's Today screen structure (Today/Tomorrow groups, colour dots, groceries preview+count):
  **in-browser** — loaded the live marketing page and read the actual product screenshot embedded
  in it, saved to `docs/design/refs/cozi-today-agenda.jpg`.
- Cozi's week calendar screen (to-dos interleaved with events): **in-browser**, same session,
  `docs/design/refs/cozi-calendar-week-view.jpg`.
- Cozi's in-app tick/skip/empty-state behavior: **not observed** — flagged as a gap above, not
  guessed.
- The recommended layout: **reasoned** from the above plus the ticket's own trust-disclosure and
  ADR 0035 hands-path constraints, not copied wholesale from any one source.
