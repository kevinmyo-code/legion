# Nightrunner: Complete Project Context

**Last updated:** 2026-07-02 (Sprint 2 complete)  
**Status:** Active development (Sprints 0–2 shipped; Sprint 3+ planned)  
**Solo dev:** Kevin (@kevinmyo@gmail.com)

---

## I. Project Identity

**Name:** Nightrunner (ナイトランナー) — formerly Moose/Aria  
**Platform:** Android head unit (aftermarket, cheap Chinese double-DIN units running AOSP 8–10)  
**Hardware target:** 1998 Jeep Cherokee XJ (primary), BMW E46, JDM builds (secondary)  
**Product:** "The car that knows itself" — an AI companion grounded in live OBD data + compounding per-car history + Showa city-pop aesthetic

**Mascot:** Female, lofi-girl-but-Showa style (working name **Kaze**). Persona: warm, easygoing, playful, treats the driver like a trusted old friend. Default voice: **Sulafat** (warm, female). Pro illustrator ref sheet planned ($300–600).

**Killer moment:** First night drive — Lights Out mode on, cassette spinning, live OBD telemetry flowing, the companion chimes in with something you notice only she can see.

**Moat:** Live OBD pipe + stateful per-car memory + **chassis quirk index** (bundled YAML) + character bonded to that data. Nobody combines all four.

---

## II. Pricing & Distribution (LOCKED 2026-07-01)

- **$5 one-time, paid app.** User pays at install; zero billing code in the app (Play handles the charge, we handle nothing).
- **BYO Gemini API key.** User pastes their own key at first run; FirstRunScreen gate validates with a 1-token ping. No proxy, no hosted tier, no token budget.
- **Key storage:** Android Keystore AES/GCM (encrypted slot with plaintext fallback if keymaster is broken on cheap units).
- **Distribution:** Play Store primary; Gumroad/itch.io maybe v1.1.

**Killed:** Free tier, API-key proxy (Cloudflare Worker), TokenBudget session cap, photo diagnosis (deferred to phone port), iOS/CarPlay.

---

## III. Tech Stack (Do Not Deviate)

| Layer | Choice | Notes |
|---|---|---|
| **Platform** | Android, Kotlin, Jetpack Compose | Head unit = home screen; tap Cruise avatar to talk |
| **Voice AI** | Gemini Live (WebSocket STS) | Server VAD, half-duplex, ~2s latency, warm-session model (3min idle) |
| **Sub-agents** | Gemini Flash REST (one-shot) | DiagnosticAgent, SymptomAgent, MaintenanceAgent, ColdStartAgent; all with search except cold-start |
| **Local DB** | Room v1 (clean schema 2026-07-02) | obd_samples, code_events, foresight_notes, oil_analyses, chassis_quirks, maintenance_item, service_record, vehicle_spec, etc. Additive-only migrations. |
| **OBD** | ELM327 Bluetooth RFCOMM | Mutex-guarded command port; 30s telemetry cadence; 365-day sample retention |
| **Music** | MediaSession/AVRCP only | Phone over BT or any app on the unit. Spotify SDK killed. |
| **Nav** | Google Maps / Waze intent only | Embedded nav killed. NavState tracks last destination. |
| **Images** | Gemini gemini-2.5-flash (Nano Banana) | AvatarStudio for portrait + wallpaper generation; cached locally |
| **Background** | AriaForegroundService (FGS) | Microphone type; floating overlay button removed |
| **Crash/observability** | Firebase Crashlytics + breadcrumbs | ADB logcat blocked on head unit; Crashlytics is the observability workaround |
| **Animation** | Frame-clock only (withFrameNanos) | Motion.kt helpers (animPhase, triangle, wave, rememberRamp). System animator scale = 0 on cheap units; duration-based tween = silent freeze |

---

## IV. Agentic Architecture

### The Orchestrator: GeminiLiveSession (WebSocket)

