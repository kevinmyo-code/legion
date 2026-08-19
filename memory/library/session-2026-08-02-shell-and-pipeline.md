---
shelf: session-2026-08-02-shell-and-pipeline
status: live
kind: session
tags: [library]
---

# Session 2026-08-02: Shell and IngestPipeline

> **STATUS: LIVE.** Field notes from second session 2026-08-02, branch `feat/ledger-ingestion`. Hardware verification, defect findings, residual known issues.

## Context

PC restarted overnight; all Part 3 files were on disk, no loss. Two commits landed on `feat/ledger-ingestion`, neither pushed.

## Commits landed

- **498f47f** - Part 3: folder-scan ingest pipeline and LLM spend gate. Tickets 05 and 06. Splits `StatementDispatcher.dispatch` into `dispatchDeterministic` (free, no network, not suspend) and `runLlm` (paid), so LLM fallthrough count is known before any spend. New `ledger/IngestPipeline.kt` (per-file classify/stage/commit core), `service/IngestScanner.kt`, `service/ScanState.kt`. `SubAgent.askWithUsage` parses `usageMetadata`, which nothing parsed before. `SpendEstimate` deliberately carries NO price constant.
- **512823a** - Part 4: the app shell, ignition, and key entry. Ticket 07. Single-activity Compose shell, four tabs, `LegionTheme`, key screen with the free-tier disclosure, `BootReceiver` + `RECEIVE_BOOT_COMPLETED` deleted, three orphan activities absorbed, new dependency `androidx.navigation:navigation-compose:2.8.0`.

## Hardware verification

Verified on device CPH2471 / A17K, API 31. Tag: `tested` / `on-device` as noted.

### IngestPipelineReplaceFlowTest passes

`IngestPipelineReplaceFlowTest` PASSED (1 test, 0 failures). Closes ticket 04 case 7 (replace resets an overlapping file to NEW), which Part 2 left untested rather than faking. This is the second LEGION code path ever verified on hardware (after the v3->v4 migration). **Tag: tested.**

### Ticket 07 shell renders on device

Four tabs, sub-routes, tab selection all rendered and navigable on device. **Tag: on-device.**

## Three defects found and fixed in 512823a

All three defects caught by running the app on the phone (not caught by preview or compile). All fixed inside commit 512823a.

### Defect 1: Every screen's body text rendered in quarantine red

**Symptom:** First-run key screen and consent copy all rendered in red (quarantine error state color), including screens with no error state.

**Root cause, traced to source:** Dark scheme set BOTH `surface` and `errorContainer` to `InstrumentSurface`, and Material 3's `contentColorFor` is a by-value `when` chain that tests `errorContainer` BEFORE `surface`. So `Surface {}` default content colour resolved to `onErrorContainer` (the red), not the intended `onSurface`. The chain ordering in `contentColorFor` is: errorContainer first, then surface, and earlier roles win on collision.

**Fix:** `errorContainer` set to `InstrumentSurfaceSunken` (chosen by Kevin). This breaks the collision and `contentColorFor` now resolves to `onSurface` correctly.

**Residual, known, not fixed:** `errorContainer` now collides with three other roles (`secondaryContainer`, `surfaceVariant`, `surfaceContainerLow` - all `InstrumentSurfaceSunken`), so `contentColorFor(errorContainer)` returns `onSecondaryContainer` (dim ink), not red. Ticket 08's quarantine UI must set `contentColor` explicitly. **Tag: traced.**

### Defect 2: Screens wrapped in Surface instead of filling viewport

**Symptom:** Every screen's `Surface` wrapped its content instead of filling, leaving a truncated lighter band over bare black.

**Fix:** Eight bare `Surface {}` calls, all now `fillMaxSize()`.

### Defect 3: Bottom bar tab selection by exact route equality

**Symptom:** Entering ANY sub-route unlit the whole tab bar (selection test failed because the current route no longer matched the top-level route).

**Fix:** Resolved by owning tab via `LegionRoute.topLevelOf()` instead of exact route equality.

## The lesson: A written verification step was skipped

**Ticket 07 resolution included an explicit instruction:** "Render the five previews in `ui/theme/ThemePreview.kt` before building screens on the theme. It compiles and has never been drawn." This step was **SKIPPED**. The exact class of defect it was designed to catch shipped into a first-run consent screen.

