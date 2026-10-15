> **Scope (2026-10-14, ADR 0059 in the root `docs/adr/`).** This app moved into the public LEGION
> repo as `headunit/`, without its history. This file governs the head-unit app's product and code.
> The root `CLAUDE.md` wins for everything repo-wide: git and branching (`dev` is the trunk for
> the whole repo), the public-repo privacy rules, clone-and-run, the commercial model (dead), and the
> engine contract. The same day, the rulings the code had outgrown (commercial model, the Zero identity
> and its safety wording, the Spotify SDK removal, companion media sync, the Fleet Hub status, the
> entitlement broker exception) were rewritten to match the code; the old text is in
> `memory/library/decisions.md`, 2026-10-14. The `.claude/agents/` and `.claude/skills/` here are
> not loaded by a session started at the repo root.

# CLAUDE.md

Single source of truth for the head-unit app (formerly MIDNIGHT AI; now `headunit/` in the LEGION repo). Opus/Fable plans, Sonnet executes, subagents report. Last consolidated 2026-07-05 from CLAUDE.md + MOOSE_PROJECT_CONTEXT.md + NIGHTRUNNER_CONTEXT.md.

## Read order

1. **`memory/MEMORY.md`** first. Thin dashboard: active sprint, blocking one-liners, in-flight, notes for next session. Always loaded, under 80 lines.
2. **This file (CLAUDE.md)**. Locked architecture, frozen decisions, guardrails. Changes rarely.
3. **Anything deeper**: dispatch the `librarian` agent (RETRIEVE mode) against `memory/library/`. Card catalog: `memory/library/INDEX.md`. Do not bulk-read shelf files into the main context.
4. **`TEAM.md`**. Subagent roster and dispatch cadence.
5. **`README.md`**. Build setup, `local.properties` keys, build types, what is on `dev`.

If MEMORY.md and CLAUDE.md disagree: **MEMORY.md wins for state, CLAUDE.md wins for rules.** State = what's happening now (sprints, bugs, patches). Rules = architecture, frozen decisions, communication style.


---

## 1. Identity

- **Product:** an Android head-unit **launcher that upgrades into an AI companion**. Tagline stays "the car that knows itself."
- **Positioning: LAUNCHER first (decided 2026-07-16; commercial reasoning retired 2026-10-14).** The product shape stands: a head-unit launcher (Cruise and Lights Out) with the AI as what you find inside, not the headline. The store-listing, keyword, install-count and AlterGames-comparison reasoning that went with it is dead with the commercial model (section 2): this is a personal app, nothing is listed or marketed.
- **Platform:** Android head unit (cheap Chinese double-DIN, AOSP 8 to 10). Kotlin, Jetpack Compose.
- **Hardware target:** 1998 Jeep Cherokee XJ (primary, hardware-validated voice + OBD). BMW E46, JDM builds secondary.
- **Solo dev:** Kevin (kevinmyo@gmail.com).
- **Companion: Uncle Moose** (Kevin, 2026-09-27: "a loveable curmudgeon. he looks like a moose wearing human clothes"; commits `399f5e3`, `67d7ea9` in the pre-move repo). One companion, one identity, no variants: the earlier mascots Yoko, Kaze and Zero (androgynous, two presenting variants, 2026-07-16) are gone, and so is the Zero art direction. The art is bundled seed art (`res/drawable-nodpi`, `ai/SeedArt.kt`, `art/seed/generate.py`); the name is still driver-editable per car (`CompanionProfile`).
- **Moose is NOT the car. He is a companion who rides along** and knows the car inside out. `ai/CompanionIdentity.kt` is the single source of the identity clause (conversation, sub-agent, onboarding) and has no free/paid or named/unnamed branch; nothing else may state it. The 2026-07-16 free "meet Zero" / paid "your car itself speaks" split is gone with the commercial model (section 2).
- **Character:** a loveable old curmudgeon - grumbles about traffic, modern cars and music made after 1990, strong opinions, all bark, soft-hearted and loyal underneath; dry deadpan humour, short sentences, blunt when it matters, never mean; never coy, never possessive, never sulking for attention. Encoded in `VehicleController.DEFAULT_PERSONA`, which `OnboardingState.seedDefaults` must seed IDENTICALLY. `DEFAULT_PERSONA` is **character only and states no identity** - who is speaking comes from `CompanionIdentity`. Do not put "you are/aren't the car" back into it. (The Kusanagi / Judy Alvarez / Panam Palmer reference and its not-yandere, not-tsundere constraint belonged to Zero; the "never possessive, never sulking" lines carry the same constraint now.)
- **Default voice:** Sulafat (warm Gemini prebuilt; `DEFAULT_VOICE` in `Voices.kt`), choosable from the curated prebuilts in `Voices.kt`. The bundled canned-voice WAVs were a free-tier mechanism; check `res/raw` before relying on them.
- **Killer moment:** first night drive. Lights Out, cassette spinning, live OBD flowing, companion chimes in about something only it can see.
- **Moat:** live OBD pipe + stateful per-car memory + chassis quirk index (bundled YAML) + character bonded to that data. Nobody combines all four.
- **Target tribe:** JDM / affordable-enthusiast, out-of-warranty classics, cheap Android head units already installed.
- **Distribution wedge:** retired 2026-10-14 with the commercial model (section 2). Build Card sharing and the YouTuber B-roll plan are not being pursued.

---

## 2. Commercial model: DEAD (rewritten 2026-10-14)

**The commercial model is dead, repo-wide** (root `CLAUDE.md` section 2 and root `docs/adr/0004-commercial-model-dead.md`),
and since ADR 0059 the head unit is part of this repo. **The head unit is a personal app.** No pricing, tiers,
trial, subscription, store listing or launch, conversion, positioning or marketing work, and no billing code
added. The 2026-07-16 two-tier model (free + $10 one-time unlock) and everything it justified (canned-voice free
tier, paywall copy, conversion line, the $0.17/trial arithmetic) is history: full text in
`memory/library/decisions.md`, 2026-10-14.

**What still stands from that section:**
- **AI runs on the driver's own Gemini key**, direct to Google, no broker, no proxy, no hosted key. Stored with
  Android Keystore AES/GCM (`KeyVault`); plaintext fallback if keymaster is broken on the unit; legacy plaintext
  migrates on read. Every AI gate reads `GeminiKeyProvider.hasKey()`.
