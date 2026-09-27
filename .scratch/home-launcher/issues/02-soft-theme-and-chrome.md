---
map: home-launcher
ticket: "02"
title: "Soft theme, bundled font and icons, and the shell chrome restyled"
type: build
status: open
status-detail: >
  Opened 2026-09-27 from ticket 01's resolution. Foundation for 03 and 04:
  the soft Material tokens, Figtree, Material Symbols Rounded, and the
  status line and talk bar restyled; the mission-control bezel goes.
blockers: ["01"]
blocked-by: ["[[01-the-shape]]"]
open-blockers: 0
ready: true
tags: [ticket]
---

# Soft theme, bundled font and icons, and the shell chrome restyled

The foundation 03 and 04 build on. Visual source of truth: the prototype canvas in
`research/prototype-canvas/` (any `.dc.html` there; the colours and sizes below were lifted from it).
The prototype was drawn at 412dp wide; **the A25 is 384dp** (`ScreenshotDeviceConfig`), so fit to
384.

## 1. Assets (bundled, never fetched at runtime - CLAUDE.md sec 7)

**Font: Figtree, static TTFs** (minSdk is 24; variable-font axes need 26). Download to
`app/src/main/res/font/`:

| File | Source |
|---|---|
| `figtree_regular.ttf` | `https://raw.githubusercontent.com/erikdkennedy/figtree/master/fonts/ttf/Figtree-Regular.ttf` |
| `figtree_medium.ttf` | `.../fonts/ttf/Figtree-Medium.ttf` |
| `figtree_semibold.ttf` | `.../fonts/ttf/Figtree-SemiBold.ttf` |
| `figtree_bold.ttf` | `.../fonts/ttf/Figtree-Bold.ttf` |

License: `https://raw.githubusercontent.com/erikdkennedy/figtree/master/OFL.txt` to
`app/src/main/assets/licenses/figtree-OFL.txt`. All five URLs returned 200 on 2026-09-27.

**Icons: Material Symbols Rounded as vector drawables** (Apache 2.0). Pattern, verified 200:
`https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/<name>/materialsymbolsrounded/<name>_24px.xml`
saved as `app/src/main/res/drawable/ms_<name>.xml`. **Delete the
`android:tint="?attr/colorControlNormal"` line from each file** - the tint comes from Compose, and a
theme-attribute reference in a vector parsed outside a View theme is a crash risk. Vendor this whole
set now so 03 and 04 never fetch mid-build:

`settings mic mic_off calendar_month checklist account_balance_wallet monitor_heart directions_car
graphic_eq newspaper bar_chart sunny partly_cloudy_day rainy schedule arrow_back more_vert add check
expand_more cloud_off repeat shopping_cart task_alt luggage wb_twilight bedtime fitness_center home
medication menu_book work school edit delete archive unarchive history keyboard_arrow_up
keyboard_arrow_down stop air warning close`

If a name 404s, pick the nearest Material Symbols name and say which in the report.

## 2. Tokens - `ui/theme/soft/`

`SoftColors.kt`, exact values:

| Token | Hex | Use |
|---|---|---|
| ground | `#121317` | screen background |
| barLow | `#16181D` | talk bar surface |
| barRule | `#23262D` | hairline above the talk bar |
| card | `#1C1E24` | tiles, cards |
| cardHigh | `#24272E` | chips, inputs |
| cardHighest | `#2A2D34` | menus, progress tracks |
| outline | `#3A3E47` | dashed "new" card, input borders |
| text | `#E6E7EC` | primary text |
| text2 | `#A9ADB8` | secondary text, statuses |
| text3 | `#8A8F9B` | tertiary, ticked text, placeholders |
| primary | `#FFB4A1` | accents |
| primaryContainer | `#733423` | talk pill, FAB, primary buttons |
| onPrimaryContainer | `#FFDBD1` | text on it |
| alertContainer | `#4A1F24` | over budget, overdue, destructive |
| onAlert | `#FFB4AB` | text on it; alert text on card |
| caution | `#FFC857` | estimates, refusals, not synced, mic blocked |
| good | `#7EDBA5` | synced dot |
| recordDot | `#FF6B5E` | record button dot |
| recordingContainer | `#8C1D18` | record button while recording |
| tickedBox | `#5E636E` | ticked checkbox fill |

`AreaAccent.kt`: `enum class AreaAccent(val container: Color, val onContainer: Color)` -
`CALENDAR #1F3450/#A8C8FF, LISTS #43340F/#FFD36B, MONEY #173D2B/#7EDBA5, BODY #4A1F2C/#FFB1C3,
FLEET #0F3B40/#77DCE5, RECORDINGS #33265A/#CDBDFF, NEWS #4A2A12/#FFB77C, REPORTS #262E57/#B9C3FF`.
Ticket 04 reuses these eight pairs as the list palette.

`SoftType.kt`: `FontFamily` over the four Figtree files; a `Typography` with headlineSmall 26/bold,
titleLarge 22/semibold, titleMedium 16/semibold, titleSmall 14/semibold, bodyLarge 16/regular,
bodyMedium 14/regular, bodySmall 13/regular, labelLarge 15/semibold, labelMedium 13/semibold,
labelSmall 12/medium.

