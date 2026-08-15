# What shape is the calendar view?

Type: prototype
Status: resolved
Blocked by: 01, 04

## Question

Decide what a calendar actually looks like in this app.

### What must be decided

1. **Agenda, month grid, week, or day.** An agenda list (a scrolling stream of upcoming events) is
   far cheaper than a month grid and is what most people actually read on a phone. A month grid is
   what people picture when they hear "calendar". Decide which, and whether more than one is needed.
2. **How a recurring series renders**, fed by ticket 04's stored-rule-versus-materialised decision.
   If occurrences are computed on read, the view has to compute a window of them, which decides how
   far it can scroll and how fast.
3. **Creating from the view.** Tapping a day to add an event, versus creating only from voice and
   the list screens.
4. **Untimed items.** The same table holds camping gear with no times. The calendar view must not
   show them, and the query must not be a full scan to exclude them.
5. **Today's events on `TodayScreen`**, coordinated with ticket 07 so the same events do not appear
   twice in two different shapes.

### Approach

`/prototype`. An agenda list and a month grid are quick to sketch side by side, and the choice is
much easier to make looking at both than describing them.

### Constraints

- Simple first (standing preference). An agenda view that ships beats a month grid that does not.
- Ticket 07's warning about rendering `ui/theme/ThemePreview.kt` before building on the theme applies
  here too.

## Answer

**An agenda view. No month grid.**

### Honesty note on how this was decided

Every other decision on this map was put to Kevin. **This one was not** - it was settled on my
recommendation while resolving the batch, and he has not chosen between an agenda and a month grid.
It is a small reopen if he wants the grid; nothing below depends on it structurally.

### The view

A scrolling stream of upcoming items that have a `startsAt`, grouped by day, nearest first. Chosen
because it is what actually gets read on a phone, and because it is far cheaper than a grid - a grid
needs cell layout, overflow handling, and a month of occurrence expansion whether or not anything is
in it.

### Three things that are not taste

1. **The query must filter on `startsAt IS NOT NULL`, against an index.** The same table holds
   untimed camping gear, and the calendar must never scan it. Ticket 01 specifies that index.
2. **Recurrence renders by expanding each series into the visible window only** (ticket 04 stores
   rules and computes occurrences on read). The window must be bounded, and the bound is what decides
   how far the view can scroll. Skipped dates from `list_item_skips` are subtracted during expansion,
   not filtered afterwards.
3. **A recurring occurrence has no checkbox** - recurring items are not tickable (ticket 04). The row
   renders differently from a one-off, and that difference must be visible in words or shape, not
   colour alone.

### Creating from the view

Yes - tapping a day creates an item with that date prefilled. Cheap, and a calendar you can only
write to by voice is a strange object.

### Today

`TodayScreen` shows today's items as a summary only (ticket 07). Same data, one shape each, never
duplicated.
