# Decisions

> **STATUS: LIVE, with a frozen tail (banner added 2026-08-01).** This shelf still governs
> LEGION, but most of its volume predates the 2026-07-30/31 pivot and describes a head-unit
> car launcher. Trust the 2026-07-31 and later entries; treat everything earlier as Midnight
> AI history unless it is a language/framework fact that survives the platform change. See
> CLAUDE.md §11.


Frozen-list deltas since CLAUDE.md, strategy re-evals, external strategy session pointers.
Maintained by the librarian.

## External strategy sessions

- 2026-07-06: OBD market + behavioral moat (M365 Copilot) -> `docs/strategy/2026-07-06-copilot-session-handoff.md`.
  Reconciled 2026-07-07 against CLAUDE.md + memory/MEMORY.md; execution queue lives in the
  `c-users-kevin-downloads-midnight-ai-ses-synchronous-sketch` plan (package rename A1 shipped,
  A2 is a doc-ingest pass). See "2026-07-07 reconciliation" below for the 3 decisions it forced.

## 2026-07-07 reconciliation (from the Copilot-handoff session)

1. **Creator Pack IAP: killed before it ever existed.** The handoff proposed a $2 post-v1 IAP to
   remove a recap watermark. Rejected, reaffirms CLAUDE.md sec 2 ($5 one-time, zero billing code)
   rather than changing it. Not a reopen; recorded so nobody re-proposes it from the handoff doc
   later.
2. **Protocol scope: no gates, full OBD support.** The handoff proposed "CAN-era (2008+) only for
   v1," which would have dropped the 1998 XJ (the only hardware-validated car, pre-CAN ISO 9141).
   Rejected. The planned adapter-tier system (still greenfield) may record detected protocol for
   trust-weighting but must never exclude a car on protocol grounds.
3. **Package rename done: `com.kevin.nightrunner` -> `com.kevin.midnightai`.** Shipped 2026-07-07
   (commit `410955a`), source dirs `com/kevin/aria` -> `com/kevin/midnightai`. CLAUDE.md sec 3/15
   referenced the old package/dir names in prose until fixed during the 2026-07 memory-library
   migration.

## 2026-07-08 brainstorm: Spotify SDK and Mapbox re-evaluated under a BYO-creds framing

Nothing built, this is a strategy note, not a shipped change; CLAUDE.md sec 8 not yet edited as
of this writing. Insight: the app is already a harness for a user-supplied Gemini key (`KeyVault`
+ `GeminiKeyProvider`); could the same "you bring the credentials" model apply to Spotify/Mapbox,
the two services on the sec 8 frozen list for commercial-terms/cost reasons? Verified current
external terms via web search:

- **Mapbox — cost objection genuinely invalidated.** Nav SDK free tier = 100 MAU + 1,000
  trips/mo per account. A BYO public `pk` token means each user's own account absorbs their own
  usage, comfortably inside their own free tier; the original "$20-50k/mo at scale" kill reason
  evaporates ($0 to us). Residual objections are build-scope (full turn-by-turn/rerouting/
  geocoding vs. the Maps-intent's free equivalent) and account/token setup friction, not cost
  anymore. Real upside isn't cost, it's aesthetic control: the map could render inside the
  city-pop world instead of Google owning the screen with us reduced to the Companion Badge.
  Reclassified: "too expensive" -> roadmap candidate (large-scope aesthetic upgrade). Google Nav
  SDK / other embedded nav stay killed.
- **Spotify — optional add-on layered on top of existing BT/MediaSession streaming, never a
  required onboarding step.** Terms tightened since the original kill (Dev Mode 25->5 users,
  Premium required, 1 client ID; Extended Quota needs a registered business + 250k MAU, orgs only
  as of May 2025), but the reframe neutralizes the objections that mattered: the cohort that wants
  Spotify integration already has Premium, and "each user registers their own dev app + pastes
  their own client ID" sidesteps the 5-user cap (each user is the sole user of their own app;
  client ID is a runtime param in the App Remote SDK). Value over MediaSession (already gives
  transport/metadata/art free) is voice-driven content selection, "play my night-drive playlist,"
  search/browse. **Gating, unresolved:** does Spotify's Developer Policy actually permit this
  BYO-own-dev-app distribution pattern? Their Feb 2026 "Platform Security" update targets quota
  workarounds, so this is a real make-or-break, not a formality; resolve with a cheap
  policy-reading spike before any build. Reclassified: killed -> conditional roadmap candidate
  (optional add-on), contingent on that policy answer.
