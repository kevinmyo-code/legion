# Legion

Phone-only AI assistant (Alfred/JARVIS register - a tool with personality, not a mascot)
orchestrating **aspects** of life: **fleet** (OBD/car, ported from Midnight AI), **ledger**
(finance - bank-statement ingestion ported from Project Andromeda), **pantry** (grocery
receipt photos -> consumption rates + macros, not yet built).

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

## Ledger aspect (bank-statement ingestion) - done, deterministic-first

Ports Project Andromeda's `duo_ledger.bronze` layer (`~/PycharmProjects/Andromeda`) - the
only part of Andromeda with real content; `silver`/`gold`/`categorization`/`fx`/`agent` are
all empty stubs, nothing to port there. Full design in
`.claude/plans/wiggly-beaming-quasar.md`.

- `ledger/parsers/`: `DbsStatementParser`, `BofaStatementParser` - direct Kotlin ports, same
  balance-continuity reconciliation checks as the Python originals. `LedgerMoney` parses
  exact `Long` cents (not `Double` - see `LedgerTransaction`'s doc for why). `PdfWords`/
  `PdfText` (PdfBox-Android) replace `pdfplumber`.
- `StatementDispatcher` tries both deterministic parsers first; only when NEITHER recognizes
  the layout does it fall to `LedgerStatementAgent`, a one-shot Gemini extraction that must
  pass the same reconciliation principle (extracted transactions sum to the statement's own
  stated total) before anything is accepted - a mismatch quarantines, nothing is written.
  Every row is tagged `DETERMINISTIC` or `LLM_RECONCILED`.
- `LedgerController` orchestrates: read the picked file, dispatch, dedupe against existing
  rows (by real-world content, not filename/lineRef - two different exports of the same
  transaction must not double-count), insert.
- Voice tools: `import_statement` (opens `ui/LedgerImportActivity`, a picker placeholder),
  `get_balance`, `list_recent_transactions`.
- Room: `LedgerTransaction`/`LedgerTransactionDao`, `CarDatabase` v1 -> v2 with a real
  (verbatim, generated-schema-matched) migration.
- **Real finding, not assumed:** PdfBox-Android ships its fonts/glyphlists as Android assets,
  unreachable from a plain JVM unit test - confirmed by actually running the coordinate-
  extraction spike before porting the rest of the DBS parser, which failed with a clear
  `GlyphList not found` error. Fixed by adding Robolectric (test-only) to shadow
  `AssetManager`; the spike then verified real extraction against a generated fixture,
  matching the fixture's known column positions exactly.
- 11 tests, 5 fixture PDFs (generated via Andromeda's own `reportlab` tooling under
  `tests/helpers/pdf_builders.py`, for parity with the Python originals) - happy-path DBS,
  happy-path BofA, a DBS balance mismatch, a BofA section-total mismatch, an unrecognized
  layout (proving the LLM-fallback routing without needing a real Gemini key). All green.
- Not tested: the dedup path in `LedgerController` itself (attempted via Robolectric's
  `ShadowContentResolver`, hit an API/behavior mismatch not worth chasing further - the
  underlying dedup query is a simple, inspectable `COUNT(*)`, and the parser/reconciliation
  logic it sits on top of is fully verified).

## Not built yet

- **`ui/`** - almost every screen. This is intentional, not a gap to panic about; see the
  design-language note above. (`LedgerImportActivity`/`SavedPlacesActivity`/`MainActivity`
  are placeholders, not real UI.)
- **`pantry` aspect** - not started. New (receipt-photo ingestion behind the deterministic
  reconciliation gate described in `MIDNIGHT_AI`'s `memory/MEMORY.md`) - no Andromeda
  equivalent to port from.
- **Ledger's categorization/FX/insights** - not built. Nothing to port (Andromeda's
  `silver`/`gold`/`categorization`/`fx` are empty stubs); this is new design work for later.
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