```
WebSocket → Gemini Live STS model
  ├─ buildBaseInstruction() [cached 2min]
  │   └─ Kaze persona + system rules + tool declarations
  ├─ buildLiveContext() [fresh]
  │   └─ current location, now-playing, current vehicle, driver name, car context
  ├─ On tool call: dispatch(name, args) → LiveToolbox
  └─ On audio end or silence: cleanup or idle-warm for 3min instant-resume
```

**Key patterns:**
- **Lean prompt:** base ~500 tokens (cached), live context ~200 tokens (fresh). Heavy lifting → tools.
- **Pull-based tools:** Vehicle data, OBD reads, memories, history all queried on-demand, never pre-injected.
- **Half-duplex:** Server VAD handles end-of-speech detection; driver taps Cruise avatar to start, mic mutes during playing.

### The Tool Layer: LiveToolbox (~35 voice tools)

Tools are declared in `LiveToolbox.declarations()` as a JSON array of function objects. Dispatch happens in `LiveToolbox.dispatch(context, name, args)`.

**Data tools (read-only, zero tokens):**
- `get_health` — OBD snapshot: voltage, coolant, code count
- `get_vehicle_data` — single live OBD metric (rpm, speed, temp, etc.)
- `get_trend` — obd_samples aggregation: min/max/avg over N days + trend comparison
- `get_mpg` — current-drive + lifetime + recent per-drive measured MPG
- `check_readiness` — live emissions-monitor states (inspection readiness)
- `recall_memory` — keyword grep over memory table
- `list_car_tasks`, `list_build_history`, `get_spend` — DB reads
- Plus ~15 more (look_up_vin, get_specs, check_recalls, etc.)

**Action tools (zero tokens, side effects):**
- `control_music`, `control_volume`, `start_navigation`, `show_app` — controller calls
- `remember`, `add_car_task`, `log_build_entry`, `tag_place`, `set_reminder` — DB writes
- Plus ~10 more

**Sub-agent delegation (one-shot REST, 200–500 tokens per call):**
- `diagnose_codes` → `DiagnosticAgent.diagnose(label, codes, question)`
  - System: "Explain these codes in character for this exact year/make/model"
  - Search: yes (Google for model-specific failure modes)
- `triage_symptom` → `SymptomAgent.triage(label, readings, codes, symptom)`
  - System: "Rank likely causes by urgency; note what would help narrow it down"
  - Search: yes
- `ask_maintenance` → `MaintenanceAgent.answer(label, mileage, items, history, question)`
  - System: "What maintenance is due now? If they ask how to do it, search for procedures/torque/fluids for this model"
  - Search: yes
- `check_cold_start` → `ColdStartAgent.analyze(label, latest_burst, prior_bursts)`
  - System: "Judge warm-up health: idle stability, trim behavior, warm-up rate. Note if anything is drifting."
  - Search: no (pure reasoning over OBD numbers)

**UI-scoped (caller handles dispatch):**
- `show_saved_places` — returns null; the Cruise screen owner decides what to do

### Sub-Agent Template: SubAgent.kt

```kotlin
class SubAgent(
    systemInstruction: String = "",
    useSearch: Boolean = true,
    model: String = "gemini-3.1-flash-lite",
)

suspend fun ask(context: String, question: String): String?
```

Calls `https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={user-key}` with:
- `systemInstruction` (optional, cached)
- `contents` (user prompt = context + question)
- `tools` (optional google_search tool if `useSearch=true`)

Returns extracted text or null on failure.

### Background Loops: AriaForegroundService

