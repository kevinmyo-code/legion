# MEMORY.md

## 2026-10-14 docs sweep: lines below that git log has overtaken

Marked inline with **(STALE 2026-10-14: ...)**. Summary of `dev` at `c94cb8a` (2026-10-11):
- `feat/django-limb`, `fix/gemini-audit-followups` and `feat/cars-manager` are all merged into `dev`
  (`2443785`, `64316b6`, `e155df0`). `dev` is the trunk; `origin/main` sits at `8ebfe70` (2026-07-31).
- Removed 2026-10-11: in-app navigation and Mapbox, the wake word and ambient listening, the garage
  settings hub (`ui/GarageHub.kt`). Removed 2026-09-27: in-app image generation.
- The companion is **Uncle Moose** (`399f5e3`, `67d7ea9`), not Zero.
- Built 2026-10-11: two-pane Settings, voice music parity, Spotify playlist pins, `ask_engine`, the
  `fast` build type. ABI splits have been on since 2026-09-07 (`440c9f7`).

## ⚠ 2026-09-07 - THIS REPO IS LIVE AGAIN. Read this before anything below it.

**Everything under the older banner is superseded for this repository.** It says MIDNIGHT_AI stays
private as a frozen archive, that the project is phone-only, and that head units no longer matter.
All three were true on 2026-07-31 and none of them is true now. A stale instruction is not ignored,
it is obeyed - so this section exists to stop the next session acting on the one below it.

**What this repo is now:** the **third limb** of the LEGION Django engine, alongside the phone app
and a planned web app. A live Android head-unit app, `com.kevin.midnightai`, its own release
cadence, its own device token, reading the engine over HTTPS JSON. **Build here.** Kevin, today:
*"i want to add that too as a limb for the django app. it reads the django just like ours."*

The standing rule is **ADR 0045** in the LEGION repo (`docs/adr/0045-the-head-unit-is-a-third-limb.md`).
What survives of the freeze is only the provenance half: LEGION's project history still does not get
written into this repo's memory files, and `memory/library/`'s frozen shelves are still history
rather than rules.

**The two repos share a CONTRACT, never code.** `openapi/legion-schema.yaml` here is a vendored copy
of the engine's schema; models are generated from it. There is no shared module and there will not
be one - the two codebases share class names from the 2026-07-31 seed and no longer share meanings.

### Where the work is

**(STALE 2026-10-14: merged into `dev` 2026-10-10, `2443785`.)** Branch **`feat/django-limb`**, in the worktree
`C:\Users\kevin\AndroidStudioProjects\AiApp\.claude\worktrees\limb`. Four commits:

1. the engine client - vendored schema, 35 generated models, `backend/engine` transport on OkHttp
2. a head-unit home screen (**since reverted**, see 3)
3. Cruise is home again; the reverse camera and the honest empty states folded into it
4. ABI splits for the APK

### To do, in order

1. ~~**Build the APK and confirm the ABI split actually works.**~~ **DONE, verified on 2026-09-07.**
   `./gradlew assembleDebug` ran to completion (BUILD SUCCESSFUL, 44 tasks) from this worktree after
   deleting `app/build/outputs/apk/debug/` first, so the outputs below are that run's, not a stale
   leftover. Exactly two APKs, no universal one:
   - `app-arm64-v8a-debug.apk` 87 MB, containing only `lib/arm64-v8a/`
   - `app-armeabi-v7a-debug.apk` 71 MB, containing only `lib/armeabi-v7a/`
   `isUniversalApk = false` took. The one thing the earlier reasoning got wrong is the size: the
   guess was 50-65 MB each, the reality is 71-87 MB, because the per-ABI native payload is bigger
   than the even-quarters split of 245 MB implied. Still roughly a third of the 262 MB universal
   APK. Nothing here has been installed on the head unit; this verifies the packaging only.

2. **Sideload to the head unit and answer the one open question.** Kevin reports the unit's own
   stock music display shows nothing when his wife plays Spotify from her phone over Bluetooth. This
   app reads the same source - a `MediaSession` the vendor stack publishes - so it may show nothing
   for the same reason, and the whole music surface rests on the answer.
   Grant notification access, start Spotify on her phone, open **ControlPanel -> media diagnostics**.
   - sessions visible with populated metadata -> the cassette works, carry on
   - sessions visible, metadata empty -> AVRCP metadata does not reach this ROM. Transport controls
     may still work while the cassette stays blank. That is a different and harder problem, and it
     should be understood before anything else is built on top of the music surface.
   - no sessions at all -> check the notification-access grant first; without it `getActiveSessions`
     throws rather than returning empty.

3. **Point the REAR tile at the vendor camera activity.** The camera is composite/RCA into the head
   unit's own input, so the vendor firmware owns the video - there is nothing here to render and no
   `CAMERA` permission is used. Long-press the REAR dock tile, or use the ControlPanel row, and pick
   the target. Its package cannot be known off-device; vendor camera activities frequently have no
   launcher icon, which is why the picker also takes free-text package/class.

4. **(STALE 2026-10-14: settled; `fix/gemini-audit-followups` is in `dev`, and `dev` is the trunk.)** **Decide this repo's trunk.** `dev` and `main` are **93 commits behind**
   `fix/gemini-audit-followups`, which is the real tip and what `feat/django-limb` was cut from.
   Merging the limb branch into a stale `dev` would look like a merge and behave like a revert.

5. **The main tree has uncommitted AGP 9.3.1 / Gradle 9.5.0 bumps** that break toolchain parity with
   LEGION (both repos are otherwise on AGP 9.2.1 / Gradle 9.4.1). Kevin's call; unresolved.

### Traps, all of them paid for once already

- **The gradle MCP tools build the LEGION repo, not this one**, wherever they are called from. They
  return a real green about a tree nobody asked them to build. Outside LEGION's main checkout use
  `./gradlew` through a shell with an explicit `cd`, and read totals from the JUnit XML under this
  checkout's own `app/build/test-results/`.
