# Backlog: Music

> **STATUS: FROZEN ARCHIVE (banner added 2026-08-01).** This shelf is Midnight AI history: a
> head-unit car launcher with a commercial model and a city-pop design language. All three of
> those premises died in the 2026-07-30/31 pivot to LEGION. Nothing below governs LEGION.
> Read it as reference for why something was built the way it was. **Do not act on its
> blockers, sprints, backlog items, or hardware notes.** Live rules are in CLAUDE.md; live
> state is in memory/MEMORY.md. See CLAUDE.md §11.


Music discovery, recommendation, and mixtape management items. Maintained by the librarian.

## Personalized-music arc Phase 2: recommend_music + play_mixtape voice tools (M3, 2026-07-08)

Code done, tests green, not yet device-verified.

- **M3** — Music recommender via web search (new sub-agent)
  - `vehicle/MusicAgent.kt` (new): SubAgent with useSearch=true, default gemini-3.1-flash-lite
    model. Recommends 1-2 NEW music discoveries via web search, grounded in driver's listening
    taste (`CarToolbelt.musicTasteSummary`) and saved music on the head unit (`CarToolbelt
    .savedMusicSummary`). Never recommends something already saved. Can offer a saved mixtape
    by name when it fits.
  - `CarToolbelt.kt` additions: `savedMusicSummary` formatter, new `formatSavedMusic(tracks, tapes)`
    pure helper (no DB, pure testing-friendly), two new AgentTool factories (`get_music_taste`,
    `list_saved_music`), and a `forMusic` belt = [get_music_taste, list_saved_music, web_lookup].
  - `LiveToolbox.kt` additions: two new voice tools — `recommend_music` (delegates to MusicAgent,
    clone of ask_maintenance delegation pattern) and `play_mixtape` (action tool: resolves a
    spoken tape name to `MixtapePlayer.play(context, mixtapeId)` via exact/prefix/substring match,
    run on Dispatchers.Main). End-to-end: Live orchestrator chains play_mixtape after the driver
    accepts a recommend_music offer in the same conversational turn.
  - New unit test `SavedMusicSummaryTest.kt` (5 tests, all passing) on the pure `formatSavedMusic`.
  - CLAUDE.md updated: recommend_music/MusicAgent added to sub-agent delegation table (§4.3),
    play_mixtape added to actions table, MusicAgent.kt added to codebase map (§15).
  - Build verified: `gradlew assembleDebug` + `gradlew testDebugUnitTest` both green.

**Device-verify checklist:**
  - Ask for a recommendation with taste history + saved tapes present.
  - Confirm it offers a named saved tape.
  - Say yes, confirm play_mixtape starts it and MusicSource flips to MIXTAPE.
  - Verify the graceful <8-plays / nothing-saved sentence works on a fresh install.

**Committed:** "Add recommend_music + play_mixtape voice tools" (2026-07-08).

## Music tier architecture: four control paths (Phase A, 2026-07-09)

Four distinct music playback sources, all wired into a unified `media/MusicRouter.kt` dispatcher
so tools like `control_music` (play/pause/next/previous) work on any active source. Shipped Phase
A (builds + compiles in all flavors, no Spotify SDK wired yet). See decisions.md for the Spotify
SDK personal-only scope and policy reasoning.

- **TIER 1: PHONE (existing, primary).** Driver's phone or on-unit installed app's MediaSession.
  `MusicController` sends transport via MediaSession/MediaBrowser; `NowPlayingController` mirrors
  metadata/state. Handles BT streaming (SBC, aptX, LDAC), AVRCP album art on capable head units,
  any on-device app declaring MediaBrowserService. Dominates the active source; toggled by
  physical source input or when nothing else is playing.

- **TIER 2: MIXTAPE (existing, since M3).** Driver's own sideloaded audio files (USB import,
  internal-storage SAF browse). `MixtapePlayer` wraps Media3 ExoPlayer + our own MediaSession
  host. Full playlist / random / shuffle; metadata from ID3/vorbis/flac tags; generated cover art.
  Activated by voice tool `play_mixtape` (plays by name) or UI (logbook MIX tab).

