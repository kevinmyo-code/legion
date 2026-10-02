---
map: home-launcher
ticket: "07"
title: "Settings menu, letter folders in Apps, and category buttons under the dock"
type: build
status: built
status-detail: >
  Built 2026-10-02 on feat/home-tray: SettingsGearMenu (ui/common) behind
  StatusLine's new onOpenPhoneSettings; letterFolders() in ui/apps/AppDrawer.kt
  with AppFolders (folder grid, dialog, flat search grid) in AppsScreen;
  CategoryPicks/CategoryPicksStore (ui/apps) plus CategoryRow and
  CategorySheets (ui/home) as a new row under the dock. Suite green (3821
  tests, 0 failures, from the JUnit XML), detekt: nothing on any line this
  ticket wrote (new composables baselined, as the repo does). Screenshots
  recorded and looked at: HOME with a full dock and a mixed category row, the
  worst-case tile state still shows every trust disclosure, folders, an open
  folder, search, the dropdown's rows, both sheets, the 360x520 fallback. To
  fit, the today card/tile padding tighten, tile rows are weighted, and HOME
  now scrolls while music is playing (the now-playing row does not fit above
  620dp+64dp). Owed on the phone (ticket 05): Phone settings opens Android
  Settings and where the real popup lands; Mail with Gmail and Outlook (work)
  asks which; a one-app category opens directly; an unset one opens the
  chooser; letter folders open and launch; a work app launches.
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Settings menu, letter folders in Apps, and category buttons under the dock

**Kevin, 2026-10-02:** *"currently top right has settings icon but that opens the app's settings. i
want it to open a modal or a dropdown where i can choose the phone's settings or the app settings.
and the app tray i want it to be organized in folders of alphabets. we should have native button
(like not custom pinned app buttons) for things like banking, music, navigation, email etc."*

Asked what a category button opens: *"you pick each app. but if theres multiple, like gmail and
outlook, i should be able to choose multiple too."* Shown three clickable trays
(`research/tray-canvas/`, live canvas `https://claude.ai/artifact/ABGpwwEBbJRwE4hx9qXLRv`), he
picked **A, one folder per letter**. The five categories *"work for now"*. The pinned dock
*"stay[s]. maybe on top of the category buttons as a new row."*

The prototype `research/tray-canvas/Main.dc.html` is the reference for layout and behaviour. The
other two boards are the rejected options, kept for the record.

## 1. Settings menu on the gear

- The header gear (`StatusLine`'s `onOpenSettings`) opens a small anchored dropdown
  (`DropdownMenu`), not a route. Two items, each an icon plus title plus one-line subtitle:
  - **Phone settings** - "Wi-Fi, display, battery". Starts `Settings.ACTION_SETTINGS` with
    `FLAG_ACTIVITY_NEW_TASK`. A failed start says so in words (snackbar or the same message
    surface the screen already has), never a dead tap.
  - **LEGION settings** - "Assistant, connections, privacy". Navigates to `LegionRoute.SETTINGS`
    exactly as the gear does today.
- The gear stays the single way into LEGION settings from the header (one-home ruling: "keep the
  top right corner one"). `AssistantStrip`'s own settings entry is untouched.
- Absent on driving mode, as the gear already is.

## 2. Apps: one folder per letter

- `AppsScreen` with an empty search shows a **4-column grid of letter folders**, one per letter
  that has at least one app. Each folder: a rounded tile holding up to four of its apps' icons in a
  2 x 2 mini grid, with the letter and its count under it.
- Labels that do not start with A-Z (digits, symbols, non-Latin) go in one **`#`** folder, sorted
  last. Letter is the first character of the label, uppercased with `Locale.ROOT`; accented Latin
  letters fold to their base letter (`É` goes in `E`).
- Tap a folder: a dialog over a scrim, letter large, "N apps", the apps in a 3-column icon grid.
  Tap an app launches it through `launchDrawerApp` (same call, same failure sentence). Long-press
  gives the existing pin menu. Tap the scrim or back closes the dialog.
- **Search non-empty:** folders are replaced by a flat 4-column icon grid of `filterDrawer`
  results, with the existing "No app matches" sentence.
- Work apps carry **WORK** under the label in words, in the folder dialog and in search, exactly as
  the list rows do today. The pause-work-apps control and the "Couldn't read the installed apps"
  sentence stay.
- Grouping is a pure function next to `filterDrawer` in `ui/apps/AppDrawer.kt`
  (e.g. `letterFolders(apps): List<LetterFolder>`), unit-tested.

## 3. Category buttons: a new row under the dock

- HOME's bottom becomes two rows above the talk bar: the **pinned dock (ticket 06, unchanged)**,
  then a **row of five category buttons**: Bank (`account_balance`), Music (`music_note`), Maps
  (`navigation`), Mail (`mail`), Chat (`chat`). Round, icon plus short label. The five are a fixed
  list for now; making it user-editable is not this ticket.
- Each category holds an **ordered list of one or more apps the user picked**, stored like
  `DockPins`: device-local `SharedPreferences`, `(packageName, userSerial)` per app, never Room,
  never synced. Pure logic in its own object, tested over an in-memory list.
- **Tap:**
  - none picked: open the chooser straight away;
  - one picked: launch it via `launchDrawerApp`;
  - several picked: a bottom sheet titled with the category, "Open with", one row per app (icon,
    label, WORK in words), tap launches; a **Choose apps** button at the bottom.
- **Long-press** a category button: the chooser directly.
- **Chooser:** bottom sheet, "Apps for <Category>", the line "Pick one or more. One app opens
  straight away; more than one asks which.", a search field, every drawer app as a checkbox row,
  Cancel and Save. Several can be ticked. Saving none is allowed and returns the button to unset.
- **Button states in words or shape, not colour alone:** unset is an outlined button whose
  accessibility label says "not set up"; several apps show a count badge.
- A picked app that is no longer installed or whose profile is paused stays listed, dimmed,
  "Not installed" / "Paused", and a tap says so. Same posture as the dock.
- Reads apps from the `AppDrawerCache` snapshot; no second `LauncherApps` query.

## 4. Fit on the A25

Two rows now share HOME's bottom with the 2 x 4 tile grid and the today card, and HOME must not
scroll (ticket 01). At 384 x 832 dp (`ScreenshotDeviceConfig`) everything stays visible and
unclipped; tighten tile padding or the today card before anything scrolls. Check the recorded
screenshot, not the layout code.

## Verification

1. Unit tests: letter grouping (A-Z, `#`, accents, work twins, empty list), category picks
   (set none / one / several, reorder-free toggle, parse/format round-trip, an uninstalled pick).
2. Roborazzi screenshots recorded **and looked at**: HOME with a full dock and the category row
   (mixed: unset, one, several), the Apps letter-folder grid, an open folder, the settings
   dropdown, the chooser sheet. Plus the existing 360 x 520 fallback for HOME.
3. `compileDebugKotlin -Pnokey`, detekt against its baseline, `testDebugUnitTest` green with totals
   from the JUnit XML.
4. **Owed on the phone (ticket 05's pass):** Phone settings opens Android Settings; Mail with Gmail
   and Outlook (work) asks which and opens each; a single-app category opens directly; an unset one
   opens the chooser; letter folders open and launch; a work app launches.