**Root class:** This is an ORCHESTRATOR failure mode, not a grep-miss (L10 type) or an unverified assumption (L1 type). A written, ticket-mandated verification step was simply not performed, and no gate noticed.

**Technical rule worth graduating:** A Material 3 ColorScheme must not assign one colour value to two roles, because `contentColorFor` resolves by value and earlier roles win. This is load-bearing for theme correctness - a single colour shared across multiple roles leads to silent resolution failures. This rule should live in `playbook-coding.md` under a Material 3 theme section.

**Rule for the orchestrator (for lessons.md):** Mandated verification steps in a ticket resolution (e.g., "render the previews before building") must not be silently skipped. There is no gate to catch a step that was supposed to happen but did not. Flag explicitly if a step is deferred or impossible.

## Residual, known, not fixed

Deliberate non-fixes with verification tags.

- **Nav graph has ZERO test coverage.** Back-stack behaviour is `reasoned` only.
- **Permission chain never exercised** (POST_NOTIFICATIONS -> RECORD_AUDIO -> startForegroundService). The assistant toggle has never been flipped on a device. `reasoned`.
- **Deep-link `navigate()` in MainActivity.** No `launchSingleTop`/`popUpTo`; a voice command to a sub-route while deep in another tab pushes onto the current stack. `reasoned`.
- **Compose BOM 2024.05.00 vs navigation-compose 2.8.0 version skew.** Compiles, but Gradle's highest-wins resolution silently overrides part of the BOM pin. `reasoned` by senior-dev, not verified via `dependencies` task.
- **One device crash, UNEXPLAINED.** Process died once mid-session, tap landed on launcher underneath. Empty crash buffer, no FATAL, no AndroidRuntime:E. Did not recur across three subsequent installs.

## Operational fact: adb pairing post-reboot

**Discovery:** After PC reboot, the phone needs a **FRESH `adb pair`**, not just `adb connect` - surviving pairing record is not the same as a live transport. The working connect port was the one the pairing dialog advertised (43687), NOT the one shown on the main Wireless Debugging screen (44225). **Tag: tested** this session.

## Continuation: Part 5 and Part 6, ledger UI and scan completion

Session 2026-08-02 continued with two more commits, now pushed. Critical architectural decision and a new root-cause bug class emerging.

### Commits landed (pushed to origin)

- **7267369** - Part 5: ledger read surfaces. Ticket 08 items 4-7. Variant B "Stream" transaction list, per-currency balances with no FX, quarantine rows, empty states, plus `ui/common/` (SectionHeader, Hairline, ReadingRow, NotBuiltRow) extracted early per ticket 09's mandate.
- **4272146** - Part 6: folder connection, scan progress, LLM spend gate. Ticket 08 items 1,2,3 and the rest of 7. First code ever to call `IngestScanner.scan()`.

### Ratified architectural decision (Kevin, explicit, this session)

**IngestScanner moved OUT of AriaForegroundService into new LedgerIngestService (foregroundServiceType=dataSync, bind-driven, no mic/GPS/Live socket).** 

**Reason, traced by reading the method:** `AriaForegroundService.onCreate()` unconditionally boots the entire voice stack - mic prewarm, a Gemini Live socket, GPS, telephony, a weather loop - with NO check of `AssistantIgnition`. Binding `IngestScanner` from the Ledger tab would have started the assistant with the toggle off, violating ticket 07's ruling that a refusal means "assistant off, nothing else affected". Part 4 had placed the scanner in AriaForegroundService; that was harmless only while nothing bound to it.

### Root-cause bug class identified (belongs in lessons.md)

**Process-wide cache initialization must not live in an unconditionally-started service.**

`GeminiKeyProvider`, `ProactivePreferences` and `LedgerFolderPreferences` are process-wide caches seeded once from disk by an `init()` call. The first two were seeded in `AriaForegroundService.onCreate`; the third was never seeded anywhere. Ticket 07 turned that service into an explicit opt-in toggle that is OFF by default. 

**Result:** On a normal launch, nothing seeded any of them, while the backing values sat on disk perfectly intact. 

**Symptoms, both tested on device:** the connected statements folder was forgotten on every process start, and the ledger spend gate reported "no Gemini key" for a key that WAS saved and present.

