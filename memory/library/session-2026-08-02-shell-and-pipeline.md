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
