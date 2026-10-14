# Moose — Project Context Handoff
*For planning sessions in a separate AI chat. Last updated: 2026-06-29.*

---

## What This Is

**Moose** is an Android in-car AI companion for Android aftermarket head units — specifically the cheap Chinese double-DIN Android units that enthusiasts retrofit into older cars (BMW E46/E36, Jeep Cherokee, JDM builds like EG Civics, S-chassis, S30 Zs). The app runs as a persistent foreground service, owns a Gemini Live speech-to-speech (STS) session, and draws a floating avatar button over any other app.

**Positioning:** "the car that knows itself" — not generic AI, but an AI that knows THIS specific old car's history, codes, maintenance, mods, and personality. Defensible moat: OBD sensor data + compounding per-car history + character bonded to that data. Factory AI (NIO Nomi, SoundHound) can NEVER serve an out-of-warranty classic because there's no OEM deal. Carly/FIXD have codes without soul. Nobody combines conversation + car intelligence + tasteful design + classic-car fit.

**Target user:** the JDM/affordable-enthusiast tribe — modified, out-of-warranty cars that genuinely need diagnostics and maintenance tracking. They already run cheap Android head units (install base exists), they speak the city-pop/Showa aesthetic dialect natively, and they're tight loud communities (forums/Discord/IG/meets) = organic distribution.

**Solo developer:** Kevin. This is a one-person build. Every architectural choice must be achievable by one person.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Platform | Android (Kotlin, Jetpack Compose) |
| Voice AI | Gemini Live API (speech-to-speech WebSocket, server VAD) |
| Sub-agents | Gemini Flash REST (`generateContent`, one-shot workers) |
| Local DB | Room (v11 as of 2026-06-29) |
| OBD | ELM327 Bluetooth, Android BT RFCOMM |
| Music control | Spotify App Remote (AAR; on-device IPC) + Web API (PKCE curation) + MediaSession/AVRCP fallback |
| Navigation | Stadia Maps embedded (geocode + driving-traffic routing) + MapLibre CruiseMap; Maps/Waze intent as user-selectable alternative |
| Image generation | Gemini `gemini-2.5-flash-image` (Nano Banana) |
| Overlay | Android `SYSTEM_ALERT_WINDOW` (WindowManager) |
| Background service | `AriaForegroundService` (FGS microphone type) |

**Architecture north star:** "build like Claude Code" — lean orchestrator prompt (the Live STS session), pull-based tools and sub-agents instead of dumping all car data into context every turn. The Live model calls tools when it needs them; it doesn't carry the whole maintenance log on every turn.

---

## What's Built (Feature Inventory)

### Core Voice / AI Loop
- **Gemini Live STS session** — WebSocket full speech-to-speech, server VAD, half-duplex
- **Warm pre-connected session** — socket stays connected idle after a conversation ends (3-min park); next tap resumes instantly instead of paying a full connect cost. Service prewarms on start.
- **Split + cached system instruction** — `buildBaseInstruction()` (persona, tone, date, driver profile; cached 2 min) + `buildLiveContext()` (volatile: OBD codes, location, place, now-playing, nav dest, odometer; always fresh). Two-part inject: setup gets base; each conversation start injects fresh live context.
- **Sub-agent pattern** — `SubAgent.kt` (one-shot `generateContent` on `gemini-3.1-flash-lite`); Live model delegates domain tasks to specialist workers, keeping the main prompt lean.
- **Proactive engine** — `ProactiveBus` / `speakProactive()`; multiple one-shot openers (weather greeting, low battery, break nudge, milestones, idle chatter, arrival). Gated on onboarding, busy state, in-call.
- **Memory system** — `MemoryEntry` Room table. Pull-based `recall_memory(query)` tool (keyword-grep ranked by relevance + recency, RECALL_SCAN=200, RECALL_LIMIT=6). NOT pre-injected into the prompt.
- **Audio ducking** — cooperative focus duck + drop `STREAM_MUSIC` to ~15% on PTT turns, restored on turn complete.

