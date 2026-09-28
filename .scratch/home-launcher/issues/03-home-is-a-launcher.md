---
map: home-launcher
ticket: "03"
title: "HOME is a launcher; CALENDAR is its own route again; the meter bands retire"
type: build
status: open
status-detail: >
  Opened 2026-09-27 from ticket 01's resolution. Needs 02's theme, font and
  icons. Runs in parallel with 04 (disjoint files).
blockers: ["02"]
blocked-by: ["[[02-soft-theme-and-chrome]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# HOME is a launcher; CALENDAR is its own route again

Ticket 01 has the decisions; the prototype's `Main.dc.html` in `research/prototype-canvas/` is the
picture. This ticket makes it real on a 384dp phone.

## Routes

- `LegionRoute.HOME` stays `"home"`, the start destination, and now renders the new `HomeScreen`.
- **`LegionRoute.CALENDAR = "calendar"` returns** and renders `CalendarScreen` (month grid + day
  agenda, unchanged look, mission control). Give it `DeckScreenHeader("Calendar", onBack)` at the
  top like every other drill-down.
- `LEGACY_DEEP_LINK_ROUTES`: drop `"calendar"` (it is a live route again); `"notes"` now maps to
  `CALENDAR` (reminders live there); `"today"` and `"meters"` stay mapped to `HOME`. Update
  `LegionRouteTest` and any doc comment that says otherwise.
- **The reminder deep link must still open the reminder.** `ReminderAlarmReceiver` posts
  `EXTRA_ROUTE` plus `EXTRA_OPEN_ITEM_ID`; `openItemId` currently feeds `CalendarScreen` on HOME. Post
  `CALENDAR` from now on, and in `MainActivity`, a present `openItemId` navigates to `CALENDAR`
  whatever route the intent names - so a notification posted by an older build ("home" or
  "calendar") still lands on the editor. Test it (pure function over route + item id is fine).
- `StatusLine`'s alarm tap goes to `CALENDAR`. Grep every other `LegionRoute.HOME` navigation and
  repoint the ones that meant "the day view" (the widget pager's `legacyRouteForAspect` for notes,
  for one). List each call site and your call in the report.

## LEGION is the phone's home app now (ADR 0050, landed on dev the same day)

Merged into this branch before this ticket starts. What it binds here:

- **HOME must never crash.** A crash now leaves the phone with no home screen until Android
  restarts LEGION. Every reader behind a tile or the today card is wrapped so a failure becomes that
  tile's sentence ("Couldn't read ..."), never an exception. A test feeds each reader a throwing
  fake and asserts the state still builds.
- A Home press already pops to `LegionRoute.HOME` (`homePressNonce` in `MainActivity`), and
  `BackHandler(enabled = isDefaultHome)` sits in the HOME destination. Keep both when the HOME
  composable changes to `HomeScreen`.
- The app drawer (`APPS`) and Quiet are in the status line on every screen; HOME does not repeat
  them.

## `CalendarScreen` loses the meter bands

Delete the `metersState` `LaunchedEffect`, the `HomeMeterBands(...)` call and the `onOpen*`
parameters that existed only for it. Delete `ui/HomeMeterBands.kt` and `MetersUiState`. Keep
every pure function the tiles below still need (`buildMeterBreaches`, `moneyUncategorizedSentence`,
`buildIntakeTile`, `buildFleetTile`, `weatherLine`, ...) and their tests.

## `ui/home/`

- `HomeUiState` + `HomeViewModel` (`AndroidViewModel`; there is no Hilt yet - see CLAUDE.md sec 8's
  migration order; one `StateFlow<HomeUiState>`, `refresh()` called on `ON_RESUME`, collected with
  `collectAsStateWithLifecycle`). Reads go through the same controllers the meter bands used; no
  new DAO queries unless a reading genuinely has no controller.
- `HomeTileReadings.kt`: every status string below as a pure function, unit-tested.
- `HomeScreen(onOpen...)` = the stateful wrapper; `HomeContent(state, callbacks)` = stateless, so
  Roborazzi can render it with fakes. Wrapped in `SoftTheme`, background `ground`.

### The today card (tap anywhere: `CALENDAR`)

- Weekday (`bodySmall` `text2`) over the date, e.g. "September 27" (`headlineSmall`).
- Top right: weather from `WeatherController` as "72°" + description, with a sun/cloud/rain icon
  picked from the description, and an air-quality line from the `AreaCard` readers (`areaLine`,
  `aqiLine`, `locationFailureMessage`) - fold them in, then delete the `AreaCard` composable if it
  has no caller left. **Unreadable is never rendered as empty**: no weather reads "Weather
  unavailable", a location failure reads its own message. Never append "drive safe" - the assistant
  is a concierge and the user is not assumed to be in a car (CLAUDE.md sec 1).
- "Next: ..." - the next EVENT today still ahead, else the next open TASK or reminder due later
  today ("... due 11:59 PM"), else "Nothing else on the calendar today". Built from the same reads
  the calendar's day view uses (`activeByKindInLocalWindow`, `NotesController.allItems`,
  `buildInboxRows`) so the card and the calendar can never disagree.
