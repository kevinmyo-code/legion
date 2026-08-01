# Coding Playbook

Accumulated architecture notes and conventions for the coding agent. Maintained by the librarian:
the orchestrator relays SKILL: lines from coding agent reports via a librarian FILE dispatch,
which appends them here.

Midnight AI (historical names in older notes: Moose, Aria, Nightrunner; same app). Package
`com.kevin.midnightai`. **LEGION's package is `com.kevin.legion`; mentally substitute it.**

> **STATUS: PARTLY LIVE (banner added 2026-08-01).** Section by section:
>
> | Section | Status for LEGION |
> |---|---|
> | Sub-agents: investigate loop vs one-shot | LIVE. `ai/SubAgent.kt` also takes an inline image part now |
> | Live session and tools (service/) | LIVE |
> | Gemini Live session lifecycle | LIVE, minus the active-talk billing accounting (no commercial model) |
> | Google Drive v3 concurrency and versioning | LIVE and load-bearing. See MEMORY.md's no-compare-and-swap blocker |
> | Soft-delete tombstone pattern for Room LWW sync | LIVE |
> | Testing, singletons, composables | LIVE |
> | Build / platform | PARTLY. Gradle and Room mechanics hold; AOSP 8-10 and head-unit constraints do not |
> | Music control (media/) | PARTLY. `MusicRouter`/`MusicSource`/mixtapes are retired; MediaSession and Spotify App Remote notes hold |
> | UI (ui/), Settings hub structure, Settings and preferences | FROZEN. City-pop, frame-clock motion, and the head-unit size classes are all dead. `ui/` is a clean slate |
> | Credential backup security | LIVE |
> | Gradle product flavor split for policy compliance | FROZEN. No commercial model, single build |
> | Image generation and media sync | FROZEN. Generated art died with city-pop |
> | Mapbox SDK API surface and Location bridge | FROZEN. Mapbox is removed entirely |
> | Maintenance features and three-state data model | LIVE, part of the fleet aspect |
>
> See CLAUDE.md §11.

## Music control (media/)

- `MusicController` (object) controls Spotify via MediaSession: `play/pause/next/previous` send
  transport to Spotify's active session; `playFromSearch(query)` uses
  `TransportControls.playFromSearch` (Assistant path); if no session, `playViaBrowser` cold-starts
  Spotify's MediaBrowserService on the main thread.
- GOTCHA: `playViaBrowser` posts to the main Looper and runs `MediaBrowser.connect()` + callbacks
  with no try/catch, an exception there crashes the whole app. Transport calls need guarding too.
- `NowPlayingController` mirrors the active session into a `StateFlow<NowPlayingInfo?>`; requires
  one-time notification-access grant (`hasAccess`). This is the signal to confirm a play actually
  started.
- FIXED 2026-06-28: the next/play crashes were the media framework needing a Looper thread, Live
  tool dispatch runs on a worker, so `controlMusic` now wraps every MusicController call in
  `withContext(Dispatchers.Main)`. ALWAYS call MediaSession/MediaController/MediaBrowser on Main.
- FIXED 2026-06-28: `controlMusic` search now confirms via `NowPlayingController.state.first { ... }`
  (4s window) before reporting success, was the "says playing but nothing happened" false-success
  bug.
- DECISION 2026-06-28 (from Business memo): MediaSession is permanent, Spotify Web API & App
  Remote are both blocked for solo devs (5-user dev cap, Extended Quota needs a 250k-MAU
  business). Invest only in MediaSession reliability; do not add a Spotify OAuth backend. See
  library/playbook-business.md.
- Tools are dispatched in `service/LiveToolbox.kt`: `controlMusic` (search returns optimistic
  success, a known false-success bug).

## Sub-agents: investigate loop vs. one-shot (2026-07-09)

