# Mapbox Nav SDK v3 on a phone: the facts that block or shape it

Research for `.scratch/mapbox-nav/issues/01-sdk-facts.md`. Researched 2026-10-03. Facts, not a
recommendation, except where a row says "least bad".

Tags:

- `sourced` - read on a Mapbox (or Android) primary page, URL given, fetched 2026-10-03
- `tested` - probed live from this machine with `curl` / `unzip`, 2026-10-03 (no Gradle run)
- `traced` - read in this repo
- `secondary` - third-party write-up, no primary found
- `reasoned` - inferred from the above, not observed

Short URL keys used below:

| Key | URL |
|---|---|
| INSTALL | https://docs.mapbox.com/android/navigation/guides/install.md |
| INIT | https://docs.mapbox.com/android/navigation/guides/initialization.md |
| MIGRATE | https://docs.mapbox.com/android/navigation/guides/migration-from-v2.md |
| PRICING-NAV | https://docs.mapbox.com/android/navigation/guides/pricing.md |
| PRICING | https://www.mapbox.com/pricing |
| SIGNUP | https://docs.mapbox.com/accounts/guides/signup.md |
| VOICE | https://docs.mapbox.com/android/navigation/guides/ui-components/voice.md |
| NOTIF | https://docs.mapbox.com/android/navigation/guides/ui-components/notifications.md |
| ROUTE | https://docs.mapbox.com/android/navigation/guides/turn-by-turn-navigation/build-the-route.md |
| REROUTE | https://docs.mapbox.com/android/navigation/guides/turn-by-turn-navigation/rerouting-and-refresh.md |
| PROGRESS | https://docs.mapbox.com/android/navigation/guides/turn-by-turn-navigation/route-progress.md |
| ARRIVAL | https://docs.mapbox.com/android/navigation/guides/ui-components/arrival-detection.md |
| SPEED | https://docs.mapbox.com/android/navigation/guides/ui-components/speed-limit.md |
| API | https://docs.mapbox.com/android/navigation/api/coreframework/3.32.0/ (Dokka reference) |
| DIRECTIONS | https://docs.mapbox.com/api/navigation/directions.md |
| GEOCODE6 | https://docs.mapbox.com/api/search/geocoding.md |
| GEOCODE5 | https://docs.mapbox.com/api/search/geocoding-v5.md |
| SEARCHBOX | https://docs.mapbox.com/api/search/search-box.md |
| SEARCHSDK | https://docs.mapbox.com/android/search/guides/search-by-category.md |
| COMPOSE | https://docs.mapbox.com/android/maps/guides/using-jetpack-compose.md |
| TOS | https://www.mapbox.com/legal/tos (last updated 2024-03-31) |
| PT | Mapbox Product Terms PDF, last updated 2026-07-21, linked from https://www.mapbox.com/legal/product-terms |
| ATTRIB | https://docs.mapbox.com/help/dive-deeper/attribution.md |
| UX | https://docs.mapbox.com/android/navigation/ux/guides.md and `.../ux/guides/install.md` |
| MAVEN | https://api.mapbox.com/downloads/v2/releases/maven |
| CHANGELOG | https://github.com/mapbox/mapbox-navigation-android/blob/main/CHANGELOG.md |
| AOSP-FGS | https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start |

---

## 1. Build and clone-and-run

**Headline: the core Nav SDK v3 no longer needs a secret download token.** The current install guide's
Maven block has no `credentials {}` and no mention of `Downloads:Read`, and the repository served
every artifact anonymously when probed, native binaries included.