- Chips: "N due today" (open tasks + open one-off reminders due today) and, only when non-zero,
  "N overdue" (`alertContainer`) for open ones dated before today, looking back 30 days. **A failed
  calendar read shows a "Couldn't read the calendar" chip in `caution`, never "Nothing due".**

### The 2 x 4 grid

Order: Calendar, Lists, Money, Body, Fleet, Recordings, News, Reports. Each tile `card`,
`shapes.large`. **Tile layout, computed to fit:** a header row of the area's icon chip (32dp,
`AreaAccent`) and the title (`titleMedium`), then the status (`bodySmall`, `text2`) below at full
tile width, up to 2 lines. Recordings puts its record button on the status row's right edge.

| Tile | Opens | Status |
|---|---|---|
| Calendar | `CALENDAR` | same due count as the card |
| Lists | `CHECKLISTS` | "N lists" / "1 list" / "No lists yet" |
| Money | `MONEY` | Over budget: "Over by $X" in `onAlert`. Else groceries over: "Groceries over by $X". Else "$spent of $target", or "$spent this month" with no budget, or "No spending yet" |
| Body | `BODY` | Logged: "1,450 of 2,200 kcal". Not logged: "Nothing logged today". No target: "No calorie target" |
| Fleet | `FLEET` | Overdue: "N overdue" in `onAlert`. Else the next item, from `buildFleetTile`'s caption. No schedule: "No maintenance schedule". All unknown: "Mileage unknown" |
| Recordings | `SETTINGS_VOICE_NOTES` | "N saved"; while recording "Recording" in `onAlert` |
| News | `NEWS` | "Feeds and newsletters" |
| Reports | `ASK` | "Spending and groceries" |

**Trust disclosures move with their figure, in words, never truncated** (CLAUDE.md sec 4 rules 5
and 7; memory: trust disclosures are not furniture). Whatever the retired meter band printed next to
a figure the tile now shows, the tile prints too, as a second line in `caution`:
- Body, when the gap's tier is `REPORTED`: "estimated, not measured".
- Money: `BudgetVsActual.spentCents` EXCLUDES uncategorized spend (`LedgerBudget.kt`), so when
  `uncategorized.spentCents > 0` the tile says so ("+ $X uncategorized, not counted"), and when
  `hasProvisionalRows` or any figure shown contains an `UNRECONCILED` row, it says "unverified".
  Read `LedgerBudget.kt` and the retired band before wording these; match what the band said.
- Groceries line, when its `tierNote` is non-null: that note.

**Record button** (48dp touch, a `recordDot` dot when idle, a stop square on
`recordingContainer` while recording): `VoiceNoteController.start(context, VoiceNoteKind.SOLO)` and
`stop(context)`, the same calls `HomeMeterBands` made. A `Refused` start shows its reason on the
tile, in `caution`, where the tap happened - never a toast. Recording state from
`VoiceNoteController.recordingState(context)`.

**A breach is a tile status, not a pane.** `buildMeterBreaches` still decides what breaches.

### Fit, and the fallback

`HomeContent` must not scroll or clip in the A25's content box. The A25 is 384 x 832dp; minus the
system bars, the status line and the talk bar, HOME gets about **384 x 636dp**. Test it at that
size. Rows share the grid height (`weight`). If the space is too small for the tiles' minimum
height (a small phone, a big font scale), fall back to a vertical scroll rather than clipping, and
test that at **360 x 520dp**.

### Now playing

When music is playing, a soft row sits above the talk bar (title, artist, play/pause, tap opens
`SETTINGS_SPOTIFY_MEDIA`), reading and calling the same `NowPlayingController` the old
`MediaMiniBar` did. Nothing playing: no row. If `MediaMiniBar` has no caller left, delete it.

## Nothing HOME reached may become unreachable (ADR 0035)

Check each, and put the list in the report: Body, Money, **Pantry** (HOME's Groceries row was its
only entry - `LedgerScreen` has no link to `MONEY_PANTRY` as of 2026-09-27, grep-checked, so add
one: an `onOpenPantry` callback and a "Groceries and receipts" row near the top of Money, in that
screen's existing style), Fleet, Checklists, Recordings (list + one-tap record), Ask, News, the
media panel, weather, air quality.

## Verification - every step accounted for

Same rules as 02: `./gradlew` from Bash in this worktree, totals from the JUnit XML.

1. `compileDebugKotlin -Pnokey` green; `testDebugUnitTest` green with the XML count.
2. Unit tests for every function in `HomeTileReadings.kt`, including: over budget, groceries over,
   uncategorized present, provisional present, estimated body, not logged, overdue maintenance,
   calendar read failure, weather null, location refused.
3. Deep-link tests: `"home"` + item id, `"calendar"` + item id, `"notes"`, `"today"`, `"meters"`.
4. Roborazzi `HomeContent` at 384 x 636dp: normal; alerts (over budget + overdue + recording +
   estimated body); failures (calendar unreadable + weather unavailable + location refused); and
   the 360 x 520dp fallback. Paths in the report; **do not delete them.**
5. detekt, `docs_check.py`, `voice_guide.py` clean. No file over the 1000-line hook.
6. One commit for this ticket; ticket to `status: built`.

Owed after the build, to ticket 05: the real phone - fit, every tile, record start/stop, a real
reminder notification tap.