- **SKILL: SubAgent.investigate() for adaptive tool-pulling**, SubAgent.askTyped() for
  grounding-only. The investigate loop (`SubAgent.kt`, ≤4 model POSTs, 30s budget) re-sends system
  prompt + function declarations on each POST, allowing the model to dynamically call tools based
  on prior results (DiagnosticAgent, SymptomAgent, MaintenanceAgent use this). `askTyped` is a
  single POST returning a typed `AgentResult`; it supports `google_search` grounding (pre-computed
  one-shot search results injected into the prompt) but NOT function-calling, since search + fns
  can't coexist in one Gemini call. Use `askTyped` when the agent's domain is well-scoped (pure
  reasoning over pre-seeded data or web search only, no tool-chaining needed): ColdStartAgent
  (numeric reasoning) and MusicAgent (taste + library + search) both moved from investigate loop to
  `askTyped` (commit bcdc870), saving latency + tokens on the driver's key. This is an internal
  optimization; no behavior changes from the user's perspective.

## Live session & tools (service/)

- `LiveToolbox.declarations()` advertises function tools; `dispatch()` runs them. Session/UI-scoped
  tools (e.g. `show_saved_places`) return null and are handled in `LiveSessionController.handleToolCall`
  (which wraps every tool in a timeout + try/catch and ALWAYS sends a tool response).
- Subtitles: `LiveSessionController.subtitle` is a SharedFlow consumed by the overlay in
  `AriaForegroundService`. FIXED 2026-06-28: output transcription is now ALWAYS on (`subtitles =
  true` in GeminiLiveSession, no longer debug-gated) and captions are mirrored to
  `CompanionPhase.caption` (StateFlow) which CruiseScreen collects + renders under the avatar with
  a 6s auto-hide. `CompanionPhase` is the process-global UI mirror (phase + caption).
- Foreground/background intents exist: `AriaForegroundService.ACTION_APP_FOREGROUND` / `_BACKGROUND`.
- `CompanionBadgeController` (floating companion badge, `TYPE_APPLICATION_OVERLAY`) replaced the
  earlier floating talk button entirely, see library/decisions.md R1.

## UI (ui/)

- `CruiseScreen` is the home HUD; observes `CompanionPhase.phase`, `NowPlayingController.state`,
  `AppBackground.version`. Design tokens in `ui/theme/` (`AriaColors`, `AriaType`), reuse them.
- `AvatarStudio.carImage()` returns the user's car photo, else a generated city-pop portrait.
- GOTCHA: SPEAK buttons (`AvatarGenerator`, `PersonaPicker`) use `RecognizerIntent` which most
  head units don't provide, they no-op to a toast. Being removed in favor of Gboard voice.
- Photo upload uses `PickVisualMedia` (`ControlPanelScreen`), head-unit photo picker is empty /
  can't see Drive downloads; needs an `ACTION_OPEN_DOCUMENT` (SAF) fallback.

## Build / platform

- [STALE 2026-07: app is Midnight AI, `app_name` string updated from the literal "AiApp"]
- [STALE 2026-07: launcher takeover via CATEGORY_HOME shipped, see CLAUDE.md sec 13; the earlier
  "no HOME launcher category" note no longer applies]
- Room migrations are manual & versioned in `data/local/CarDatabase.kt`. Bump `version` on every
  entity/column change, no exceptions (see the B14 lesson in library/blocking.md, a same-version
  entity-set mismatch throws instead of migrating).

## Settings & preferences (ui/)

- SKILL: The app-wide night-palette picker (`AriaPalette.current`, set via Settings -> Color palette)
  already propagates live into `LightsOutScreen` because that screen reads `AriaColors.Amber` /
  `NeonMagenta` directly inside the composable rather than caching them. Any future Lights-Out-specific
  feature that touches color should check whether the global palette system already covers it before
  adding a parallel color control.
- SKILL: `CruiseSettings` (in `CruiseScreen.kt`, package `com.kevin.midnightai.ui`) is the
  established SharedPreferences home for Cruise/Lights-Out driver-configurable display settings,
  prefs file `"cruise"`. Reuse its `flag`/`setFlag` or `variant`/`setVariant` helper patterns for
  new boolean/enum toggles instead of creating a new prefs object.

## Credential backup security (2026-07-09)