| Fact | Detail | Tag | Source |
|---|---|---|---|
| Current version | `3.32.0`, released 2026-10-01/02. Tested with Maps SDK `11.32.0`, Nav Native `324.32.0`, Common `24.32.0`, Mapbox Java `7.10.1`. Patch lines 3.30.x, 3.29.x, 3.21.x also still shipping. | sourced | CHANGELOG; github.com/mapbox/mapbox-navigation-android/releases |
| Coordinates | `com.mapbox.navigationcore:android-ndk27:3.32.0` (16 KB page-size build) or `com.mapbox.navigationcore:android:3.32.0`. Group changed from `com.mapbox.navigation` in v3. | sourced | INSTALL, MIGRATE |
| What `android` pulls | `navigation`, `copilot`, `tripdata`, `voice`, `ui-maps` (all `-ndk27`). `ui-components` is separate. | tested | MAVEN POM `navigationcore/android-ndk27/3.32.0` |
| Maven repo block | `maven { url = uri("https://api.mapbox.com/downloads/v2/releases/maven") }`, nothing else. | sourced | INSTALL, Maps install guide |
| Secret token for core SDK | **Not required.** Anonymous `curl` returned HTTP 200 for the Nav, Maps, Nav-native (`dash-native-ndk27`, 44.7 MB), Maps-core (29.0 MB), Common (14.2 MB), maps-compose and Search SDK artifacts. | tested | MAVEN |
| Secret token still required for | The **Navigation UX Framework** ("Dash", public preview, evaluation terms, MapGPT). Its own install guide still demands `MAPBOX_DOWNLOADS_TOKEN`. Not the SDK we want. | sourced | UX |
| ToS wording conflict | TOS still says "When you use our APIs, including our SDK Registry/Downloads API, each request to an API must include one of your account's unique API keys." Server does not enforce it; docs no longer ask for it. | sourced + tested | TOS, MAVEN |
| Least-bad build pattern | Anonymous repo by default. If a `MAPBOX_DOWNLOADS_TOKEN` Gradle property is present, attach it as basic-auth credentials; if absent, resolve anonymously. No flavor, no stub, no `-Pnokey` analogue needed. A stranger's clone builds with zero Mapbox setup. | reasoned | (not built; Gradle not run) |
| Fallback if Mapbox re-locks the repo | Then a `nav` flavor (or a `-Pmapbox` gate) that swaps a no-op `NavEngine` binding via Hilt. Cost: a second source set that rots. Only worth building if the anonymous path breaks. | reasoned | - |
| minSdk | 21 in every AAR manifest checked. LEGION is 24. | tested | AAR manifests |
| compileSdk | AAR `minCompileSdk` is 1 for Nav/native AARs, **31** for `maps base` and `maps-compose`. LEGION is 36. Mapbox builds with compileSdk 37 since 3.32.0-rc.1, but that is not imposed on consumers. | tested + sourced | AAR `aar-metadata.properties`; CHANGELOG |
| Kotlin | SDK POMs depend on `kotlin-stdlib-jdk8:1.7.20`. v3 is Kotlin-only. LEGION's catalog says Kotlin 2.1.0; a newer compiler reads older metadata. | tested + sourced; compat reasoned | POMs, MIGRATE |
| AGP | Release notes cite AGP 8.10.1 / Gradle 8.11.1 for Mapbox's own build. AAR `minAndroidGradlePluginVersion=1.0.0`. LEGION is AGP 9.2.1. | sourced + tested | releases page, AAR metadata |
| Transitive pins worth watching | `kotlinx-serialization-json 1.3.1`, `coroutines 1.6.4`, `fragment-ktx 1.4.0`, `lifecycle-runtime-ktx 2.4.0`, `appcompat 1.6.1`, maps-compose on `compose-bom 2023.01.00`. All older than LEGION's, so Gradle upgrades them; risk is low but non-zero. | tested; risk reasoned | POMs |
| Native size, arm64-v8a | `libnavigator-android.so` 30.1 MB, `libmapbox-maps.so` 18.2 MB, `libmapbox-common.so` 6.6 MB, `libc++_shared.so` 1.2 MB. **56.3 MB uncompressed, 20.5 MB deflated.** Search SDK adds `libSearchCore.so` 3.5 MB. | tested | unzipped AARs |
| Native size, all four ABIs | ~216 MB uncompressed (armeabi-v7a, arm64-v8a, x86, x86_64). | tested | unzipped AARs |
| APK impact | LEGION has no `abiFilters` today. With modern AGP, native libs are stored uncompressed and page-aligned, so a universal APK grows by roughly the uncompressed sum. `abiFilters("arm64-v8a")` (plus `x86_64` for an emulator) keeps it near +56 MB. | traced (no abiFilters in `app/build.gradle.kts`); packaging reasoned | - |
| Compose: map | `com.mapbox.extension:maps-compose-ndk27:11.32.0` gives a `MapboxMap` composable; advanced features reached through `MapEffect` to the underlying `MapView`. | sourced | COMPOSE |
| Compose: Nav UI | Nav UI components (`MapboxManeuverView`, `MapboxTripProgressView`, `MapboxSpeedInfoView`) are **Views** only. The Nav install guide's own Compose sample wraps a `MapView` in `AndroidView`. The logic APIs (`MapboxManeuverApi`, `MapboxTripProgressApi`, `MapboxSpeedInfoApi`, route-line API) are UI-free and can feed our own composables. | sourced | INSTALL, SPEED, trip-progress guide |
| Drop-In UI | **Gone.** "Drop-In UI is not available in Navigation SDK v3 for Android." No replacement in the core SDK. (The UX Framework is the closest thing and needs the secret token plus eval terms.) | sourced | MIGRATE, UX |

## 2. Runtime token and account