### Companion Identity
- **Onboarding** — first run is a spoken STS conversation (`ConversationalOnboardingScreen`): the companion names itself, shapes its personality (voicing the picker axes as suggestions), learns the driver + car via onboarding tools, then continues to avatar generation → voice picker. The typed `OnboardingScreen` wizard (Name → Personality picker → Avatar → Voice → About You → Car facts) is kept as a fallback for no-key / mic-denied / opt-out.
- **Persona picker** (`PersonaTraits.kt`) — 5 trait stages (age/era, temperament, chattiness, humor, dynamic), assembles deterministically into `personaPrompt`. No freeform paragraph on a head-unit keyboard.
- **Avatar pipeline** (`AvatarStudio.kt`) — generates 5 concept images (Gemini image-edit, city-pop anime style), user picks one, derives 4 states (idle/listening/thinking/speaking), cached to `filesDir/avatars/companion/<state>.png`. Voice restyle: `restyle_avatar` tool (fire-and-forget, ~"give me a second"). Seasonal outfits (`OccasionStylist`) — generates styled versions ~9×/year on occasion change, cached. Time-of-day tint (SRC_ATOP colorFilter, no image regen).
- **Voice picker** (`Voices.kt`) — 8 curated prebuilt Gemini voices, sample clips in `res/raw`.
- **Identity = global companion** (tied to the app install, NOT the OBD dongle). Per-car odometer/maintenance still keyed by dongle MAC; the companion travels with the user.