- GOTCHA: `allowBackup=true` in the manifest means any SharedPrefs file is eligible for cloud
  backup / `adb backup` / device-to-device transfer by default, unless explicitly excluded. The
  `companion_profile` prefs file holds the Gemini API key + Mapbox token, plus their plaintext
  Keystore-failure fallbacks and the spend-passphrase hash. This must be excluded. Use
  `SharedPreferences.getDefaultSharedPreferences()` (never backed up by default) or exclude the
  file by name in `AndroidManifest.xml` via `<include>` whitelist (backup whitelist, not
  blacklist, is the recommended modern pattern under API 31+). Commit 660208a added the exclusion
  for `companion_profile` in the backup/device-transfer rules. SKILL: when storing any credential,
  check whether the storage mechanism is eligible for backup and exclude it if it is.

## Gradle product flavor split for policy-compliant multi-product distribution (2026-07-09)

**CONTEXT:** Spotify SDK (App Remote, limiting 5-user Dev Mode) cannot be legally distributed in
a commercial public app. Splitting into two separate flavors/builds with different package names
+ signing fingerprints + distribution channels (public `play` on Play Store = zero SDK, personal
`personal` sideload = full SDK + personal registration) solves this. This is NOT Kotlin Multiplatform
expect/actual; it's raw Gradle flavor source-set resolution (simpler, already built into AGP).

**ARCHITECTURE (MEDIA/SPOTIFYBRIDGE.KT + MEDIA/SPOTIFYBRIDGEFACTORY.KT):**

The interface (`SpotifyBridge.kt`) is pure Kotlin, lives in src/main/java, and has zero Spotify
SDK imports:
```kotlin
interface SpotifyBridge {
    fun isAvailable(): Boolean
    suspend fun connect(context: Context): Boolean
    fun disconnect()
    suspend fun playUri(uri: String)
    suspend fun transport(action: TransportAction)
    val state: StateFlow<SpotifyPlaybackState?>
}
```

The factory exists TWICE, one file per flavor source-set:
- `app/src/play/java/.../SpotifyBridgeFactory.kt`: Returns `NoopSpotifyBridge` (every method is
  no-op, isAvailable always false), zero Spotify imports, zero SDK dependency.
- `app/src/personal/java/.../SpotifyBridgeFactory.kt`: Returns `PersonalSpotifyBridge` (Phase B
  implementation will use the real SDK). Currently a no-op stub.

**BUILDCONFIG FIELD PATTERN (APP/BUILD.GRADLE.KTS):**
```kotlin
flavorDimensions.add("distribution")
productFlavors {
    create("play") {
        dimension = "distribution"
        // Zero Spotify buildConfigField
    }
    create("personal") {
        dimension = "distribution"
        applicationIdSuffix = ".personal"
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"${System.getenv("SPOTIFY_CLIENT_ID") ?: ""}\"")
    }
}
```

Result: `BuildConfig.SPOTIFY_CLIENT_ID` exists ONLY in personal-flavor compile unit. Code in play
flavor that tries to reference it is a compile error, not a silent no-op or dead code. This is the
guarantee.

**GOTCHA: GRADLE FLAVOR SOURCE-SET RESOLUTION IS NOT TRANSITIVE.**
- `app/src/main/` code can import only from other `src/main/` code and from dependencies
  (buildscript-resolved at top level).
- A file in `app/src/play/` CANNOT import anything from `app/src/personal/` and vice versa.
- The interface pattern works because the interface is in `src/main/`, both factories are in
  flavor-specific directories, and `src/main/` code references only the interface, not the
  implementations directly.
