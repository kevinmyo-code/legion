# Legion

Phone-only AI assistant (Alfred/JARVIS register - a tool with personality, not a mascot)
orchestrating **aspects** of life: **fleet** (OBD/car, ported from Midnight AI), **ledger**
(finance, to be ported from Project Andromeda), **pantry** (grocery receipt photos ->
consumption rates + macros, not yet built).

This repo is a clean-history fork-by-copy of `MIDNIGHT_AI` (a private archive of the prior
car-launcher product), seeded 2026-07-31 per the pivot recorded in `MIDNIGHT_AI`'s
`memory/library/decisions.md` and `memory/MEMORY.md`. See those files for the full pivot
rationale, naming trail, and competitive-landscape research - not duplicated here.

## Status: builds clean (`./gradlew compileDebugKotlin -Pnokey`), no UI yet

The fleet aspect (all of `vehicle/`), the orchestrator core (`LiveToolbox`, `AriaBrain`,
`GeminiLiveSession`), and the Drive sync backbone are ported and compile. Verified with an
actual build, not just source inspection - `./gradlew compileDebugKotlin -Pnokey` succeeds
with only pre-existing deprecation warnings (none introduced by the port).

What's been done:

- Package renamed `com.kevin.midnightai` -> `com.kevin.legion` across all copied source.
- Retired packages/files dropped entirely: `billing/` (commercial model dead), city-pop
  art/theme generation (`AvatarStudio`, `OccasionStylist`, `WallpaperPresets`,
  `CompanionIdentity`), the phone<->head-unit GPS beacon (`BeaconService`, `DeviceRole`,
  `location/Beacon*`), embedded Mapbox nav (`NavCapability`, `service/Nav*`,
  `MapboxTokenProvider/Validator`), the floating companion badge
  (`CompanionBadgeController`, `OverlayOwners`), `LauncherSettings`, `TriviaController`,
  `VoiceUsageMeter`, the mixtape stack (`MixtapeLibrary`, `MixtapePlayer`, mixtape/music-taste
  Room tables), `SpendGate`, and `MusicAgent`/`get_music_taste`/`recommend_music` (their
  entire data foundation - the taste ledger and saved-mixtape library - was already gone,
  so they'd have silently always returned "not enough history").
- Room database rewritten fresh as v1 (`data/local/CarDatabase.kt`) - no migration chain
  from Midnight AI's v12, since this is a new app with no installed base.
- Build config (`build.gradle.kts`, `gradle/libs.versions.toml`) trimmed to match: no
  Mapbox, Firebase, Play Billing, Media3, or ZXing dependencies. `MidnightEvents` now logs
  via `Log.d` instead of `FirebaseCrashlytics` (same public API, so no call site changed) -
  no Firebase project is wired up yet (`google-services.json` intentionally excluded,
  gitignored going forward).
- `control_music`/`play_music` rebuilt on `MusicController` (generic MediaSession
  transport) + Spotify App Remote direct play, replacing the retired
  `MusicRouter`/`MusicSource` phone<->head-unit source-switching model.
  `get_current_location` rebuilt on Android's built-in `Geocoder` (the Mapbox-backed
  `NavGeocoder` is gone).
- `ui/` is almost entirely a clean-slate rebuild (city-pop design language is dead per the
  pivot) - only placeholder `MainActivity` and `SavedPlacesActivity` exist, enough for the
  app to launch and for the `show_saved_places` tool to have somewhere to point.

**Verification history, so the next session doesn't have to redo it:** the first reconciliation
pass fixed everything a `grep` for retired class names could find (36 files), then a real
`./gradlew compileDebugKotlin` run caught what grep couldn't: `BuildEntryDao.setPhoto()` still
querying a dropped `photoPath` column, `service/Phase.kt` wrongly deleted as "badge-only" when
`CompanionPhase`/`LiveSessionController` need it for core conversation state, and several files
(`NowPlayingController`, `MusicController`, `VolumeController`, `MediaNotificationListener`)
that were referenced but never actually copied from Midnight AI in the first place. **Lesson:
grep-based reconciliation finds symbol-level breaks; only a real compile finds the rest** (schema
mismatches, wrongly-deleted shared code, missing files that were never flagged because nothing
grepped for their absence).

## Not built yet

- **`ui/`** - almost every screen. This is intentional, not a gap to panic about; see the
  design-language note above.
- **`ledger` and `pantry` aspects** - not started. Ledger ports from Project Andromeda;
  pantry is new (receipt-photo ingestion behind the deterministic reconciliation gate
  described in `MIDNIGHT_AI`'s `memory/MEMORY.md`).
- **Onboarding** - `ai/OnboardingFlow.kt` (the system-instruction builder) ported, but its
  identity clause is placeholder content (see `ai/AssistantIdentity.kt`'s doc comment) and
  the conversational onboarding UI that hosts it doesn't exist yet.

## Contested calls not yet resolved

- Whether `media/MusicController.kt` (generic MediaSession prev/pause/next, used by
  `control_music`) is still wanted alongside Spotify App Remote, or whether Spotify alone
  should cover voice-driven playback control - didn't come up in the pivot decision, kept
  both since removing working code speculatively is worse than leaving an unused path.
- `vehicle/BuildSheetController.kt` build/mod entries are text-only now (`photoPath` column
  dropped from `BuildEntry`), consistent with the 2026-07-31 decision that retired fleet
  photos - just flagging that this was a schema change, not just a doc update.

## Setup

Same as Midnight AI: `local.properties` needs `sdk.dir` (Android SDK path) and, for a
convenience dev build, `GEMINI_API_KEY` (BYO; `-Pnokey` ships without one, exercising the
real no-key first-run instead). Four `RELEASE_STORE_*` values are needed for a release
build. No Mapbox download token needed anymore (nav dependency removed). `gradle.properties`
deliberately does NOT hardcode a JDK path (`org.gradle.java.home`) - Midnight AI's did, which
broke on any machine without Android Studio installed at that exact path, violating the
pivot's clone-and-run requirement. Set `JAVA_HOME` in your own environment, or override
`org.gradle.java.home` in a local, gitignored `gradle.properties` if Gradle picks up a JRE
with no `javac`/`jlink`.