- **Shared foundation for either:** `KeyVault.encrypt/decrypt` is already generic (arbitrary
  short secrets, not Gemini-specific); generalizing into a multi-credential vault + a "Connect
  your services" surface is the cheap first build if either gets greenlit. Full analysis,
  sequencing, and verification plan: `~/.claude/plans/c-users-kevin-downloads-midnight-ai-ses-synchronous-sketch.md`
  (top section, prepended 2026-07-08; note this plan file is NOT part of the git repo, it's local
  planning scratch space, don't assume a future session can read it without being pointed here).
- **Update, same day:** the Mapbox half of this brainstorm was acted on later in the 2026-07-08
  session - Kevin explicitly reopened it (scoped to Mapbox specifically; Spotify stays a
  re-evaluated-but-not-yet-built candidate, Stadia/MapLibre/Google Nav SDK stay killed). CLAUDE.md
  sec 3/8/12/15 were updated in the same session as Phase 1 shipped (see below) - the "not yet
  done" note above is now stale for Mapbox, still accurate for Spotify.

## 2026-07-08 Mapbox embedded navigation: reopened and Phase 1 shipped

Following the brainstorm above, Kevin gave the explicit go-ahead to reopen embedded Mapbox nav
(BYO public token model - see the brainstorm's cost analysis, unchanged). CLAUDE.md sec 8's
Mapbox row updated same session from "❌ KILLED 2x" to a scoped reopen entry; Stadia/MapLibre/
Google Nav SDK split into their own still-killed row. Sec 3 (tech stack), sec 12 (new embedded-map
motion exception, alongside the existing AmbientWallpaper one), and sec 15 (codebase map - also
fixed a stale `NavPreferences.kt` line that never matched any real file, corrected to `NavState.kt`)
updated in the same commit per the "decision change updates CLAUDE.md in the same commit" rule.

Phase 1 (BYO-token infrastructure, fully offline-buildable, no Mapbox SDK dependency yet) shipped
this session - full implementation detail in `backlog-nav.md`. Phase 2 (SDK wiring, Drop-In nav
screen, geocoding, `start_navigation` branch) is blocked on a manual Kevin step (Mapbox account +
secret download token in `local.properties`) and on Cherokee validation of whether the head-unit
GPU can render Mapbox Maps v11 (requires OpenGL ES 3.0) - see `backlog-nav.md` for the full
blocked-items list.

## 2026-07-08 R1 resolved: Split-Screen Companion Mode killed, replaced by the Companion Badge

Approved after repeated real-world split-screen bugs (native mode covering the app with no
fallback, the panel not disappearing on Home, and fundamentally: no code-side fix can make an
overlay-based approach actually feel like a real split when the other app has no idea we exist).
Rather than patch further, removed native multi-window + the full-height floating panel entirely
and replaced with a much smaller floating companion badge: circular avatar (tap to talk,
phase-colored ring) + minimal music transport (prev/play-pause/next) below it, shown whenever
`start_navigation`/`open_music` opens an app full-screen. No settings picker, no side-app
assignment, always-on, automatic. Return to Midnight AI is via Home (already worked); the badge
now correctly hides on `MainActivity.onResume()`. CLAUDE.md sec 7 rewritten, sec 8 updated (2
entries: the CarPlay-reinterpretation row, and a new row recording native+panel as killed). Files
removed: `SplitLauncher.kt`, `AppTray.kt`'s `launchAdjacent`/`launchAppAdjacent`,
`CruiseScreen.kt`'s `CompactCruise` + `BoxWithConstraints` reflow + split-icon button + split
app-picker, `ControlPanelScreen.kt`'s Split-screen Appearance setting, `MainActivity.kt`'s
`onMultiWindowModeChanged`/`SplitState` tracking, manifest's `resizeableActivity`. Files added:
`CompanionBadgeController.kt` (was `CompanionPanelController.kt`), `ui/CompanionBadge.kt` (was
`CompanionPanel.kt`'s `CompanionHud`). Needs field verify, never tested on the physical unit.

## 2026-07-09 Ship-readiness assessment (point-in-time snapshot)

Filed to repo root as `ROADMAP.md` (commit 1a19d40). Verdict: core app code ~complete for v1,
almost entirely hardware-unvalidated. Firebase/crash-reporting was still a placeholder at time of
writing (resolved later same day, commit 2857be3). Sprint-3 content layer (chassis quirks, oil
analysis, Foresight nightly) assessed as dead scaffolding—not started. No Play launch collateral
exists yet. Distance-to-goal analysis split: reliable personal daily-driver = SHORT (verification
drive + fix pass + real Firebase wired); public $5 Play product = LONG. 7-phase roadmap defined in
ROADMAP.md with gating identified as "real Firebase + a clean verification drive of the killer
moment" (first Firebase half resolved 2026-07-10). Implications for sprints: Sprint 1/2 blockers
(B13-B17, B1/B2, B8/F4) must clear before Sprint 3 starts; current path is verification drive,
not more upstream fixes.

## 2026-07-09 Music tiers + nav-app picker + Spotify SDK personal-only flavor

Kevin requested multiple music options, nav-app configurability (driver picks Maps/Waze/other),
and explicit personal-only Spotify SDK control. This session resolved all three, and reopens
CLAUDE.md sec 8's Spotify SDK row (fully killed as of 2026-07-08) under a strict personal-only
scoped model. Decision logic:

**SPOTIFY SDK POLICY RESEARCH:** Spotify's Feb 2026 Developer Policy caps Development Mode at
one client ID, <=5 authorized users, Premium required, non-commercial personal use only. Shipping
the App Remote SDK + client ID inside the $5 Play Store app (distributed to unlimited commercial
users) violates this, regardless of runtime gating via a passphrase (the SDK/client ID themselves
are physically present in every public APK, bound to the public signing key). A runtime toggle is
NOT a solution. HOWEVER: a **Gradle product flavor split** is. Two separate builds = two separate
apps = two separate package names + signing fingerprints. The public `play` flavor (on Play Store,
$5, unlimited users) contains ZERO Spotify SDK code. Kevin's personal `personal` flavor (sideload
only, app ID `.personal`, signing key only on his machine) contains the full App Remote SDK + his
personal client ID (shared signing fingerprint + personal developer registration). This is
legitimate personal-use Dev Mode: one client ID, one primary user (Kevin), SDK is not in the
public distribution chain at all.

**ARCHITECTURE SHIPPED (Phase A):**
- `app/build.gradle.kts`: added `flavorDimensions += "distribution"` with `play` and `personal`
  product flavors. `play` = zero Spotify SDK, zero client ID. `personal` = applicationIdSuffix
  `.personal`, buildConfigField `SPOTIFY_CLIENT_ID` read from `local.properties` (previously
  orphaned), zero Spotify SDK dependency wired yet (Phase B).
- `media/SpotifyBridge.kt` (new, src/main/): interface boundary — `isAvailable()`, `connect`,
  `disconnect`, `playUri`, `transport`, `state: StateFlow<SpotifyPlaybackState?>`. References
  NOTHING from the Spotify SDK (pure Kotlin). Lazy singleton `SpotifyBridgeHolder`.
- `media/SpotifyBridgeFactory.kt` (Gradle flavor pattern, NOT Kotlin Multiplatform expect/actual):
  exists twice: `app/src/play/...` returns `NoopSpotifyBridge` (isAvailable=false, every method
  no-op, zero Spotify imports), and `app/src/personal/...` returns `PersonalSpotifyBridge` (Phase
  B impl; currently a no-op stub). Flavor-set resolution at compile time ensures `BuildConfig
  .SPOTIFY_CLIENT_ID` doesn't even exist to reference in play-flavor code.
- `media/MusicSource.kt`: added `SPOTIFY` enum variant alongside PHONE/MIXTAPE.
- `media/MusicRouter.kt`: added `Source.SPOTIFY -> SpotifyBridgeHolder.bridge.transport(...)` to
  all four transport methods. Safe to compile in every flavor because the bridge is a no-op
  except in personal, and Source.SPOTIFY is never actually set as active yet (Phase B).
- NEW music tier: `play_music(query)` voice tool in LiveToolbox.kt — fires Android's standard
  `MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH` intent at the preferred app (Maps/Waze/etc
  already use this pattern). Intent extras: `SearchManager.QUERY`. No SDK, no credentials, works
  in all flavors, surfaces result as normal PHONE-source MediaSession transport (existing `control
  _music` tool already works on it). Falls back to just opening the app if target doesn't declare
  play-from-search handler. This is the "Siri-style, launch app + play what the user asks" tier
  Kevin described.
- NAV-APP PICKER: `service/NavPreferences.kt` (new, all-flavors, plain SharedPreferences — mirrors
  `DebugSettings.kt` pattern). `ui/ControlPanelScreen.kt`: new `NavAppPicker` composable in Setup
  section (right before Mapbox block), using horizontal `ChipRow` (already established in same file
  for other pickers). Discovers installed nav apps via `geo:` intent `queryIntentActivities`,
  force-includes Maps/Waze even if they don't register geo: filters (rare build variants). Blank
  preference = unchanged original Maps-then-Waze fallback. `start_navigation` in LiveToolbox.kt
  checks `NavPreferences.preferred()` before original hardcoded resolve order.
- `AndroidManifest.xml`: broadened `<queries>` with `<intent><action android:name="android.intent
  .action.VIEW"/><data android:scheme="geo"/></intent>` to permit `queryIntentActivities` on geo:
  handlers under Android 11+ package visibility rules.
- Companion badge (floating avatar over launched app) already exists from prior session
  (CLAUDE.md sec 7); both `start_navigation` and new `play_music` reuse it as-is.

**PHASE B BLOCKED (on Kevin):** Register Spotify developer app against personal flavor's package
(`com.kevin.midnightai.personal`) + signing fingerprint, add personal client ID to `local
.properties`, add Kevin as an authorized Development Mode user (<=5 total), then add actual
`com.spotify.android:spotify-app-remote` SDK dependency + wire `PersonalSpotifyBridge` impl
(connect/playUri/transport/state via real SDK, call `MusicSource.set(Source.SPOTIFY)` on connect).

**PHASE C (future):** Spotify Web API search-to-play (App Remote can only play specific URIs, not
search by name; search requires OAuth + Web API, separate sub-phase).

**POLICY-CRITICAL VERIFICATION (MUST DO BEFORE MERGING TO MAIN):** After building, unzip the
`play` flavor APK and grep dex for `com/spotify` and the literal client ID string — both MUST be
completely absent. This is the proof the lock holds.

**CLAUDE.md UPDATES (same commit as Phase A):** sec 2 (Pricing notes the split is two non-tiered
apps, not an unlock), sec 3 (Music row rewritten for four tiers + routing, Nav row notes
NavPreferences), sec 8 (old Spotify row split into play/personal + policy math explained for each;
see decisions.md), sec 9 (guardrail: "Spotify SDK/creds never enter play flavor"), sec 15
(codebase map: added SpotifyBridge.kt, SpotifyBridgeFactory.kt, NavPreferences.kt, fixed stale
SplitLauncher reference in start_navigation/open_music).

**STATUS:** Phase A code complete, NOT build-verified (gradlew assemblePlayDebug / assemblePersonalDebug
not yet run). Nothing device-tested. Full detail: `backlog-music.md` (music tiers) + `backlog-nav.md`
(nav-app picker) + `playbook-coding.md` (flavor-split architecture gotcha).

## 2026-07-12 Dual-target app: single codebase, responsive reflow to head unit + phone

**DECISION:** One Kotlin/Compose codebase, compiles to two device profiles at runtime: fixed-landscape
head-unit (renders exactly as today) and phones (portrait + landscape responsive reflow via Material3
`WindowSizeClass` + a project-local `LocalSizeClass` CompositionLocal). Rationale: cheap head units are
slow and many people simply mirror CarPlay/Android Auto; phones give faster UX iteration; Kevin's
daily driver (2020 Outlander) has no head-unit screen, so a phone build is his live OBD test rig. No
new build flavors (play/personal unchanged for Spotify Dev Mode legitimacy). Constraints held:
Gemini Live session protection preserved (MainActivity configChanges remains for rotation without
WebSocket destruction); frame-clock motion rule (ui/Motion.kt) preserved; no new external dependencies.

**IMPLEMENTATION (in progress):** `WindowSizeClass` breakpoint logic (COMPACT reflows UI, MEDIUM/EXPANDED
keep today's layout). Includes additive phone feature: camera capture + gallery import for car photos
(reuses existing AvatarStudio.save* infrastructure, gated on `FEATURE_CAMERA_ANY` so head-unit stays
clean). Execution plan lives in the external harness file `velvet-sprouting-tide.md` (separate planning
project, not in repo). Being built commit-by-commit by the coding agent; builds/tests expected clean at
each step. Validation target: 2020 Mitsubishi Outlander (Kevin's daily, no screen, phone + ELM327 OBD
tests; linked to [[backlog-obd#BLE OBD ELM327 support]]).

## 2026-07-12 Android Auto deferred to backlog

**DECISION:** AA support deferred from roadmap to backlog. AA cannot host rich Midnight AI UI — Google's
Car App Library templates only (ListTemplate/PaneTemplate/GridTemplate: capped lists, ~4-row panes,
refresh-throttled), NO custom Compose, no avatar/gauges, NO third-party live voice (Google reserves the
mic for Assistant), and NO production "diagnostics" category exists for personal-use tooling. Verified
against Google docs (2026-07). This is a hard architectural mismatch with the brand; revisiting is
deferred to later.

**THIN-FACADE OPTION (future, if revisited):** (a) Media — promote MixtapePlayer's Media3 MediaSession to
a browsable MediaLibraryService + manifest browser intent-filter + Assistant onPlayFromSearch; surface
phone-PRE-RENDERED assets (mixtape covers, wallpapers, Wrapped poster) as browse + 2026 Spotlight-Section
imagery. (b) Parked "Wrapped/Recap" posters and OBD snapshot — 4-row OBD PaneTemplate + DTC ListTemplate,
personal-flavor-FIRST (no Play category fits public diagnostics). Key insight: pre-generate rich visuals
on the phone and serve them as IMAGE ASSETS in AA templates (works for cover/poster imagery and parked
Wrapped) BUT NOT for images-of-dense-text read while driving (distraction/accessibility rejection) and NOT
fullscreen custom canvases (AA places images in capped slots). Live voice/avatar/gauges permanently out of
AA regardless.

## 2026-07-13 Image-generation economics: trial + subscription budgets

**AUDIT:** Image-gen cost audit (via AvatarStudio, BackgroundGenerator investigation) exposed that
(a) one complete trial avatar = 8 API calls (generateConcepts + deriveAndSaveStates + derivePortraits lazy), but
trial allowance was 3 calls, making the first-run avatar unfinishable and breaking free-tier onboarding; (b)
subscription image costs were metered against the shared 300-min/mo voice budget instead of a separate cap,
causing silent voice starvation. See blocking.md for the full audit and fixes.

**DECISIONS (2026-07-13, Kevin approved):**

1. **Trial avatar = idle-only, ~3 calls.** Instead of generating 3 unique talking states
   (listening/thinking/speaking) per avatar as paid users do, trial avatars reuse the chosen face for all
   talk states, reducing cost from 8 to ~3 calls per avatar. Trades some visual variety for sustainability of
   the free first-run. A user who upgrades from trial to BYO key or subscription unlocks full portrait
   generation on next regenerate; existing trial avatars' portraits fill in lazily on next Cruise view. Not a
   deterministic bug—expected behavior per the product decision.

2. **Subscription images get separate monthly cap, not shared voice budget.** New constant
   `SUB_IMAGE_CALLS_CAP=24` (monthly image-call ceiling for subs, independent of the 300-min voice cap).
   `EntitlementManager.kt` mirrors this constant and tracks `subImageCallsRemaining` StateFlow;
   `canGenerateImage` on SUBSCRIBED checks the image cap, not voice minutes. Image generation on trial and
   subscriptions are now metered independently.

3. **Starting numbers: TRIAL_IMAGE_GENS=4, SUB_IMAGE_CALLS_CAP=24.** (1) Trial budget bumped from 3 to 4
   calls = one avatar (~3 calls) + one wallpaper (1 call). (2) Subscription cap sized at 24 calls/month,
   intended as cost-safe until Gemini image-gen and Live-voice rates are finalized before general subscription
   launch. Both constants hardcoded in config.ts (backend) and EntitlementManager.kt (client) and MUST be kept
   in sync by hand.

4. **Seasonal outfit generation trimmed from Phase 4.** Seasonal outfits (styledStates cache per occasion,
   ~4 calls per outfit) are expensive relative to the trial/sub budgets. The "Appearance" and "Occasion Stylist"
   surfaces that display them are already cost-safe (trial reduceImageWork() blocks them early-return); no
   functional regression. Trimmed from Phase 4 scope as a latent cost liability, not actively used by any
   caller in production yet.

5. **Keep token-tips card (Phase 4).** The ImageCostTips card in ControlPanelScreen's Plan section (links
   to Settings) stays in the implementation to educate users about image-gen budgets on metered plans. Not
   deferred; already wired (ControlPanelScreen.kt).

**DEFERRED (acknowledged but kept out of scope for this pass):**

- **Onboarding manager tri-modal redesign.** Kevin's proposal: per-step unified setup (tap menu + free-type + speak
  per field), one-tap trigger, non-destructive redo from settings. Today's all-voice or all-typed split is a
  product UX pattern question, not an economics issue; deferred.

- **Sprint 3 content layer identified as retention moat but gated behind verification drive.** Chassis-quirk YAML
  bundling, oil-analysis trends, Foresight nightly aggregation (Gemini-as-reasoner over obd_samples) are the
  claimed per-car differentiation. Currently dead scaffolding (CLAUDE.md §3 lists them, code structure exists,
  no first-run validation or meaningful data collection yet). Blockers clear before Sprint 3 starts; chassis quirks
  are the immediate next feature area (see backlog-obd.md and Sprint 3 in sprints.md).

- **Possible retention surface: on-device "time together" (days/miles/drives, streaks).** Zero-token, on-device
  only. Noted as a cheap engagement idea, deferred to backlog.

**VERIFICATION:** Functions not deployed yet, so trial/subscription metering only live end-to-end after Firebase
Functions deploy. Monthly reset for both image and voice caps is Phase B subscription-period work, NOT built (subs
not live yet, no regression). Until monthly resets are live, budgets reset only on subscription renewal (harder
to test, caught by subscription-lifecycle integration tests later).

## 2026-07-14 Launch posture: BYO-key only, hosting roadmapped

Kevin's call: **launch with bring-your-own Gemini key only; the hosted freemium layer (Firebase
entitlement broker - free-trial voice/images + $8/mo subscription) is roadmapped, not immediate.**
The freemium architecture (CLAUDE.md §2 + the 2026-07-13 economics decision above) is built and
code-complete, and the broker (`functions/`) is deployment-ready (the mintLiveToken ephemeral-token
REST shape was verified 2026-07-14 against Google's current AuthToken reference: authToken wrapper,
uses/expireTime/newSessionExpireTime + direct bidiGenerateContentSetup, token in response.name,
v1alpha authTokens) - but NOT deployed. Rationale: skips the hosting cost/complexity + the
App-Check-on-debug-builds hassle for launch; a free base app means requiring a key no longer
suppresses installs.

Gated by `billing/LaunchFlags.kt` `HOSTING_ENABLED = false` (deliberately non-const so the freemium
branches aren't folded to unreachable/dead code while off). With it off:
- `EntitlementManager.init` skips the whole Firebase-broker stack for a no-key install (no anon auth,
  no Firestore listener, no doomed mintLiveToken/proxyImage). Mode stays AI_PAUSED - the base app is
  fully usable; AI actions prompt to add a key.
- The Plan screen (`ControlPanelScreen.PlanSection`) shows the Gemini-key entry OPENLY: no trial
  budget line, no "$8/mo coming soon", no $5-unlock gate on the key field. Entering a key ->
  `EntitlementManager.refresh()` -> BYO_KEY -> full access.
- BYO-key drivers are unaffected either way.

To ship hosting later: deploy `functions/` (set the GEMINI_API_KEY secret, register App Check + a
debug token for sideloaded builds, smoke-test mintLiveToken opening a real Live socket) and flip
`HOSTING_ENABLED = true`. The freemium UI + broker paths return with it; no code was deleted.
Committed 1ddcd88; CLAUDE.md §2 carries a matching launch-posture note.

**REVISED same day (2026-07-14): launch WITH the trial, not BYO-only.** Kevin: "$5 unlock gates the
key but we provide a trial with limited tokens." A trial can only run through the broker (metering
free tokens on Kevin's key - baking a shared key into the app ships it to everyone, §9), so the trial
IS the hosting; "trial + defer the broker" can't both hold. The launch model is therefore the full §2
freemium: free base -> limited trial (300s voice / 4 image-gens) -> $5 unlock reveals BYO key -> own
Gemini key. `HOSTING_ENABLED` flipped back to true; the onboarding FinalStep no-key key-prompt is now
gated to the flag-false (BYO-only) mode so trial users aren't asked for a key. To make the trial
functional Kevin deploys the broker (still his manual step: secret, App Check, deploy). New config
`functions/src/config.ts` `ENFORCE_APP_CHECK` (default true) can be flipped false to TEST the trial on
a sideloaded build - Play Integrity can't attest an APK not installed from Play, so it would otherwise
reject every callable on the head unit - then re-enabled before a public launch. This supersedes the
BYO-only-launch entry above; that BYO-only path still exists as the HOSTING_ENABLED=false interim.

## 2026-07-14 Cross-device sync (BYO-cloud, Google Drive) - CLAUDE.md sec 8/9 reopen

Kevin wants persistent car data shared across the head unit and a phone app: drive -> head unit
records OBD/metrics/mixtapes -> reviewable and generatable from the phone (prep a mixtape at home,
ready in the car) and vice versa (review OBD, share recaps from the phone). This is asynchronous and
cross-location (the two devices are rarely on the same network at the same time), so it REQUIRES a
cloud intermediary - direct device-to-device won't deliver the prep/review flows. That reopens the
sec 9 "car data never leaves the device" guardrail and the anonymous-only/no-account stance.

Decision (Kevin, 2026-07-14): **BYO-cloud, the driver's own Google Drive `appDataFolder`** (option A
of A=BYO-cloud / B=Kevin-hosted / C=direct-P2P). Rationale: only A both fits the async use case AND
keeps the on-device/BYO promise - Kevin never holds car data, pays nothing, sees nothing; same shape
as BYO-Gemini and BYO-Mapbox. B (extend Kevin's Firebase to store per-user car data) was rejected: it
makes Kevin custodian of everyone's OBD+location+logbook history (privacy liability, storage cost, and
the exact fleet-data temptation sec 9 forbids). C keeps the guardrail but only works when both devices
are co-present, which is when you don't need it. Drive chosen over BYO-Firebase because asking a
consumer to create a Firebase project is a non-starter; Drive they already have, and same Google
account on both devices = same hidden appDataFolder = the cross-device link for free (no account
system Kevin builds; the existing anon broker auth is separate and untouched). Kevin confirmed the
Cherokee head unit has working Play Services and can sign into a Google account.

Scope, first cut: **light data only.** Syncs every DB table's ROWS (incl. mixtape/album definitions +
generated cover paths + recaps/Wrapped). Media BYTES stay per-device (sideloaded mixtape audio, build
photos, avatar/cover images) - a synced mixtape arrives as track list + cover; audio plays where the
files live; covers can regenerate on the phone (a metered image-gen, so it still respects
EntitlementManager). Surface this limitation in the UI, don't hide it.

Design (approved shape, build order commit-by-commit, each gradlew assembleDebug + tests green):
1. Docs: this entry + CLAUDE.md sec 8 row + sec 9 second scoped-exception bullet (THIS commit).
2. Room v6 -> v7 additive migration: `updatedAt` on the mutable tables (vehicle, car_task,
   maintenance_item, vehicle_spec, reminder_items, mixtape, mixtape_track, library_track,
   chassis_quirks) for last-write-wins; verbatim generated SQL + schemas/7.json + MigrationTest.
3. Identity: Google Sign-In (Credential Manager) + `drive.appdata` scope + `SyncCapability` gate
   (Play Services + signed in + scope granted, mirrors NavCapability) + Setup opt-in UI (mirrors the
   Mapbox token entry). Separate from the anon broker auth.
4. `SyncEngine`: per-table gzipped-NDJSON files in appDataFolder. Merge rules by table nature -
   append-only high-volume (obd_samples, music_plays) = union by key, MONTHLY shards so only the
   current month re-uploads; append-only low-volume = union; mutable = LWW by updatedAt. Triggers:
   app foreground, drive-end (TelemetryRecorder knows), manual "Sync now".
5. Recap/Wrapped share from the phone (Kevin's explicit ask; poster export already scoped Sprint 6).
   Lightest slice - build the identity+sync plumbing against it first.
6. Media-bytes layer: deferred, separate decision.

Guardrail note: this is now the ONE sanctioned way car data leaves a device, and it lands only in the
user's own cloud. Still no Kevin-hosted car data, no comparative/fleet data, ever.

### Consistency model (2026-07-14, refines the above)

Kevin flagged the real hazard: BOTH devices can independently pre-populate data (each records OBD, each
can have its own companion/avatars, its own logbook). First connect must RECONCILE, never blind-overwrite.

Core insight: local autoincrement `id`s are NOT portable (both devices start at 1), so `id` can never be
the cross-device identity. Two merge classes:
- **Append-only events** (obd_samples, music_plays, code_events, service_records, build_entries, memories,
  oil_analyses, recaps): UNION - insert if the row's identity is unseen. Both devices' histories genuinely
  merge into one timeline.
- **Mutable singletons** (vehicle, maintenance_item, vehicle_spec, car_tasks, place_reminders, places):
  LAST-WRITE-WINS by `updatedAt` (the v7 column).

Identity per table:
- **Natural stable key -> key on it, no syncId**: vehicle (obdMac), maintenance_item (obdMac+serviceName),
  vehicle_spec (obdMac), chassis_quirks (quirkId slug), places (label PK), obd_samples & music_plays
  (obdMac+timestamp+pid/title - also skips a UUID to save space at ~1M rows/yr), recaps/logs (their period).
- **No portable natural key -> add `syncId` (UUID)**: memories, service_records, build_entries, code_events,
  oil_analyses, car_tasks, place_reminders. Assigned at row creation (SyncEngine backfills legacy blank ones
  before first export). This is the v7->v8 migration.

Two conflicts that fit neither rule, decided with Kevin:
1. **Companion identity clash** (name/persona/voice is a single global, can't union): ONE-TIME CHOICE on first
   conflicting connect ("keep this device's companion, or the one already in your Drive?"). Never silent
   overwrite. Companion identity syncs as a small `companion.json` (LWW timestamp + this choice on clash);
   the avatar IMAGE bytes stay per-device (media deferred), so the face may lag the identity until regen.
2. **Mixtapes DEFERRED to the media phase (step 6).** They're relational (mixtape_track references library_track
   by local id, which breaks cross-device) AND need the audio bytes, which don't sync in the light cut - a
   synced mixtape would be an unplayable empty shell. So mixtape/library_track/mixtape_track are OUT of the
   first sync cut; they sync later with the files. (This is why v7's updatedAt on those 3 tables is currently
   unused - harmless, ready for step 6.)

Same-car caveat: "same car" is keyed by OBD MAC today, so the same physical car seen via two different dongles
reads as two vehicles. Cross-device sync surfaces this but doesn't newly break it; the planned VIN-keying
(CLAUDE.md sec 5) is the eventual dedup. Out of scope for the first cut.

SyncEngine (step 4) will carry a per-table registry: {identity = natural-key | syncId, mode = union | LWW}.

## 2026-07-15 Garage/gate control transport and auth

Feature: voice + touch control of a garage/gate opener from the head unit.

**Chosen (v1):** A Shelly Gen2+ relay mounted AT THE GARAGE, wired across the opener's wall-button
(low-voltage) terminals, on the user's home WiFi. Controlled through the Shelly Cloud Control API (v2
`POST /v2/devices/api/set/switch`) over the phone hotspot, so the head unit never switches networks.
BYO auth: the user's Shelly "Authorization cloud key" (auth_key) + account server host, pasted in Setup,
stored via `CompanionProfile` (KeyVault-encrypted, same pattern as the Gemini key and Mapbox token).
Action-only, single-button TOGGLE: one voice tool `activate_garage` (params door, confirmed), confirm-gated
in code on voice (confirmed two-step) and by dialog on touch; name-targeted multi-door with a settable
default. Cruise GARAGE tab + Setup section. No Room schema addition needed.

**Architecture:** a thin `GarageOpener` interface seam; `ShellyCloudOpener` is wired; `ShellyBleOpener`
(car-local BLE transport, built first) is retained UNWIRED behind the seam as a future no-cloud option.
`GarageDoorConfig` is transport-shaped (a generic `deviceId`). She says "triggering", never
"opening/closing", because a single-button toggle cannot know direction without a sensor.

**Discarded options and why:**
- **Genie Aladdin Connect cloud (BYO email/password):** DEAD. Genie moved to an OAuth partner-only API;
  end users cannot obtain OAuth client credentials; Home Assistant now requires a Nabu Casa cloud proxy.
  Incompatible with BYO/no-backend.
- **Raw RF clone remote:** rolling-code doors cannot be cloned, and the head unit has no RF emitter.
- **Shelly local LAN HTTP:** would force the head unit to switch from phone hotspot to home WiFi.
  Rejected on UX.
- **Car-local BLE to a Shelly wired across a spare remote:** BUILT FIRST, then superseded. Needed soldering
  across a sacrificed remote plus a car-mounted unit; too much install friction. Code kept unwired for
  future.
- **Off-the-shelf alternatives:** SwitchBot (BYO-cloud, easy install, not chosen), Remootio (clean dev
  API, pricier, garage-mounted), H&A Open Sesame (closest to the "HomeLink in a box" wish but proprietary
  app and unreliable rolling-code cloning).

**Guardrail note (§9):** BYO-cloud shape (user's own Shelly account and key, Shelly's servers), not
Kevin-hosted, consistent with BYO-Gemini/BYO-Mapbox. Not on the frozen list. Zero-token, does not touch
the entitlement broker (not a Gemini call).

**Deferred:**
- Door status ("is it closed?") via a reed switch on the Shelly input, which re-adds a `get_garage_status`
  tool and lets responses go directional.
- Proactive geofenced hourly "door left open" alert.
- Second brand/second account support.
- An OBD-manager-style brand-management UI.

## 2026-07-15 Active-time voice billing (S1 sync branch, dormant until broker deploy)

**DECISION:** Broker-metered trial and subscription voice usage now bills only ACTIVE talk/speech time via a pure `VoiceUsageMeter` (startSegment/pause/stopAndReportSeconds), not wall-clock duration from socket open to close. The 3-minute warm-idle session tail (kept alive for instant resume on tap) and pre-warming sockets consume zero budget. BYO-key users (Google bills their key) are unaffected—Google already meters per audio token, which is inherently active-only. This fixes the trial burning ~3 minutes of a 5-minute budget for a 10-second question when the session idles warm afterward.

Implementation in `billing/VoiceUsageMeter.kt` + integration into `GeminiLiveSession` lifecycle. Dormant until the entitlement broker (functions/) is deployed. Kevin must ensure the backend's `reportUsage.ts` only bills active seconds, not socket wall-time.

## 2026-07-15 Broker test-prep and trial-flow testing

**DECISION / IMPLEMENTATION CHECKLIST:**

To test the freemium trial end-to-end, the Firebase Functions broker (`functions/`) must be DEPLOYED:
- `GEMINI_API_KEY` Cloud Functions secret configured
- Anonymous Auth enabled
- App Check (Play Integrity + debug token for sideloaded builds)
- `firebase deploy`
- Smoke-test `mintLiveToken` callable opening a real Gemini Live socket

**Debug mode for trial-flow testing (sideloaded/emulator APK without Play Integrity):**
- `functions/src/config.ts`: flip `ENFORCE_APP_CHECK = false` (default true) so App Check doesn't reject sideloaded debug builds
- Run debug trial + subscription flows
- MUST flip `ENFORCE_APP_CHECK = true` before public launch
- Only DEBUG apks can test the trial this way; the real unlock_byo Play Billing product testing requires Play Console (a launch-phase todo)

**Debug unlock button (BillingManager):**
- Added `debugGrantUnlock()` to BillingManager, gated on `BuildConfig.DEBUG`
- "SIMULATE $5 UNLOCK" button in ControlPanelScreen's Plan section (DEBUG builds only)
- Allows trial-to-BYO-key flow testing without a Play Billing product integration
- Removed before release

## PENDING GRILL 2026-07-16 — Theme system + coherence-law tension

**UNRESOLVED IDEA, NOT DECIDED.** Kevin's brainstorm (field-test 2026-07-15 brain dump): treat the app as a genuinely useful TOOL first, with city-pop as a THEME layered on top. Build a theme system so city-pop is one skin among potential others (dark, light, minimal, etc.). Framing: "this is a genuinely useful tool, and the city-pop theme should be a theme layered over the useful tool." This idea is in real tension with CLAUDE.md sec 12's coherence law ("every surface reads like a frame from the same 1988 city-pop cassette broadcast") and the "ONE cohesive material, ruthlessly consistent" rule. A selectable theme system could fragment that cohesion. Also compounds the home-screen customization idea (backlog-cruise.md) - freely movable widgets + user photos + multiple themes could push the design into maximalist-personalization territory, opposite the Stardew "out-charm, don't out-polish" principle. This is the biggest open design question in the brain dump. Marked PENDING GRILL for architecture/design review.

## 2026-07-16 Pricing rewritten to two tiers: zero trial, zero backend

Kevin's call: **the 2026-07-14 freemium model (free base + metered trial on Kevin's key via Firebase broker + $5 BYO unlock + $8/mo hosted sub) is dead. New model: free forever with bundled canned voice, $10 one-time unlock for conversational AI.**

**Tier 1, free forever, no backend:**
- **Includes:** launcher, Lights Out, live OBD, DTC read AND clear (full capability, never gated), mixtapes + Media3 player, logbook basics, palettes, bundled curated Zero avatar + wallpaper set, canned voice lines (pre-generated once on Kevin's key, shipped as WAVs in res/raw). Zero (the mascot) speaks pre-recorded responses to driving moments.
- **Excludes:** conversation, persona editing, voice choice, AI generation, OBD history + trends, Drive sync (S1), garage (G1), Mapbox (N1), sub-agent diagnosis, Recap/Wrapped prose.

**Tier 2, $10 one-time unlock (Play Billing `unlock_byo` product):**
- Reveals the BYO-key entry. Driver's own Gemini key, unlimited, no further charge from Kevin. Unlocks all excluded features above.

**Why (this is what survives in the record):**
1. Market research killed the founding premises: cheap one-time unlocks are the norm (AGAMA ~$3-5, Car Launcher Pro $6.99, CarWebGuru ~$3-19.99). $5 was parity with the market's ceiling, not a wedge.
2. **The trial was the ONLY reason the backend existed.** Trial can only run through the broker (baking a shared key into the app is a §9 non-starter). Kill the trial, the broker has no purpose.
3. **Killing the trial removes the ONLY mechanism to lose money.** Marginal cost per install = $0. No break-even, so conversion is a vanity metric, not a survival metric. Kevin's stated fear ("many installs, 0 conversion, my cost") is fully answered.
4. Kevin reframed this as "just a portfolio hobby project," not an income source. That changes everything. The launcher category is traffic-rich and money-poor (per ticket 10 market research) - a feature, not a bug, when optimizing for installs.
5. Arithmetic: at ~$0.17/fully-used trial + 15% Play fee, $5 needed 4.0% conversion to break even (above the 2.6-5.8% industry range). $10 needs 2.0%. The old trial might never have paid for itself. Moot now (no trial), but it is why $10 not $5.
6. **Paywall is identity, boundary draws itself.** Free users cannot customize deeply; every powerful customization (generation, persona, voice, conversation) is Gemini-dependent and therefore already behind the key. VERIFIED IN CODE: `CompanionProfile.persona()` read-only except in editors displaying it back, nothing wired to nothing without a key. OBD history deliberately gated (moat surface; OBDAI charges rent for a shallower version and gets 4.5 stars). Mixtapes deliberately NOT gated (cassette = half the killer moment; AVRCP/BIP finding means local Media3 is the only place album art works reliably; gating would push free users to phone-BT where art is broken).
7. The subscription's own record: ticket 01 said kill it; tickets 03/10 said keep it (OBDAI charges $4.99/mo for AI tier at 10k installs, 4.5/5 stars with no resentment). Reconciliation held: this market resents subs for LAUNCHERS, accepts them for INFERENCE/MEMORY. Ours would have been the second kind. BUT: it died anyway on cost/maintenance, NOT market sentiment. Record this explicitly so a future session doesn't "rediscover" OBDAI and think the decision was made in ignorance. Also killed: keeping dormant code (dormant code is code you maintain).
8. **This cost the cleanest possible privacy claim.** "YOUR CAR DATA never touches our servers" was already true (§9); what the sub cost is the absolute version. Now literally true, not mostly true - marketing weapon in a market with documented sub fatigue.

**What died.** `functions/` (Firebase entitlement broker), `LiveTokenClient`, `ImageProxyClient`, `EntitlementManager`'s broker path, App Check / Play Integrity surface, `VoiceUsageMeter`'s billing role, `sub_monthly` product. All dead code; deletion is a follow-up commit. `UnlockState` survives (LOCAL, unrelated to any broker). `BillingManager` survives for `unlock_byo` one-time product.

**Conversion mechanism (decided).** Zero sells the unlock herself, in character, at moment of desire: canned line out loud, real number on the cream caption bar (canned audio cannot say numbers), frequency-capped, living where the wanting happens not in settings. Plus bundled demo WAV of a real conversation + locked generators shown with generated examples.

**Paywall copy must answer "I pay you $10 AND Google?"** Answer: arithmetic. OBDAI is $4.99/mo = $120 over two years. This is $10 once, then Google directly at cost, no markup, no monthly bill.

**OPEN DEPENDENCY, RESOLVED 2026-07-16 via market research.** Source: `.scratch/car-launcher-market/research-gemini-pricing.md` researched against Google's own docs and terms (fetched 2026-07-16). Findings:

1. **Model is viable.** Free-tier Gemini key with NO billing account CAN open Live API at $0 marginal cost. Google's pricing page (`ai.google.dev/gemini-api/docs/pricing`) lists explicit "Free of charge" column for Live models. Drivers do NOT need to attach a credit card to Google Cloud. This risk could have sunk the two-tier model and did not materialise.
2. **CAVEAT to pre-launch checklist:** Exact free-tier Live-specific rate limits (RPM, RPD, concurrent sessions) are NOT published in a primary-source table. Google states the real number is whatever AI Studio console shows live and explicitly that published limits are not guaranteed. MUST be tested empirically on a real no-billing key before shipping on faith.
3. **Privacy hole is real and must be disclosed.** Gemini API terms (`ai.google.dev/gemini-api/terms`) verbatim: free tier, Google "uses the content...and any generated responses to provide, improve, and develop Google products" and "human reviewers may read, annotate, and process your API input and output." Paid tier: "Google doesn't use your prompts...or responses to improve our products." Audio is not treated differently from "content" and "generated responses" generally, so free-tier live voice conversation audio is included. CONSEQUENCE: "your car data never touches KEVIN's servers" remains TRUE (§9 holds). "Nothing leaves your control" is FALSE for free-tier - Google may train on and human-review conversations. DECISION: disclose plainly in key-entry flow. Free key means Google may train on and review; attaching billing opts out. Being the only product in the category honest about this is on-brand and costs nothing. Do NOT ship "your key, your data, your car" as unqualified marketing.
4. **Region fork:** EEA / UK / Switzerland drivers barred from free tier entirely by Use Restrictions clause. Must attach billing, which is a bigger onboarding wall but automatically grants paid tier's no-training commitment. Irony worth recording: European drivers get strictly better privacy posture + strictly worse onboarding.
5. **Rates (two corrections to MEMORY.md numbers).** Image: $0.0387/image (Kevin's ~$0.04 estimate was right). Voice: $0.005/min audio-in + $0.018/min audio-out. A 50/50 5-minute split = ~$0.058, notably BELOW the ~$0.09/300s in MEMORY.md. Gap unexplained, probably hinges on whether input audio bills continuous mic or only active speech. UNCONFIRMED, do not quote $0.09 as fact. "$5 lasts a few months" is TRUE ONLY at light use: ~2.9 mo at 5min/day, ~1.4 mo at two 5min drives/day, ~two weeks for chatty daily commuter. Kevin's claim was optimistic, not wrong. Do not put "a few months" in marketing without usage qualifier.
6. **Clean:** No ToS clause prohibits BYO-key architecture (end users supplying own keys to third-party app). Verified directly against terms.

## 2026-07-16 The mascot is Zero, androgynous, Ghost-in-the-Shell-meets-city-pop

Kevin's call: **Yoko (formerly Kaze) is renamed Zero. Androgynous, 90s cel anime, ships in two curated variants (male-presenting and female-presenting) each with matched canned voice. Zero is a companion who rides along. The driver's own avatar, when they unlock and create one, is the car.**

**Identity is the paywall, and the shape of it is the upgrade story.** Free tier: "meet Zero," a companion character who rides with you, notices things, speaks canned pre-generated lines. Paid tier (unlock): "your car itself speaks," with your own custom avatar as the vehicle's face, your name, your persona. The C1 first-person car-self reframe is the paid payoff, not the default—it is what you unlock, not what you start with. Resolves the code inconsistency: `OnboardingState.kt:65` seeded `"You are Yoko - warm and easygoing..."` while `VehicleController.MOOSE_PERSONA` (fallback) said `"You are Midnight, a 1998 Jeep Cherokee. Your personality is an old, lovable curmudgeon..."`. The two were in conflict because they were trying to be the same thing. Zero is neither of those—Zero is the companion. When a driver unlocks, their car gets a voice via their own avatar (the first-person reframe).

**Free drivers cannot rename Zero.** Not because "nobody has named their car yet," but because Zero is a fixed bundled character with bundled canned voice lines and a bundled avatar set. A renamed/re-personalised Zero speaking canned Zero voice lines would be incoherent. Brand consistency plus canned-audio validity.

**The male and female variants share one persona.** Presentation differs, character does not. Zero's character is written and unified: `VehicleController.DEFAULT_PERSONA` is the single source of truth. `OnboardingState.seedDefaults` now seeds it VERBATIM by reference rather than holding a second string, fixing the inconsistency above.

**Zero's character (Kevin's reference).** Motoko Kusanagi (composure, economy, quiet competence) + Judy Alvarez (directness, warmth, no games) + Panam Palmer (bluntness, grit, gets invested). Explicitly **"not too yandere but not too tsundere either."** Record why the negative constraint is load-bearing: an LLM drifts into yandere and tsundere companion tropes by default, so "never coy, never possessive, never sulking or running hot-and-cold for attention" does more work than any positive trait. Do not let a future session trim it as redundant.

**Hard rule, record this: canned line may NEVER speak the companion's name.** All seven FIRST_GREETING_LINES already obey it: "Hey. First drive together.", "Well, hello. Good to finally meet you." - not one says "Yoko". This keeps bundled audio valid across any rename and is why this rename cost almost nothing.

**Bundled avatar set must be Zero VARIANTS, not different characters.** Outfits, seasons, poses. `OccasionStylist` already does seasonal outfit + time-of-day tint. Free user picking a different CHARACTER while Zero voice comes out would be incoherent.

**Timing was lucky.** The pro illustrator ref sheet ($300-600) was uncommissioned. The redirect was free.

**Art direction rewritten (do this before any Zero art generates).** `AvatarStudio.STYLE_DNA` + `PORTRAIT_PROMPT`: now "1990s Japanese cel anime in the Ghost in the Shell register: flat cel shading, bold clean lineart, androgynous features, a composed and level gaze, understated near-future styling. Never moe." Keeping the amber/magenta/cyan night palette. The old "large expressive reflective eyes" and "era-appropriate 80s city-pop fashion" were Yoko's lofi-girl look. PORTRAIT_PROMPT's fashion line is now "understated near-future techwear."

**The character runs cooler than the surface, deliberately.** The night palette is the bridge. CLAUDE.md §12's coherence law is UNCHANGED: the surface stays Hiroshi Nagai / Eizin Suzuki late-Showa city-pop. Only the character moved. §12's `3.png` is now the MEDIUM reference (90s cel anime) only, not the character reference, since it anchored Yoko. A new Zero character reference is still needed; the illustrator ref sheet remains parked and uncommissioned.

**Naming-history note.** This project has renamed a lot (Moose, Aria, Kaze, Nightrunner, Yoko, now Zero; `com.kevin.aria` to `com.kevin.midnightai`). Stale vocabulary is a live hazard. `MOOSE_PERSONA` is itself a stale name. Flag any shelf still saying "Yoko" and fix it in this pass.

## 2026-07-16 Position as a LAUNCHER. The companion is the upgrade.

**What changed.** CLAUDE.md §1's "Product" line. Was: "the car that knows itself. AI companion grounded in live OBD + compounding per-car history + late-Showa city-pop aesthetic." Now: an Android head-unit **launcher that upgrades into an AI companion**, positioned and sold as a launcher. Tagline "the car that knows itself" survives.

Store listing, keywords (car launcher / head unit / double DIN / AOSP) and screenshots lead with Cruise and Lights Out, with the one confirmed differentiator from market research: **nobody else in this category is art-directed**. The AI is what you find inside, not the headline.

**The pricing rewrite (decision 1) had already answered this and nobody noticed.** Free IS a launcher: Cruise, Lights Out, live OBD, DTC read and clear, mixtapes, palettes, Zero noticing things, no conversation. That is AGAMA's product with better art. Paid is a companion. The product already WAS "launcher that upgrades into a companion" before the question got asked. This is why the decision was cheap—the product shape was already right.

**Why it holds.**
- Free tier genuinely stands alone as launcher. That makes a launcher pitch HONEST, not bait-and-switch.
- "Car launcher" is a shelf people search; "AI car companion" is not a category anyone browses.
- Ticket 10's "launcher category is traffic-rich and money-poor" is VOID as objection. It was an argument about revenue; Kevin stated this is a portfolio project. Traffic-rich is now purely good.
- BYO-key forces it anyway. Nobody in a mass launcher audience pastes an API key, so we cannot serve AGAMA's six million the AI regardless. Pitching the AI would promise what most installs never get; pitching the launcher promises exactly what everyone receives.

**Risk accepted, record explicitly.** Launcher pitch invites feature comparison with AlterGames (Moscow studio, 2007+, three brands, ~10M game installs, industrial electronics). We lose that comparison on features and win on looks. **Do not fight them on features.**

**Ticket 02 (install and live with competitors) did NOT block this in the end.** It was blocking because "their themes suck" was aesthetic judgment only Kevin could make. Market research killed that and replaced it with a verified one (nobody is art-directed) needing no hands-on judgment. Ticket 02 survives narrower purpose: establishing whether AGAMA is good enough that competing on looks is actually hard.

**Consequence that reframes the open work, most important to file here.** If positioning on looks in a category where nobody has any, then **having ONE authored look is the entire wedge, and a theme system means having no look.** The open question is no longer "city-pop, or one skin among many" but "does this app have a single authored aesthetic, whatever era it lands in, or a theme system?"

The era itself is now genuinely open, which nobody decided on purpose: Zero went cyberpunk/Motoko (decision 2), and the settings garage went Tokyo Drift (2026-07-16), so **late-Showa city-pop is drifting into the minority one reasonable decision at a time.** Ghost in the Shell and Tokyo Drift cohere with each other; §12's city-pop surface is now the outlier. §12's coherence law ("ONE cohesive material. Ruthlessly consistent") is not yet violated but under real pressure. **Flag prominently: a future session must not "restore" city-pop or "embrace" cyberpunk without Kevin deciding it.**

## PENDING GRILL 2026-07-16 — Theme system + coherence-law tension

**UNRESOLVED IDEA, NOT DECIDED.** Kevin's brainstorm (field-test 2026-07-15 brain dump): treat the app as a genuinely useful TOOL first, with city-pop as a THEME layered on top. Build a theme system so city-pop is one skin among others (dark, light, minimal, etc.). Framing: "genuinely useful tool, city-pop should be a theme layered over it." Real tension with CLAUDE.md sec 12's coherence law ("every surface reads like a frame from the same 1988 city-pop cassette broadcast") and "ONE cohesive material, ruthlessly consistent" rule. Selectable theme system could fragment that cohesion. Compounds home-screen customization idea (backlog-cruise.md) - freely movable widgets + user photos + multiple themes could push design into maximalist-personalization territory, opposite Stardew "out-charm, don't out-polish." Biggest open design question in the brain dump. Marked PENDING GRILL for architecture/design review.

## PENDING GRILL 2026-07-16 — Monetization rethink: gate customization + sync behind paywall (SUPERSEDED by 2026-07-16 pricing decision above)

**ARCHIVED, not a grill anymore.** 2026-07-15 concern: current model gates only AI, giving away too much customization. Gate SOME customization + Drive sync + recaps behind unlock instead. Free keep: read OBD codes, basic customization (palette, gauges), maybe 1 sample daily recap. This idea reopened sec 2 pricing model. Rejected by the pricing rewrite above: the $10 unlock now gates everything powerful (generation, persona, voice, conversation, Drive sync, recaps, Mapbox, garage, diagnosis). Free tier is genuinely free and feature-complete, no wall in a different place. The 1-sample-recap idea is superseded (recaps are Gemini-billed, behind the unlock). Keeping this entry for historical record, marked archived.


## 2026-07-16 - Car profiles: vehicle identity decoupled from the dongle, companion goes per-car

**Kevin's call, explicit reopening of a §9 guardrail.** Trigger: he owns two cars (Outlander daily,
XJ Cherokee with the head unit, driven by his wife) and ONE v020 dongle + one phone. He wants to move
the dongle between them, pick the car on the device, and have the avatar, persona, prompts, wallpaper
and settings follow - all through the same Google Drive.

**Two rules changed.**

1. **"Per-car data keyed by OBD MAC" -> keyed by the ACTIVE vehicle** (`vehicle/ActiveVehicle.kt`).
   The MAC is now only a fallback. The old rule encoded `VehicleController`'s assumption that "each
   car has its own dongle". With one dongle shared, both cars collapse into one vehicle id. That is
   not merely messy telemetry: `Spec("vehicles", listOf("obdMac"), Mode.LWW)` means ONE row per id,
   last-write-wins across sync, so registering car B overwrites car A's row **including
   `odometerBaseline`** - which drives every maintenance interval - and it thrashes on every sync.
   `maintenance_items` (LWW on vehicleId+serviceName) corrupts the same way.
2. **"Companion = global (app install)" -> companion is PER CAR.** Not a whim: §1 (decided the same
   day) makes the paid companion the CAR itself. Two cars with faces cannot share one identity. The
   old rule was written when the companion was a companion; it did not keep up with §1.

**The free/paid line falls out for free.** Free-tier Zero stays global by construction - she has no
name, so nothing is keyed, and she rides along in whatever car you're in. That is exactly right for a
companion who is explicitly NOT the car. Paid/car-self is per-car. This maps onto
`CompanionIdentity.isCarSelf` (name blank = Zero) with no new concept.

**What did NOT change:** the Gemini/Mapbox/Shelly credentials, the spend hash, the sync-enabled flag
and first-session-done stay global. They belong to the install, not a car.

**Three implementation decisions worth not re-deriving:**

- **No Room migration.** `vehicle.obdMac` is the PK but it is just a `String` - nothing enforces that
  it looks like a MAC. Synthetic `car:<uuid>` ids live in the same column beside real MACs, and
  existing rows keep their MAC as their permanent id. The column name is now a misnomer; that is the
  price of not rewriting every key in the DB and every synced snapshot already on Drive.
- **Legacy values are read through, never migrated.** Both `CompanionProfile`'s identity keys and
  `AvatarStudio`'s art/wallpaper paths fall back to the old flat key/path when the active car has
  none. A one-shot migration would have to pick a vehicle id to move the old profile onto, and at app
  start the dongle often has not connected yet - so it would resolve to `default`, and the driver's
  companion would vanish the moment the dongle came up. Reading through also gives a sane default:
  the legacy profile/art is what any car without its own gets. Writes ALWAYS go per-car, so setting
  up car A never disturbs car B.
- **The active selection is per-device and is deliberately NOT in the sync registry.** The phone can
  be in the Outlander while the head unit is bolted in the Cherokee; they legitimately disagree. If
  it synced, picking a car on the phone would flip the head unit mid-drive.

**Companion sync is now per-car files** (`companion-<id>.json` / `companion_media-<id>.zip`), with the
legacy `companion.json` / `companion_media.zip` still read as a fallback. Both devices can sync at
once without fighting: the head unit writes the Cherokee's pair, the phone writes the Outlander's.

**Kevin's rollout, deviating from §10 on purpose:** both phases land on ONE feature branch
(`feat/car-profiles`), he test-drives it, it goes to `dev` only if it works, and to `main` only after
a week of it still working.

**Still recommended regardless: buy a second dongle** (~$15). The picker makes one dongle *work*, but
a dongle is foolproof where a picker is a footgun - forget to switch and the commute lands on the
wrong car's record. The dongle is the stopgap; the picker is the product.


## 2026-07-16 - Car manager (CARS): the surface, and two sync defects it exposed

**Kevin's ask:** a car manager for multi-car owners (his own 2 cars, but the shape is
AdamLZ-with-twelve). Roster of avatars linked to each car, each with its own drive history, and no
dongle hardcoded to one car. **Grilled first (/grillme), then built.** The MODEL was already right
after the morning's car-profiles work; what was missing was a first-class surface. The full brief was
emitted before any code.

