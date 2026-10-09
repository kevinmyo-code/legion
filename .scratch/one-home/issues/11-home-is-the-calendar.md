---
map: one-home
ticket: "11"
title: "HOME is the calendar: month grid, day agenda, three panel buttons"
type: build
status: built
status-detail: >
  Built 2026-10-09 on feat/home-calendar, not merged. HOME's middle area is now prototype A: month
  header, 6-week grid, selected-day agenda, and To-dos / Lists / Ideas buttons opening bottom sheets,
  above the unchanged dock and category row. Dock: a one-app bucket wears its app's icon; Google Maps
  can be added to the Maps bucket again. Owes the device run, and owes Kevin a ruling on where the
  six unmounted tiles (Money, Body, Fleet, Recordings, News, Reports) are reached from.
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# HOME is the calendar

**Kevin, 2026-10-09:** *"meanwhile, i wanna revamp my phones home page. i want the calender to be
the main thing, no scrolling, with lists, events, todos all accessible somehow."*

After seeing three prototypes: *"i like A, for the phone screen. keep the existing top bar ofc. app
tray opener and settings etc. obd link etc. bottom for the pinned apps and the prebuilt app buckets
too should stay. if a bucket has only one app, take the icon of that app too. map bucket > let me add
google maps back there"*

## The decision

Prototype A, saved beside this ticket as `research/home-calendar-prototype-a.html` (the file the
decision was made against; read it for sizes, colours and behaviour).

- **Kept exactly:** the shell's top bar (status line, app-tray opener, settings, OBD link) and the
  talk bar with the mic; the pinned dock; the five category buckets.
- **Replaced:** the today card and the 2 x 4 tile grid, with the calendar.
- **No scrolling** at any font scale the screen supports. The grid gives height back first, down to a
  floor, so the agenda keeps three rows; the agenda then shows what fits and "+N more".
- **Suggestions are not plans.** Own marker (square, NEWS accent), own words ("Suggestion - not a
  plan"), sorted after every plan, never in the to-do or plan counts. `EventReadsAreKindFilteredTest`
  stays green and nothing here reads `events` without naming a kind.
- **Dock:** a bucket with exactly one installed app shows that app's icon. Google Maps is addable to
  the Maps bucket (see below).

## Where things are

| What | Where |
|---|---|
| State, pure functions | `ui/home/HomeCalendarModels.kt` |
| Reads and writes, behind one seam | `ui/home/HomeCalendarSource.kt` (calls the existing readers; adds no query) |
| ViewModel, one `StateFlow<HomeCalendarUiState>` | `ui/home/HomeCalendarViewModel.kt` |
| Month, grid, agenda, buttons | `ui/home/HomeCalendar.kt` |
| The three sheets | `ui/home/HomePanelSheets.kt` |
| HOME itself | `ui/home/HomeScreen.kt` (`HomeContent` new; the old layout is `HomeTilesContent`, unmounted) |
| Dock changes | `ui/home/CategoryRow.kt`, `ui/home/CategorySheets.kt` |
| Day view opens on a given day | `ui/CalendarScreen.kt` `initialDayStart`; `ui/MainActivity.kt` |
| Lists opens a given list | `ui/checklists/ChecklistsScreen.kt` `initialChecklistId` |

## Why Google Maps could not be added

Not an exclusion list. Commit `3d5376a6` ("HOME's Maps button opens LEGION's Mapbox nav screen, not the
picked map app") made the Maps button's tap AND long-press both call `openNav()`, so its chooser
sheet could never open and nothing could be picked. The app drawer itself lists Google Maps (the
`<queries>` package entry stays). Now: tap with nothing picked still opens LEGION Navigation;
long-press opens the chooser like every bucket; with an app picked, a tap asks which, with LEGION
Navigation listed first. **ADR 0054 forbids handing NAVIGATION to another map app; a launcher entry
is not that**, and no navigation code changed.

## Decisions taken in the build that Kevin did not make

1. **Maps with a pick asks instead of launching.** Otherwise adding Google Maps would remove the only
   door to LEGION Navigation from HOME.
2. **Where a tapped row goes.** A reminder opens its edit dialog (the notification-tap path); an
   event, task or suggestion opens that day's day view, because that is the only detail the app has
   for them. "+N more" opens the same day view.
3. **Events dot = EVENT-kind rows only.** The drill-down's dots also count timed reminders; here a
   reminder is a to-do and marked as one, so a blue dot always has an Event row behind it.
4. **Ideas = Friday to Sunday** (starting today once inside it). Tap an idea for "Add to my plans" /
   "Not interested", the existing `EventSuggestions` actions and their sentences.
5. **Grid digits, weekday letters, legend and the agenda's title are dp-sized, not sp.** At font scale
   1.3 in the 636dp content box nothing else fits three agenda rows. Everything else scales.
6. **Agenda rows are 40dp (grown with font scale), not the prototype's 44**, for the same reason.
7. "Plus who pinned it, if pins exist by then": no pins exist, so rows say only the type.

## Owed from Kevin

**Where do Money, Body, Fleet, Recordings (and its record button), News and Reports open from now?**
Those six tiles were the only hands path to those screens (ADR 0035); the brief replaced the area they
lived in and did not say. They are not deleted: `HomeTilesContent` keeps the old layout and its
screenshot tests, and the callbacks still reach `HomeScreen`. Until ruled, voice is the only way in.

## Verification

- [x] `compileDebugKotlin -Pnokey`; `testDebugUnitTest` (totals from the JUnit XML).
- [x] ViewModel: day selection, markers per kind, "+N more", suggestions never counted as plans or to-dos.
- [x] Single-app icon rule; Google Maps addable to the Maps bucket (Robolectric compose).
- [x] Roborazzi baselines at 384 x 832 and 384 x 636, font scale 1.0 and 1.3, looked at: no scrolling,
      no clipping, three agenda rows at the tightest case. `app/src/test/snapshots/home-calendar-*.png`.
- [ ] **On the phone:** every tap in the list below, at the system font scale Kevin actually uses.
      Cold start lands on this screen; prev/next month; tap a day; tap each kind of row; "+N more";
      tick and untick in the To-dos sheet and see the grid and agenda follow; open a list from the
      Lists sheet; add a suggestion to plans and drop one from the Ideas sheet; long-press Maps, pick
      Google Maps, tap Maps and choose each entry; a one-app bucket shows its real icon; the status
      line and talk bar still sit above and below; Back from the day view returns here.
- [ ] **Deferred to [[08-ship-pass]]:** the above, since it needs the device.
