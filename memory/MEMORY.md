# MEMORY.md

Dashboard for LEGION. **MEMORY.md wins for state, CLAUDE.md wins for rules.** Depth lives in the
library. Under 80 lines. MIDNIGHT_AI: see CLAUDE.md §1.

## Status as of 2026-08-13 (session 7)

- **SIX domains: fleet, ledger, pantry, body, notes/lists/calendar, plus goals/advisors.** Tabs:
  Today, Money, Body, Fleet, Notes, Setup. **955 unit tests green.**
- **Room is v19.** Three data-only bumps on 2026-08-13 (16->17, 17->18, 18->19), each proven against
  a COPY of Kevin's real data before touching the device, then verified on-device: v19, integrity
  ok, 497 rows and 168,422 cents unchanged throughout.
- **Device: OPPO A17k (`CPH2471`), wireless ADB working.** Not a OnePlus; an old note was wrong.
- Branch **`feat/car-probe`** holds tonight's work. `dev`/`main` are far behind - **204 commits ahead
  of `origin/main`.** CLAUDE.md §8: Claude never pushes `main`, never opens or merges that PR.

## Blocking

- **Onboarding has no screen. Firebase not wired**, so a swallowed exception is invisible.
  **Crisis resource is US-only (988).**
- Google console work still needing Kevin: `.scratch/google-account-integration/` tickets 11
  (publish consent screen) and 09 (add the Gmail scope). Drive OAuth itself CLEARED 2026-08-13.
- **Ticket 07 on `.scratch/android-auto/` needs Kevin**: settled decision 1 was taken on a premise
  since falsified, so "two surfaces, deliberately" has to be re-taken.

## Untested / unverified

- **NOTHING on the Android Auto surface has touched a head unit.** APK installed and hash-verified;
  never plugged in.
- **NO ALARM HAS EVER FIRED.** **`sync/` has never executed.** **OBD, wake word, proactives never
  run**; wake word CANNOT (`assets/vosk-model/` is a README only).
- **Compose previews have never been rendered**, any screen, ever - now including `CarProbeScreen`
  and `ExcludedOwnAccountMovementsScreen`. `assets/dtc_descriptions_seed.json` has NEVER existed.
  The 30 voice clips have never been HEARD.
- **`CarDatabaseMigration15To16/17To18/18To19Test` compile but have NEVER RUN.**
  `connectedAndroidTest` UNINSTALLS the app and would take Kevin's real data. The on-device
  migration is the stronger evidence and it WAS performed.
- **`get_monthly_spend` has never been spoken**, so the §4 rule 7 spend disclosure is verified on
  screen only, never aloud.

## In-flight

**QUANT-VIZ + GLANCEABLE, branch `feat/quant-viz` off `feat/car-probe`, 34 commits, suite green.**
Map `.scratch/quant-viz/`, 16 tickets, ALL landed and QA'd on-device with hash-verified installs.
Full account in `library/decisions.md` (2026-08-13/14) and `library/lessons.md` L19.
- **Kevin delegated the taste, then reversed my main call**: "inline viz across all tabs. im not
  gonna read numbers. it has to be glancable." **Every tab face now carries inline viz** - that
  reversal also kills cyberdeck ticket 06's chart-free Today. Treat it as standing.
- Money face: 12-month spend sparkline + daily bars. Today: intake/sleep/cumulative-spend
  sparklines. Fleet: mpg + miles captioned sparklines, due meters. Body unchanged (already wired).
  Drilldowns: category daily bars, monthly spend trend, recap trends, oil-analysis small multiples,
  pantry SPEND panel, goal meters.
- **SET TARGET affordance shipped** (ticket 09) - `set_budget` was voice-only, so no meter could
  ever fill from a screen. Groceries USD 300 written through it on the real phone; meter 69% with
  the pace tick at day 14/31, hand-checked.
- **LOG tab: month calendar** (dots for density, today filled, HIDE collapses) and **tapping a day
  pops an AlertDialog of that day's entries**; `SHOW IN LIST` is now the only route to the day
  filter. Popup renders from the SAME month list that draws the dots, so they cannot disagree.
