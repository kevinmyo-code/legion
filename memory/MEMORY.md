# MEMORY.md

Dashboard for LEGION. **MEMORY.md wins for state, CLAUDE.md wins for rules.** Depth lives in the
library. Under 80 lines. MIDNIGHT_AI: see CLAUDE.md §1.

## Status as of 2026-08-15 (session 8)

- **THE PHONE CHANGED. It is a Samsung Galaxy A25 (`SM-A256U`), Android 16 / SDK 36.** Migrated
  2026-08-15; Kevin: "a25 is the real phone now". The **OPPO A17k (`CPH2471`) is RETIRED** - it still
  holds a full copy of the database as a fallback, so do not wipe it, but **never write to it.** Both
  phones were identical at the moment of migration and **`sync/` has still never executed**, so
  anything written to the A17k diverges silently and nothing reconciles it.
  - **Migration verified row-for-row**: 5 vehicles / 54 maintenance items / 2 service records /
    18,645 obd_samples / 148 ledger rows / 188 ingested_files, and **totals identical to the cent on both
    sides.** WAL was checkpointed into the main file before the copy and the target's stale
    `-wal`/`-shm` deleted, so no mismatched journal could replay.
  - **The Gemini key did NOT come across** - it is sealed by the A17k's hardware Keystore, which is
    device-bound by design. Drive authorisation and runtime permissions (mic, calendar) also need
    re-granting.
- **Two device facts that invalidate prior assumptions, both measured:**
  - **384 x 832 dp, not 360 x 806.** Every layout figure in `.scratch/mission-control/` was measured
    against the A17k - the 560dp content budget, the 328/159dp tiles, the 7-character hero. Not
    wrong, **unverified at this size.**
  - **Animation scales are 1.0, not 0.0.** The A17k froze every infinite animation, so the entire
    mission-control motion vocabulary was dormant. **That motion has never been observed by anyone,
    on any device, and it is now running.** Treat as untested, not as shipped-and-fine.
- **SIX domains: fleet, ledger, pantry, body, notes/lists/calendar, plus goals/advisors.** Tabs:
  Today, Money, Body, Fleet, Notes, Setup. **1365 unit tests green** (2026-08-16).
- **Room is v22.** v21->v22 landed 2026-08-16: `code_clear_events` (clear-DTC), additive, SQL
  verified byte-identical to the generated schema AND applied to a pulled copy of the real device
  DB (47->48 tables, zero DDL changes, zero row drift, integrity clean). **v20->v21 predates this
  session and is unaccounted for here** - read `app/schemas/` rather than trusting this line.
- Earlier: v19->v20 landed 2026-08-15 (fleet-maintenance): `intervalSource` + `deleted` on
  `maintenance_items`, `engine` on `vehicles`, and `cost` REAL -> `costCents` INTEGER on
  `service_records`. That last one is **non-additive**, the map's single stated exception to §5,
  permitted only because the column was **proven empty first** (0 of 2 rows). Proven against a COPY
  of Kevin's real data, then verified on-device.
- Branch **`feat/mission-control`** holds this session's work. `dev`/`main` far behind. CLAUDE.md §8:
  Claude never pushes `main`, never opens or merges that PR.

## Blocking

- **NOTIFICATION-LISTENER ACCESS IS NOT GRANTED ON THE A25.** Measured 2026-08-16
  (`adb shell settings get secure enabled_notification_listeners`): `com.kevin.legion` is absent
  while four other apps hold it. Per-device special access, so the migration dropped it. This is
  why pause/skip did nothing while play worked. **Kevin must grant it** (Settings > Apps > Special
  app access > Notification access), or one line:
  `adb shell cmd notification allow_listener com.kevin.legion/com.kevin.legion.service.MediaNotificationListener`.
  Until then only Spotify transport works, via the App Remote fallback added 2026-08-16.
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

