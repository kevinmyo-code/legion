---
shelf: backlog-visuals
status: frozen
kind: backlog
tags: [library]
---

# Backlog: Visuals

> **STATUS: FROZEN ARCHIVE (banner added 2026-08-01).** This shelf is Midnight AI history: a
> head-unit car launcher with a commercial model and a city-pop design language. All three of
> those premises died in the 2026-07-30/31 pivot to LEGION. Nothing below governs LEGION.
> Read it as reference for why something was built the way it was. **Do not act on its
> blockers, sprints, backlog items, or hardware notes.** Live rules are in CLAUDE.md; live
> state is in memory/MEMORY.md. See CLAUDE.md §11.


Wallpaper generation, rolling road, avatar and photo image-gen items. Maintained by the
librarian.

## Quick wins

- ~~**B6/B7** — wallpaper cropping/customization~~ DONE 2026-07-08, needs field verify, folded
  into a full flow redesign (see below) rather than a narrow crop fix. B6's Appearance preview
  already used non-cropping `ContentScale.Fit` at the real aspect ratio (pre-existing, not
  touched); B7's "full car shows" addressed by adding explicit framing language to the shared
  generation prompt ("fully visible with all four wheels and the full roofline in frame, never
  cropped").

## Wallpaper generation redesign (2026-07-08, needs field verify)

Full flow rebuild per driver request ("make it easy for users to customize their wallpapers"),
not just the B6/B7 crop fixes. New unified photo (optional) -> presets -> 3 candidates -> pick
flow, shared by onboarding's BACKDROP step and Settings -> Appearance (was two separate, more
limited ad-hoc flows). Presets: color scheme (5 options: Shibuya Night, Bayshore Midnight,
Setagaya Dawn, Wangan Cruise, Enoshima Gold; "Bayshore Midnight" replaces an earlier "Roppongi
Rain" name flagged for its red-light-district association), time of day (Dawn/Day/Dusk/Night),
landscape (City Street/Coastal Highway/Mountain Pass/Countryside), and the existing
include-companion-avatar toggle. `ai/WallpaperPresets.kt` (new) holds the 3 enums;
`AvatarStudio.showaTextPrompt` takes them as parameters instead of a single hardcoded
palette/scene (root cause of "no customization possible" and part of B7). `ui/BackgroundGenerator.kt`
rewritten as the unified flow; `ControlPanelScreen.kt`'s `AppearanceSection` now just shows the
current wallpaper + a CUSTOMIZE button into it (the restyle/tweak quick-adjustment and Cruise
backdrop/rolling-road sections are untouched, different features). Background pre-generation
during onboarding chat was removed, it can't meaningfully guess presets the driver hasn't picked
yet, so generation now happens on-demand at the BACKDROP step instead.

**Also fixed: "avatar included was not the user's avatar but a random one."** Traced fully, there
was no ID-mismatch bug (`CompanionProfile.AVATAR_ID = "companion"` is used consistently
everywhere it's read/written). The real issue was `AVATAR_COMPOSITE_PROMPT`'s
identity-preservation language being weak ("add the character... in the exact same art style"
without emphasis on not redesigning them). Strengthened to explicitly list identity traits to
preserve (face shape, hair color/style/length, eye color, outfit+colors, body proportions) and
explicitly forbid redesigning/reinterpreting the character. This is a prompt-fidelity
improvement, not a deterministic fix, image-model compositing can still drift; needs a field test
to confirm it actually helped, not just a code-correctness check.

**Third follow-up fix (Cruise deck rework family), 2026-07-08 — typed onboarding wizard was
missing the wallpaper/backdrop step entirely. DONE, needs device verify.** `BackgroundGenerator`'s
own doc comment already said "shared by onboarding's BACKDROP step and Settings -> Appearance,"
but it was only ever wired into the spoken flow (`ConversationalOnboardingScreen`'s
`OnbStep.BACKDROP`), the typed wizard's `Step` enum (`OnboardingScreen.kt`) never had a backdrop
step at all, so anyone routed to (or choosing) "set up by typing" skipped wallpaper customization
completely and got whatever default the app shipped with. Added `Step.BACKDROP` between `CAR` and
`ABOUT` (after car facts are collected, so the description field can prefill from
year/make/model/trim, mirroring the spoken flow's `bgDescription` derivation), reusing
`BackgroundGenerator` directly rather than duplicating it, same composable, same presets, same
generation pipeline, just wired into the second entry point that was missing it.

## Named/trip photo albums with cover art (2026-07-14)

CODE DONE, merged to main (commit eb61854), device-unverified. Feature #4 from the sprint scope: driver can organize trip/named photo albums with generated cover art (not just a flat photo gallery). NEW `data/PhotoAlbumStore.kt`: filesystem-based album index (JSON at `filesDir/albums/index.json` + per-album folders with photos + `cover.png`; no Room table to skip a v7 migration). NEW `ui/AlbumCoverGenerator.kt`: clones `MixtapeCoverGenerator` flow (metered image gen, respects trial/sub budget; uses `AvatarStudio` image generation). LogbookScreen ALBUM tab restructured: shelf view (NEW ALBUM card + ALL PHOTOS card + one card per album) -> AlbumDetail (add photo / COVER ART generation / rename / delete) -> AlbumViewer (optionally scoped to an album, so delete/restyle routes back to album folder). ALL PHOTOS tab preserves the old flat view (collectAlbum: album files + build-entry photos + car/wallpaper). Cover art generation is on the same trial/subscription budget as avatar/wallpaper/mixtape generation (metered via `EntitlementManager.canGenerateImage`).

## Rolling road (fix + redesign together)

- **B5** — Fix generation failure
- **F2** — Curated preset list (author defaults) -> user picks

## Sprint 3 content-layer material (queue after blockers clear)

- **F1** — Wheel estimator (car photo + wheel style + size -> Gemini image-edit mock via
  AvatarStudio)

## Sprint 5 charm pass (queue for later)

- ~~**U11** — Rename "City-pop it" -> "Stylize photo"~~ DONE 2026-07-08, needs device verify. Button label and doc comment updated in `LogbookScreen.kt`; underlying `AvatarStudio.stylizePhoto()` function unchanged. Grepped for all "CITY-POP" / "City-pop" references, zero remaining.
- **U12** — Freeform tuning box for user prompt refinement in photo album

## Image-gen cost-control implementation (2026-07-13, branch image-gen-economics)

**COMMITS:** 3d4fbab + 46dda71 (not merged to main, not pushed as of session end).

**FEATURE WORK:** Product economics pass on image generation (trial avatar budgets, subscription image tiers,
backend entitlement broker integration). See decisions.md for the full scope and numbers.

**IMPLEMENTATION:**

1. **Backend (functions/config.ts + entitlements.ts):**
   - TRIAL_IMAGE_GENS constant: 3 -> 4 (one avatar + one wallpaper).
   - New constant SUB_IMAGE_CALLS_CAP=24 (monthly image-call cap for subs, separate from voice minutes).
   - entitlements.ts Entitlement type: new field `subImageCallsUsedThisPeriod` (default 0).
   - New function `hasImageBudget()`: checks separate image cap for SUBSCRIBED.
   - New function `consumeImageGen()` for subs: uses the image cap instead of subMinutes.

2. **Client (EntitlementManager.kt + AvatarStudio.kt + BackgroundGenerator.kt + AvatarGenerator.kt + ControlPanelScreen.kt):**
   - EntitlementManager: mirror TRIAL_IMAGE_GENS=4 and SUB_IMAGE_CALLS_CAP=24 constants.
   - New StateFlow `subImageCallsRemaining` (read from Firestore entitlements doc).
   - `canGenerateImage()`: TRIAL branch checks TRIAL_IMAGE_GENS budget; SUBSCRIBED branch checks
     subImageCallsRemaining (image cap, not voice). BYO_KEY and PAUSED unchanged.
   - New function `reduceImageWork()`: returns true only on TRIAL_ALLOWANCE and !hasKey (used as escape
     hatch for trial cost-reduction logic).
   
   - AvatarStudio.kt cost reductions on trial:
     - generateConcepts call sites: 5 -> 3 concepts on trial.
     - deriveAndSaveStates: copies chosen face to all talk states on trial (listening/thinking/speaking reuse
       the same face image, zero extra API calls).
     - derivePortraits/styledStates/generateRollingRoad/generateRollingRoadCar/describeCarPhoto: early-return on
       trial (not called at all).
     - generateBackgroundConcepts: clamps to 1 concept + no avatar composite on trial.
     - Rolling-road: sets a paid-extra lastError on trial (the "rolling road is a paid extra" message).
   
   - AvatarGenerator + BackgroundGenerator: new EXHAUSTED phase (points to Settings > AI power & plan to
     unlock) when image budget exhausted, instead of silent null. REGENERATE buttons relabeled "(3 NEW FACES)"
     to clarify what the button does.
   - BackgroundGenerator: hides photo picker + avatar-composite switch on trial.
   
   - ControlPanelScreen: new `ImageCostTips()` card in the BYO_KEY Plan section (educates trial users about
     budget limits and upgrade paths).

**VERIFICATION:**
- Functions tsc clean (TypeScript build successful).
- gradlew assembleDebug + testDebugUnitTest green (all 36 existing unit tests pass).
- No new unit tests added (cost-control logic is thin flag-checking; mocking the Firestore state machine is
  out of scope for this pass).

**OPEN ITEMS:**

1. **Broker not deployed.** Trial/subscription metering is built but only live end-to-end after `functions/`
   is deployed to Firebase. Key constant sync (config.ts <-> EntitlementManager.kt) is manual and must be
   checked before any production deploy.

2. **Monthly reset not built.** Both `subMinutesUsedThisPeriod` and the new `subImageCallsUsedThisPeriod` are
   never reset to zero at month-end. This is Phase B subscription-period work (logic: track period-start date,
   reset on next period-start via a Cloud Functions scheduled task or on-demand check at auth time). Not a
   blocker for trial metering (trial is one-time, not monthly), and subs are not live yet.

3. **Finalize cost numbers.** TRIAL_IMAGE_GENS=4 and SUB_IMAGE_CALLS_CAP=24 are starting estimates, sized to be
   safe but not empirically validated against current Google Gemini image-gen and Live-voice billing rates. Must
   be finalized (and may need adjustment) before general subscription launch, working backward from cost targets
   and actual usage patterns in the trial cohort.

4. **On trial -> BYO/sub upgrade: lazy portrait fill.** When a trial user upgrades to a paid tier (BYO key or
   subscription), their existing avatars still have idle-only faces (no listening/thinking/speaking portraits).
   On next regenerate, full portrait generation is unlocked. On the current avatar without regen, portraits fill
   in lazily the next time Cruise displays them (no visible UI change, just late population). This is expected
   per the product decision, not a bug. A possible follow-up: auto-derive portraits on upgrade (one-time), but
   deferred for now.

5. **Optional CLAUDE.md touch-up (not required).** CLAUDE.md §2 describes trial image sizing only qualitatively
   ("a small image-gen count"; now concrete: 4 = 1 avatar + 1 wallpaper). No frozen rules changed; the quantification
   is informational only. Documentation precision would benefit from a quick update, but not blocking anything.

**STATUS:** Code complete, not pushed or merged. Gradlew builds clean. No field validation yet (neither trial
budget depletion on real devices nor broker integration with live Gemini calls). Trial image exhaustion and
Firestore image-budget sync gated on Firebase Functions deploy and live entitlement checks.
