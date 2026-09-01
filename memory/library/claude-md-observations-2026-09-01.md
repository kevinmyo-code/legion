---
shelf: archive
status: live
kind: reference
---

# CLAUDE.md's observation sections, as they stood 2026-09-01

**Lifted verbatim out of `CLAUDE.md` when it was cut back to rulings only.** Nothing was edited on
the way across.

These four sections described the CODE rather than stating rules, and that is why they rotted: the
tech stack, the Room version changelog, the package map, and the not-built-yet list. Between them
they carried most of the file's `CORRECTED` and `STALE` scar tissue - §5 alone confessed to being
wrong three times in a row about the schema version, and §6 twice about the package layout.

Kept because some of it is genuine history worth reading once - what was ported from Midnight AI,
what the engine cutover moved and when. **It is a snapshot, not an authority.** For what is true
now: `Glob` the tree, `sed -n '/version = /p' app/src/main/java/com/kevin/legion/data/local/CarDatabase.kt`
for the schema version, `docs/architecture/` for how it fits together, and the board for what is
unbuilt.

---

## 3. Tech Stack

| Layer | Choice | Notes |
|---|---|---|
| Platform | Android phone, Kotlin, Compose | Min/target per `build.gradle.kts` |
| Voice AI | Gemini Live WebSocket STS | `service/GeminiLiveSession.kt`, server VAD, half-duplex |
| Sub-agents | Gemini Flash REST | `ai/SubAgent.kt`, one-shot + bounded investigate loop; now also takes an optional inline image part (`imageBytes`/`imageMimeType`) for pantry vision |
| BYO key | Paste + 1-token validation ping | Ping is `ai/GeminiKeyValidator.kt` (`VALID`/`INVALID_KEY`/`NETWORK_ERROR`); storage is `ai/KeyVault.kt` (Keystore AES/GCM) via `CompanionProfile.saveGeminiKey`; resolution is `ai/GeminiKeyProvider.kt`. Direct to Google, no proxy |
| Local DB | Room **v55** (`data/local/CarDatabase.kt`) | Fresh v1 for this app (no migration chain from Midnight AI's v12, no installed base). Chain complete through `MIGRATION_40_41` (`events_replica.createdAt`, closing the gap that let a migrated Notes/Dates row's server-side `created_at` default to the migration's own run time instead of the note's real age, 2026-08-26); all real verbatim generated-SQL migrations with `exportSchema` |
| OBD | ELM327 Bluetooth RFCOMM + BLE | Unchanged from Midnight AI |
| Music | Spotify App Remote as the SPINE (`media/SpotifyController`, connection held in the FGS - ADR 0032) + Web API name resolution (`media/SpotifyWebApi`, own library first) + generic MediaSession transport fallback (`media/MusicController`) | BYO Spotify client ID (ADR 0033). `MusicRouter`/`MusicSource`/mixtapes all retired |
| Location | Android `Geocoder` | The Mapbox-backed `NavGeocoder`, embedded nav, and the phone-to-head-unit GPS beacon are all gone |
| Sync | Google Drive `appDataFolder`, `drive.appdata` | `sync/`, `play-services-auth` |
| Crash/observability | `Log.d` via `MidnightEvents` | Firebase is NOT wired up. `google-services.json` is intentionally excluded and gitignored |