- **One Gradle invocation at a time on this machine.** `assembleDebug` and a full LEGION test run
  were each killed by the OS for low memory today. A second Android Studio window syncing counts.
- **Never generate code into `build/generated/`** - that subtree is AGP's. A source dir inside it
  broke resource merging in the LEGION repo and surfaced as an unresolved `R.drawable` reference in
  a file nowhere near the change. Codegen here goes to `build/openapi-generated/`.
- **AGP rejects a task Provider in its SourceSet API**, so the idiomatic
  `kotlin.srcDir(tasks.named(...).map { ... })` fails and Gradle 9 then refuses to infer the
  dependency from a bare path. Edges are declared by hand in `app/build.gradle.kts`, KSP included.

### Verified, and not

`compileDebugKotlin` green. Full unit suite **261 tests, 0 failures, 0 errors**, read from the
JUnit XML. **Nothing in any of it has run on hardware.**

Untested and known: the real `ActivityNotFoundException` / `SecurityException` catches in
`ReverseCameraLauncher` are traced by reading, not exercised - constructing those types under plain
JUnit is unreliable in this project's setup. The REAR tile's tap-versus-long-press on a real
touchscreen, whether `CompanionPhase.showNotice` visibly flashes in both layout branches, and
whether the new cassette caption reads at the head unit's font scale and viewing distance are all
unverified.

### A note on the UI, so it is not re-litigated

Kevin asked whether the old Midnight AI UI could be copied - cassette now-playing, app tray, Lights
Out. There was nothing to copy: all of it is in this repo and always was. `CruiseScreen` IS that UI.
A separate `DriveHome` screen was built on 2026-09-07 and reverted the same day for reinventing a
plainer version of it. **Add to Cruise; do not build a second home screen.**

---

Dashboard for Midnight AI (historical names: Moose, Aria, Nightrunner; mascot was Kaze, then Yoko,
then Zero, now Uncle Moose). Read before responding. CLAUDE.md = locked rules. This file = what is happening now.
MEMORY.md wins for state, CLAUDE.md wins for rules. Depth lives in the library, not here.

## ⚠ DIRECTION CHANGED 2026-07-30/31 — read this before anything below

**Kevin has pivoted the project.** Most of this file, and large parts of CLAUDE.md, are now
historical. Do not act on the sprint/blocking/in-flight sections without checking them against this.

**The new project is named LEGION, its public repo is live: https://github.com/kevinmyo-code/legion,
and it BUILDS CLEAN as of 2026-07-31** (`./gradlew compileDebugKotlin -Pnokey` succeeds — verified
with a real build, not just source inspection). Package renamed `com.kevin.midnightai` ->
`com.kevin.legion` in that repo only — this repo (`MIDNIGHT_AI`) is untouched and stays the private
archive. The fleet aspect, orchestrator core, and Drive sync backbone are ported and compiling.
**Ledger and pantry aspects are now BOTH done (2026-07-31)** — see their own paragraphs below.
`ui/` is still an intentional clean-slate rebuild (only placeholder `MainActivity` +
`SavedPlacesActivity` exist). See that repo's own README.md for the full status and remaining
contested calls.

**Lesson worth keeping:** the first reconciliation pass fixed everything a grep for retired class
names could find (36 files), but a grep can only find symbol-level breaks. The actual compile
caught what it couldn't: a Room DAO query against a dropped column, a file wrongly deleted because
its name looked badge-related when other code depended on it for core state, and several files
that were referenced but never actually copied over in the first place (nothing greps for an
absence). Don't trust a grep-clean result as "done" for a port of this size — run the real build.

