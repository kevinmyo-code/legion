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
- **Commit identity.** This repo's git config used `REDACTED-work-email@example.com` (a work email). Kevin
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

## 2026-08-01 - Design language: "Instrument" on Material 3's machinery

Context: the city-pop language died with the 2026-07-30/31 pivot and `ui/` was left a clean slate
with no theme, no tokens, and no reference. Nine screens plus four wayfinder tickets were blocked
behind choosing one. Three directions were mocked and compared across the same three surfaces
(home, a dense ledger list, a pantry item carrying macro estimates): Instrument (a dark readout,
mono numerals, hairlines), Dossier (light, serif, documents), and Platform (stock Material 3).

**Kevin's call: Instrument, built on Material 3's machinery.** The two were presented as a fork and
are not one. M3 is a component library plus a token layer; Instrument is almost entirely a retuning
of the token layer, so M3's components, touch targets, accessibility semantics and dynamic-type
handling are all kept and only the tokens change. This is explicitly a rejection of Midnight AI's
approach, where `AriaColors`/`AriaType`/`AriaDimens` were built from scratch and every screen had to
know about them.

Three overrides carry the direction: the **shape scale flattened to near-zero** (M3's 8-28dp radii
and card-first habit cost vertical space and soften exactly the wanted quality; 2dp survives on the
largest roles only because at 0dp a sheet has no visible edge against a near-black ground);
**monospace for anything numeric** (Compose has no `font-variant-numeric: tabular-nums`, so picking
a mono family IS the mechanism for aligning digits, not a style preference); and **one accent**,
with `secondary`/`tertiary` left as neutrals. **Dynamic colour is declined** - it would hand the
signal hue to the user's wallpaper and the signal is the identity.

**Money and provenance roles live outside `ColorScheme`**, in `LegionSemantics` behind
`LocalLegionSemantics`: credit / debit / estimated / quarantined / rule / ruleFaint / faint / ghost.
Material 3 has no vocabulary for these and squatting on `tertiary` would both lie about that role's
meaning and break when a component reads it for its own purposes. Two sub-decisions worth not
re-deriving: **`debit` resolves to plain `onSurface`** because most statement rows are debits and
colouring them all red turns signal into noise; and **`estimated` is a guardrail rather than
styling** (CLAUDE.md §4 rule five), so an explicit label always carries the meaning and the colour
only reinforces it - colour alone fails in greyscale and for colour-blind users.

Built in `ui/theme/` (Color, Type, Shape, Theme, ThemePreview) with `res/values/themes.xml`
retargeted off `Theme.Material.Light.NoActionBar`, which was flashing white on every cold start
against a near-black app. `compileDebugKotlin` green. **No preview has been rendered and nothing has
run on a device.** Left open deliberately: dark-vs-light default (currently forced dark, ignoring
the system, with a working light scheme so daylight use is not broken), icon set, and motion.

---

## 2026-08-02 - The ingested-file ledger: work avoidance, not a correctness barrier

Wayfinder ticket `.scratch/ledger-drive-ingestion/issues/03-ingested-file-ledger.md`, seven calls
with Kevin. Settled the record that stops a folder rescan re-processing statements it has already
seen. Full schema and state machine live in the ticket; this entry records the reasoning that is
expensive to re-derive.

**The ruling that determines everything else: the file record's job is WORK AVOIDANCE.**
Correctness against double-counting stays at the transaction layer, which ticket 04 is fixing
anyway. A file key that is wrong in either direction costs wasted work or a manual re-import; it
can never produce a wrong balance. That is what licenses a metadata-only skip filter instead of
downloading and hashing sixty PDFs on every scan. Had the record been made a correctness barrier,
identity would have had to be a content hash, and two independent correctness mechanisms would sit
in the same path with neither clearly winning.

**Identity is `driveFileId` + `sizeBytes` + `lastModified`, with the `acc=N;` prefix stripped.**
The device probe (same day, ticket 11) established the three facts this rests on. There is no
hash-like column - confirmed against a null projection, not inferred. `acc=N` is a positional
account index rather than an identifier, so storing it would make the key unstable for a reason
unrelated to the file. And `last_modified` is a genuine per-file upload timestamp: five files
sharing one value looked like a useless folder-wide stamp until a sixth, uploaded later, came back
with its own. Without that sixth file the change-signal design would have been built on a
misreading.

**`contentSha256` is recorded but is not the skip key**, and it stops a duplicate **before** the
parser or any Gemini call runs. It costs nothing extra because the bytes are already in memory. The
saved LLM call therefore never enters ticket 06's spend estimate, rather than being estimated and
then wasted.

**Two decisions driven by a probe finding rather than by taste.** The provider returns stale-empty
listings with *no signal at all* - `extras` was `Bundle[EMPTY_PARCEL]` on every query and the
`loading` key the SAF contract suggests looking for never appeared, yet the picker served "No items"
for a folder holding five files. So (1) records are **never pruned**: absence from a scan is not
evidence of deletion, and pruning would convert a display concern into a data-integrity risk, since
the next scan would re-ingest and lean entirely on transaction dedup. And (2) a scan that finds
nothing new is a normal outcome, never an error state.

**Quarantine is escapable only by explicit user action.** Permanent quarantine is wrong because
parsers improve and the LLM is nondeterministic; auto-retry is worse because it silently re-pays for
a Gemini call on every scan of a file that will probably fail identically - the exact behaviour
ticket 06 exists to prevent.

**`ledger_transactions` gains a nullable `sourceFileId`, with no `@ForeignKey`.** Nullable because
the per-file `ACTION_OPEN_DOCUMENT` fallback that `minSdk = 24` makes mandatory has no folder-scan
record behind it. No FK because `onDelete = CASCADE` would let deleting a file record silently
delete committed financial rows. This column is the only mechanism that makes replaced or corrected
upstream statements solvable at all.

**Known tension, recorded rather than hidden.** Ticket 10 states sync must be settled before this
schema is final. It was not. `ingested_files` carries no `syncId` and is final in every respect
except sync. Deferring was judged cheaper than pre-empting 10's ruling, because the additive
migration discipline makes adding the column a one-line `ALTER TABLE` later. Ticket 10 is therefore
still free to rule either way.

---

## 2026-08-02 - Batch ingestion mechanics: two phases, split by what each is bound on

Wayfinder ticket `.scratch/ledger-drive-ingestion/issues/05-batch-ingestion-mechanics.md`, six calls
with Kevin, same session as ticket 03. Full spec in the ticket; this records the reasoning.

**It runs in the existing `AriaForegroundService`, and `androidx.work` was deliberately NOT added.**
The service already declares `foregroundServiceType="...dataSync..."` and the app already holds
`FOREGROUND_SERVICE_DATA_SYNC`, so this costs no dependency and no manifest change. WorkManager's
pitch is durability across process death and reboot, and ticket 03 had already made that cheap by
other means: a killed scan is re-run, not resumed, because known unchanged files cost zero bytes.
With the rescan trigger below, nothing needs to execute while the app is closed either, which
removes the remaining reason to add it.

**The central call is splitting the pipeline by what each phase is bound on, rather than picking one
concurrency number for the whole thing.** Fetch and SHA-256 are network-bound and run **parallel,
limit 4**, because that is where the probe's measured 637-1248ms per file actually goes; a 60-file
first sync drops from roughly 72s to under 20s. Parse, gate and any LLM call are **strictly
serial**, which buys four things at once: peak PdfBox memory stays at one document, the spend gate's
count is exact rather than racing work already in flight, progress stays an ordered sequence instead
of a set of concurrent states, and concurrent Gemini calls never hit a possibly rate-limited key.

**Phase 1 completes for every file before phase 2 starts, spilling bytes to `cacheDir`.** This is
the non-obvious one and it exists to serve ticket 06: staging is what makes an **exact** count of
new files knowable before a single parse runs. The alternative, a bounded pipeline with backpressure,
has better memory characteristics but only ever yields an estimate from metadata, which would have
made the spend gate materially harder. Holding the batch in memory instead of on disk was rejected
on the Oppo's 3-4 GB ceiling. The price is a three-part cleanup obligation (per-entry, per-scan
`finally`, and orphan sweep at next start) that an implementer must not skip.

**A batch is NOT atomic as a whole**, and the ticket says so explicitly because the per-statement
gate makes it tempting to wrap the loop in a transaction. Thirty-nine good statements commit even if
the fortieth quarantines.

**Rescan is a listing-only diff on app open.** One `queryChildDocuments` per connected folder,
diffed against `ingested_files`: zero bytes, zero parsing, zero spend. Unknown ids surface as a quiet
inline count the user taps. This separates *listing* (cheap) from *ingesting* (expensive), which the
ticket's original framing conflated. Against §7: passive in-app surfacing is not a re-engagement
mechanic, whereas a background poll's natural UI is exactly the notification §7 prohibits - and it
would have needed the dependency this ticket just avoided. It also degrades honestly against the
probe's measured sync latency: a just-uploaded statement is often not listed yet, and the count then
says nothing rather than claiming the folder is empty.

**Single-file import is unified into the same pipeline as a one-element run**, which amended ticket
03 (`treeUri` `NOT NULL` -> nullable). The payoff is that a hand-imported statement later found in a
connected folder is caught by the hash check and skipped free. Two separate ingestion paths were
rejected because both would have to honour the reconciliation gate independently and stay correct as
parsers change - and the existing single-file path already has untested DB-write behaviour
(CLAUDE.md §10).

---

## 2026-08-02 - Twin transactions: dedup counts per tuple instead of testing existence

Wayfinder ticket `.scratch/ledger-drive-ingestion/issues/04-twin-transactions.md`, four calls with
Kevin. Full spec and test matrix in the ticket; this records the reasoning and the one finding that
changed another ticket.

**`lineRef` cannot carry dedup weight, and that had to be established by reading all three
producers rather than assumed.** `BofaStatementParser` builds it from the first 60 characters of the
line - deterministic but **not unique**, so two identical coffee lines produce an identical
`lineRef`. `DbsStatementParser` builds it from page index plus y-coordinate - deterministic **and**
unique, since two lines cannot share a y on a page. `LedgerStatementAgent` builds it from the index
of a nondeterministic LLM response - **neither**. Any design keyed on `lineRef` would have worked
for DBS, silently failed for BofA, and been undefined for the LLM path. This is the L10 lesson in
miniature: the property looked uniform from the field name and was not.

**The key call: ask "how many", not "does one exist".** `countMatching` returns a boolean-ish
existence check, which is what collapses twins. Counting per
`(accountId, txnDate, amountCents, normalizedDescription)` and inserting `max(0, N - M)` resolves
twins and overlapping statements with the same arithmetic and no special cases: two coffees in one
statement give N=2 M=0 so both survive, while a year-to-date statement restating them gives N=2 M=2
so nothing double-counts. Chosen over an occurrence-ordinal column because it needs no new column on
`ledger_transactions` and **no `lineRef` stability at all**, so it behaves identically for the
deterministic parsers and the LLM path.

Normalization (trim, collapse whitespace, uppercase) is **comparison-time only** - the stored
description is never modified - and the comparison runs in Kotlin over a date-ranged fetch rather
than in SQL, which avoids a stored normalized column and keeps the rule in one testable function.

**It errs toward DROPPING, deliberately, and that is recorded rather than silent.** Two genuinely
separate identical purchases straddling two statements collapse into one. That is accepted because
an overlapping monthly-and-YTD pair is routine while two truly separate identical purchases on the
same date in different statements is rare, and because the data genuinely cannot distinguish them.
`ingested_files.duplicatesSkipped` makes it auditable per file.

**The finding that mattered most was not in the ticket: counting opens a hole in ticket 03's replace
flow.** Because an overlapping statement can contribute zero rows, a transaction attested to by two
statements exists under only one `sourceFileId`. Ticket 03's
`DELETE FROM ledger_transactions WHERE sourceFileId = :id` would destroy it, and since the other
file is already `INGESTED` a rescan skips it, so it never returns - silent financial data loss.
Fixed by resetting overlapping `INGESTED` files for the same account to `NEW` on any replacement,
bounded by new `minTxnDate`/`maxTxnDate` columns so it does not re-ingest a whole account. **This
was found by grilling, not by reading the code**, and it is the second amendment ticket 03 took in
one session. Worth generalising: when a dedup rule changes what gets written, every delete path that
assumed one-row-one-owner has to be re-examined.

Installed base is zero (never run on a device, DB at v3, nothing released), so no backfill.

---

## 2026-08-02 - LLM spend gate: split the dispatcher so the count is exact and free

Wayfinder ticket `.scratch/ledger-drive-ingestion/issues/06-llm-spend-gate.md`, four calls with
Kevin plus one `analyst` dispatch. Full spec in the ticket. Reasoning worth keeping:

**The gate's placement was the whole decision.** Placed after staging (where ticket 05 had put it),
only the new-file count is known, so the gate must quote a worst case - "up to 60 files may need AI
reading" - when the truth is usually zero. Inflated warnings train click-through, which defeats the
gate. Placed after deterministic parsing, the count is **exact and cost nothing**, because
deterministic parsers never call Gemini. `StatementDispatcher` splits into `dispatchDeterministic`
and `runLlm`. Everything else in the ticket follows from that.

**A recognize-only pass was rejected on evidence, not taste.** Both parsers need full PDF text or
word extraction before they can judge a layout, and that extraction is the dominant cost.
`DbsStatementParser` only discovers an unrecognised layout after iterating pages. So a recognition
API would add a second code path that can drift from the real parser, to save almost nothing.

**"Ask every time, never remember" became defensible only because of the split.** Recognised
BofA/DBS statements never reach the gate, so a monthly rescan prompts zero times. Prompt frequency
tracks actual spend rather than activity. Per-folder memory was rejected because one click
permanently disarms the gate on the folder that will hold every future statement, and the user will
not remember doing it.

**The analyst refused to state a price, and that was the right call to respect.** No pricing data in
the repo, no live access. Its order-of-magnitude figure came from training-era pricing for an older
Flash-Lite generation and is not confirmed current nor confirmed to be this model's price. **No
constant was adopted.** What it did establish `traced`: model is `gemini-3.5-flash-lite`,
non-streaming `:generateContent`, ledger sends extracted *text* not PDF bytes, and the fixed prompt
scaffold is 884 chars / ~221 tokens. Token estimates per file are `reasoned` only.

Two things the analyst caught that were not asked for. The probe's 164-267 KB file sizes are **disk
bytes** including PDF structure and embedded fonts, so they are unusable for token math and would
overstate input by roughly an order of magnitude (they remain correct for ticket 05's staging
figure, which is disk). And **N, the fallback file count, dominates the batch total**, not token
estimation - which is exactly the input the dispatcher split makes exact. The weakest input became
the most certain one.

**`SubAgent` discards `usageMetadata`.** Gemini returns it on every call. Parsing it (three
integers) turns the cost estimate from a reasoned number derived from a reasoned number into one
calibrated by measured tokens after a single real batch. It also answers sub-question 6 - money
spent on a call that still quarantined becomes visible instead of silent. Shared with pantry and the
vehicle agents, so cost visibility improves everywhere; deliberate scope widening.

**Generalisable trap: a "skip anything already recorded" rule collides with any gate that lets the
user say no.** Declining had to become a fifth state exempt from the skip rule, or declining once
would have meant declining forever. Third amendment ticket 03 took in one session.

---

## 2026-08-02 - App shell and ignition: user-initiated, keyless-usable, no onboarding

Wayfinder ticket `.scratch/ledger-drive-ingestion/issues/07-app-shell-and-ignition.md`, five calls
with Kevin. Full spec in the ticket. Reasoning worth keeping:

**Ignition is an explicit user toggle, and `BootReceiver` was deleted.** `BootReceiver` called
`startActivity(MainActivity)` on `ACTION_BOOT_COMPLETED` - car-launcher behaviour that outlived the
phone pivot and nobody had removed. On a phone an app that opens itself every boot is hostile and is
the manufactured-return shape §7 prohibits. Starting the service from `MainActivity.onCreate` was
rejected because opening the app to glance at a ledger would silently start mic capture and a Live
session with no off switch short of force-stop; from `MidnightApplication.onCreate` because the
process starts for reasons the user never initiated.

**The key is OPTIONAL and there is no first-run wall.** The unlock for this was noticing how little
of the app actually needs a key: deterministic ledger parsing, pantry, OBD and saved places all work
keyless. Only the assistant and ticket 06's LLM fallback need one. A key wall would block a stranger
who only wants to import bank statements behind a Google Cloud signup, and clone-and-run is a hard
requirement, not a preference. So the key is requested at the point of use.

**FACT correction worth not re-deriving: the 1-token validation ping exists and is not where
CLAUDE.md said.** It is `ai/GeminiKeyValidator.kt`, returning `VALID`/`INVALID_KEY`/`NETWORK_ERROR`
via a `maxOutputTokens = 1` call. `ai/KeyVault.kt` is AES/GCM encrypt/decrypt only. CLAUDE.md §3's
tech-stack row attributed the ping to `KeyVault.kt`; **corrected in the same commit.** The
three-way result is exactly right for the key screen - a typo and an aeroplane need different
recoveries.

**The free-tier training disclosure carries over.** It was never about commercial tiers, so the
pivot killing the commercial model did not kill the disclosure. It is a factual statement that
Google's free tier may train on submitted content, and here that content is the user's own bank
statements and receipts. It belongs on the key screen, at the moment of consent, not in an About
page where it is present but useless.

**Onboarding deferred, deliberately, with the reason stated:** `OnboardingFlow.kt` is a scripted
conversation in the assistant's voice, and `AssistantIdentity.kt` is placeholder copy by its own doc
comment. Wiring the host UI now would have meant writing the assistant's entire register by accident
inside a ticket scoped to app structure. Side effect: `OnboardingState.isComplete` proxying
"has a Gemini key" was a placeholder and is now **honest**, because the key genuinely is the only
gate on assistant features.

Shell is one activity, Compose Navigation, four tabs, with the three orphan `exported="false"`
activities absorbed as routes - their content is already written, only the hosting changes.
Assistant is a global toggle rather than a tab because it is a mode, not a place. `MainActivity`
also finally switches from `MaterialTheme` to the `LegionTheme` built in ticket 02, which no screen
was using.

---

## 2026-08-02 - Ledger UI: readability beats density, and the render found what code review would not

Wayfinder ticket `.scratch/ledger-drive-ingestion/issues/08-ledger-ui.md`. Prototype branch
`proto/ledger-ui` (`476318e`), **not merged**. Three radically different transaction lists rendered
on the Oppo A17K at 360dp.

**Variant B "Stream" won: no columns, description gets full width and never truncates, amount
beneath it, running balance dropped from the row entirely.** It costs roughly 3x the vertical space
of the densest variant and that was accepted on purpose. The reasoning: on a phone the merchant
string is the thing being scanned for, so truncating it is the actual failure mode, and every
column-based layout truncates it at 360dp.

**Three defects the render exposed that reading the code would not have.** This is the value of the
prototype and worth remembering as a pattern:

1. The statement-style variant **wrapped** `-1200.00` onto two lines. Three numeric columns (amount
   and balance, plus a date gutter) simply do not fit at 360dp with a real description.
2. That variant also **inverted its own hierarchy** - the running balance rendered visually heavier
   than the amount, so the derived number dominated the real one.
3. **BofA descriptions are prefix-heavy** (`CHECKCARD 0701 TRADER JOES #452 SAN JOSE CA`), so
   right-truncation strips the merchant and keeps the boilerplate. That is a data-shape finding
   independent of any variant, and it argues for display-time prefix stripping. Display only - the
   stored description is never modified, matching ticket 04's normalization rule.

**Balances are per-currency and never combined.** No FX anywhere, with a visible line saying so. An
invented headline number combining SGD and USD would be exactly the unstated-value problem §4 rule
five prohibits.

**Incidental but load-bearing: the Instrument theme from ticket 02 rendered on hardware for the
first time and holds up.** It had only ever compiled. Mono numerals align down the column, hairlines
read correctly on the near-black ground, `credit` green is the only coloured money. The
"compiles but never rendered" caveat is closed **for the dark scheme only** - light is still
unrendered.

**Honest gap:** folder connection, scan progress, the spend gate, quarantine rows and the three
empty states were built but **never visually reviewed** - the phone re-locked between captures.
They are provisional, not settled. The three distinct empty states matter most: "no folder" /
"nothing new" / "folder looks empty, Drive may still be syncing", the last existing because of the
probe's stale-listing finding and required never to read as an error.

---

## 2026-08-02 - Fleet and pantry screens: segregate guesses from the document