| Name | Cadence | Role |
|---|---|---|
| **TelemetryRecorder** | 30s (engine-on only) | Samples 9 PIDs into obd_samples; MPG accumulation (MAF ÷ AFR14.7 ÷ 2801 g/gal); cold-start bursts (<40°C coolant = 60s burst at 10s cadence); 365-day purge; busy-gate (voice wins the port) |
| **startHealthMonitor** | 5min scan | DTC baseline tracking; fresh-code detection → speaks proactive alert + writes code_events w/ Mode-02 freeze frame; overheat threshold 110°C |
| **startDriveMonitor** | 1min scan | Drive-state logic (started/stopped/milestone 10k mi/break suggestion 2hr); idle chatter cadence (45min quiet, 60min cooldown, 30% chance) |
| **startArrivalMonitor** | 20s scan | Arrival detection (Google Places check-in); triggers reminder proactives (waits up to 30s for busy conversation) |
| **Weather loop** | 30min refresh / 60s retry | Keeps weather warm for proactive context |

---

## V. Data Layer: Room Schema (v1, clean 2026-07-02)

**Core tables:**

| Table | Purpose | Key Fields |
|---|---|---|
| `obd_samples` | Time series of PID readings (30s cadence, 365-day retention, ~18 MB/yr) | vehicleId, pid, value, unit, timestamp, lat, lng |
| `code_events` | DTC snapshot + freeze frame at code-set time | vehicleId, timestamp, mileage, codesJson, freezeFrameJson |
| `foresight_notes` | Nightly aggregation observations (e.g., "coolant runs 6°F hotter this month") | vehicleId, generatedAt, content, pidsJson, dismissed |
| `oil_analyses` | Blackstone UOA results + user entry | vehicleId, date, viscosity, tbn, iron, notes |
| `chassis_quirks` | Per-vehicle known quirks (bundled YAML) | vehicleId, code, title, description, firstNoticed |
| `maintenance_item` | Scheduled service interval (oil change every 5k or 6mo, etc.) | vehicleId, name, intervalMiles, intervalMonths, lastDoneMileage, lastDoneDate |
| `service_record` | Maintenance log (when you did the work) | vehicleId, date, service, mileage, cost, notes |
| `vehicle_spec` | Year/make/model/VIN details + cached NHTSA recall list | vehicleId, vin, year, make, model, powertrain, recalls |
| `vehicle` | Per-vehicle identity + odometer baseline | obdMac (PK), vin, year, make, model, odometerBaseline, tripMilesSinceBaseline, etc. |
| `car_task` | Voice-managed to-do list | id, vehicleId, task, done, created |
| `build_entry` | Modification/repair log (Build Card) | vehicleId, date, category, part, cost, notes |

**All migrations from v1 onward are additive-only. Copy generated SQL verbatim. No destructive fallbacks ever.**

---

## VI. Current Priorities (Sprints 0–6)