- `app/src/main/java/media/MusicRouter.kt` calls `SpotifyBridgeHolder.bridge.transport(...)` —
  this is safe in all flavors because `SpotifyBridgeHolder` is in `src/main/` and the bridge is a
  no-op in the play flavor (via the factory's flavor-specific source-set return value).

**POLICY-CRITICAL VERIFICATION (MUST DO BEFORE MERGING TO MAIN):**
After building, unzip the compiled APKs:
1. Play-flavor APK: dex dump, grep for `com/spotify` (must be completely ABSENT). Grep for the
   literal `SPOTIFY_CLIENT_ID` value (must be ABSENT or empty string placeholder). This is proof
   the SDK and credentials are not in the public distribution.
2. Personal-flavor APK: reverse check (Spotify imports should be present once Phase B SDK is wired;
   client ID should be the real value from local.properties).

**COMMON ERROR: referencing buildConfigField in src/main/ code.**
If you write:
```kotlin
// src/main/...
if (BuildConfig.SPOTIFY_CLIENT_ID.isNotEmpty()) { ... }
```
This will NOT compile in the play flavor (field doesn't exist), and WILL compile in personal.
This is CORRECT — you've caught the architecture error. Move this code into `PersonalSpotifyBridge`
(in src/personal/) or into the tool dispatch layer, NOT into shared src/main/ code.

**FUTURE PHASE B WIRING:**
Once Kevin registers a Spotify developer app against `com.kevin.midnightai.personal` + his signing
fingerprint, and adds himself as an authorized Dev Mode user:
1. Add `SPOTIFY_CLIENT_ID` to `local.properties` (currently orphaned).
2. Add `com.spotify.android:spotify-app-remote` dependency to play/personal split only (via
   `configurations` + flavor-specific `implementation`).
3. Implement `PersonalSpotifyBridge` in `app/src/personal/...` (real SDK calls).
4. Test: `gradlew assemblePersonalDebug` should compile. `gradlew assemblePlayDebug` should still
   work with zero Spotify SDK present.
5. Unzip play APK, verify absence. Run Spotify tests on personal APK.

## Gemini Live session lifecycle and active-talk accounting (2026-07-15)

- SKILL: `LiveSessionController` active-talk begins without a fresh `LiveEvent.Connected` in three
  places: (1) resumeWarm (idle session reused), (2) requestSpeak's speakOnWarm branch (direct
  audio on existing idle session), (3) sendText fold-in (text without reconnect). Any per-session
  accounting (VoiceUsageMeter.startSegment, active-time billing, telemetry) must hook ALL three
  sites, not just the Connected event. The controller's connected state machine doesn't re-emit
  Connected on every active-talk start if the socket already exists.
- SKILL: `GeminiLiveSession.silentDestroy()` skips emitting `LiveEvent.Closed`, so any controller
  state normally finalized on Closed (meter cleanup, session-scoped counters, graceful shutdown
  hooks) must ALSO be closed out at every `silentDestroy()` call site (warm-idle timeout, user
  interrupt mid-call, resource cleanup on app pause). Do not rely on Closed alone; both exit paths
  must clean up.

## Google Drive v3 concurrency and versioning (2026-07-15)

- SKILL: Drive API v3 dropped v2's etag File field and replaced it with a `version` counter
  (incremented on every PATCH). The API provides NO server-side If-Match/412 precondition (documented
  omission as of 2026-07). Optimistic concurrency must be client-side: fetch live version immediately
  before PATCH, compare to last-seen, re-check on divergence. This is a TOCTOU race (fetch and write
  are not atomic); true atomic conditional-write is unavailable. Mitigate with conflict re-download/
  re-merge/retry loops (up to N attempts before failing) and fork guards (findByName-before-create
  to prevent duplicate-file races on parallel first syncs).

## Soft-delete tombstone pattern for Room LWW sync tables (2026-07-15)

- SKILL: To add soft-delete to a Room LWW sync table (e.g., car_tasks, tagged_places): (1) Add
  `deleted` @ColumnInfo(defaultValue="0") column to the entity (TINYINT/BOOLEAN in SQL). (2) Add
  real additive migration in Migrations.kt (Room v6 onward, verbatim generated SQL). (3) DAO delete
  becomes `UPDATE table SET deleted=1, updatedAt=NOW WHERE id=?` so LWW sees the tombstone (clock
  bump propagates it). (4) Reads filter `deleted=0` throughout (WHERE clause in DAOs, LiveData
  filters, voice-tool result sets). (5) Garbage collection deletes truly-old tombstones (e.g.,
  `WHERE deleted=1 AND updatedAt < CUTOFF`) in an existing retention-purge loop (no new job needed).
  SyncMerge/SyncEngine need no changes: deleted rows sync SELECT *, LWW merge handles them, and
  tombstones naturally expire by age. One caveat: sync SELECT must ship tombstones (deleted=1) so
  remote devices can apply them; retention cleanup only runs locally.

## Image generation and media sync (2026-07-24)

- SKILL: `AvatarStudio.carImage(context)` is @Preview-safe (reads two static filesDir paths only:
  car/photo.png + car/portrait.png). But `loadBackground()` AND `loadGarageBackdrop()` route through
  `ActiveVehicle.current` -> `ObdBluetoothManager`, whose static init THROWS in the preview JVM. Any
  @Preview composable that touches those must guard behind `LocalInspectionMode { ... }` with a
  procedural/empty fallback. This is the L1 root-cause class in lessons.md.
- SKILL: `AvatarStudio.requestImage`'s `generationConfig` has NO aspect-ratio or imageConfig field.
  One image per HTTP call; aspect is prompt-text-only ("Landscape 16:9" is a request, not a guarantee).
  Use `Bitmap.cropToLandscape()` post-hoc to enforce fixed 16:9 (1.778:1); the head-unit surface
  1024x600 is 1.707:1 (~4.2% off), absorbed by ContentScale.Crop.
- SKILL: `companion_media-<vehicleId>.zip` pack/unpack (`AvatarStudio.packCompanionMedia()`/
  `unpackCompanionMedia()`, `SyncEngine.uploadCompanionMedia()`/`ensureCompanionMedia()`) now uses a
  list-based `packZip()`/`unpackZip()` overload (N loose files by fixed entry name, wire-compatible
  with the old single-file format via delegation). To add a new synced media type: add it to the list
  on both pack + unpack; key it per-active-vehicle for active-vehicle paths, by explicit vehicleId
  for `ensureCompanionMedia` lazy-fetch (never cross the two).
- SKILL: `saveGarageBackdrop()` (like `saveBackground()`) MUST call `CompanionProfile.touchCompanion
  (context)` - companion_media upload only fires on `CompanionSync.decideCompanion`'s UPLOAD_LOCAL
  branch, driven by the companion clock, NOT by zip content diff. Save a synced media file without
  bumping the clock and it never uploads.

## Settings hub structure (ui/) (2026-07-24)

- SKILL: The settings hub is a grouped tile structure - a `GROUP_INFO` list drives 6 group tiles
  (Companion / Your car / Appearance / Connections / System / Reset), each launching a sub-menu
  (one of N leaf screens) with a BackHandler in `ControlPanelScreen.kt` managing the nav stack. The
  hub render slot is now `ui/GarageHub.kt` (diegetic workbench design with the car-on-hoist as the
  "Your car" tap target and bench objects as the other five groups).

## Testing, singletons, and composables (2026-07-28)

- SKILL: `gradlew testDebugUnitTest` runs on a plain JVM with unmocked `android.jar` — no Robolectric,
  no `unitTests.isReturnDefaultValues`. ANY `android.*` call throws `RuntimeException("Method ...
  not mocked")` at TEST RUNTIME, not compile time. To unit-test a helper, keep platform calls OUT of
  it. Example: `NavGeocoder.buildForwardUrl(...)` was made pure by moving `Uri.encode` up to its
  caller, which finally let URL param composition get regression tests. `tested` - Derek confirmed
  with a throwaway probe.
- SKILL: `com.mapbox.geojson.Point` has real structural `equals()`/`hashCode()` implementation
  (type + bbox + `Objects.deepEquals` on the coordinates array). Kotlin `==` is safe for dedup/
  identity without decomposing to `latitude()`/`longitude()`. `traced` - Derek decompiled
  `mapbox-sdk-geojson-7.10.0` with javap.
- SKILL: Context-holding singletons must `init()` from `MidnightApplication.onCreate()`, not
  `MainActivity.onCreate()`. `AriaForegroundService` can run in a process that never created
  `MainActivity` (e.g., voice-triggered `restyle_avatar`/`restyle_background` via `LiveToolbox` on
  the service side). A `MainActivity`-only init silently no-ops forever in that process shape, which
  silently broke `GenerationMeter`'s honest spend reporting. `traced` by Kevin.
- SKILL: A `@Composable` helper that calls `collectAsState()` internally forces its CALLER to
  recompose at that flow's rate. In `GarageHub.kt`, a `liveStatus()` helper collected
  `LocationController.state`, which drove GPS-fix-rate recompositions (dozens/min); two expensive
  IPC calls (`NavCapability` → ActivityManager, `SyncCapability` → GoogleApiAvailability binder)
  ran every fix. Non-reactive work inside a collecting helper needs its own `remember` block to
  cache results. `traced` by Derek.
- SKILL: Do not pass positionally-indexed lists across a composable boundary. `GarageHub` read
  `bench.getOrNull(1)` as "Appearance" and `getOrNull(2)` as "Connections", while `ControlPanelScreen`
  built that list via `GROUP_INFO.filter(...)`, which declared Connections first. Result: the
  tile labelled "LOOK" opened Connections and "EXTERNAL SYNC" opened Appearance. Fixed with a
  `GarageRole` enum on each item and lookup by role. `traced` by Derek.
- SKILL: A weighted child inside a vertically scrollable parent throws — measured against infinite
  max height. Restoring `verticalScroll(rememberScrollState())` to Cruise's COMPACT branch (which
  also had `Modifier.weight(1f)` + `Arrangement.SpaceEvenly`) caused a layout crash on preview.
  Both weight/spacing had to go; fixed spacing replaced them. `tested` on COMPACT layout.