| Fact | Detail | Tag | Source |
|---|---|---|---|
| Runtime token can be set in code | `MapboxOptions.accessToken = token`, "and change it in runtime. First, the token from `MapboxOptions` will be used. If it's not set, it will be read from resources." Must be set before inflating a `MapView` or the app crashes. | sourced | INIT, UX install guide |
| So BYO paste-in works | A `pk.*` pasted in Setup, stored in `KeyVault`, pushed into `MapboxOptions.accessToken` at process start (Application, not Activity) before any map or `MapboxNavigation` is created. No resource file needed. | reasoned from sourced | INIT |
| Token validation | `GET https://api.mapbox.com/tokens/v2?access_token=...` returns `code`: `TokenValid` / `TokenMalformed` / `TokenInvalid`. **An invalid token returns HTTP 200 with `TokenInvalid`**, so status code alone is not a verdict. | sourced + tested | https://docs.mapbox.com/api/accounts/tokens.md |
| Credit card | **Not required to sign up.** "Start without a credit card" gives demo access with caps. | sourced | SIGNUP |
| Demo caps (no card), per month | Nav SDK v3: **20 MAU**, **100 Active Guidance trips**. Directions 10,000. Temporary Geocoding 10,000. Search Box 5,000 requests. Maps SDK 100 MAU. Lower rate limits. One token, cannot be created, rotated or deleted. | sourced | SIGNUP |
| Hitting a demo cap | "you lose access to all Mapbox APIs until you upgrade." Navigation stops working outright. | sourced | https://docs.mapbox.com/accounts/guides/demo-access.md |
| Demo excludes | Raster Tiles, **Permanent Geocoding**, and the Boundaries/Traffic/Movement data products. Whether live traffic in `driving-traffic` routing is affected is not stated. | sourced; traffic impact unknown | SIGNUP |
| With a card | Pay-as-you-go free tiers apply (section 5). Usage-threshold email alerts are available. | sourced | demo-access guide |

## 3. Programmatic voice-control surface

Every row below is reachable from code with no UI interaction. Nothing found that needs a tap.

| Voice intent | API | Notes | Tag | Source |
|---|---|---|---|---|
| Text destination to coordinates (address) | Geocoding v6 `/search/geocode/v6/forward?q=&proximity=lng,lat`, or Search SDK `SearchEngine` | **v6 has no POIs.** v5 has also had POIs removed. | sourced | GEOCODE6, GEOCODE5 |
| Text destination (POI / business name) | Search Box API `/forward` (one-off, per request) or `/suggest`+`/retrieve` (session); Search SDK wraps both | Search Box `proximity` **defaults to IP** if omitted: always pass the live fix. | sourced | SEARCHBOX |
| "Nearest gas station" | Search Box `/category/gas_station?proximity=...`; Search SDK `Discover.search(query, proximity)` | Up to 25 results. `eta_type=navigation` adds drive-time ETA per result. | sourced | SEARCHBOX, SEARCHSDK |
| Search **along the route** | Search Box `/category` or `/forward` with `sar_type=isochrone`, `route=<polyline6>`, `route_geometry=polyline6`, `time_deviation=<min>`; Search SDK `discover.search(query, route: List<Point>)` | Results carry `added_distance` / `added_time`. | sourced | SEARCHBOX, SEARCHSDK |
| Request routes | `MapboxNavigation.requestRoutes(RouteOptions.builder().applyDefaultNavigationOptions().coordinatesList(listOf(origin, dest)).build(), NavigationRouterCallback)` | Up to 25 coordinates. `bearingsList` avoids an initial U-turn. `waypointNamesList` names stops for spoken instructions. | sourced | ROUTE |
| Start active guidance | `startTripSession()` + `setNavigationRoutes(routes)` with a non-empty list | Order does not matter; route + running session = Active Guidance. | sourced | ROUTE, PRICING-NAV |
| Route preview before committing | `setRoutesPreview(routes, primaryIndex)`, `changeRoutesPreviewPrimaryRoute`, `moveRoutesFromPreviewToNavigator` | Lets the assistant read options before starting. | sourced | API `MapboxNavigation` |
| Add / remove a stop mid-trip | **No dedicated API.** Rebuild `RouteOptions` from current location + remaining stops +/- the change, `requestRoutes`, `setNavigationRoutes`. | Changing the waypoint count **starts a new billed trip**. | sourced (billing), reasoned (method) | ROUTE, PRICING-NAV |
| Reroute | Automatic on off-route; `OffRouteObserver`, `RerouteStateObserver`; `setRerouteEnabled(bool)`; `replanRoute()` "for the case when user wants to change route options during active guidance"; `setRerouteOptionsAdapter` modifies options used by reroutes. | | sourced | REROUTE, API |
| Pick an alternative | Alternatives are routes at index > 0 in `RoutesObserver`; continuous alternatives tracked automatically; `switchToAlternativeRoute(route)`; `getAlternativeMetadataFor(route)` for the time/distance delta. | | sourced | REROUTE, API |
| Avoid tolls / highways / ferries | `RouteOptions` `exclude` values: `toll`, `motorway`, `ferry`, `unpaved`, `cash_only_tolls`, plus beta `tunnel`, `country_border`, `state_border`, `point(lon lat)` (max 50). | Mid-trip: change options via the reroute options adapter + `replanRoute()`. Exact wiring not verified. | sourced (params); reasoned (mid-trip wiring) | DIRECTIONS, API |
| Distance / time remaining, ETA | `RouteProgress.distanceRemaining` (m, Float), `durationRemaining` (s, Double), `fractionTraveled`, `remainingWaypoints`; `MapboxTripProgressApi` formats ETA. | New `RouteProgress` on every location update or once a second. | sourced | PROGRESS, API |
| Next maneuver + distance | `RouteProgress.bannerInstructions`, `currentLegProgress` (step progress), `MapboxManeuverApi` for formatted upcoming instructions. | | sourced | API, maneuver guide |
| Current road name | `LocationMatcherResult.road` (from `LocationObserver.onNewLocationMatcherResult`). | Works in Free Drive too. | sourced | API `LocationMatcherResult` |
| Speed limit | `LocationMatcherResult.speedLimitInfo`; `MapboxSpeedInfoApi`; Directions `annotations=maxspeed`. | | sourced | SPEED, API, DIRECTIONS |
| Traffic-aware profile | `applyDefaultNavigationOptions()` defaults to `PROFILE_DRIVING_TRAFFIC`. Falls back to `driving` where no traffic coverage. | | sourced | API `applyDefaultNavigationOptions`, DIRECTIONS |
| Congestion / incidents on route | Leg `annotation.congestion` / `congestion_numeric`; leg `incidents` (types: accident, construction, road_closure, ...; driving-traffic only); `RouteProgress.upcomingRoadObjects`; `hasUnexpectedUpcomingClosures()`; route `duration_typical` vs `duration` gives "N min worse than usual". Route refresh every 5 min if `enableRefresh`. | | sourced | DIRECTIONS, REROUTE, API |
| Stop / cancel guidance | `setNavigationRoutes(emptyList())` drops to Free Drive; `stopTripSession()` to idle; detaching / `MapboxNavigationProvider.destroy` ends the billing session. Default notification has an "end navigation" button. | Clearing routes with the session running **starts a Free Drive trip**. | sourced | NOTIF, PRICING-NAV |
| Free Drive | `startTripSession()` with no routes. Road name, speed limit, map matching. | Free Drive trips billed in metered pricing (section 5). | sourced | INIT, PRICING-NAV |
| Arrival | `ArrivalObserver.onWaypointArrival` / `onNextRouteLegStart` / `onFinalDestinationArrival`; custom `ArrivalController`; `navigateNextRouteLeg()`. | | sourced | ARRIVAL |
| Session state | `getTripSessionState()`, `registerTripSessionStateObserver`, `getNavigationSessionState()`, `isRunningForegroundService()`. | | sourced | API |