Wayfinder ticket `.scratch/ledger-drive-ingestion/issues/09-fleet-and-pantry-ui.md`. Prototype
branch `proto/fleet-pantry-ui` (`07abbdf`), **not merged**, rendered on the A17K at 360dp.

**The load-bearing decision is pantry macros, and it is a §4 rule five guardrail, not styling.**
Two treatments were built. The dense one puts macros under the product name with an `est.` prefix in
`semantics.estimated` amber. The chosen one physically separates them: `ON THE RECEIPT` and
`ESTIMATED, NOT ON THE RECEIPT` as two blocks, with a sentence between them saying a receipt never
prints nutrition and these were not checked against anything.

**Why segregation beat the compact version:** inline, the estimate shares a row with a real price,
so at a glance the two read as equally solid. Segregation makes that mistake **structurally
impossible** rather than merely discouraged. It also satisfies the part of rule five that colour
alone is never sufficient - the sentence carries the meaning and the amber only reinforces it.
Accepted cost: roughly double the vertical space, repeated item names, and two places to look for
one item.

**Fleet is honest about hardware it has never talked to.** The LIVE block carries an explicit
connection state and per-row last-seen timestamps ("No OBD adapter connected. Values below are the
last seen reading", "3 days ago"), because nothing in the OBD stack has run since the port. A screen
that implied a live reading it does not have would be the same class of dishonesty as an unlabelled
estimate.

**Data that exists with no UI gets an explicit ghosted NOT BUILT row** naming what exists and why it
is not shown ("Service history - 12 records in the database, no screen"). Not dead space, not
hidden, not faked. Same posture as the rest of the project.

**Four shared components identified for `ui/common/` before the three aspects diverge:**
`SectionHeader`, `Hairline`, `ReadingRow`, `NotBuiltRow`. Validated across both aspects and matching
what ticket 08's ledger list already needed.

**Render defect worth remembering as a pattern:** the fault *description* was coloured
`semantics.quarantined` and rendered at reading size, so it visually outshouted its own code
`P0442`. A description is not an alarm state. Semantic colours are for states, not for the text that
happens to sit next to one.

Incidental: the prototype host now holds `FLAG_KEEP_SCREEN_ON` and shows over the lock screen, which
fixed the silent blank-capture problem that lost half of ticket 08's screenshots.

---

## 2026-08-02 - Ledger sync: both tables sync, and the append-only blocker was wrong

Wayfinder ticket `.scratch/ledger-drive-ingestion/issues/10-does-ledger-data-sync.md`, three calls
with Kevin. **The ticket's instruction to read `SyncEngine`/`SyncMerge` rather than decide from doc
comments is what made this ticket worth doing** - the code contradicted the project's own recorded
blocker on three counts.

**What `memory/MEMORY.md` said, and what the code actually does** (all `traced` from source):
"Drive has no compare-and-swap" - but `DriveConflict` + `DriveClient.upsert` implement
version-checked optimistic concurrency, with a **version re-check as the real guard** because Drive
v3's `If-Match` is undocumented, plus retry up to `MAX_CONFLICT_RETRIES`. "Last-write-wins will
silently lose rows" - but `syncFile` re-merges remote into local, re-reads, and uploads the merged
result; it is a read-merge-rewrite loop, not a blind overwrite. "Sync must become append-only" -
but `SyncMerge.Mode.UNION` **is** append-only and was **already in use by 8 tables**. A blocker that
had been carried forward as a hard constraint since the port dissolved on contact with the source.

**`ledger_transactions` syncs, `Mode.UNION` on `syncId`** - the same shape `service_records` and
`memories` already use. Transactions are immutable once committed, which is exactly UNION's
assumption, so nothing is ever overwritten. The `syncId` whose doc comment pointed at a sync feature
nobody had wired is finally the identity it was put there to be.

**`ingested_files` syncs, `Mode.LWW`, natural key `driveFileId`, and needs NO `syncId` column.** This
closed ticket 03's long-deferred question **by removing it rather than answering it**. The reason is
worth keeping: 03 stripped the positional `acc=N;` prefix purely because it was an account index
that could shift under a second signed-in account - and that unrelated decision left the stored
value identical on both devices for the same file, i.e. a genuine cross-device natural key. LWW
rather than UNION because the record is a state machine that legitimately changes; UNION would pin
it to whichever state propagated first, so a retried or replaced file would never update across
devices. Side effect: `sourceFileId` no longer dangles, because both sides of the reference sync.

**The blocker was narrowed, not deleted.** What genuinely remains: after `MAX_CONFLICT_RETRIES` the
loop calls `check(...)`, which **throws**, and nothing reports it since Firebase is not wired and
`MidnightEvents` only logs. Making that log-and-skip-the-pass is in scope for this effort precisely
because ledger is now the worst thing in the app to lose. Everything else in `sync/` is out of
scope, and pantry stays unregistered as a separate call.

**Standing caveat, unchanged: none of `sync/` has ever run in this app.** It compiles and is ported.
Every claim above is `traced`, none is `tested`. Registering two tables does not change that.

**Generalisable: a blocker recorded from doc comments outlived the code that disproved it.** Re-read
the source before treating an inherited blocker as a constraint on a new decision.

---

## 2026-08-02 - Note: research carried over from the lost map

Moved off `memory/MEMORY.md` during a trim so it is not silently lost. Two research items were
inherited from the destroyed `.scratch/multi-aspect-assistant/` map via a librarian digest, and were
flagged **worth re-verifying before building on**: NOAA weather research, and Drive-access research.

Status as of today: **the Drive-access half has been superseded and does not need re-verifying** -
ticket 01 redid it from source against a disassembled Drive APK, and ticket 11 then confirmed the
crux on hardware. **The NOAA item is untouched and still unverified.** It is unrelated to the ledger
effort; `weather/WeatherController` uses Open-Meteo (keyless) per CLAUDE.md §3, so nothing currently
depends on it.

## 2026-08-02 Ticket 07 theme defect: errorContainer collision

**Context:** Ticket 07 (shell and key-entry UI) rendered all body text in quarantine red, including the first-run consent copy, due to a Material 3 theme collision. The dark scheme assigned `InstrumentSurface` to both `surface` and `errorContainer` roles. `contentColorFor()` resolves by value and tests `errorContainer` before `surface`, so the collision winner was red.

**Decision (Kevin):** `errorContainer` set to `InstrumentSurfaceSunken`. This breaks the immediate collision and routes `contentColorFor` to the intended `onSurface` ink. **Tag: `on-device`** - the cause is `traced` to source, and the fix was rebuilt, installed and screenshotted on CPH2471, so the red is confirmed gone rather than merely reasoned away. Known residual: the new value collides with `secondaryContainer`/`surfaceVariant`/`surfaceContainerLow`, so the general problem recurs at ticket 08 (quarantine UI must set `contentColor` explicitly).

## 2026-08-02 Ledger ingestion: IngestScanner moved to LedgerIngestService (architectural decision)

**Ratified by Kevin, explicit, same session.** `IngestScanner` was initially placed in `AriaForegroundService` in Part 4 (commit 512823a). Part 6 (commit 4272146) moved it out into a new `LedgerIngestService` (foregroundServiceType=dataSync, bind-driven from the Ledger tab, no mic/GPS/Live socket startup).

**Reason, traced by reading `AriaForegroundService.onCreate()`:** That method unconditionally boots the entire voice stack on every process start - mic prewarm, a Gemini Live socket, GPS, telephony, a weather loop - with NO check of `AssistantIgnition`. Binding `IngestScanner` (which lives at Ledger tab tap time) from the voice service would have started the assistant with the toggle explicitly OFF, violating ticket 07's resolution: "a refusal means assistant off, nothing else affected." The original placement was harmless only because nothing bound to the service; once the Ledger tab wired `startService()` + `bindService()`, the architecture became unsustainable.

**The general principle:** Do not place feature-triggered services inside an unconditionally-started infrastructure service. Separation of concerns. A user flipping a toggle must not side-effect unrelated infrastructure.

## 2026-08-03 - Ledger ingestion complete: BofaCardStatementParser, folder mapping, DBS verified, overlap risk open

**Status: All three of Kevin's real statement formats now parse deterministically.** BofA checking PDF, BofA credit card PDF, BofA mid-cycle checking CSV, and DBS/POSB Singapore PDF all ship with zero LLM spend on real files (commits `4dad45f`, `537bd49`, `ac40183`).

**BofaCardStatementParser: three reconciliation anchors, all verified exact on real July statement.** Per-section subtotals; the summary identity `Previous + Payments + Purchases + Fees + Interest == New Balance Total`; all rows summing to net movement. Real result: 54 rows, −940.68, DETERMINISTIC. **Kevin's explicit call, same session: statement-only for the credit card.** Daily expenses move to debit. The card's mid-cycle CSV export is refused outright rather than parsed or sent to the LLM — it prints no balance and no total, so it carries zero reconciliation anchors and can never pass the gate.

**First round of the card parser shipped with L14's vacuous-gate bug** (see `library/lessons.md` L14 for full write-up; rule 6 graduated into CLAUDE.md §4). BofA prints Interest Charged rows in a different shape with no reference or account column. The row regex missed all four, the section check compared zero parsed rows to a printed $0.00 and passed. Found by counting: raw regex probe of the real PDF found 54 date-led lines where the parser returned 50. Fixed with a bare form for unmatched rows and a hard-fail rule: every non-blank line inside a recognized section MUST parse as a row.

**Folder mapping: changed from "conflicts quarantine" to "fills a gap only" (commit `537bd49`).** Kevin moved his statements into Drive as `Bank Statements > USA Bank Statements / Singapore Statements` and stated he will not split per account. The old `accountConflict` check quarantined any file whose printed account disagreed with its folder mapping, assuming one account per folder. Now the hint only reaches `BofaCsvStatementParser`, the one parser with no printed account; the distinction is structural, not a comparison. A document's own printed account is a stated falsifiable fact and always wins.

**First real DBS run verified exact (commit `ac40183`).** All 14 transactions found, page-split table handled. Money: withdrawals 15,424.58, deposits 5,350.44, opening 14,715.02, closing 4,640.88, parser sum ties to the cent. One cosmetic defect fixed: PdfBox emits the statement's rotated sidebar text as lines of single characters. One landed between the last transaction and totals where the description accumulator absorbed it, producing `Interest Earned 4 4 4 4 4`. Fixed with an all-single-char-token artifact guard that skips the line without ending description.

**Open finding, not yet fixed: PDF/CSV overlap double-counting.** Measured on Kevin's real files: the July checking PDF covers 06/05–07/06 (16 rows), the CSV covers 07/01–07/31 (12 rows). Three transactions overlap; `resolveDedup` caught 2 of 3. One slipped through because `dedupKey` includes the normalized description and BofA words the same transaction differently: `PURCHASE 0706 VPN24.ME EDINBURGH 00` (PDF) vs `VPN24.ME 07/06 PURCHASE EDINBURGH 00` (CSV), same date, same −8.99. This will recur monthly. Options under consideration: drop description from dedup key (risks genuine duplicates); match on account+date+amount only for CSV-vs-existing; or record each statement's covered period and treat a CSV row inside a reconciled statement's period as already accounted for. The third is the only one that is stated fact rather than guess, per §4.

**Architectural note: sync/ was found structurally unreachable (commit `7ea4725`).** `CompanionProfile.setSyncEnabled` had zero callers, so `SyncCapability.syncAvailable` was always false and `SyncEngine.maybeAutoSync` returned immediately. Nothing launched the Drive consent PendingIntent either; `DriveAuth.tokenFromConsent` had no callers outside its own file. `MainActivity.onResume` had been calling `maybeAutoSync` into a closed door. Built a Connect Google Drive screen (modelled on `KeyScreen`) plus manual "Sync now". Sync only enables once a token is actually in hand, never optimistically. **Still device-unverified**: the consent round trip, a real Drive pass, and Play Services on the A17K.

**Process findings worth keeping for next session:** The working loop that found every bug: run the user's real file, count rows independently of the parser, fix, make the fixture match reality, re-run. Four real-statement bugs in two days, all found this way, none by any other gate. Real statements are copied into `app/src/test/resources/ledger_fixtures/` temporarily, run, then deleted — never committed. A subagent committed a 419-line parser alone, without its tests/fixtures/wiring, leaving HEAD with unreferenced code. Caught and restructured into two coherent commits via `git reset --soft`. Kevin's standing decision: no card-number masking for now (full PAN lands in Room and syncs to Drive as `accountId`).

### 2026-08-03 (session 3, addendum) - Drive OAuth blocks the owner, not only strangers

The clone-and-run blocker "Drive's Android OAuth client is keyed to package + SHA-1" was recorded as
a problem for a STRANGER building their own copy. First real device attempt showed it blocks Kevin
too: Connect Google Drive brought up the account picker, sign-in succeeded, and nothing enabled.
`com.kevin.legion` is a new package and its debug keystore was created 2026-08-01, so no OAuth
client existed for that package + cert pair, and there is no `google-services.json`. Debug SHA-1
`AEC022FB19028BB466490D9E2F7BC725EE84F55B`. Kevin created a client id the same day; it is NOT yet
confirmed to be an Android-type client, which is the thing that matters (a Web client cannot work -
the id string has nowhere to live in the app, Google matches on package + signing cert alone).
Still needs the Drive API enabled, the `drive.appdata` scope on the consent screen, and his own
account as a test user while the app is in Testing.

Second, separable finding: the screen could not say ANY of that. `DriveAuth.tokenFromConsent` was
`runCatching{}.getOrNull()`, so DEVELOPER_ERROR (status 10) was indistinguishable from the user
dismissing the dialog. Fixed in `7a9acc6` - Token/Cancelled/Failed carrying the real Throwable, and
plain-language categories on the screen plus a `MidnightEvents` log, since there is no crash
reporter. The status-10 diagnosis itself is INFERRED from the API shape and has never been read off
a device; the next connect attempt is what confirms or refutes it.


### 2026-08-06 (session 5) - A provisional tier under the reconciliation gate

Kevin asked for LLM ingestion of a CSV of recent transactions. Reading his real file
(`currentTransaction_7823.csv`, read then left alone, never copied into the repo) showed it is the
one case already refused BY NAME: Bank of America's mid-cycle card export, which
`BofaCardCsvStatementParser` existed solely to reject. 40 data rows, 38 debits, 2 credits, CRLF, no
balance column, no total row, no account number anywhere in the body (only in the filename).

**Two findings reframed the ask.** First, an LLM cannot help: the gate was never failing on
extraction, it was failing on the absence of anything to extract against. A model would read all 40
rows correctly and hit the same "prints no balances or a total to verify against" refusal, having
spent tokens; the format is a fixed 5-column CSV that needs no model to parse. Second, the ask was
therefore not an ingestion problem at all but a request for a §4 rule 2 exemption, which is Kevin's
call and not the executor's. Surfaced as such rather than built.

**Kevin's four calls (all recommendations taken except the last):** deterministic parser, not LLM
and not both; `accountId` from the filename's last-4; provisional rows deleted when a reconciled
file commits over the same window; and provisional rows INCLUDED in the displayed balance with the
balance marked provisional (he declined the recommended "excluded from sums" - the mid-cycle figure
being current is worth more to him than a total that is verified by construction).

**Rule 7 graduated into CLAUDE.md §4** in the same commit: a source that states no anchor may be
stored provisionally, never as fact, on four conditions that only work together - deterministic
extraction, an `UNRECONCILED` tag, said in words on every surface that renders one, and deletion
when a gated file covers the same dates. It narrows what "commit" means without widening what
"verified" means. The failure being guarded against is not storing a weak row; it is storing one
that later reads as strong.

**The collision worth remembering.** Calls 2 and 3+4 do not compose: the CSV's `accountId` is
`"7823"` and the card PDF's is `"4111111111117823"`, and every ledger mechanism keys on equality, so
supersede would never fire and one card would render as two accounts - the same bug the whitespace
strip in `LedgerStatementAgent` was written to fix, through a different door. Resolved by a last-4
suffix relation (`ledger/LedgerAccountIdentity.kt`, `sameCard`) in exactly three places - the
supersede delete, the balance pairing, the balances grouping - and deliberately NOT in
`resolveDedup`, where loosening the key would let a checking account ending 4146 absorb card rows.
Documented weakness: two accounts sharing a last-4 collide. Four accounts today, no collision.

**Ordering property that is load-bearing and invisible.** The supersede delete runs inside
`db.withTransaction`, guarded so a provisional file never supersedes anything, and strictly BEFORE
`getForAccountInRange` feeds `resolveDedup`. Wrong order and the reconciled statement's genuine rows
match the provisional rows as duplicates and get dropped - the verified row discarded in favour of
the unverified one, exactly backwards.

**Widening a TEXT-stored enum is not a migration.** `ingestMethod` is `TEXT NOT NULL` with no CHECK
constraint, so `UNRECONCILED` changed no SQL, the identity hash held, and the DB stayed at v5 - the
schema JSON was byte-unchanged after a kapt run. Also corrected while in CLAUDE.md: it claimed Room
v3 for a database that has been at v5 since `companion_profiles`.

Ticket: `.scratch/ledger-drive-ingestion/issues/12-provisional-card-csv.md` (added outside the
original 11). **Device-unverified**: the previews were never rendered and nothing has run on the
phone, both carried as named follow-ups rather than closed.

### 2026-08-06 (session 5, addendum) - Fleet-wide voice: three calls closed

**Car tasks stay GLOBAL (Kevin).** `CarTask` has no `vehicleId` column - the to-do list has never
been per-car - so `add_car_task`/`complete_car_task`/`remove_car_task`/`list_car_tasks` cannot take
the `vehicle` argument the other 11 stored-data tools got. Making them per-car needs a Room v6
migration. Ruled not worth it; the four tools stay unscoped and say so in their descriptions. This
closes the blocking item the fleet ticket surfaced rather than leaving it open.

**Persona survived the per-car-to-global promotion (Kevin, on device).** This was the highest-risk
unverified claim in the fleet work - `CompanionProfile` moved from per-car keys to flat keys with a
one-time promotion, and Kevin's configured Alfred/Dorothy setup was the real data at stake. He
confirmed it. Verification gate 5 is CLOSED, `on-device`, not inferred from the unit tests.

**Alfred and Dorothy now carry an explicit British delivery.** `Persona` gains a `delivery` field -
accent and idiom, separate from `clause` (who is speaking) because it steers how the voice sounds.
Injected by `AriaBrain.assembleBase` next to VoiceStyle's notes, ordered BEFORE them so an explicit
user pick still wins. Not added to `shortClause`: sub-agents are text-only and an accent buys a
one-shot JSON extraction nothing.

**Natural language is the only lever.** The Live API exposes no accent parameter, and the 30
`CURATED_VOICES` presets are not documented by accent - the same finding VoiceStyle.kt already
recorded for pitch and rate. So this is prompt steering: expected to work, not guaranteed, and only
listening settles it. Written concretely (name the vocabulary, name what to avoid) rather than as
"sound British", for the same reason the registers are written as instruction rather than
description.

**Defect found and fixed while in there:** `assembleBase` prepended `CompanionProfile.persona()` raw
to every system instruction. That field holds the persona KEY, not prose (`AssistantIdentity`
resolves it through `personaFor()`), so every instruction opened with the literal token `alfred`
before the register naming him. The legacy `VehicleController.DEFAULT_PERSONA` prose now applies
only when no profile is active at all, which is the one case with no key to resolve.

**Note for the next session:** the base instruction is cached for 2 minutes (`BASE_TTL_MS`), so a
persona or delivery edit is not audible until the cache lapses or `invalidateBase()` fires. A cold
app start is the reliable way to hear a change.

---

## 2026-08-07 - Notes, lists and a local calendar: charting decisions

Charted as `.scratch/notes-lists-calendar/map.md` (10 tickets). Filed at charting time, not at
effort end, per `issue-tracker.md`'s standing warning. All eight settled with Kevin in one grilling
session; none is re-openable by a ticket without him.

**Absorb, don't sit alongside.** `CarTask` and `PlaceReminder` fold into one general notes/lists
domain rather than becoming a fourth system. Kevin's words: "absorb it. basically expand cartask to
include other things. and also the ability to make different lists." Consequence: a `car_tasks`
migration is mandatory, and `syncId` + `deleted` tombstones must survive it or the next sync
resurrects deleted rows from a remote snapshot that never saw them go.

**One model.** A list owns items; a note is a list whose items do not tick. Chosen over two entities
because photo ingestion yields lines either way, voice editing needs one grammar either way, and
`ui/` is the thin part of this app. Accepted cost, stated at the time: a real prose paragraph becomes
one long item.

**The human is the reconciliation gate for a photographed list.** It lands as a draft; nothing is
written until Kevin confirms. **This is a narrowing of CLAUDE.md §4, not an exemption from it**, and
the distinction that does the work is BLAST RADIUS: a misread checklist line is visible, correctable
and poisons nothing downstream, whereas a misread receipt total silently becomes a fact inside a
spend figure that is later trusted. Ticket 06 owns the exact wording, precisely so this cannot be
cited later as "notes didn't need a gate, so neither do we." Rejected alternative: OCR twice and
require agreement - a genuine automatic gate, but it doubles the cost of every photo to catch errors
visible on screen for free.

**An item carries at most one optional trigger**, a time or a tagged place, never both.

**Alarms are local, not Google Calendar.** Kevin initially wanted Google Calendar to BE the
mechanism, which is the better design in a world where the OAuth client works - it outsources all
alarm plumbing. Rejected because that client has never authorized once, so the feature would ship
unusable. Accepted cost: Android alarm plumbing is this map's largest new-platform surface, and the
app has never scheduled an alarm or posted a notification.

**A calendar event is the same entity as a list item**, with optional `startsAt`/`endsAt`. The
calendar screen is a view over rows that have a `startsAt`.

**Recurrence is IN scope**, against the recommendation to defer it. Kevin's call, on the argument
that retrofitting recurrence onto a flat events table is materially harder than designing for it -
which is correct. It is the most expensive decision on the map and it makes the effort larger than
the camping checklist that prompted it. He was shown the cheaper second-wave alternative and
declined it.

**All Google integration is OUT of scope.** Both the Calendar mirror and the Gmail access Kevin
raised mid-charting ("i wanted the ai to have my gmail etc. via mcp or api"). Split off as a future
map. Two facts established while ruling it out:
- **Multi-user needs no backend.** Each install does its own OAuth against its own Google account;
  §7 survives intact. It is the same BYO shape as the Gemini key. The "one shared Google account"
  lock in §2 is about the Drive sync STORE; Gmail/Calendar identities would be per-person, so that
  lock needs amending whenever that map is charted.
- **Gmail read scopes are RESTRICTED**, a different tier from `drive.appdata`. `reasoned`, not
  verified: an OAuth client in Testing status with external user type may expire refresh tokens
  after 7 days, which would force weekly hand re-authorization and probably kills the idea. **That
  is the first research ticket of that future map** and must not be planned around until checked.

**The OAuth client now blocks three things, not one:** Drive sync, any Calendar mirror, and any
Gmail access. Open since 2026-08-01. It is the highest-leverage unblock in the project.

**2026-08-07, later the same day - two amendments to the notes/calendar map.**

**Alarms resolved (ticket 03, research).** Reminders do NOT need an exact alarm, so charting
decision 5's expected cost mostly evaporates: `setAndAllowWhileIdle` is inexact, permission-free AND
Doze-exempt, which is what a personal reminder actually needs. Plain `set()` would have been wrong
for exactly the overnight-into-morning case. `setExactAndAllowWhileIdle` only where the user marks an
item exact, gated on `canScheduleExactAlarms()`, downgrading IN WORDS when refused. Declare
`SCHEDULE_EXACT_ALARM` + `RECEIVE_BOOT_COMPLETED`, NOT `USE_EXACT_ALARM` - identical capability, but
non-revocable, and its Play restriction is a publish-time gate a sideloaded app never reaches.
WorkManager ruled out on facts: Doze blocks JobScheduler outright, so it has no Doze exemption while
`setAndAllowWhileIdle` does. Local facts verified in-repo, not taken on the researcher's word:
`targetSdk 34` (so Android 14 denial-by-default applies), `POST_NOTIFICATIONS` already declared AND
already requested at runtime, zero `AlarmManager` references, and a `BootReceiver` that existed and
was deleted in `legion-shape` ticket 07. **Unverified and load-bearing:** whether the documented
one-hour lateness bound covers `setAndAllowWhileIdle` specifically - the guide never restates it for
that call. Do not promise any delivery figure until it is measured on the device.

**Photo ingestion CUT (Kevin, mid-effort).** "screw it. we dont want the photo ingestion for the
list." It was the origin of the entire idea - a paper camping checklist - and it is gone. Two
tickets closed as OUT OF SCOPE rather than resolved, so neither is a step on the route.

**This WITHDRAWS charting decision 3** (the human as reconciliation gate for a photographed list).
That matters beyond this map: it was the only narrowing of CLAUDE.md §4 anywhere in this effort, and
the only thing on it carrying precedent risk. **Nothing on this map now ingests an outside document
at all**, so no §4 narrowing is needed and none is claimed. Decision 3 was withdrawn before anything
was ever built on it and **must not be cited by a future feature** as licence to skip a gate.

Worth recording honestly: **nothing was learned about whether the model can read handwriting.** The
probe harness was written and compiled against the app's real `SubAgent` path, but was never run - no
photo ever arrived. The question is untouched, not answered. The harness was deleted rather than left
as dead scaffolding. Pantry's receipt vision path is unaffected and stays.

**2026-08-07 - notes/lists/calendar map RESOLVED (all remaining tickets, one batched session).**

Kevin: "lets solve all the tickets at once if possible." Done as a batch of decisions put to him,
not answered for him - the wayfinder one-ticket-per-session rule exists so the agent does not stand
in for the human's side of a HITL ticket, and batching the questions does not breach that. Eight
contested calls put to him; the rest settled on recommendation. **One exception, flagged: the agenda
-vs-month-grid choice in ticket 08 was NOT put to him.**

The three answers that reshaped the map, all Kevin's, none of them my recommendation:

**A recurring item cannot be ticked.** I offered per-occurrence ticking as the recommended answer;
he took "not tickable" instead. It is the single most valuable decision on the map. It deletes
per-occurrence completion state, which deletes the "edit this one or all of them" prompt, which is
the thing that makes recurrence expensive in every calendar app. Recurrence went from the map's
biggest cost to a small stored rule with a skip list. **A repeat is an event you attend, not a chore
you complete.**

**One "Car" list, `category` dropped entirely.** I recommended three lists preserving the
maintenance/project/wishlist split; he took the single pile. Accepted cost, stated at the time:
"what do I need to buy for the car" stops being separately answerable.

**Photo ingestion cut** (earlier the same day, recorded above) - which had already withdrawn charting
decision 3 and with it the map's only §4 narrowing.

Settled on recommendation and accepted: local-only, no sync (`sync/` has never executed, Drive has
no CAS, and two people ticking one packing list is the worst first test of unrun machinery - accepted
cost is that a shared list is not shared, and the UI must SAY so); all four car-task tools retired
(safe, checked: zero `ui/` references, they were always voice-only); alarms per ticket 03; one new
"Notes" destination with the calendar as a view inside it; fuzzy-match voice addressing that refuses
rather than guesses on ambiguity, defaulting to the most recently used list and always saying which.

**Standing constraint that came out of this and outlives the map:** `LiveToolbox` carries 60+ tools
and every one is prompt tokens on every live session, on Kevin's own key. Absorption must be
net-neutral or better on tool count. If a map that retires four tools ends with more than it started,
absorption did not happen - it just moved.

**Map state:** 8 resolved, 2 out of scope, 0 open. One fog patch left (archiving/searching/deleting
an old list). The destination is reached: nothing remains to decide before building.

**2026-08-07 - archiving and list reuse (ticket 11), plus a correction.**

Kevin: *"archived. eventually we want to have a master camping list that we can reproduce for
subsequent trips."* One sentence closed two fog patches, which graduated together into one ticket.

**Archived, not deleted**, behind a SHOW ARCHIVED toggle - reusing `ui/CarsScreen.kt`'s existing
pattern rather than inventing one. Archiving is a separate axis from the `deleted` sync tombstone.

**A "master list" is NOT a new concept: it is an archived list you copy.** Rejected a template flag
(a third state per list) and a separate template entity (a second table duplicating `ListItem`, which
would have been the first real crack in the one-model decision). The cheapest answer that fully does
the job.

**A copy carries text and order; it resets ticks and drops dates, repeats and place triggers - and
gets a NEW `syncId`.** That last one is not cosmetic: inheriting a `syncId` would make two different
rows claim one cross-device identity, and the next sync would read one as a stale edit of the other.
Resetting `done` is the load-bearing part - a packing list that starts half-done is the one failure
a packing list must not have.

**The master does not learn.** Copy and original are independent from the moment of copying.
Rejected automatic write-back (editing one list silently changing another) and an offer-at-archive
prompt (needs provenance, arrives when you least want a question). **Accepted cost, said plainly at
the time: packing up a wet tent, Kevin will not remember to write the lesson back, and it will be
lost.**

**Correction to the previous entry.** It claimed archiving was "the only decision left on the map".
Wrong - "what a fired reminder actually does" was also still fog, and ticket 03 settling the alarm
mechanism had made it specifiable. Now charted as ticket 12 and **it is the genuine last open
decision.** Filed here rather than silently fixed, because the previous entry's claim was the kind
of tidy-sounding overstatement this library exists to prevent.

**2026-08-07 - what a fired reminder does (ticket 12). THE MAP IS COMPLETE.**

Last open decision. All four calls went to Kevin and all four took the recommendation.

**A fired reminder changes nothing on the item** - it stays open until ticked. Firing is a
notification, not an action: "call the plumber" reminding you does not mean you called them.
Rejected auto-tick (a list that claims you did something you did not is worse than no list) and a
third fired-but-not-done state.

**A missed reminder is reported, never silent.** Ticket 03 established boot recovery recomputes
forward from now, so a one-off due while the phone was off is genuinely gone. It goes in a MISSED
section on next open. **This was rejected on the repo's own history rather than on taste**: silent
vanishing is the exact failure shape of `sync/` (structurally unreachable, passed every test) and
categorization (fully built, never wired, a month reading "uncategorized" with no way to act). A
reminder that quietly does not happen would be the third instance of the same bug. Consequence: the
missed state must be STORED, because it cannot be recomputed afterwards.

**Its own notification channel at `IMPORTANCE_DEFAULT`+.** Both existing channels are
`IMPORTANCE_LOW`, which makes no sound; a silent reminder is not a reminder.

**Snooze lives on the notification**, one fixed interval, no picker - and must not be labelled with
false precision while `setAndAllowWhileIdle`'s real lateness is unmeasured. **Alfred speaks a
reminder aloud** at a turn boundary when a live session is active, because that is when a posted
notification is invisible (driving), and the notification still posts regardless.

**MAP STATE: 10 resolved, 2 out of scope, 0 open. Destination reached** - nothing remains to decide
before building. One fog patch survives and it is a build-time VERIFICATION, not a decision: measure
`setAndAllowWhileIdle`'s real lateness on the device.

**ADB is back up 2026-08-07** (OnePlus CPH2471). Two facts established immediately:
- **The real database on Kevin's phone is at `user_version = 7`**, read from the file header (no
  `sqlite3` on device; `run-as` + `dd` + `od`). So installing the current build runs v7 -> v8 -> v9
  against his real data - 5,279 fleet rows, his statements, the Outlander - for the first time.