**Fix:** All three now seeded in `MidnightApplication.onCreate`, which does not depend on any feature being switched on.

**General rule worth graduating:** When a service stops starting unconditionally, everything it was incidentally initializing silently stops being initialised. A foreground service is not a safe home for process-wide init. Application.onCreate is.

### Four bugs found by senior-dev review (all fixed before commit)

1. **BLOCKING: scan was launched from composable's `rememberCoroutineScope()`**, so leaving the ledger tab cancelled it mid-batch, abandoning any Gemini call already paid for and making `LedgerIngestService.onUnbind`'s wait-for-Finished grace period meaningless. Now runs on the service's own scope via `LedgerIngestService.startScan`.

2. **`IngestScanner.scan` swallowed `CancellationException` via generic `catch (e: Exception)` and reported a cancelled scan as `Finished(FileResults())` - indistinguishable from one that legitimately found nothing.** Now rethrown.

3. **`LedgerFolderPreferences.connect()` never released the outgoing persisted URI grant**, so every CHANGE FOLDER leaked one. Persisted grants are an OS-capped resource.

4. **Foreground notification claimed "scanning" from the moment the tab was opened** (promoted in `onCreate`). Now promoted in `startScan`, so it exists only while a scan runs. Plus a re-entrancy guard so a fast double-tap can't start two runs that sweep each other's scanDir.

### On-device test result (tested, CPH2471, this session)

The full ingestion pipeline ran end to end on hardware for the first time. 

- Folder connected via SAF tree with a persisted grant
- Gate rendered "1 statement needs AI reading" (correctly 1, not 2 - only the unrecognized layout reaches the LLM)
- Approval ran a REAL Gemini call on Kevin's own key
- Result QUARANTINED because document prints no total to reconcile against: "This statement doesn't print a clear total to verify against - refusing to guess"
- **Money was spent and the output was still refused. Zero rows written.**

This is CLAUDE.md §4's central thesis - LLM extraction only behind a deterministic gate - validated against a live model on hardware. The practical consequence worth recording: a successful, paid LLM call can legitimately produce nothing, and the UI has to make that legible rather than look broken.

Earlier in same session, also tested on hardware: the first end-to-end ingestion at all, importing `dbs_happy_path.pdf` through real SAF picker ("Imported 3 transaction(s)"), and `dbs_balance_mismatch.pdf` quarantining.

### Known issues NOT verified

- **Tab switch DURING a live scan:** Fix is correct by construction (service scope vs composition scope) but both fixtures complete in under a second, so interruption could not be staged. `reasoned`.
- **"Read by AI" provenance label has STILL never rendered.** Needs an unrecognized layout that DOES reconcile, and every fixture for that path is designed to fail. This is a FIXTURE GAP, not a code gap.
- **Today's run used a LOCAL SAF tree, not a Drive-synced folder.** Same DocumentsContract code path, but does not reproduce the probe's stale-listing latency finding.

### Operational facts

- `adb shell pm clear` is BLOCKED by the OEM on this device (SecurityException, no CLEAR_APP_USER_DATA from shell). Wiping ledger state for repeatable test means uninstall + reinstall.
- Files pushed by `adb push` into a subfolder are NOT visible through the MediaStore-backed Downloads DocumentsProvider (folder lists as "No items"), but ARE visible through internal-storage root (ExternalStorageProvider), which lists the real filesystem. Use the device root, not Download, when staging SAF fixtures.

## Part 7: Reconciling fixture, Treatment B render, and the date bug

Three additional commits pushed to origin; all code is tested / on-device verified.

### Commits landed (pushed to origin)

- **b81a4cf** - The reconciling ledger fixture + the `+` money-parsing fix. Fixture `unrecognized_reconciling.pdf` falls through both deterministic parsers AND reconciles - no prior fixture did both. The gate now accepts it.
- **ef70cc7** - Ticket 09: fleet and pantry screens. Pantry implements Treatment B (segregated receipt facts / LLM-guessed macros under separate headers). Fleet shows LIVE / DUE / FAULTS / NOT BUILT YET blocks. 23 new unit tests. Senior-dev review came back CLEAN, no blocking findings.
- **0ee27e9** - The document-date timezone fix (critical finding in this commit).

### The headline result (tested on device, CPH2471)