`MapboxNavigation` is one-per-process; `MapboxNavigationApp.setup { NavigationOptions }` + `attach(lifecycleOwner)` / `registerObserver` creates and shares it. `current()` gives synchronous access. It "can be safely used ... from a ViewModel". (sourced, INIT) It fits an injected controller wrapping the singleton. (reasoned)

## 4. Voice instructions and audio

| Fact | Detail | Tag | Source |
|---|---|---|---|
| Mapbox speaks only if we wire it | Audio comes from `MapboxAudioGuidance` or `MapboxSpeechApi` + `MapboxVoiceInstructionsPlayer`, both registered by the app. Not registering them means Mapbox says nothing. | sourced; "silent by default" reasoned from the wiring | VOICE |
| Instruction text is ours to route | `registerVoiceInstructionsObserver { vi -> }` delivers each `VoiceInstructions` at the moment it should be spoken. `VoiceInstructions` has `announcement()` (plain text), `ssmlAnnouncement()` (SSML), `distanceAlongGeometry()`. `RouteProgress.voiceInstructions` holds the current one. | sourced | API `VoiceInstructionsObserver`; github.com/mapbox/mapbox-java `VoiceInstructions.java` |
| Timing is critical | "Only new voice instructions are available via this observer because the timing of delivering and playing out an instruction is critical." A late spoken turn is a missed turn. | sourced | API `VoiceInstructionsObserver` |
| Onboard-TTS-only option | Docs suggest building `SpeechAnnouncement`s with only `announcement` text so the player uses Android TTS, to cut latency and Voice API cost. | sourced | VOICE |
| Mapbox player audio focus | `VoiceInstructionsPlayerOptions`: `focusGain` default `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`; `usage` default `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`; `contentType` default `CONTENT_TYPE_MUSIC`; stream `STREAM_MUSIC`; `abandonFocusDelay` default 0 ms. | sourced | API `VoiceInstructionsPlayerOptions` |
| Volume 0 is not mute | Volume 0.0 "does not release audio focus - other audio on the device will still be ducked". `MapboxAudioGuidance.mute()` suppresses playback; mute state persists in DataStore across launches. | sourced | VOICE |
| LEGION's own focus | `GeminiLiveSession` requests `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` with `USAGE_ASSISTANT`, and on any focus loss only clears its `ducked` flag, never pauses. | traced | `service/GeminiLiveSession.kt` ~L380-400, ~L2447-2463 |
| Conflict 1: talking over each other | If Mapbox's player fires mid-answer, its focus request makes the Gemini listener see `LOSS_TRANSIENT_CAN_DUCK`; Gemini keeps playing (by design), the system may duck it, and both voices overlap. | reasoned | - |
| Conflict 2: the mic hears the turn | Gemini capture is gated half-duplex only around Gemini's OWN speech. A Mapbox TTS turn would be picked up by the open mic and could be transcribed as the user speaking. | reasoned | - |
| Least-bad shape | Do not register Mapbox's player. Take `announcement()` from `VoiceInstructionsObserver` and speak it through ONE LEGION-owned speech path that already knows when the mic is gated and when Gemini is mid-turn. Whether that path is Android TTS or a text push into the live Gemini session is ticket 05's decision. | reasoned | - |
| UX Framework precedent | The (preview) UX Framework has an explicit "Bring Your Own TTS" `VoicePlayerMiddleware`. The core SDK has no such interface; you simply do not use its player. | sourced | `.../ux/guides/configuration/tts.md` |