`SoftTheme.kt`: `@Composable fun SoftTheme(content: @Composable () -> Unit)` = `MaterialTheme`
with a `darkColorScheme` built from the tokens, `SoftType`, and shapes small 10 / medium 16 /
large 20 / extraLarge 24 dp.

**The L11 trap, closed by a test and not by care.** M3's `contentColorFor` resolves by VALUE; on
2026-08-02 `surface` and `errorContainer` shared a value and every body text drew in quarantine red.
So: every background/surface/container slot in the scheme is pairwise distinct (`surfaceVariant`
must NOT equal `surfaceContainerHigh`, `background` must not equal a container, and so on), and
`SoftThemeTest` (plain JVM) asserts it, plus WCAG contrast >= 4.5:1 for each on-colour against its
container and for `text`, `text2`, `text3`, `caution`, `onAlert` against `card`. Compute relative
luminance in the test; no library.

Also: `@Composable fun MsIcon(@DrawableRes res: Int, contentDescription: String?, tint: Color,
modifier: Modifier = Modifier, size: Dp = 24.dp)` - one helper over `painterResource`, so 03 and 04
do not each write their own.

## 3. The shell chrome

Wrap only the chrome in `SoftTheme`; every screen keeps `LegionTheme` until its own ticket.

**`StatusLine`** (`ui/common/DeckPanels.kt`; move it to its own file if that file is over the
1000-line hook). Same information, new presentation, **states in words, never colour alone**:

- Left: an 8dp dot (`good` when sync is on, `text3` when off) and "Synced" / "Sync off"; then the
  OBD state in words ("OBD linked", "OBD off", ...); then the key state in words if the current
  line shows it. Restructure `ShellStatusLineParts`/`formatShellStatusLine` to carry what the new
  row needs; keep or update their tests.
- Alarms: when `alarmCount > 0`, a pill (`alertContainer`/`onAlert`, `labelMedium`) reading
  "1 alarm" / "N alarms", tappable via `onOpenAlarm`. The key segment still survives beside it
  (ticket 04 answer sec 3 of cyberdeck-ui, kept).
- Right: the clock (`labelLarge`, tabular figures) and a settings `IconButton` (48dp touch,
  `ms_settings`, contentDescription "Settings") replacing the SETUP stamp. It stays the only way
  into settings.
- Height about 40dp, background `ground`. The blinking cursor goes; `cursorSolid` and the
  `fleetSweepActive` plumbing that feeds only it can go too if nothing else reads them.

**`AssistantStrip`** (`ui/assistant/AssistantStrip.kt`). `AssistantStripResolver` is untouched; only
`AssistantStripContent` and `AssistantOffRow` change:

- Surface `barLow` with a 1dp `barRule` hairline on top; inner padding 16 horizontal, 8-10 vertical.
- A full-width pill: height 52dp, fully rounded, `primaryContainer`, `ms_mic` + `state.label` in
  `labelLarge` `onPrimaryContainer`, centred. Active phases (listening/speaking) pulse the icon's
  alpha - keep the existing infinite transition.
- `micBlocked` or `silenced`: pill turns caution-toned (container `#3A2E12`, content `caution`),
  `ms_mic_off`. Colour is not the only signal: the label already says it.
- `state.subtitle`: `bodySmall` `text2` under the pill, max 2 lines.
- `AssistantOffRow`: the same pill shape, outlined (1dp `outline`, transparent), `text2`: "Assistant
  off. Tap to turn it on in Settings."
- **Never a persona name in this copy** (CLAUDE.md sec 1).

**`DeckBezel`**: remove it from `MainActivity`'s shell (the `Scaffold` keeps `fillMaxSize()`). If
`DeckBezel` then has no caller in `app/src`, delete it. Fix any `docs/` path that named it
(`python tools/docs_check.py` will say).

## 4. Out of scope

HOME itself (03), the lists (04), and every other screen's content. Do not restyle a mission-control
screen here.

## Verification - every step accounted for (CLAUDE.md sec 8, L11)

Run everything in THIS worktree with `./gradlew` from Bash, never the Gradle MCP (it builds the main
tree and reports a pass for code you did not build). Read totals from
`app/build/test-results/testDebugUnitTest/*.xml`, never the console.

1. `./gradlew compileDebugKotlin -Pnokey` green.
2. `./gradlew testDebugUnitTest` green; totals from the XML, compared against the count before your
   change.
3. `SoftThemeTest` passes and fails if you set `surfaceVariant = surfaceContainerHigh` (check once,
   revert).
4. Roborazzi screenshots at `ScreenshotDeviceConfig.QUALIFIERS`: status line (normal; 2 alarms +
   key; sync off) and talk bar (idle; listening; mic blocked; assistant off). Record them; list the
   PNG paths in the report. **Do not delete them.**
5. detekt against its baseline (`./gradlew detekt` or the task the `verify` skill names) - no new
   findings.
6. `python tools/docs_check.py` clean.
7. One commit for this ticket on `feat/home-redesign`, message in the repo's style (a sentence that
   says what changed for Kevin). Stage your own paths; commit as a bare `git commit` so the wiki hook
   fires. Set this ticket to `status: built` with a `status-detail` naming the commit.

Report ends with the assumptions ledger (`built` / `tested` / `traced` / `reasoned` /
`on-device`).