- **TIER 3: PLAY_MUSIC (NEW, Phase A). Intent-based voice search.** "Play night-drive ambient" or
  "Play Deftones" — driver's preferred music app (usually Spotify or Apple Music if installed,
  falls back to YouTube Music or local player) receives an intent with `MediaStore
  .INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH` + `SearchManager.QUERY`. This is the Android standard
  "Siri-style voice search" path. If the target app can't handle it, we just open the app and
  let the driver type/search. No SDK, no credentials, works in every flavor (play + personal).
  Result surfaces as PHONE-source MediaSession once it starts playing, so `control_music` transport
  already works. Preferred app picked from `MUSIC_FALLBACKS` constant (same list as `open_music`).

- **TIER 4: SPOTIFY (NEW, Phase A infrastructure; App Remote SDK wired in Phase B, gated to
  personal flavor only).** Voice content selection + playback control for Premium users who register
  their own Spotify developer app. Architecture: Gradle flavor split — public `play` build has zero
  Spotify SDK code (policy-compliant for unlimited Play Store distribution), Kevin's personal
  `personal` build (sideload, package `.personal`) has the App Remote SDK + personal client ID (Dev
  Mode: one user, one app, Premium required, personal use only). `media/SpotifyBridge.kt` is a
  pure-Kotlin interface (no SDK imports). `media/SpotifyBridgeFactory.kt` exists twice (flavor
  source-set pattern): play-flavor returns `NoopSpotifyBridge` (no-op), personal-flavor returns
  `PersonalSpotifyBridge` (Phase B: real SDK conn + transport). `MusicRouter.kt` routes `Source
  .SPOTIFY` transport to the bridge. Phase B requires Kevin to register developer app + authorize
  self as <5 Development Mode user. Phase C (future): Web API search-to-play (App Remote plays
  only URIs, not search queries).

- **ROUTING IN MUSICCONTROLLER / MUSICPLAYER / MEDIATRANSMIT:** `media/MusicRouter.kt` is the
  unified dispatcher. Four transport methods (play/pause/next/previous) switch on `MusicSource
  .current` and dispatch to MusicController (PHONE), MixtapePlayer (MIXTAPE), SpotifyBridge
  (SPOTIFY). No need for individual tool wrappers; `control_music` tool in LiveToolbox calls one
  unified `musicRouter.transport(action)` method.

- **MUSIC_SOURCE ENUM:** added `SPOTIFY` variant. SharedFlow `MusicSource.current` is written by
  MixtapePlayer.play(), SpotifyBridge.connect(), or reset to PHONE on startup/app resume. Observed
  by UI (CruiseScreen now-playing overlay) and LiveToolbox tools.

**Build status:** Phase A (architecture, no SDK dependency) compiles in both `assemblePlayDebug`
and `assemblePersonalDebug`. Not hardware-tested. Spotify app-remote SDK will be added in Phase B.

**Verification checklist (MUST DO BEFORE MERGING):**
  - Build: `gradlew assemblePlayDebug assemblePersonalDebug` both green.
  - Unzip play-flavor APK, grep dex: `com/spotify` ABSENT, `SPOTIFY_CLIENT_ID` literal ABSENT.
  - Unzip personal-flavor APK, verify reverse (Spotify imports present where expected, client ID
    present in dex).
  - On device (when Phase B ships): "play my night-drive playlist" via TIER 3 (intent) on any music
    app (test Spotify + YouTube Music if available).
  - TIER 4 (Spotify App Remote) testing blocked until Phase B and Kevin Spotify dev registration.

## PENDING GRILL 2026-07-16 — USB CD player loader (physical object + import flow)

**UNRESOLVED FEATURE IDEA, NOT DECIDED.** A USB CD drive attached to the head unit, loading discs into the app. Fits the physical-object widget language (cassette/vinyl/boombox) and the reinstate-vinyl idea in the UI customization brain dump. Not researched or designed. Open questions for the grill: (1) AOSP 8-10 USB host + mass-storage/SCSI access to an external optical drive - feasible? (2) CD-DA ripping vs data-disc playback - CD-DA is not a filesystem read, needs raw track access. (3) Integration: import-to-library (disc -> library_track, reusing MixtapeLibrary's copy-into-filesDir shape) or a live disc transport tier in MusicRouter/MusicSource? Cheapest first cut is almost certainly import-to-library, not a live disc transport. Marked PENDING GRILL pending investigation and scope decision.