## 5. Lifecycle, foreground service, billing

| Fact | Detail | Tag | Source |
|---|---|---|---|
| SDK ships its own FGS | `NavigationNotificationService`, `foregroundServiceType="location"`, declared in the Nav AAR manifest along with `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`, fine/coarse location. | tested | `navigation-ndk27-3.32.0.aar` manifest |
| When it runs | Whenever a trip session runs (Free Drive or Active Guidance), with a notification (maneuver, distance, ETA, end button). `startTripSession(withForegroundService = false)` disables it, and then **no location updates in the background**. `setTripNotificationInterceptor` / custom `TripNotification` restyle it. | sourced | INIT, NOTIF |
| Backgrounded mid-guidance | With the FGS, guidance keeps receiving location "even if the app is minimized"; `RouteProgress`, voice and arrival observers keep firing. | sourced (location); observers reasoned | INIT |
| Starting nav while LEGION is backgrounded | Android 12+ blocks starting an FGS from the background unless exempt (e.g. user turned off battery optimisation, `SYSTEM_ALERT_WINDOW`, a notification action). LEGION targets 34, so Android 14's type check also applies: a `location` FGS created in the background needs the location permission held at that moment, which `ACCESS_BACKGROUND_LOCATION` provides. LEGION already declares it. A screen-off "navigate home" is therefore plausible but **must be proven on the A25**. | sourced (rules); applicability reasoned | AOSP-FGS; traced manifest |
| What makes a trip | Active Guidance: session started with a route set. Free Drive: session with no route. Type or destination change ends one trip and starts another. Each **leg** of a multi-stop route is a separate trip. | sourced | PRICING-NAV |
| Caps | Active Guidance trip auto-splits at **12 h**; Free Drive at **1 h**. | sourced | PRICING-NAV |
| Grace period | **30 s** per trip session before it counts (v2.4.0+). Covers a brief Free Drive between guidance sessions, or an aborted start. | sourced | PRICING-NAV |
| Rerouting is not a new trip | New route within 100 m of the remaining waypoints and same waypoint count = same trip. Different count, or a waypoint moved > 100 m = new trip. | sourced | PRICING-NAV |
| Pause vs end | `stopTripSession()` only pauses data; restarting with the same route resumes the same trip. Destroying `MapboxNavigation` ends it; recreating it mid-journey bills a second trip. Keep the instance alive across screens. | sourced | PRICING-NAV |
| What is free inside a session | Directions, Map Matching, Route tiles, Voice API, route refresh requests made by the SDK during a session. **`requestRoutes` called with no session running is billed as a Directions request.** | sourced | PRICING-NAV |
| MAU counting | A user is an MAU after one trip. Reinstall after uninstall = a new MAU; every emulator / test device = an MAU. Showing a map also makes them a Maps MAU (separate line). | sourced | PRICING-NAV |
| Metered free tier (card on file) | Nav SDK for Mobile: **up to 100 MAU free** (then $0.30/MAU). Trips: **up to 1,000/month free** (then $0.08, $0.064, $0.048). | sourced | PRICING |
| Unlimited-trips option | Free up to **10 MAU**; switch only via sales. | sourced | PRICING, PRICING-NAV |
| Other free tiers (card on file) | Directions API **100,000** req. Temporary Geocoding **100,000** req. Maps SDKs for Mobile **25,000** MAU. Search Box `/category` + `/reverse`: 50,000 req (intro pricing) / 25,000 (standard). Search Box sessions: 500 (intro) / 2,500 (standard), as printed. | sourced | PRICING |
| So: "100 MAU, 1,000 trips, 100k Directions/Geocoding" | **Confirmed for an account with a card.** Without a card the caps are 20 MAU / 100 AG trips / 10k, and exceeding one cuts off all APIs. | sourced | PRICING, SIGNUP |
| Two users, rough budget | Trips: one per leg, plus a new one per stop added. Two drivers at ~4 legs/day is ~240/month: inside 1,000 with a card, over the 100-trip demo cap within two weeks. | reasoned | - |