**Ledger aspect: done (2026-07-31), deterministic-first with an LLM fallback.** Ports Project
Andromeda's `duo_ledger.bronze` (`~/PycharmProjects/Andromeda`) — the only part of Andromeda with
real content; `silver`/`gold`/`categorization`/`fx`/`agent` are empty stubs, nothing to port there.
Kevin's explicit call: deterministic parsers (Bank of America, DBS) stay primary; an LLM extraction
path only kicks in when neither recognizes the layout, and even then must pass the same
reconciliation principle (extracted transactions sum to the statement's own stated total) before
anything is accepted — a mismatch quarantines, nothing is written. Every row tagged
`DETERMINISTIC`/`LLM_RECONCILED`. Money is `Long` cents, not `Double` — deliberate deviation from
`BuildEntry`/`ServiceRecord`'s convention, since the reconciliation gate depends on exact equality.
11 tests, 5 fixture PDFs (generated via Andromeda's own `reportlab` tooling for parity), all green;
`compileDebugKotlin`/`testDebugUnitTest` both verified with a real build. Full design in LEGION's
`.claude/plans/wiggly-beaming-quasar.md`; status detail in LEGION's own README, not duplicated here.
Real finding worth remembering: PdfBox-Android (needed for DBS's coordinate-based column
classification) ships fonts/glyphlists as Android assets, unreachable from a plain JVM unit test —
caught by actually running the spike before porting the rest of the parser, fixed by adding
Robolectric (test-only). Not built: ledger categorization/FX/insights (nothing to port, new design
work), most of `ui/`.

**Pantry aspect: done (2026-07-31), LLM-vision-first (no deterministic path exists — receipts are
photographed, not born-digital).** New design work, no Andromeda equivalent. Kevin's explicit call:
build ingestion + per-item macro estimation now (calories/protein/carbs/fat, LLM-guessed from the
product name); consumption-rate tracking and spend/nutrition aggregation are deferred, same shape as
ledger's categorization/FX. `ai/SubAgent.kt` extended with an optional inline image part (reused, not
a one-off HTTP call). `PantryReceiptAgent.parseAndReconcile` enforces the same discipline as ledger:
extracted line-item totals must sum exactly to the receipt's own printed total or the whole receipt
quarantines — macro estimates are never part of that check and must always be surfaced as estimates,
never fact (CLAUDE.md §9.1). `PhotoAlbumStore` (old car-photo-album feature) replaced outright by
`PantryPhotoStore` (ingestion-only, no browsable album). 8 tests, all against canned JSON strings (no
real image fixture or network call needed — there's no deterministic ground truth to synthesize
fixtures against here). `compileDebugKotlin`/`testDebugUnitTest` both verified with a real build (19
tests total across ledger+pantry, all green). Full design in LEGION's
`.claude/plans/wiggly-beaming-quasar.md`; status detail in LEGION's own README.

Midnight AI stops being a commercial head-unit car launcher. It becomes **one Android phone app**:
a single voice assistant (Alfred/JARVIS register, a tool with personality) orchestrating **aspects**
of life — **fleet** (OBD, ported), **ledger** (finance, ported from Project Andromeda), **pantry**
(grocery receipt photos → ingestion + per-item macro estimates, done; consumption-rate tracking
deferred).

Locked during charting, and none of it is re-openable without Kevin:

- **Phone-only.** Head units may still install it; they no longer constrain design. The AOSP 8-10
  ceiling, the frame-clock-only motion ban, and the ADB blackout are all lifted.
- **Commercial model is DEAD** — no billing, tiers, broker, trial, store listing, or positioning.
- **New PUBLIC repo with clean history**, seeded by copying surviving source. `MIDNIGHT_AI` stays
  private as the archive. (`app/google-services.json` is tracked from `f356eb6` with the real
  `midnight-ai-c7421` project — CLAUDE.md's "placeholder" claim is stale and wrong.)
- **Drive-BYO is the only store.** One shared Google account, two phones. No Firestore, no backend.
- **Clone-and-run is a HARD requirement** — a stranger clones, sideloads, signs in, and it works.
  That, not cost, is what rules out Firestore.
- **One global assistant identity.** Cars are data, not identities. Per-car `CompanionProfile`
  keying and `CompanionIdentity`'s Zero-vs-car-self split both die.
- **The city-pop design language is dead**, with the mascot Zero and all generated art. The two-
  identities decision from `.scratch/car-launcher-market/` ticket 05 is superseded.
- **LLM ingestion is now allowed**, behind a deterministic reconciliation gate (extracted rows must
  reconcile against the document's own totals; else quarantine, never silently accept).
- **Project Andromeda retires once ledger ports.** Until then its map is the finance authority.

**Wayfinder map REBUILT 2026-07-31: the original (`.scratch/multi-aspect-assistant/map.md`, 15
tickets, including the 12 contested keep/delete calls) did not survive a machine port — `.scratch/`
is gitignored and was never committed, and none of its content had been filed to
`library/decisions.md` yet when the port happened. Do not assume the old 15-ticket numbering or the
old 12 calls still apply; they are gone, not just stale.** A fresh map and carry-over inventory now
live at the same paths. The fresh inventory's contested items (a different 5, not the lost 12) are
**already resolved and filed**: see `library/decisions.md` 2026-07-31 "multi-aspect pivot:
carry-over inventory ruled on" — music (Spotify App Remote kept, no UI, mixtapes retired), garage
(kept, voice-only), spend gate (retired, no ledger replacement yet), fleet build/mod photos
(retired; photo storage becomes pantry-ingestion-only), tagged places (kept as-is). Resolved
research carried forward from the lost map (per the librarian's prior digest, re-verify specifics
before building on them): NOOA research, Drive research.

Two findings that change plans: **Drive's Android OAuth client is keyed to package + SHA-1 signing
cert, so a stranger's build fails authorization** (ticket 15 — this threatens the hard requirement),
and **Drive has no compare-and-swap, so today's shared-file LWW will silently lose rows** — sync
must become append-only.

## Library

Long-term memory is memory/library/ (card catalog: memory/library/INDEX.md). Do not bulk-read
shelves into context. Dispatch the librarian: RETRIEVE for a digest, FILE at session end.

## Active sprint

Sprint 3, content layer (chassis-quirk YAML + QuirkAgent, oil-analysis trends, Foresight). Not started.

**2026-07-16 rewrite (three decisions, library/decisions.md is the authority).** (1) Pricing is two
tiers, no trial, no subscription, no backend (§2); `functions/` is dead code. (2) Mascot is **Zero**:
androgynous, Motoko-shaped, ONE persona across a male and female variant, and **NOT the car** - Zero
rides along, the driver's own avatar is the car, so C1's first-person reframe is the PAID payoff (§1).
(3) **Positioned as a LAUNCHER**, companion is the upgrade (§1).

DONE 2026-07-16: Zero's persona written + unified (`VehicleController.DEFAULT_PERSONA`, seeded by
reference from `OnboardingState`), `STYLE_DNA`/`PORTRAIT_PROMPT` redirected, Moose/Yoko retired, B30
shipped, **the broker deleted**, and the code now matches §2 everywhere ($10, no trial, no sub, plus
the free-tier training disclosure on the key screen).

NOT started: bundled canned Zero voice lines (2 variants, generate once on Kevin's key, bundle in
`res/raw` beside the 8 `voice_sample_*.wav`), bundled Zero avatar/wallpaper set, the "I can't explain
it yet" paywall entry, **a Zero character reference (ref sheet STILL uncommissioned - and per
ROADMAP.md this is now the #1 blocker: we positioned on being the only art-directed launcher and the
art does not exist)**. Also queued from Kevin's 2026-07-16 dump: mute toggle (note it would let a
driver silence the only conversion mechanism, so the canned notices must earn their keep), API-key
usage/spend readout (repurpose the now-dormant `VoiceUsageMeter`; must be labelled an ESTIMATE,
Google does not expose per-key spend), and a 7-day-trial-on-the-driver's-own-key idea that would
reopen tickets 06/07 (costs Kevin nothing, but taking away a working setup after 7 days is the
opposite failure from the one it solves).

