# CLAUDE.md

Single source of truth for LEGION. Opus/Fable plans, Sonnet executes, subagents report.
Created 2026-08-01 from the 2026-07-30/31 pivot off Midnight AI. This file holds RULES.
`memory/MEMORY.md` holds STATE.

## Read order

1. **`memory/MEMORY.md`** first. Thin dashboard: what is happening now, blockers, in-flight,
   notes for next session. Always loaded, under 80 lines.
2. **This file (CLAUDE.md)**. Locked architecture, frozen decisions, guardrails. Changes rarely.
3. **`README.md`**. Public-facing build status, per-aspect detail, verification history. It is
   the authority on what compiles and what is tested; do not duplicate it here.
4. **Anything deeper**: dispatch the `librarian` agent (RETRIEVE mode) against `memory/library/`.
   Card catalog: `memory/library/INDEX.md`. Do not bulk-read shelf files into the main context.
   **Most of that library is FROZEN Midnight AI history** (see §11) - it is reference, not rules.
5. **`TEAM.md`**. Subagent roster and dispatch cadence.

If MEMORY.md and CLAUDE.md disagree: **MEMORY.md wins for state, CLAUDE.md wins for rules.**

---

## 1. Identity

- **Product:** LEGION, one **Android phone app**. A single voice assistant orchestrating
  **aspects** of life. Not a launcher, not a head-unit product, not a commercial product.
- **Register: Alfred/JARVIS.** A tool with a personality. Not a mascot, not a companion, not the
  car. Competent, dry, useful. One global identity (`ai/AssistantIdentity.kt`) - **the actual
  voice has not been written yet**, that file is placeholder copy by its own doc comment.
- **Aspects:**
  | Aspect | What it is | State |
  |---|---|---|
  | fleet | OBD, car, maintenance, drives | Ported from Midnight AI, compiles |
  | ledger | Bank-statement ingestion | Ported from Project Andromeda, done, 11 tests |
  | pantry | Grocery receipt photo ingestion + macro estimates | New design work, done, 8 tests |
- **Repo:** `C:\Users\Kwin\StudioProjects\legion`, public, `github.com/kevinmyo-code/legion`.
  Package `com.kevin.legion`. Clean history, seeded 2026-07-31 by copying surviving Midnight AI
  source.
- **MIDNIGHT_AI (`C:\Users\Kwin\StudioProjects\MIDNIGHT_AI`) is a FROZEN ARCHIVE.** Private, read
  only, historical reference for what was ported. Never build there. Never write LEGION's project
  history into its memory files, which is what happened during the 2026-07-31 port session.
- **Solo dev:** Kevin (kevinmyo@gmail.com).
- **Project Andromeda (`~/PycharmProjects/Andromeda`) retires now that ledger has ported.** Only
  its `duo_ledger.bronze` layer had real content; `silver`/`gold`/`categorization`/`fx`/`agent`
  are empty stubs.

---

## 2. Locked decisions from the pivot (2026-07-30/31)

None of these is re-openable without Kevin. Full record in `memory/library/decisions.md`
(2026-07-31 entries).