## 6. ToS constraints for a personal app

Source is the Product Terms PDF (2026-07-21) unless noted. Section numbers are theirs.

| Clause | What it says | Consequence for LEGION | Tag |
|---|---|---|---|
| 1.2.2 Vehicle usage | If the Licensed Application "is related to vehicle usage", a separate development license and commercial license must be bought. Vehicle usage "includes (i) applications primarily intended for use within vehicles or (ii) use in any vehicle system or component". 1.2.1's free non-production allowance also excludes vehicle-related apps. | **Ambiguous for a phone nav feature.** Mapbox sells Nav SDK for Mobile self-serve with per-trip pricing, which implies phone driving apps are not the target; clause (i) plausibly means in-dash/IVI. But the text does not say so, and LEGION also has an OBD fleet aspect. Midnight AI (head unit) was squarely inside it. | sourced (text); reading reasoned |
| 1.4 Attribution | Mapbox logo, "(c) Mapbox", "(c) OpenStreetMap", and "Improve this map" on any Mapbox Map, prominent. The Maps SDK draws logo + info button by default; they may move but must stay on the map. If the built-in info button is hidden, a **telemetry opt-out** must be offered elsewhere. | Keep the default ornaments. | sourced (PT, ATTRIB) |
| 1.9 Default restrictions | Query only in response to human queries; no bulk/automated queries; no export, cache or store of results unless a section permits it. | A proactive "pre-geocode every saved place" job is out. | sourced |
| 2.7.1 Geocoding results | Do not display lat/lng of geocoding results to End Users; no building a POI/address database. | A place card must show the name/address, never raw coordinates from a Mapbox result. | sourced |
| 2.7.2 Temporary geocodes | "shall not export, store, or cache Temporary Geocodes." Default for v6 and Search Box. | **Saving a Mapbox search result as a LEGION place is not allowed** on temporary geocoding. Saved places must keep coming from our own GPS fix, or use permanent geocoding. | sourced |
| 2.7.3 Permanent geocodes | Allowed to store, needs a card on file, $5 / 1,000 from the first request (no free tier), only as an ancillary feature, one request per End User account, no lat/lng shown. | Possible for "save this place", at a small cost, card required. | sourced (PT, GEOCODE6, PRICING) |
| Search Box storage | "all data returned by the Search Box API endpoints is only available for temporary use." | POI results cannot be persisted. | sourced (SEARCHBOX) |
| 2.7.5 POI results | Only "in conjunction with a Mapbox Map". | A voice-only "nearest gas station" answer with no map shown is arguably outside this. Showing the result on the nav map satisfies it. | sourced; reading reasoned |
| 2.9.1 Mobile SDKs exclusive | "Customer shall use the Mobile SDKs as Customer's exclusive means of accessing the Service Offerings in mobile applications." | **Direct REST calls from the phone (as Midnight's `NavGeocoder` did) look non-compliant.** Search from the phone should go through the Search SDK (+3.5 MB native), or happen server-side in Django. | sourced; reading reasoned |
| 2.9.2 Version currency | Mobile apps must use the latest SDK or one released within the last 12 months. | A standing maintenance duty: bump at least yearly. | sourced |
| 2.9.5 Precise-location consent | End Users in states requiring consent, **Texas named**, must give Consent before use and be able to opt out of precise-geolocation sharing via a Mapbox-documented method. | Kevin is in Houston. Setup needs an explicit consent line and the telemetry opt-out reachable. | sourced |
| 2.10.1 Navigation APIs | "shall not export, download, cache or store results from any request to a Navigation API." | No persisting routes (Directions responses) to Room or Postgres. A saved "route" must be its waypoints, re-requested. Trip logs of our own GPS are ours. | sourced; reading reasoned |
| 2.8.1 Map content cache | Map tiles may be cached on-device up to 30 days within the SDK's limits. | The SDK handles it; do not build our own tile cache. | sourced |
| Google alongside | No clause found forbidding Google data next to Mapbox data. Constraints run the other way (POI results need a Mapbox Map; Studio styles only on a Mapbox Map). | `get_current_location` on Android `Geocoder` can coexist; displaying Mapbox POIs on a non-Mapbox map cannot. | sourced (absence after full read of PT); reasoned |
| Copilot | Full trip-trace upload; off by default; only the customer may enable it. | Leave it off. | sourced (PT privacy FAQ, product terms) |
| Account termination | SDK license ends with the account. | A household that closes its Mapbox account loses navigation, said in words. | sourced (PT 2.10.2) |

## 7. Lessons from Midnight AI (FROZEN history, phone-relevant only)

| Lesson | Still applies? | Tag | Source |
|---|---|---|---|
| Two tokens, never conflate: build-time secret (`Downloads:Read`) vs runtime public `pk.*` | **Half stale.** The secret is no longer needed for the core SDK (section 1). The runtime `pk.*` is still the BYO token and still what bills. | traced + tested | `memory/library/backlog-nav.md` L37-43 |
| Token stored encrypted in `KeyVault`, plaintext fallback on keystore failure, mirroring the Gemini key | Yes. `KeyVault` and `CompanionProfile.saveGeminiKey` exist in LEGION today. Per ADR 0044 the server may now be the better home (ticket 08). | traced | backlog-nav L45-49; `ai/KeyVault.kt`, `ai/CompanionProfile.kt` |
| Token validator: GET `tokens/v2`, parse `code` | Yes, with a correction: invalid tokens come back **HTTP 200 + `TokenInvalid`**; only `TokenValid` means valid. | traced + tested | backlog-nav L50-53 |
| Init context-holding singletons in `Application.onCreate`, not `MainActivity` (the FGS process may never create the Activity) | Yes, directly: `MapboxOptions.accessToken` and `MapboxNavigationApp.setup` belong in Application, since a voice turn from `AriaForegroundService` can start nav with no Activity. | traced | `memory/library/playbook-coding.md` ~L300 |
| `bbox` is a hard filter; use `proximity` for bias | Yes. Search Box and v6 both have `bbox` as a filter and `proximity` as a bias, and Search Box defaults `proximity` to IP. | traced + sourced | backlog-nav L146-151; SEARCHBOX |
| Coordinates are `lng,lat` | Yes, across all Mapbox APIs. | traced + sourced | backlog-nav L152-155; GEOCODE6 |
| Do not restrict `types`; do not hardcode `country=US` | Yes. | traced | backlog-nav L156-160 |
| Geocoder used v5 `mapbox.places` with POIs | **Stale.** v5 and v6 no longer return POIs; "Starbucks" would now fail. POIs need Search Box. | sourced | GEOCODE5, GEOCODE6 |
| Custom location source via `LocationOptions.Builder().locationProviderFactory(DeviceLocationProviderFactory, REAL)` | Optional now: a phone has its own GPS and the SDK's default provider works. Useful only if `LocationController` must stay the single merge point. | traced + sourced | playbook-coding ~L331; device-location guide |
| `Location.Builder.monotonicTimestamp` takes **nanoseconds**, not ms (lesson L3) | Yes, if a custom provider is built. | traced | `memory/library/lessons.md` L82-91 |
| On Android 14, an FGS declaring only `location` has no permission-free fallback; refuse rather than degrade | Yes; the SDK's own FGS is `location`-only. | traced | playbook-coding ~L336 |
| Drop-In `NavigationView` + Compose re-init gotcha (issue #6310) | **Dead.** Drop-In does not exist in v3. | traced + sourced | backlog-nav L78-79; MIGRATE |
| GL ES 3.0 gate (`reqGlEsVersion >= 0x30000`) | Head-unit concern. Any current phone passes; a cheap gate is harmless. | traced; reasoned | backlog-nav L65-68 |
| Read SDK bytecode (javap on the AAR from the Gradle cache) when a unit or semantic matters | Yes. | traced | playbook-coding ~L324 |

---

## Blockers and forks

| # | Item | Kind | Who |
|---|---|---|---|
| 1 | **ToS 1.2.2 "vehicle usage"** may require a paid development + commercial license. The text is broad; the self-serve Nav SDK for Mobile pricing suggests phone apps are fine. One email to Mapbox (sales/legal) settles it in writing. Could kill the map if the answer is "yes, buy a license". | Potential blocker | Kevin |
| 2 | **Card or no card.** No card: 20 MAU, 100 AG trips/month, and a cap hit cuts every Mapbox API. Two drivers likely exceed 100 trips. With a card: 100 MAU / 1,000 trips free, and usage alerts available. The map's "free tier covers two users" holds only with a card on file. | Fork | Kevin |
| 3 | **Phone-side search must go through the Search SDK (2.9.1)**, or search runs in Django. Midnight's raw-REST geocoder pattern looks non-compliant on a phone. Feeds ticket 03. | Fork | Kevin / ticket 03 |
| 4 | **Saved places vs temporary geocodes (2.7.2).** "Save this as a place" from a Mapbox result cannot be stored unless permanent geocoding is used ($5/1,000, card required, no lat/lng shown). Or keep saving only our own GPS fixes. | Fork | Kevin |
| 5 | **No stored routes (2.10.1).** Any "saved route" or route history must store waypoints, not Directions responses. | Constraint | Build tickets |
| 6 | **Who speaks the turns.** Mapbox's player and Gemini Live will overlap and the open mic will hear Mapbox. Route `announcement()` text through one LEGION-owned speech path. | Fork | Ticket 05 |
| 7 | **Texas precise-location consent (2.9.5)** plus a reachable telemetry opt-out. Small, but binding for Kevin and every household member. | Constraint | Build tickets |
| 8 | **Starting nav from the background** (screen off, voice only) depends on an FGS-start exemption plus `ACCESS_BACKGROUND_LOCATION`. Must be proven on the A25 before the voice-first story is claimed. | Risk | Ticket 12 |
| 9 | **APK size**: +56 MB for arm64 alone, ~216 MB across four ABIs. Add `abiFilters`. | Constraint | Ticket 09 |
| 10 | **Download-token ToS wording** contradicts the anonymous repo. Least-bad: anonymous by default, attach a token when one is present. If Mapbox re-locks the repo, clone-and-run needs a stub flavor. | Watch item | Ticket 02 |
| 11 | **Every stop added mid-trip is a new billed trip**, and clearing a route with the session running starts a Free Drive trip. Stop must end the session, not just clear routes. | Constraint | Ticket 07 |

## Assumptions ledger

| Claim | Tag |
|---|---|
| Nav SDK current version is 3.32.0, Maps 11.32.0 | sourced |
| Core Nav SDK artifacts download without any token | tested (anonymous curl, POMs and native AARs, HTTP 200) |
| Gradle will resolve the repo anonymously the same way curl does | reasoned (Gradle not run, per brief) |
| Install guide shows no secret token / credentials block | sourced |
| UX Framework still requires a secret token | sourced |
| ToS still says Downloads API requests must carry a key | sourced |
| minSdk 21, minCompileSdk <= 31 across checked AARs | tested (12 AARs) |
| LEGION's Kotlin 2.x / AGP 9.2.1 / compileSdk 36 are compatible | reasoned |
| arm64 native payload 56.3 MB uncompressed / 20.5 MB deflated | tested |
| APK grows by roughly the uncompressed size | reasoned |
| Nav UI components are Views; Maps has a Compose extension | sourced |
| Drop-In UI is gone in v3 | sourced |
| `MapboxOptions.accessToken` can be set and changed at runtime | sourced |
| No credit card needed to sign up; demo caps 20 MAU / 100 AG trips | sourced |
| A demo cap hit cuts off all Mapbox APIs | sourced |
| Demo access affects live traffic in routing | unknown, not stated |
| Invalid token returns HTTP 200 + `TokenInvalid` | tested |
| Every voice intent in section 3 maps to a public API | sourced (per row), except mid-trip add/remove stop and mid-trip exclude change: reasoned |
| `applyDefaultNavigationOptions` defaults to driving-traffic | sourced |
| Mapbox is silent unless its player is wired | reasoned from sourced wiring docs |
| Mapbox player default focus is GAIN_TRANSIENT_MAY_DUCK, usage NAVIGATION_GUIDANCE | sourced |
| Mapbox TTS and Gemini would overlap; mic would hear Mapbox | reasoned (from traced GeminiLiveSession behaviour) |
| SDK runs its own `location` FGS with a notification during any trip | tested (AAR manifest) + sourced |
| Starting nav from background works with LEGION's permissions | reasoned; needs on-device proof |
| 30 s grace, 12 h / 1 h caps, per-leg trips, 100 m reroute rule | sourced |
| `requestRoutes` outside a session is billed as Directions | sourced |
| Metered free tier 100 MAU / 1,000 trips; Directions and Temp Geocoding 100k | sourced |
| Two drivers ~240 trips/month | reasoned (assumed 4 legs/day) |
| ToS 1.2.2 may or may not cover a phone nav feature | sourced text, reading reasoned |
| Temporary geocodes and Search Box results may not be stored | sourced |
| Routes from Navigation APIs may not be stored | sourced |
| Mobile SDKs must be the exclusive access path in a mobile app | sourced text; "REST from phone is non-compliant" reasoned |
| Texas listed for precise-location consent | sourced |
| No ToS clause forbids Google data alongside Mapbox | sourced (absence in a full read of Product Terms) + reasoned |
| Geocoding v5 and v6 no longer return POIs | sourced |
| Midnight lessons (KeyVault, Application init, bbox, lng-lat, nanoseconds, FGS) | traced |