**Lesson worth keeping (it cost us twice today):** stale comments and stale copy don't sit there
harmlessly, they actively mislead. A `RuntimeMode` kdoc made a coding agent refuse a decided feature;
`ControlPanelScreen` offered $5 next to B30's $10. Both were docs, not logic. When a decision lands,
fix the words in the same pass.

## Blocking (one-liners; narratives: library/blocking.md)

- B13-B17, B1/B2, B8/F4: old permission/onboarding/scan fixes, still device-unverified since 07-08/09.
- B29: CODES tab flickers red/amber - a failed DTC read resolves to 0, same as "no codes". Open.
- ~~B30: OBD history invisible.~~ FIXED 2026-07-16 (`ed70790`). Not on-device verified (LIVE poll vs A2DP).
- B31: Drive sync has no persisted lastSyncAt (DriveSyncEntry.kt:53 is transient `remember`). Open.
- **B32 (2026-07-19): gauges drop to voltage-only and STAY dead mid-drive.** First fix (`isFailureResponse`, matching NO DATA/UNABLE/STOPPED/CAN ERROR) proved insufficient on a real drive. Second pass same day: broadened `isFailureResponse` to the full ELM327 error set (BUS ERROR/BUS INIT/DATA ERROR/etc), added `ATPC` (protocol close) before `ATSP0`+`0100` in `reinitProtocolLocked()` since ATSP0 alone doesn't tear down a K-line session the adapter still thinks is open, and instrumented both the exact failure string (`MidnightEvents.obdPidSilence`) and a retrievable non-fatal per reinit attempt with recovered y/n (`MidnightEvents.obdReinit`) since ADB is blocked. `.scratch/drive-notes-2/issues/02-*`. **Open, waiting on a drive** - check Crashlytics for `obd_reinit` non-fatals next.
- **B33 (2026-07-19): voice cuts off after 2nd turn / shows Listening but doesn't hear.** Root cause not found yet, but WHY it couldn't be found was: `MidnightEvents` breadcrumbs (B9/B10/B12) were log-only, never reported, so 7 days of Crashlytics showed zero events despite the bug happening. Fixed: `MidnightEvents.silentMicTurn()` forces a real non-fatal when a turn forwards <2000 bytes. `.scratch/drive-notes-2/issues/01-*`. **Open, waiting on a drive to produce a real non-fatal.**
- **S2: §9.1 Companion Safety, PARTLY IMPLEMENTED 2026-07-16 (`3939a12`).** DONE: the **crisis path** (`CrisisDetector` + `AriaBrain.safetyInstructions` + `LiveEvent.CrisisDetected` -> `silentDestroy` + `CompanionPhase.crisis` -> `CrisisCard`, 8 JVM tests) and the **no-sentience/no-need + anchored-memory rules**, both placed in `safetyInstructions` NOT `DEFAULT_PERSONA` (§2 lets an unlocked driver rewrite the persona, so a guardrail living there is editable by the user it protects). Detector is precision-tuned: fires only on unambiguous phrases, guards car idioms ("suicide doors" is a real body style), deliberately misses oblique distress and leaves it to the prompt rule - a test pins that as a decision. **NOT on-device verified, and the resource is US-only (988)**; §2's region fork means EEA/UK/CH drivers get a US number, which is a known gap. STILL NOT BUILT: "Zero winds down when the drive ends" (the app IS the launcher, always on, parked or not, so §9.1's natural session limiter is not real). Already satisfied by accident: "preserve friction" (`DEFAULT_PERSONA`'s blunt / never-coy / never-possessive lines) and "no compulsion mechanics" (§2's one-time unlock kills the engagement incentive structurally). Follow-up: the crisis line is text-only on a screen a driver may be looking away from; a bundled neutral WAV (out of character, deterministic) is the honest fix and belongs with Phase 3's canned-voice generation.
- ~~S2-CONFLICT~~ **RESOLVED 2026-07-16 by Kevin: "Zero is not the car. Users' avatar will be the car."** §1 wins, §9.1's car-conceit bullet was overturned and rewritten. It was never just a docs conflict: `AriaBrain.assembleBase()` was concatenating `DEFAULT_PERSONA` ("you are not the car") and `sharedInstructions` ("you ARE this car") into ONE shipped system instruction, and **eleven** sites asserted an identity independently (AriaBrain, DEFAULT_PERSONA, OnboardingFlow x2, OnboardingManager x4, Diagnostic/Symptom/Music/ColdStart/Maintenance agents, Daily/Monthly/Yearly recaps). All now source `ai/CompanionIdentity.kt`: `clause()` for conversation, `shortClause()` for sub-agents, `onboardingClause()` for first-run. **The branch key is `CompanionProfile.name().isNotBlank()`, NOT `UnlockState`** - a name is only set by the customization flow, and reading UnlockState would flip already-named installs (incl. Kevin's Cherokee, BuildConfig key, never purchased) back to Zero and couple every sub-agent to billing. `DEFAULT_PERSONA` is now character-only. **Rule going forward: no prompt restates the identity; it comes from CompanionIdentity.** Two things to watch: (1) `onboardingClause()` is unconditionally car-self because the name is blank *during* the flow that sets it - when §2's free tier lands (fixed Zero, no rename), the free path must NOT reach it; (2) §9.1's safety argument for the car conceit was outvoted, not disproven, so the no-sentience rules in `safetyInstructions` now carry that load alone - do not weaken them.
- DB caution (RE-CORRECTED 2026-07-29, was stale by three): the DB is at **v12** and `addMigrations` registers 5->6->7->8->9->10->11->12, so **v5 and newer upgrade in place - install over, do NOT uninstall.** v12 added `maintenance_items.neverDone`; **that migration has never actually executed** (its MigrationTest case needs a device), so the first install-over is the first real run. Only v1-v4 have no path and crash-loop. This matters because uninstalling costs what sync does NOT carry: the Shelly auth key + garage doors, CruiseSettings (gauges/palette/saver), Nav/Music prefs, UnlockState, and the KeyVault Gemini key. The old "pre-v6, always uninstall" wording made a full wipe look routine when it is actually expensive.