**PdfBox-Android REMOVED, 2026-08-29 (backend-erp ticket 25, "statement ingestion leaves the phone
entirely").** It used to parse bank-statement PDFs on-device (ledger only); Kevin ruled the phone
never ingests a statement at all now - a statement PDF is already on the laptop, so the web app
ingests it there, against `public.commit_statement`. It was the single largest dependency this app
carried, and the Robolectric requirement its bundled fonts/glyphlists forced on ledger's unit tests
went with it (Robolectric itself stays, for Roborazzi screenshot tests and several unrelated
Robolectric suites).

Dropped dependencies, deliberately: Mapbox, Firebase, Play Billing, Media3, ZXing, PdfBox-Android.

---

## 5. Data Layer (Room v41)

Additive migrations only, verbatim generated SQL, `exportSchema = true`, schema JSON committed
under `app/schemas/`, no destructive fallback on upgrade.

- **v1** - fresh baseline for LEGION. Fleet tables carried over from Midnight AI's v12 shape minus
  everything retired (mixtape tables, music-taste ledger, `BuildEntry.photoPath`).
- **v2** - `LedgerTransaction` + DAO.
- **v3** - `PantryReceipt` + `PantryLineItem` + DAOs. No `ingestMethod` column on `PantryReceipt`:
  every row is LLM-extracted by construction, so it would always read the same value.
- **v4** - `ingested_files` + DAO (the per-file ingestion ledger, ticket 03).
- **v5** - `companion_profiles` + DAO.
- **v6 through v34** - not listed here (v34 is the aspect-engine core: `aspects`, `record_types`, `field_defs`, `records`, `widget_instances`, 2026-08-23; the engine's only writer is `engine/RecordStore.kt`).
- **v35** - `widget_instances` gains `gridRow`/`gridCol`/`rowSpan`/`colSpan` (the pager's grid
  mechanics, aspect-engine ticket 09).
- **v36** - `muted_reminders` (aspect-engine ticket 19, the Dates aspect build; a reminder mute is
  its own tiny table rather than a column on `records` - see `MutedReminder`'s own doc comment).
- **v37** - `records.guid`, a `TEXT NOT NULL DEFAULT ''` column plus a real per-row backfill plus a
  unique index, the cross-device identity column senior review of the mirror/sync ticket flagged
  as a MUST-FIX (a per-database `AUTOINCREMENT` id was being matched across two independent
  phones). 2026-08-24.
- **v38 through v40** - not itemized here (backend-erp Phase 4, aspect 4 of 5, Notes+Dates merged:
  `events_replica`/`event_skips_replica` land at v38, `events_replica.startsAt` widens to nullable
  at v40 - see [MIGRATION_39_40]'s own doc comment for that one's create/copy/drop/rename shape).
- **v41** - `events_replica` gains `createdAt` (`INTEGER NOT NULL DEFAULT 0`), a plain additive
  `ALTER TABLE ADD COLUMN` - `.scratch/backend-erp/issues/11-notes-write-path-rewire.md`'s own
  follow-up. Closes a real defect, not a cosmetic gap: `SupabaseEventsBackend.uploadMigratedEvent`
  used to insert with no `created_at` at all, silently taking Postgres's own `default now()`, so
  every migrated Notes/Dates row's creation time became the moment the one-time migration ran
  rather than the note's real age - `GoalChecklistSync`'s "already materialized today" gate and
  `LogDigestBuilder`'s FRESH/AGING/STALE age buckets both key entirely off this field. 2026-08-26.

`data/local/Migrations.kt` is the authority, and the entity roster grouped by aspect is in
`docs/architecture/c3-data.md`. **CORRECTED 2026-08-18, and again 2026-08-24, and again
2026-08-26:** this section said v21 for weeks while the code was at v25, then said v34 while the
code was at v37, then said v37 while the code was at v41, and `CarDatabase.kt`'s own KDoc still
says 15 in one place. Read the code before quoting a version -
`sed -n '/version = /p' data/local/CarDatabase.kt` is the one-line way to check.

**Widening an enum stored as TEXT is not a migration.** `LedgerTransaction.ingestMethod` and
friends are `TEXT NOT NULL` with no CHECK constraint, so adding a constant changes no SQL, leaves
the identity hash alone, and needs no version bump - `IngestMethod.UNRECONCILED` was added at v5
with zero schema change. Confirm it the same way rather than assuming: read the column's
`createSql` in `app/schemas/`, and check the schema JSON is byte-unchanged after a kapt run.

---

## 6. Codebase Map

**CORRECTED 2026-08-24.** This map went stale in two ways at once: it never got an `engine/`
entry after the 2026-08-23 aspect-engine build, and its `ui/` line ("CLEAN SLATE... all
placeholders") had been long false even before that - see §10, corrected 2026-08-18, for the
same drift. `ui/` is 121 Kotlin files. Read the code before trusting a package list, this one
included.

```
app/src/main/java/com/kevin/legion/
├── ai/            AriaBrain, SubAgent (+ inline image part), AssistantIdentity (resolver) +
│                  Personas (the ACTUAL register copy: ALFRED, DOROTHY), KeyVault, CrisisDetector,
│                  OnboardingFlow, PersonaTraits (ORPHANED - see §10), Voices, ReflectionEngine
├── engine/        **CORRECTED 2026-08-28: NOT the spine any more - scoped to user-created aspects
│                  only** (backend-erp ticket 18, "the engine SURVIVES, scoped to user-created
│                  aspects"). All six built-in aspects (places, pantry, fleet, notes, dates,
│                  ledger) are off it. What remains: RecordStore backing `create_aspect` and the
│                  generated list/detail/form/widget-pager UI, ReconciliationGate, ComputedEvaluator,
│                  FieldConfig, PayloadCodec, WidgetInstanceStore, DefaultArrangementSeeder, DeviceId;
│                  dates/ (DatesAgenda still reads the engine's dueAt scan for OTHER aspects only,
│                  Dates' own data lives in `events`), fleet/, ledger/, notes/, pantry/, places/
│                  (per-aspect engine adapters - copiers and cutover glue, not live storage),
│                  migration/ (the wave1-4 one-time copiers, still present, writer-less now),
│                  mirror/ (xlsx export per aspect into the user's Drive folder - the audit
│                  surface and the two-phone sync channel). `engine/EngineBoundaryTest` enforces
│                  this boundary in the test suite
├── service/       AriaForegroundService, GeminiLiveSession, LiveSessionController, LiveToolbox,
│                  EngineToolbox (the nine engine meta-tools + clerk + schema generator, folded
│                  into LiveToolbox's declarations/dispatch), WakeWordEngine (live, Vosk-based -
│                  AmbientListener was retired 2026-08-21 and no longer exists), ProactiveBus,
│                  GlanceCardController, Phase
├── ledger/        LedgerController. **CORRECTED 2026-08-28: repointed off `engine/RecordStore`
│                  onto `ledger_transactions` directly (ticket 15)**, LedgerStatementAgent,
│                  LedgerIngestResult, parsers/
├── pantry/        PantryController. **CORRECTED 2026-08-28: repointed off `engine/RecordStore`
│                  onto `pantry_receipts`/`pantry_line_items` directly (ticket 15)**, PantryReceiptAgent,
│                  PantryIngestResult
├── vehicle/       fleet aspect: OBD stack, agents, maintenance, recaps, garage (Shelly).
│                  **CORRECTED 2026-08-28: `ServiceHistory`/`MaintenanceSchedule` repointed onto
│                  legacy `service_records`/`maintenance_items` (ticket 16)** - only Vehicle
│                  identity still dual-writes engine + legacy mirror, kept for `FleetReconcile`
├── media/         MusicController, NowPlayingController, SpotifyController, SpotifyWebApi, VolumeController
├── location/      LocationController, PlaceController, ReminderController
├── sync/          DriveAuth, DriveClient, SyncEngine, SyncMerge, SyncCodec, DatabaseSnapshot(Guard),
│                  DriveConflict, SyncCapability - the LEGACY appDataFolder JSON sync. Separate
│                  from engine/mirror/'s xlsx channel, which is the sync path going forward
├── data/          EnginePhotoStore, PantryPhotoStore, MidnightImport, local/ (Room, v37)
├── weather/       WeatherController (Open-Meteo, keyless)
├── ui/            NOT a clean slate - mission-control design language shipped and verified
│                  on-device (see §10). MainActivity is the ONLY Activity; its NavHost start
│                  destination is DASHBOARD (the widget pager), with "Classic" one tap away to
│                  the per-aspect screens. widgets/ (the pager itself), generated/ (list/detail/
│                  form screens driven by field defs), grid/ (preset sizes, launcher semantics),
│                  plus theme/, common/, and one folder per aspect
└── util/          AppSigning, Dates, Units
```

**Build:**
- `./gradlew compileDebugKotlin -Pnokey` - compile without a baked-in key (the honest first-run path)
- `./gradlew testDebugUnitTest` - unit tests (2,530 across the whole suite, green both with and
  without a baked Gemini key, verified 2026-08-24. The old "19 across ledger + pantry" figure was
  from 2026-07-31, before fleet, engine, and everything since)
- `./gradlew assembleDebug` - build

**Setup:** `local.properties` needs `sdk.dir` and optionally `GEMINI_API_KEY` for a convenience dev
build. Four `RELEASE_STORE_*` values for a release build. Set `JAVA_HOME` in your own environment;
**do not put `org.gradle.java.home` in the committed `gradle.properties`** - Midnight AI's did, and
it broke on any machine without Android Studio at that exact path, violating clone-and-run.

**There is more than one of Kevin's machines and their paths differ. Neither has a usable JDK on
`PATH`**, so `./gradlew` fails from a fresh shell on both. Export per shell, never commit it.

| Machine | Repo | Android Studio JBR |
|---|---|---|
| First (2026-08-01) | `C:\Users\Kwin\StudioProjects\legion` | `/c/Users/Kwin/Apps/AndroidStudio/jbr` |
| Second (2026-08-19) | `C:\Users\kevin\AndroidStudioProjects\legion` | `/c/Program Files/Android/Android Studio/jbr` |

```
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # second machine
export PATH="$JAVA_HOME/bin:$PATH"
```

On the second machine an Oracle JRE 24 IS on `PATH` (`Common Files/Oracle/Java/javapath`) - it is
the wrong JDK and Gradle picks it up if `JAVA_HOME` is unset. `adb` lives at
`~/AppData/Local/Android/Sdk/platform-tools/` and is not on `PATH`. Git identity was unset there
(set repo-locally, 2026-08-19). **The A25 has never been attached to it.**

---

## 10. Not built yet

Stated so nobody treats these as gaps to panic about or as silently-missing work:

- ~~**Almost all of `ui/`.**~~ **STALE, corrected 2026-08-18, count refreshed 2026-08-24.** `ui/`
  holds 121 Kotlin files and the design language IS chosen: mission control, built and verified on
  the phone, now hosting a widget-pager dashboard as the app's home screen. See
  `docs/adr/0023-design-language-mission-control.md` and
  `docs/adr/0037-the-aspect-engine-is-the-spine.md`.
- **Onboarding UI.** `ai/OnboardingFlow.kt` ported, but its identity clause is placeholder and the
  conversational onboarding screen that hosts it does not exist.
- ~~**The assistant's actual voice.**~~ **DONE, corrected 2026-08-16** - see §1. `Personas.kt`
  ships Alfred and Dorothy, the picker ships, and the persona genuinely changes the system prompt.
- **Freeform personality authoring. BACK BURNER (Kevin, 2026-08-16), Alfred and Dorothy are
  enough for now.** Midnight AI let users build a personality by staged questions or free text.
  `PersonaTraits.kt` still holds all five stages and `assemblePersona()`, but its only caller
  `CompanionProfile.savePersona()` **has no production caller** - the roster UI writes a persona
  KEY instead. Ported, complete, orphaned. **Do not simply re-wire it:** `CompanionProfile.persona()`
  is dual-typed (key in the live path, prose in the legacy one) and `personaFor()` silently falls
  back to `ALFRED` on any unrecognised string (`Personas.kt:159`), so freeform prose written to
  that field is discarded without an error. Full account in
  `.scratch/hands-and-senses/issues/12-assistant-identity.md`.
- **Ledger categorization / FX / insights.** Nothing to port; new design work.
- **Pantry consumption-rate tracking and spend/nutrition aggregation.** Deliberately deferred at
  scoping time, same shape as ledger's insight layers.
- ~~**`LedgerController` dedup and `PantryController` DB-write paths are untested.**~~ **STALE,
  corrected 2026-08-24.** Both cutovers (2 and 3) closed this: `PantryControllerTest` and
  `LedgerIngestPipelineEngineCommitTest`/`IngestPipelineEngineCommitTest` are real Robolectric
  suites over the engine write path (CRUD reads, the gate's Success/Quarantine boundary, anchor
  persistence, rule-7 supersession, a genuine post-gate write failure rolling back rather than
  reporting false success) - the `ShadowContentResolver` gap that blocked this was worked around
  by testing `PantryController.writeReceipt`/`IngestPipeline.commit` directly rather than through
  the content-resolver-backed import entry points.
- **Firebase.** Not wired up. `MidnightEvents` logs via `Log.d`.

Two contested calls left open by the port, flagged not decided: whether `media/MusicController` is
still wanted alongside Spotify App Remote, and that `vehicle/BuildSheetController` build entries are
now text-only (`photoPath` dropped) as a schema change, not just a doc update.

---