**HANDS AND SENSES TRIAGED HARD, 2026-08-16 (session 9).** Map `.scratch/hands-and-senses/`.
Seven of its nine ticket-sized items are now closed, and **only ticket 01 produced code**.
- **BUILT AND COMMITTED: clear DTCs** (`bd4de4b`), the app's first WRITE to the car. Transaction,
  not a send - snapshot, Mode 04, re-read, and **only the re-read may be spoken**. Five outcomes,
  `44` ack diagnostic only, new `code_clear_events` table. Two senior-review defects fixed before
  commit: a dismissable dialog could cancel a write mid-send leaving a real clear with no record,
  and D7's union rule short-circuited so a RETURNED code was invisible on screen while the voice
  called it active. **NEVER RUN ON A CAR.** `UNVERIFIED`/`REFUSED` have never been produced on
  hardware; the migration test compiles and has deliberately never run.
- **ALSO COMMITTED: the music fix** (`d683d2c` + `ccef947`), found by Kevin in use. See Blocking.
- **Closed on premise, not merits:** 12 identity and 13 voice/persona (**already built** - the
  register lives in `ai/Personas.kt`, ALFRED + DOROTHY, and CLAUDE.md was corrected); 11 Health
  Connect (**archived**, no wearable); 09 Gmail auto-pull (**killed**, statements never land in
  Gmail); 04 notification listener (**archived**).
- **Still live:** 05 comms (in progress, paused), 08 morning brief, 18 inbox, 19 people dates.
- **THE PATTERN, five tickets running:** the map was charted from a competitive-landscape
  brainstorm, so it describes what a JARVIS COULD do rather than what Kevin's data looks like.
  **Grep the premise and confirm the data source before spending a session on any ticket.**
- Findings kept from dead tickets: LEGION **already holds** notification-read access via an empty
  `MediaNotificationListener`; there are **three proactive gates, not one** (`AmbientListener` and
  `TelephonyController` bypass `ProactiveGate`), so the settled master kill switch cannot be
  honoured yet; **78 tool declarations** today; and the map's own "LEGION almost only reads"
  framing is false, since `AmbientListener` ships.

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

**HANDS AND SENSES charted 2026-08-16.** Map `.scratch/hands-and-senses/`, **21 tickets**, from a
competitive-landscape brainstorm (`.scratch/competitive-landscape/research/landscape.md`). Theme:
**LEGION almost only READS; this map gives it hands and new senses.** Destination is DECISIONS.
- **IT IS A SURVEY, NOT A MAP - Kevin caught it the same night.** **Six tickets are efforts in
  disguise** and must each chart their own map before being resolved: home control, wrench mode,
  location intelligence, document vault, memory decay, proactive mode. The map's "Efforts in
  disguise" table names them, why, and the slug. Nine remain genuinely ticket-sized; morning brief
  is borderline. **Do not try to resolve one of the six in a session.**
- **All 4 research resolved same day**, filed to `library/decisions.md`: HA needs only REST (tokens
  are unscoped - use a non-admin HA user); Health Connect sideloads fine but **sync freshness is
  undocumented**; Gemini Live takes **camera frames on a plain key** at 1 fps (2-min/10-min session
  caps make compression + resumption mandatory); the vault needs **no RAG** (0.48 USD/month
  whole-document; context caching is a 15x trap; **free API tier is disqualifying** for private
  docs); **TomTom is the only no-card traffic vendor** and Google retired its 200 USD credit.
- **Three charting corrections, all from grepping**: calendar/Gmail tools, the NHTSA recall checker,
  the `advisor/`+`goals/`+body layers, and the **companion memory system all ALREADY EXIST**.
  Memory's consolidation/reflection ported; **its FORGETTING never did** (nothing consumes
  `lastAccessedAt`, no scorer, no pruning, plus a legacy `MemoryEntry` table). Ticket 20 owns it.
- **Settled by Kevin:** proactivity = master switch + five categories (Safety, Timing, Wellbeing,
  Fleet, Digest), two states each, **master is a true kill switch, nothing exempt**. HA fronts home
  control (never per-device integrations). Glasses are a peripheral, phone stays the brain.
  **People-lookup/OSINT is OUT.** Money is never written to.
- **`is_area_safe` must never ship** - FBI crime data is agency-level and ~13 months stale.

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