- **Still not rendered with real data** (nothing to render): pantry chart, goal meter, MISSED's
  4-row cap, the dialog's internal scroll. Verified in code only.
- Deferred nits: month-label formatting duplicated (`SpendTrendDrilldown`/`PantryRows`);
  `dueFraction` treats a month as 30 days.
- **CLAUDE.md §10 "almost all of `ui/` is clean slate" is badly stale (70+ files)** - needs a
  Kevin-visible correction.

**LEDGER: FOUR BUGS FIXED 2026-08-13, ALL FOUND BY PULLING THE DB OFF THE PHONE, NONE BY THE SUITE.**
Full account in `library/decisions.md`. All 497 rows were `Pets` (a SEEDING hole - Room builds a
fresh schema from the entity set and NEVER replays migrations, so the model was starved, not
wrong); `CHECKCARD` read as a merchant (`extractMerchantKey` split on the MMDD date, one rule then
confirmed 48 unrelated rows); transfer detection was never wired to categorisation; ~$24k of own
money counted as spend, now leaving `operating` with the exclusion disclosed in words.

**ANDROID AUTO charted and probed.** Map `.scratch/android-auto/`, 16 tickets, all 6 research
resolved the same day - read the map, not this line, before acting.
- **Settled decision 3 FALSIFIED hours after charting**: the self-managed call is NOT the only route
  to the car's HFP mic (`MODE_IN_COMMUNICATION` + `setCommunicationDevice` gets a plain foreground
  service the same mic). **The risk is DISTRIBUTION, not telephony**; two gates, sideloading and
  **category honesty** (no category fits LEGION) - the second is Kevin's judgement, not a fact.
- Two shipped defects surfaced, both `traced` not `tested`, tickets 13/14/15: **OBD reports the car
  fine when the Bluetooth link goes QUIET** (`Elm327Io` polls `available()`, never blocks on
  `read()`), and the live session could be **silenced with zeroes and no callback**.

**Still open from 2026-08-07:** `CategoryDao.insert` plus an add-category affordance. D14's fixed
list exists to stop the MODEL inventing categories, not to stop Kevin adding one.

## Notes for next session

- **Four bugs this session were found by LOOKING AT THE DATA, none by 955 tests.** Same shape as
  L15: each component individually correct, wrong in aggregate. Pull the DB and query it.
- **A decision put to Kevin twice, with numbers in between, beat the first answer.** He first chose
  to exclude everything the transfer keywords caught; measuring it first showed that also hides 40
  `Zelle payment to <person>` rows worth several thousand dollars across 40 `Zelle payment to <person>` rows of real money. He changed his answer.
- **`adb shell cat` CORRUPTS a binary pull** - use `adb exec-out`, and compare the pulled size
  against `ls -l` on the device. **Verify every install by sha256**, never by "Success".
- **Device quirks:** logcat filters the app's own logs (surface diagnostics in the UI); `adb push` to
  `/data/local/tmp` is OEM-blocked, route via `/sdcard`; no `sqlite3` on device - pull the file;
  `pm clear` OEM-blocked; unsigned `.ps1` refused; `uiautomator dump` serves STALE content.
- **Real statements: copy in, run, DELETE.** Never commit money data; fixtures are invented.

## Library + how to update this file

`memory/library/` (catalog: `INDEX.md`). Never bulk-read shelves; dispatch the librarian, then
**verify what it writes** - it has invented content before. **Most shelves are FROZEN Midnight AI
history.** LIVE: `decisions.md`, `lessons.md`, `playbook-coding.md` (partly). CLAUDE.md §11.
- Under 80 lines. One-liners; narratives go to the library, then refresh Blocking / In-flight /
  Notes. A decision changing a CLAUDE.md rule is filed to `library/decisions.md` AND applied to
  CLAUDE.md in the same commit; a lesson graduates the same way (L14 -> §4 rule 6).
