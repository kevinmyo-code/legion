# Where does this live in the app?

Type: prototype
Status: resolved
Blocked by: 01

## Question

LEGION's navigation today is Today, Body, Ledger, Pantry, Fleet, plus Cars / Telemetry / Companions /
Settings / Key / Drive Sync behind them. Notes, lists and a calendar have to land somewhere.

### What must be decided

1. **Its own destination, or folded into an existing one.** A sixth top-level place, versus a
   section on Today. Read `ui/LegionRoute.kt` and `ui/MainActivity.kt` before deciding.
2. **The relationship to Today.** `TodayScreen` already aggregates gaps across domains and now
   carries workouts, meals and sleep. Today's events and due reminders are the single most obviously
   Today-shaped thing in the whole app. Decide what Today shows versus what the notes destination
   shows, without showing it twice.
3. **How many screens.** A list-of-lists, a single list, a calendar view (ticket 08), and a note
   editor could be one screen or four.
4. **Whether the calendar is a peer or a view.** Charting decision 6 makes an event an item with
   times, so a calendar is a *view* over the same data. Decide whether the navigation reflects that
   or hides it.

### Approach

`/prototype`. Sketch the navigation and the list-of-lists screen concretely enough to react to.
Cheaper than arguing in the abstract, and the existing screens give plenty to match.

### Constraints

- The design language: `ui/theme/` exists and `ThemePreview.kt` has five previews. **Render them
  before building on the theme.** This is ticket 07's own instruction on the `ledger-drive-ingestion`
  map, it was skipped, and skipping it is exactly how the red-body-text bug shipped (L11). Do not
  repeat it here.
- Motion is unrestricted (phone-only pivot lifted the head-unit ban). Normal Compose animation is
  allowed.

## Answer

**One new top-level destination, "Notes", with the calendar as a view inside it** (Kevin,
2026-08-07).

```
[Today] [Body] [Ledger] [Pantry] [Fleet] [Notes]
                                           |
                                  LISTS | CALENDAR
```

This is honest to the model rather than to habit. Charting decision 6 makes a calendar event an item
with times, so a calendar genuinely *is* a view over the same rows - and the navigation says so
instead of implying a split the data does not have. It also holds the top-level count at six.

### Today

`TodayScreen` shows **today's timed items and anything due, as a summary that links into Notes.** It
does not become a second list editor. Today already carries workouts, meals, sleep and gaps; the rule
is that Today answers "what about today" and Notes answers "show me everything", and the same item
never renders in two different shapes.

### Screens

Three, not four: a list-of-lists, a single list (which is also the note editor - charting decision 2
means they are the same screen), and the calendar view (ticket 08). Branching logic goes in pure
resolver functions, matching `TodayGapResolvers` / `BodyGapResolvers` / `LedgerPendingResolver`, so
it is unit-testable without rendering.

### Binding, not advisory

**Render `ui/theme/ThemePreview.kt`'s five previews before building on the theme.** This was ticket
07's own instruction on the `ledger-drive-ingestion` map, it was skipped, and skipping it is exactly
how the red-body-text bug shipped (L11). It is still unmet across three screens changed earlier
today. Do not add a fourth.