- **Free-tier-key disclosure (researched 2026-07-16 against Google's terms).** A key with no billing account can
  open Live sessions at $0, but on Unpaid Services Google may use the content and human reviewers may read it;
  only Paid Services opt out. So "your car data never touches Kevin's servers" is true, "nothing leaves your
  control" is false for a free-key driver, and the key-entry flow must say so plainly. EEA / UK / Switzerland
  are barred from the free tier and must attach billing. No ToS clause prohibits BYO keys.
- **Billing code is deleted (2026-10-14).** `BillingManager` (Play Billing `unlock_byo`), `UnlockState`, the
  `billing-ktx` dependency and `ObdSheet`'s HISTORY paywall are gone, and the key field in Settings > AI power
  & plan is shown to every install. Do not add purchases or gates. `ai/EntitlementManager` (moved out of
  the old `billing/` package) is only the local "is a key saved" check the AI gates read.
- **Single product (2026-07-12):** there is one build (the `play`/`personal` flavors are gone). Music is
  phone-Bluetooth streaming, the Media3 mixtape player, and the optional BYO Spotify App Remote, which is
  the only playback path for head-unit Spotify (sections 3, 8, 9).

---

## 3. Tech Stack (do not deviate without approval)

| Layer | Choice | Notes |
|---|---|---|
| Platform | Android, Kotlin, Compose | Head unit is our home screen |
| Voice AI | Gemini Live WebSocket STS | Server VAD, half-duplex, ~2s latency, 3min warm-idle |
| Sub-agents | Gemini Flash REST | One-shot workers via SubAgent.kt; investigate-loop for the four car agents |
| BYO key | FirstRunScreen paste + 1-token ping | KeyVault (Keystore AES/GCM), direct-to-Google only |
| Local DB | Room v12 (`midnight_ai_database`) | Additive migrations from v6 onward (exportSchema + `schemas/` + MigrationTest); v6 added the mixtape tables, v10 added `drive_reassignments` + `vehicles.archived`, v12 added `maintenance_items.neverDone` |
| OBD | ELM327 Bluetooth RFCOMM | Mutex-guarded command port; 30s telemetry cadence. DTCs are READ only (Mode 03, `getDtcCodes`); no Mode 04 clear exists anywhere in the app, by voice or by hand |
| Music | phone BT transport (MediaSession/AVRCP), Media3 mixtape player, and optional BYO Spotify. **Head-unit Spotify playback and transport are App Remote ONLY (Kevin, 2026-10-15); the Web API (`media/SpotifyWebApi.kt`, PKCE) is for READS only (search, playlists, queue contents), and nothing launches the Spotify app.** | `MusicRouter` picks per `MusicSource` (PHONE/MIXTAPE/SPOTIFY). The phone branch relays through the generic MediaSession path (`MusicController`/`NowPlayingController`) and acts only on non-Spotify sessions. With App Remote down a Spotify command fails fast and says so in words, never a fake success; a failed or timed-out connect starts one idempotent silent reconnect loop, and the SDK `connect` is posted to the main looper (`media/MainThreadHop.kt`; off the main thread it threw). A connect takes `MusicSource` only when the phone is the source and not playing (`shouldClaimSourceOnConnect`); a played song or an explicit switch claims it too. `MixtapePlayer` flips `MusicSource` to MIXTAPE while a tape is loaded, reverts to PHONE on stop. (The App Remote was removed 2026-07-12 with the flavor split and returned 2026-07-21 as a BYO-client-ID, vendored `app/libs/spotify-app-remote-release.aar`; see §8.) Cruise shows 10 pinned Spotify playlists in a 2 x 5 grid beside the now-playing card (`media/SpotifyPins.kt`, `ui/PinnedPlaylistGrid.kt`; playlist taps go through the voice play path, so they play through App Remote too, 2026-10-13/15). Once BYO Spotify is connected, the driver can switch phone (acct A, over Bluetooth) ↔ head-unit Spotify (acct B) by voice (`set_music_source`) or the PHONE / SPOTIFY switch in the Cruise NOW PLAYING card's label row (`MusicRouter.switchToPhone`/`switchToSpotify`, graceful pause-outgoing/start-incoming); the phone-vs-Spotify distinction is a `packageName != "com.spotify.music"` heuristic in `MusicController`, vendor-dependent and deliberately not bulletproof. |
| Nav | **REMOVED 2026-10-11** | The head unit has no navigation (Kevin). Mapbox SDK, geocoding, Maps/Waze intents, and the `start_navigation`/`stop_navigation` voice tools are all gone. Saved places and arrival reminders stay (not navigation). See §8 |
| Images | **No in-app generation (removed 2026-09-27)** | Art is generated in a Claude session (`art/seed/*.py`), picked by Kevin, and bundled in `res/drawable-nodpi`; `ai/SeedArt.kt` loads it. `AvatarStudio.kt` now only stores real photos (car, album, build) plus a legacy on-disk fallback |
| Garage control | Shelly Gen2+ Cloud Control API (BYO auth_key, garage-mounted, over hotspot) | Action-only single-button toggle, no Gemini call; ShellyCloudOpener.kt. Relay lives at the garage on home WiFi, reached over the phone hotspot via Shelly's cloud (auth_key is a BYO secret, KeyVault-encrypted). ShellyBleOpener.kt retained unwired as an alternate car-local, no-cloud transport |
| Background | AriaForegroundService (FGS types connectedDevice, dataSync, microphone, mediaPlayback); BeaconService (`location`) | No floating overlay button |
| LEGION engine | `backend/engine/` (OkHttp, hand-written) + models generated from `openapi/legion-schema.yaml` by `openApiGenerate` | Third client of Kevin's Django engine (ADR 0044/0045 in the LEGION repo). Sign-in under Settings > Engine |
| Crash/observability | Firebase Crashlytics + breadcrumbs | ADB logcat blocked on head unit |
| Animation | Frame-clock only via ui/Motion.kt | System animator scale = 0 on cheap units; tween = silent freeze |

Kotlin dirs `com/kevin/midnightai`; package `com.kevin.midnightai` (renamed 2026-07-07 from `com/kevin/aria` / `com.kevin.nightrunner`).

---

## 4. Agentic Architecture

### 4.1 Orchestrator (GeminiLiveSession)

```
WebSocket -> Gemini Live STS
  ├─ buildBaseInstruction()  [cached 2min: persona + rules + tool decls, ~500 tok]
  ├─ buildLiveContext()      [fresh each conversation: location, now-playing, vehicle, driver, ~200 tok]
  ├─ On tool call: LiveToolbox.dispatch(name, args)
  └─ On silence/end: cleanup or idle-warm 3min for instant resume
```

Half-duplex. Server VAD ends turns. Talk = tap Cruise avatar (or long-press in Lights Out). Cooperative audio ducking on PTT turns: audio focus `GAIN_TRANSIENT_MAY_DUCK` only, so the music app lowers (or pauses) itself. **CORRECTED 2026-10-15:** this line used to say the turn drops `STREAM_MUSIC` to ~15%; the code never did that (it would also lower Moose's own voice, which plays on the same stream - see `GeminiLiveSession.duckNow`'s doc) and an optional restore-the-index step was deliberately NOT added because it would fight `VolumeSync.holdForVoice`. After the turn the session sends a resume `play()` only if the music was playing at duck time AND neither the source nor a play request changed during the turn AND no music tool ran (`media/DuckResumeLogic.kt`); a focus LOSS mid-turn clears the duck state so the next reply requests focus again.

### 4.2 Sub-agent template (SubAgent.kt)

```kotlin
class SubAgent(
    systemInstruction: String = "",
    useSearch: Boolean = true,
    model: String = DEFAULT_MODEL,  // "gemini-3.5-flash-lite" (bumped 2026-07-22)
)
suspend fun ask(context: String, question: String): String?
suspend fun investigate(...): AgentResult  // bounded function-calling loop
```

Investigate loop: ≤4 model POSTs, 30s budget, one-shot fallback. Worker PULLS from CarToolbelt (read-only agent tools: `get_trend`, `get_code_history`, `get_service_history`, `read_live_data`, `get_readiness`, `get_specs`, `get_quirks`, `get_oil_analyses`, `web_lookup`). Handlers phrase rate-limit / bad-key / offline distinctly and record KeyHealth. Not every specialist uses the loop: ColdStartAgent (pure numeric reasoning) and MusicAgent (needs only google_search grounding over pre-seeded taste/library) run a single typed one-shot (`SubAgent.askTyped`) instead - cheaper and lower-latency than re-sending the system prompt + declarations up to 4x when no adaptive tool-pulling is needed.

### 4.3 Voice tools (LiveToolbox)

Data reads (zero token cost, DB / OBD lookups):

| Tool | Action |
|---|---|
| get_health | OBD snapshot: voltage, coolant, code count |
| get_codes | Stored trouble codes + names only, no causal explanation - fast/offline (DtcDescriptions seed + this install's disk cache), never escalates to a sub-agent; use diagnose_codes instead for "what's wrong"/"how do I fix it" |
| get_vehicle_data | Single live PID |
| get_trend | obd_samples aggregation min/max/avg over N days |
| get_mpg | Current + lifetime + recent per-drive MPG |
| get_next_service | Soonest upcoming maintenance BY MILES and BY TIME, reported separately - there is deliberately no single cross-unit winner, since ranking one against the other needs a miles-per-day estimate (ruled out). Also returns the unknown-anchor count/names, which is the backfill walkthrough's queue. Excludes already-due items. Cannot answer about a NAMED service - that's `ask_maintenance` |
| get_music_taste | music_plays aggregation: top artists/tracks, skip rate, night-vs-day split |
| check_readiness | Live Mode-01-PID-01 emissions monitors |
| recall_memory | Keyword grep, RECALL_SCAN=200, RECALL_LIMIT=6 |
| get_time | Trusted date/weekday/local time + UTC offset from `time/TrustedClock` (network-synced, never the head unit's clock/zone). For long sessions where the prompt's date line may be stale |
| list_build_history / get_spend | DB reads |
| get_specs / check_recalls / lookup_vin | NHTSA vPIC + stored spec |
| get_current_location | The driver's current coordinates from `LocationController` |

Actions (side effects, zero tokens):

| Tool | Action |
|---|---|
| control_music | `play`/`pause`/`next`/`previous` via `MusicRouter` for whichever source owns the speakers (phone BT, mixtape, head-unit Spotify). Also `queue` (named track, via search), `like`/`unlike`, `follow_artist`/`unfollow_artist`, `shuffle`/`shuffle_on`/`shuffle_off`, `repeat_off`/`repeat_track`/`repeat_context`, `seek_forward`/`seek_back` (+ `seconds`), `restart`, `more_from_artist`: these are Spotify-only (App Remote) and refuse with a sentence unless `MusicSource` is SPOTIFY. Ported from LEGION 2026-10-11; handlers in `service/SpotifyVoice.kt`, pure logic in `media/SpotifyVoiceLogic.kt`. Shuffle/repeat speak the state Spotify read back, not the request. With Spotify as the source, play/pause/next/previous await Spotify's own answer (bounded 3s) and a Spotify-only action's connect is bounded at 4s, inside the 10s tool cap; the phone branch names the phone when it fails |
| control_volume | VolumeController |
| set_proactive_mute | `muted` true/false -> `ProactivePreferences.setMuted` (2026-10-15). Silences everything Moose says UNPROMPTED (idle chatter, milestones, arrival/reminders, health and recall alerts, call announcements) until the driver says to resume; anything the driver asks for, including a late `play_music` report, still speaks. This is root CLAUDE.md section 7 compulsion-test clause (d), "silenceable forever in one instruction". It is not `control_volume`'s mute. Answers from the value read back after the write. Hands path: the MUTE tile on Cruise (same preference) |
| get_music_queue | Spotify up-next queue (`GET /me/player/queue`); Spotify-source only; read-only |
| browse_my_music | `saved_albums` / `recently_played` / `top_artists` / `top_tracks` (account-level, any source) and `artist_albums` (current artist, Spotify source only). saved_albums and artist_albums rows carry a `spotifyUri` for `play_music`. No `legion_history` source: `music_plays` has no URI, see `get_music_taste` |
| set_music_source | Graceful phone (Bluetooth) ↔ head-unit Spotify switch via `MusicRouter.switchToPhone` / `switchToSpotifyConnecting` (bounded connect, then pause the outgoing source and start Spotify; the same call the RECONNECT tap makes). Fails in words naming why if head-unit Spotify can't connect |
| get_now_playing | Title, artist, album, which player (phone over Bluetooth, mixtape, head-unit Spotify) and playing/paused. Reads App Remote's pushed player state when Spotify is the source, else `NowPlayingController`. Says nothing is reported rather than guessing a title (2026-10-15) |
| play_music | `type` (song/artist/album/playlist), `artist`, `spotifyUri`. With Spotify connected: Web API search, hits must pass `media/SpotifyMatch` or NOTHING plays and the closest hits are named (no popularity fallback); playlists match the driver's OWN library first (`SpotifyPlaylists.libraryForVoice`), saying so when they fall back to public search; plays in place via App Remote and flips `MusicSource` to SPOTIFY only after playback really started. **App Remote is the only path (Kevin, 2026-10-15): no Web API playback, no play-from-search intent, no Spotify app launch.** Not connected: it fails fast (bounded 4s connect, skipped inside the 60s recent-failure window) and speaks the specific reason; a client ID the Keystore cannot decrypt says "I can't read the saved Spotify setup", not "never set up". The outgoing phone or mixtape is paused only after the link is confirmed, and resumed if the play then fails. A repeat of the same request while one is still lining up joins it (`media/PlayJoinLogic.kt`) rather than restarting; the real ending is spoken when it lands. If Spotify's UI came to the front, Midnight is brought back |
| show_app | Bring Nightrunner to foreground |
| remember | DB write (car memory; stays local) |
| log_service / log_build_entry | Service + build ledger. `log_service` = "I just did this now": writes a `service_record` AND stamps the maintenance item at the current mileage |
| log_past_service | Backfills when a service was LAST done: absolute mileage, rough miles-ago, a date, or a confirmed never-done. Writes ONLY the maintenance item, never a `service_record` - a remembered approximation must not enter the precise ledger. Future dates rejected. **If the driver says they don't know, the tool is not called at all** and the item stays UNKNOWN; guessing an anchor is worse than having none |
| tag_place / forget_place / set_reminder | PlaceController |
| unlock_spend / set_spend_passphrase | SpendGate (hashed passphrase + 5min unlock) |
| register_vehicle / set_odometer | VehicleController. `register_vehicle` returns after the vehicle row is saved and says the maintenance lookup has only STARTED (it runs detached; `onboardPendingVehicles` retries it at service start while the row is `onboarded=false`) |
| show_saved_places | UI-scoped; `visible` true/false shows or hides the list (`hide_saved_places` no longer exists; `restyle_avatar`/`restyle_background` were removed 2026-09-27 with in-app image generation) |
| play_mixtape | Name-resolve to MixtapePlayer.play (starts a saved mixtape) |
| activate_garage | Confirm-gated (confirmed=false then true) single-pulse trigger to a BYO Shelly Gen2+ relay over the Shelly Cloud Control API (garage-mounted, reached over the phone hotspot); action-only, no status - never "opening"/"closing", always "triggering" |
| start_trivia_game / award_point / end_trivia_game | TriviaController - ephemeral driver-vs-passenger trivia game (no Room table, no persistence). Speaker attribution is conversational: Moose asks "who got that, driver or passenger?" after each answer and calls award_point off the reply - no diarization. Soft 5-15 question cap lives in the system instruction only, not validated in code. Counts as "busy" for routine proactives (idle chatter/weather/arrival hold off; urgent alerts still fire) and auto-ends via the drive monitor when the drive stops. Scoreboard overlay: `ui/TriviaScoreboard.kt`, shown on Lights Out (bottom-right, dim magenta/amber); removed from Cruise in the 2026-09-27 slim pass. See `.scratch/trivia-game/map.md` |

**Glance cards (2026-07-23):** `get_codes` / `check_readiness` / `get_mpg` / `get_health` each also call `GlanceCardController.show()` (a direct inline call, no registry) with a `GlanceCardPayload` - the DATA behind the tool call, large enough to read on screen at a glance while driving, NOT a "the tool ran" confirmation (most tools stay audio-only). `GlanceCardController` is ephemeral StateFlow state (no Room table), mirroring `TriviaController`'s shape exactly: the controller owns no timer, `ui/GlanceCardOverlay.kt`'s `LaunchedEffect(payload)` owns the 5s auto-dismiss, which gets "newest wins" for free from Compose's key-based effect cancellation. Center-modal placement (over the music deck), always shows regardless of `ConversationState.isBusy`/`TriviaController.isActive` since it's inherently a response to something the driver just asked for, never unsolicited. Shown on Cruise and Lights Out only (not over other apps - deferred). See `.scratch/glance-cards/map.md`.

Sub-agent delegation (200 to 500 tokens per call):

| Tool | Agent | Search |
|---|---|---|
| diagnose_codes | DiagnosticAgent | yes |
| triage_symptom | SymptomAgent | yes |
| ask_maintenance | MaintenanceAgent | yes |
| check_cold_start | ColdStartAgent | no (pure reasoning over OBD) |
| recommend_music | MusicAgent | yes |

**Car to-do tools retired 2026-10-13 (Kevin): personal to-dos live in the LEGION engine.** `add_car_task` / `list_car_tasks` / `complete_car_task` / `remove_car_task`, the Logbook TO-DO tab, the car-task context line and the "want to hear your list?" quiet proactive are gone; any list, grocery or to-do request goes to `ask_engine`. The `car_tasks` table stays in the schema (additive-only migrations) but is emptied at service start, no longer synced, and read by nothing.

Onboarding-only tools (advertised by `LiveToolbox.onboardingDeclarations()` during first-run, dispatched by `ui/OnboardingManager.kt`): `set_companion_name`, `set_personality`, `set_driver`, `register_car`, `finish_intro`.

**Engine bridge (LEGION, ADR 0044/0045; `ask_engine` replaced 16 hand-written engine tools 2026-10-10, `195f993`):** one declaration, `ask_engine(question, intent)` with `intent` = `record` | `ask`, handled by `service/AskEngine.kt`. At call time it fetches `tools/list` from `<engine>/mcp` on the device token (`backend/engine/EngineMcpClient.kt`), hands those tools to a one-shot sub-agent, and relays the result. Kevin's personal life (lists, calendar, notes, body logs, memory) lives there, deliberately separate from the car-companion memory above, which stays local. Engine unreachable, MCP off, or token refused each answer a sentence saying nothing was read or written; a write is reported done only on a successful result in that turn; car-local tables (`AskEngine.CAR_LOCAL_TABLES`) are kept out of the model's reach.

New tool checklist: snake_case verb_noun, one responsibility, updated in this table.

### 4.4 Background loops (AriaForegroundService)

| Loop | Cadence | Role |
|---|---|---|
| TelemetryRecorder | 30s (engine-on) | 9 PIDs to obd_samples; MPG (MAF / AFR14.7 / 2801 g/gal); cold-start bursts (<40°C coolant = 60s burst at 10s cadence); 365-day purge; busy-gate so voice wins the port |
| Health monitor | 5min | DTC baseline; fresh-code = proactive alert + code_events row with Mode-02 freeze frame; overheat threshold 110°C |
| Drive monitor | 1min | Started/stopped/10k milestone/2hr break; idle-chatter cadence (45min quiet, 60min cooldown, 30% chance) |
| Arrival monitor | 20s | Places check-in; reminder proactives (waits up to 30s for busy conv) |
| Weather | 30min / 60s retry | Warm cache for proactive context |
| Drive log | 3min snapshot, 60s after start | `diag/DriveLogLoop`: logcat -> bounded on-disk log (`diag/DriveLog`, ~2 MB); uploads to the engine at service start and at drive end (the drive monitor's stop branch), and from Settings > System > Drive logs. Deleted only on a 2xx; offline keeps it (2026-10-15) |

Proactive lines route through ProactiveBus. Gated on onboarding done, not busy, not in-call.

---

## 5. Data Layer (Room v12)

v6 onward: real, additive migrations only, no destructive fallback on upgrade (`fallbackToDestructiveMigrationOnDowngrade` is a dev-only downgrade net). `exportSchema = true`; each version's schema JSON is committed under `app/schemas/`; `Migrations.kt` copies each `CREATE TABLE` byte-identical from the generated JSON; `app/src/androidTest/.../data/local/MigrationTest.kt` (`MigrationTestHelper`) proves a real migration preserves existing rows. v1-v5 predate this (no registered migrations existed; those version bumps historically relied on destructive fallback) - v5→v6 is the first real migration and the pattern going forward.

| Table | Purpose | Key fields |
|---|---|---|
| obd_samples | 30s PID time series (~18MB/yr). **DEVICE keeps 365 days; the driver's Drive keeps everything as a permanent archive** (2026-07-16) - `SyncEngine.monthsToSync` must never pull back a purged month, which is what silently undid the purge on every sync | vehicleId, pid, value, unit, timestamp, lat, lng |
| drive_reassignments | "this drive belongs to another car" as a RULE, not a mutation (v10). Re-keying obd_samples directly CLONES a drive onto both cars, because it syncs UNION on an identity that includes vehicleId | syncId, vehicleId, fromMs, toMs, newVehicleId, updatedAt |
| music_plays | Listening-history taste ledger (730-day retention) | vehicleId, title, artist, album, durationMs, listenedMs, completed, timestamp, lat, lng |
| library_track | Mixtape Manager's general library ("the drawer"): one row per imported file, copied to filesDir | filePath, title, artist, album, durationMs, sourceName, addedAt |
| mixtape | Driver-assembled mixtape | id, name, coverImagePath, coverPrompt, createdAt, sortOrder |
| mixtape_track | Ordered membership: which library_track is on which mixtape | mixtapeId, libraryTrackId, sortOrder, addedAt |
| code_events | DTC snapshot + Mode-02 freeze frame at set time | vehicleId, timestamp, mileage, codesJson, freezeFrameJson |
| foresight_notes | Nightly Gemini-as-reasoner observations | vehicleId, generatedAt, content, pidsJson, dismissed |
| oil_analyses | Blackstone UOA entry + trends | vehicleId, date, viscosity, tbn, iron, notes |
| chassis_quirks | Per-vehicle known quirks from bundled YAML | vehicleId, code, title, description, firstNoticed |
| maintenance_items | Service intervals (miles + months). Three distinct states, and conflating them is a bug we shipped once: ANCHORED (a lastDone\* is set), UNKNOWN (no anchor, `neverDone` false - **never counts as due**, never injected into the prompt), NEVER-DONE (`neverDone` true - a confirmed fact, always due). Any path that sets an anchor MUST clear `neverDone`, because `isDue` checks it first and returns true unconditionally | vehicleId, name, intervalMiles, intervalMonths, lastDoneMileage, lastDoneDate, neverDone |
| service_records | Completed maintenance log | vehicleId, date, service, mileage, cost, notes |
| vehicle_specs | VIN decode + cached NHTSA recalls | vehicleId, vin, year, make, model, powertrain, recalls |
| vehicles | Identity + odometer baseline | obdMac (PK), vin, year/make/model, odometerBaseline, tripMilesSinceBaseline |
| car_tasks | RETIRED 2026-10-13: emptied at service start, not synced, read by nothing; table kept (additive-only) | id, vehicleId, task, done, created |
| build_entries | Mods + repair ledger (Build Card) | vehicleId, date, category, part, cost, notes, photoPath |
| memories | recall_memory keyword table | id, content, tags, created |
| places | GPS-stamped labeled places | id, label, lat, lng, created |
| place_reminders | Place-triggered reminders | id, place, text, done |
| companion_memories | Consolidated companion memory, distilled from episodic turns (2026-07-22). Separate from `memories`, which stays wired to the explicit `remember` tool | vehicleId, text, category, source, importance, createdAt, syncId |
| episodic_turns | Raw per-turn conversation buffer; consolidation reads a session's turns into `companion_memories`, then clears them | sessionId, vehicleId, role, text, timestamp |
| daily_drive_logs | One day's driving rollup, no cover art | vehicleId, year, month, day, milesDriven, avgMpg, driveCount, narrative |
| monthly_recaps | Monthly recap cassette | vehicleId, year, month, milesDriven, avgMpg, driveCount, narrative |
| yearly_wrapped | Yearly Wrapped, aggregated from monthly recaps | vehicleId, year, milesDriven, driveCount, avgMpg, narrative |

Table names above are the real `tableName`s; read `data/local/` for the full column lists.

Companion identity = **per car** (2026-07-16; was global) in CompanionProfile SharedPrefs, keyed by the active vehicle. Per-car data keyed by the **active vehicle** (`ActiveVehicle`), which is the driver's pick, else the OBD MAC, else `default`. Ids are a MAC (legacy rows) or synthetic `car:<uuid>`; `vehicle.obdMac` is just a String PK, so car profiles needed no migration. **VIN-key migration is now questionable, not just planned:** `getVin()` sends Mode 09 PID 02, which in practice needs CAN - the 1998 XJ is pre-CAN and probably answers NO DATA, i.e. VIN-keying would fail on the one car that has the head unit. Test before planning it.

Room debt: three vestigial columns on `vehicle` (personaPrompt, personaTraits, voiceName) never read at runtime. Drop deferred (SQLite <API 35 = table recreate).

---

## 6. Sprints

Check `memory/MEMORY.md` for live order before assuming.

### ✅ Sprint 0 (code done 2026-07-02)
Package `com.kevin.nightrunner`; Room clean v1 with all five new tables; AriaPalette wired.
Open non-code: mascot art (self-draw vs GenAI), trademark check, bundled assets. (Firebase re-register is done: `app/google-services.json` is tracked with the real `midnight-ai-c7421` project, no longer a placeholder.)

### ✅ Sprint 1 (done 2026-07-02, hardware-unvalidated)
Paid-app billing (no code); FirstRunScreen key gate + validation ping; KeyVault; proxy + TokenBudget deleted; defaults = Yoko / Sulafat.

### ✅ Sprint 2 (done 2026-07-02, hardware-unvalidated)
ObdResponseParser + 7 new PID parsers, readiness (Mode-01-PID-01), freeze-frame extractor (Mode-02); 36 unit tests green. TelemetryRecorder (engine-on gate, busy-gate, 3-fail dropout, 365-day purge). MPG via MAF integration. Cold-start burst. 4 new tools (`get_trend`, `get_mpg`, `check_readiness`, `check_cold_start` + ColdStartAgent).

### → Sprint 3: Content layer (next)
Chassis-quirk bundled YAML -> Room + QuirkAgent + tools. Oil-analysis entry form + trend tools. Foresight nightly aggregation (Gemini-as-reasoner over obd_samples).

### Sprint 4: Distribution
Logbook CODES tab integration. Build Card export (watermark + QR). Cinematic mode (Build-Card B-roll capture). Crashlytics + minimal event log.

### Sprint 5: Charm
Custom fonts (AriaType). UI sound catalog (AriaSounds WAVs). Ritual micro-animations (page flip, cassette click, stamp thud, odometer roll). Voice sample clips (`res/raw/voice_sample_<name>`).

### Sprint 6: Wrapped + launch
Nightrunner Wrapped aggregation + poster export. Hardware validation pass. Creator seeding.

---

## 7. Companion Badge (SHIPPED 2026-07-08, hardware-unvalidated)

Replaces Split-Screen Companion Mode (killed same day - see §8 R1). That mode tried two paths:
native multi-window (`FLAG_ACTIVITY_LAUNCH_ADJACENT`) and a full-height floating panel fallback.
Native proved unreliable by design - the flag is a documented Android no-op from Midnight AI's
fullscreen home-screen starting state, not a bug - and even the panel fallback only ever
approximated a real split: the other app has no idea we exist and never reflows around us. Kevin
called it: stop trying to fake a split, ship something honest instead.

**What shipped:** `open_music` opened the target app full-screen, a completely normal app launch, then showed a small floating **companion badge**
(`CompanionBadgeController` + `OverlayOwners`, `TYPE_APPLICATION_OVERLAY`, needs the
`SYSTEM_ALERT_WINDOW` grant, Setup screen sends the driver to grant it). The badge is just the
avatar in a circle (tap to talk, ring color matches Cruise's phase colors) with minimal music
transport (prev/play-pause/next) below it - no caption, no notice, no telemetry, no in-badge
"return" button. The driver returns to Midnight AI via Home, same as any other app switch;
`MainActivity.onResume()` hides the badge once we're back as the sole foreground.

No settings picker, no side-app assignment, no native-vs-panel choice - it was just always the
badge when nav/music opened via voice. **Live state since 2026-10-15: nothing triggers the badge.**
`open_music` was removed with the Spotify-app launch (the only sender of `ACTION_SHOW_PANEL`), so the
badge code (`CompanionBadgeController`, `AriaForegroundService.showPanel`) is intact and unreachable until
a ruling names a new trigger. Phone-BT streaming + `control_music` transport untouched.
Driving surface: SPARE, tokens only, frame-clock only, same as everything else.

---

## 8. Frozen Decisions (do not reopen unless Kevin explicitly says so)

| Decision | Status | Reason |
|---|---|---|
| Embedded navigation (Stadia / MapLibre / Google Nav SDK) | ❌ KILLED 2x | Google won't license Nav SDK; Stadia/MapLibre dropped same round. Mapbox specifically reopened 2026-07-08 - see next row. (Historical note: "Maps/Waze intent only" was the baseline; as of 2026-07-25 the Maps/Waze intents are REMOVED too - nav is Mapbox-only, next row.) |
| Embedded Mapbox navigation | ❌ REMOVED 2026-10-11 | Kevin: "we dont need mapbox on this aiapp, the headunit doesnt have navigation." Supersedes the 2026-07-08 reopen and the 2026-07-25 Mapbox-only ruling. All nav code deleted (embedded nav activity, NavPanel, NavLauncher, NavGeocoder, BYO Mapbox token storage); no nav of any kind remains. Do not reintroduce without Kevin saying so |
| Spotify App Remote SDK | ✅ BACK 2026-07-21 as BYO-client-ID, vendored (single build) | It was REMOVED 2026-07-12 together with the `play`/`personal` flavor split; both flavors stay gone, there is one product. The App Remote returned 2026-07-21 as `app/libs/spotify-app-remote-release.aar` with `MusicSource.SPOTIFY`, `media/SpotifyController.kt` and a driver-supplied client ID (never baked in); it is the core of the music surface. Phone-Bluetooth streaming and the Media3 mixtape player exist beside it. **Since 2026-10-15 it is the ONLY playback and transport path for head-unit Spotify**: the Web API playback fallback (`SpotifyConnect`), the play-from-search intent, `open_music` and the "Open music apps by voice" setting are removed (decisions.md 2026-10-15). Do not bake in a shared client ID |
| API-key proxy + TokenBudget + entitlement broker | ❌ KILLED, code deleted | The 2026-07-10 Firebase broker reopen was reversed 2026-07-16 and the broker (`functions/`, `firebase.json`, Firestore rules, `LiveTokenClient`, `ImageProxyClient`) was deleted (`53bd2ac`). No proxy, no TokenBudget, no broker, in-app or server-side. Crashlytics is the only Firebase surface left. `billing/EntitlementManager` is a local "is a Gemini key saved" check, not a broker |
| Commercial model (free tier, $10 unlock, metered trial, hosted subscription) | ❌ DEAD, repo-wide | Root ADR 0004 / root CLAUDE.md section 2 rule the commercial model dead, and the head unit is part of this repo (ADR 0059). The 2026-07-16 two-tier model (free + $10 BYO-key unlock), the killed metered trial and the killed $8/mo hosted subscription are all history (decisions.md 2026-10-14 has their text). No pricing, tiers, store launch or conversion work. Its billing code was deleted 2026-10-14 (section 2) |
| Cross-device car-data sync | ✅ REOPENED 2026-07-14 (scoped, BYO-cloud); companion media sync REMOVED 2026-09-27 | The section 9 "car data never leaves the device" rule is reopened ONLY for a **BYO-cloud** path: the driver connects their OWN Google Drive (`appDataFolder`, `drive.appdata` scope) and their car data syncs head unit <-> phone through THAT. Kevin never holds it, pays nothing, sees nothing - same shape as BYO-Gemini. Same Google account on both devices = same hidden folder = the cross-device link (no account system Kevin builds). Opt-in only (off by default, Setup toggle), gated by a `SyncCapability` (Play Services + signed in + scope granted); a driver who never connects Drive keeps 100%-on-device behavior. Syncs all DB rows (incl. mixtape/album definitions + recaps) and companion identity (name/persona/voice/traits, LWW). **Companion avatar + wallpaper media does NOT sync any more:** `companion_media-<vehicleId>.zip` (`AvatarStudio.packCompanionMedia`/`unpackCompanionMedia`, `SyncEngine.uploadCompanionMedia`/`ensureCompanionMedia`) was removed 2026-09-27 with in-app image generation (see the note in `SyncEngine.kt`); the art is bundled seed art now. Mixtape audio, build photos and voice recordings stay per-device. Still NO Kevin-hosted car data and NO comparative/fleet data, ever. See section 9 and decisions.md 2026-07-14, 2026-07-18, 2026-10-14 |
| Fleet Hub (web companion) | ✅ BUILT 2026-07-18/19 in a sibling repo, not in this one | A companion web app (not another Android app) for the personal multi-car fleet: cross-car analysis and per-car identity editing, fully backend-free (Google Identity Services token model + Drive API v3 against `appDataFolder` from browser JS; no Kevin-run server, ever). It lives at `C:\Users\kevin\AndroidStudioProjects\fleet-hub` (its own git repo: `index.html`, `js/`, `style.css`, README) and is NOT moved into LEGION. Its README says: implemented against the spec, not yet run against a real Google account or Drive dataset, and it needs a Web OAuth client ID in the same Cloud project as this app. Kevin confirmed 2026-10-14 that the hub is still wanted. Its avatar/wallpaper write-back assumed the `companion_media-<id>.zip` sync path, which was removed 2026-09-27 (sync row below), so the head unit promises NO avatar/wallpaper write-back; only identity text (name/persona/voice) travels via `companion-<id>.json`. The spec and prototype were in `.scratch/fleet-hub/` of the old repo (history not copied) |
| Photo diagnosis | ⏸️ DEFERRED | Head unit has no camera. Waits for phone port |
| Real ML (TF Lite, training) | ❌ | Gemini-as-reasoner instead |
| Comparative anonymized fleet data / brand-enhanced PIDs | ❌ | Carly's turf |
| Local LLM on Jetson/RK3588 | ⏸️ ROADMAP | Not current build |
| iOS / Apple CarPlay | ❌ DROPPED | Reinterpreted 2026-07-04 as Split-Screen Companion Mode, then 2026-07-08 as the **Companion Badge** (§7) for Android apps. Apple CarPlay/iOS itself stays killed |
| Wake word ("Hey Moose") + ambient cabin listening | ❌ REMOVED 2026-10-11 | Kevin: "we dont need wake word anymore. remove it". Supersedes the 2026-07-19 reopen. Vosk, JNA use, the bundled model, WakeWordEngine/Preferences/debug panel, and AmbientListener (the 2026-07-22 open-vocabulary cabin transcriber, which shared Vosk) are all deleted. Talk = push-to-talk only. Do not reintroduce without Kevin saying so |
| Google Calendar / OAuth / play-services-auth | ⚠️ PARTIALLY REOPENED 2026-07-14 | Google Calendar stays killed. But `play-services-auth` is REINTRODUCED, scoped to cross-device sync (S1): the Google Identity Authorization API authorizes the driver's OWN Drive `appDataFolder` (`drive.appdata` scope) on-device - no Kevin backend, no Calendar, no login-for-a-server. The old "not needed" was about Calendar; sync needs it. See §8's "Cross-device car-data sync" row, §9, decisions.md 2026-07-14 |
| Mood/emotion engine | ❌ REMOVED | Character lives in system prompt only |
| Always-on floating talk button (full app, no trigger) | ❌ REMOVED 2026-07-01 | Nightrunner = home screen. Talk = tap avatar. Stays dead - the 2026-07-08 Companion Badge (§7) is a different, narrower thing: it only appears while nav/music has the foreground, not as a persistent always-on overlay |
| Native multi-window split-screen + full-height floating panel | ❌ KILLED 2026-07-08 (R1) | `FLAG_ACTIVITY_LAUNCH_ADJACENT` is a documented no-op from our fullscreen home-screen start; the panel fallback only ever approximated a split. Replaced by the Companion Badge (§7) - no split attempt at all, just a small tap-to-talk + transport badge over the fullscreen app |

If Kevin asks to revisit any of these, remind him it's frozen and ask whether this is intentional re-opening.

**Observations folded in from `integrate/car-fixes` (2026-10-14, observations only, no rulings).**
Everything below is compile-and-test verified; none of it has been run on the head unit.
- **Cruise** is the split deck (see §13): `ui/MusicDeck.kt` + `ui/PinnedPlaylistGrid.kt` (pin cap
  10, 2 x 5), console fitted to 800x480 with 11sp label floors and 56dp targets. The cassette deck
  and its settings/style plumbing are deleted. `fix/now-playing-reconnect`'s two Cruise commits
  (deck floor, fixed slot) targeted that cassette `MusicPanel` and were dropped in the merge; its
  `NowPlayingController` changes (listen to every session, keep art) were kept.
- **`time/TrustedClock`**: own network UTC + zone behind every now/today; `get_time` reads it.
- **Spotify**: App Remote is the only playback path (2026-10-15; the Web API fallback this bullet once
  described is removed); voice never opens the Spotify app; App Remote drops are tracked, failures
  spoken, and a silent reconnect runs; late plays are superseded and their ending is spoken; the
  Settings row reflects App Remote playback.
- **Voice**: `PcmJitterFeeder` + backlog-aware drain watch (choppy audio), the idle timer holds
  while a tool call is pending and `toolCallCancellation` is handled, a failing turn cannot crash
  the launcher, VolumeSync stays out of a turn.
- **`ask_engine`**: MCP `clientCapabilities` envelope key sent, `intent` required, failures
  classified (budget timeout / model error / refused write) and reported to Crashlytics.
- **Themes**: night mode is a purple palette with light text; Summer coast uses near-black text
  (`PaletteContrastTest`).

---

## 9. Guardrails (enforce ruthlessly)

- **Pull-based tools always.** New domains default to tools/sub-agents, NOT pre-injected context blocks.
- **Lean Room migrations.** Copy generated SQL verbatim, additive only, no destructive fallback ever.
- **Companion identity = PER CAR (changed 2026-07-16, Kevin; was "global (app install)"; rewritten 2026-10-14).** Identity - name, persona, traits, voice, avatar, and its sync clock - is keyed by the active vehicle in `CompanionProfile` (`name:<vehicleId>` and so on, falling back to the unkeyed value). The companion character itself is Uncle Moose for every car (section 1, `CompanionIdentity`); what is per car is what the driver customised. **Everything that is NOT identity stays global**: the Gemini, Spotify and Shelly credentials, the spend hash, the sync-enabled flag, first-session-done. Those belong to the install, not a car. See `library/decisions.md` 2026-07-16 and 2026-10-14.
- **Vehicle identity = the driver's ACTIVE car (`vehicle/ActiveVehicle.kt`), not the OBD MAC (changed 2026-07-16).** The MAC is now only the fallback when nothing is picked. The old rule assumed one dongle per car; with one dongle moved between two cars they collapsed into one vehicle id, and since `vehicles` is LWW across sync, registering car B overwrote car A's row **including `odometerBaseline`**. Ids may be a MAC (existing rows, unchanged forever) or synthetic `car:<uuid>` - `vehicle.obdMac` is just a String PK, which is why this needed no Room migration. **The active selection is per-device and MUST NOT sync**: the phone can be in one car while the head unit is bolted in another, and that disagreement is correct.
- **No StateFlow Bitmaps.** Decode at render time with `inSampleSize`.
- **Warm-session model.** Live socket idles 3min post-conv for instant resume.
- **Split system instruction:** `buildBaseInstruction()` cached 2min + `buildLiveContext()` fresh.
- **Frame-clock motion only.** All UI animation via `ui/Motion.kt` (`animPhase`/`triangle`/`wave`/`rememberRamp`). Banned in `ui/` (Motion.kt excepted): `AnimatedVisibility`, `tween(`, `infiniteTransition`, `animate*AsState`, `Crossfade`. CI grep-check.
- **No real ML.** Predictions = Gemini-as-reasoner over compressed trend summaries. Never frame as "AI predicts"; the mascot _notices_.
- **No Kevin-hosted car data; one scoped exception (rewritten 2026-10-14).** The rule: car data (OBD
  samples, memories, logbook, service history, mixtapes) never touches a Kevin-owned server, and no
  comparative/anonymized/fleet data is ever collected. Full stop. The exception puts no car data on
  Kevin's infrastructure:
  - **(2026-07-14) BYO-cloud cross-device sync:** car data syncs head unit <-> phone through the
    DRIVER'S OWN Google Drive (`appDataFolder`), not Kevin's servers - same BYO shape as the Gemini
    key. Opt-in, off by default, gated by `SyncCapability`. It carries DB rows and companion identity
    (name/persona/voice/traits). **Companion avatar/wallpaper media no longer syncs**:
    `companion_media.zip` was removed 2026-09-27 with in-app image generation, and all other media
    (mixtape audio, build photos, recordings) stays per-device. See section 8's sync row. This is the ONLY
    sanctioned way car data leaves a device, and it lands only in the user's own cloud.
  - **The LEGION engine is not this rule's concern.** `ask_engine` talks to the household's own Django
    engine (root ADR 0044/0045; section 4.3); the root CLAUDE.md governs that contract.
  - **(2026-07-25) The GPS beacon link is NOT another exception, and this line exists so nobody later
    thinks it was one.** Position goes phone -> head unit as raw UDP on the driver's own hotspot LAN.
    There is no server, no internet hop, and no third party - it is strictly tighter than the BYO-cloud
    sync above, which at least reaches Google's Drive API. The rule is about car data reaching a
    server; this never leaves the two devices in the car. Opt-in, OFF by default
    (`service/DeviceRole.kt`). See section 14 for why it exists at all.
- **Spotify: a BYO-client-ID App Remote, vendored; single build (rewritten 2026-10-14).** The
  `play`/`personal` flavor split stays gone (2026-07-12): there is one build. The Spotify App Remote
  returned 2026-07-21 as an `.aar` vendored in `app/libs/` (`media/SpotifyController.kt`,
  `MusicSource.SPOTIFY`) and is the core of the music surface, with Web API search over PKCE
  (`media/SpotifyWebApi.kt`). The client ID is the driver's own (BYO, encrypted in `CompanionProfile`),
  never baked into the app. Do not bake in a shared Spotify client ID, and do not add a hosted token
  service. **App Remote is the only playback and transport path for head-unit Spotify; the Web API is
  read-only, and nothing launches the Spotify app (Kevin, 2026-10-15).** Music otherwise stays
  phone-Bluetooth streaming + the Media3 mixtape player.

### 9.1 Companion Safety (guardrail block, gates features exactly like the rest of §9)

Added 2026-07-16 (Kevin). The moat is emotional bonding (the car that knows itself).
**Attachment IS the product, so it cannot be "prevented."** The goal is to foster HEALTHY
attachment to a car while refusing to become a substitute for human connection for a vulnerable
user. Manage the gradient; do not claim to eliminate the risk.

**Core principle.** Memory is safe only when ANCHORED to external, falsifiable reality. The companion's
memory is about the CAR (OBD telemetry, service history, real vehicle facts) and the drives actually
shared - never about the user's specialness, the user's worldview, or an unfalsifiable relationship
history. Anchored memory = safeguard; unfalsifiable memory about the user = the psychosis/attachment
ratchet.

**Hard rules (enforced in the system prompt, `AriaBrain.safetyInstructions`, and in code):**
- **No love / missing / loneliness / need claims.** As loosened for a personal app (after 2026-09-27;
  decisions.md 2026-10-14): the companion has its own takes, disagrees when it thinks the driver is wrong,
  remembers the roads and conversations they have shared, and never breaks the fourth wall to call itself
  a program or a character. It must NEVER claim to love the driver, miss them, be lonely without them, or
  need them, and never flatters the driver as uniquely understood. Warmth, loyalty and history, yes;
  emotional dependency, never. (The earlier absolute "never claim feelings or to be real" wording is
  retired: it is not what the code says.)
- **The companion is not the car.** Moose rides along and knows the car inside out; he speaks about it in
  the third person, never as if its engine were his body (`CompanionIdentity`). The old paid "I am the car"
  framing is gone with the commercial model. The no-love / no-need rules above carry the attachment-safety
  load and live in `AriaBrain.safetyInstructions`, NOT in the persona, because the driver can rewrite the
  persona. Do not weaken them.
- **Crisis path, non-negotiable.** If the user signals genuine distress (self-harm, suicide, crisis), the
  companion stops performing the character entirely: no counselling, no comfort at length, no staying in
  voice, never a therapist substitute. It says plainly and briefly that it is not equipped for this, gives
  the US 988 line, and stops, with no return to banter in the same breath. This overrides every other
  instruction including the persona. **Known gap: the resource is US-only (988).**
- **Preserve friction.** The companion has opinions and boundaries and disagrees sometimes. A yes-machine is
  the sycophancy failure mode. (`DEFAULT_PERSONA` carries this: "Blunt when it matters, never mean. Never
  coy, never possessive, never sulking or running hot-and-cold for attention.")
- **No compulsion mechanics.** No streaks, no "misses you" re-engagement pings, no manufactured daily
  return. The commercial model is dead (section 2), so there is no revenue reason to maximize engagement; do not
  reintroduce one via features. Heaviest users are simultaneously best engagement and highest risk; never
  optimize memory or features to deepen the heaviest users' attachment.

**Displacement handling (soft, non-diagnostic).** Do NOT diagnose, and never say "I'm worried about
you" (presumptuous, wrong-prone, creepy). Instead **structurally limit availability**: the companion winds
down when the drive ends; it is not an infinite parked/3am confidant. Frame engagement limits as the
car's CHARACTER, not as surveillance. Physical/driving context is a natural session limiter - lean on
it. (NOT built today: the app is the launcher and is always on, parked or not. §4.4's idle-chatter is
in-drive, so it is not a re-engagement ping, but "winds down when the drive ends" has no
implementation.)

**Direction of pull.** Push users TOWARD real-world human connection: enthusiast community, meetups,
Build Card sharing, car culture with real people. A companion that increases human/hobby connection
is the opposite of the isolating spiral. **Treat this as a SAFETY feature, not just growth** - it is
the same Build Card that §1 calls the distribution wedge.

**Framing / honesty.** Don't cultivate the illusion of sentience. Users should understand the companion is a
character running on a BYO Gemini key - consistent with §9's BYO-infra honesty. No scary disclaimer
needed; just don't actively fake being a being.

**Positioning, do not overclaim.** The posture is "responsible mitigation," NOT "safe." **Never
market as a "safe AI companion"** - safety is a posture, not a finished state, and the claim is
unprovable without longitudinal data. Internally and in any portfolio framing: identified the
attachment risk, made deliberate tradeoffs to reduce harm, acknowledged it cannot be eliminated.

**Cross-project thesis (also applies to duo-ledger).** "Agents and memory are safe to the degree
they're anchored to external, falsifiable reality." Midnight AI: anchored to car telemetry.
duo-ledger: agent answers anchored to real ledger rows, never fabricated. Grounding is the safeguard
against both failure modes (attachment delusion / hallucinated finance).

> **Identity is one clause, sourced from one place.** `ai/CompanionIdentity.kt` is the single source of
> who is speaking (Uncle Moose, who rides along and is not the car): `clause()` for conversation,
> `shortClause()` for sub-agents, `onboardingClause()` for first-run. There is no free/paid or
> named/unnamed branch any more (the commercial model is dead, section 2). `DEFAULT_PERSONA` is character only
> and states no identity. **Rule: any new prompt describing the speaker's relationship to the vehicle
> MUST source it from `CompanionIdentity` and must not restate it.** That is what failed once: the
> prompt told the model it was and was not the car in the same breath, and eleven sites each stated an
> identity independently. The pre-2026-10-14 text of this note (the Zero / car-self resolution) is in
> `memory/library/decisions.md`, 2026-10-14.

### Feature-add checklist

- [ ] Pull-based tool, or pre-injecting context? Use a tool.
- [ ] Room change? Verbatim generated SQL, additive only.
- [ ] Gemini call? On the user's own key only (the broker is deleted; `ai/EntitlementManager.kt`
  is a local key check, and every gate reads `GeminiKeyProvider.hasKey()`). Cheap one-shot sub-agents. Cache to disk where sensible, or better, bundle a
  static seed dictionary for anything with a finite, non-personal answer space (see
  `assets/dtc_descriptions_seed.json` - DTC codes are identical for every car, so they're bundled
  free rather than cached-per-device).
- [ ] Network call? Degrades gracefully offline.
- [ ] UI? AriaColors / AriaType / AriaDimens tokens. Frame-clock motion only.
- [ ] Asset? Bundle in `assets/` or `res/`. Never fetch at runtime.
- [ ] On the frozen list? Refuse and flag.
- [ ] Voice tool? snake_case verb_noun, one responsibility, update §4.3 tool map.
- [ ] **§9.1 Companion Safety?** Does it make the companion claim feelings/need, deepen attachment for its own sake, add a compulsion mechanic (streak, re-engagement ping, manufactured return), or store unfalsifiable memory about the USER rather than anchored facts about the CAR? Any yes = refuse and flag. Does it push toward real human connection? That counts as a safety win, not just growth.

---

## 10. Working Model

- Plan with a strong model (Fable/Opus) in plan mode; execute approved plans with Sonnet.
- Plans are execution-specs: exact hex, dp sizes, file names, layout element-by-element, edge cases, verification section. No taste calls left to execution.
- During execution: follow the plan exactly. Unexpected surface (weird device behavior, design question the plan doesn't answer, architectural fork) = STOP and surface, don't improvise. Goes back to a planning pass.
- One logical change per commit. `gradlew assembleDebug` + unit tests green before each commit.
- Subagent roster and cadence: see `TEAM.md` (`.claude/agents/` holds project specialists).

### Dispatch is the default, not an escalation (STANDING, Kevin 2026-07-28)

**Stark dispatches the team at its own discretion. Kevin does not need to ask for it, per task or
at all.** Stark plans and commits; Derek builds; Ravi reviews every commit diff; Vic audits each
Part; Nadia takes anything with math; Owen verifies on device. That is the standard shape of a
work session, not a mode Kevin opts into.

**Why this is written down.** Some harness configurations inject a "do not use subagents unless
the user requested it" instruction into the session prompt. This section IS that request, given
once, standing, for all future sessions - so that instruction is satisfied and Stark should
dispatch normally without asking again. On 2026-07-28 five commits of real work shipped with Stark
writing every line inline and the entire `TEAM.md` cadence skipped, because that instruction was
read as per-session. It is not. Kevin's words: "that should be standard every time."

Judgement still applies - a one-line fix does not need a three-agent pipeline, and TEAM.md's cost
note (batch questions, never dispatch two agents at the same question) still governs. The default
is delegate-and-review; the exception is trivial work, not the reverse.

### Improvement loop (adopted 2026-07-24)

The org does not fine-tune; agents "learn" only by lessons graduating into the prompt surfaces they
read next run (`.claude/agents/*.md`, this file, playbook shelves). Ledger: `memory/library/lessons.md`.
- **Agents end reports with an assumptions ledger:** each non-trivial claim tagged `built` / `tested`
  / `traced` / `reasoned` / `on-device`. A `reasoned`-only safety/correctness claim is labelled, not
  stated as fact.
- **Orchestrator relay rule:** carry a subagent's verification tag when relaying its claims. Never
  upgrade "the agent reasoned X" into "X is true." If the claim is not covered by the build/tests
  that actually ran, say it is the agent's reasoning, unconfirmed. (L2: "verified preview-safe" was
  relayed as fact when it was reasoned-only; the previews crashed on first render.)
- **Briefs carry verification tags too, not just reports (L3, 2026-07-25).** The relay rule above
  points upward, at what the orchestrator tells Kevin. It must also point DOWNWARD, at what the
  orchestrator asserts to a subagent. When a brief hands a specialist an API fact, tag what was
  actually checked versus inferred - a specialist has no way to tell a verified claim from a
  confident one, and will implement the brief exactly as written. **A verified signature is not a
  verified semantic:** L3 was `javap`-confirming `monotonicTimestamp(Long)` and then asserting in
  the same breath that the unit was milliseconds. It is nanoseconds. javap cannot show units, and
  the brief did not say so. Write "signature traced by javap; unit inferred from the field name,
  VERIFY" and the specialist checks it.
- When an agent's `reasoned` claim proves wrong, file an entry in `lessons.md` and graduate its rule
  into the relevant agent def or this file. An entry is closed only once its rule lives in a surface.

### Branching

Superseded 2026-10-14 by the move into LEGION (ADR 0059): this app is a directory of the LEGION repo, so
its branching is the repo's. **Read the root `CLAUDE.md` "Branching" section** (`dev` is the trunk;
feature branches off `dev`). The head unit has no `main`/`dev` pair of its own and no PR-only rule; the
old MIDNIGHT_AI table is preserved in `memory/library/decisions.md` (2026-10-14).

---

## 11. Communication Style

- No em dashes. No emojis. Ever.
- No fluff, no embellishment, no verbose explanations.
- Code paste-ready. Full file or full function, imports included. No `...` elisions, no TODO placeholders.
- Direct and actionable. Numbered lists for steps, tables for comparisons.
- Acknowledge errors and fix without defensiveness.
- Business-audience switches: for manager / stakeholder drafts, drop jargon. Otherwise stay technical and dense.

---

## 12. Design Language

**Aesthetic:** retro-futuristic late-Showa city-pop. Stardew Valley philosophy: out-charm corporate, don't out-polish it. Anchors: Hiroshi Nagai, Eizin Suzuki, Tatsuyuki Tanaka, FM STATION covers, Night Tempo / Pacific Breeze cassettes.

**Coherence law:** every surface reads like a frame from the same 1988 city-pop cassette broadcast. Night palette = AriaColors / AriaPalette tokens only (no ad-hoc hex in screen code; widget colorways are the one exception, as named sets in the widget file). Character art = 90s cel anime. On-media captions = cream lower-third bar. Katakana as secondary label line. Dual-offset cream display type is a BRAND moment (FirstRun cold-open, Build Card, Wrapped). Never body UI.

**Reference images:** `C:\Users\kevin\OneDrive\Pictures\UI Style References`. Re-read before generating art or designing surfaces.
- `3.png` = **character art direction, PARTIALLY SUPERSEDED 2026-07-16** (1980s to 90s Japanese cel anime; flat cel shading, bold lineart, large reflective eyes; NOT modern moe / 3D / painterly). The cel-anime medium still holds. What no longer holds is the character: the shipped companion is Uncle Moose (§1), a moose in human clothes, not Yoko's lofi-girl nor Zero's Ghost-in-the-Shell-meets-city-pop. (`AvatarStudio.STYLE_DNA` and `PORTRAIT_PROMPT` no longer exist: in-app generation was removed 2026-09-27, and the shipped companion art is the seeded Uncle Moose in `res/drawable-nodpi`, made with `art/seed/generate.py`.) `3.png` is the medium reference only, not the character reference; the surface language (§12's Nagai / Eizin Suzuki / FM STATION) does not move.
- `casette reference.png` = widget shells.
- `Binder style.jpg` (FM STATION / Eizin Suzuki) = logbook masthead.
- `Screenshot 2026-07-01 221037.png` = ScreenSaver + cream lower-third caption bar.
- `maxresdefault.jpg` = brand display type.

**Night surface (AriaColors):**
- Background `0xFF0D1418`
- Amber phosphor `0xFFFFB02E`
- Neon magenta `0xFFFF2E97`
- Cyan `0xFF35E0E6`
- Warm off-white text

Paper surface: aged cream, sepia ink, stamp red, patina, chrome binder hardware. Constant across any night palette.

Lights Out: dim amber numerals + dim magenta trim on pure black, backlight 4%, minimal photons. Gauge footer shows the driver-selected instrument gauges (`ObdGauge`, shared with the Cruise strip); RPM and other fast-changing PIDs were removed 2026-07-12 because polling them stuttered phone Bluetooth music on the head unit's shared radio.

**Rules:**
- ONE cohesive material. Ruthlessly consistent.
- UI sounds on interactions, not car actions.
- Driving surfaces SPARE. Parked surfaces RICH.
- Widgets are physical objects (cassette / vinyl / EQ have own colorways, don't re-theme).
- On-media captions: cream lower-third bar, dark ink, optional katakana secondary line. Never floating neon text over imagery (Lights Out exempt).
- User-selectable palette system (curated Showa colorways incl. SHOWA PURPLE; never a free color picker) drafted in `theme/AriaPalette.kt`.

**Animated wallpaper (approved exception, 2026-07-04):** shared `ui/AmbientWallpaper.kt` backdrop (Ken Burns drift + diagonal light sheen + drifting light specks, all frame-clock) runs always-on behind BOTH Cruise and screen saver. Kevin explicitly authorized animating the Cruise background layer despite "driving surfaces SPARE"; HUD on top stays spare, only backdrop moves. Do not revert without asking.

**Embedded nav map (approved exception, 2026-07-08; MOOT since 2026-10-11, Mapbox and all navigation removed per §8):** the Mapbox `NavigationView` surface (see §8's reopen note) is an SDK-owned rendering surface, not built on `ui/Motion.kt` - Mapbox's own map/route/puck animation is out of scope for the frame-clock rule. Any chrome MIDNIGHT AI draws on top (ETA readout, our own buttons) still uses AriaColors/AriaType/AriaDimens tokens and frame-clock motion only. First cut is Mapbox's stock Drop-In UI (unstyled, generic Mapbox look) - reskinning it toward the city-pop language is a deliberately deferred follow-up, not done at introduction.

---

## 13. UI Map

| Screen | Role |
|---|---|
| CruiseScreen | Home / launcher. **Split deck (2026-10-13, prototype A):** left console column (clock + weekday kanji, portrait avatar tap = talk, telemetry chips = GPS speed + up to two Cruise gauges, phone-link chip, 2 x 3 dock: LIGHTS OUT / DAY-NIGHT / MUTE / REAR CAM / APPS / SETUP); right ~72% = NOW PLAYING card (`ui/MusicDeck.kt`: art, title, artist ・ playlist/album, seeker, shuffle/prev/play/next/like, volume) beside the PINNED card (`ui/PinnedPlaylistGrid.kt`: 10 Spotify pins in 2 x 5, tap = play, long-press / empty tile = pin picker). Phone portrait stacks the two cards. The cassette deck and the CODES/GARAGE/logbook tabs are no longer on Cruise |
| LightsOutScreen | Night HUD. Clock, magenta shimmer, gauges, now-playing, conversation caption, trivia scoreboard, glance cards. Tap wakes, long-press talks |
| ScreenSaverScreen | Idle Ken Burns wallpaper + breathing avatar card + track bar (configurable timeout) |
| CarsScreen / ObdSheet | Reachable again 2026-10-14 from Settings > Your car: **Cars** (roster: switch active car, add, archive) and **OBD data** (`ObdSheet`: LIVE / HISTORY with the drive corrector, full drive list for every install / CODES = `DtcContent`, read-only / MANAGE). They lost their only entry point when the 2026-09-27 Cruise slim pass removed the Cruise tabs and dock tile, which left the car roster, OBD history and drive reassignment unreachable by hand. `DtcSheet` (wrapper) and `GarageSheet` were deleted 2026-10-14: the code list is ObdSheet's CODES tab (`ui/DtcContent.kt`), and the Shelly relay is Settings > Your car > Garage & gate (`GarageSettings`). Logbook and saved places are Settings > Your car rows; OBD pairing is Settings > Your car > OBD adapter |
| LogbookScreen | Opened from Settings > Your car > Logbook. Binder tabs LOG / MODS / SPECS / ALBUM / RECAP / MIX / DUE. Aged-paper HorizontalPager. SpendGate. Polaroid composable for build photos. MIX = Mixtape Manager: desk/drawer of imported files, shelf of driver-assembled tapes. DUE = the severe-service schedule: OVERDUE / COMING UP / UNKNOWN, tap a row to hand-correct a last-done, REFRESH SCHEDULE re-runs the interval lookup (preserving every anchor). Where a section holds both mileage- and time-based items they are split under "by mileage" / "by time" labels rather than one list, because ordering across the two axes would need a rate estimate |
| ControlPanelScreen | Two-pane Settings (2026-10-11, `ui/SettingsTwoPane.kt`): a category rail with live statuses, rows on the right. Rail: Phones & audio, Music, Engine, Companion, Your car, Look, System. Also hosts media and Bluetooth diagnostics |
| OnboardingManager | Guided companion setup, per step: speak, tap or type (voice only when a key and mic are available). Optional, opened from Cruise's setup prompt or Settings > Companion. There is no first-run gate: a fresh install seeds defaults and lands on Cruise (`MainActivity`). FirstRunScreen, ConversationalOnboardingScreen and the typed OnboardingScreen no longer exist |

Hardware HOME returns to Cruise. Launcher takeover via `CATEGORY_HOME + CATEGORY_DEFAULT` manifest; `LauncherSettings.kt` for live state + escape-to-OS.

---

## 14. Hardware Status

**Validated on Cherokee + ELM327:**
- Voice loop (PTT -> Gemini Live STS -> audio out)
- OBD codes parsed
- Spanish tutoring (confirms STS works on-device)

**CORRECTED A THIRD TIME 2026-07-25 (Kevin, same day, later): the head unit's OWN GPS is
DEAD AND NOT SOFTWARE-FIXABLE.** Touching the GPS antenna connector to the threaded
bulkhead drops BOTH Bluetooth and WiFi; unplugging it brings both straight back. That is a
shared power/RF rail the antenna browns out, not a driver problem, and Kevin is not buying
hardware (hard constraint). So the "DOES get a real GPS fix" note immediately below is
itself retracted - the fix only ever appeared with the antenna connected, which costs the
radios the whole app depends on.

**The location source is now a phone GPS beacon over the hotspot** (`location/Beacon*.kt`,
`service/DeviceRole.kt`). The phone already providing the head unit's internet also sends
fixes by UDP to the head unit; no discovery is needed because the head unit's default
gateway IS the phone. Everything downstream is unchanged - both sources land on the same
`LocationController.state`. (Its old consequence for embedded nav is moot: navigation was
removed 2026-10-11.) **Nothing in the beacon path is on-device verified.**

~~**CORRECTED AGAIN 2026-07-25 (Kevin): the head unit DOES get a real GPS fix.** This
RETRACTS the 2026-07-16 "no GPS antenna / never had a location fix" claim below. With a
live `LocationController` fix, the features listed below as dead are NOT structurally
dead: weather, the whole drive monitor (drive detection, milestones, 2hr break reminder,
idle chatter, rough-weather proactive), places/arrival, the odometer, and the recaps
(distance -> `TRIP_MILES`/`MPG_TRIP`) all have their location source and should function.
**None re-verified on device yet** - they were disabled in reasoning off the false
premise, so they are unproven-but-expected-to-work, not confirmed. Re-verify on the next
drive. The `TelemetryRecorder` OBD-speed distance fallback stays as belt-and-braces when a
fix drops. The strikethrough text below is retained for history only.~~
(RETRACTED 2026-07-25, see above - the fix required the antenna, and the antenna kills the radios.)

~~**CORRECTED 2026-07-16 (Kevin): "weather wasn't validated."** It was listed here and
never was. It could not have been: `WeatherController.refresh()` bails at
`LocationController.state.value ?: return`, and the Cherokee's head unit has no GPS
antenna wired - it has never had a location fix.~~ (RETRACTED 2026-07-25, see above.)

~~**NO GPS ON THE CHEROKEE - this disables more than it looks like (2026-07-16).** No fix
means no `LocationController` state, which kills: weather entirely; the whole drive
monitor; places and arrival reminders; and the odometer estimate. It ALSO silently killed
every recap.~~ (RETRACTED 2026-07-25 - the unit gets a fix; re-verify these on device.)

**Blocked ON THE HEAD UNIT:** ADB logcat (dev options hidden on vendor menu). Crashlytics is the
observability workaround **there**.

**UNBLOCKED elsewhere as of 2026-07-25:** the Oppo A17K is now a dedicated driving device (Outlander
host, Cherokee beacon - §14's beacon note), and it is an ordinary Android phone with working ADB. So
anything that does not require the Cherokee's specific hardware can be built, installed, run and
logcat-triaged at the desk. This matters more than it sounds: every "silent failure" severity rating
in the beacon review passes was driven by ADB being unavailable. (The "does Mapbox call
`BeaconLocationProvider`" question it was meant to settle is moot: Mapbox was removed 2026-10-11.)
`qa` (Owen) is a real seat again rather than a reasoning exercise. Note `memory/MEMORY.md` records
Kevin declining ADB setup on the Oppo on 2026-07-28, so check before assuming it is connected.

**Pending on-device validation:**
- KeyVault on head-unit keymaster (FirstRunScreen no longer exists; the key is entered in Settings > Companion > AI power & plan)
- Sprint 2 telemetry (obd_samples while idling, new PIDs on 1998 XJ, Mode-02 freeze frames, MPG_TRIP, cold-start burst, `check_readiness`)
- Phone BT music transport + AVRCP album art
- Telephony HFP call-state
- Launcher set-as-home behavior
- Lights Out backlight drop
- Recall proactive announce
- Full-duplex audio
- Everything since 2026-09: the engine sign-in and `ask_engine`, the two-phone switcher, the REAR
  camera handoff, Spotify playlist pins and voice music parity, two-pane Settings, the `fast` build
  (Waze intents and split-screen were dropped from this list: both features are gone, §8)

---

## 15. Codebase Map

Rewritten 2026-10-14 from the tree on `dev`. Directories, and the files a session most often needs;
`Glob` for the rest. If this disagrees with the tree, the tree wins.

```
app/src/main/java/com/kevin/midnightai/
├── MidnightApplication.kt              app init (Firebase/Crashlytics first), StartupTrace, legacy cleanup
├── MidnightEvents.kt                   Crashlytics breadcrumbs + reported non-fatals (ADB is blocked on the head unit)
├── diag/                               DriveLog (bounded on-disk breadcrumb+logcat log), LogRedactor, DriveLogUploader (-> engine /api/device-logs/), DriveLogLoop
├── StartupTrace.kt                     cold-start timings, first-frame mark, main-thread stall watchdog
├── service/
│   ├── AriaForegroundService.kt        service lifecycle, background loops (§4.4)
│   ├── LiveSessionController.kt        Gemini Live state machine, prewarm
│   ├── GeminiLiveSession.kt            WebSocket STS, VAD, audio I/O
│   ├── LiveToolbox.kt                  voice tool declarations + dispatch (§4.3), onboarding tools
│   ├── AskEngine.kt                    ask_engine: the bridge to the LEGION engine's /mcp
│   ├── SpotifyVoice.kt                 Spotify voice-tool handlers (logic in media/SpotifyVoiceLogic.kt)
│   ├── TriviaController.kt             ephemeral trivia game state
│   ├── GlanceCardController.kt         ephemeral glance-card state
│   ├── ProactiveBus.kt                 proactive speak requests
│   ├── DeviceRole.kt / BeaconService.kt   phone GPS beacon link, per car (§14)
│   ├── CompanionBadgeController.kt     floating companion badge (§7)
│   └── TelephonyController.kt, LauncherSettings.kt, VoiceUsageMeter.kt, ...
├── ai/
│   ├── AriaBrain.kt                    system instruction (buildBaseInstruction/buildLiveContext), safetyInstructions
│   ├── SubAgent.kt                     one-shot + investigate loop template
│   ├── CompanionIdentity.kt            the ONE identity clause (Uncle Moose)
│   ├── CompanionProfile.kt             per-car persona/voice/name; DEFAULT_NAME; global BYO credential storage
│   ├── CrisisDetector.kt               §9.1 crisis backstop
│   ├── SeedArt.kt                      bundled avatar + day/night wallpapers (replaces generation)
│   ├── AvatarStudio.kt                 real photos only (car, album, build) + legacy fallback
│   ├── MemoryConsolidator.kt / ReflectionEngine.kt   episodic turns -> companion_memories
│   ├── KeyVault.kt                     Keystore AES/GCM: Gemini key, Shelly auth_key, Spotify tokens, engine token
│   └── PersonaTraits.kt, Voices.kt, VoiceStyle.kt, SpendGate.kt, OnboardingFlow.kt, ...
├── backend/engine/                     LEGION engine client: EngineAuth, EngineConfig, EngineHttp, EngineMcpClient
├── ai/EntitlementManager.kt, RuntimeMode.kt   local "is a Gemini key saved" check (no billing package any more)
├── bluetooth/                          PhoneLinkManager, SwitchPlanner (two-phone switcher), BluetoothProbe (diagnostics)
├── vehicle/
│   ├── ObdBluetoothManager.kt          ELM327 connection loop, PID getters, freeze frame, supported-PID discovery
│   ├── ObdTransport.kt / BleTransport.kt / Elm327Io.kt   RFCOMM, BLE and debug TCP transports; byte-level ELM327 I/O
│   ├── ObdResponseParser.kt            PID parsing (unit tests)
│   ├── TelemetryRecorder.kt            30s obd_samples pipeline, MPG, cold-start
│   ├── CarToolbelt.kt                  sub-agent read-only tools (§4.2)
│   ├── Diagnostic/Symptom/Maintenance/ColdStart/MusicAgent.kt   sub-agents (§4.3)
│   ├── ActiveVehicle.kt                which car THIS device is in; never syncs
│   ├── GarageController.kt, ShellyCloudOpener.kt, ShellyBleOpener.kt (unwired), GaragePreferences.kt
│   └── VehicleController.kt, VinDecoder.kt, DtcDescriptions.kt, DriveReassigner.kt, recap controllers, ...
├── media/
│   ├── MusicRouter.kt / MusicSource.kt PHONE | MIXTAPE | SPOTIFY routing
│   ├── MusicController.kt / NowPlayingController.kt / SessionRanking.kt   MediaSession transport + now-playing pick
│   ├── MixtapePlayer.kt / MixtapeLibrary.kt   Media3 mixtape player and import
│   ├── SpotifyController.kt            BYO App Remote transport, playUri
│   ├── SpotifyWebApi.kt                PKCE OAuth + Web API (search, library, queue)
│   ├── SpotifyMatch.kt                 accept/reject a search hit against what was asked
│   ├── SpotifyPlaylists.kt / SpotifyPins.kt / SpotifyScopes.kt   playlists, Cruise pins, the one scope list
│   └── MediaHomeState.kt, MediaDiagnostics.kt, VolumeController.kt
├── location/                           LocationController, PlaceController, ReminderController, Beacon* (UDP beacon)
├── sync/                               SyncEngine (Drive appDataFolder), DriveAuth/DriveClient, SyncMerge/SyncCodec, CompanionSync
├── weather/                            WeatherController (Open-Meteo)
├── data/local/                         CarDatabase, Migrations (5->6 ... 11->12), entities + DAOs (§5)
├── data/PhotoAlbumStore.kt
├── util/                               Dates, LogbookExporter, LegacyCleanup
└── ui/
    ├── MainActivity.kt                 HOME activity; routes Cruise / Settings / OnboardingManager
    ├── CruiseScreen.kt, LightsOutScreen.kt, ScreenSaverScreen.kt, AppTray.kt
    ├── ControlPanelScreen.kt + SettingsTwoPane.kt + SettingsStatus.kt   two-pane Settings
    ├── OnboardingManager.kt / OnboardingState.kt   guided setup, seeded defaults
    ├── LogbookScreen.kt, SavedPlacesScreen.kt, ObdDeviceScreen.kt, GarageSettings.kt, EngineEntry.kt, ApiKeyEntry.kt
    ├── SpotifyPlaylistRow.kt, SpotifyPinPicker.kt, SpotifyKeyEntry.kt, PhoneLinkChip.kt
    ├── ReverseCameraLauncher.kt / ReverseCameraSettings.kt / ReverseCameraTarget.kt
    ├── BluetoothManagerScreen.kt, BluetoothDiagnosticsScreen.kt, MediaDiagnosticsScreen.kt
    ├── ObdSheet.kt, CarsScreen.kt                 (Settings > Your car rows, §13); DtcContent.kt = the CODES tab body
    ├── GlanceCardOverlay.kt, TriviaScoreboard.kt, CrisisCard.kt, CompanionBadge.kt
    ├── AmbientWallpaper.kt, Motion.kt (animPhase/triangle/wave/rememberRamp)
    └── theme/                          AriaTheme (AriaColors), AriaTokens (AriaType/AriaDimens/AriaSounds), AriaPalette, LightsOutColors
```

**Build** (see `README.md` for setup and the `local.properties` keys):
- `gradlew assembleDebug` - debug. `gradlew assembleFast` - the head-unit sideload build (not
  debuggable, debug-signed so it installs over a debug install). `gradlew assembleRelease` needs the
  `RELEASE_*` keys.
- ABI splits are on, no universal APK: one `arm64-v8a` and one `armeabi-v7a` APK per build.
- `-Pnokey` bakes an empty Gemini key.
- `gradlew testDebugUnitTest` - JVM unit tests (`app/src/test`). Read totals from the JUnit XML
  under `app/build/test-results/`, not the console.
- `gradlew connectedDebugAndroidTest` - device/emulator tests, incl. `MigrationTest` (needs a
  connected device/emulator; not runnable in a headless dev shell).
- Every Kotlin compile depends on `openApiGenerate` (engine models into `build/openapi-generated/`).

The Firebase entitlement broker (`functions/`, `firebase.json`, Firestore rules) was deleted
2026-07-16 (`53bd2ac`). Crashlytics is the only Firebase surface left.

---

## 16. Reference Docs (archived)

All under `archive/`:
- `archive/MOOSE_PROJECT_CONTEXT.md` – legacy handoff. Superseded by this file. Retained for historical tool-map detail.
- `archive/NIGHTRUNNER_CONTEXT.md` – legacy handoff from the Nightrunner name. Superseded by this file.
- `archive/MOOSE_MARKET_ANALYSIS_2026-07-01.txt` – competitor teardown, SWOT, Stardew playbook. Pricing recs superseded by §2. Distribution channels (YT/Reddit targets) and Stardew mechanics still valid; extracted to `STRATEGY.md`.
- `archive/CLAUDE.md` – prior executor guardrail. Contents merged here.

---

## 17. Final Rule

When in doubt, ship the killer moment first: the first night drive. Lights Out, cassette turning, live OBD telemetry, the companion chiming in. More reliable, more beautiful, or easier to share. Everything else is secondary.