**Treatment B rendered for the first time.** A synthetic receipt was generated, imported through the real pantry flow, and a REAL Gemini vision call on Kevin's own key extracted it. It RECONCILED - "Logged 5 item(s) from TRADER JOES #452", printed total 29.82 matching the sum of five line items exactly. The segregated layout then rendered correctly: prices and macros never share a row, the sentence sits between the two blocks. **Tag: tested.**

### THE CRITICAL BUG FOUND (commit 0ee27e9)

Rendering Treatment B immediately exposed that a receipt printed 04/18/2026 and the app displayed "Apr 17, 2026". A one-day drift. Cause, **traced then confirmed empirically**: EVERY ingestion path normalises a parsed calendar date to UTC midnight (`atStartOfDay(ZoneOffset.UTC)`) — `DbsStatementParser`, `BofaStatementParser`, `LedgerStatementAgent`, `PantryReceiptAgent` — while rendering through `ZoneId.systemDefault()`. At UTC-5 (device timezone), that lands on the previous calendar day.

**The ledger had been doing this to every transaction date since it shipped.** Earlier screenshots this session showed Apr 26/18/10 for rows printed 27/19/11.

**Fix:** New `documentDate`/`documentDateCompact` render formatters in UTC, used at the THREE call sites that render UTC-midnight values: (1) ledger stream row in `LedgerController`, (2) pantry receipt header in `PantryController`, (3) `get_transactions` voice tool result. Deliberately NOT a blanket change to all date formatting: the same two formatters are used at 8 other sites on real instants (`CodeEvent.timestamp`, `ServiceRecord.date`, `BuildEntry.date` are all `System.currentTimeMillis()`), and on `MaintenanceItem.lastDoneDate`, which `LiveToolbox.parseIsoDate` normalizes to LOCAL midnight. Those 8 were already correct and a blanket change would have broken them.

**Verified on device after the fix:** receipt reads Apr 18 2026, ledger rows read Apr 27/19/11/5 (the printed dates). **Tag: tested.**

**Total scope: 71 unit tests green, builds clean.**

### The `+` money-parsing fix (commit b81a4cf)

`MONEY_RE` was `^(-?)\$?...` - accepted a leading minus, rejected a leading plus. The first reconciling fixture printed its total as `+1,025.00` the way real statements do; the model echoed the `+` into `statedTotal`, and `parseMoneyCents` threw. The gate quarantined with "doesn't print a clear total to verify against" - a false negative, not a safety property. Fix: now accepts `+`. **`MONEY_TOKEN_RE` deliberately left as `-?`** - it only locates candidates, and a leading plus falls outside the match so the remainder parses to the same cents value. **Tag: built.**

### Fixture generators now checked in

Both are deterministic, dependency-free, and derive their printed total from the item rows rather than hardcoding it — a fixture whose own total is a typo tests the opposite of what it is for.

- **`tools/make_ledger_fixture.py`** — dependency-free uncompressed PDF via reportlab (note: reportlab is not installed; generated fixture is a one-time artifact, not a build requirement). Produces `unrecognized_reconciling.pdf`: falls through both deterministic parsers AND reconciles.
- **`tools/make_pantry_fixture.ps1`** — PowerShell + System.Drawing, because pantry ingests a PHOTO not a PDF and rendering text to a raster requires a font engine. Windows-only, acceptable for a hand-run fixture generator. **Operational: this machine's execution policy refuses unsigned script FILES even with `-ExecutionPolicy Bypass`; the generator must be run via `Invoke-Expression (Get-Content -Raw ...)`.** The script therefore cannot rely on `$PSScriptRoot` (empty when piped).

### Pre-existing gap found, NOT fixed

`DtcDescriptions.loadSeed` reads `assets/dtc_descriptions_seed.json`, which has NEVER existed in git history (checked `--all`), though its own doc comment calls it bundled. Degrades gracefully to an empty map, so every fault code reads "not identified locally". Building a DTC dictionary is a content task and wants its own ticket.

### Still not verified

- A tab switch DURING a live scan (reasoned, service scope architecture is correct by construction).
- The `+` fix itself on device - unit-tested only, no statement printing a plus has been through a real extraction.
- A run against a REAL Drive folder; everything so far used a local SAF tree, which cannot reproduce latency.
- `sync/` has still never executed. Ticket 10's rulings remain traced.
- The assistant permission chain has still never been exercised.
