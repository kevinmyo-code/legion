---
shelf: backlog-nav
status: frozen
kind: backlog
tags: [library]
---

# Backlog: Navigation

> **STATUS: FROZEN ARCHIVE (banner added 2026-08-01).** This shelf is Midnight AI history: a
> head-unit car launcher with a commercial model and a city-pop design language. All three of
> those premises died in the 2026-07-30/31 pivot to LEGION. Nothing below governs LEGION.
> Read it as reference for why something was built the way it was. **Do not act on its
> blockers, sprints, backlog items, or hardware notes.** Live rules are in CLAUDE.md; live
> state is in memory/MEMORY.md. See CLAUDE.md §11.


Embedded navigation, routing, and map display items. Maintained by the librarian.

## Mapbox embedded navigation Phase 1: BYO-token infrastructure (2026-07-08)

Code done, build verified (`gradlew assembleDebug` + `gradlew testDebugUnitTest` green),
fully offline-buildable (zero Mapbox SDK dependency added yet). Hardware risk flagged:
Mapbox Maps SDK v11 requires OpenGL ES 3.0; cheap double-DIN GPUs are real risk. Feature
is capability-gated rather than replacing Maps/Waze intent path — if GPU can't render,
no regression.

- **Decision:** Kevin explicitly reopened the frozen "Embedded navigation" decision from
  CLAUDE.md §8 (previously "❌ KILLED 2x", cost was the blocker). Reopened SCOPED to
  Mapbox specifically via BYO PUBLIC TOKEN model: driver pastes their own Mapbox public
  token (`pk...`), mirroring BYO-Gemini-key pattern exactly. Rationale: Mapbox bills per
  MAU with 100-MAU/1,000-trip free tier; single driver on their own token = 1 MAU,
  comfortably free, so driver's account absorbs their own usage and Kevin's cost stays
  zero. Preserves "everything on-device + user's own key" guardrail (CLAUDE.md §9).
  See decisions.md for full evaluation.

- **IMPORTANT TOKEN DISTINCTION:** Two Mapbox tokens, do not conflate.
  1. Kevin's SECRET download token (`Downloads:Read` scope): build-time only, goes in
     `local.properties` as `MAPBOX_DOWNLOAD_TOKEN`, never committed, only lets Gradle
     pull SDK artifacts. Does not bill usage.
  2. Driver's PUBLIC runtime token (`pk...`): the BYO one entered in Setup, set via
     `MapboxOptions.accessToken` once SDK is wired (Phase 2). This is what bills MAU/trips
     on driver's own account.

