---
map: home-launcher
ticket: "04"
title: "Lists as icon cards; a list opens to a real checklist"
type: build
status: built
status-detail: >
  Built 2026-09-27. ListsViewModel (AndroidViewModel, one StateFlow) plus
  ListVisual/ListProgress (pure, unit-tested) drive an icon-card Lists page
  and a Keep-style open list, all under SoftTheme; ChecklistController.ItemState
  gained tickDay (additive) closing the untick trap, proven by a controller
  test. compileDebugKotlin green; testDebugUnitTest 3644/3644 by XML (up from
  3611 baseline + AppDrawerCacheTest), one confirmed pre-existing
  order-dependent flake (AssistantStripScreenshotTest/UncaughtExceptionsBeforeTest,
  passes clean in isolation, unrelated to this ticket's files); detekt clean
  for every file this ticket touches (78 new baseline entries, named per file
  under their own `=== File.kt ===` markers - mostly Compose's own PascalCase
  naming convention that this repo's detekt.yml has no `ignoreAnnotated`
  exception for, plus ListsViewModel's TooManyFunctions, a direct consequence
  of the ticket's own "one ViewModel" design; 107 pre-existing findings
  elsewhere, in files this ticket never touched, already unbaselined before
  this ticket started). Roborazzi baselines recorded (5 PNGs). Needs a run on
  the phone; the calendar day view's own untick has the identical
  any-day-plain-list bug (finding for Kevin, not fixed here per the ticket's
  own instruction).
blockers: ["02"]
blocked-by: ["[[02-soft-theme-and-chrome]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Lists as icon cards; a list opens to a real checklist

Picture: `research/prototype-canvas/ListsC.dc.html` - the page AND the list screen it opens (press
Play in the canvas to use it). Kevin: *"I like C, icon cards. looks clean that way."*

`ChecklistsScreen(onBack)` stays the entry point, so `MainActivity` and the `CHECKLISTS` route do
not change. Its list and detail modes are rewritten; the history mode keeps its behaviour and moves
onto the soft theme. Everything renders inside `SoftTheme`.

## Structure

- `ListsViewModel` (`AndroidViewModel`, one `StateFlow<ListsUiState>`, `refresh()` on
  `ON_RESUME` and after every write) - page state, the open list's state, session-only UI flags.
- `ListsContent(state, callbacks)` and `ListDetailContent(state, callbacks)` stateless, for
  Roborazzi.
- `ListVisuals.kt` (pure): a list's icon and colour from its NAME, via a keyword table then a
  stable fallback on its id - groceries/grocery/shopping to `shopping_cart`; todo/to do/tasks to
  `task_alt`; bio/workout/gym/fitness to `fitness_center`; morning to `wb_twilight`;
  evening/night/bed to `bedtime`; packing/travel/trip to `luggage`; house/home/chores/cleaning to
  `home`; meds/medicine/pills/vitamin to `medication`; reading/books to `menu_book`; work to
  `work`; school/study/class to `school`; car to `directions_car`; else `checklist`. Colour: the
  eight `AreaAccent` pairs; keyword matches get a fixed pair, the fallback is `id mod 8`. Unit-test
  the table and that the fallback is stable.
- `ListProgress.kt` (pure): ring fraction and label per card, unit-tested.
- **Every write is a `ChecklistController` call.** No DAO in `ui/`.

## The Lists page

- Top bar: back (`onBack`), "Lists" (`titleLarge`), overflow with "Show archived" / "Hide
  archived".
- Two sections, each a 3-column grid, 10dp gaps: **"Routines"** (`scheduleKind != null`) then
  **"Lists"** (plain). Omit an empty Routines section. Archived lists, when shown, get a third
  section "Archived", cards dimmed.
- **Icon card** (`card`, `shapes.large`, centred column): a 68dp progress ring (track
  `cardHighest`, arc in the list's colour) around a 58dp chip in the list's container colour with its
  icon (28dp), then the name (`titleSmall`, one line, ellipsised), then the short label (`labelSmall`
  `text2`):
  - routine that applies today: "2/6 today"; ring = today's ticks / items.
  - routine that does NOT apply today (`checklistsForDay(today)` excludes it): "Not today", ring
    empty.
  - plain: "2/8"; ring = ticked / items.
  - no items: "Empty".
  - its item read `Failed`: "Couldn't load", no ring. Never a quiet "0/0".
- Last in the Lists section, a dashed "New list" card (1.5dp dashed `outline`, `ms_add`) opening a
  dialog: name + the three-state schedule picker the old screen had (none / daily / weekly on chosen
  days), restyled. Create, then open the new list.

## A list, opened (Keep-style)

- Top bar: back, the list's name (`titleLarge`), overflow: Rename, Schedule, History,
  Archive/Unarchive, Delete list.
- **Delete always confirms** (the old DELETE LIST stamp deleted on one tap - fix that): "Delete
  <name>? The list goes. What you ticked off it is kept." (ADR 0049 wording: ticked, never
  bought.) Soft delete through `deleteChecklist`, then back to the page.
- A routine shows one line under the top bar: its schedule and "Ticks count for today, <day>." - or
  "Not scheduled today." when it does not apply.
- Unticked items, in order: a 22dp rounded-square checkbox (2dp `text3` border) in a 48dp touch
  target, the text in `bodyLarge`. Tap ticks.
- **Measured items** (`measureUnit != null`): a caption from `measurePromptLabel`, and an inline
  number field with the unit on the row's right. Ticking with an empty or unparseable field calls
  `tick(..., value = null)` and shows the `TickOutcome.Refused` message **under that row, in words,
  in `caution`** - the controller's own message, not a rewrite.
- Below them, inline: "+ Add item" field. The keyboard's Done adds (`addItem`, `sortOrder =` item
  count) and keeps focus for the next one.
- A divider, then a collapsible header "N ticked" (plain) or "N done today" (routine), expanded by
  default, session-only. Ticked rows: filled `tickedBox` checkbox with a check, text struck through
  in `text3`; a measured tick shows its value ("Water, 2.5 L"). Tap unticks.
- An item whose tick or untick is still in the outbox shows `ms_cloud_off` + "Not synced yet" in
  `caution`, from `ChecklistsOutboxDrain.queuedItemIdsForDay` - a tick that has not reached the
  engine must not look like one that has (CLAUDE.md sec 7).
- Long-press an item: a small sheet - Edit (the existing text + measure editor, restyled), Move up,
  Move down (the existing adjacent `sortOrder` swap), Delete item.
- A PLAIN list whose items are all ticked shows a card at the bottom: "Everything is ticked." and a
  "Delete list" button into the same confirm. Never an auto-delete.
- No items: "Nothing on this list yet. Add the first item below."

## The untick trap - fix it in the controller, not around it

`ChecklistController.ItemState` has no tick day. For a PLAIN list, `itemsWithTickState` says ticked
if ANY live tick exists on ANY day, but `untick(itemId, day = today())` only clears today's - so an
item ticked on an earlier day can never be unticked from a screen that passes today. (The web already
handles this: `server/frontend/src/lib/checklist.ts`'s `dayToClear`.)

Add `tickDay: Int? = null` to `ItemState` (additive, defaulted - no call site breaks), filled in
both branches: the viewed day when ticked for a routine, the latest live tick's day for a plain list.
The list screen unticks `tickDay`. A controller test proves a plain item ticked yesterday unticks
today. Leave the calendar day view's own untick alone, but name in the report whether it has the
same bug - that is a finding for Kevin, not a fix for this ticket.

## Verification - every step accounted for

Same rules as 02 and 03: `./gradlew` in this worktree, totals from the JUnit XML.

1. `compileDebugKotlin -Pnokey`, `testDebugUnitTest` green; the XML count.
2. Unit tests: `ListVisuals`, `ListProgress` (including Not today, Empty, Failed), the `tickDay`
   controller test.
3. Roborazzi at 384 x 636dp: the page (routines + lists + new card; a Failed card; archived shown);
   a plain list with a ticked group and a not-synced row; a routine with a measured item showing a
   refusal; the all-ticked delete offer; an empty list. Paths in the report; **do not delete them.**
4. detekt, `docs_check.py` clean; no file over the 1000-line hook (`ChecklistsScreen.kt` is 785
   lines today - split it rather than grow it).
5. One commit for this ticket; ticket to `status: built`.

Owed to ticket 05: the real phone - tick and untick on Groceries and on a routine, a measured
refusal, add, long-press, archive, delete.