### ✅ Sprint 0 — DONE 2026-07-02 (code)
- Package rename: `com.kevin.nightrunner` (dirs still `com/kevin/aria`, Kotlin doesn't care)
- Room reset: clean v1, nightrunner_database, all five new tables
- AriaPalette wired: 4 Showa colorways + Appearance picker

**Open (non-code, parked):** mascot art (self-draw vs GenAI), trademark/name check, bundled assets, Firebase re-register for new package

### ✅ Sprint 1 — DONE 2026-07-02
- Billing: **PAID APP** ($5 Play charge, zero code)
- FirstRunScreen key gate: katakana cold-open, paste, 1-token validation ping, SAVE-ANYWAY on network error only
- KeyVault: Android Keystore AES/GCM, legacy plaintext migrate-on-read, plaintext fallback if keymaster broken
- Deleted: proxy routing, TokenBudget, cloudflare-worker scaffold, MOOSE references
- Defaults: **Kaze** (female, Showa persona), voice **Sulafat**

### ✅ Sprint 2 — DONE 2026-07-02 (hardware-unvalidated)
- ObdResponseParser: +7 PID parsers (load, trims, speed, IAT, MAF, fuel), readiness (Mode-01-PID-01), freeze-frame extractor (Mode-02). 36 unit tests, zero failures.
- TelemetryRecorder: 30s obd_samples loop (engine-on gate, busy-gate, 3-fail dropout, 365-day purge), MPG (MAF integration), cold-start burst (<40°C, 10s cadence), drive finalize + lifetime aggregates in prefs
- Freeze frames: code_events rows w/ Mode-02 snapshot on new codes (+ once for pre-existing per install)
- 4 new tools: `get_trend` (pure aggregation), `get_mpg`, `check_readiness`, `check_cold_start` → new ColdStartAgent (no-search)

### → Sprint 3 — Content layer (next)
- Chassis-quirk bundled assets + parsing; QuirkAgent + tools
- Oil-analysis form + trend tools
- Foresight nightly aggregation (Gemini-as-reasoner over obd_samples trends)

### Sprint 4 — Distribution
- Logbook CODES tab integration
- Build Card export (watermark + QR)
- Cinematic mode (Build-Card B-roll capture)
- Crashlytics + minimal event log

### Sprint 5 — Charm
- Custom fonts (AriaType)
- UI sound catalog (AriaSounds WAVs)
- Ritual micro-animations (page flip, cassette click, stamp thud, odometer roll)
- Voice sample clips

### Sprint 6 — Wrapped + launch
- Nightrunner Wrapped aggregation + poster export
- Hardware validation pass (Cherokee)
- Creator seeding outreach

---

## VII. Frozen Decisions (Do Not Reopen)

| Decision | Status | Reason |
|---|---|---|
| Embedded navigation | ❌ KILLED 2×| Maps/Waze intent only. Google won't license embedded turn-by-turn except gated Nav SDK. Only free embedded = Mapbox (dropped). |
| Spotify SDK | ❌ KILLED | 5-user dev cap; 250k MAU + business entity for extended quota. MediaSession/AVRCP only. |
| Photo diagnosis | ⏸️ DEFERRED | Needs phone port (head unit has no camera). |
| API-key proxy + TokenBudget | ❌ DELETED | Incompatible with $5 one-time BYO-key pricing. |
| Free tier | ❌ KILLED | $5 one-time only. |
| Mood/emotion engine | ❌ REMOVED 2026-06-20 | Character lives in system prompt only. |
| Wake word ("Hey Moose") | ❌ REMOVED 2026-06-20 | PTT-only (tap Cruise avatar). |
| iOS / CarPlay | ❌ DROPPED | iOS = $99/yr + entitlement maze; CarPlay = Apple templates only (no custom UI, no Showa aesthetic, no cassette deck). |
| Floating overlay button | ❌ REMOVED 2026-07-01 | Nightrunner = head-unit home screen. Talk = tap avatar or long-press in Lights Out. |
| Google Calendar + OAuth | ❌ REMOVED | Not needed for v1. |
| Local LLM (Jetson/RK3588) | ⏸️ ROADMAP | Later tier. Gemini Live is the current orchestrator. |

---

## VIII. Design Language & UI

**Aesthetic:** Retro-futuristic, late-Showa city-pop. "Stardew Valley philosophy" — out-charm corporate, don't out-polish it.  
**Anchors:** Hiroshi Nagai, Eizin Suzuki, Tatsuyuki Tanaka, FM STATION covers, Night Tempo/Pacific Breeze cassettes.  
**Reference images:** `C:\Users\kevin\OneDrive\Pictures\UI Style References`

**Night surface (AriaColors):**
- Background: `0xFF0D1418` (dark navy)
- Amber phosphor: `0xFFFFB02E`
- Neon magenta: `0xFFFF2E97`
- Cyan: `0xFF35E0E6`
- Warm off-white text

**Paper surface:** Aged cream, sepia ink, stamp red, patina, chrome binder hardware (constant across all palettes)

**Lights Out (v3, 2026-07-02):** City-pop night — dim amber numerals + dim magenta trim on pure black, backlight 4%, minimal photons. +RPM gauge.

**Rules:**
- ONE cohesive material (not a patchwork of styles)
- UI sounds on interactions, not car actions
- Driving surfaces = SPARE, parked surfaces = RICH
- Widgets are physical objects (cassette/vinyl have own colorways, don't re-theme)
- Frame-clock motion only (no duration-based tweens on cheap head units)

**Current UI Map:**
- **CruiseScreen** — home/launcher: clock + weekday kanji, dock tiles (TAPE/VINYL/EQ), deck object, track info, transport row, portrait avatar (tap = talk), telemetry strip, CODES pull tab, gear (settings), lights-out/app-tray buttons
- **LightsOutScreen** — night HUD: clock, magenta shimmer, gauges, now-playing, conversation caption; tap wakes, long-press talks
- **ScreenSaverScreen** — idle Ken Burns wallpaper + breathing avatar + track bar (configurable timeout)
- **DtcSheet** — readable code list + DiagnosticAgent.describeCodes (cached as dtc_descriptions.json)
- **LogbookScreen** — binder tabs: LOG / TO-DO / MODS / SPECS / ALBUM
- **ControlPanelScreen** — flat settings list (Appearance, About, Reset, Test Voice, etc.)

---

## IX. Guardrails & Patterns

### Hard Rules (Enforce Ruthlessly)

1. **Pull-based tools always.** New domains default to tools/sub-agents, NOT pre-injected context blocks.
2. **Lean Room migrations.** Copy generated SQL verbatim, additive only, NO destructive fallback ever.
3. **Companion = global** (app install). Per-car data keyed by OBD MAC (VIN-key migration planned).
4. **No StateFlow Bitmaps.** Decode at render time with inSampleSize.
5. **Warm-session model.** Live socket idles 3min post-conversation for instant resume.
6. **Split system instruction:** buildBaseInstruction() (cached 2min) + buildLiveContext() (fresh).
7. **Frame-clock motion only.** All UI animation via Motion.kt (animPhase/triangle/wave), NOT Compose tween/infiniteTransition.
8. **No real ML.** Predictions = Gemini-as-reasoner over compressed trend summaries. Never frame as "AI predicts" — the mascot *notices*.
9. **No backend.** Everything on-device + user's own Gemini key. Zero comparative/anonymized data collection.

### When Adding Features: Checklist

- [ ] Pull-based tool, or are you pre-injecting context? (use a tool)
- [ ] Room change? (verbatim generated SQL, additive only)
- [ ] Gemini call? (goes on the user's key; keep one-shot sub-agents cheap, cache results on disk where sensible)
- [ ] Network call? (degrade gracefully offline)
- [ ] UI? (use AriaColors/AriaType/AriaDimens tokens; frame-clock motion only)
- [ ] Asset? (bundle in assets/ or res/, never fetch at runtime)
- [ ] On the frozen list? (refuse and flag)
- [ ] Voice tool? (snake_case verb_noun, one responsibility, update tool map in MOOSE_PROJECT_CONTEXT.md)

### Communication Style (Match These Preferences)

- **No emojis.** Ever.
- **No fluff, no embellishment, no verbose explanations.**
- **Code paste-ready.** Full file or full function, imports included, no "..." elisions.
- **Direct and actionable.** Numbered lists for steps, tables for comparisons.
- **Acknowledge errors and fix without defensiveness.**

### When Building: Execution Pattern

1. **Plan with a strong model (Fable/Opus)** in plan mode. All taste calls in the plan (exact hex values, dp sizes, file names, layout element-by-element, edge cases).
2. **Execute exactly.** Follow the plan. If something unexpected surfaces (weird device behavior, design question the plan doesn't answer), STOP and surface it.
3. **Build + tests green before each commit.** One logical change per commit.
4. **Keep CLAUDE.md current** as the executor guardrail.

---

## X. Hardware Status

**Validated on-device (Cherokee + ELM327):**
- Voice loop (PTT → Gemini Live STS → audio out)
- OBD codes parsed
- Weather chat
- Spanish tutoring (confirms STS works on-device)

**Blocked:**
- ADB logcat — dev options hidden on vendor menu; head-unit brand/model unknown

**Pending (unproven on device):**
- Sprint 1 first-run flow (FirstRunScreen key gate, KeyVault on head-unit keymaster)
- Sprint 2 telemetry (obd_samples rows while idling, new PIDs on 1998 XJ, Mode 02 freeze frames, MPG_TRIP, cold-start burst, check_readiness states)
- Phone BT music transport + AVRCP album art
- Telephony HFP call-state
- Launcher set-as-home behavior
- Lights Out backlight drop
- Waze launch via intent
- Recall proactive announce
- Full-duplex audio

---

## XI. Codebase Navigation

**Key files:**

| File | Role |
|---|---|
| `ai/GeminiLiveSession.kt` | WebSocket orchestrator; system instruction building |
| `service/LiveToolbox.kt` | Tool declarations + dispatch (~35 tools) |
| `vehicle/TelemetryRecorder.kt` | 30s obd_samples pipeline, MPG, cold-start burst |
| `vehicle/ObdBluetoothManager.kt` | ELM327 Bluetooth, 9 PID getters, getFreezeFrame() |
| `vehicle/ObdResponseParser.kt` | OBD response parsing (36 unit tests) |
| `vehicle/DiagnosticAgent.kt`, `SymptomAgent.kt`, `MaintenanceAgent.kt`, `ColdStartAgent.kt` | Sub-agent workers (one-shot REST) |
| `ai/SubAgent.kt` | SubAgent template (systemInstruction + ask pattern) |
| `ai/KeyVault.kt` | Android Keystore AES/GCM encryption |
| `ui/FirstRunScreen.kt` | Key paste gate + validation |
| `service/AriaForegroundService.kt` | Background loops (telemetry, health, drive, arrival, weather) |
| `data/local/CarDatabase.kt` | Room schema (obd_samples, code_events, foresight_notes, etc.) |
| `ui/CruiseScreen.kt` | Home/launcher UI |
| `ui/LightsOutScreen.kt` | Night HUD |
| `ui/Motion.kt` | Frame-clock animation helpers |
| `ui/theme/AriaPalette.kt` | Showa colorway picker |

**Build:**
- `gradlew assembleDebug` — build
- `gradlew testDebugUnitTest` — unit tests (36 parser tests, etc.)

---

## XII. External Context

**User email:** kevinmyo@gmail.com  
**Memory system:** `C:\Users\kevin\.claude\projects\C--Users-kevin-AndroidStudioProjects-AiApp\memory\`
- `MEMORY.md` — index of active memory files
- Individual `.md` files for specific topics (nightrunner-rebrand, nightrunner-pricing-gtm, aria-hardware-status, etc.)

**Handoff files:**
- `CLAUDE.md` — executor guardrail (frozen decisions, architecture, communication style, priorities)
- `MOOSE_PROJECT_CONTEXT.md` — legacy handoff doc with detailed tool map and vehicle-controller notes
- `NIGHTRUNNER_CONTEXT.md` — this file (complete one-file reference)

---

## XIII. For the Next Agent

You have everything you need in this file. If you need to:
- **Implement a feature:** Check the "When Adding Features" checklist (IX), read the relevant `.kt` file, and follow the execution pattern.
- **Understand the agentic flow:** Read section IV (Agentic Architecture).
- **Check what's frozen:** Read section VII.
- **Understand OBD/telemetry:** Read sections V (Data Layer) and VI (Background Loops), then look at `TelemetryRecorder.kt`.
- **Debug hardware issues:** Check section X (Hardware Status).
- **Understand the design:** Read section VIII (Design Language & UI).

**Do not reopen frozen decisions. If you think something should change, flag it explicitly.**

---

*This is the single source of truth for Nightrunner. Keep it up-to-date as decisions change.*