- **CAR MANAGER (CARS) BUILT 2026-07-16, branch `feat/cars-manager`, pushed 2026-07-24, NOT on-device.** Grilled then built; full record in `library/decisions.md`. Top-level CARS roster off a Cruise dock tile; per-car avatar/wallpaper/persona/voice/palette; ARCHIVE not delete; drive corrector in OBD->HISTORY. **Room v10** (`drive_reassignments` + `vehicles.archived`). **Two defects it exposed, both would have shipped silently:** (1) `decideCompanion` PROMPTed on a blank local, so the first switch to any car a device had never held asked the driver to choose between NOTHING and their own companion - and "keep local" would have DESTROYED that car's identity on Drive; (2) the 365-day retention purge was **undone by every sync** since S1 (`monthsToSync` pulls back any month with a remote shard, so purged rows were re-inserted and re-uploaded forever - unbounded growth on device AND Drive). Both fixed. **Retention contract is now: device 365 days, Drive = permanent archive.**
- **Do not re-derive:** a drive correction MUST be a RULE (`drive_reassignments`), never a re-key. `obd_samples` syncs UNION on an identity that INCLUDES vehicleId, so changing it clones the drive onto BOTH cars on the next sync rather than moving it; tombstones don't help either (UNION never updates an existing row - B19's tombstones only work because car_tasks/places are LWW). And the re-key must run INSIDE `syncFile` between the merge and the upload: `syncFile` uploads `localAfter` itself, so a post-loop re-key re-uploads the old-keyed rows and the correction resurrects forever.
- **GPS ON THE CHEROKEE: the head unit's own GPS is DEAD and not software-fixable** (CLAUDE.md §14, corrected a THIRD time 2026-07-25). Connecting the antenna drops BOTH Bluetooth and WiFi - a shared power/RF rail the antenna browns out - and Kevin will not buy hardware. **This line previously said the opposite** ("DOES get a real GPS fix"); that was the mid-day 2026-07-25 state and was retracted later the same day, but the retraction never reached MEMORY.md until 2026-07-29. Location now comes from the **phone GPS beacon over the hotspot** (`location/Beacon*.kt`, `service/DeviceRole.kt`); everything downstream still lands on `LocationController.state`, so weather / drive monitor / places / odometer / recaps have a source and SHOULD function - but only when the beacon phone is present and in PHONE role, and **none of it is device-verified**. Embedded nav on the Cherokee now depends on the beacon on top of GL ES 3.0 + token.
- **Sync only ever fired from `MainActivity.onResume` (found 2026-07-16).** On a head unit that means "when you start the car", so everything a drive records sat local until the NEXT start - "drive, get home, check phone" was structurally impossible. Now also a 5-min push from the service while the engine runs. NOT drive-end: that needs the GPS-dead drive monitor, and Kevin cuts the key on arrival so the unit powers down first.

**Gate: 2026-07-16 Kevin drove the Cherokee - "all obd and voice and stuff works fine."** First real
on-device confirmation; the "almost entirely hardware-unvalidated" framing is retired. But he has NOT
said which specific items he exercised, so everything above stays open until confirmed individually.
Ask, do not assume.

**(STALE 2026-10-14: the wake word and ambient listening were REMOVED 2026-10-11, `0af1fac`; CLAUDE.md §8.)** **CUSTOM WAKE WORD SHIPPED, 2026-07-19** (`.scratch/custom-wake-word/`, map CLOSED, all 7 tickets
resolved, filed to decisions.md + CLAUDE.md §8). Vosk, runtime grammar from `CompanionProfile.name()`,
"hey <name>" phrase (bare name false-triggered too easily - real field data forced this), paid-tier
only (`RuntimeMode.BYO_KEY`), opt-in Setup toggle (`WakeWordPreferences`, off by default), fires a
real `ACTION_TALK` turn (debounced 4s). Model moved `src/debug/assets/` -> `src/main/assets/` so it
ships in release. On-hardware: clean, no false triggers, battery moot (shore power while driving).
**Third bug of the "stale on cold start" shape found and fixed same day:** `WakeWordEngine.start()`
runs at `onCreate`, before the OBD dongle connects, so it built "hey zero" instead of the real name on
a genuine key-off/key-on cycle. Fixed via `WakeWordEngine.refresh()` wired to the EXISTING
`ACTION_CAR_SWITCHED` (same hook that already fixed stale avatar/voice, `02dba74`). **Rule for future
work:** anything caching `CompanionProfile`/`ActiveVehicle` at service-start time MUST subscribe to
`ACTION_CAR_SWITCHED`. NOT yet verified on a real key-off/key-on cycle.