## Mapbox SDK API surface and Location bridge (2026-07-25)

- SKILL: Read Mapbox SDK bytecode to verify semantics. Javap (`/c/Program Files/Android/Android
  Studio/jbr/bin/javap`) can show method signatures but not parameter units (nanoseconds vs millis).
  Decompile actual implementation: extract the AAR from `~/.gradle/caches/modules-2/files-2.1/com.mapbox
  .*/`, unzip it (`classes.jar`), unzip the jar (`com/mapbox/...`), then `javap -c` the real converter
  logic. This is how to confirm API semantics rather than trusting memory or docs. Example: `Location
  .Builder.monotonicTimestamp(Long)` takes nanoseconds (verified by reading `LocationServiceUtils
  .toCommonLocation` and `toAndroidLocation` bytecode), not milliseconds (which the field name suggested).
- SKILL: Wire a custom app-owned location source into Mapbox Nav SDK v3 via `LocationOptions.Builder()
  .locationProviderFactory(DeviceLocationProviderFactory, LocationOptions.LocationProviderType.REAL)` ->
  `NavigationOptions.Builder(context).locationOptions(...)`. The SDK never reads app StateFlows on its
  own. `DeviceLocationProviderFactory.build(request: LocationProviderRequest?)` takes a NULLABLE param
  and may invoke its callback only when a real location exists (if no fix exists, the callback may never
  fire, but that is the device's problem, not the SDK's contract violation).