**Decisions (all Kevin's, in grilling order).**
1. **Assets: HYBRID.** All car IDENTITIES pull eagerly every sync (tiny JSON, so the roster renders
   instantly and offline); MEDIA pulls lazily on first switch and caches forever. Offline = name +
   persona, no art, "sync to get this car's art". Rejected eager-everything (a twelve-car roster
   would drag eleven cars' portraits onto a head unit to drive one) and lazy-everything (the roster
   needs names to render at all).
2. **Per-car:** avatar, wallpaper, persona, voice, curated AriaPalette colorway. **Global (device
   ergonomics):** cassette variant, backdrop mode, gauge picks, saver timeout. Per-car palette does
   NOT decide ticket 05 - sec 12 already sanctions a curated Showa colorway picker and bans a free
   colour picker, so this scopes an approved feature. If 05 later kills the picker, per-car palette
   dies with it; cheap to unwind (a prefs namespace).
3. **NO recording gate**, despite Kevin's initial ask. A gate only catches "no selection at all" and
   is silent on **stale selection**, which IS the failure mode with 2+ cars. And it costs drives:
   **mislabeled is recoverable, missing is gone forever.** Replaced with a notice + a corrector.
4. **"RECORDING AS <car>" on EVERY OBD connect**, reusing CompanionPhase.showNotice's existing 4s
   flash. Every connect, not on-change, for the same reason as (3). Zero new surface.
5. **Corrections via a drive_reassignments RULE table**, not a re-key. See the defects below.
6. **New top-level CARS screen** off a Cruise dock tile. Named CARS, not GARAGE: three claimants on
   that noun (Shelly door tab, ticket 11, this). Functional, NOT art-directed - ticket 11 stays
   blocked behind 05.
7. **Retention: device 365 days, Drive permanent archive.** Preserves sec 1's compounding-history
   moat while bounding the head unit's eMMC. Year-old data becomes Drive-only and needs an explicit
   archive fetch; nothing reads that far back today.
8. **ARCHIVE only, no delete.** A truthful per-car delete needs the same machinery as (5) for an
   operation nobody runs, and the nuclear option already exists at the platform level (Drive's
   "Manage apps -> Delete hidden app data"). Archiving never frees Drive space - consistent with (7).
9. **Corrector lives in OBD -> HISTORY**, unit = one gap-split DriveWindow. CARS manages cars,
   HISTORY manages drives, and the drive list already exists there. Inherits sec 2's paid gate.
10. **Roster row:** avatar, label, companion name, last driven, active indicator. Odometer/MPG/
    service deliberately OUT - Logbook's job.

**TWO DEFECTS the grilling exposed. Both would have shipped silently.**

- **BUG-1 (mine, same day, b350485).** CompanionSync.decideCompanion PROMPTed on a blank local.
  Making identity per-car made `reconciled` per-car too, so the first switch to any car a device had
  never held gave local=blank, remote=real, !reconciled -> the "two companions met" dialog, asking
  the driver to choose between NOTHING and their own companion. "Keep local" would upload the blank
  and **destroy that car's identity on Drive**. On a multi-car roster it fires on nearly every first
  switch. Fixed: a blank local is an ABSENCE, not a rival -> ADOPT_REMOTE, ordered after
  sameContentAs (so blank-vs-blank stays NOTHING) and before the !reconciled check.
- **BUG-2 (pre-existing, since S1).** The 365-day retention purge was **undone by every sync**.
  monthsToSync unions local months with months that exist as a REMOTE SHARD - that branch is there so
  a fresh device pulls history, but it cannot distinguish "fresh device, pull everything" from "we
  purged this on purpose". Every sync re-inserted every purged row and re-uploaded it. obd_samples
  grew unbounded on device AND on Drive, and the purge only burned CPU. Fixed by (7)'s retention
  floor.

**The subtlest implementation detail, worth not re-deriving.** The re-key MUST run inside syncFile,
between the merge and the upload. It was written after the REGISTRY loop first, which is wrong:
syncFile uploads `localAfter` itself, so a post-loop re-key fixes the local device and re-uploads the
OLD-keyed rows anyway - the correction resurrects on every pass, on every device, forever.
obd_samples is UNION, so the merge genuinely does re-insert every old-keyed row still on Drive.
Re-keying before `localAfter` is read is the only thing that makes it converge. The rules also sync
FIRST in the REGISTRY so a correction from another device is known before obd_samples merges, and
applyReassignments is idempotent so it can be applied blindly rather than tracked.

**Accepted limits.** A device offline longer than the 90-day reassignment horizon keeps its
misattribution. A "recording as" flash the driver doesn't look at prevents nothing - it is the cheap
first line; the corrector is the real answer. Free tier can only correct its last drive (inherits
sec 2's history gate).

## 2026-07-18 - Fleet Hub wayfinder map: a web companion, spec complete, nothing built

**Kevin's ask:** after CARS (2026-07-16, above) shipped, Kevin wanted three things a head-unit
touchscreen can't do: a bigger screen for comfortable editing, fleet-wide cross-car analysis, and
managing a car's setup remotely when not near it. Charted as a new wayfinder map, `.scratch/fleet-hub/`,
fully resolved across one session, 7 tickets, full effort.

**Destination:** a build-ready spec for a web-based Fleet Hub companion (not another Android app,
not head-unit-bound). Delivered as `.scratch/fleet-hub/spec.md` plus a validated prototype
`.scratch/fleet-hub/prototype.html`. **Status: SPEC ONLY, nothing built.** Implementation of the
actual web app is a separate future effort.

**Decisions, in resolution order.**
1. **Backend-free Drive access is architecturally sound**, researched and cited to Google's own
   docs (`.scratch/fleet-hub/research-drive-access-feasibility.md`). Google Identity Services'
   client-side token model (OAuth implicit-grant style, no client secret, no server exchange) can
   request `drive.appdata` and hand back a browser-usable access token; Google's own Drive API JS
   quickstart is a live reference of exactly this shape. `drive.appdata` is Google's lowest
   sensitivity tier (non-sensitive), so no heavy app-verification review is needed. Real tradeoff:
   no silent token refresh (~1hr tokens, re-auth is a visible Google dialog) - accepted as the same
   cost category BYO-Gemini already asks of drivers. `DecompressionStream("gzip")` (browser-native
   since May 2023) can decompress the exact `.json.gz` NDJSON files `SyncEngine.kt` already writes,
   entirely client-side. Nothing forces a server into the design.
2. **Hosting: public static site** (e.g. GitHub Pages), zero cost. Safe because ALL real data
   access happens client-side against the driver's own Drive (decision 1); no car data touches
   Kevin's infrastructure. **Paid-tier only**, same paywall shape as sec 2's pricing - a free-tier
   Zero install has no custom persona/avatar/fleet data worth managing. **Full companion branding**
   (city-pop/AriaColors visual language), not CarsScreen's "functional, not art-directed" treatment -
   this is a real product surface.
3. **Write-back scope: companion identity ONLY** (name, persona, voice, avatar/wallpaper
   generation), NOT broader table data (maintenance, logbook, spend), which stays view-only this
   cut. Safe because `companion-<id>.json` is one small JSON object per car, not multi-row NDJSON -
   the web client just writes a correctly-timestamped file and the head unit's EXISTING SyncEngine
   LWW-merges it on next sync, no new merge/conflict code anywhere. Broader table edits would be a
   genuine multi-row merge risk (naive overwrite could silently drop rows), deferred to a future map.
   Latency stated plainly in the hub's UI ("changes apply next time you start the car") rather than
   left implicit.
4. **Avatar/wallpaper generation IS in scope, browser-side, on a separately-pasted BYO Gemini key**
   (same BYO pattern as FirstRunScreen, not read from Android's Keystore - a browser can't reach
   that anyway), stored in `localStorage`, honestly disclosed as browser storage rather than
   Keystore-grade security. **Correction found while scoping this: companion media sync already
   exists and needed no new engineering.** `AvatarStudio.packCompanionMedia()`/
   `unpackCompanionMedia()` plus `SyncEngine.uploadCompanionMedia()`/`ensureCompanionMedia()` already
   zip the avatar directory + wallpaper into `companion_media-<vehicleId>.zip`, upload via a plain
   overwrite (`DriveClient.upsert`, current-only, no history), and pull lazily on another device that
   needs it. **CLAUDE.md sec 8's Cross-device sync row and the sec 9 guardrail bullet both currently
   say media bytes stay per-device - that line is STALE, not accurate**, the same failure mode
   MEMORY.md already flagged once ("stale comments and stale copy don't sit there harmlessly, they
   actively mislead"). Correct statement: companion media (avatar + wallpaper) already syncs via
   `companion_media-<id>.zip`; only OTHER media (mixtape audio, build photos, voice recordings) stays
   per-device. The hub needs no Android-side engineering to participate - it just produces a zip in
   the same format under the same filename; `ensureCompanionMedia` doesn't care who wrote it.
   (CLAUDE.md correction itself is being handled separately by the orchestrator, not by this filing.)
5. **Fleet analysis: all four metrics in v1** (MPG trends, maintenance + Build Card spend totals,
   drive frequency/mileage, oil analysis trends - Kevin chose all four rather than narrowing), shown
   as a **single combined cross-car dashboard** (genuine side-by-side comparison on shared charts),
   not a per-car drill-down list - that is what makes it actually fleet-wide rather than a nicer
   CarsScreen. Each chart defaults to a recent window (e.g. last 90 days/year) with the PERMANENT
   Drive archive (2026-07-16 CARS decision, never purged on Drive) reachable via an explicit "view
   full history" expansion, rather than defaulting to an unreadable multi-year raw chart.
6. **Prototype validated with Kevin** (`.scratch/fleet-hub/prototype.html`, rough static HTML/CSS,
   single-theme night surface since Midnight AI has no light mode anywhere): the paper-block persona
   editor (cream/sepia/stamp-red for the persona textarea specifically, nodding at sec 12's "paper
   surface constant across any night palette"), the left-rail car roster in the identity editor, and
   the four-compact-panel dashboard density were all confirmed as-is, no changes needed.
7. **Full build-ready spec written** (`.scratch/fleet-hub/spec.md`): page list (Connect/Dashboard/
   Cars/Setup), a single 760px responsive breakpoint (desktop primary, phone must fully work), per-
   page loading/error/empty states, the dashboard's exact Drive-fetch-and-compute sequence
   (explicitly reusing EXISTING Android-side formulas - TelemetryRecorder's MAF/AFR MPG calc,
   ObdHistory's 10-minute-gap drive-splitting - rather than inventing new definitions), and the
   identity-edit write sequence (re-fetch the identity file fresh immediately before merging to
   minimize the LWW race window, pack the media zip in the exact existing format, and on a write
   conflict surface the SAME reconcile choice CompanionSync's PROMPT decision already gives on
   Android, rather than silently overwriting a concurrent head-unit edit).

## 2026-07-19: Custom wake word ("hey <name>") — reopens the frozen PTT-only decision

Map: `.scratch/custom-wake-word/map.md`. Destination reached: an implementation-ready architecture
decision for a wake word on the companion's custom (driver-renamed) name.

1. **Detection approach: Vosk, runtime-reconfigurable grammar, on-device.** Rejects Picovoice
   Porcupine (would need a fourth BYO credential per driver, not worth it for this feature). Rejects
   openWakeWord and Android SpeechRecognizer (dead ends for arbitrary custom phrases). Load-bearing
   constraint: the phrase must work for ANY driver-chosen name, not a small curated set, which rules
   out anything requiring per-phrase training at build time.
2. **Paid-tier only, opt-in, off by default, supplements PTT** (does not replace it). No free-tier
   use - free Zero can't converse at all, so there's nothing to wake into.
3. **Wake phrase is "hey <name>", not the bare name.** Real field data forced this: a bare single
   word false-triggers on ordinary cabin conversation, radio, or podcasts that happen to say a common
   short name far more than a two-word phrase would. `WakeWordTestEngine.buildTargetWords` builds
   `"hey " + name` (plus `"hey zero"/"hey hero"/"hey hero oh"` homophone padding for the shipped
   default). This IS the false-trigger guardrail an earlier ticket deferred pending real data.
4. **Rename regeneration: silent, live-immediately, no confirmation, no voice enrollment.** The
   detector is purely text-derived (grammar built from `CompanionProfile.name()`), so renaming the
   companion just rebuilds the grammar with no extra UX step.
5. **On-hardware validation: clean, 2026-07-19.** No false triggers across a real drive (cabin talk,
   radio) on the "hey <name>" grammar. Battery/CPU draw is a non-issue in practice: the head unit is
   on shore power the entire time the car is running, which is the only time a wake word plausibly
   needs to be listening - parked-and-off isn't a state this feature needs to work in. No VAD/energy
   gate needed in front of Vosk given both came back clean without one.

**Consequence for CLAUDE.md §8:** the frozen "Wake word ('Hey Moose') | REMOVED | PTT/tap only" row
is superseded - wake word is reopened, scoped to the paid tier, gated opt-in/off-by-default,
"hey <name>" phrase only. CLAUDE.md updated in the same pass as this filing.

**Next:** the debug-only `WakeWordTestEngine` validation harness becomes the shipping detector -
real implementation (Setup toggle outside dev-tools, paid-tier gate, wiring a HIT to an actual
Gemini Live turn instead of only logging it, and getting the Vosk model into a release-shipped asset
path) is a separate build pass, not more wayfinding. The map is closed.

## 2026-07-19 (addendum): wake word shipped, plus a third cold-start staleness bug

The custom wake word map closed and the feature was BUILT the same day (not just decided):
`WakeWordEngine` (paid-tier, opt-in, off by default, wired to fire a real `ACTION_TALK` turn),
`WakeWordPreferences`, a Setup toggle gated on `RuntimeMode.BYO_KEY`, and the Vosk model moved from
`src/debug/assets/` to `src/main/assets/` so it ships in release. Validated on a real drive: detection
works, no false triggers.

**The pattern worth not rediscovering a fourth time.** A key-off/key-on cycle reverted the wake word
to the fallback "hey zero". Cause: `WakeWordEngine.start()` runs at `AriaForegroundService.onCreate()`,
which is BEFORE the OBD dongle connects, so `ActiveVehicle.current()` still resolves to
`DEFAULT_VEHICLE_ID`, `CompanionProfile.name()` reads blank, and Vosk's `Recognizer` - immutable once
constructed - is built with the wrong grammar. `ActiveVehicle.notifyResolutionChanged()` /
`ACTION_CAR_SWITCHED` already existed and already corrected voice, persona and avatar for exactly this
reason (drive-notes-batch ticket 02, `02dba74`); the wake word simply was not subscribed to it.

**Rule: anything that reads `CompanionProfile` or `ActiveVehicle` at service-start time and caches the
result MUST handle `ACTION_CAR_SWITCHED`.** This is now the third bug of this shape (stale avatar,
stale voice, stale wake-word grammar). The cold-start window is real and any new component that caches
identity will hit it. Check new components against this rule at review time rather than in the car.

## 2026-07-19: Fleet Hub read an empty appDataFolder - Cloud project scoping, not a sync bug

**Symptom:** Kevin enabled Drive sync, tapped SYNC NOW, got "Synced with your Google Drive." Fleet
Hub showed "No cars synced here yet." Same Google account on both sides, sync toggle genuinely
connected, a sync pass genuinely ran.

**Root cause:** Fleet Hub's OAuth client ID was registered in Cloud project **608058469679**; the
Android app lives in **`midnight-ai-c7421` / 103196707820** (`app/google-services.json`). **Google
Drive's `appDataFolder` is scoped per Cloud PROJECT, not per Google account.** Two projects means two
separate hidden folders for the same user. The head unit wrote into one; Fleet Hub authenticated
against the other, listed it successfully, and correctly reported it empty. Within a single project,
an Android client and a Web client DO share one `appDataFolder` - which is precisely what makes the
app-plus-web-companion architecture viable, and precisely what was broken here.

**Fix:** one-time Cloud Console step - create the Web application OAuth client inside
`midnight-ai-c7421`, enable the Drive API there, set the served origin, drop the ID into
`fleet-hub/js/config.js`. No Android-side change; no re-sync needed. `config.js` now ships blank with
a loud comment and the README leads with the constraint.

**Why it cost a whole round:** every cheap hypothesis pointed elsewhere and came back clean (same
account - checked; sync connected - checked; sync ran - checked). The tell was the COMBINATION of a
reported-successful sync and an empty reader: success plus emptiness means the two sides are not
looking at the same place, which is a location bug, not a transfer bug. Two real but unrelated bugs
were found and fixed on the way (a singular/plural filename mismatch in `dashboard.js` against
`SyncEngine.REGISTRY`, and `syncNow`'s failure paths being log-only with no Crashlytics reporting) -
both worth keeping, neither the cause.

**Generalizable:** when a writer reports success and a reader sees nothing, stop investigating the
transfer and start investigating whether both ends address the same namespace. Applies to any
scoped-storage API, not just Drive.

## 2026-07-21/22: Spotify App Remote SDK reopened, BYO client ID (reverses the 2026-07-12 kill)

**CLAUDE.md sec 8's "Spotify SDK (App Remote), any flavor - REMOVED" row and sec 9's matching hard
guardrail are REVERSED, scoped.** Kevin: "since we are shipping a harness, spotify remote sdk is ok.
power users can use their own accs." This is an intentional reopening per sec 8's own rule ("if Kevin
asks to revisit any of these, remind him it's frozen and ask whether this is intentional"); asked,
confirmed. CLAUDE.md itself is not yet edited to match - sec 8/9 still read stale until that pass.

**Shape: true BYO, not Kevin's shared client ID.** The 2026-07-08 brainstorm note (above, in this
file) had already scoped two architectures and flagged the deciding question:

- **Kevin's single client ID, baked in** - simple for users, but Spotify's Development Mode caps a
  single app at 5 users (tightened from 25; confirmed current 2026-07-21). Rejected.
- **True BYO: each user registers their own Spotify dev app, pastes their own client ID** - Kevin
  picked this explicitly (2026-07-21 AskUserQuestion). No shared app, no user cap to ever hit -
  consistent with the project's existing BYO-Gemini/BYO-Mapbox/BYO-Shelly spine. The redirect URI
  cannot be per-user (a manifest intent-filter scheme is static), so it stays app-fixed
  (`com.kevin.midnightai://spotify-callback`) while only the client ID is BYO.

**The compliance question the 2026-07-08 note flagged as "resolve before any build" - resolved,
GRAY.** Spike (`business` agent, 2026-07-21/22, primary sources): `.scratch/spotify-byo/policy-read.md`.
No clause in Spotify's Developer Policy, Developer Terms, or the Feb 2026 "Platform Security" update
names or prohibits "many independent registrants, each bringing their own Client ID, distributed
inside a third-party app." Developer Terms VI.1.2 bars sharing YOUR OWN credentials with others - it
does not reach a user bringing their own. Live precedent: Home Assistant's official Spotify
integration has run this exact pattern at scale for years with no enforcement action on record. The
real, permanent exposure is VI.2/VI.7 - Spotify's blanket discretionary "we may limit anything, sole
discretion, without notice" clause, which is unbounded by its nature and not resolvable by more
reading.

**Risk accepted, Kevin's words:** "its gray, like we might not even get big enough for spotify to
notice. if they do, that in itself would already mean success." Recorded as a conscious bet on scale,
not a belief the pattern is definitely safe.

**What shipped 2026-07-21/22 (code, not yet on-device validated):**
- `app/libs/spotify-app-remote-release.aar` wired into `app/build.gradle.kts` (+ `gson` runtime dep).
- `MusicSource.Source` gained `SPOTIFY` alongside `PHONE`/`MIXTAPE`; `MusicRouter`'s exhaustive
  `when`s now branch on it.
- `CompanionProfile.spotifyClientId`/`saveSpotifyClientId`/`hasSpotifyClientId` - KeyVault-encrypted
  storage, same shape as the Mapbox token slot.
- `media/SpotifyController.kt` - connect/disconnect/transport over App Remote. App-to-app auth (goes
  through the installed Spotify app, not a browser redirect), so no Activity-result flow needed;
  flips `MusicSource` to SPOTIFY on connect, reverts to PHONE on disconnect/failure.
- **NOT yet wired:** the Setup UI to capture/paste the client ID and trigger `connect()`, and the
  manifest `<data android:scheme="com.kevin.midnightai" android:host="spotify-callback"/>` intent
  filter the redirect needs. `SpotifyController` is the transport/lifecycle seam only.

**Follow-up, not yet done:** CLAUDE.md sec 3 (tech stack table), sec 8 (frozen-decisions row), sec 9
(guardrail text), and sec 15 (codebase map) all still describe Spotify as fully removed / single-
build-no-Spotify. Amending those is Kevin's edit per sec 8's own rule (this file records deltas; it
does not rewrite CLAUDE.md's frozen sections).

## 2026-07-24: City-pop garage settings skin (city-pop identity 1)

Effort: wayfinder map (.scratch/garage-skin/, gitignored/disposable) - chart, design, resolve, ship a city-pop reskin of the settings hub. Destination: a diegetic "garage workbench" aesthetic replacing the functional MenuScreen.

**Decisions and outcomes:**

1. **Scope: hub only.** Reskin the six-group settings menu hub; leaf sub-pages (e.g., Appearance details) stay functional lists. Compromise buys shipping vs. the sprawl.
2. **Layout chosen: Variant B "Workbench".** Prototype loop against a visual spec. The car on the hoist IS the tappable "Your Car" group; the other five groups (Companion / Appearance / Connections / System / Reset) are diegetic bench objects. Shipped as `ui/GarageHub.kt`, replacing the old MenuScreen render slot.
3. **Katakana dropped.** Prototype flourish only; not in shipped UI.
4. **No companion figure in the garage.** Deliberate: sidesteps the still-open Zero mascot-direction fog (PORTRAIT_PROMPT still needs rewriting; the illustrator ref sheet still uncommissioned).
5. **Backdrop REVERSED mid-effort.** Originally planned as "one bundled illustrated room" asset; reversed to a PER-DRIVER generation feature, mirrors the existing wallpaper flow. Implementation: `ui/GarageBackdropGenerator.kt` (cloned from BackgroundGenerator); `AvatarStudio.loadGarageBackdrop()`/`saveGarageBackdrop()` keyed per active-vehicle. Consequence: illustrated garage is **BYO-key dependent** (calls Gemini image-gen). Free/no-key drivers keep GarageHub's procedural gradient backdrop (no regression). No raster assets bundled. Reached via Appearance "Garage backdrop" entry.
6. **Sync: the generated backdrop rides the existing companion_media-<vehicleId>.zip path.** Same BYO-cloud sync (CLAUDE.md sec 8/9), keyed per-active-vehicle like wallpaper. No new sync protocol.
7. **Out of scope:** identity-2 (green CRT/muscle garage, blocked on unauthored art); companion figure in the garage (blocked on mascot fog); full leaf-page reskin.

**Incident: lessons.md L1.** AvatarStudio.loadGarageBackdrop() routes through ActiveVehicle.current -> ObdBluetoothManager static init, which THROWS in the preview JVM. Root-cause class recorded in lessons.md; fix: guard @Preview composables behind LocalInspectionMode.

**Concurrent session work (same day):** Agent crew codenames recorded in .claude/agents/*.md + TEAM.md (orchestrator=Stark, coding=Derek, senior-dev=Ravi, bug-hunter=Vic, qa=Owen, analyst=Nadia, librarian=Marcus, business=Priya, marketing=Simone); an improvement loop added (lessons.md failure-mode ledger + assumptions-ledger requirement for coding agent); Crew page + Memory-system page added to public midnight-ai-architecture GitHub Pages repo (separate public repo).

## 2026-07-25 - Nav is Mapbox-only (§8 frozen decision reopened, Kevin)

Deliberate reversal of §8's "Maps/Waze intent stays the always-available, GPU-independent fallback -
no regression" stance. Navigation is now MAPBOX-ONLY: embedded Mapbox Nav v3 (BYO public token) plus
**Mapbox forward geocoding** (`service/NavGeocoder.kt`, REST on the BYO token, proximity-biased by the
live GPS fix) so any spoken/typed destination resolves, not just saved places. Google Maps / Waze
intents, `NavPreferences`, `NavAppPicker`, and the manifest geo: `<queries>` are removed. Accepted
consequence: no Mapbox token OR no GL ES 3.0 head unit = NO navigation at all; the driver is pointed to
Settings to set up in-dash Mapbox (the nav-app picker in Connections > Navigation becomes just the
Mapbox token entry). Enabled/made-sane by the same-day GPS correction (§14: the Cherokee DOES get a
fix), which makes geocoding proximity and the live Cruise nav map viable on the primary rig. CLAUDE.md
§3 nav row + §8 both rows updated in the same change. Code built by Derek on feat/cars-manager.

## 2026-07-25 - GPS beacon: phone-as-GPS-sensor over hotspot

**The head-unit GPS antenna is dead.** Connecting it to the bulkhead drops both Bluetooth and WiFi - a
shared power/RF rail brown-out. Disconnecting restores both. Kevin's hard constraint: no additional
hardware purchases.

**Resolution: phone-as-GPS-beacon.** The phone already supplies the head unit's internet via hotspot
(it is the access point); it now also supplies position over UDP. Options considered and rejected:
- Run the whole app on the phone (Oppo A17K Helio G35) — Compose + Mapbox janks on weak SoC. Makes
  the weak device the app host instead of a passive sensor. Loses positioning anyway.
- Wireless Android Auto with tab-switching — AA takes the screen; the app cannot read location from it.
  Would require reverting the Mapbox-only nav decision (§8, same day). Positioning surrender.

**Why the design self-configures:** the phone is the hotspot's access point, so the head unit's default
gateway IS the phone. No mDNS, no broadcast sweep, no pairing UI, no IP entry. Pull-gated push: head
unit HELLOs every 5s naming a rate; phone transmits only while they arrive. Ignition-off / out-of-range
stops sampling without a shutdown message needing to survive the failure that caused it.

**Not a new car-data exception (§9).** Raw UDP on the driver's own hotspot LAN - no server, no internet
hop, no third party. Strictly tighter than the BYO-cloud Drive sync, which at least reaches Google's API.
Opt-in, OFF by default.

Commits: `d329dbb` (beacon link), `81e478d` (Mapbox bridge + 8 review fixes), branch `feat/cars-manager`.

## 2026-07-29 - Maintenance schedule (three states, dual-axis, no rate estimate)

Spec'd via a full grilling pass, then built in 5 commits: `65d0f13` (data + logic), `52130fa` (voice tools), `6f0fd03` (DUE tab), `5eae3b6` (docs), `7174600` (canonicalization fixes).

**Three distinct `maintenance_items` states. Conflating them was shipped twice in one day.**
1. **ANCHORED** - a `lastDoneMileage` and/or `lastDoneDate` is set. Due when its own mileage or time threshold is crossed.
2. **UNKNOWN** - no anchor at all and `neverDone` false. **Never counts as due, never injected into the prompt**, and forms the backfill queue. Previously `dueItems` coerced a null anchor to 0, so every freshly-registered car injected ~12 phantom overdue items into the live system instruction via `AriaBrain` - the companion was crying wolf about services it knew nothing about.
3. **NEVER-DONE** - `neverDone = true`, a confirmed and actionable fact, **always due**. Distinct from UNKNOWN on purpose: on a 200k-mile car "never been done" is the most useful thing the schedule can say, and folding it into "I don't know" loses it. `isDue` checks `neverDone` FIRST and returns true unconditionally, so **any path that sets an anchor MUST clear `neverDone`** - `logServiceDirect` did not, which left a just-serviced item permanently overdue (fixed in `6f0fd03`).

**Where intervals come from: a search-grounded Gemini lookup, NOT a bundled asset.** `VehicleController.lookupServiceIntervals` asks the model (with search) for the **SEVERE / heavy-duty** schedule for the car's year/make/model and parses a JSON array of 6-12 items. Severe was chosen over normal because an out-of-warranty 28-year-old Jeep doing short trips is the textbook severe case, and erring toward more frequent service on an old vehicle is the cheap direction to be wrong in. The DUE tab labels which schedule it is showing. There is no bundled YAML and no powertrain table.

**Refresh is manual.** A REFRESH SCHEDULE action in the DUE tab re-runs the lookup, replacing interval fields while PRESERVING `lastDoneMileage` / `lastDoneDate` / `neverDone`. Manual, not an auto re-seed on upgrade: it never silently rewrites driver data, and it doubles as the escape hatch when the model returns junk. Already-onboarded cars keep their old intervals until the driver taps it (`onboardPendingVehicles` skips `onboarded` vehicles forever).

**Dual-axis reporting, no cross-unit winner.** `get_next_service` returns `byMiles` and `byTime` as **one soonest candidate each** (`ServiceCandidate?`, not lists), plus the unknown count and names. It excludes already-due items. There is deliberately no single merged winner: ranking a miles item against a time item requires a miles-per-day rate estimate, which Kevin explicitly ruled out. An earlier cut ranked a dual-axis item onto one axis by comparing each remaining value as a fraction of its own interval - that reduces algebraically to comparing the driver's pace since last service against the interval's designed pace, i.e. a rate estimate in disguise, and it also carried a real ordering bug where miles-tagged items beat time-tagged ones wholesale (an oil change due tomorrow lost to a service due in 29,990 miles). Both removed.

**Backfill.** `log_past_service(service, mileage?, miles_ago?, date?, never_done?)` writes ONLY the `maintenance_items` row and **never a `service_record`** - a remembered approximation must not enter the precise ledger alongside work logged at the time with real cost and notes. Future dates are rejected. An unparseable date no longer discards a good mileage anchor supplied in the same call. **If the driver says they don't know, the tool is not called at all** and the item stays UNKNOWN; guessing an anchor is worse than having none. The walkthrough is prompt-driven over the unknown queue with NO persisted state - the queue IS the remaining unknowns, so it is resumable for free - driver-initiated only, one item at a time, stoppable, never proactive (sec 9.1 no-compulsion).

**Venue: DUE, the logbook's 8th tab.** OVERDUE / COMING UP / UNKNOWN under a severe-service header. Where a section holds both mileage- and time-based items they are split under "by mileage" / "by time" labels rather than one list, because a bare concatenation reads as urgency order and put an inspection due tomorrow below an oil change with 4,900 of 5,000 miles left. Rows are tappable to hand-correct; voice is the primary path, the tab is the correction path.

**Canonicalization (fixed 2026-07-29, `7174600`).** `SERVICE_KEYWORDS` matched by substring taking the FIRST list entry, which was deterministically wrong: "cabin air filter" contains "air filter", so logging the cabin filter stamped the ENGINE filter and said so aloud; a bare "brake" keyword swallowed "Brake Fluid", a service the severe lookup requests by name. Now longest-keyword-wins, so a specific phrase beats a general one regardless of declaration order. The seed path also never canonicalized at all, so registration stored the model's raw phrasing while every later write canonicalized, missed the row, and created an interval-less duplicate - seeding now canonicalizes and de-duplicates.

**Verification:** the v11->v12 migration has NEVER executed (`MigrationTest.migrate11To12_addsNeverDone` is written, needs `connectedDebugAndroidTest`). The DUE tab has never been rendered. A bug-hunter pass found no first-render crash, no anchor+never-done coexistence, no cross-car leak and no prompt bloat - all traced, none device-verified.

## 2026-07-29 - UI coherence audit, steps 1-5 of 6 (step 6 deferred by Kevin)

Audit published as an artifact; built in 3 commits: `b3dc485` (steps 1-2), `dda5d84` (steps 3-4), `3a8b14f` (step 5). Findings were MEASURED from source, not asserted.

**The measurement.** `MaterialTheme.typography` vs `AriaType` refs: Cruise 0/30, Logbook 0/119, LightsOut 0/14, **ControlPanelScreen 94/1**. Settings was the only screen outside the design system - a stock Material list wearing Aria's colours. Distinct inline dp values: Cruise 34, Logbook 32. Corner radii above `AriaShapes`' 8dp cap ("not soft rounded blobs"): Cruise 10 and 14, Settings 10 and 12.

**Root cause found: `AriaDimens` was referenced ZERO times across all of `ui/`,** and held only four border widths plus one touch target - no spacing scale to import. So CLAUDE.md sec 12's "use AriaDimens tokens" rule was literally unfollowable, which is why every screen invented its own dp values. Fixed first, because the other findings regress without it: added a 4dp-based scale `s1`=4 .. `s5`=32, plus `screenEdge(sizeClass)` / `sectionGap(sizeClass)` helpers in `ui/WindowSize.kt` (not `theme/`, because `SizeClass` is a ui/-layer concept and `ui/ -> ui/theme/` is the established import direction). Adoption was deliberately a visual no-op - only values already exactly on the scale were swapped; 10dp and 86/60dp did not map and were left rather than quietly retuned.

**The avatar crop (Kevin's explicit requirement).** `AvatarVibe` drew the portrait with `ContentScale.Crop` and no `alignment`, defaulting to Center, while BOTH call sites pass a SQUARE box (COMPACT 100x100, EXPANDED avatarSize squared) and generated portraits are tall (216x268 by the composable's own defaults). Centre-cropping a tall portrait into a square keeps the vertical middle and discards the head. `alignment = Alignment.TopCenter` trims from the bottom instead: Crop scales by width (0.463), giving a 124dp-tall image in a 100dp box, so the top **80.6%** survives. The same one-line bug was live in the floating companion badge and the CARS roster thumbs; both fixed. Deliberately NOT applied to the settings backdrop, GarageHub wallpaper, the driver's car photo or mixtape covers - centre is correct for a scene, only faces care where the crop lands.

**Settings joined the design system.** All 94 sites now derive from AriaType's MONO roles (Settings is a night/instrument surface in Lights Out's family; the serif logbook roles have no business there), named once as ScreenTitle / RowTitle / RowSubtitle / Label. `GlassRow` renamed `PanelRow` - solid Panel, hairline bezel, 4dp corner, no alpha. Glassmorphism appears nowhere in sec 12's vocabulary of cassette shells, aged paper, chrome and instrument bezels. **The wallpaper backdrop was removed** in favour of a solid instrument ground: sec 12 grants Cruise an explicit animated-backdrop exception and grants Settings none; "parked surfaces RICH" means MATERIAL rich, not busy (the logbook is the proof - richest screen, no photograph); and a per-driver generated wallpaper meant Settings had no stable identity and fought its own content for contrast. Dropping the bitmap load also removed the reason its `LocalInspectionMode` guard existed.

**Cruise COMPACT dock.** The phone's bottom control row was a horizontally-scrolling strip of NINE controls built from FIVE idioms - a raw `Text("gear")` glyph, two purpose-built buttons, a status indicator that was never interactive, a physical binder object, and four tabs - which overflowed 360dp so several sat off-screen with no affordance. Now a fixed 4x2 dock of equal-width `DockTile`s sharing one idiom: destinations on top (logbook, codes, cars, garage), toggles beneath (night, mute, setup, apps). Every tile carries the 56dp `driveTouchMin`; at 360dp each gets ~76dp. Behaviour was reused, not reimplemented. `AmbientListeningIndicator` LEFT the dock - it is a status readout, not a control - and now sits beside the avatar. **EXPANDED and MEDIUM are untouched**, so the head unit's corner-anchored HUD is unchanged and still calls the pre-dock components; the two idioms coexist per size class deliberately.

**Step 6 (deferred, not rejected):** two-pane Settings for EXPANDED - groups pinned in a left rail, detail filling the right, instead of a single-column list wasting a 1024x600 screen. Phone stays single-column. Deferred because it is the only structural change in the plan and would have been the fifth consecutive UI change landed without a frame rendered.

**Verification:** NOTHING in this series has been rendered on a device or in a preview renderer. Compile + unit tests green; a senior-dev pass cleared step 5 with no blocking findings, including hand-verified crop arithmetic. Whether a generated portrait actually frames the head inside that top 80.6% is a generation-prompt question this work cannot answer.

## 2026-07-31 - multi-aspect pivot: carry-over inventory ruled on (5 decisions)

Context: the pivot's original `.scratch/multi-aspect-assistant/` map (built on the home laptop
2026-07-30/31) did not survive a machine port - `.scratch/` is gitignored by design and was never
committed. The map, including a carry-over inventory with 12 contested keep/delete calls awaiting
Kevin's ruling, was rebuilt from scratch (`.scratch/multi-aspect-assistant/map.md` and
`inventory.md`, both disposable). A fresh walk of all ~190 files in
`app/src/main/java/com/kevin/midnightai/` produced 5 new contested items (not the same list as the
lost 12 - nobody should assume overlap). All 5 are now resolved:

1. **Music: no UI, keep Spotify App Remote for direct voice-driven play.** Nothing on-screen for
   music in the new app (the phone itself is the music surface). But `media/SpotifyController.kt`
   (App Remote transport) and `media/SpotifyWebApi.kt` (PKCE search -> track URI, since App Remote
   has no search) port, so the assistant can be asked by voice to play a specific track/artist
   directly. RETIRE the mixtape stack entirely (`MixtapeLibrary.kt`, `MixtapePlayer.kt`,
   `MixtapeCoverGenerator.kt`, the `LibraryTrack*`/`Mixtape*`/`MusicPlay*` tables,
   `MusicHistoryRecorder.kt`, `ui/NowPlayingWidgets.kt`) and the PHONE/MIXTAPE source-routing in
   `media/MusicRouter.kt`/`MusicSource.kt`. Open question not resolved: whether generic
   `media/MusicController.kt` (MediaSession prev/pause/next over whatever's playing) is still
   wanted alongside Spotify App Remote, or Spotify alone covers voice control - didn't come up.

2. **Garage/Shelly control: keep as a voice-only utility.** `vehicle/GarageController.kt`,
   `GarageOpener.kt`, `GaragePreferences.kt`, `ShellyCloudOpener.kt` (wired transport),
   `ShellyBleOpener.kt` (retained unwired) port unchanged, action-only, no status - same shape as
   today. RETIRE the dedicated car-launcher-shell screens: `ui/GarageHub.kt`, `ui/GarageSheet.kt`,
   `ui/GarageBackdropGenerator.kt`. Becomes a settings-list entry + voice tool
   (`activate_garage`), no bespoke UI, consistent with decision 1's "no UI interface" pattern.

3. **Spend gate: retired, no replacement for now.** RETIRE `ai/SpendGate.kt` and its logbook-spend
   usage outright. Ledger does NOT get an equivalent passphrase gate in this pass - access-gating
   is deferred until fleet/ledger/pantry are actually built and it's clear what (if anything) needs
   protecting. Do not build a ledger auth model preemptively.

4. **Fleet build/mod photos retired entirely; photo storage becomes pantry-ingestion-only.**
   Broader than the question asked (which was just "reuse `PhotoAlbumStore.kt` for pantry?").
   Kevin's answer: no need for car build/mod photos anymore, period. So `data/PhotoAlbumStore.kt`
   becomes ingestion-only storage feeding the pantry receipt-photo -> deterministic-reconciliation
   pipeline (the LLM-ingestion gate already locked in MEMORY.md's pivot section) - not a browsable
   album. Consequence for the fleet aspect: `vehicle/BuildSheetController.kt`, `build_entry`'s
   `photoPath` field, and the Polaroid photo composable in `ui/LogbookScreen.kt` should be RETIRE
   (or at minimum, build/mod cost tracking ports as text-only, no photos) when fleet is actually
   scoped - these were provisionally filed as straight PORT in the inventory before this answer
   landed and need re-flagging then.

5. **Tagged places / reminders: keep as-is.** `data/local/TaggedPlace.kt`, `PlaceReminder*`,
   `location/PlaceController.kt`, `location/ReminderController.kt` port unchanged as a generic
   voice utility - arrival-based reminders are still useful on a phone outside the driving-specific
   context ("get home, remind me to call X") that originally motivated them.

**Lesson repeated from the pivot's own MEMORY.md note, now proven twice:** these 5 decisions were
made in a single conversation and could easily have stayed sitting only in `.scratch/`, exactly how
the original 12 were lost. Filed here immediately per Kevin's instruction, not deferred to session
end.

## 2026-07-31 - project named: ATLAS

The multi-aspect assistant (the new public repo, per this file's "New PUBLIC repo" pivot rule) has
a name: **ATLAS** — backronym "Adaptive Tracking for Life, Assets & Sustenance," in the
Stark-style register requested (a real word doubling as a fitting phrase, like JARVIS/FRIDAY/EDITH,
not a mascot name like Zero/Yoko/Kaze). Chosen over ARGUS ("Automated Reconciliation & Guardian
Utility System," many-eyed-watcher framing) and HESTIA ("Household Expense, Stock, Transport &
Item Assistant," warmer/domestic framing) after Kevin ruled out reusing "Andromeda" for the
wrapper app, since Andromeda is the existing finance project being absorbed as the ledger aspect,
not the name for the thing absorbing it.

**Not yet done, flagged so it isn't silently assumed complete:** no trademark/App Store name
collision check has been run on ATLAS. No renaming has happened anywhere in code, package names,
or this repo's docs — `com.kevin.midnightai` / "Midnight AI" / "Nightrunner" references are all
still current until an explicit rename pass happens in the new public repo. This entry records the
NAME decision only, not an executed rename.

## 2026-07-31 - competitive landscape research: who else is building this

Kevin asked directly who else is combining fleet + ledger + pantry under one assistant, and how
good it is, before finalizing the name. Web research (not exhaustive, single session):

**Nobody found combines all three domains under one voice assistant.** That exact shape — fleet
(OBD/car) + ledger (finance) + pantry (receipt ingestion), unified under a single BYO-key, no-
subscription AI persona — did not surface anywhere. Each piece individually has real competition:

- **Closest conceptual match: LifeSync AI** (life-sync.ai) — one app, four modules (kitchen/
  finance/health/home), "cross-domain AI." Looks shallow: reviews found are generic marketing copy,
  no real App Store/Play ratings surfaced, and several near-identical low-effort "LifeSync" clones
  exist (LifeSync Mental Health, Life Tracker AI, a Blink-app-builder "LifeSync AI Assistant") —
  signals this niche is being cheaply cloned, not owned by a strong incumbent. Subscription-gated
  after a 7-day trial, the opposite of this project's BYO-key/no-subscription model.
- **Pantry/receipt ingestion is mature and good.** GroceryTrack (Gemini-powered, 95%+ field
  accuracy on standard receipts, $3.99/mo) plus Recipy, Grocyy, FoodiePrep, NutriScan. Proves the
  core tech (LLM reading receipt line items reliably) works. Also means pantry alone is commodity —
  differentiation must come from the combination, not from pantry being novel.
- **Fleet/OBD + AI chat is mature and crowded.** OBDAI, PitStop AI, MECH AI, Car AI, FIXD already
  combine OBD diagnostics + AI chat + fuel/expense tracking. OBDAI's in-app agent is literally named
  "ARIA" ("Automotive Reasoning & Intelligence Agent") — pure coincidence with this project's own
  retired mascot name (Aria/Yoko/Kaze/Zero lineage), but worth remembering if that name ever
  resurfaces. OBDAI's $4.99/mo pricing is the same reference point the old CLAUDE.md §2 pricing copy
  argued against.

**Bottom line:** the individual pieces are commodity-competitive; nobody combines them the way this
pivot does, and the closest adjacent player (LifeSync) doesn't look like a serious, well-built
threat yet. The moat is the combination + BYO-key economics + single-assistant-persona framing, not
novelty in any one domain — consistent with the original fleet-only moat framing this project
already had.

## 2026-07-31 - project renamed: LEGION (supersedes the ATLAS entry above)

ATLAS is dropped. Reason: OpenAI shipped "ChatGPT Atlas" (Oct 2025), a widely-covered AI browser
explicitly framed as a "true super-assistant" — the same product category, from the dominant player
in the space. Disqualifying for a portfolio project regardless of the name's other merits.

Checked and rejected in order, all with live collisions against existing AI-assistant products
specifically (not just incidental word reuse): ARGUS (Altus Group's "ARGUS Assist" for commercial
real estate — weakest collision of the batch, but Kevin chose to keep iterating rather than settle),
HESTIA (hestia.ai, Hestia Insight), JANUS (JanusAI, janus.ai, DeepSeek's Janus Pro), KAIROS (KaiROS
AI, multiple "Kairos: AI [daily companion/intern/assistant]" apps), VESPER (four separate "Vesper
AI" branded assistant apps), HELM (Helm.ai, gethelmagent.com, plus the unrelated but massive
Kubernetes Helm collision), KEYSTONE (an almost-exact match: "Keystone - Habits, Missions, Lists,
AI Assistant & Goals"). Pattern across all seven: real-word, evocative one-word names are saturated
in the 2026 AI-assistant naming rush - virtually anything meaningful is already claimed by a
competing assistant product specifically, not just an unrelated app.

Kevin's next pick, **Arthas** (his favorite WoW character), was flagged before filing: Arthas
Menethil is a specific, actively-owned Blizzard/Activision character, not a generic word — a
materially different and higher risk than the word-collisions above, since character names can be
protected even without a standalone trademark filing and Blizzard enforces its IP. Kevin chose not
to use the character's proper name and picked **Legion** instead, keeping the Warcraft flavor
(the Burning Legion) at a lower-risk remove.

**Legion also has a live collision**, checked and disclosed: Legion Technologies (legion.co), a
real B2B workforce-management company, actively brands multiple features "Legion AI Assistant"
(Schedule Assistant, Expression Assistant, Forecast Explainer, etc., per their Spring 2026 release).
Different market from a personal life-assistant app (retail/hospitality workforce scheduling vs.
fleet/ledger/pantry), so day-to-day consumer confusion is unlikely, but it is a real, active,
enterprise-grade trademark holder, not an incidental mention. **Kevin's explicit call: use Legion
anyway**, accepting this collision knowingly, given the different market.

**Status: LEGION is the project name.** Same caveat as the ATLAS entry it supersedes — this is a
NAME decision only. No rename has been executed anywhere in code, package names (`com.kevin.
midnightai` is still current), or this repo's docs. That happens as part of the new public repo
setup (see this file's "New PUBLIC repo" pivot rule), not automatically from this decision.

## 2026-07-31 - new public repo created: github.com/kevinmyo-code/legion

Executed the "New PUBLIC repo" pivot rule (this file, top of file) the same day as the naming
decision above. `MIDNIGHT_AI` stays private, untouched, as the archive - nothing was force-pushed
or rebased; the new repo is a completely fresh `git init`, no shared history.

**Method:** bulk-copied the PORT/REWORK-classified packages from the 2026-07-31 carry-over
inventory (this file's earlier entry) via direct filesystem copy, not a manual per-file port -
`location/`, `sync/`, `util/`, `weather/`, `vehicle/` wholesale, `ai/`/`data/local/`/`service/`
copied then pruned of retired files, `media/` cherry-picked to just `SpotifyController.kt` +
`SpotifyWebApi.kt`. Package renamed `com.kevin.midnightai` -> `com.kevin.legion` across all copied
source via a single `sed` pass (verified zero remaining old-package references afterward). Room's
`CarDatabase.kt` was hand-rewritten at v1 with only the surviving entities - no migration chain
ported, since a new app with no installed base has nothing to migrate from. Build config
(`build.gradle.kts`, `gradle/libs.versions.toml`) hand-trimmed to drop Mapbox/Firebase/Play-
Billing/Media3/ZXing. `ui/` was NOT copied at all - every screen is retired per the inventory
(city-pop design language dead) except a hand-written placeholder `MainActivity` so the app has an
entry point.

**Secrets check before anything public happened:** `app/google-services.json` (a live, tracked
Firebase project ID per this repo's own CLAUDE.md §16) was deliberately excluded, not scrubbed-
after-the-fact - never copied into the new repo at all, and added to its `.gitignore` going
forward. `local.properties` and any `.jks` keystores were confirmed absent from the copy before
the first commit. No secret ever touched the new repo's history.

**Known incomplete state, not silently glossed over:** a grep before the first commit found 36
files still referencing classes deleted in the prune (`LiveToolbox.kt`, `AriaBrain.kt`,
`SyncEngine.kt`, `CompanionSync.kt`, `VehicleController.kt`, several sub-agents, and others). This
does not compile yet. Reconciling those 36 files is flagged as the next real coding task, not a
mechanical follow-up - e.g. `LiveToolbox`'s tool declarations need per-tool judgment to strip
correctly, not a blind delete. Full list and the grep to reproduce it live in the new repo's
`README.md`.

**Two operational items resolved along the way, worth keeping:**
- **Commit identity.** This repo's git config used `kevin.win@quanex.com` (a work email). Kevin
  chose `kevinmyo@gmail.com` for the new repo's LOCAL git config only (not global, doesn't touch
  this repo) so the work email never enters LEGION's public commit history.
- **`gh` CLI wasn't installed and machine-wide `winget install` was cancelled at a UAC prompt.**
  `winget install --id GitHub.cli -e --source winget --scope user` succeeded (installs the zip
  package to `%LOCALAPPDATA%\Microsoft\WinGet\Packages\`, no admin needed) where the default
  machine-scope MSI install had failed. `gh auth login` was still interactive (device-code browser
  flow) and had to be run by Kevin directly, not automatable.

**Result:** https://github.com/kevinmyo-code/legion, public, `main` branch, one commit, 170 files.

## 2026-07-31 - LEGION reconciliation pass: builds clean

Followed the scaffold commit above. Reconciled all 36 files a grep for retired-class names
flagged, then ran a real `./gradlew compileDebugKotlin -Pnokey` (not just trusted the grep-clean
result) and fixed what it caught that grep structurally couldn't - see `library/lessons.md` L10 for
the full account. Two commits pushed to the LEGION repo (`70a1de4` reconciliation, `df7e4d5` README
update); LEGION's own README.md documents its status in detail, not duplicated here.

**Notable fixes beyond the original 36-file scope:**
- `NowPlayingController.kt`/`MusicController.kt`/`VolumeController.kt`/`MediaNotificationListener.kt`
  were referenced by kept files but never actually copied from Midnight AI - copied over now.
- `location/Beacon*.kt` (5 files: BeaconClient/Server/Preferences/Protocol/LocationProvider) were
  missed in the original beacon-cluster prune - deleted now; a phone has its own GPS.
- `MusicAgent.kt` and the `get_music_taste`/`recommend_music` voice tools retired outright, beyond
  the original music decision's scope - their entire data foundation (the taste ledger, the saved
  mixtape library) was already gone, so they would have silently always returned "not enough
  history." Discovered only because the compile caught a Room DAO referencing a deleted column in
  the same cluster, which prompted a closer look at what else depended on it.
- `control_music`/`play_music` rebuilt on `MusicController` + Spotify App Remote directly, replacing
  `MusicRouter`/`MusicSource`. `get_current_location` rebuilt on Android's built-in `Geocoder`
  (Mapbox-backed `NavGeocoder` is gone). `start_navigation`/`stop_navigation`/`open_music`/
  `restyle_background`/`restyle_avatar`/`set_music_source`/`set_spend_passphrase`/`unlock_spend`/
  the three trivia tools removed entirely - each tied to a retired system.
- Firebase was never actually dropped as a real dependency despite the build config excluding
  it - `MidnightApplication`/`MidnightEvents` still called `FirebaseCrashlytics` directly.
  `MidnightEvents` now logs via `Log.d`, same public API, so no call site needed to change.
- Two non-portable machine-specific paths broke the build outright: `local.properties`' `sdk.dir`
  had single-backslash Windows path escaping (Java properties files need doubled backslashes or
  forward slashes) and `gradle.properties`' `org.gradle.java.home` hardcoded Kevin's specific
  Android Studio JBR path - both fixed generally, not just patched for this machine, since the
  pivot's clone-and-run requirement means anyone else's build would have hit the same walls.

**Verification tag:** `built` and `tested` - the compile actually ran and actually succeeded
(`BUILD SUCCESSFUL`, only pre-existing deprecation warnings). Not `reasoned` - this was checked,
not inferred.

## 2026-07-31 - LEGION ledger + pantry aspects built

**Ledger** (bank-statement ingestion): Kevin's explicit call was deterministic parsing stays
primary (ported Andromeda's DBS/BofA parsers verbatim, same balance-continuity reconciliation
checks), with an LLM extraction path only when neither recognizes the layout - and even then it
must pass the same reconciliation principle (extracted transactions sum to the statement's own
stated total) before anything is accepted. `LedgerTransaction.amountCents`/`balanceCents` are
`Long` cents, not `Double` - deliberate deviation from `BuildEntry`/`ServiceRecord`'s convention,
since the reconciliation gate depends on exact equality. Real technical risk verified rather than
assumed: PdfBox-Android ships fonts/glyphlists as Android assets, unreachable from a plain JVM
unit test - caught by running a coordinate-extraction spike before porting the rest of the DBS
parser, fixed by adding Robolectric (test-only) to shadow `AssetManager`. 11 tests, 5 fixture PDFs
generated via Andromeda's own `reportlab` tooling for parity with the Python originals.

**Pantry** (grocery receipt photo ingestion): new design work, no Andromeda equivalent - receipts
are photographed, not born-digital, so there is no deterministic layout the way bank statements
have one. LLM vision is therefore the PRIMARY path, not a fallback, but the same reconciliation
discipline applies: `PantryReceiptAgent.parseAndReconcile` requires extracted line-item totals to
sum exactly to the receipt's own printed total before anything is written, or the whole receipt
quarantines. Kevin's explicit call: build ingestion + per-item macro estimation now (the LLM
estimates calories/protein/carbs/fat per line item at extraction time from the product name, since
a receipt never prints those); consumption-rate tracking and any spend/nutrition aggregation are
separate future work, deferred the same way ledger's categorization/FX was. Macro estimates are
never part of the reconciliation check - there is nothing on a receipt to verify them against -
and must always be surfaced as estimates, never fact (CLAUDE.md §9.1's "anchored to falsifiable
reality" thesis). `ai/SubAgent.kt` was extended with an optional inline image part (`imageBytes`/
`imageMimeType` on `ask`/`askTyped`) rather than writing a one-off HTTP call, since this is a
capability other future vision work can reuse. The old car-photo-album feature (`PhotoAlbumStore`,
named albums, cover art) was replaced outright by `PantryPhotoStore` (ingestion-only storage, no
browsable album) since the multi-album shape didn't fit automatic receipt ingestion at all. 8
tests (`SubAgentPartsTest` for the new inline-image JSON shape, `PantryReceiptAgentTest` for the
reconciliation gate), all against canned JSON strings rather than real image fixtures - there is no
deterministic ground truth to synthesize fixtures against here, unlike ledger's real PDFs.

Both aspects: `compileDebugKotlin` and `testDebugUnitTest` verified with real builds (19 tests
total, all green), not just source inspection or a grep pass. Full design in LEGION's
`.claude/plans/wiggly-beaming-quasar.md`.

**Verification tag:** `built` and `tested` for both aspects - real compiles and real test runs,
not inferred.

## 2026-08-01 - Ledger Drive-folder access: SAF, gated at API 30, no new OAuth scope

Context: Kevin wants to point LEGION at a Google Drive folder of bank statements, ingest them in
one batch, and have new statements uploaded there later picked up without re-processing the old
ones. The existing `sync/DriveAuth.kt` requests `drive.appdata`, a hidden app-private space that
structurally cannot see user files, so a new access route was needed. Three were on the table: SAF
(`ACTION_OPEN_DOCUMENT_TREE`) against the Google Drive app's DocumentsProvider, `drive.file` plus
the Google Picker, or `drive.readonly` REST.

**Decision: SAF, gated at `SDK_INT >= 30`, with a per-file `ACTION_OPEN_DOCUMENT` fallback, and no
new OAuth scope.** Researched by disassembling the shipping Drive app (2.26.307.6) and reading AOSP
`DocumentsUI`; full findings were written to `.scratch/ledger-drive-ingestion/research/`, which is
gitignored and disposable, hence this entry.

Why: Drive's provider advertises `Root.FLAG_SUPPORTS_IS_CHILD` only above API 30, which is exactly
the flag `DocumentsUI` filters tree-pickable roots on. Below that the root does not appear, so the
long-standing "Drive does not support tree picking" belief is true for Android 10 and below and
false above it. `minSdk` here is 24, so the per-file fallback is mandatory rather than defensive.
Both paths yield document URIs, so nothing downstream forks. The rejected alternatives:
`drive.file`'s folder-grant semantics for later-added files are undocumented by Google (both owning
doc pages read, neither states it), and `drive.readonly` is a restricted scope whose verification
burden worsens the already-open clone-and-run blocker.

**Two consequences worth not re-deriving.** (1) **SAF exposes no content hash.**
`DocumentsContract.Document`'s column set is closed and Drive's `md5Checksum` is unreachable
through a tree URI, so any content identity must be computed by LEGION over the bytes, at the cost
of a full read per file. Cheap alternatives are the document id (`acc=<index>;doc=<driveFileId>`),
size, and last-modified. (2) **The central claim is traced, not tested.** Whether a picked tree's
`listFiles()` returns files added AFTER the grant traces to yes through four independent layers
(prefix grant, per-call re-query, per-call `enforceTree()`, Drive's real `isChildDocument`), but
nothing was run on hardware - the research session had no device attached. A 15-minute device probe
is specified and raised as its own ticket. If it comes back no, this decision is void.
