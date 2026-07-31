# Legion

Phone-only AI assistant (Alfred/JARVIS register - a tool with personality, not a mascot)
orchestrating **aspects** of life: **fleet** (OBD/car, ported from Midnight AI), **ledger**
(finance, to be ported from Project Andromeda), **pantry** (grocery receipt photos ->
consumption rates + macros, not yet built).

This repo is a clean-history fork-by-copy of `MIDNIGHT_AI` (a private archive of the prior
car-launcher product), seeded 2026-07-31 per the pivot recorded in `MIDNIGHT_AI`'s
`memory/library/decisions.md` and `memory/MEMORY.md`. See those files for the full pivot
rationale, naming trail, and competitive-landscape research - not duplicated here.

## Status: source scaffold, does not build yet

This is a mechanical source port, not a working app. What's been done:

- Package renamed `com.kevin.midnightai` -> `com.kevin.legion` across all copied source.
- Retired packages/files dropped entirely: `billing/` (commercial model dead), city-pop
  art/theme generation (`AvatarStudio`, `OccasionStylist`, `WallpaperPresets`,
  `CompanionIdentity`), the phone<->head-unit GPS beacon (`BeaconService`, `DeviceRole`,
  `location/Beacon*`), embedded Mapbox nav (`NavCapability`, `service/Nav*`,
  `MapboxTokenProvider/Validator`), the floating companion badge
  (`CompanionBadgeController`, `OverlayOwners`, `Phase`), `LauncherSettings`,
  `TriviaController`, `VoiceUsageMeter`, the mixtape stack (`MixtapeLibrary`,
  `MixtapePlayer`, mixtape/music-taste Room tables), `SpendGate`.
- Room database rewritten fresh as v1 (`data/local/CarDatabase.kt`) - no migration chain
  from Midnight AI's v12, since this is a new app with no installed base.
- Build config (`build.gradle.kts`, `gradle/libs.versions.toml`) trimmed to match: no
  Mapbox, Firebase, Play Billing, Media3, or ZXing dependencies.
- `ui/` is entirely a clean-slate rebuild - almost nothing survived (city-pop design
  language is dead per the pivot). Only a placeholder `MainActivity` exists so the project
  has an entry point.

**What's NOT done - the actual next task:** roughly 36 files still reference classes that
were deleted in the prune (found via `grep -rl` for the retired class names across the
copied tree before this commit). Notably `service/LiveToolbox.kt`, `ai/AriaBrain.kt`,
`sync/SyncEngine.kt`, `sync/CompanionSync.kt`, `vehicle/VehicleController.kt`, and several
sub-agents still declare or dispatch tools tied to nav, mixtape playback, avatar
generation, the companion badge, and per-car companion identity. Patching these correctly
needs real per-call-site judgment (e.g. stripping a tool declaration wrong could silently
break OBD logic), not a blind find-replace - treat this as the first real coding task on
this repo, not a mechanical follow-up. Run this to see the current list:

```
grep -rl "AvatarStudio\|CompanionIdentity\|MixtapePlayer\|MixtapeLibrary\|MusicRouter\|MusicSource\|CompanionBadgeController\|TriviaController\|NavState\|NavPreferences\|NavGeocoder\|BeaconService\|DeviceRole\|SpendGate\|MapboxToken\|VoiceUsageMeter\|LauncherSettings\|BillingManager\|EntitlementManager\|UnlockState\|RuntimeMode\|GenerationMeter" app/src/main/java/com/kevin/legion
```

## Contested calls not yet resolved (kept, flagged rather than silently PORTed)

- `media/MusicController.kt` (generic MediaSession prev/pause/next) was kept but its fate
  wasn't explicitly decided - Spotify App Remote may cover voice-driven playback control
  on its own. Confirm before relying on it.
- `vehicle/BuildSheetController.kt` and `build_entry.photoPath` still exist as copied, but
  fleet build/mod photos were explicitly retired (2026-07-31 decision) - this needs a pass
  to go text-only, not photo-backed.
- No Firebase project is wired up (`google-services.json` intentionally excluded, gitignored
  going forward). Crashlytics dependency was dropped from `build.gradle.kts` entirely rather
  than shipping with a placeholder config. Add back once a fresh Firebase project exists for
  Legion, if wanted at all.
- Room schema export (`app/schemas/`) is empty until the first successful build generates
  the v1 JSON - no historical migration files exist to seed it, deliberately.

## Setup

Same as Midnight AI: `local.properties` needs `GEMINI_API_KEY` (BYO, for dev convenience;
`-Pnokey` ships without one) and the four `RELEASE_STORE_*` values for a release build.
No Mapbox download token needed anymore (nav dependency removed).