- **A backup of the v7 database (main + `-shm` + `-wal`) was pulled to the scratchpad**, deliberately
  OUTSIDE the repo so real financial data can never be committed.

**Sequencing trap, recorded because getting it backwards is unrecoverable:** `connectedAndroidTest`
WIPES app data (existing device quirk). Running the migration tests first would destroy the genuine
v7 database those migrations most need proving against, leaving synthetic tests passing on invented
data. Correct order: back up, install over the top to exercise the REAL migration, verify row counts,
and only then run `connectedAndroidTest` and restore.

---

## 2026-08-07 - Cyberdeck UI overhaul charted; charts are hand-rolled Canvas

**The front end is being overhauled** (Kevin): new aesthetic (diegetic cyberpunk cyberdeck,
biohacking frame), new visualization layer, shipped on-device before the map closes. **The
2026-08-01 Instrument design-language decision is superseded.** Grilled and locked at charting:
diegetic dial at 2-of-3 with rationed theatre; dark-only; chrome speaks deck, Alfred's register
untouched; utility screens inherit tokens only; no new data collection. Map:
`.scratch/cyberdeck-ui/map.md`, ten tickets.

**Chart rendering: hand-rolled Canvas/DrawScope, no library** (ticket 02, research subagent).
`TelemetryChart` already proves the pattern; libraries fight the aesthetic and force
Kotlin/Compose bumps (KoalaPlot needs Compose 1.10/Kotlin 2.3 vs repo's 2024.05.00/2.1.0,
`traced`). Escape hatch recorded: Vico v2.5.2 if pan/zoom/markers ever needed. Estimate 350-550
lines for `DeckLineChart`/`DeckBarChart`/`DeckSparkline` (`reasoned`).

---

## 2026-08-07 - The deck is MILSPEC

**Design language for the cyberdeck overhaul: MILSPEC** (Kevin, from a three-direction artifact
comparison; CLINICAL and STREET declined, no hybrid). Avionics console: phosphor amber `#FFB000`
on green-black `#0A0D08`, warm-bone ink, stencil caps, 1px borders with 2px corner brackets,
dashed row rules, chunky meters with pace ticks, checklist status copy (`NO LINK`, `0
QUARANTINED`). Green `#7FBF3F` and red `#FF5330` exist in the palette but their SEMANTIC mapping
is deliberately deferred to the semantic-color ticket. Full token table:
`.scratch/cyberdeck-ui/issues/01-deck-design-language.md`. This supersedes Instrument as the
thing `ui/theme` implements.

---

## 2026-08-07 - Deck semantics: amber = data, green = good, red = needs you

**Semantic color for the MILSPEC deck** (grilled, Kevin). Red `#FF5330` means intervention
required EXCLUSIVELY (quarantine, failed ingest, crisis) - never debits, never over-budget.
Green `#7FBF3F` is the good/armed family including credits. Amber `#FFB000` is every other
value; amber inverted tags carry advisories. **Exception tagging**: verified rows are untagged,
sections state the default (`ALL ROWS RECONCILED`); tags mark drops in confidence
(`EST`/`REPORTED` muted outline, `UNRECONCILED` amber inverted + worded aggregates per §4 rule 7,
quarantine red inverted). Universal states (Body = all REPORTED) stated once in the panel header.
`LLM_RECONCILED` = `DETERMINISTIC` at list level. Ticket:
`.scratch/cyberdeck-ui/issues/03-semantic-color.md`.

---

## 2026-08-07 - Deck motion: boot on cold start, one ambient cursor, three theatre moments