- **Phase 1 implementation shipped (no SDK wired yet):**
  - `ai/CompanionProfile.kt`: new Mapbox token slot (`mapbox_token_enc` encrypted +
    `mapbox_token` plaintext keystore-failure fallback, mirroring Gemini key's pattern).
    New methods: `mapboxToken()`, `saveMapboxToken()`, `hasMapboxToken()`. Reuses generic
    `KeyVault` as-is.
  - `ai/MapboxTokenValidator.kt` (new): clone of `GeminiKeyValidator`. GETs
    `https://api.mapbox.com/tokens/v2?access_token=...`, parses `code` field (`TokenValid`)
    from JSON body on 200. 401/403 → INVALID_KEY, else → NETWORK_ERROR. Reuses shared
    `KeyCheck` enum.
  - `ai/MapboxTokenProvider.kt` (new): SDK-free process-cache mirroring `GeminiKeyProvider`
    shape (`init`/`token`/`hasToken`). Has NO Mapbox SDK import yet (Phase 2 adds one line
    to push cached token into `MapboxOptions.accessToken`). Wired into same process-start
    call sites as `GeminiKeyProvider.init` (`AriaForegroundService.onCreate`,
    `MainActivity.onCreate`).
  - `ui/MapboxKeyEntry.kt` (new): clone of `ApiKeyEntry.kt` for optional `pk...` token.
    Status line, save button, PLUS a "TEST" button using `MapboxTokenValidator` (ping,
    busy/result UI like FirstRunScreen). Token explicitly optional, NOT in FirstRunScreen
    required-key gate — Gemini remains only required key.
  - `ui/ControlPanelScreen.kt`: new "In-dash navigation" block in `SetupSection`, sibling
    to existing "AI key" block, hosting `MapboxKeyEntry()`.
  - `vehicle/NavCapability.kt` (new): `supportsEmbeddedNav(context)` — GL ES 3.0 gate via
    `ActivityManager.deviceConfigurationInfo.reqGlEsVersion >= 0x30000`;
    `embeddedNavAvailable(context)` = GPU check AND `CompanionProfile.hasMapboxToken`.
    This is the gate Phase 2's `start_navigation` branch will check.

- **Phase 2 blocked (SDK wiring, needs Kevin's manual setup + Cherokee validation):**
  1. MANUAL PREREQUISITE (Kevin): create Mapbox account, generate secret download token
     (`Downloads:Read` scope), add to `local.properties` as `MAPBOX_DOWNLOAD_TOKEN=sk...`.
     Without this, Gradle can't resolve Mapbox Maven repo. Also generate personal public
     `pk...` token for on-device dev testing.
  2. Once unblocked: add Mapbox Maven repo to `settings.gradle.kts` (credentials from
     local.properties), add SDK deps to `gradle/libs.versions.toml` + `app/build.gradle.kts`,
     extend `MapboxTokenProvider.init` to set `MapboxOptions.accessToken`, build
     `ui/EmbeddedNavScreen.kt` (Drop-In `NavigationView` in AndroidView — note Compose-init
     gotcha mapbox-navigation-android GitHub issue #6310, re-init must be guarded),
     forward-geocode spoken destination via Mapbox Search/geocode API on user's public token,
     new `service/NavRoute.kt` state holder (mirrors `NavState.kt` pattern), branch
     `LiveToolbox.startNavigation` on `NavCapability.embeddedNavAvailable` (embedded if
     capable+geocoded, else Maps/Waze intent unchanged), wire `MainActivity`/`CruiseScreen`
     to show `EmbeddedNavScreen` when `NavRoute` active.
  3. DEVICE VALIDATION (Cherokee, load-bearing): does head-unit GPU render Mapbox Maps v11
     at all (GL ES 3.0 question)? Can only be answered on real hardware. If can't render,
     embedded nav stays gated off permanently and Phase-1 token infra is harmless dead weight.

- **UI DECISION:** ship Mapbox's stock Drop-In `NavigationView` UI first (fast path, proves
  GPU renders), reskin toward city-pop later — NOT custom nav UI from start, NOT map-only
  render-proof phase.

- **CLAUDE.md updates:** §3 (tech stack), §8 (frozen decisions table — Mapbox row updated
  from "❌ KILLED" to conditional roadmap candidate; Stadia/MapLibre/Google Nav SDK remain
  killed), §12 (new "approved motion exception" note for embedded map surface, alongside
  existing AmbientWallpaper exception), §15 (codebase map fixed: `NavPreferences.kt` was
  documented but never existed; corrected to real `NavState.kt`). All updated in same
  commit as reopen.

- **Committed:** "Mapbox BYO-token infrastructure (Phase 1 offline)" (2026-07-08).

## Nav-app picker: Maps/Waze/other configurability (2026-07-09)

Kevin requested the ability for drivers to pick their preferred navigation app (Maps, Waze, or any
other installed geo: handler) instead of the hardcoded Maps-first-then-Waze fallback. Shipped Phase
A (no dependencies, works in all flavors, integrates with companion badge overlay).

- **SERVICE / NAVPREFERENCES.KT (NEW):** Plain SharedPreferences wrapper (mirrors `DebugSettings.kt`
  pattern). Single pref: `preferred_nav_app` (package name string, blank = original fallback order).
  Methods: `setPreferred(packageName)`, `preferred(): String?`, `isBlank()`.

- **UI / CONTROLPANELSCREEN.KT:** New `NavAppPicker` composable in Setup section, placed right
  before the existing "In-dash navigation (Mapbox)" block. Uses horizontal `ChipRow` for a
  selectable list of installed nav apps. Discovery via `context.packageManager.queryIntentActivities`
  on a `geo:` intent scheme filter. Force-includes Google Maps + Waze even if they don't register
  the filter (rare vendor builds might not). "Default" chip = blank preference, original Maps-then-
  Waze fallback behavior. On selection, calls `NavPreferences.setPreferred(packageName)`.

- **ANDROIDMANIFEST.XML:** Broadened `<queries>` block to permit package visibility on geo: scheme
  handlers (required for `queryIntentActivities` under Android 11+ package visibility rules). Added
  `<intent><action android:name="android.intent.action.VIEW"/><data android:scheme="geo"/></intent>`
  to the existing <queries> tag.

- **LIVETOOLS.KT START_NAVIGATION DISPATCH:** Before the original Maps-then-Waze hardcoded resolve
  order, checks `NavPreferences.preferred()`. If set + package installed, uses it. If not set or
  package uninstalled (defensive), falls through to original behavior unchanged.

- **COMPANION BADGE:** `start_navigation` already opens the nav app full-screen and automatically
  shows the floating companion badge overlay (from prior session, CLAUDE.md sec 7). No changes
  needed; the picker just changes which app gets launched.

**Status:** Phase A complete, not hardware-tested. Verifiable by:
  1. Build: `gradlew assembleDebug` green.
  2. On device: open Setup, confirm NavAppPicker shows discovered installed nav apps.
  3. On device: select a non-default nav app, ask "navigate to [place]", confirm the picked app
     launches (not the default).

**Future:** Phase 2 (Mapbox embedded nav, see backlog-nav.md's Mapbox section) will add a branch
in `start_navigation` — if Mapbox token + GPU capable, embedded `EmbeddedNavScreen`, else fall
back to this picker. The two nav options (embedded vs app-picker intent) are orthogonal.

## Mapbox Geocoding API behavior (2026-07-28 session, verified/traced)

Implementation facts discovered during Phase 2 (embedded nav) dev work. Applied to `service/NavGeocoder.kt`.

- **`bbox` parameter is a HARD FILTER, not a proximity bias.** Mapbox Geocoding v5 docs, verbatim:
  "Limit results to only those contained within the supplied bounding box." Use `proximity` instead
  if you want a bias (ranked ordering, but results outside the box are included). A code path had
  added a ±2.2° bbox (~150 miles) around the current location fix, which meant any destination
  further than that returned a false "not found" error. Removed the bbox; destinations now geocode
  correctly. `traced` — Derek fetched live API docs.
- **Reverse geocoding: `/mapbox.places/<lng>,<lat>.json` — longitude first.** Confirmed via Mapbox's
  own docs example (`mapbox.places/-73.989,40.733.json`). This was already correctly implemented in
  response parsing (`parseFeature` reads `[lng, lat]`), but bears recording for future refs since the
  coordinate order is the inverse of what humans usually speak ("lat, long"). `traced` by Derek.
- **Unrestricted `types` parameter is correct for navigation destinations.** Restricting to
  `types=address,poi,place` excludes `region` (state name), `postcode` (zip code), `locality`
  (neighborhood), `neighborhood`, and `district` — all valid human spoken-destination inputs. Removed
  the type filter. `reasoned` by Derek.
- **Hardcoded `country=US` broke non-US drivers.** Removed. `traced` — this was in live code.
- **Android's `android.location.Geocoder` is unavailable on cheap AOSP head units** (requires GMS or
  vendor-supplied backend, absent on most cheap double-DIns). On the Cherokee, reverse-location lookup
  had silently been falling back to `LocationManager.getLastKnownLocation`, which bypassed
  `LocationController` (the single merge point for phone GPS beacon + device GPS, see §14) and
  returned a fix of unbounded age with no staleness check. Fixed: `get_current_location` now
  reverse-geocodes via Mapbox REST on the user's public token instead, staying on the unified location
  path. No fallback to `getLastKnownLocation`. `traced` by Derek.

## Navigation phase 2: Mapbox-only

**Pointer only — `decisions.md` 2026-07-25 and CLAUDE.md §8 are the authority.** Nav is Mapbox-only;
the Maps/Waze fallback, `NavPreferences`, `NavAppPicker` and the manifest geo `<queries>` block are
removed. Consequence: no token OR no GL ES 3.0 = no navigation, driver is sent to Settings.

Deliberately NOT restated in full here. A decision recorded in three places drifts in three places -
that is exactly how the §9.1 identity contradiction shipped, with eleven sites each asserting it and
one of them changing.

**Correction to what was filed 2026-07-28:** that entry said "GPS fix confirmed on Cherokee
2026-07-25". It is not. The head unit's own GPS is DEAD and not software-fixable - the antenna browns
out a shared rail and kills both WiFi and Bluetooth (CLAUDE.md §14, corrected a third time on
2026-07-25). Position comes from the phone beacon over the hotspot, and **nothing in the beacon path
is on-device verified.** Embedded nav on the Cherokee therefore depends on the beacon phone being
present and in PHONE role, on top of GL ES 3.0 and a token.