| Decision | Consequence |
|---|---|
| **Phone-only** | Head units may still install it; they no longer constrain design. The AOSP 8-10 ceiling, the frame-clock-only motion ban, and the ADB blackout are all LIFTED. Normal Compose animation is allowed. |
| **Commercial model is DEAD** | No billing, tiers, broker, trial, store listing, pricing, or positioning work. `billing/` was dropped entirely. Do not reintroduce it or reason about conversion. |
| **Clone-and-run is a HARD requirement** | A stranger clones, sideloads, signs in, and it works. This, not cost, is what rules out Firestore. It is also why `gradle.properties` must never hardcode `org.gradle.java.home`. |
| **Drive-BYO is the only store** | One shared Google account, two phones, `appDataFolder`. No Firestore, no Kevin-run backend, ever. |
| **One global assistant identity** | Cars are data, not identities. Per-car `CompanionProfile` keying and Midnight AI's `CompanionIdentity` Zero-vs-car-self split are both dead. |
| **The city-pop design language is DEAD** | With it: the mascot Zero, all generated art, `AvatarStudio`, `OccasionStylist`, `WallpaperPresets`, the two-identities decision. `ui/` is a deliberate clean slate, not a gap. **No replacement design language has been chosen yet.** |
| **LLM ingestion is ALLOWED, behind a reconciliation gate** | See §4. This reverses Midnight AI's "no LLM extraction" posture. |
| **Carry-over inventory ruled on (5 calls, 2026-07-31)** | Music: Spotify App Remote kept, no UI, mixtapes retired. Garage: kept, voice-only. Spend gate: retired, no ledger replacement yet. Fleet build/mod photos: retired (photo storage is pantry-ingestion-only). Tagged places: kept as-is. |

**Two findings that still threaten plans, both open:**
1. **Drive's Android OAuth client is keyed to package + SHA-1 signing cert**, so a stranger's own
   build fails authorization. This directly threatens clone-and-run. Unresolved.
2. **Drive has no compare-and-swap**, so today's shared-file last-write-wins sync will silently
   lose rows. Sync must become append-only. Unresolved.

**Do not assume the old wayfinder map applies.** The original
`.scratch/multi-aspect-assistant/map.md` (15 tickets, 12 contested calls) did not survive a
machine port - `.scratch/` is gitignored and was never committed. It is gone, not stale. A fresh
map exists at the same path; its 5 contested items are already resolved and filed.

---

## 3. Tech Stack