**Motion vocabulary for the deck** (grilled, Kevin). Boot sequence on COLD START ONLY (~800ms,
tap-to-skip; warm returns instant). One-shot ~350ms draw-ins for meters/charts on screen entry,
never looping. Exactly ONE continuously animating element app-wide: the top-bar block cursor.
Full-theatre effects spent on exactly three moments: boot (scan sweep), ingest commit (panel
sweep), quarantine (red glitch - §4's arresting state). Reduced-motion/animator-scale-0 collapses
everything to instant and must still render a complete UI. Ticket:
`.scratch/cyberdeck-ui/issues/04-motion-vocabulary.md`.

---

## 2026-08-07 - Shell is a hard-key row; driving mode enters the map

**Navigation shell** (Kevin, two-option artifact comparison): the bottom bar becomes five
stencil hard-keys - HOME / BIO / LOG / FLEET / CRED - active key inverted amber, existing
NavigationBar wiring underneath. Global status line (`SYNC OK · OBD NO LINK · KEY ARMED` + clock
and the one blinking cursor) tops every screen; Alfred's strip stays pinned above the keys.
Module-launcher home declined (two taps per move, duplicated Today).

**Driving mode is new scope** (Kevin, same session): when the OBD dongle is connected, the
OPTION (not auto-switch) of a driving-style UI. Ticket 11 on the cyberdeck map; glance-first,
voice-primary, GlanceCardController/Phase heritage may be reusable.

---

## 2026-08-07 - The deck home: INTAKE hero, fixed order, no charts

**Today as the deck home** (grilled, Kevin). INTAKE (kcal + meter) is the hero; fixed order
INTAKE -> SYSTEMS SWEEP -> AGENDA -> ALERTS; silent domains stated as rows (`NOT LOGGED`), never
hidden; zero charts on home (trends live in modules); attention travels by advisory tag, never by
reordering. Rotating hero explicitly declined. Ticket:
`.scratch/cyberdeck-ui/issues/06-today-deck-home.md`.

**SUPERSEDED 2026-08-14:** zero charts on home was reversed by Kevin during quant-viz (commits 087d8f9, f1c396d), before mission-control existed. INTAKE, SLEEP, and LEDGER month-to-date sparklines ship on HOME. Mission-control ticket 11 confirms this as the baseline.

---

## 2026-08-07 - BIO module: sparkline panels, drilldown depth

**Body surface** (grilled, Kevin). Four fixed panels - MASS / INTAKE / SLEEP / TRAINING - each a
hero number + small sparkline, full axis-labeled charts and history lists one tap in. TRAINING
drills twice, ending at per-exercise progression (weight x reps over weeks). Range selectors in
drilldowns only. `UPLINK: SELF-REPORT` stated once in the header. Not-logged days are chart GAPS,
never zeros. Ticket: `.scratch/cyberdeck-ui/issues/07-body-surface.md`.

---

## 2026-08-08 - CRED module: monitor first, plumbing is one row

**Ledger surface** (grilled, Kevin). BIO grammar: BURN / BALANCES / FLOW sparkline panels with
chart drilldowns (per-category bars + burn-down, balance trajectory, monthly trends); ingestion
plumbing (scan/mapping/pending/guesses) collapses to ONE exception-tagged OPS checklist row that
turns red on quarantine; the transaction STREAM stays an inline list with existing
drilldown/recategorize flows re-skinned. §4 rule 7 wording restated for BURN/BALANCES
aggregates. Ticket: `.scratch/cyberdeck-ui/issues/08-ledger-surface.md`.

---

## 2026-08-08 - Cyberdeck decisions COMPLETE; build tickets graduated

Kevin delegated the last three grilling tickets to the recommended defaults ("resolve them all
with ur default recs"). **FLEET** (ticket 09): Fleet+Telemetry merge; UPLINK panel leads always,
staleness worded; driving-mode offer lives on UPLINK + Alfred strip. **Pantry/LOG** (ticket 10):
Pantry inherits panels, skips charts (grocery spend already in CRED FLOW); Agenda becomes a
mission-log timeline; lists stay lists. **Driving mode** (ticket 11): offered on OBD connect
never auto, three giant readouts max, one EXIT key, exits on link drop, no dialogs, no theatre.

**MAP STATE: all 11 decision tickets resolved. Fog graduated into build tickets 12-21**
(theme -> shell + chart kit -> five surface rebuilds + driving mode -> ship pass 21, which is
the destination gate: this map's destination is SHIPPED, not specced). Frontier: ticket 12
(theme). Remaining fog is intra-build detail only (state copy, measured contrast floors).

---

## 2026-08-13 - Gmail scope floor: `gmail.readonly`, and restricted tier was never avoidable

**Wayfinder ticket:** `.scratch/google-account-integration/issues/03-gmail-scope-floor.md`
(research, resolved from an agent report; tags below are the agent's, not orchestrator-verified).

LEGION's Gmail integration is read-only and pull-only, doing two jobs: brief the inbox on request,
and search it on request. The narrowest scope that does both is **`gmail.readonly`**.

- `q` search is **forbidden** under `gmail.metadata` - stated verbatim in the Gmail discovery doc and
  enforced server-side, along with `format=FULL`/`format=RAW`. Search is `q`, so `q` sets the floor.
- **`gmail.metadata` is itself RESTRICTED tier.** Choosing metadata-only buys privacy and **zero**
  tier relief. There is no sensitive-tier read scope for a standalone app - the sensitive Gmail
  scopes are `gmail.addons.*`, live only while an add-on runs inside Gmail's UI.
- Consequence for the app as a whole: **LEGION can never be published without a Google security
  assessment.** That is forced by wanting Gmail at all, not a choice made carelessly. Clone-and-run
  was already dead for anything OAuth-shaped (package + SHA-1 keying).
- Quota is a non-constraint: ~405 units per briefing against 6,000/min/user. Cost is per-method not
  per-format, so metadata and readonly cost identically. Batching does not reduce quota.
- Play Services grants Gmail scopes exactly as it grants `drive.appdata` - no new client type, no
  Web client (that is only for `requestOfflineAccess`, which needs a server LEGION must not have).

**Open, handed to that map's ticket 07 rather than decided here:** Google's User Data Policy Limited
Use section bars transferring restricted data to third parties and **says nothing about LLM
providers** (`inferred`). Under `gmail.readonly`, "no mail bodies reach Gemini" is a rule only LEGION
enforces. `gmail.metadata` would make it technically impossible at the same tier, at the price of
search. That trade is live and must be ruled on deliberately.

**Context:** this map exists at all because the `com.kevin.legion` OAuth client was finally
registered on 2026-08-13, clearing a blocker open since 2026-08-01.

---

## 2026-08-13 - Testing-status OAuth expires the GRANT weekly; Production is the free exit

**Wayfinder ticket:** `.scratch/google-account-integration/issues/01-testing-status-token-lifetime.md`
(research, resolved from an agent report; tags below are the agent's, not orchestrator-verified).

The 7-day expiry on a Testing-status OAuth client is documented at the **grant** layer -
"authorizations by a test user will expire seven days from the time of consent" - not at the token
layer. `DriveAuth` holding no refresh token and asking Play Services for a fresh access token each
time therefore does **not** dodge it. **That last step is `inferred`: Google's docs never mention
Android or Play Services in connection with the rule, in either direction.**

- **Drive sync is already exposed**, today, not just the unbuilt Gmail/Calendar features.
  `drive.appdata` is outside the rule's only exception (name/email/profile).
- **And it would fail SILENTLY.** Expected surface is `authorize` returning `Outcome.NeedsConsent`
  with no exception; `DriveAuth.accessTokenOrNull()` collapses that to `null` and `SyncEngine`
  swallows a null by design. A lapsed grant reads exactly like never having connected - the same
  failure shape `DriveConnectResolver` was written to kill, surviving in the one path that still
  discards the reason.
- **Internal user type is unavailable** - it needs a Cloud Organization and a personal Gmail account
  has none. **Production is the exit, and publishing is NOT verification.** Published-but-unverified
  is a documented supported state: 100-user lifetime cap, a one-off interstitial per account, no
  7-day expiry. Google states an explicit exemption from verification, restricted scopes included,
  for an app whose users are all known personally to the developer.
- **This corrects a claim made the same day**, while charting: that accepting Gmail's restricted
  scope meant the app "can never be published without a security assessment". That conflated
  publishing status with verification. Amended on the map as settled decision 5.
- **Still open** (`needs-a-spike`): whether the console gates Publish behind a verification
  submission once a restricted scope is configured. Probe pressed forward as its own unblocked
  ticket 11 - press Publish while only `drive.appdata` is on the client, so the answer arrives before
  anything commits to Gmail.

---

## 2026-08-13 - Google Calendar goes through CalendarContract, not the REST API

**Wayfinder ticket:** `.scratch/google-account-integration/issues/02-calendar-api-choice.md`
(research, resolved from an agent report; tags are the agent's, not orchestrator-verified).

LEGION reads and writes Google Calendar through **Android's `CalendarContract` provider**, with a
`READ_CALENDAR`/`WRITE_CALENDAR` runtime permission pair and **no Calendar OAuth scope at all**.

- **The folklore that an app can only write local calendars is wrong** (`documented`, from AOSP
  source). `CalendarProvider2.insertInTransactionInner` flags a non-sync-adapter insert `DIRTY` and
  calls `notifyChange(..., syncToNetwork)` with `syncToNetwork` true precisely when the caller is
  **not** a sync adapter. That is the intended upload path and the one the AOSP Calendar app uses.
  The real restriction is on the **`Calendars`** table (an app may write only `NAME`,
  `CALENDAR_DISPLAY_NAME`, `VISIBLE`, `SYNC_EVENTS`) and does not touch `Events` rows.
  `ACCOUNT_TYPE_LOCAL` is an opt-in "do not sync", not a fallback.
- **Never append `CALLER_IS_SYNCADAPTER`** on an app write. Nothing prevents it and it is the one way
  to silently break upload.
- **REST lost on two hard points, not on taste.** `events.watch` push requires an HTTPS webhook with
  a valid certificate; LEGION has no backend and CLAUDE.md §7 forbids one, so REST's two-way story
  degrades to a `syncToken` poll. And every panel render becomes a network round trip, where the
  provider's `Instances` time-range URI is local, offline, free, and already the agenda query shape
  chosen by `notes-lists-calendar` ticket 08.
- Recurrence fidelity is a **tie** - the provider carries `RRULE`/`RDATE`/`EXRULE`/`EXDATE`, real
  exception rows, and `Events.CONTENT_EXCEPTION_URI` for single-occurrence edits.
- **Correction to an assumption made while charting: Calendar scopes are `sensitive`, not
  `restricted`.** REST's marginal OAuth cost was smaller than the map implied. It still loses.
- **One load-bearing inference, deliberately carried into the build** (`needs-a-spike`): Google's
  closed-source sync adapter performing the final upload. A ~20 minute on-device spike settles it.
  It blocks the build, not the decision.

**Note on agent disagreement, kept deliberately.** This agent also argued the 7-day Testing-status
expiry does not reach LEGION because `DriveAuth` stores no refresh token - contradicting the same
day's ticket 01, which found the rule stated at the **grant** layer. It further mis-read the
2026-08-03 device run as a successful Drive connect when `DriveAuth`'s own doc comment records it
FAILING with `DEVELOPER_ERROR`, so its "free 10-day-old data point" does not exist. **Its correction
was discounted, not adopted.** The disagreement is settled by observation, not argument - see the
Publish ticket. Two agents, same day, opposite conclusions from the same public docs: relay the tag,
never the confidence.

---

## 2026-08-13 - Gmail + Calendar decisions COMPLETE; an appointment stops being a reminder

**Map:** `.scratch/google-account-integration/`. Kevin delegated the last six grilling tickets to
the recommended defaults ("resolve them all with your default recs"), the same move he made on the
cyberdeck map. **None of the six was individually put to him; all are reopens.**

**The load-bearing one (ticket 04), and it re-reads a decision made six days earlier.**
`ListItem.startsAt` stops meaning "this is a calendar event" (`notes-lists-calendar` charting
decision 6) and starts meaning "remind me about this task at T". **Google owns appointments,
LEGION owns reminders, and nothing is ever written to both stores.** Consequences:

- **No mirror, no migration, no cached event row, no Room change at all.** There is no sync problem
  because the two stores never hold the same row. Settled decision 2 ("Google owns a timed event")
  bought the removal of a recurrence translation layer; taken one step further it removes the sync
  layer too.
- Recurrence, `ListItemSkip`, `AlarmScheduler`, MISSED, `exact`/`exactDowngraded` and place triggers
  **all survive untouched**, applying to reminders only. Google's `RRULE`/`EXDATE` is read-only to
  LEGION via the `Instances` URI.
- **LEGION will NOT notify for a Google Calendar event.** Google already does, on every device.
  Reading an event onto a surface is not owning its alarm; building a second notification is the
  double-fire the split exists to avoid.
- Named cost: Alfred must pick a store per utterance ("dentist Tuesday at 3" vs "remind me to change
  the oil Tuesday"). Mitigation is the notes domain's existing rule - **he says which he did** - and
  ambiguity defaults to reminder, being local, private and trivially undone.

**The others.** Gmail: two tools, app owns the briefing query (`is:unread in:inbox category:primary
newer_than:2d`, cap 10), model owns the search query passed to `q` unchanged, **guarded by
disclosure - Alfred says the query he ran** - rather than by the app second-guessing Gmail's parser.
**Read-through only**: no Room table for mail, and mail results explicitly excluded from
`EpisodicTurn`/`CompanionMemory`, because since 2026-08-12 sync backs up all 42 tables and the only
defence that survives a later feature is that the content was never stored. No durable memories from
mail. Deck: **no new module, no Gmail surface at all** - voice-only; Google events merge into the
existing agenda. Consent: incremental, one GOOGLE row, three independent states, and `SyncEngine`
starts recording *why* it failed so a revoked grant stops reading as "never connected".

**Proposed CLAUDE.md §7 guardrail, NOT yet applied and not yet put to Kevin:** *third-party content
is read-through only* - anything other people wrote TO Kevin, rather than anything he created or
chose to import, may be read to answer a question and must then be dropped. Never persisted, never
synced, never remembered. **The guarantee is that it was never stored, not that something remembered
to exclude it.** To be applied in the same commit as the build, per MEMORY.md's rule, if he accepts.

**MAP STATE: all eight decision tickets resolved. Fog graduated into build tickets 12-16** (grant
plumbing -> calendar read -> calendar write + Gmail tools -> ship pass 16, the destination gate).
**Two tickets are Kevin's console work and block the build: 11 (press Publish) and 09 (add the Gmail
scope).** Nothing remains in the fog.

---

## 2026-08-13 - Verification gates DISTRIBUTION, not the owner using their own app

**Settled empirically, on the device, not by reading docs.** Two research agents disagreed and the
Google Cloud console appeared to contradict the policy. All of it is resolved by one observation.

**The setup.** `gmail.readonly` is a RESTRICTED scope. Adding it to the (published, unverified)
`com.kevin.legion` client made the console raise *"Verification required. A restricted scope was
added"*, and Data Access then listed it under **"Your restricted scopes - Approval required"** with
a warning triangle and an unfilled verification submission form.

**The observation.** No submission was made. The app requested the scope on-device, **Google
presented a normal consent screen, Kevin tapped Allow, and the grant was issued.**

**The rule this establishes for LEGION.** Google's verification process gates **distributing an app
to strangers**. It does not gate the developer using their own app on their own account. Google's
own docs carry the exemption in writing - no verification *"if you are the only user of your app or
if your app is used by only a few users, all of whom are known personally to you"*, restricted
scopes included. **The console's banner describes what verification would require; it is not a
precondition for consent.** A future session hitting that banner should not treat it as a blocker.

**Related, same day:** the consent screen was moved from `Testing` to `In production`, which removed
the 7-day grant expiry. Publishing status and verification are different things and conflating them
cost an incorrect claim earlier in the session (map settled decision 5, since amended).

**Still unproven, deliberately recorded as such:** that a Gmail API *call* returns data. A granted
scope and a 200 response are different claims, and an unverified app with a restricted scope is
exactly where a call-time 403 could still appear. One voice command settles it.

---

## 2026-08-13 - Gmail works. The 403 was SERVICE_DISABLED, not verification

**Ticket:** `.scratch/google-account-integration/issues/20-gmail-says-granted-but-cannot-read.md`.

Setup said "Gmail: Granted" while the assistant said it could not read mail. Both were true. The
grant was real; **the Gmail API was never enabled in the Cloud project**, so every call returned
403 `SERVICE_DISABLED` / `PERMISSION_DENIED`. Enabling `gmail.googleapis.com` in
`midnight-ai-c7421` fixed it, and a real briefing call now returns 200.

**Three lessons, in order of how much they will cost next time.**

1. **A scope appearing in the OAuth scope picker does NOT mean its API is enabled.** The
   orchestrator saw `gmail.readonly` in the picker, concluded the API was on, and stated that as a
   finding rather than an inference. It was false, and it made the later failure look mysterious.
   The console's own note - "only scopes for enabled APIs are listed below" - is what made the
   inference tempting; it is not reliable.
2. **The predicted failure was the wrong failure.** A call-time 403 had been flagged as the
   outstanding risk of using a restricted scope on an unverified app. A 403 duly arrived, from an
   unrelated cause. Being right that something would break is not the same as knowing why, and the
   plausible explanation would have sent the fix in the wrong direction.
3. **On a handset that filters the app's own logcat, a verbatim on-screen diagnostic is not a
   luxury.** LEGION's four friendly spoken failure messages would have said "Gmail returned an
   error" forever. The permanent TEST panel on the GOOGLE screen printed Google's raw response body
   and the answer was in it. **Friendly messages for the driver, verbatim ones for the developer -
   both, not either.** Pattern to reuse: `GmailTestOutcome`/`GmailTestPanel` in
   `ui/sync/GoogleAccessScreen.kt`.

**Also verified in the same pass:** ticket 17's read/write calendar split works (the Notes stream
went from 10 to 24 items once read-only calendars stopped being filtered out), and a new defect was
found by looking - the `CAL` tag vanishes when an event title wraps to two lines, leaving a Google
event distinguishable only by the absence of a checkbox. Ticket 21.

## 2026-08-13 - Aspect advisors charted: five pull-only coaches, playbooks baked in

**Map:** `.scratch/aspect-advisors/` (11 tickets). Kevin's ask: a recommendation sub-agent per
aspect - BIO coach, LOG planner, FLEET maintenance advisor, CRED financial advisor - called by
the voice orchestrator. Grilled at charting, all Kevin's calls:

1. **Shipped on-device is the destination** (execution in scope, cyberdeck-style override), and
   scope is all four aspect advisors PLUS a cross-aspect HOME advisor.
2. **Pull-only.** Advisor speaks only when asked. On-screen advisor panels and proactive delivery
   (notifications, ProactiveBus) were offered and DECLINED - they are out of scope, not fog.
3. **Baked-in playbooks**, researched at dev time, shipped in the brief. Runtime search grounding
   declined. LLM advises, app computes: advisors get deterministic digests, never do arithmetic.
4. **Goals are new stored data** per aspect, outside the trust tiers like targets (an intention
   is not a claim). Room migration to come.
5. **Propose -> accept -> write.** An advisor's plan lands in the record only on Kevin's spoken
   yes, through the existing tool layer, tagged advisor-proposed.

All four playbook research tickets resolved same day (drafts in
`.scratch/aspect-advisors/research/`, sources in each). Notable: scheduled deloads scored neutral
in a 2024 RCT so BIO prescribes reactive-only; GTD's hard-landscape rule makes "Google owns
appointments" the orthodox split; FLEET's rules were checked against `MaintenanceItem`'s actual
due-axis semantics; CRED bakes hard referral boundaries (tax, investment selection, insurance).
§7 tension to settle deliberately: the no-compulsion line for a coach is its own ticket
(`issues/10-safety-and-labelling.md`), not left to prompt vibes. Frontier next: the advisor
contract, then the goal store.

## 2026-08-13 - The advisor contract: one harness, one tool, one POST, and a persisted advice log

**Ticket:** `.scratch/aspect-advisors/issues/01-advisor-contract.md`. Grilled with Kevin.

1. **One `AdvisorAgent` harness, five briefs** (playbook + digest builder + writable-proposal
   schema per aspect). Shared rules - estimate wording, no-compulsion, crisis stop - live once in
   the harness prompt, the tier-tagging-at-the-tool-layer trick applied to safety copy.
2. **One `ask_advisor(aspect, question)` tool** on the live session; accepts reuse existing write
   tools.
3. **One-shot `askTyped`**: precomputed digest, one POST returns prose + structured proposal.
   `investigate` ruled out - Flash cannot combine structured output with tool declarations
   (SubAgent's own docs, traced).
4. **Advice log kept** (Kevin, against the record-only recommendation): every advisor exchange
   persists (question, gist, proposal, accepted/rejected); last ~3 per aspect ride the digest;
   schema folded into the goal-store ticket; the "I've told you three times" compulsion edge is
   assigned to the safety ticket.

**Segue with legs:** Kevin flagged live-session context bloat - ~69 tool declarations ride every
Gemini Live session - and asked for tool DISCOVERY instead. Charted as research ticket
`issues/12-lean-toolbox.md`: can Live-API tool declarations change mid-session, does a
discover+dispatch pattern work, what do 69 declarations actually cost. Reaches well beyond the
advisors effort if it pans out.

## 2026-08-13 - Lean toolbox researched: Gemini Live cannot swap tools mid-session; dispatch can

**Ticket:** `.scratch/aspect-advisors/issues/12-lean-toolbox.md`; findings in
`.scratch/aspect-advisors/research/lean-toolbox.md`. Research only - the adoption decision is
folded into the token-budget ticket (11).

Facts: the v1beta BidiGenerateContent WebSocket LEGION uses fixes tool declarations at session
setup ("You cannot update the configuration while the connection is open" - researched); OpenAI
Realtime allows it, Gemini does not. Session resumption as a reconfigure path is UNVERIFIED
(reasoned, needs a spike). Measured: `LiveToolbox.declarations()` holds **71 tools** (MEMORY.md's
69 is stale), ~43.8k non-comment source chars, estimated **~10-11k prompt tokens riding every
socket including prewarms**. A 12-15-tool core plus `discover_tools(domain)` +
`call_tool(name, args_json)` estimates ~2.2-2.7k tokens, **~75-80% saved**, and needs no API
support - schemas ride back in a function response (MCP's tools/list -> tools/call contract).

Risks named: one extra model turn of voice latency on first domain use; undeclared-tool
hallucination; lost server-side arg validation (call_tool must validate against the real registry
and return corrective errors); no published reliability data for this pattern on flash-live audio
models; and the mail episodic-exclusion check must key on the INNER tool name, not `call_tool`.
Recommendation: 6-8 static aspect buckets, no RAG retriever, spike one domain before migrating.

## 2026-08-13 - The goal store: one table, prose-first, revision trail, metric-keyed progress

**Ticket:** `.scratch/aspect-advisors/issues/02-goal-store.md`. Grilled with Kevin.

1. **ONE `goals` table with an aspect column**, not per-domain tables. Targets are separate
   because their shapes genuinely differ (cents+category vs calories+macros vs miles+date); a
   goal is uniformly statement + aspect + optional number, so fragmenting it buys nothing and
   costs the HOME advisor a fan-out read. Legion-shape's "separate storage per domain" governed
   plan-versus-actual mechanics, not every new concept.
2. **Prose required, numbers optional.** Forcing a number manufactures fake metrics for real
   goals ("ship the deck"). Measurable goals get gap math; the rest are coached qualitatively.
3. **Optional `metricKey`** (TEXT) names a metric the app already tracks; when set, deterministic
   code supplies current value, trend and projection and the LLM only interprets. **Widening the
   key list is not a migration** (§5's IngestMethod precedent) - and the ticket says to CONFIRM
   that by reading `createSql` and diffing the schema JSON, not assume it.
4. **Revision trail, house pattern.** `lineageId` + supersede + status, nothing deleted, matching
   `BudgetTarget`/`MealTarget` copy-forward (traced). Coaching payoff: a goal that quietly got
   easier is visible, and it is §7-safe because it is a fact about the record.
5. **Voice tools + a GOALS panel per aspect screen** (targets already work this way).
6. **Goal-to-target links inferred, never stored** - a stored link is hand-maintained bookkeeping
   that goes stale on every target edit.
7. **Advice log** stores gist + full advice text + proposal JSON + outcome; only gist and
   proposal ride the digest.

**Schema fact corrected:** the database is at **Room v15** (traced, `CarDatabase.kt` line 109).
MEMORY.md said v11 and the ticket had guessed v12+; both stale, and a session that trusted either
would have written the wrong migration. MEMORY.md corrected in the same commit. The goal store is
**v15 -> v16**, two additive tables.

## 2026-08-13 - Propose-accept-write: the mechanism is the consent, and advisors never write actuals

**Ticket:** `.scratch/aspect-advisors/issues/03-propose-accept-write.md`. Grilled with Kevin.

1. **Stored proposal + `accept_proposal(id)`.** The advisor's structured proposal persists as
   `pending`; the accept tool executes the STORED json. The live model names a proposal, it never
   supplies the values, so nothing drifts between what was read aloud and what lands.
2. **A modification re-asks the advisor** for a fresh proposal. Accept-time overrides were
   rejected: they put the model back in the business of supplying numbers.
3. **Intentions only, per-aspect allowlist.** Goals, targets, plans, maintenance items,
   reminders. **Never an actual, never a delete, never a recategorise.** An actual is a claim
   about what happened and only Kevin can make it - an advisor logging one manufactures
   `reported`-tier data from an inference, exactly what the two-trust-tiers decision forbids.
4. **Provenance in the advice log only.** No `source` column on the five target tables - five
   migrations for a field nothing reads on the hot path, plus five write paths to keep correct.
5. **Proposals expire** (conversation + ~24h, reasoned starting number). `accept_proposal`
   refuses past that **in words**, and the assistant re-checks. Re-verifying by rebuilding the
   digest at accept time was rejected as a second Gemini call to save a cheap re-ask.

**Scope note that matters for anyone reading LiveToolbox:** this protocol is the EXCEPTION.
The ~15 existing `log_*`/`set_*` tools keep their deliberate no-confirm behaviour (voice-logging:
"no confirm step; the assistant states what it wrote"; `log_workout_set` says so in its own
description, traced). Direct dictation is unchanged; only advisor-authored writes are gated.

## 2026-08-13 - Aspect digests: text not JSON, four periods, aggregates plus exemplars

**Ticket:** `.scratch/aspect-advisors/issues/08-aspect-digests.md`. Grilled with Kevin (batched).

1. **Five `DigestBuilder`s in `advisor/`**, read-only over existing controllers and DAOs -
   advisor concerns stay out of the domain controllers, and one place audits per-question cost.
2. **Compact labelled text, not JSON** (`BUDGET groceries target 400.00 actual 312.45 remaining
   87.55 [proven]`). JSON spends a real share of every digest on punctuation and repeated keys.
   The ~30-40% saving is ESTIMATED, not measured - the token-budget ticket must confirm it.
3. **Window: current period + 3 prior, then trends.** Older history arrives as one precomputed
   trend figure, never rows, so cost does not grow with Kevin's history.
4. **Aggregates plus named exemplars** (biggest merchants, the stalled lift, the overdue item) -
   specific without shipping the ledger into every prompt.

**Stated from existing law rather than asked:** every figure carries its `TrustTier` (reusing
`plan/Plan.kt`'s `combinedTier()`, traced), `UNRECONCILED`-touching figures are marked unverified
IN WORDS (§4 rule 7) and macro figures marked estimate (§4 rule 5); an empty domain reads
"not logged", never zero - a digest reporting 0 kcal for an unlogged day would have the coach
scolding Kevin for a day he simply did not record. Per-aspect contents are specced on the ticket.

## 2026-08-13 - Advisor safety: persona owns tone, harness owns rules; no code floors

**Ticket:** `.scratch/aspect-advisors/issues/10-safety-and-labelling.md`. Grilled with Kevin.

1. **Candid about facts, neutral about Kevin.** "You planned four, you logged one" is always
   allowed. Banned: disappointment, guilt, streaks, "don't give up on me" - any framing where the
   app's feelings are the reason to comply. §7's serve-the-user-or-the-retention test, made
   applicable.
2. **Data NEVER triggers the crisis path.** `CrisisDetector` stays speech-only (traced: it reads
   driver speech, is precision-tuned, and its own doc calls it the second line behind the prompt).
   Inferring distress from a weight trend is the unfalsifiable inference §7 forbids. Speech-borne
   distress mid-advisor-conversation still fires the existing path and the coach stops performing.
   The advisor may **decline to help in words** instead - a refusal, not an escalation.
3. **The advisor speaks in whatever persona is ACTIVE** (Kevin's call, against "always Alfred").
   `Personas.kt` bundles alfred + dorothy plus custom (traced). **Load-bearing consequence:
   persona owns TONE, the harness owns the RULES** - every safety rule lives in the `AdvisorAgent`
   harness prompt, never a persona fragment, so switching persona changes how advice sounds and
   never what it may say. Any build that puts safety copy in a persona has broken this.
4. **NO hard numeric floors** (Kevin's call, against enforcing playbook safe ranges at accept
   time). Cost recorded honestly: a hallucinated number CAN reach a written target and no code
   stops it. Standing in its place: propose-accept-write reads every number to Kevin and writes
   only on his yes; playbooks still carry safe ranges as advice; the advisor can decline. Personal
   app, one adult, §7's framing. Revisit if a bad number ever lands.

**Estimate labels ride a structural `basis` field** (`record`/`estimate`/`playbook`) that the
harness renders from, not prose the model might omit - the enforce-at-the-tool-layer pattern
again. Per-playbook professional-referral boundaries stay binding.

## 2026-08-13 - The HOME advisor: synthesis only, read-only, one digest not four

**Ticket:** `.scratch/aspect-advisors/issues/09-home-advisor.md`. Grilled with Kevin.

1. **Its own condensed cross-aspect digest** - one headline line per aspect (the gap that
   matters, trend direction, goals off track) plus goals and exceptions, ~1x an aspect digest.
   Four raw digests (~4x prompt) and running the four advisors first (5 calls, 5x latency) were
   both rejected on cost.
2. **Synthesis brief, no fifth playbook.** It connects - eating out hitting macros AND budget, a
   repair hitting the emergency fund, an overloaded week explaining missed sessions - and
   **defers domain depth to the aspect advisor** instead of improvising.
3. **Routed as `ask_advisor(aspect = "home")`** - just another aspect value, no new mechanism.
4. **Read-only.** It hands proposals off to the advisor that owns the aspect and its allowlist,
   so the author of a proposal is always the one holding the relevant playbook.

**Harness consequence worth carrying into the build:** the one-harness-five-briefs contract holds,
but HOME's brief has NO playbook and NO writable operations. The harness must treat both as
OPTIONAL parts of a brief; one that assumes every brief has both would need reworking to admit it.

## 2026-08-13 - Advisor cost ceiling MEASURED; three inherited estimates confirmed

**Ticket:** `.scratch/aspect-advisors/issues/11-token-latency-budget.md`. Analyst pass, real
tokenizer (`countTokens`, free tier only - no billed generateContent calls made).

**Three estimates carried from earlier sessions, all confirmed rather than inherited:**
1. Compact text vs JSON digests: **38.5% mean saving** (33.7-44.6% across five aspects).
   Ticket 08's "30-40%" holds, and slightly undersold BIO and FLEET.
2. chars/4 as a token heuristic: measured **4.15 chars/token** on the real LiveToolbox slice, so
   ticket 12's ~10-11k figure was accurate to ~4%, NOT the 20-30% hot it flagged as a risk.
3. Advice-log window of 3 exchanges: **194 tokens**, the cheapest line in the budget. The
   PLAYBOOK, not the log, is what the budget must watch.

**Per-question totals:** BIO 3,233 / CRED 3,183 / FLEET 3,806 / LOG 2,821 / HOME 1,038.

**CEILING (binding on build tickets): 2,500 tokens per playbook, 4,000 per aspect question,
1,500 for HOME.** FLEET's playbook is 2,909 and **must be trimmed by ~409 tokens** (the 17-row
interval table and the seasonal/DIY prose are the candidates). BIO/CRED/LOG already fit.

**Do not ship a playbook's `## Sources` section to the model** - dev-facing licensing docs,
500-700 tokens per aspect, zero coaching value.

**Standing socket cost:** `ask_advisor` alone +239 tokens (+2%, ship it); all five advisor/goal
tools +872 (+7-8%). The number that should drive the lean-toolbox decision: **the declared
toolbox is already ~a third of a 32k Live context window before a word of conversation**, and
this effort is the first concrete proposal to grow it - the segue that opened ticket 12 was
worrying about exactly this addition.

**Latency is UNMEASURED and stays that way here** (would need billed calls). It is a ship-pass
verification step. Note: `ask_advisor` is a sub-agent hand-off like the existing `diagnose_codes`,
which already tells the model to say "digging into it" before the wait - reuse that pattern.

## 2026-08-13 - Aspect advisors BUILT: six tickets, 905 tests, two majors caught by review

All six code build tickets of `.scratch/aspect-advisors/` are landed on `feat/cyberdeck`
(`e0fc5e6` goal store, `bc27ef8` harness + playbooks, `76ea3a4` digests + goal tools,
`7602003` ask_advisor + accept, `c8c5fff` review fixes). **905 unit tests, 0 failures**, re-run
by the orchestrator on every wave rather than relayed. Only the ship pass (ticket 20) remains.

**Built in three dependency waves, deliberately not all at once**: tickets 18 and 19 both edit
`LiveToolbox.kt`, and two agents writing one 3,900-line file concurrently is a silent bad merge.
Wave 1 harness + playbooks, wave 2 five digests + goal tools, wave 3 the wiring.

**Two MAJOR defects found by `bug-hunter` reading call chains end to end, both fixed:**
1. **A failed write was recorded `accepted` and reported success.** `WorkoutController.generatePlan`,
   `SleepController.setTarget` and `ReminderController.add` all signal failure by **returning a
   spoken failure sentence as an ordinary String** rather than throwing. The executor wrapped
   those as success, so the row was marked `accepted` permanently - unretryable, and shown as
   accepted forever in the advice log. **The DB row itself became the false positive.** Fixed by
   DAO read-back verification, never string-matching (a matched sentence rots on first rewording);
   `WriteFailed` leaves the row `pending` so Kevin can say yes again.
2. **Check-then-act race in `accept_proposal`** - a double tap, or a model retry racing the
   original past `TOOL_TIMEOUT_MS` whose orphaned coroutine still completes, could write twice.
   Fixed with `claimIfPending` (`UPDATE ... WHERE outcome = 'pending'`); **rows-affected is the
   mutual-exclusion point, the plain read never was.**

**Lesson worth graduating: a controller that returns a failure SENTENCE instead of throwing turns
every caller into a silent-failure site.** Three separate controllers in this codebase do it. Any
new caller of `generatePlan`/`setTarget`/`ReminderController.add` must verify by read-back.

`senior-dev` found no hole in the four safety-critical properties (intentions-only, consent
enforcement, said-in-words, persona-owns-tone) and confirmed the v16 migration SQL byte-verbatim.
Its one finding (HOME hardcoding its trust tier) is fixed for BIO/CRED; FLEET stays a documented
hardcode because `MaintenanceItem` carries no per-row tier to combine.

**A correction to the contract, found in build:** `SubAgent.askTyped` enforces NO output schema -
no `generationConfig`/`responseSchema` in the request body. Structured output is prompt-plus-parser,
best-effort. `ParseFailed` now carries the raw text and the tool RELAYS the prose, because the
coaching is usually fine and only the JSON envelope failed. Hardening filed as ticket 21.

---

## 2026-08-13 - Android Auto: charted, and the map's own premise falsified within hours

Effort `.scratch/android-auto/`. Kevin asked to use LEGION in the car "as a widget". **Android Auto
has no third-party widget surface**; a projected phone app has exactly two doors, the Car App Library
templates and `MediaBrowserService`. Grilling turned the ask into a shape: **a media app whose play
button places a self-managed telephony call**, media being the door and the call the room, because a
calling app has no entry point in Android Auto's app grid. Same brain, all 69 tools, one car-aware
prompt variant. Destination is DECISIONS; fifteen tickets.

All five research tickets were fired at charting and all five resolved the same day. They rearranged
the map rather than confirming it.

1. **Settled decision 3 falsified.** The call was chosen because it was believed to be the only route
   to the car's echo-cancelled HFP microphone. It is not. Android keys "a call is active" off
   `AudioManager.getMode()`, so `MODE_IN_COMMUNICATION` + `setCommunicationDevice(TYPE_BLUETOOTH_SCO)`
   gets a plain foreground service the same mic; Telecom is a managed wrapper, **not a privilege
   gate**. Separately, a self-managed call **is** surfaced to connected Bluetooth devices, so it
   reaches the car mic over SCO whether or not Android Auto draws anything. **Two routes, neither
   needing projection to render.** Settled decision 1 ("two surfaces, deliberately") was taken on the
   dead premise and is re-opened for Kevin.
2. **A charting claim corrected.** `onPlayFromSearch` does **not** deliver the raw spoken sentence.
   Google documents `query` as music entities re-joined; "Play Live from Moderat on SoundCloud"
   arrives as `"live moderat"`. The full sentence sits at the **undocumented**
   `android.intent.extra.user_query`. Worse, routing "on LEGION" to a sideloaded, never-Play-indexed
   name is **unsettled**, and Google's only primary statement on name invocation is Play-Console-keyed.
3. **The risk moved from telephony to distribution.** Android Auto 17.2's own
   `CarProjectionInCallServiceImpl` declares both flags AOSP needs to hand it a self-managed call
   (verified on Kevin's phone by pulling gearhead's APK). But the in-call view Google documents is
   attached to `androidx.car.app.category.CALLING`, an Internal/Closed-Play-track-only programme, and
   Android Auto's unknown-sources developer option verbatim "doesn't apply to apps built using the
   Android for Cars App Library". **Sideloading may structurally exclude LEGION from that surface.**
   Developer mode does explicitly cover **media** apps, which independently vindicates the disguise
   choice for a reason nobody had while charting.

**Two defects in shipped code fell out of research, neither of them Android Auto work:**

- **OBD reports the car as fine when the Bluetooth link goes quiet.** `Elm327Io.readUntilPrompt`
  polls `available()` and never blocks on `read()` - and AOSP raises the disconnect from `read()`. So
  a quiet link returns `""`, `isFailureResponse` reads that as a car fault, LEGION runs its ISO
  9141-2 K-line recovery ritual against a **Bluetooth** problem and leaves `_connectionState` at
  `CONNECTED`. **`ACTION_ACL_DISCONNECTED` has zero matches across `app/src/main`.** BLE is worse:
  `GattInputStream.closed` is written and never read. Fix is local to `sendCommand`: `""` means
  nothing arrived on the socket, `"NO DATA"` means the adapter answered and the car did not.
  **`traced`, not `tested`** - ticket 13 proves it before anything is built on it.
- **The live session can be silenced with no error.** `GeminiLiveSession.kt:888` captures with
  `VOICE_RECOGNITION`, which is not privacy-sensitive, so a privacy-sensitive capture elsewhere (the
  Android Auto Assistant) hands LEGION **zeroes - no exception, no callback**. Fix is
  `VOICE_COMMUNICATION` / `setPrivacySensitive(true)` plus
  `AudioRecordingConfiguration.isClientSilenced()` so it can say it is being silenced.
  `MODIFY_AUDIO_SETTINGS` is also missing from the manifest and both routing paths need it. Ticket 15.

**Standing worth carrying:** all five findings came from research agents, are tagged
`documented`/`inferred`/`field-report`/`traced` in the resolutions, and **none was verified on
device** except the gearhead manifest dump. Ticket 14 is one 30-minute head-unit session that settles
rendering, audio route and Assistant preemption together; `dumpsys telecom`'s bound-services list
separates "gearhead never got it" from "gearhead got it and declined to draw", which are **different
rulings**.

---

## 2026-08-13 - Only OWN-ACCOUNT movements leave spend, and every figure says so

**Reverses, and then narrows, Kevin's 2026-08-07 ruling** documented in `LedgerTransfers.kt`: that a
`SUSPECTED_TRANSFER` is flagged but stays in `operating` spend, because only a matched pair is safe
to pull out and an unimported second statement is routine. That reasoning was sound and the new rule
keeps its spirit by replacing the weak test rather than lowering the bar.

**The problem, measured on Kevin's real data (497 rows), not argued:** 75 `PAYMENT TO CRD` rows on
checking total a five-figure sum of his own card payments (in cents), and only 10 `PAYMENT FROM CHK` legs on the card were ever imported.
So tens of thousands of dollars of his own money moving between his own accounts was counted as spending, on top
of the purchases it was paying for.

**Why the obvious fix was wrong, and how it was caught.** Excluding everything
`TRANSFER_KEYWORDS` matches was Kevin's first answer. Measuring it first showed the keyword list
catches three different things: card payments (75 rows, a five-figure sum in cents), own SAV/CHK transfers (19 rows,
several thousand dollars in cents), and **40 `Zelle payment to <person>` rows worth several thousand dollars across 40 person-to-person rows, which are real money leaving,
not transfers at all**. The blunt rule would have hidden thousands of dollars of genuine payments - the
opposite of the bug being fixed, and precisely what the 2026-08-07 note anticipated. **Kevin was
shown the breakdown and changed his answer.** A decision put to him twice, with numbers in between,
is worth more than the first answer executed faithfully.

**The rule now:**
1. A row leaves `operating` only when its description resolves to **an account Kevin holds** (last
   four of a known `accountId`, via the existing `LedgerAccountIdentity`/`LedgerAccountMapping`
   notion). **A keyword is not the test; an owned account is.** Ambiguous means NOT excluded -
   wrongly excluding real spend is the failure that matters, wrongly including a transfer is only
   the status quo.
2. **Every figure that excluded something says so in words**, with count and amount, on the ledger
   surfaces AND in the voice path. This is §4 rule 7's "said in words on every surface" applied to
   an aggregate rather than a row. A spoken total that omits what the screen discloses is the same
   bug in a different surface - see `memory/MEMORY.md`'s L15, where three bugs were found by looking
   at the phone and none by the suite, all of them one figure computed in more than one place.
3. **The excluded rows are inspectable**, or the disclosure is a claim rather than a fact.
4. **The categorisation gate deliberately stays broader than the spend gate.** Transfers AND Zelle
   rows both stay out of merchant guessing, because a person is not a merchant either; only the
   SPEND rule narrows to own-account. The two rules now differ on purpose and the code says so, so a
   future reader does not "fix" the divergence.

**Related, same day, same session:** the seeding hole that put all 497 rows in `Pets`
(`MIGRATION_16_17`), and the `CHECKCARD` merchant-key bug that let one rule confirm 48 unrelated rows
into Subscriptions (`MIGRATION_17_18`). All three were found by looking at real data pulled off the
device, none by the test suite.

---

## 2026-08-13/14 - Quantitative visualization, then GLANCEABLE (Kevin)

**Delegated taste.** Kevin: "lets take a moment to review the app... personally i feel the data can
be presented in a better way. think visualization of quantitative data (book). the UI can be
improved" then "leave me out of it. plan the ui improvement and data visualization. hand it off to
the team to build after. i trust ur taste." Map `.scratch/quant-viz/`, branch `feat/quant-viz`.

**The finding.** The Deck chart kit (`ui/common/DeckCharts.kt` + `DeckChartData.kt`) was complete,
tested and Tufte-native (sparklines, gap-never-zero, exact Long-cents labels) but wired into ONE
module. Money - the most chart-ready data in the app, dated, signed, categorised, with explicit
targets - had zero visualization. `MonthlyRecap`, `YearlyWrapped` and `OilAnalysis` were write-only.
`DeckMeter` was used once against four target-vs-actual pairs printed as sentences.

**Taste call 1, and Kevin's reversal of it.** The map originally locked "pane -> drilldown: inline
surfaces get at most a sparkline; Today stays chart-free (cyberdeck ticket 06 answer #4)." After the
first pass Kevin said: **"inline viz across all tabs. im not gonna read numbers. it has to be
glancable."** That reverses my restrained-inline call AND cyberdeck-ui ticket 06's chart-free Today,
both on his own authority. Tickets 10-13 followed. **Standing rule: every tab face carries inline
visualization; numbers support the graphic, not the reverse.**

**Other locked calls that survived:** no new chart types (no pies/donuts); charts are ADDED next to
disclosure words, never replacing them; one definition of spend (the monthly trend calls the same
`budgetVsActual` per month, never a parallel SQL aggregate); ledger coverage rule (an empty day
INSIDE a covered statement window is a real 0 bar, a day outside every window is a gap slot).

**A gap of my own making, closed:** `BudgetTarget`/`set_budget` existed only by voice, so no meter
could ever fill from the screen. Ticket 09 added the SET TARGET affordance to the category
drilldown (Double-free dollars-to-cents parser). Verified by writing Groceries = USD 300 through it
on the real phone: meter filled 69% with the pace tick at day 14 of 31, both hand-checked.

**LOG tab shape (tickets 14-16).** The WEEK AHEAD density strip is retired in favour of a month
calendar: dots for density (1-2 events = 1 dot, 3-4 = 2, 5+ = 3), today's cell filled, a selected
cell outlined, HIDE collapses the grid. **Tapping a day opens an `AlertDialog` listing that day's
entries** (Kevin: "tapping the date with event on it should pop up a UI showing things due on that
date") - `SHOW IN LIST` is now the only route to the list filter, since a tap that silently filtered
a list below the fold read as doing nothing. The popup renders from the same month list that draws
the dots, on purpose - see L19. `AlertDialog` was chosen over a bottom sheet because every existing
modal in the app is one.

**Deferred, deliberately:** month-label formatting is duplicated between `SpendTrendDrilldown` and
`PantryRows`; `dueFraction` approximates a month as 30 days; the dialog's internal scroll is
untested because no day in Kevin's real data is busy enough to overflow it; MISSED's 4-row cap is
untested for the same reason.

---

## 2026-08-14 - Mission Control UI: full visual re-do, new wayfinder effort

**Chart.** New map `.scratch/mission-control/map.md` (wayfinder:map), eleven tickets. **Destination is
SHIPPED on-device, not a spec.** Rebuilds nine data surfaces, shell, nav, boot chrome, driving mode,
and utility screens on a mission-control aesthetic: red-orange chrome (bezel, pill outlines, alarms),
mint-green data readouts, amber highlights and markers, CRT bezel with flat content, tiled console
modules with roomy drilldowns, bundled monospace face. Four reference photos in `.scratch/mission-control/research/refs/` are the brief.

**Supersedes parts of `.scratch/cyberdeck-ui/`.** That map stays closed as history. Tickets from
cyberdeck-ui that are reopened by this one: ticket 01 (MILSPEC palette), 03 (semantic colour), 04
(motion escalation), build tickets 12-20, utility-screens scope. **The reversal that matters most:**
cyberdeck-ui ticket 03 locked "amber = data, green = good, red = needs-you EXCLUSIVELY". Mission-control
reverses it. Red-orange is now ordinary chrome (pill outlines, bezel, frames); mint carries data; amber
is highlights and markers. **Consequence:** alarm can no longer announce itself by hue and must escalate
by solid fill + motion + the word. Ticket 04 of the new map owns that escalation and it is unresolved.

**Still binding, not reopened:** dark-only, no new data collection, CLAUDE.md §4's worded
provenance/quarantine/estimate/`UNRECONCILED`, money stays `Long` cents, Alfred's register locked.

**Charting decisions (all grilled 2026-08-14, Kevin).**
1. Destination is a full visual re-do including screen layout, not a repaint.
2. Refs-faithful colour: red is chrome.
3. Global bezel, flat content.
4. Hybrid density: module roots tiled, drilldowns roomy, tappable keeps 48dp target.
5. Ambient motion raised and budgeted: at most one continuously-animating element per visible surface,
   low frequency; three theatre moments survive (boot, ingest commit, quarantine).
6. One open-licensed monospace bundled in `res/font`, app-wide.
7. Everything relayable, incl. utility screens and driving mode.

## 2026-08-14 - Palette, ground, and the two-hue token table (mission-control ticket 01, RESOLVED)

**Question:** What are the actual colour values, and what does each one mean? Three mocked takes of
REAL LEGION screens (HOME, BIO, LOG) with real logged data, varied ground and hue pairs. Kevin reacted,
one won.

**Answer: VACUUM with a tinge of SENTRY** (Kevin, 2026-08-14). Artifact preserves the three takes and
full reasoning at `https://claude.ai/code/artifact/23c1949c-69d1-46b1-af82-58611b7255cd`. HANGAR
(warm brown-black from ref-a) declined outright: warmth costs contrast, daylight readability is the
hard rule in exchange for dark-only.

Ground settled to pure black `#000000` (unlit OLED pixels); SENTRY's navy-black `#05070C` demoted to
panel tier. Shipping table:

| Token | Value | Role |
|---|---|---|
| `ground` | `#000000` | Screen ground |
| `panel` | `#05070C` | Pane fill |
| `panelAlarm` | `#170604` | Sunken alarm (M3 `errorContainer`) |
| `ink` | `#E4E9EF` | Reading text, descriptions |
| `faint` | `#8E97A3` | Labels, units, provenance. Ticket 10 checks this FIRST |
| `ghost` | `#58606C` | Timestamps, gaps, disabled |
| `chrome` | `#FF5330` | Pill outline, bezel, alarm border/fill |
| `chromeText` | `#FF8A6B` | Pill label, section rule |
| `chromeDim` | `#5A2317` | Bezel line, pane outline. Structural tier |
| `rule` | `#1E2530` | Section boundary |
| `ruleFaint` | `#141A22` | Row separator, meter track, gridline |
| `data` | `#57EFC6` | Every value |
| `amber` | `#FFBA1F` | Highlights, active key, estimate tag |
| `marker` | `#FFD84A` | Chart endpoint, typed markers |
| `good` | `#7BE86A` | Money in, system ok. Revised away from both takes' greens |

`LegionSemantics` mapping: `credit` = `good`, `debit` = `data` (was `ink`, real change), `estimated`
= `amber`, `quarantined` = `chrome` (provisional until ticket 04), `rule`/`ruleFaint`/`faint`/`ghost`
as mapped. New fields `chrome`, `chromeText`, `chromeDim`, `marker`, `data` are build-ticket concern.

**Sub-questions answered:**

1. **Chrome tiers: three, two load-bearing.** `chromeDim` does structural work (bezel line, pane
   outline); `chrome` reserved for pills, ticks, alarm; `chromeText` for labels. **Finding: full-strength
   chrome on every pane edge turns the screen into a grid of alarms.** This was the clearest finding.
2. **Dim-mint token?** No. Ticks, axes, units read as `faint` or `ghost`. Diluting mint dilutes the
   claim that mint means "this is a value".
3. **Marker yellow separate from amber?** Yes, `#FFD84A` vs `#FFBA1F` (barely), but **typed markers
   should differ by SHAPE not hue.** The yellow is a nudge.
4. **Green survives?** Yes, rare hue, only money-in and system-ok. Under a mint-dominant palette, green
   is now a rare accent.
5. **Debits.** Not red, not dimmed. Ordinary values in mint with a minus sign. Preserves shipped
   `debit` posture; hue changes from `ink` to `data`.

**Deliberately left open:**

- **Quarantine/`UNRECONCILED`/`OVERDUE` placeholder.** Set in chrome red in the mocks; visibly fight
  the red chrome around them. Ticket 04's problem.
- **M3 `contentColorFor` collision audit.** The hard invariant from `Theme.kt` (no two of twelve early
  roles may share a raw value) is NOT satisfied by this palette as written, because it is a palette,
  not a scheme. Build-ticket verification step with its own history (CLAUDE.md §8 L11).
- **Contrast ratios unmeasured.** Nothing was computed or read on a device. Ticket 10 owns it.

**Unverified and flagged:**

- No contrast ratio was computed, nothing was read on a device, assumptions about daylight readability
  are reasoned not measured.
- "Pure black is unlit pixels" assumes the Oppo A17K has an OLED panel, which was NOT checked. Ticket 10.

**Findings worth preserving in the library (generalizable past this palette):**

1. Full-strength chrome on every pane edge turns the screen into a grid of alarms, so structural tier
   and label/alarm tier must be different values.
2. Dim-mint is not a token—diluting the data hue dilutes the claim that mint means "this is a value".
3. Marker yellow barely separates from amber; typed markers should differ by SHAPE not hue.
4. Green had to be revised away from both takes' values because a credit did not separate from
   surrounding mint debits (common problem under mint-dominant palettes).

## 2026-08-14 - Bundled monospace: Martian Mono Condensed (mission-control ticket 02, RESOLVED)

**Bundles Martian Mono Condensed** (OFL 1.1, Evil Martians) in `res/font`, replacing `FontFamily.Monospace`. Runner-ups: JetBrains Mono, IBM Plex Mono. Full measurements and source analysis: `.scratch/mission-control/research/bundled-mono.md`.

Five constraints worth preserving:

1. **Variable fonts unusable at `minSdk = 24`.** Compose gates `setFontVariationSettings` behind `SDK_INT >= 26`, silently returns unmodified typeface below. One variable file = default instance at every weight with faked bold. Ship statics.
2. **All credible open-mono faces are OFL 1.1; none Apache 2.0.** Roboto Mono relicensed. OFL has no NOTICE mechanism; `OFL.txt` to `third_party/`.
3. **IBM Plex reserves its name.** Subsetting makes a Modified Version requiring rename. Why Plex placed third.
4. **`isFixedPitch` flag is unreliable.** Measure `hmtx` instead. Tabular alignment is the mechanism LEGION's money columns depend on—Compose has no `font-variant-numeric: tabular-nums`.
5. **Type-scale swap IS a type-scale change.** Martian cap height 0.800em; existing `Type.kt` sizes need ~10% pass down. Not optional.

Closes map fog: Martian's four in-family widths mean a condensed cut costs a weight file, not a second face.

**Not verified:** deflated sizes via `gzip -9` proxy only, no APK built; small-size caps judgements via FreeType, not on-device text stack (Oppo A17K).

## 2026-08-14 - Bezel, label pills, and panel chrome (mission-control ticket 03, RESOLVED)

**Dimensioned spec** at `https://claude.ai/code/artifact/ff4efeb1-20b4-4c1d-9154-da2be719254f`, all values in dp at ticket 01's palette. Cite the spec for dimension tables; this entry preserves findings that do not live in the numbers.

**Load-bearing findings:**

1. **A 22dp feed row cannot be tappable.** Dense-feed row and tappable row are **different components, not one with a flag.** 48dp is the floor for anything that navigates. Any alarm needing a tag inside a dense feed must promote that row to 48dp.
2. **The global bezel costs 32dp of width, 8.9% of a 360dp phone.** Not tunable away—most of it is the 9dp content padding, which cannot drop much below 8dp without the pill colliding with the frame. Downstream layout work gets 328dp, not 360.
3. **Twenty rows is the phone's ceiling at 22dp.** The reference photos' wall of forty rows is unreachable and should stop being the target.
4. **The label pill paints the PARENT's ground, not the pane fill.** That is what makes the pane's top rule appear to break around it, and it is an implementation constraint: the pill cannot be a child of the pane's clipped content.
5. **Long pill labels truncate; the copy gets shortened instead of the type.** A pill at 8sp on one pane and 9sp on the next destroys the grid rhythm. Build tickets shorten copy; they do not add a second pill size.
6. **No zebra striping.** Every fill on this ground already does semantic work; grouping is the section rule's job.
7. **`DeckTag`/`QuarantineTag`'s API shape survives untouched, and the reason generalizes.** Keeping red out of the `DeckTagStyle` enum so the only path to a red tag is `QuarantineTag`, auditable in one grep, is **more** load-bearing now that red is ordinary chrome than it was when red was rare. Reinforces earlier ee201c3 finding: a comment-only guard on an enum value is not enough for a rule CLAUDE.md treats as load-bearing.
8. `StatusLine` survives; the deferred-read cursor stays the one ambient animation. `DeckPane`, `DeckRow`, `DeckMeter` change but keep signatures. `DeckBezel` and `DeckSectionRule` are new.

**Not verified:** nothing was rendered in Compose or on the device; every value is a CSS approximation of a dp. The notch and gesture-bar cases are untested. Corner arc rendering, dashed-hairline phase, and the pill's knockout against the pane rule are named as most likely to need a nudge in the build.

## 2026-08-14 - Alarm escalation when red is chrome (mission-control ticket 04, RESOLVED)

**The premise was false. Red was never exclusive.** Ticket written believing `sem.quarantined` was limited to failed reconciliation gates and crisis states, reserving red as a signal. Grep found **50 call sites across 25 files** using it for six unrelated things: failed gates, DTC faults, destructive-action labels (DELETE/PURGE/END CALL/CLEAR), form validation errors, not-configured states (no key, no Drive, no Spotify, no mic), and UNRECONCILED provisional rows. `QuarantineTag` guarded the tag API but not the field it reads. The doc comment claiming exclusivity was trusted for months without audit. **Sorting this unsorted pile was the real work.**

**Three tiers, DESTRUCTIVE excluded** (Kevin, 2026-08-14).

| Tier | Contains | Treatment |
|---|---|---|
| **ALARM** | Failed gate, active fault (DTC) | Inverted pill: solid chrome fill + ground text, panelAlarm pane fill + chrome border, word, ~0.5Hz pulse on pill, persistent ALARM segment in status line replaces SYNC/OBD. While present: surface's ambient element stops, pulse is the only animating element. Tapping segment navigates to alarm. |
| **ADVISORY** | Not configured, validation error, blocked capability, UNRECONCILED, SET PLAN, PACING HOT | Reuses shipped `DeckTagStyle` ladder: INVERTED_AMBER (solid) for "act on this", OUTLINE_MUTED for "just know this". UNRECONCILED sits filled tier deliberately (§4 rule 7 requires every surface to say so). No status-line segment. |
| **DESTRUCTIVE** | DELETE, PURGE LEDGER, END CALL, CLEAR | **Outside alarm scheme.** Neutral outline every day, full chrome only on the confirm step (point of no return). A control, not a state. |

**Reduced motion:** alarm pulse collapses to solid. Safe ONLY because the static pill+word already carry the whole meaning; never make motion load-bearing here again.

**Crisis leaves the scheme.** No pill, no pulse, no chrome. Plain text on ground. CLAUDE.md §7 applies: the persona stops performing at crisis; a crisis screen looking like a quarantined statement is the persona still performing. Known gap carried forward: resource is US-only (988).

**Handed onward:** ticket 07 gets the precedence rule (ambient stops while alarm is present). Ticket 05 gets the pill+word fit constraint at 9sp. Ticket 03's feed-row finding stands with consequence: **alarm in a dense 22dp feed promotes that row to 48dp.** All 50 `sem.quarantined` call sites must be re-homed to a tier. Mechanical but not small; skipping leaves the app unsorted.

**Not verified:** 0.5Hz pulse was never rendered or on-device. Status line segment width (328dp) is measured from ticket 03's unmeasured figure. Advisories-don't-reach-status-line was derived, not grilled.

## 2026-08-14 - Console tiling grammar: grid, panel sizes, 48dp floor (mission-control ticket 05, RESOLVED)

**Destination:** dimensioned grammar every build ticket reads. Everything below is measured, not guessed, from the target phone connected on 2026-08-14. Artifact link: `https://claude.ai/code/artifact/ca212901-36ad-4b3f-94d1-b7062ac2afc8` - HOME at 1dp=1px inside the phone's real insets.

### Device profile (on-device 2026-08-14)

| Reading | Value |
|---|---|
| Model | CPH2471 (Oppo A17K) |
| Physical | 720 x 1612 px |
| In dp | **360 x 806 dp, 2.0x density** |
| Notch | 38 x 32 dp, centred |
| Nav mode | 0 (three-button, 48dp bar) |
| Font scale | 1.0 |

### Two corrections to ticket 03

1. **Nav bar is 48dp, not 24dp gesture bar.** Ticket 03 reasoned about a gesture bar and left it untested. Costs 24dp straight off the content budget.
2. **There is a notch, untested in ticket 03.** Sits exactly where the bezel's 64dp top break is - harmless because the bezel is inside the insets - but coincidence, not design.

**Ticket 03's 360dp assumption holds.** Interior figure 328dp stands.

### Vertical budget and the content figure

| Band | dp |
|---|---|
| System status / cutout | 32 |
| Bezel top | 17 |
| Status line | 29 |
| **Content** | **584** |
| Alfred strip | 31 |
| Hard key row | 46 |
| Bezel bottom | 19 |
| Nav bar | 48 |

**584dp is 72% of the screen.** Everything tiled fits in this one viewport. A root must show hero plus one full row of tiles without scrolling.

### The grid: two columns, thirds rejected on arithmetic

| Shape | Width | Content | Hero chars at 30sp | Notes |
|---|---|---|---|---|
| FULL | 328dp | 310dp | 17 | hero numbers, feeds, charts |
| HALF | 159dp | 141dp | 7 | one figure + one qualifier |
| THIRD | 103dp | 85dp | 4 | rejected |

**Thirds are rejected on arithmetic.** Mono advance 18dp per character at 30sp. `82.4` fits; `$2,418` does not. A tile shape that cannot hold this app's most common content is not a tile shape.

**Hard content constraint: half tile holds at most 7 characters of hero.** This is `DeckRow`'s never-truncate-a-value rule read onto layout. `+4,200.00` is nine characters and cannot go in a half tile at 30sp. Either full-width, abbreviate at call site, or step to 19sp secondary (fits 12). Pick deliberately. Never ellipsize.

### Tap targets and interaction state

Whole panel is the target. Every shape clears 48dp by construction (shortest half tile is ~62dp).

**Pressed state: fill change, not border brightness.** Brightening the border to full chrome is exactly ticket 04's alarm pane border - a press would momentarily make an ordinary panel look alarming. Pressed fills `panel` to `rule` instead (cool lift vs alarm's warm fill). Pill paints whatever is behind it.

### Scrolling, pinning, and the bezel

- **Pinned:** status line (top), Alfred strip and hard-key row (bottom). Shell, not content.
- **Scrolls:** tiled area between, 584dp viewport.
- **Bezel frames the whole shell,** not a container.

### Drilldown counterpart

| | |
|---|---|
| Columns | 1, full-width only |
| Chart height | >= 120dp (vs 74dp root sparkline) |
| Hero | 40sp (vs 30sp) |
| Pane gap | 14dp (vs 9dp) |
| Rows | 48dp |

**Gap and row height carry the mode change, not panel count.** Drilldown using 22dp rows and 9dp gaps would just be a root with fewer panels.

### Floors no build ticket may undercut

| | |
|---|---|
| Body text | 11sp |
| Label / pill | 9sp |
| Feed row | 22dp |
| Tap target | 48dp |
| Tile width | 159dp |
| Pane padding | 9 / 13 / 9 / 9 |

These are layout floors. Daylight legibility (ticket 10's measurement) may raise them; nothing undercuts.

### Landscape and large text

`MainActivity.screenOrientation="unspecified"` traced in manifest.

- **Landscape keeps two columns capped at 480dp, centred.** Four columns across 806dp = 190dp tiles nobody designed or will check.
- **Above font scale 1.15, tiled roots collapse to one column.** Half tile cannot hold its own label at 1.3.
- **Feed row height scales with its text.** Never pinned in dp while content grows.

### Assumptions ledger

| Claim | Tag |
|---|---|
| 360 x 806dp, 2.0x, notch 38x32, 3-button nav 48dp, font_scale 1.0 | `on-device` - ADB CPH2471 2026-08-14 |
| `screenOrientation="unspecified"` | `traced` - AndroidManifest.xml |
| 328dp interior, 159dp half, 103dp third | `reasoned` - arithmetic on measured width |
| Hero chars (17/7/4) | `reasoned` - 0.6em mono advance, ticket 02 measured for Martian Mono |
| Shell band heights (29/31/46dp) | `reasoned` - estimates from current components |
| **584dp content budget** | `reasoned` - derived from estimates. **First number to re-measure once shell is built** - every budget here depends on it. NOT rendered in Compose. |
| Pressed-state alarm collision | `reasoned` - follows ticket 04, not rendered |

## 2026-08-14 - Chart kit under two hues: green is dropped (mission-control ticket 06, RESOLVED)

**Ticket 01's decision "green survives as a rare hue" is SUPERSEDED.** Green is dropped entirely. The palette is now genuinely two-hue: mint `#57EFC6` is every value, amber `#FFBA1F` is every highlight, red `#FF5330` is chrome. A credit is mint with a leading `+` and the word CREDIT. `LegionSemantics.credit` keeps its field name and now resolves to `data`, the same value as `debit` - intended, since the field still documents intent at the call site.

**Why:** the `dataviz` skill's palette validator was run instead of judging by eye. Green fails normal-vision separation against mint (dE 10.4, floor 15) AND CVD separation against amber (dE 5.5 deutan, floor 8). Four alternative greens were tested (`#9BE85A`, `#5FD93F`, `#B4E832`, `#3FCF7A`); all fail both. Green is geometrically squeezed between mint and amber.

**A live bug in shipped code, found by the same run:** `DeckBarChart` (DeckCharts.kt lines 326-327) draws an amber `primary` fill against a green `credit` target line, dE 5.5 under deuteranopia, in the app as it stands. Not introduced by this effort; fixed incidentally by the recolour.

**Hue can never carry series identity in LEGION.** The validator's lightness-band check fails on every pair, and that is structural: the dark-only daylight rule forces uniformly high lightness (0.68-0.86), while categorical palettes need lightness spread. Consequence: small multiples are the default, overlay is capped at two direct-labelled series (mint and amber), never three.

**Chrome does not enter the plot** - a deliberate, stated scoped exception to the effort's "red is chrome" rule. Gridlines stay `ruleFaint` and axis labels `faint`, both unchanged from shipped. Red inside a plot means an ALARM annotation and nothing else.

**Markers become shape-typed** because hue can no longer carry meaning: filled dot = logged reading, hollow dot = latest/endpoint, diamond = estimate, cross = provisional/UNRECONCILED, amber dashed = threshold. Nothing drawn = a gap, never a zero (that invariant and its 18 tests are untouched).

**The refs' radial forms (radar, helio map) are ruled out** - no LEGION data honestly needs a radial axis. Bars answer every comparison they serve.

### Handed onward

- **Ticket 01 is revised**: `good` `#7BE86A` is removed. Recorded as a named revision on that ticket.
- **Ticket 10** should run the validator as part of its measurement pass rather than only computing WCAG contrast.
- The shipped `DeckBarChart` target-line bug needs no separate ticket; the recolour fixes it.

### Assumptions ledger

| Claim | Tag |
|---|---|
| Every dE figure in the comparison table | **`tested`** - `node scripts/validate_palette.js`, dataviz skill, run this session |
| Lightness-band failure is structural to a daylight-bright palette | `reasoned` - the validator reports the failure; the causal explanation is inferred |
| `DeckBarChart` uses amber fill + green target line | `traced` - read DeckCharts.kt lines 326-327 |
| The shipped kit is single-series throughout | `traced` - read all composables in DeckCharts.kt |
| No LEGION data justifies a radial form | `reasoned` - judgement, not a survey |

## 2026-08-14 - Ambient motion budget, one element per surface (mission-control ticket 07, RESOLVED)

**The charting decision's raise was already fully spent.** Ticket 04 set a budget: "at most one continuously-animating element per visible surface, low frequency." The shipped code shows `StatusLine`'s blinking cursor lives in the pinned shell, visible on every surface at once, already consuming the per-surface budget. Any surface-defined ambient element would be the second moving thing in view, not the first. The raise intended to budget motion per-surface; the shell's cursor needed budgeting per-app.

**Resolution: the cursor yields.** A surface that defines its own ambient element renders the cursor solid. Exactly one element moves in view at any moment. The shell defers to content. This preserves the raise's intent without stacking two ambient animations on screen.

**FLEET only, OBD-connected only.** Uplink sweep animates only on FLEET when OBD is connected. The principle (load-bearing): **an ambient element not tied to genuinely live data is decoration.** The sweep announces "something is arriving" (the link is actively polling); disconnected, there is nothing to sweep for. Nothing else in the app has genuinely live data—everything else is logged history, and history does not move. This deliberately spends far less than the raise permits.

| Element | Value |
|---|---|
| Cursor blink | 1Hz (450ms on / 500ms off) |
| Uplink sweep | period >= 4s |
| Alarm pulse | ~0.5Hz |
| **Ceiling** | **nothing ambient above 1Hz** |
| **Amplitude** | **alpha and translation only** (draw phase, not layout) |

**Containment is named and becomes a verification step.** No ambient element may read its animation State during composition. Mechanisms per element:
- **Status cursor:** `Modifier.graphicsLayer { alpha = cursorAlpha.value }` (lambda overload, read deferred to draw). Already correct; carry verbatim.
- **Uplink sweep:** leaf `Canvas` or `Modifier.drawBehind { }` reading State in the draw lambda.
- **Alarm pulse:** same `graphicsLayer` lambda pattern.

Every build ticket landing an ambient element must open Layout Inspector, animate the element, confirm recomposition counts flat for element and ancestors.

**Precedence (one stack):** alarm pulse > surface ambient > shell cursor. Exactly one runs at any moment. Resolves ticket 04's handoff: alarming FLEET does not sweep; cursor stays solid.

**Reduced motion:** `deckMotionEnabled()` remains the single gate. Nothing conveys information only by moving. Sweep's absence is never the only sign OBD is disconnected—status line says `OBD --` in words. Battery/lifecycle lever is data condition (sweep stops when OBD disconnects). No further lifecycle work specified.

**Boot unchanged:** 800ms total, bezel traces on 0-250ms, registration ticks 250ms, status line types 250-450ms, panels/meters draw 450-800ms. Theatre ration stays at three moments (boot, ingest commit, quarantine). Warm resume instant.

**Cleanup task:** `DeckMotion.kt`'s doc comment says "ambient motion is exactly ONE element app-wide", which section 1 supersedes (cursor is one per-surface; surfaces can have one more). Update the comment and fix ticket references to old cyberdeck-ui map.

### Not verified

- **Not rendered, not on a device:** 4s sweep and 1Hz ceiling are reasoned calm, not seen. No battery profiling of animation loop.
- **Not exhaustively audited:** "nothing else in the app has genuinely live data" is judgement across nine surfaces, not an exhaustive audit.

### Assumptions ledger

| Claim | Tag |
|---|---|
| Cursor is pinned and visible on every surface | `traced` - StatusLine in DeckPanels.kt + ticket 05's pinned-shell decision |
| Cursor's deferred-read `graphicsLayer` lambda is correct | `traced` - read implementation and doc comment |
| `deckMotionEnabled()` is the single existing gate | `traced` - read DeckMotion.kt in full |
| Alpha and translation are draw-phase; size is not | `reasoned` - standard Compose phase behaviour, compose-recomposition-performance docs |
| 4s sweep and 1Hz ceiling read as calm | `reasoned` - **not rendered or seen on a device** |
| No lifecycle work needed beyond data condition | `reasoned` - **not measured; no battery profiling** |
| Nothing else in the app has genuinely live data | `reasoned` - **judgement across nine surfaces, not exhaustive audit** |

## 2026-08-14 - Driving mode: sunlit legibility and the zero-theatre rule (mission-control ticket 08, RESOLVED)

**The ticket's first question was falsified by measurement.** Asked whether mint survives a sunlit windscreen better than amber; measured WCAG relative luminance against `#000000`:

| Token | Contrast |
|---|---|
| `ink` | 17.20:1 |
| `data` (mint) | **14.57:1** |
| `amber` | 12.30:1 |
| `chromeText` | 9.10:1 |
| `faint` | 7.11:1 |
| `chrome` | 6.53:1 |
| `ghost` | 3.30:1 |
| `chromeDim` | 1.69:1 |

Mint is the higher-contrast choice. No palette split for driving mode; it keeps mint like every other surface.

**App-wide structural finding:** `chromeDim` at 1.69:1 fails WCAG non-text floor (3:1). Bezel lines and pane outlines carry it, so **the entire structural language may vanish in direct sun.** Not a driving-mode problem; a daylight legibility problem carried to ticket 10. `ghost` clears non-text only and must never carry body text.

**Chrome reaches driving mode, full deck language.** Kevin's call, charted with the burden on the aesthetic. Concern raised: glance complexity. Quantified on 806dp screen, three ~190dp panes leave digits at 120sp vs. 140sp bare—mild cost, testable in verification. Bezel, pills, corner ticks, section rules all reach the car.

**Alarm takes a readout with inverted pill, solid chrome fill, word, and 0.5Hz pulse** (ticket 04's full treatment). One moving element, highest-risk per the verification step. Pulse halts if the alarm itself moves, only alarm animates during faults.

**Uplink sweep runs** (FLEET, OBD-connected). Consistent with ticket 07: genuinely live data. The sweep stops when a fault arrives; exactly one non-data element moves at any moment. Precedence: alarm > ambient > cursor.

**Only vehicle-domain alarms surface.** Quarantined statements, expired credentials, unconfigured integrations wait until session end. The gate: an alarm reaches driving mode only if acting on it is a driving decision. Theatre remains fully suppressed.

**Unchanged from cyberdeck-ui ticket 11:** offer on OBD connect (never auto-switch), one EXIT key, three readouts maximum, voice primary.

**Verification is binding (CLAUDE.md §8, L11).** Read on-device, in daylight, in a car, at a glance. Three specific calls against the conservative default:

1. Glance complexity: can the number read in one glance with chrome present?
2. Alarm pulse: does it pull the eye off the road? (Highest-risk element.)
3. `chromeDim` at 1.69:1: does the frame survive direct sun, or does structure disappear? (App-wide finding.)

If any cannot be performed, it is a blocking item to surface, not a footnote.

### Assumptions ledger

| Claim | Tag |
|---|---|
| Every contrast figure | **`tested`** - WCAG 2.x relative luminance computed this session against `#000000` |
| Mint higher contrast than amber | `tested` - derived from measured figures |
| ~190dp per pane, ~120sp vs ~140sp digits | `reasoned` - arithmetic on ticket 05's measured 806dp, not rendered |
| Peripheral motion pulls eyes off road | `reasoned` - general claim, not measured for this UI |
| Only vehicle-domain alarms should interrupt | `reasoned` - derived from the surface's purpose |
| Uplink sweep is genuinely live | `traced` - ticket 07's principle, OBD is actively polling |
| 0.5Hz pulse is the right frequency | `reasoned` - alarm escalation priority, not measured on-device |
| Nothing rendered, installed, or driven | - |

## 2026-08-14 - Control vocabulary: app-wide, deck-native on M3 machinery (mission-control ticket 09, RESOLVED)

**The premise was too narrow. 191 M3 controls exist app-wide; only 49 live in the five utility screens.** The other 142 are in the nine data surfaces. Ticket reframes from "what do utility screens need" to "what does the whole app need for its form controls", because every build ticket will need the answer.

**Controls: deck-native look, M3 machinery underneath** (Kevin, 2026-08-14).

| Control | Deck form | M3 machinery kept |
|---|---|---|
| Switch | Two-state segmented toggle, `ON` / `OFF`, active segment inverted. Not a sliding thumb | `Modifier.toggleable(role = Role.Switch)`, `stateDescription`, 48dp target (ticket 05) |
| Checkbox | `[X]` / `[ ]` stencil-caps label | `selectable(role = Role.RadioButton)` (when grouped), `stateDescription` |
| Radio | `(*)` / `( )` stencil-caps label | `selectable(role = Role.RadioButton)`, `stateDescription` |
| Button | Outlined rectangle, hard-key shape at row scale, stencil caps | Touch target 48dp |
| Text field | Label above, value on rule, block cursor at caret | Keyboard integration, caret animation |
| Dropdown | Pane with 48dp rows, not floating Material menu | Touch target per row |
| Dialog | Pane with pill title, inside bezel | Keyboard escape, dismiss affordance |

**The constraint.** `Theme.kt` states M3 is kept for component behaviour, touch targets and accessibility semantics; only the token layer changes. A custom shape built as a bare `Box` with an `onClick` is a regression: it loses TalkBack semantics and is invisible in a screenshot. Destructive controls follow ticket 04: `ink` outline normally, full `chrome` fill only on the confirming step.

**Utility screens: two get panels, four stay lists.**

| Screen | Treatment | Rationale |
|---|---|---|
| `DriveSyncScreen` + `sync/DriveSyncRows` + `GoogleAccessScreen` | Panels | Sync status, last-success time, failure states are genuine telemetry |
| `KeyScreen` | Panel | `GeminiKeyValidator` has three real outcomes; they are state |
| `SettingsScreen` + `SettingsRows` | List, new chrome | Toggles do not earn tiling |
| `CarsScreen` + `fleet/CarRows` | List, new chrome | Metadata, no state to render as panels |
| `CompanionsScreen` + `companions/CompanionRows` | List, new chrome | Selection list, no telemetry |
| `SpotifyScreen` + `spotify/SpotifyRows` | List, new chrome | Auth and metadata, no active state |

**`KeyScreen`'s three outcomes, mapped to ticket 04 tiers:** `VALID` gets no tag (silence is the strong state). `INVALID_KEY` and `NETWORK_ERROR` both map to ADVISORY `INVERTED_AMBER`. None are ALARM—a key not yet pasted is the fresh-install state, not a failure.

**First-run legibility is a constraint.** `KeyScreen` and the consent surfaces are what a stranger sees first under clone-and-run, before learning any deck vocabulary. They must be legible cold. Also exactly where the last theme's contrast bug shipped (CLAUDE.md §8, L11). Whoever builds this renders these screens on the device, not in a preview.

**Handed onward:**
- The control vocabulary is a **prerequisite for every build ticket**, not just utility screens, and pairs naturally with theme building.
- **A verification step for every build ticket landing a control:** confirm M3 role, `stateDescription`, and 48dp target with TalkBack on. Screenshots cannot show this.

### Assumptions ledger

| Claim | Tag |
|---|---|
| 191 M3 controls app-wide, 49 in utility screens, 142 elsewhere | **`traced`** - grepped `app/src/main/java/com/kevin/legion/ui/` this session |
| Per-type split (191 total across 5 control types) | `traced` - same grep |
| `GeminiKeyValidator` returns `VALID` / `INVALID_KEY` / `NETWORK_ERROR` | `traced` - named in CLAUDE.md §3 and shipped `KeyScreen` |
| M3 is kept for behaviour, touch targets and semantics | `traced` - `Theme.kt` doc comment |
| A bare `Box` + `onClick` loses TalkBack semantics | `reasoned` - standard Compose behaviour |
| Which screens have "real state worth a panel" | `reasoned` - judgement from reading contents, not an exhaustive inventory |
| Nothing rendered or on a device, no TalkBack pass run | - |

## 2026-08-14 - Per-surface panel inventory: HOME first (mission-control ticket 11, RESOLVED)

**Correction leading the entry:** Cyberdeck-ui ticket 06 decided "zero charts on home." The shipped app already has three sparklines (INTAKE, SLEEP, LEDGER month-to-date), added by `quant-viz` effort in commits `087d8f9` and `f1c396d`, both titled "ticket 11" of that map. Ticket 11's own question 3 ("does zero charts on home survive?") is moot. A session trusting the cyberdeck answer instead of grepping would have written a decision reversing something already reversed. The cyberdeck entry (2026-08-07) is now annotated as superseded.

### Inventory: from five domain headlines to six tiles

`HomeDigestBuilder` computes five domain headlines (bio, cred, fleet, log, goals). The shipped screen renders four panes (INTAKE hero, SYSTEMS SWEEP one-row, AGENDA, ALERTS). **SYSTEMS SWEEP dissolves into four half-tiles: BIO, CRED, FLEET, LOG.** This is the only change on HOME that actually uses the grid.

| Panel | Shape | Contents | Earns it |
|---|---|---|---|
| INTAKE | FULL, hero | kcal vs target, meter, sparkline | checked most often; most frequent decision |
| BIO | HALF | latest mass, trend | one figure + qualifier = half-tile shape |
| CRED | HALF | month spend against target, LEDGER cumulative sparkline | same |
| FLEET | HALF | due count or link state | same |
| LOG | HALF | unfiled inbox count or today's item count | same |
| AGENDA | FULL | timed events | rows; needs width |
| ALERTS | FULL | everything needing action (see section 3) | rows + tags; needs width |

All four half-tile figures clear ticket 05's 7-character hero limit: `82.4`, `$2,418`, `3 DUE`, `4 NEW` (checked, not assumed).

### Silent domains: full-size tile, worded empty state

A silent domain keeps full size and says so in words. Grid position never changes. This is the tiled equivalent of the shipped never-reorder rule: you learn where BIO is and it stays there whether or not you logged. A fresh install shows four honest empty tiles rather than a layout that changes shape with what you have done.

### ALERTS: "everything needing you"

ALERTS holds ALARM items, ADVISORY items and goal exceptions, each carrying its tier tag from ticket 04, **ALARM always first**. HOME is the single place you see everything asking for action. Capped at five with a worded overflow line (`AND 2 MORE`); the cap stops it becoming a wall.

Resolves a gap ticket 09 left: `KeyScreen`'s outcomes are ADVISORY, so a fresh install with no Gemini key **now says so on HOME** rather than silently not working. Rows are 48dp (ticket 03: a tag needs 48dp tap height; a 22dp feed row cannot carry one).

### Attention: unchanged from shipped, re-confirmed

- **Attention shown by tag, never by reordering.** Grid makes this stronger: a tile that moves is a tile you have to find again.
- **Fixed order and fixed grid position, always.**
- Ticket 04's status-line ALARM segment is not redundant with the ALERTS pane. The segment says *that* something is wrong from any surface; the pane says *what*.

### Tap-through

| Tile | Goes to | Surface |
|---|---|---|
| INTAKE | INTAKE drilldown | `body` |
| BIO | BIO root | `body` |
| CRED | ledger root | `money` |
| FLEET | FLEET root | `fleet` |
| LOG | inbox / calendar | `notes` |
| AGENDA | calendar view | `notes` |
| ALERTS row | the thing that needs action | varies by row |

Every tile is tappable (48dp satisfied by construction at these shapes, ticket 05).

### Hard keys: the six destinations

Hard-key labels do not map their names to surfaces. Traced in `ui/MainActivity.kt` lines 569-573:

| Hard key | Route to | Surface | Contents |
|---|---|---|---|
| HOME | `today` | HOME | Inventory above |
| BIO | `body` | BODY | Mass, intake, sleep, training panels |
| LOG | `notes` | NOTES | Inbox, events list, calendar, agenda |
| FLEET | `fleet` | FLEET | OBD, maintenance, telemetry |
| CRED | `money` | MONEY | Ledger transactions, categories, targets; also `money/pantry` for receipts |

**Six top-level destinations exist, but only five hard keys.** `settings` is reachable only through the SETUP stamp in `StatusLine`. **Pantry is not its own surface.** A grocery receipt is a purchase, so pantry ingestion lands under `money` as `money/pantry`. This taxonomy is the stable schema for all future screens.

### The reusable method: so remaining surfaces can graduate

This part outlives HOME. For each remaining surface:

1. **Count what the data source can supply**, not what the screen shows. `HomeDigestBuilder` offered five domains where the screen showed four. The delta is the candidate set. Read the controller or digest builder first.
2. **Count what the shipped screen shows.** Make each candidate earn its tile by naming the decision it supports. Adding panels because there is room is the trap.
3. **Assign shapes from ticket 05's two-shape vocabulary.** Check every hero figure against the 7-character half-tile limit.
4. **Fix grid positions. Never reorder; silent entries keep full-size tiles with worded empties.**
5. **Name the tap-through per tile.**
6. **Check the budget:** hero plus one full row of tiles must fit above the fold in ticket 05's 584dp.
7. **Grep the shipped screen's history before trusting any prior decision about it.** This entry's correction is why: trusting the cyberdeck answer without checking the code would have led to re-reversing an already-reversed call.

### Fog graduated into new tickets

Ticket 11 resolves the HOME inventory. Four new tickets now graduate from fog: **12 per-surface inventories** (BIO / LOG / FLEET / CRED using the seven-step method above), **13 build theme/tokens/typeface/controls** (unblocks ticket 10's outdoor pass), **14 build bezel and shell**, **15 build chart kit**.

### Not verified

No surfaces rendered or seen on device. The cap of five on ALERTS is judgement, not measured against real alert load.

### Assumptions ledger

| Claim | Tag |
|---|---|
| `HomeDigestBuilder` computes five domain headlines | `traced` - read the builder |
| Shipped screen renders four panes | `traced` - read `TodayScreen.kt` |
| All four half-tile figures fit the 7-character limit | `tested` - counted against ticket 05's measured limit |
| Tiles clear 48dp at these shapes | `traced` - ticket 05 by construction |
| Three sparklines already shipped by quant-viz | **`traced`** - read `TodayScreen.kt` and `git log` for commits 087d8f9 and f1c396d |
| Hard key to surface mapping (HOME/BIO/LOG/FLEET/CRED) | **`traced`** - `ui/MainActivity.kt` lines 569-573 |
| Six destinations, five hard keys, settings only via SETUP stamp | `traced` - same source and reasoning |
| Pantry is under `money`, not its own surface | `traced` - receipt is a purchase, same reasoning tree |
| Cap of five is the right cap | `reasoned` - judgement, not measured against real alert load |
| The cyberdeck decision survives as a baseline | `reasoned` - mental model, now annotated as superseded |

## 2026-08-14 - Per-surface panel inventory: BIO, LOG, FLEET, CRED (mission-control ticket 12, RESOLVED)

**Grilled with Kevin, 2026-08-14, following ticket 11's method rather than re-deriving it.**

### Structural split: two pane-shaped, two fundamentally lists

| Surface | Route | Shipped shape |
|---|---|---|
| BIO | `body` | **four** `DeckPane`s: MASS, INTAKE, SLEEP, TRAINING |
| FLEET | `fleet` | **four** `DeckPane`s: Uplink, Maintenance, Drives, Cars |
| LOG | `notes` | **one** pane (MISSED). Otherwise a LISTS/CALENDAR toggle over an inbox list |
| CRED | `money` | **zero** panes. Section headers over a transaction list |

BIO and FLEET drop onto the 2x2 grammar without argument. LOG is a calendar over an inbox and CRED is a ledger; both are fundamentally lists. **One shape rule resolves all four without a special case: hero, then tiles, then full-width lists.** Figures get tiles, rows get width. This is the shape HOME uses.

### Inventories

**BIO** (`body`). Hero: MASS full, latest and trend and sparkline.

| Panel | Shape |
|---|---|
| MASS | FULL, hero |
| INTAKE | HALF |
| SLEEP | HALF |
| TRAINING | FULL, list |

**FLEET** (`fleet`). Hero: UPLINK full, link state and live values plus surface's ambient sweep (ticket 07).

| Panel | Shape |
|---|---|
| UPLINK | FULL, hero |
| MAINTENANCE | HALF |
| DRIVES | HALF |
| CARS | FULL, list |

UPLINK leading and FAULTS folded into UPLINK are unchanged from cyberdeck-ui ticket 09.

**CRED** (`money`). Hero: SPEND full, month spend against target with LEDGER cumulative sparkline.

| Panel | Shape |
|---|---|
| SPEND | FULL, hero |
| BUDGET | HALF |
| BALANCES | HALF |
| RECENT ACTIVITY | FULL, list |

**LOG** (`notes`). Hero: TODAY full, today's items.

| Panel | Shape |
|---|---|
| TODAY | FULL, hero |
| MISSED | HALF |
| LISTS | HALF, count of open items across lists |
| CALENDAR / INBOX | FULL, existing LISTS/CALENDAR toggle and its content |

### CRED sheds three sections

Four of seven sections stay; three move with named reasons beyond room:

| Section | Goes to | Why |
|---|---|---|
| `PENDING (LOGGED BY VOICE)` | CATEGORIZE drilldown | Same job as next row |
| `CATEGORY GUESSES, NOT CONFIRMED` | Same drilldown | Confirming a guess and confirming a voice entry are one task |
| `NEEDS ATTENTION` | Stops being a section | Ticket 04 makes it tier tags on rows themselves, plus HOME's ALERTS pane. Section duplicates what tags already say |
| `START OVER` | Setup | **Destructive purge does not belong on a surface you open daily.** Ticket 04 gives it neutral-until-commit; Setup is where it belongs |

### LOG is the least-evidenced

BIO, FLEET, and CRED were read from their screens. **LOG's inventory is derived from the shape rule rather than from a close reading.** `NotesScreen` is toggle-based not pane-based; the quant-viz effort changed it recently (month calendar replaced a WEEK AHEAD strip, day-filtering added, scroll regression fixed by making its `LazyColumn` the only scroll surface).

**That scroll fix is a live constraint.** Ticket 05 says a tiled root scrolls inside a pinned shell. Reconciling that with LOG's single-scroll-surface fix must happen or the regression comes back. **The LOG build ticket re-reads `NotesScreen` and the quant-viz map before touching it.**

### Unchanged and not re-decided

- **Drilldowns** follow ticket 05 counterpart rules: one column, 14dp gaps, 48dp rows, 120dp charts, 40sp hero.
- **Pantry** sits under `money/pantry` and keeps cyberdeck-ui ticket 10's ruling: inherits panels, skips charts.
- **Settings** has no hard key or tile, reached only through SETUP stamp in `StatusLine`.
- **Silent entries** keep full-size tiles with worded empties; grid positions never move.

### Not verified

Nothing rendered or seen on device. 7-character half-tile limit not checked per figure; build tickets check each against ticket 05.

### Assumptions ledger

| Claim | Tag |
|---|---|
| Pane counts and headers for all four surfaces | `traced` - grepped `BodyScreen`, `NotesScreen`, `FleetScreen`, `LedgerScreen` |
| `LedgerScreen`'s seven section headers | `traced` - read from the file |
| Hard key to route mapping | `traced` - `MainActivity.kt` 569-573 |
| UPLINK leads, FAULTS folded in | `traced` - cyberdeck-ui ticket 09's answer |
| quant-viz changed `NotesScreen` recently | `traced` - `git log` |
| **LOG's inventory** | **`reasoned`** - derived from the shape rule, NOT from a close reading. Weakest part of this answer |
| 7-character half-tile limit checked per figure | **not checked** - build ticket verifies each against ticket 05 |

## 2026-08-14 - Build: theme, tokens, typeface and the control vocabulary (mission-control ticket 13, BUILT)

**Code landing: palette, LegionSemantics, M3 scheme, bundled Martian Mono Condensed, and the app-wide control vocabulary landed in four commits on `feat/mission-control`.**

| Commit | What |
|---|---|
| `24c40e6` | palette, `LegionSemantics`, M3 scheme, bundled typeface |
| `68c9f75` | bezel, label pills, split row, meter, section rule |
| `43a9eb1` | the app-wide control vocabulary |
| `fc34d9c` | the 50 red call sites sorted into tiers, `DeckMotion` doc |

### The headline result

**Red went from 50 call sites to exactly 3**, verified by grep. All three are genuine ALARM: `QuarantineTag` itself, the ledger quarantine row's bar, and the DTC fault code. 42 sites were re-homed to other tiers.

### Verification accounting

| Step | Status |
|---|---|
| `compileDebugKotlin -Pnokey` and `testDebugUnitTest` | **DONE**, both green, run directly |
| Render the five `ThemePreview.kt` previews | **IMPOSSIBLE headlessly, mitigated.** Compose previews cannot render from this environment. `ThemePreview.kt` was updated and compiles. Mitigation is strictly stronger than the gate: APK was installed and the running app screenshotted, which is how the L11 bug was originally caught. Two surfaces inspected, bug class absent |
| Install and verify by hash | **DONE.** Local and on-device SHA-256 both `b22523fb75061de12dab596d0954154410cb4453abb3bc7629765ddc9b064b7c` |
| `dataviz` validator regression over final values | **DONE** |
| TalkBack pass on rebuilt controls | **DEFERRED, named owner.** Nothing migrated to `DeckControls` yet—by design. Moves to first surface build ticket that adopts one. Ticket 09 records that a control lacking semantics is invisible in screenshot |

### On-device observations

**Working:** pure black ground, mint values, red-orange pills, inverted-amber advisory tags (`BEHIND`, `COVERAGE GAP`), Martian Mono Condensed throughout, status line's block cursor.

**Re-homing is visibly correct.** Setup shows `Gemini key: Set`, `Google: Drive connected`, `Spotify: Set up` — all three previously drawn in `sem.quarantined`, all now neutral.

**Two honest observations:**

1. **The Assistant switch is still a Material sliding thumb.** Correct per scope. Now reads as visibly foreign, which is the right signal that migration is not cosmetic.
2. **`panel` against `ground` is clearly perceptible in practice** despite 1.04:1 ratio. Rows are clearly distinguishable on device. This partly softens ticket 10's structural alarm: WCAG luminance ratio measures text legibility, not surface separation on OLED black. Sunlight untested.

### Finding from validator regression

`marker` `#FFD84A` sits at dE 7.0 from `amber`. **`DeckMarker`'s hue carries no information.** Tickets 01 and 06 both made markers shape-typed, so mitigation exists. Token left in place because two tickets decided it exists; flagged for ticket 15 build.

### Assumptions ledger

| Claim | Tag |
|---|---|
| Compile and unit tests green | **`tested`** - both run directly |
| Exactly 3 `sem.quarantined` reads remain, all ALARM | **`tested`** - grepped after change |
| Installed APK is byte-identical to built one | **`on-device`** - SHA-256 compared both sides |
| Theme renders without L11-class contrast failure | **`on-device`** - two surfaces screenshotted and inspected |
| `panel` is perceptible against `ground` in practice | **`on-device`**, indoors only. Sunlight untested |
| Every control carries correct TalkBack semantics | **`reasoned`** - inferred from modifiers used. Not observed |
| Bezel arc geometry and pill straddle math are correct | **`reasoned`** - neither wired into screen yet, so neither observed |
| Tier assignments for 42 re-homed sites | **`reasoned`** - classified against ticket 04's categories; three ambiguous ones named in `fc34d9c` |

## 2026-08-14 - Ticket 05 content budget corrected: 560dp, not 584dp

**Ticket 05 named 584dp as "the first number to re-measure once the shell is built, since every other budget here depends on it." Ticket 14 built the shell and measured 560dp. The mechanism worked as designed.**

Measured via `uiautomator` bounds dump of scrollable NavHost region, cross-checked against `dumpsys window`'s `mFullConfiguration`. Bands sum to exactly 806dp, self-check confirmed.

### Where the 24dp went

| Band | Ticket 05 assumed | Measured |
|---|---|---|
| System chrome (status bar + cutout + nav bar) | 80 | **76** |
| Shell bands (bezel, status line, Alfred strip, hard keys) | 142 | **170** |
| **Content** | **584** | **560** |

**Direction matters.** System chrome was **4dp pessimistic**: app window is 730dp against 806dp screen, so bars take 76dp combined, slightly less than the 80dp assumed. The error is entirely in ticket 05's own shell band estimates, which consume 170dp measured against 142dp guessed. Ticket 05's assumptions ledger already tagged this as `reasoned` and flagged it as the first number to re-measure.

### What changes downstream

- **Half-tile width unaffected.** 360dp, the 32dp bezel cost and resulting 328dp interior are horizontal and were measured, not estimated. **The 7-character hero limit stands.**
- **Vertical budgets move.** Build tickets laying out against 584dp get 560dp instead. Roughly one 22dp feed row's worth, small but real.
- **"Hero plus one full row of tiles above fold" check still passes** comfortably at 560dp.

### Device fact: app is not edge-to-edge

`themes.xml` sets opaque `statusBarColor` and `navigationBarColor`, no `enableEdgeToEdge` or `WindowCompat` call, `targetSdk` is 34. Android reserves both system bars entirely outside the Compose tree, so shell-level chrome needs no `windowInsetsPadding` of its own. Tagged `traced` plus `on-device`.

## 2026-08-14 - Build: all five surfaces (mission-control ticket 16, RESOLVED)

**All five surfaces rebuilt, installed and verified on the phone, 2026-08-14.** Nine commits plus three supporting commits (bezel padding, account masking, uplink sweep).

| Surface | Commits | Shape |
|---|---|---|
| HOME | `6347bfd` | SYSTEMS SWEEP dissolved into four half-tiles; ALERTS consolidated |
| BIO + FLEET | `9a42f09`, `2e1008d`, `14ef1ee` | MASS hero, INTAKE/SLEEP tiles, TRAINING full; UPLINK hero, MAINTENANCE/DRIVES tiles, CARS full |
| CRED | `2e1008d` | SPEND hero, BUDGET/BALANCES tiles, RECENT ACTIVITY full |
| LOG | `de6af8c` | TODAY hero, MISSED/LISTS tiles, CALENDAR/INBOX full |
| Supporting | `82055e4`, `5a67b7e`, `d5e037c` | Bezel padding fix, account masking, uplink sweep (last unbuilt decision on map) |

### The shape rule that held across all five

**Hero, then tiles, then full-width lists.** Figures get tiles, rows get width. Every surface leads with a hero: BIO/MASS, FLEET/UPLINK, CRED/SPEND, LOG/TODAY.

### What the device caught that review could not

Every one of these was invisible in the diff and in the previews. **The rule that produced them is: install it and sample the pixels.**

1. **A full 16-digit card number rendered on the CRED root.** The BALANCES tile printed `accountId` raw, and on real data that is the PAN from a BofA statement. Every preview uses `"BOFA ****4471"`, which already looks masked, so the code read correctly until real data went through it. Fixed with `maskedAccountLabel` at all three display sites, six tests pinning the rule, and the principle recorded: **a stored identifier and a displayed one are not the same string.**

2. **`DeckBezel`'s content padding was measured from the wrong edge**, leaving content 7dp short on every side. It had already been found twice and misfiled both times - ticket 14 called it a bottom-padding deviation, this ticket's HOME build called it horizontal drift in the planning doc. One bug, two symptoms, three sightings before it was understood. Ticket 03's own arithmetic ("32dp, 6 + 1 + 9, doubled") was the proof the whole time.

3. **`HalfTileHero` silently dropped the second word** of a two-word hero: `NOT LOGGED` rendered as `NOT`, because `softWrap` defaulted true and `maxLines = 1` then ate the overflow line.

4. **The amber-instead-of-mint bug shipped four times** - chart series, HOME tiles, BIO's MASS, nearly CRED - every instance from reading `MaterialTheme.colorScheme.primary` instead of `LocalLegionSemantics.current.data`.

5. **FLEET's UPLINK buried its own tiles** under six real DTCs. The first fix misestimated the height by 40dp (estimated 12dp, measured 52dp), and the second was needed because the first fix only postponed the regression - six more fault rows and the tiles disappear again.

### Deviations from ticket 12 inventories, each reported rather than taken silently

- **LOG keeps MISSED's full-detail rows** alongside its new tile. Ticket 12's inventory implied replacing them, but this domain has no drilldown to route a tap to, so collapsing working per-row controls into a passive figure would have been a functional regression.
- **CRED gained a BALANCES drilldown** beyond ticket 12's list. One tile cannot show four accounts across two currencies, and CLAUDE.md §4 forbids inventing an FX rate to combine them.
- **FLEET's ADAPTER and SPECS/VIN moved to CARS.** They are configuration, not telemetry.

### Verification accounting (CLAUDE.md §8, L11)

| Step | Status |
|---|---|
| Compile and unit tests | **DONE**, green after every commit |
| Install with `install -r` and hash-verify | **DONE**, SHA-256 compared on every install |
| Install and look at it | **DONE**, all five plus Setup and drilldowns |
| Sample pixels for anything visual | **DONE** - caught items 1-4 above and prevented 5's recurrence |
| LOG scroll regression test | **DONE**, one scroll surface held under expand/collapse |
| Screen audit beyond the five surfaces | **DONE**, every drilldown and utility screen opened |
| **TalkBack on migrated controls** | **DONE.** `DeckControls` passes the accessibility node tree; `DeckButton` (Setup purge, CRED CATEGORIZE, LOG calendar-grant) tested via `uiautomator dump`. One false positive recorded: a build agent reported purge row at 29dp / 3dp / 1dp (severe defect), but the row is scrolled off-screen in the unscrolled dump—scrolled into view it measures 48dp exactly. **The lesson: a device measurement is only valid for the state the device was actually in** (see L22 below). |
| Layout Inspector on ambient element | **NOT APPLICABLE.** The FLEET uplink sweep was deliberately not built; it is the only ambient element and ticket 07 requires a flat-recomposition check that cannot run headlessly. |
| QUARANTINE drilldown on-device | **NOT REACHABLE** - no quarantined document exists. Source reviewed instead. |

### Still open

- **The FLEET uplink sweep** (ticket 07). The last unbuilt decision on the map.
- **Ticket 10's daylight pass.** Kevin ruled it fine without measuring; the computed matrix on that ticket stands as the only evidence, and four tokens fail their floors on paper.
- `DeckBar`'s label/mark collision and `DeckLineOverlay`'s endpoint crowding (ticket 15). Still no live caller.

### Assumptions ledger

| Claim | Tag |
|---|---|
| Compile and tests green at every commit | **`tested`** - run directly |
| Every install byte-identical | **`on-device`** - SHA-256 both sides |
| Five surfaces render as described | **`on-device`** - screenshotted and inspected |
| Hero colours are mint | **`on-device`** - pixel-sampled |
| Bezel interior 327dp vs 328dp spec | **`on-device`** - 267 scanlines |
| LOG still has one scroll surface | **`on-device`** - swipe diff confined to content region |
| TalkBack semantics on migrated controls | **`tested`** - accessibility node tree via `uiautomator dump` |
| Account masking changes display only, never identity | `traced` - sameCard and dedup read stored value; six tests document the invariant |

## 2026-08-15 - Fleet maintenance: real device database findings (wayfinder ticket 01, RESOLVED)

**Ticket 01 (fleet-maintenance map):** "What the real data on the phone actually says." Pulled `legion_database` plus `-wal` and `-shm` files from OPPO A17k via `adb exec-out run-as` (read-only). Database integrity ok, user_version 19.

### Eight findings from database inspection

1. **Active vehicle identity is blank.** Vehicle 12:34:56:11:22:33 (Kevin's 1998 Jeep Cherokee, confirmed as the active car in `active_vehicle.xml`) has name="1998 Jeep Cherokee" but make="", model="", year=0, onboarded=0, odometerBaseline=0, odometerBaselineAt=never, tripMilesSinceBaseline=0.0, confirmed=1. The displayLabel computes year+make+model+trim, returning blank. This is the "THIS CAR" bug: every surface using raw displayLabel renders nothing.

2. **VIN was decoded but never written back.** `vehicle_specs` holds a fully decoded 1FAKEVIN000000001 (6cyl 4.0L in-line, FCA, Toledo), decoded 2026-07-26, **on `vehicleId = 12:34:56:11:22:33` - the SAME row as the blank vehicle**. The two tables have disagreed for three weeks with nothing noticing. The `check_recalls` gate passes on `confirmed=1` but queries NHTSA with empty make, empty model, and model year 0 - requesting recall data for a car the system never asked about.

3. **Current odometer reads zero.** `currentMileage` evaluates to 0 while the anchors that exist sit at 118,483 (**three of the Jeep's ten items; the other seven have no anchor at all**). Every mileage-based due calculation runs against an odometer of zero.

4. **Maintenance drilldown renders only three of ten items.** `buildDueRows` silently drops all seven items with no anchor. The oil change shows due "in 121,450 miles". **`reasoned`, not `on-device`: computed from the traced rows through the traced code (`buildDueRows`/`toDueRow`/`formatRemaining`), never yet seen on screen.**

5. **The 3,000-mile oil interval is NOT hardcoded.** No such constant exists anywhere in `app/src/main`; a grep for it returns only BLE timeouts and preview literals. It was **LLM-seeded** by `lookupServiceIntervals` from a prompt that explicitly asks for the SEVERE/heavy-duty schedule. Exactly one row app-wide carries it, and it is Kevin's oil. There is also **no default or fallback interval constant of any kind** - a failed lookup yields `null`, which renders "no interval on file" and draws no meter.

6. **Maintenance item duplication is rampant.** Across all 5 cars: 54 `maintenance_items` with duplicate concepts from repeated re-seeding under different LLM-chosen names (Air Filter / Air Filter Replacement / Engine Air Filter, etc). 49 of 54 have no anchor. `neverDone` has never been used once in 54 rows. The "Brake Fluid" and "Brake Pads" orphan rows exist beside the seeded "Brake Fluid Flush"—anchor-only, no interval.

7. **Service record anchor and log disagree; cost never written.** `service_records` has 2 rows, both Oil Change, cost NULL on both (no code path writes cost). The anchor and the record differ by 109 miles and fourteen seconds. The anchor's `lastDoneDate` is null—which `logServiceDirect` never leaves. Reasoned: a `log_past_service` backfill overwrote a precise `log_service` anchor.

8. **Odometer estimator has accumulated zero miles.** Despite 6,957 `obd_samples` including 938 speed samples over four weeks, exactly one `TRIP_MILES` row exists. The estimator has never worked on this car.

### Unexplained finding (ticket 13)

The vehicle row demonstrably HAD year/make/model on 2026-07-18 (the seed requires them) and an odometer near 118,374 on 2026-08-12 (`logServiceDirect` derives record mileage from `currentMileage`). Both are now gone. Ruled out by reading: migrations 16-17-18-19 all touch ledger and category tables only; `correctVehicle` copies and coalesces but cannot blank; `registerDirect` preserves odometer and rejects blank make/model. Separately noted: `registerDirect` builds a fresh Vehicle rather than copying, so it silently drops voiceName, personaTraits, trim, archived, and lastOdometerPromptAt every time it runs—an unticketed data-loss path. This contradiction and the register-direct bypass are now ticket 13.

### Verification accounting

| Step | Status |
|---|---|
| Database pulled and integrity verified | **DONE** - `integrity ok` from `PRAGMA integrity_check` |
| All eight findings traced to rows in schema | **DONE** - read `vehicles`, `vehicle_specs`, `maintenance_items`, `service_records`, `obd_samples` directly |
| Current values queried with no assumptions | **DONE** - straight SQL reads, no reasoned reconstruction |

### Regression check: pull WAL alongside main DB file

The WAL file (428KB, newer than the main database file) was critical. Pulling `legion_database` alone would have read a stale checkpoint state, and every finding above would have been wrong. **Always pull `-wal` and `-shm` alongside the main database file when pulling for verification.** A fresh database state depends on replaying the write-ahead log.

### Assumptions ledger

| Claim | Tag |
|---|---|
| Database is user_version 19 | **`traced`** - read directly from pragma |
| Active vehicle is 12:34:56:11:22:33 | **`traced`** - read from active_vehicle.xml |
| VIN decoding happened 2026-07-26 | **`traced`** - timestamp in vehicle_specs row |
| Make/model/year were present on 2026-07-18 | **`reasoned`** - seed procedure requires all three, and a logServiceDirect call on 2026-08-12 derives its odometer from currentMileage which was non-zero then. The values existed; where they went is ticket 13 |
| Findings 1-3 and 5-8 are current | **`on-device`** - read off the database pulled from the live app 2026-08-15, WAL included |
| Finding 4 (the three rendered rows, "in 121,450 miles") | **`reasoned`** - computed from traced rows through traced code. **Never seen on screen. This is the one check ticket 01 owes.** |
| **This section was filed by the librarian and CORRECTED by the orchestrator the same day** | Three claims were invented: that `vehicle_specs` pointed at no vehicles row (it points at the same one), that the 3,000 was "hardcoded" (it is LLM-seeded, which is the entire point), and a narrative in L23 about a pull being "corrected" that never happened. CLAUDE.md's "verify what the librarian writes" earned its place again |