- SKILL: On Android 14, typed-foreground-service permission enforcement keys off the manifest-declared
  `foregroundServiceType`, not the `startForeground()` overload used at runtime. So the "fall back to
  untyped startForeground" pattern only rescues a service that declares OTHER types too. A service
  declaring only one `foregroundServiceType` (e.g. `location`) has no permission-free variant and must
  refuse to start instead (no graceful degradation possible).

## Maintenance & LiveSession features (2026-07-29)

- SKILL: Room's `exportSchema=true` JSON generation runs during `compileDebugKotlin` (Gradle's kapt phase), not only at `assembleDebug`. The generated schema file in `app/schemas/<version>/` is available immediately after compilation, useful for a fast migration-SQL verification loop without a full build/assemble cycle.
- SKILL: `LiveToolbox` zero-cost data-read tools (get_codes, get_health, get_vehicle_data, etc.) live inline as `private suspend fun` returning `result()`; these are fast path tools that read Room / OBD state directly. Sub-agent hand-off tools wrap `SubAgent.investigate()` via `agentResult {}` (expensive, hit the model). New zero-cost reads should follow the first pattern.
- SKILL: `AriaBrain.sharedInstructions` (called from `assembleBase()`, cached ~2min) is the right home for tool-usage guidance, behavioral guardrails, persona defaults, and companion safety rules. The fresh-per-turn `buildLiveContext()` (called on every conversation start) is for live DB state only (current vehicle facts, now-playing track, weather, drive stats). Keep the split: cached rules + fresh facts.
- SKILL: `AriaDimens` now carries a spacing scale: `s1`=4dp, `s2`=8dp, `s3`=16dp, `s4`=24dp, `s5`=32dp. New spacing in `ui/` should map onto these five stops instead of inline dp values. It was previously referenced ZERO times across all of `ui/`, which made the original "use AriaDimens tokens" rule (CLAUDE.md sec 12) literally unfollowable until the scale was added.
- SKILL: `screenEdge(sizeClass)` and `sectionGap(sizeClass)` helper functions live in `ui/WindowSize.kt`, not in `ui/theme/`. The reason: `SizeClass` is a ui/-layer concept (Material3 WindowSizeClass, used only in composables), and the established import direction is `ui/ -> ui/theme/`, never reversed. Keep platform/theme-level code in theme, screen-layout code in WindowSize.
- SKILL: `AvatarVibe` composable is called with a SQUARE box on BOTH size classes: COMPACT uses 100x100dp, EXPANDED uses `avatarSize²` (a square). Avatar cropping/framing must account for both dimensions being equal (no assumption of portrait/landscape asymmetry).
- SKILL: `AvatarStudio.carImage()` is the driver's VEHICLE PHOTO (a wide dashboard-facing banner for the Cruise screen; intended as a "this is your car" intro). It is distinct from companion portrait avatars (the androgynous Zero figure). Do not conflate the two when deciding image-cropping alignment or frame-safety.
- SKILL: CruiseScreen's EXPANDED/MEDIUM layouts still call the pre-dock components (`CompactCruise` is a dock-layout idiom, scoped to COMPACT only); EXPANDED/MEDIUM Cruise is built from the earlier control pattern and untouched. Two idioms coexist deliberately; when reviewing responsive layout changes, trace which idiom is active per size class.
- SKILL: `VehicleController.NextService` data class has exactly one construction site (where the next-due service is computed for a voice response). It is safe to add new fields to this class, but always grep `NextService\(` before adding fields; make sure you're wiring all call sites.

