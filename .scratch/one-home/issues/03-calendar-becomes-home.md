---
map: one-home
ticket: "03"
title: "CALENDAR becomes HOME"
type: build
status: open
status-detail: >
  Rename only. Split from the METERS deletion on 2026-09-10 so it could
  proceed: the rename is decided by Kevin's own words ("rename it from
  calendar") and depends on neither ticket 01's shell decision nor ticket
  02's rehoming. The deletion moved to 03b, which stays blocked.
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# CALENDAR becomes HOME

**Kevin, 2026-09-10:** *"just everything on home page (rename it from calendar)"*

The rename is decided - that half of the sentence is a directive, not a musing. **The deletion of
METERS is ticket 01's call and lives in [[03b-delete-meters]]**, which stays blocked; this ticket is
the rename alone, and it depends on nothing.

## Why a rename is not a find-and-replace

`calendar` is a route string, and route strings are addresses other things hold:

1. **`LegionRoute.CALENDAR`** and its `startDestination` role.
2. **`LegionRoute.label`** - "Calendar" becomes "HOME". Under ticket 01 option A the tab row may
   stop existing entirely, in which case `label` and `topLevelOf` lose their only caller and should
   go with it rather than linger as dead code. `LegionRoute.kt:340-358` records that exact tidy-up
   being done once already, grep-confirmed, when six tab branches died.
3. **Deep links and alarm targets.** `ReminderAlarmReceiver` and `EXTRA_ROUTE` navigate by string.
   `CalendarScreen`'s own doc names `onOpenAlarm` as landing here. **A stale route string does not
   fail to compile - it fails at 6am when an alarm fires and nothing opens.** Grep for the literal
   `"calendar"` as well as for the constant.
4. **The word in user-facing copy.** `ui/help/VoiceGuideData.kt` names screens; `docs/voice.html`
   is generated from `tools/voice_guide_copy.py` and drift there is a hard failure by design
   (`python tools/voice_guide.py` exits non-zero and names what is missing).
5. **The word in the docs layer.** `docs/architecture/` names source paths and screens;
   `python tools/docs_check.py` fails on a documented path that no longer exists. Run it.

**Keep the file's history comments.** `LegionRoute.kt`'s class doc is a supersession chain - four
tabs, then five, then three, then two. Add this cutover to it in the same style. Do not rewrite the
earlier entries; that chain is why anyone can tell what "view C" meant.

## Does the FILE rename too?

`ui/CalendarScreen.kt` to `ui/HomeScreen.kt` is optional and has a cost: `git log --follow` still
works but every existing reference in `.scratch/`, `docs/` and the memory library points at the old
name. **Recommendation: rename the route, the label and the copy; leave the file name alone in this
ticket** and let it change when the screen is next substantially rewritten. The screen is still a
calendar - a month grid with a day view - and calling the file what it renders is not the same
mistake as calling the TAB what it renders.

If it is renamed anyway, `tools/docs_check.py` is the check that catches the stragglers.

## Verification

- `compileDebugKotlin`, full suite, totals from the JUnit XML.
- `python tools/docs_check.py` clean.
- `python tools/voice_guide.py` exits zero.
- `python tools/obsidian_sync.py` and `python tools/pending_wiki.py` re-run (the commit hook does
  this, but run them while iterating).
- **Grep for the literal string `"calendar"` across `app/src/main`** and account for every hit as
  renamed or deliberately untouched. This is the step that catches the alarm target.
- On the phone: cold start lands on HOME; fire a reminder and confirm it opens the right screen.
  That second one cannot be done in a unit test and must not be reported as done without a device.
  **Deferred to [[08-ship-pass]] step 3 rather than claimed here.**