**FLEET HUB IS REAL AND BUILT** (sibling repo `AndroidStudioProjects/fleet-hub`, NOT the same as
`AiApp/.scratch/fleet-hub/`'s spec+prototype). Implemented against the spec: GIS token auth, Drive
appDataFolder reads/writes, cross-car dashboard, companion identity editor. **Bug found+fixed
2026-07-19: Fleet Hub's OAuth client was registered in a DIFFERENT Google Cloud project than the
Android app.** Drive's `appDataFolder` is scoped PER CLOUD PROJECT, not per Google account - two
projects means two separate invisible folders for the same user. Symptom was "synced OK, dashboard
still empty," which is the tell: success + emptiness means the two ends aren't addressing the same
namespace, not a transfer bug. Fix: new Web OAuth client created INSIDE `midnight-ai-c7421` (project
103196707820, matching `app/google-services.json`), confirmed working. `fleet-hub/js/config.js` now
ships blank with a loud warning; README leads with the constraint. Two smaller real bugs fixed on the
way (a singular/plural filename mismatch `dashboard.js` vs `SyncEngine.REGISTRY`, and `syncNow`'s
failure paths being log-only with zero Crashlytics visibility - now wired to `MidnightEvents.recordError`).

- **2026-07-24 session (branch `feat/cars-manager`, pushed; PR to `dev` opened).** Landed, all build+tests green, on-device unvalidated: graceful phone<->head-unit-Spotify source switching + Cruise source chip; **settings IA refactor** (12 flat items -> 6-group hub + @Previews) now rendered as the **city-pop garage hub** (`ui/GarageHub.kt`, **deleted 2026-10-11, `2d314d5`, replaced by two-pane Settings**; wayfinder effort `.scratch/garage-skin/`, Variant B: tappable car-on-hoist = Your Car, 5 bench objects); **garage backdrop = per-driver generation like the wallpaper** (generation removed 2026-09-27) (`ui/GarageBackdropGenerator.kt`, per-vehicle, synced via companion_media). Also: the **improvement loop** (`library/lessons.md` + assumptions-ledger in the coding agent def), **crew codenames** (Stark/Derek/Ravi/Vic/Owen/Nadia/Marcus/Priya/Simone in `.claude/agents/` + TEAM.md), and Crew + Memory pages added to the public `midnight-ai-architecture` Pages repo. On-device backlog: garage layout at 1024x600, generated-scene legibility, cross-device backdrop sync, Preferences "use as home" label refresh.

- **2026-07-29 (continued, same branch `fix/gemini-audit-followups`, now 89 commits ahead of dev, STILL NOT pushed).** Landed after the Gemini-audit fixes below: **Spotify in-place playback** (re-entering the client ID silently skipped OAuth consent because tokens were never cleared, so `play_music` fell through to an intent that foregrounded Spotify; `playUri` also reported success on dispatch, narrating "Playing X" over silence), **launcher `singleTask`** (the OAuth redirect was building a second MainActivity), the **maintenance schedule feature** (5 commits - see decisions.md), and **UI coherence steps 1-5 of 6** (3 commits). Every one of these is compile+tests-verified only. Full narratives in library/.
- **2026-07-28/29 session: branch `fix/gemini-audit-followups`, 9 commits, NOT pushed.** Kevin ran Android Studio's Gemini agent pane while this session was down; it made 3 commits on `feature/ui-responsiveness` (branched off `feat/cars-manager`, not `dev`). Audited, then fixed. What it broke and what got repaired is in `library/` (decisions + playbook-coding + lessons L4/L5). Headlines: **full-screen nav had become unreachable** (both call sites passed `openActivity=false`, the only `startActivity` for it), taking spoken turn guidance with it on the voice path; route requested once with no retry; router `onFailure` an empty block; **GarageHub's "LOOK" tile opened Connections** and vice versa (a list read by index but produced in a different order); fabricated spec-sheet readouts (hardcoded Houston lat/lng, `SPOTIFY ACTIVE`, `VERSION 1.0.1`); Cruise COMPACT lost `verticalScroll`; and **322 comment lines deleted across five files.** Also fixed this session: Spotify App Remote never reconnected after a drop (surfacing as a misleading "turn on Music app on this head unit"), karaoke tracks from `limit=1` search, `NavGeocoder`'s hardcoded `country=US` + a hard `bbox` filter that broke any destination >150 miles, and `android.location.Geocoder` (needs GMS the head units lack).
- **Everything from that session is compile-and-logic verified ONLY - zero device verification.** ADB still not set up on the Oppo (Kevin declined 2026-07-28), so Owen/`qa` remains unavailable. Riskiest unverified: nav route-retry-on-first-fix and the Spotify reconnect, both timing-dependent.
- **Kevin is now PHONE-FIRST (2026-07-28).** He mostly runs the app on the Oppo A17K, not the head unit, and expects that to continue. **COMPACT is now the size class that actually gets exercised daily** - it had been the secondary target behind EXPANDED. A17K is ~360dp wide; `MusicPanel`'s transport row is ~372dp, which is why Cruise's COMPACT branch must stay scrollable.

## In-flight (detail in library/; dispatch librarian RETRIEVE)

| ID | Item | Status |
|---|---|---|
| C1 | First-person car-self reframe | Code done, needs 5 canonical voice tests. The Zero decision (§1) settles the identity ambiguity it carried |
| D1 | Dual-target (head unit + phone) | Coding agent, in progress. Gated on Outlander validation |
| M1-M3 | Music taste ledger, Mixtape Manager, recommender | Code done, need device verify |
| N1 | Mapbox embedded nav | **(STALE 2026-10-14: all navigation and Mapbox REMOVED 2026-10-11.)** **Built and reworked 2026-07-28/29**: `NavSessionManager` now owns the trip session (route retry across the first GPS fix, failure surfaced via `TripStatus`, spoken turn guidance moved out of the Activity so voice-started trips get it). Forward+reverse geocoding unrestricted (no country/bbox/types). Full-screen nav reachable again by tapping Cruise's map. **GL ES on the Cherokee still unknown**; on the phone it works. Zero device verification of the retry/guidance paths |
| N2 | Music/nav tiers | **(STALE 2026-10-14: a BYO Spotify App Remote came back 2026-07-21 and is live on `dev`; nav is removed.)** Phase A done. **Row is STALE**: the play/personal flavors + Spotify SDK were REMOVED 2026-07-12 (§8). The `.aar` in app/libs/ is parked deliberately, not a violation |
| O1 | BLE OBD (HM-10 + Nordic UART) | Code done. Pending Outlander + FlyRoadTech dongle |
| G1 | Garage (Shelly Cloud, action-only) | Code done, 22 tests green, reviewed. Needs a real Shelly relay |
| S1 | Drive sync (BYO-cloud, light data) | Feature-complete incl. tombstones, concurrency, companion identity + visual sync. Head unit uploaded 2026-07-16; phone side and the 2-device round-trip still unvalidated |

## Notes for next session

- **TOP OF THE LIST: nothing from 2026-07-29 has run on a device.** The Room **v11->v12 migration has never executed** against real data, the **DUE tab has never been rendered**, and the whole UI coherence series (Settings retype, PanelRow, dock) has never shown a frame. A senior-dev pass and a bug-hunter pass both cleared them statically - no first-render crash found - but static is all it is. Render the Compose previews in Studio first (`PreviewSettingsHub` etc. now work; `WallpaperBackdrop` no longer touches Bluetooth), then install to the Oppo before the Cherokee.
- **On the Cherokee specifically: hit REFRESH SCHEDULE in the logbook DUE tab once.** Its schedule was seeded by the old unqualified prompt, and already-onboarded cars never re-seed on their own. Everything will read UNKNOWN until backfilled by voice - that is the bug fix working, not an empty feature.
- **The librarian FILE dispatch invented substantial detail this session and had to be corrected by hand** (a bundled `default-maintenance-schedules.yaml` that does not exist, a `google_search` Spotify fallback that does not exist, crashes and hangs that were never observed, wrong commit attributions). blocking.md B35-B37, both decisions.md 2026-07-29 entries and lessons L8 were rewritten from the actual commits. **Verify what the librarian writes before trusting it** - this repo has a commit literally titled "correct three stale or invented facts", and this is the same failure recurring in the tool meant to prevent it.
- **(STALE 2026-10-14: built 2026-10-11, `2da7dcf`.)** **UI step 6 deferred by Kevin, not rejected:** two-pane Settings for EXPANDED (groups pinned left, detail right). Phone stays single-column.
- **(STALE 2026-10-14: ABI splits are ON since 2026-09-07, `440c9f7`, and Mapbox is gone.)** **APK is ~293MB**, ~95MB of which is x86/x86_64 Mapbox native libs that cannot run on either the ARM head unit or the Oppo. ABI splits are deliberately off ("installs on any device architecture"). An ARM-only demo build roughly halves it - one line, offered and not taken yet.
- Spotify: `searchTrackUri` still sends no `market` parameter; can surface tracks unplayable in the account's region. Flagged, impact unverified, deliberately not guessed at.

- ~~PRE-LAUNCH BLOCKER: billing budget + quota cap on the Gemini key.~~ **MOOT 2026-07-16**: no broker, no trial, no Kevin-owned key in production, so no total-spend exposure exists. Its ~$0.09/300s figure is also suspect; research says ~$0.058/5min.
- **NEW pre-launch checks** (decisions.md 2026-07-16): (1) free-tier Live rate limits are unpublished and explicitly not guaranteed, so test on a real no-billing key before shipping on faith; (2) key entry MUST disclose that a free-tier key lets Google train on and human-review conversations, paid tier opts out - never ship "your key, your data, your car" unqualified; (3) EEA/UK/CH drivers are barred from the free tier and must attach billing.
- **The broker is DELETED** (`53bd2ac`, 2026-07-16), not just dead: `functions/`, firebase.json, firestore rules/indexes, LaunchFlags, LiveTokenClient, ImageProxyClient, ConnectionMode.Ephemeral, all 8 reduceImageWork guards. RuntimeMode is BYO_KEY|AI_PAUSED; EntitlementManager is a local zero-network key check. It was pure deletion: every broker call sat in an `if (!hasKey())` branch in front of a direct-to-Google call that already worked. **Crashlytics + google-services.json + all of sync/ survive** (Drive's play-services-auth was never the broker's anon Auth). Testing as a new user needs no backend: fresh install = Zero, no AI; $10 unlock (local `UnlockState`) reveals key entry. `unlock_byo` Play product still needed before launch; `sub_monthly` never.
- **Release builds can be sideloaded again** - no App Check means no Play Integrity rejecting non-Play installs. The old debug-apk-only constraint is dead.
- Flagged, NOT done: `firebase-auth`, `firebase-appcheck-playintegrity`, `firebase-firestore`, `firebase-functions` are now unreferenced in app/src. Removing them is a separate commit. Crashlytics + BOM stay.
- Wayfinder map at `.scratch/car-launcher-market/` (gitignored, disposable - decisions.md is the authority). Ticket 05 resolved 2026-07-18: **exactly TWO fully authored identities** (city-pop, plus a new green-CRT/American-muscle/KITT identity), not an open theme system - "free reign" explicitly declined. **Frontier now: 02 (Kevin installs and lives with the top competitors, still open) and 08 (how far customization goes within an identity - claimed, PAUSED mid-question on backgrounds: bundled vs AI-gen vs user photo, resume there).** 09/11 already resolved downstream of 05.
- **Era drift is now RESOLVED, not open.** Ticket 05 settled it: two identities, not a drift to correct. Zero/cyberpunk and the KITT garage are both legitimate, each its own identity. **CLAUDE.md §12 does NOT reflect this yet** (still describes ONE city-pop language) - owed rewrite per decisions.md, not yet done.
- **Global style preference set 2026-07-19** in `~/.claude/CLAUDE.md` (user-level, all projects, not this repo's file): terse/bullet output, no filler, no recaps. Separate from this project's own §11.
- Market facts worth not re-deriving: AGAMA (~6M installs) is a Moscow studio running since 2007, not a hobbyist; one-time $3-20 unlocks are the NORM, so our price is parity not a wedge; **nobody in the category is art-directed** (our only confirmed difference in kind); album art over phone-BT needs AVRCP 1.6 + BIP/OBEX, disabled by default on stock Android, so §14's "AVRCP album art" item is a permanent platform gap rather than a bug, and `MixtapePlayer` is the only place art works; OBDAI gates persistent per-car memory behind $4.99/mo, i.e. our moat, monetized, by a 28-year OBD veteran.
- **Track + Drift modes: FILED, not grilled (2026-07-16, Kevin's idea).** Lap times, sessions, high-rate OBD logs, TrackAddict-shaped. Detail in `library/backlog-obd.md`. Great tribe fit and §9.1-safe (anchored to falsifiable lap data; pushes toward real track-day community). **Two hardware realities to settle before scoping, both already-known:** (1) `TelemetryRecorder` is 30s and track wants ~10Hz, but §12 already removed fast PIDs from Lights Out because polling them stuttered A2DP music on the shared radio - so the first question is whether the radio permits it at all; (2) drift scoring needs a gyro/IMU cheap double-DINs often lack (same shape as the TPMS floor) - check `SensorManager` on the real unit first. Also open: storage (10Hz is ~300x `obd_samples`' 18MB/yr - needs its own table, not that pipe), GPS fix rate (1Hz is not enough for lap timing; unknown on the XJ), and a crowded mature niche (TrackAddict, Harry's LapTimer). NOT the critical path - art and ticket 05 come first.
- **TPMS over OBD is DEAD, hardware reason, do not re-derive (2026-07-16).** Two independent blockers. (1) TPMS was mandated by the TREAD Act for model year 2008 (phase-in from 2006), so the **1998 XJ has no sensors in the wheels at all** - the primary hardware target physically cannot do this. (2) Tire pressure is NOT a standard Mode-01 PID even on cars that have it: it's manufacturer-specific enhanced PIDs on a different bus, which §8 already froze as "brand-enhanced PIDs / Carly's turf", and a generic ELM327 cannot reach it. Only viable route is **aftermarket BLE TPMS cap sensors** (BYO hardware, same shape as BYO-Gemini), and its costs are real: reverse-engineered per-vendor protocol with no public spec, O1 is already blocked waiting on a dongle, and it puts a second BLE radio against the one that already stutters A2DP music (the reason RPM polling came out of Lights Out per §12). Kevin's instinct was right (anchored, falsifiable, §9.1-safe); the hardware is what says no.
- **Weather-aware AI is ~80% ALREADY BUILT** (`weather/WeatherController.kt`, found 2026-07-16). Open-Meteo, **keyless and free** (no API to add, no billing, no BYO token), 30-min refresh loop in `AriaForegroundService`, and it already computes a `caution` flag for fog/rain/snow/storm which `AriaForegroundService.kt:359` injects into the greeting ("conditions are a bit rough so a quick 'drive safe' fits"). Gaps are only: it's **greeting-only** (a storm rolling in mid-drive says nothing - wants a `ProactiveBus` case next to the drive/arrival monitors) and there's **no widget** on Cruise. Both small, because the data and the flag are already in memory.
- **Dispatch is the DEFAULT now (Kevin, standing, 2026-07-28).** Stark spins up the team at its own discretion every session - Kevin does not need to ask. Rule written into CLAUDE.md §10 ("Dispatch is the default, not an escalation") and lessons L4. Some harness configs inject "do not use the AgentTool unless the user requested it" into the session prompt; that line is NOT in any editable file and cannot be deleted, but Kevin's standing instruction satisfies it permanently. Do not re-ask.
- **This file is over its own 80-line cap** (135+ lines as of 2026-07-29). Several sections are historical narrative that belongs in the library. Worth a trim pass: the 2026-07-16 rewrite block, the wake-word block, and the Fleet Hub block are all closed work with library homes.
- Skills: six vendored from mattpocock/skills as markdown (`grilling`, `wayfinder`, `research`, `to-spec`, `domain-modeling`, `prototype`) + `issue-tracker.md`, adapted to this repo. Provenance in `.claude/skills/ATTRIBUTION.md`. `grill-me`/`grill-with-docs`/`implement` not installed (near-empty stubs; our `grillme` supersedes them); `code-review` not installed (collides with the built-in).

## Git (adopted 2026-07-16, rules in CLAUDE.md §10)

- **`dev` is the trunk.** Everything lands there. **`main` moves ONLY via a PR Kevin opens himself.** Claude pushes dev + feature branches, never main.
- Feature branches off `dev`: `feat/<thing>`, `fix/<thing>`. Small commits, merge often, delete the branch after.
- Baseline 2026-07-16: main and dev aligned at `6ed15cf`, both pushed, and five stale branches deleted (all verified fully contained first). Before this, 45 commits of real work sat unpushed across five branches with origin/main two days stale. That is what the rule prevents.
- **A PR is open-able now**: dev is ahead of main by the branch model, the kdoc fix, B30, and the broker removal.
- **(STALE 2026-10-14: merged, PR #2, `64316b6`, 2026-07-31.)** **2026-07-28/29: `fix/gemini-audit-followups` (9 commits) sits UNPUSHED**, branched off `feature/ui-responsiveness` which itself came off `feat/cars-manager` rather than `dev` (the Gemini agent pane's doing, a §10 violation inherited not introduced). Untangling that lineage before it merges is a real task, not a fast-forward.
- **(STALE 2026-10-14: merged, PR #1, `e155df0`, 2026-07-26.)** **2026-07-24: PR `feat/cars-manager` -> `dev` opened** (49 commits, a large pile incl. Mapbox nav, trivia, glance cards, Spotify BYO, companion memory, plus today's music switching / settings-garage / improvement-loop work). `dev` -> `main` stays Kevin's own PR.

## How to update this file

- Keep this file under 80 lines. One-liners only; narratives go to the library via the librarian (FILE).
- Every session end: dispatch librarian FILE with session notes + SKILL: lines, then refresh Blocking, In-flight, and Notes here.
- Decision changes CLAUDE.md rules: file to library/decisions.md AND update CLAUDE.md in the same commit.