## Maintenance three-state data model (2026-07-29)

- SKILL: `maintenance_item` rows have three distinct logical states (see decisions.md for the full feature): ANCHORED (lastDoneMileage/lastDoneDate both set), UNKNOWN (both null, neverDone=false), NEVER-DONE (neverDone=true). Do NOT coerce UNKNOWN to "0 days since" or "0 miles since" — that silently injects phantom overdue items. Code that reads lastDone* must explicitly check for null and treat it as UNKNOWN (no due-date inference). The DUE tab and prompt both filter: DUE only injects ANCHORED + NEVER-DONE, hides UNKNOWN.
- SKILL: `get_next_service` returns TWO SEPARATE lists: by mileage and by time. There is deliberately NO single merged ranking. A service due in 500 miles AND 1 month appears on both lists independently. Ranking miles against time requires estimating miles-per-day, which Kevin explicitly ruled out (confuses the driver's recent pace with the interval's design pace). The companion highlights what's immediate; the driver chooses which axis matters. Tools that consume NextService MUST handle the two lists separately; do not try to rank them.
- SKILL: `log_past_service(service_name, mileage?, time?)` backfill writes ONLY to maintenance_item, never to service_record. Remembered approximations must not pollute the precise ledger. A driver who says "I don't know the exact mileage" stays UNKNOWN (item stays guesswork-free) and the next service conversation asks for the missing axis.