### Cruise Screen (Foreground Default)
The app's in-car face when Moose is in the foreground. City-pop themed ambient dashboard.
- Full-screen backdrop (user's Showa wallpaper + scrim)
- Avatar center, tap = talk (`ACTION_TALK` → `onTap()`)
- Now-playing widget: **Cassette** (spinning reels + album art in center window) / **Vinyl** (spinning LP with album art in label circle) / **EQ bars** (with small art thumbnail). User chooses style. Album art loaded from MediaSession URI (downsampled, IO dispatcher; NOT stored in StateFlow = no OOM).
- Simulated visualizer bars (no real FFT — Android blocks capturing other apps' audio)
- OBD telemetry row: speed (GPS), RPM / coolant / battery voltage (OBD, polled 2s)
- **Bottom-right cluster:** retro segmented `VolumeMeter` (10 amber/magenta bars, polls 1s) + ▲▼ volume buttons + ◐ Lights Out + ⊞ app tray
- **Lights Out mode** (◐) — full-black screen, phosphor-green clock (`0xFF2E7D5B`), tap-anywhere to exit; drops window brightness to 0.04 via `DisposableEffect`, restores on exit
- **App tray** (⊞) — slide-up `AppTrayOverlay`, 5-col LazyVerticalGrid of all launchable apps (queryIntentActivities), tap-to-launch, "⇱ OS" escape-to-stock-OS, "‹ BACK"
- Settings ⚙ bottom-left → ControlPanel
- `LogbookObject` bottom-center (tilted binder graphic) → LogbookScreen

### Launcher Takeover
- App declares `CATEGORY_HOME` + `CATEGORY_DEFAULT` in manifest (already was, from before)
- `LauncherSettings.kt` — `isDefaultLauncher()` live check, `openLauncherSettings()` (ACTION_HOME_SETTINGS), `escapeToOS()` (finds another installed launcher, falls back to Home settings)
- ControlPanel Setup: "Use as home screen" toggle (reflects live state, opens system Home settings)
- Hardware HOME button returns to Cruise screen

### Logbook Binder (LogbookScreen)
Vintage ring-binder flipbook UI. Tabbed: **LOG** (red) / **TO-DO** (cyan) / **MODS** (gold) / **SPECS** (patina).

- **LOG tab** — `HorizontalPager` flipbook, aged-paper sheets, sepia ink, StampRed dates, chrome rings. Merges `ServiceRecord` + `BuildEntry` chronologically (oldest = page 1). Spend (cost per entry, cover total) gated by passphrase `SpendGate` (🔒 when locked). `Polaroid` composable renders build-entry photos (tilted, caption optional).
- **TO-DO tab** — `CarTask` list (voice add/check-off/remove; `add_car_task`, `complete_car_task`, `remove_car_task`, `list_car_tasks` tools)
- **MODS tab** — `BuildEntry` type="mod" list; per-entry 📷 photo picker (`PickVisualMedia`), saves to `filesDir/build_photos/<id>.png` via `AvatarStudio.saveLogbookPhoto()`. Rendered as compact Polaroid in LOG flipbook.
- **SPECS tab** — VIN decode controls (OBD button + manual typed VIN), decoded spec rows (engine/trans/powertrain/identity), editable paint color/code/notes, "Check recalls" button.
- Cover page: chrome spine, embossed title plate, tilted Polaroid of the car (user's real photo or generated city-pop portrait from make/model/trim).

### Car Intelligence (OBD + Maintenance)
- **OBD BT** (`ObdBluetoothManager.kt`) — ELM327 over RFCOMM, auto-reconnect loop, reads: coolant (01 05), RPM (01 0C), DTC codes (03), battery voltage (ATRV), VIN (09 02 multi-frame). `ObdResponseParser.kt` for all PID parsing.
- **Investigating specialists (2026-07-03):** the four sub-agents (`DiagnosticAgent`, `SymptomAgent`, `MaintenanceAgent`, `ColdStartAgent`) are no longer one-shot — each runs `SubAgent.investigate()`, a bounded Gemini function-calling loop (≤4 model POSTs, 30s budget, one-shot fallback) over `CarToolbelt.kt`. The worker PULLS exactly the car data its reasoning needs instead of the handler pre-injecting a guess. CarToolbelt read-only agent tools: `get_trend`, `get_code_history`, `get_service_history`, `get_maintenance_schedule`, `get_cold_starts`, `read_live_data`, `get_readiness`, `get_specs`, `get_quirks`, `get_oil_analyses`, `web_lookup` (nested search-grounded one-shot — the only legal way to search alongside function calls on Flash). Agents return typed `AgentResult`; handlers phrase rate-limit / bad-key / offline distinctly and record `KeyHealth`.
- **Diagnostic sub-agent** (`DiagnosticAgent.kt`) — `diagnose_codes` tool; investigates from live/named codes over the diagnostics belt. `describeCodes` stays one-shot (batch + disk cache).
- **Symptom triage sub-agent** (`SymptomAgent.kt`) — `triage_symptom` tool; plain-English "what's wrong" from behavior + a pre-seeded live snapshot, investigates the rest.
- **Maintenance sub-agent** (`MaintenanceAgent.kt`) — `ask_maintenance` tool; pre-seeded schedule + mileage, pulls service history and more via the belt.
- **Cold-start sub-agent** (`ColdStartAgent.kt`) — `check_cold_start` tool; pulls recorded cold-start bursts (`get_cold_starts`) and reasons over warm-up drift (no web).
- **Vehicle health** (`get_health` tool) — non-agent OBD snapshot: battery voltage, coolant, code count + concern flags. Proactive low-battery (<12.0V) opener.
- **Maintenance brain** — `MaintenanceItem` (per-car service intervals looked up via AriaBrain on first register), `ServiceRecord` (logged completions with mileage + date + optional cost). `VehicleController.logServiceDirect()` writes both.
- **Mileage tracking** — driver-reported baseline + GPS trip accumulator (`trackTripMileage`). Monthly prompt to re-confirm odometer.
- **VIN encyclopedia** — `VehicleSpec` entity (Room v9→v10), `VinDecoder.kt` (NHTSA vPIC free API), `VehicleSpecController.kt`. Voice tools: `lookup_vin` (OBD read + decode, read-only), `get_specs` (reads stored spec), `check_recalls` (live NHTSA recalls API, never stored). OBD VIN = multi-frame ELM327 Mode 09 PID 02 parse.
- **Build sheet / spend ledger** — `BuildEntry` (`id`, `type` mod/service/repair/consumable, `title`, `vendor`, `partNumber`, `cost?`, `date`, `mileage?`, `notes`, `photoPath`). `log_build_entry`, `list_build_history`, `get_spend` (passphrase-gated), `unlock_spend`, `set_spend_passphrase` tools. Total hidden by default; `SpendGate` uses hashed passphrase + 5-min session unlock.

### Saved Places
- `PlaceController.kt` — `tagPlace(context, label)` / `forgetPlace(context, label)` direct actions (labels normalized: "office"→"work", "house"→"home", strips articles). GPS-stamped `TaggedPlace` entries. Proactive "you've arrived at work" opener. `set_reminder(place, text)` tool for place-triggered reminders.

### Music
- **Spotify App Remote** (`SpotifyController.kt`) — on-device IPC, instant transport (play/pause/skip), PlayerState subscription replaces 3s Web API poll when connected. **Requires `spotify-app-remote-release-*.aar` in `app/libs/`** (download from github.com/spotify/android-sdk/releases).
- **Spotify Web API** (`SpotifyWebApi.kt`) — PKCE auth (`SpotifyAuth.kt`), encrypted token store (`SpotifyAuthStore.kt`), search/play/vibe/playlist curation. `SpotifyTokenProvider.kt` auto-refreshes; fires proactive voice notice + Crashlytics event on token revocation.
- **MediaSession/AVRCP fallback** (`MusicController.kt`) — transport-only over phone BT. `NowPlayingController.kt` polls Spotify Web API (3s) or listens to App Remote PlayerState; album art = file:// URI only (no Bitmap in StateFlow).
- Priority: App Remote → Web API → AVRCP.

### Navigation (embedded, reopened 2026-07-01)
- Default: **embedded turn-by-turn** via `NavEngine` — Stadia Maps geocode + driving-traffic routing, off-route reroute (5 stale-free GPS ticks), 5-min faster-route refresh. Android `Geocoder` fallback when Stadia key missing/offline.
- `MapLibre` `CruiseMap` renders Showa-style map with magenta-glow overview + traffic-colored per-step polyline + saved-place pins.
- External fallback: user selects Maps/Waze in Setup → `NavPreferences` (`moose`|`maps`|`waze`; default `moose`).
- Voice tools: `start_navigation` → NavEngine (or intent), `stop_navigation` → cancel, `get_nav_status` → ETA/dist/next-turn from `NavProgress`.
- Key files: `nav/NavEngine.kt`, `nav/StadiaApi.kt`, `nav/NavModels.kt`, `ui/CruiseMap.kt`, `ui/NavScreen.kt`, `service/NavPreferences.kt`.

### Telephony
- `TelephonyController.kt` — `PhoneStateListener` watches `READ_PHONE_STATE`. Announces incoming calls via `speakProactive`. `isInCall` flag mutes all proactive lines while a call is active. OS handles answering over BT HFP — Moose just watches state.

### Wallpaper / Avatar Generation
- `AvatarStudio.kt` — all image generation. Uses `gemini-2.5-flash-image` via raw OkHttp + Gemini API key from BuildConfig.
- `generateShowaBackground(context, photo?)` — photo edit or `generateBackgroundFromText(context, description)` for no-photo path. City-pop neon coastal style. Cached `filesDir/background/companion_bg.png`. `restyle_background` voice tool (fire-and-forget).
- `generateCarPortraitFromText(context, make/model/trim)` — city-pop car portrait for logbook cover when no real car photo.
- `saveCarPhoto()` — driver's real car photo saved separately (`filesDir/car/photo.png`).
- `saveLogbookPhoto(context, id, uri)` — downscales + saves build entry photo to `filesDir/build_photos/<id>.png`.

### ControlPanel (Settings)
Flat settings list (not a dashboard — the old HubScreen/GlassTile/GarageScreen was collapsed 2026-06-28). Sections:
- **Logbook** (opens LogbookScreen)
- **Saved Places** (opens SavedPlacesActivity)
- **AI Profile** — name, persona picker, avatar regen, voice picker, car facts (make/model/trim/year/odometer + 🔌 Fill from car VIN button when OBD connected)
- **About You** — driver name + info
- **Appearance** — wallpaper generate (photo picker or text description) + regen with adjustment, car photo upload
- **Test Voice** — speaks a line
- **Setup** — overlay grant, launcher toggle, nav app (Maps/Waze chips), recall alerts toggle, OBD connection status, music help, subtitles toggle
- **Reset** — forget memories, new companion, factory reset

### Room Database (v12)
Tables: `vehicles` (+ `confirmed` col, v11→v12), `maintenance_items`, `service_records`, `memories`, `tagged_places`, `car_tasks`, `reminder_items`, `build_entries`, `vehicle_specs`.

Migration chain: 5→6→7→8→9→10→11→12, all additive ALTER/CREATE, SQL verified byte-identical to generated `CarDatabase_Impl` after each migration. No `fallbackToDestructiveMigration`.

---

## What's NOT Built / Queued

### Hardware Validation (Critical — Nothing Below is Proven)
Everything described above compiles clean and runs on the emulator, but the emulator can't validate:
- Full-duplex audio (Gemini Live mic + speaker simultaneously)
- OBD BT (ELM327 RFCOMM, coolant/RPM/codes/VIN multi-frame)
- Phone BT music transport + AVRCP now-playing + album art
- Telephony call-state over HFP
- Launcher behavior (set-as-home, app-tray, HOME button, boot)
- Lights Out backlight drop
- Waze launch
- Recall proactive announce

### Photo Diagnosis (Wave 3 — Strong Moat Feature, Not Started)
Camera/gallery → Gemini vision + car make/model/trim/history grounding → "what's wrong with this leak / belt / warning light." Genuine moat-widener (Carly/FIXD can't; generic AI can't ground to THIS car). Self-contained; no new app.

### Fuel Tracking (Wave 2 Remainder)
MPG from OBD+GPS, fill-up voice log, cost/tank/trip. Needs its own Room migration.

### Mods Sub-Agent (`ask_mods`)
A dedicated voice-first way to query the mods/build history intelligently. On hold — basic log_build_entry/list_build_history tools exist but no reasoning sub-agent yet.

### Export / Build Card
PDF or shareable image of the build sheet. Resale tool / distribution flywheel. Not designed yet.

### Embedded Navigation (Reopened Decision)
Kevin's driving notes (2026-06-29) revisited the delegate-to-Maps-app approach. Wants the AI to directly control route/camera inside the app. Options:
- **Mapbox Nav SDK** — free embedded turn-by-turn, ~$20-50k/month at 100k MAU (previously dropped)
- **Google Navigation SDK** — gated enterprise license, not realistic
Current approach (delegate to Maps/Waze via intent) works but feels clunky with external app switching.

### Spotify Web API / In-App Player (Reopened Decision)
Driving notes want native Spotify playback control inside the app UI (no leaving the interface). Currently all control is via MediaSession/AVRCP which is transparent (controls the phone's Spotify). Web API would allow queue management, specific-song play by search, playlist navigation — but requires OAuth per user (Spotify's dev account limits to 25 users in extended quota mode). NOT built.

### UI Sound Assets
`AriaSounds` catalog (`TAP`/`TOGGLE`/`PAGE_FLIP`/`LOG_CONFIRM`/etc.) defined in code; `.wav` files in `res/raw` are a one-time asset task. Player no-ops on missing resource. Pending.

### Custom Fonts
`AriaType` defines `readout` (monospace/instrument), `neonLabel`, `stamp`, `logbookHeading`, `logbookBody` (serif). Custom font files in `res/font` not bundled yet — fall back to platform mono/serif. One-time asset task.

### Voice Sample Clips
`res/raw/voice_sample_<name>` for the voice picker's "tap to audition" feature. `tools/generate_voice_samples.ps1` exists but not run. Voice selection works; audition is a no-op.

### Local Model / On-Device STT
Long-term direction is a local LLM (Qwen or similar) on a hidden compute board (Jetson Orin / RK3588). Current Gemini Live cloud approach is the proof-of-concept. On-device STT bypass for trivial commands was considered but deferred — warm session is already near-instant, and local STT on AOSP head units is fragile.

### Room Debt (Future Migration)
`Vehicle` entity has three vestigial columns (`personaPrompt`, `personaTraits`, `voiceName`) that are never read at runtime (persona is in `CompanionProfile` SharedPrefs; the columns exist from v6→v7 migration). Dropping them requires a table-recreate migration (SQLite < API 35 has no DROP COLUMN). Deferred — doesn't affect runtime.

---

## Design Language

**Aesthetic:** retro-futuristic, "Stardew Valley philosophy" — charming, nostalgic, high-craft-within-constraints. Out-charm corporate, don't out-polish it. One person can do this; one person can't beat SoundHound/NIO on polish.

**Concrete:** late-Showa Japanese motoring culture. **City-pop anime** for living/character surfaces (avatar, driving accents, now-playing). **Aged instrument-catalog / JDM gauge-cluster** for data surfaces (logbook, maintenance, specs).

**Palette:** deep teal-charcoal background (`0xFF0D1418`), amber phosphor (`0xFFFFB02E`), neon magenta active (`0xFFFF2E97`), warm off-white text, aged paper (logbook), patina green (specs). Palette lives in `AriaColors` in `ui/theme/AriaTheme.kt`.

**Rules:**
1. ONE cohesive material, ruthlessly consistent
2. UI sounds on interactions, NOT on car actions
3. Satisfying ritual loops (greeting, logging, flipping logbook, odometer check-in)
4. **Driving mode = SPARE** (big amber readout, avatar, 1-2 glanceable things)
5. **Parked/garage mode = RICH** (the lush density lives here)

Art references at: `C:\Users\kevin\OneDrive\Pictures\UI Style References` (14 images, 1980s/90s Japanese city-pop anime, Hiroshi Nagai / Eizin Suzuki style)

---

## Architecture Key Constraints

1. **Spotify App Remote + Web API** — App Remote AAR (app/libs/) + PKCE Web API are both active. AVRCP is the no-Spotify fallback.
2. **Stadia Maps + MapLibre embedded nav** — NavEngine (geocode + routing) + CruiseMap. External Maps/Waze kept as user preference. Mapbox Nav SDK and Google Navigation SDK remain frozen.
3. **No iOS / CarPlay** — iOS direction dropped ($99/yr + entitlement maze). Android head unit only.
4. **No Calendar / OAuth** — Google Calendar was removed entirely (2026-06-28). No play-services-auth.
5. **No wake word** — PTT-only. Wake word ("Hey Moose") was removed (arm64 install break, sherpa removed). Floating button tap starts a hands-free conversation (server VAD, 10s-silence end).
6. **No mood/emotion engine** — removed (was causing speed-ups on emulator). Character lives in the system prompt only.
7. **Companion tied to app install**, not the OBD dongle. One companion, multiple cars' OBD data.
8. **Pull-based tools** — new domains default to tools/sub-agents, NOT pre-injected context blocks.
9. **Lean migrations** — copy generated `CarDatabase_Impl` SQL verbatim for every migration. No destructive fallback ever. Batch only when safe.

---

## Voice Tool Map (LiveToolbox)

| Tool | Action |
|---|---|
| `control_music` | play/pause/next/prev — Spotify Web API when connected, else MediaSession |
| `play_music` | search + play a named song/artist/album (Spotify) |
| `play_vibe` | play a mood/vibe playlist ("chill music") (Spotify) |
| `make_playlist` | create a Spotify playlist from named songs |
| `control_volume` | raise/lower/set/mute via `VolumeController` |
| `start_navigation` | opens Maps (Waze fallback) for a destination, routed via `SplitLauncher` so it lands in the split beside us |
| `stop_navigation` | clears the tracked nav destination (`NavState`) |
| `open_music` | opens a music app (default Spotify `com.spotify.music`, else first installed fallback) beside us via `SplitLauncher`; phone-BT streaming untouched |
| `show_app` | brings Moose to foreground |
| `get_vehicle_data` | live OBD single metric |
| `get_health` | OBD snapshot (battery/coolant/codes) |
| `get_trend` | obd_samples aggregation: coolant/rpm/voltage/load/fuel_trim/mpg over N days |
| `get_mpg` | measured MPG (MAF+GPS): current drive, lifetime, recent drives |
| `check_readiness` | live Mode-01-PID-01 emissions monitors (inspection readiness) |
| `check_cold_start` | → `ColdStartAgent` sub-agent over recorded cold-start bursts |
| `diagnose_codes` | → `DiagnosticAgent` sub-agent |
| `triage_symptom` | → `SymptomAgent` sub-agent |
| `ask_maintenance` | → `MaintenanceAgent` sub-agent |
| `lookup_vin` | OBD VIN read + vPIC decode (read-only) |
| `get_specs` | reads stored `VehicleSpec` |
| `check_recalls` | live NHTSA recalls API |
| `register_vehicle` | saves year/make/model, triggers interval lookup |
| `set_odometer` | direct odometer baseline update |
| `log_service` | → `logServiceDirect()`, normalises service name |
| `log_build_entry` | adds mod/repair/consumable to build sheet |
| `list_build_history` | read build entries |
| `get_spend` | total spend (passphrase-gated) |
| `unlock_spend` / `set_spend_passphrase` | spend gate management |
| `tag_place` | → `PlaceController.tagPlace()` |
| `forget_place` | → `PlaceController.forgetPlace()` |
| `set_reminder` | place-triggered reminder |
| `show_saved_places` / `hide_saved_places` | UI sheet toggle |
| `recall_memory` | keyword-grep over memory table |
| `remember` | stores a new memory |
| `restyle_background` | fire-and-forget wallpaper adjust |
| `restyle_avatar` | fire-and-forget avatar adjust |
| `add_car_task` / `complete_car_task` / `remove_car_task` / `list_car_tasks` | to-do list |

**Onboarding-only tools** (advertised by `LiveToolbox.onboardingDeclarations()` during first-run only, NOT in normal `declarations()`; dispatched by `ConversationalOnboardingScreen`, not `LiveToolbox.dispatch`):

| Tool | Action |
|---|---|
| `set_companion_name` | names the companion |
| `set_personality` | freeform persona string + avatar "look" descriptor |
| `set_driver` | driver name + about |
| `register_car` | year/make/model/trim |
| `finish_intro` | ends the spoken chat, advances to avatar/voice steps |

---

## Key File Map

```
app/src/main/java/com/kevin/aria/
├── service/
│   ├── AriaForegroundService.kt     — service lifecycle, proactive engine
│   ├── LiveSessionController.kt     — Gemini Live state machine, prewarm
│   ├── GeminiLiveSession.kt         — WebSocket STS, VAD, audio I/O
│   ├── LiveToolbox.kt               — all tool declarations + dispatch
│   ├── MooseOverlayController.kt    — floating avatar button (WindowManager)
│   ├── ProactiveBus.kt              — proactive speak requests
│   ├── TelephonyController.kt       — call-state awareness
│   ├── LauncherSettings.kt          — home-screen / escape-to-OS helpers
│   ├── NavPreferences.kt            — Maps vs Waze preference
│   └── DebugSettings.kt             — debug flags (subtitles, recall alerts)
├── ai/
│   ├── AriaBrain.kt                 — system instruction builder, memory recall
│   ├── SubAgent.kt                  — one-shot Gemini Flash REST worker
│   ├── CompanionProfile.kt          — app-global persona/voice/name SharedPrefs
│   ├── AvatarStudio.kt              — all image generation (avatars, wallpaper, car portrait)
│   ├── PersonaTraits.kt             — 5-stage picker, assemblePersona()
│   ├── OccasionStylist.kt           — seasonal outfit + time-of-day tint
│   ├── SpendGate.kt                 — passphrase gate for spend data
│   └── Voices.kt                    — 8 curated prebuilt voice descriptors
├── vehicle/
│   ├── VehicleController.kt         — register, odometer, logService, mileage, maintenance
│   ├── ObdBluetoothManager.kt       — ELM327 BT RFCOMM, connection loop, PID queries
│   ├── ObdResponseParser.kt         — all PID response parsing
│   ├── VinDecoder.kt                — NHTSA vPIC + recalls REST
│   ├── VehicleSpecController.kt     — VehicleSpec CRUD + refreshFromVin/Obd
│   ├── DiagnosticAgent.kt           — DTC sub-agent
│   ├── MaintenanceAgent.kt          — maintenance Q&A sub-agent
│   └── SymptomAgent.kt              — symptom triage sub-agent
├── media/
│   ├── MusicController.kt           — MediaSession transport (play/pause/next/prev)
│   ├── NowPlayingController.kt      — MediaSession listener, NowPlayingInfo StateFlow
│   └── VolumeController.kt          — STREAM_MUSIC volume control
├── location/
│   ├── LocationController.kt        — GPS location StateFlow
│   ├── PlaceController.kt           — tag/forget/currentLabel, DAO ops
│   └── ReminderController.kt        — place-triggered reminders
├── ui/
│   ├── MainActivity.kt              — foreground activity (Cruise → ControlPanel → Logbook)
│   ├── CruiseScreen.kt              — in-car ambient dashboard (the main screen)
│   ├── LogbookScreen.kt             — binder (LOG/TO-DO/MODS/SPECS tabs)
│   ├── ControlPanelScreen.kt        — settings list
│   ├── ConversationalOnboardingScreen.kt — spoken first-run setup (default)
│   ├── OnboardingScreen.kt          — typed first-run wizard (fallback)
│   ├── PersonaPicker.kt             — trait picker composable
│   └── theme/AriaTheme.kt           — design tokens (AriaColors, AriaType, AriaDimens)
├── data/local/
│   ├── CarDatabase.kt               — Room DB v11, all migrations
│   ├── Vehicle.kt                   — one physical car (OBD MAC keyed)
│   ├── BuildEntry.kt                — mod/service/repair ledger entry
│   ├── VehicleSpec.kt               — factory specs from VIN decode
│   └── [other entities + DAOs]
└── util/
    └── Dates.kt                     — shortDate() formatter (single source)
```

---

## Open Questions / Decisions for Planning

These are **unresolved** — good inputs for a planning session:

1. **Embedded navigation vs. delegate to Maps/Waze?**
   - Current: intent to Maps or Waze, AI can't control route/camera
   - Alternative: Mapbox Nav SDK embedded (free turn-by-turn, but ~$20-50k/month at 100k MAU)
   - Constraint: Google won't license embedded turn-by-turn to third parties (Navigation SDK is gated enterprise)
   - Kevin's stated desire: AI controls the map inside the app

2. **Spotify Web API vs. MediaSession only?**
   - Current: MediaSession/AVRCP (transparent, controls phone's Spotify, can't play specific songs reliably)
   - Web API: OAuth per user, queue/search/specific-song play, but Spotify limits to 25 extended-quota users during development
   - Question: is the MediaSession approach good enough, or does the product need song-level control?

3. **Photo diagnosis (Wave 3) — when?**
   - Strongest moat-widening feature not yet built
   - Self-contained: camera/gallery → Gemini vision + car grounding → "what's wrong with this"
   - No new infrastructure needed (AvatarStudio already does Gemini image I/O)

4. **Fuel tracking — when?**
   - MPG from OBD+GPS, fill-up log, cost/tank/trip
   - Needs its own Room migration
   - Natural Wave 2 remainder

5. **Export / build card — what format?**
   - PDF or shareable image of the build sheet
   - Distribution flywheel: enthusiasts share their build card at meets/on forums
   - No technical blocker; design question is what it looks like

6. **Hardware validation pass — how to structure?**
   - Nothing has been tested on a real head unit
   - ADB install + logcat triage for each subsystem
   - Should this be the next actual coding session before any new features?

7. **Local model roadmap — how close?**
   - Current: Gemini Live cloud (proof of concept)
   - Target: local LLM (Qwen 7-8B Q4) on hidden compute board
   - Blocking question: user supply of API keys is a product-killer; local = no-key, but ~$500 hardware add

8. **Phone "garage mode" — same app or separate?**
   - The app is already a stock Android APK (not head-unit-only code)
   - Recommendation on record: one codebase, two modes — in-car mode on head unit, garage/phone mode (full-screen, camera diagnosis, no overlay) on phone
   - Not built, not decided

---

## Business Context

- **Revenue model:** not decided. Subscription candidates: AI API costs (Gemini Live is pay-per-token), recalls/VIN service, future cloud sync.
- **Distribution:** currently side-loaded APK (not on Play Store). No Play Store listing yet.
- **Business proposal doc** exists at `~/Documents/ARIA_Business_Proposal_v1.1.docx` — $3k staged family-loan ask, BOM, cost-down, market research, SWOT.
- **Competitive moat summary:** SoundHound/NIO can't serve old/aftermarket cars (no OEM deal, no factory bus, out of warranty). Carly/FIXD have codes without soul. Nobody combines all four: conversation + OBD-grounded car intelligence + tasteful retro design + classic/enthusiast-car fit.
- **Kevin's cars:** 1998 Jeep Cherokee (the Moose default), plus BMW E-series. Both older, both candidates for an Android head unit.