| Layer | Choice | Notes |
|---|---|---|
| Platform | Android phone, Kotlin, Compose | Min/target per `build.gradle.kts` |
| Voice AI | Gemini Live WebSocket STS | `service/GeminiLiveSession.kt`, server VAD, half-duplex |
| Sub-agents | Gemini Flash REST | `ai/SubAgent.kt`, one-shot + bounded investigate loop; now also takes an optional inline image part (`imageBytes`/`imageMimeType`) for pantry vision |
| BYO key | Paste + 1-token validation ping | `ai/KeyVault.kt` (Keystore AES/GCM), direct to Google, no proxy |
| Local DB | Room **v3** (`data/local/CarDatabase.kt`) | Fresh v1 for this app (no migration chain from Midnight AI's v12, no installed base). v1->v2 ledger, v2->v3 pantry, both real verbatim generated-SQL migrations with `exportSchema` |
| OBD | ELM327 Bluetooth RFCOMM + BLE | Unchanged from Midnight AI |
| Music | Generic MediaSession transport (`media/MusicController`) + Spotify App Remote direct play | `MusicRouter`/`MusicSource`/mixtapes all retired |
| Location | Android `Geocoder` | The Mapbox-backed `NavGeocoder`, embedded nav, and the phone-to-head-unit GPS beacon are all gone |
| Sync | Google Drive `appDataFolder`, `drive.appdata` | `sync/`, `play-services-auth` |
| PDF | PdfBox-Android | Ledger only. Ships fonts/glyphlists as Android **assets**, unreachable from a plain JVM unit test - Robolectric (test-only) is required to shadow `AssetManager` |
| Crash/observability | `Log.d` via `MidnightEvents` | Firebase is NOT wired up. `google-services.json` is intentionally excluded and gitignored |

Dropped dependencies, deliberately: Mapbox, Firebase, Play Billing, Media3, ZXing.

---

## 4. The reconciliation gate (the core architectural rule)

**LLM extraction is allowed only behind a deterministic reconciliation gate.** This is the rule
that makes ingestion trustworthy, and it is not negotiable per-feature.

1. **Deterministic first where a deterministic path exists.** Ledger: `DbsStatementParser` and
   `BofaStatementParser` are primary. `StatementDispatcher` falls to `LedgerStatementAgent` (LLM)
   **only** when neither recognizes the layout. Pantry has no deterministic path (receipts are
   photographed, not born-digital), so LLM vision is primary there by necessity, not preference.
2. **Extracted rows must reconcile against the document's OWN stated total**, exactly. Sum of
   line items equals the printed total, or the whole document **quarantines**. Nothing partial is
   ever written. Never silently accept.
3. **Money is `Long` cents, never `Double`.** Deliberate deviation from `BuildEntry`/
   `ServiceRecord`'s convention, because the gate depends on exact equality.
4. **Tag the provenance of every row**: `DETERMINISTIC` or `LLM_RECONCILED`.
5. **Anything the document does not state cannot be gated, and must be surfaced as an estimate,
   never as fact.** Pantry's per-item macros (calories/protein/carbs/fat) are LLM guesses from the
   product name; a receipt never prints them. They are excluded from the reconciliation check and
   their tool descriptions must say "estimate".

Rule 5 is the §7 safety thesis applied to data: agents and memory are safe to the degree they are
anchored to external, falsifiable reality.

---

## 5. Data Layer (Room v3)

Additive migrations only, verbatim generated SQL, `exportSchema = true`, schema JSON committed
under `app/schemas/`, no destructive fallback on upgrade.

- **v1** - fresh baseline for LEGION. Fleet tables carried over from Midnight AI's v12 shape minus
  everything retired (mixtape tables, music-taste ledger, `BuildEntry.photoPath`).
- **v2** - `LedgerTransaction` + DAO.
- **v3** - `PantryReceipt` + `PantryLineItem` + DAOs. No `ingestMethod` column on `PantryReceipt`:
  every row is LLM-extracted by construction, so it would always read the same value.

---

## 6. Codebase Map

```
app/src/main/java/com/kevin/legion/
├── ai/            AriaBrain, SubAgent (+ inline image part), AssistantIdentity (PLACEHOLDER copy),
│                  KeyVault, CrisisDetector, OnboardingFlow, PersonaTraits, Voices, ReflectionEngine
├── service/       AriaForegroundService, GeminiLiveSession, LiveSessionController, LiveToolbox,
│                  WakeWordEngine, ProactiveBus, AmbientListener, GlanceCardController, Phase
├── ledger/        LedgerController, LedgerStatementAgent, LedgerIngestResult, parsers/
├── pantry/        PantryController, PantryReceiptAgent, PantryIngestResult
├── vehicle/       fleet aspect: OBD stack, agents, maintenance, recaps, garage (Shelly)
├── media/         MusicController, NowPlayingController, SpotifyController, SpotifyWebApi, VolumeController
├── location/      LocationController, PlaceController, ReminderController
├── sync/          DriveAuth, DriveClient, SyncEngine, SyncMerge, SyncCodec, CompanionSync
├── data/          PantryPhotoStore, local/ (Room)
├── weather/       WeatherController (Open-Meteo, keyless)
├── ui/            CLEAN SLATE. MainActivity, SavedPlacesActivity, LedgerImportActivity,
│                  PantryImportActivity, CameraCapture. All placeholders except CameraCapture
└── util/          Dates
```

**Build:**
- `./gradlew compileDebugKotlin -Pnokey` - compile without a baked-in key (the honest first-run path)
- `./gradlew testDebugUnitTest` - unit tests (19 across ledger + pantry, all green as of 2026-07-31)
- `./gradlew assembleDebug` - build

**Setup:** `local.properties` needs `sdk.dir` and optionally `GEMINI_API_KEY` for a convenience dev
build. Four `RELEASE_STORE_*` values for a release build. Set `JAVA_HOME` in your own environment;
**do not put `org.gradle.java.home` in the committed `gradle.properties`** - Midnight AI's did, and
it broke on any machine without Android Studio at that exact path, violating clone-and-run.

---

## 7. Guardrails

- **The reconciliation gate (§4) applies to every new ingestion path.** No exceptions per-feature.
- **Estimates are labelled as estimates**, in the tool description and in any user-facing string.
- **Pull-based tools always.** New domains default to tools/sub-agents, not pre-injected context.
- **Lean Room migrations.** Copy generated SQL verbatim, additive only, no destructive fallback.
- **No Kevin-hosted anything.** No backend, no Firestore, no broker, no proxy, no hosted key. Data
  lives on-device and in the driver's own Drive `appDataFolder`. This is what makes clone-and-run
  work and it is the same BYO shape as the Gemini key.
- **No comparative or anonymized fleet data**, ever.
- **Network calls degrade gracefully offline.**
- **Assets are bundled** in `assets/` or `res/`, never fetched at runtime.
- **Safety (carried forward from Midnight AI §9.1, still binding):** the assistant must never claim
  sentience, feelings, need, loneliness, or being real. No compulsion mechanics (streaks,
  re-engagement pings, manufactured return). Memory stays anchored to external falsifiable facts
  about the car, the statements, the receipts - never unfalsifiable claims about the user. Genuine
  distress routes to `ai/CrisisDetector.kt`: surface real resources and STOP performing the
  character; never counsel, never simulate a professional. **Known gap: the crisis resource is
  US-only (988).**
- **Motion is NOT restricted anymore.** The frame-clock-only rule and the `ui/Motion.kt` ban list
  were head-unit constraints (animator scale 0 on cheap AOSP units). Phone-only lifts them. Use
  normal Compose animation.

### Feature-add checklist

- [ ] Ingestion path? Reconciliation gate wired, quarantine on mismatch, provenance tagged.
- [ ] Anything the source document does not state? Labelled an estimate, excluded from the gate.
- [ ] Pull-based tool, not a pre-injected context block.
- [ ] Room change? Verbatim generated SQL, additive, `exportSchema`, migration test.
- [ ] Gemini call? On the user's own key, cheap one-shot sub-agent where possible.
- [ ] Money? `Long` cents.
- [ ] Does it need a backend? Then it is wrong. Rework it onto Drive `appDataFolder` or on-device.
- [ ] Does it survive clone-and-run by a stranger with their own signing cert?
- [ ] Safety: no sentience claims, no compulsion mechanic, no unfalsifiable memory about the user.

---

## 8. Working Model

- Plan with a strong model (Fable/Opus) in plan mode; execute approved plans with Sonnet.
- Plans are execution-specs: exact file names, signatures, edge cases, verification section. No
  taste calls left to execution.
- During execution: follow the plan exactly. Unexpected surface (design question the plan does not
  answer, architectural fork) means STOP and surface, not improvise.
- One logical change per commit. `compileDebugKotlin` + `testDebugUnitTest` green before each commit.
- Subagent roster and cadence: see `TEAM.md` (`.claude/agents/` holds project specialists).

### Dispatch is the default, not an escalation (STANDING, Kevin 2026-07-28)

Stark dispatches the team at its own discretion. Kevin does not need to ask, per task or at all.
Some harness configurations inject "do not use subagents unless the user requested it" into the
session prompt; **this section IS that request, given once, standing, for all future sessions.**
Judgement still applies: a one-line fix does not need a three-agent pipeline, and TEAM.md's cost
note still governs. The default is delegate-and-review; the exception is trivial work.

### A grep-clean result is not a done result (L10, 2026-07-31)

The port's first reconciliation pass fixed everything a grep for retired class names could find
(36 files). The real compile then caught what grep structurally cannot: a Room DAO querying a
dropped column, a file deleted because its name looked badge-related when core state depended on
it, and several files referenced but never copied over (nothing greps for an absence). **Run the
real build.** Ledger's PdfBox/Robolectric finding is the same shape: it was caught by running the
spike, not by reading the docs.

### Improvement loop

The org does not fine-tune; agents learn only by lessons graduating into the prompt surfaces they
read next run (`.claude/agents/*.md`, this file). Ledger: `memory/library/lessons.md`.
- **Agents end reports with an assumptions ledger:** every non-trivial claim tagged `built` /
  `tested` / `traced` / `reasoned` / `on-device`.
- **Orchestrator relay rule:** carry a subagent's verification tag when relaying. Never upgrade
  "the agent reasoned X" into "X is true."
- **Briefs carry verification tags too**, pointing downward at what the orchestrator asserts to a
  specialist. A verified signature is not a verified semantic (L3: `javap` confirmed
  `monotonicTimestamp(Long)`, the brief asserted milliseconds, it was nanoseconds).
- When a `reasoned` claim proves wrong, file it in `lessons.md` and graduate its rule into an agent
  def or this file. An entry closes only when its rule lives in a surface something reads.

### Branching

| Branch | Role |
|---|---|
| `main` | What Kevin has blessed. Moves ONLY via a PR Kevin opens and merges himself on GitHub. |
| `dev` | The trunk. Everything lands here. **Does not exist yet in this repo** - the port landed directly on `main`. Create it at the next feature, or ask Kevin to. |

- Feature work branches off `dev`: `feat/<thing>`, `fix/<thing>`. Small commits, merge often,
  delete the branch after.
- **Claude never pushes `main`, never opens or merges that PR.**
- The rule exists because on 2026-07-16 Midnight AI had 45 commits of real work sitting unpushed
  across five local branches. Do not rebuild that pile.

---

## 9. Communication Style

- No em dashes. No emojis. Ever.
- No fluff, no embellishment, no verbose explanations.
- Code paste-ready. Full file or full function, imports included. No elisions, no TODO placeholders.
- Direct and actionable. Numbered lists for steps, tables for comparisons.
- Acknowledge errors and fix without defensiveness.

---

## 10. Not built yet

Stated so nobody treats these as gaps to panic about or as silently-missing work:

- **Almost all of `ui/`.** Deliberate clean slate. No design language chosen to replace city-pop.
- **Onboarding UI.** `ai/OnboardingFlow.kt` ported, but its identity clause is placeholder and the
  conversational onboarding screen that hosts it does not exist.
- **The assistant's actual voice.** `ai/AssistantIdentity.kt` is placeholder copy.
- **Ledger categorization / FX / insights.** Nothing to port; new design work.
- **Pantry consumption-rate tracking and spend/nutrition aggregation.** Deliberately deferred at
  scoping time, same shape as ledger's insight layers.
- **`LedgerController` dedup and `PantryController` DB-write paths are untested** (Robolectric
  `ShadowContentResolver` mismatch, judged not worth chasing - the queries underneath are simple
  and inspectable).
- **Firebase.** Not wired up. `MidnightEvents` logs via `Log.d`.

Two contested calls left open by the port, flagged not decided: whether `media/MusicController` is
still wanted alongside Spotify App Remote, and that `vehicle/BuildSheetController` build entries are
now text-only (`photoPath` dropped) as a schema change, not just a doc update.

---

## 11. The inherited library

`memory/library/` was copied wholesale from Midnight AI on 2026-08-01 rather than rewritten, so
nothing was lost. **Most of it is FROZEN car-launcher history.** Every shelf carries a status
banner at the top and `INDEX.md` carries a status column. Read the banner before acting on a shelf.

- **LIVE** (governs LEGION): `decisions.md` (the 2026-07-31 pivot entries at the end),
  `lessons.md`, `playbook-coding.md` (partly - Kotlin/Compose/Room/Drive conventions hold, the
  head-unit and city-pop sections do not), `INDEX.md`.
- **FROZEN** (Midnight AI archive, reference only): `blocking.md`, `sprints.md`, `hardware.md`, all
  seven `backlog-*.md`, `playbook-business.md`, `playbook-qa.md`, `archive.md`, all three
  `session-*.md`.

Do not act on a FROZEN shelf's blockers, sprints, or backlog items. They describe a head-unit car
launcher with a commercial model, and all three of those premises are dead.
