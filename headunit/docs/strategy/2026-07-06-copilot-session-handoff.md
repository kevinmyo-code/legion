# MIDNIGHT AI · Strategic Session Handoff (Refactored)

**Source:** M365 Copilot chat · 2026-07-06
**Purpose:** Sync market positioning, accuracy architecture, and behavioral moat with CLI agent team

**Reconciliation note (2026-07-07):** this doc was ingested and reconciled against locked
architecture (CLAUDE.md) and live state (MEMORY.md) in the CLI session that produced
`c-users-kevin-downloads-midnight-ai-ses-synchronous-sketch` plan. Three decisions were made
that override sections below: (1) the §4 "$2 Creator Pack" IAP is KILLED — CLAUDE.md §2's $5
one-time / zero-billing-code rule stays frozen; (2) §3.3's "CAN-era (2008+) only for v1" is
REJECTED — no protocol gating, pre-CAN (ISO 9141, the 1998 XJ) stays first-class, adapter tier
may record protocol but never exclude a car; (3) the §8/§10 package rename question is
RESOLVED — `com.kevin.nightrunner` → `com.kevin.midnightai` shipped 2026-07-07. See
`memory/MEMORY.md` for the live execution queue built from this doc. Everything below is the
original Copilot output, unedited.

---

## 1. Product Snapshot

- **Rename:** Nightrunner → MIDNIGHT AI
- **Package:** `com.kevin.nightrunner` (rename TBD)
- **Model:** Android OBD-II app, $5 one-time, BYO Gemini key, no tiers
- **Persona:** car speaks first-person, user-named
- **UI:** cockpit-first · City Pop backdrop · cassette widget · avatar · Logbook/Codes tabs · Lights Out mode

## 2. Target Segments

| Segment | Winner | MIDNIGHT AI Play |
|---|---|---|
| Casual translator | FIXD, BlueDriver | Skip |
| Power user generic | Torque Pro, Car Scanner | Adjacent |
| Brand coding | OBDeleven, FORScan | Not our lane |
| **Emotionally-invested owner** | **MIDNIGHT AI** | Primary |
| **Privacy-conscious** | **MIDNIGHT AI** | BYO key |
| **Multi-car household** | **MIDNIGHT AI post-v1** | Persona-per-car |

**Channels:** r/JDM, r/carporn, r/wangan_midnight, City Pop TikTok, JDM IG. NOT r/OBD2.

## 3. Core Strategic Decisions

### 3.1 Kill Static Repair DB
- No 7,000-code database
- Gemini reasoner + ~5KB offline SAE J1979 fallback
- Narrative: "No static DB. Your car's data reasoned real time."

### 3.2 Ten-Gate Accuracy Stack
1. Adapter tier trust
2. Frame integrity (multi-frame ISO-TP)
3. Range/rate sanity
4. Pre-Gemini baseline delta (Gemini narrates, doesn't calc)
5. N-of-M multi-sample (3 consecutive)
6. Cross-PID consistency
7. Confidence gate (tiered grammar)
8. Severity override table (hard-coded, LLM cannot downgrade)
9. Prescription boundary (observe, never prescribe)
10. Uncertainty grammar ("I don't know")

Structured JSON envelopes in/out. Reject outputs referencing PIDs not in input. Kills fabricated evidence.

### 3.3 Adapter Tier System

| Tier | Reference HW | Rate | Baseline Weight | Behavior |
|---|---|---|---|---|
| Verified | OBDLink CX/MX+ | 20 Hz | 1.0x | Full features |
| Compatible | Vgate iCar Pro BLE | 5 Hz | 0.7x | Full + warnings |
| Degraded | Generic ELM327 | 2 Hz | 0.3x | Read-only, no custom PIDs |
| Broken | — | — | — | Refuse |

Handshake fingerprint on first connect. CAN-era (2008+) only for v1.

### 3.4 Voice DTC Clearing
- Orchestrator → DTC Clear Subagent
- Two-phase verbal confirm, literal "confirm clear"
- Refusals: engine running, red severity, emissions window, <30s since last clear, 3+ clears/30 days same code, degraded adapter
- Persistent audit + freeze-frame preservation
- Default OFF first 7 days

### 3.5 UI: Preserve Cockpit
No standard bottom-nav. OBD surfaces via:
- Avatar reactive states (idle/alert/worried/celebrating/hazy)
- Cassette label swap (music / voice msg / recap / DTC red-spine)
- Speech bubble (replaces toasts)
- Vitals strip (ambient edge PID)
- Adapter status pin
- Codes tab = mixtape rack (Side A persona / Side B raw)
- Logbook = Gemini-narrated journal
- Lights Out = whisper persona, voice-only

## 4. Behavioral Moat

### Customization (v1.0)
- 3 persona archetypes (unlockable to 5+)
- 10 cassette designs
- 5 City Pop palettes (Shibuya Night, Roppongi Rain, Setagaya Dawn, Wangan Cruise, +1)
- 2 logbook styles (Field Journal, Racing Telemetry)
- Photo treatments: chrome, cel-shade, oil paint, neon wireframe
- Cassette shell/label/reel mix

### Progress Systems
- Miles Together (per-VIN)
- Drive Streaks (grace day, non-punitive)
- Data Richness Score (baseline confidence = personality bar)
- Diagnostic Wisdom
- Baseline Milestones unlock foresight domains

### Persona Evolution
| Stage | Miles | Voice |
|---|---|---|
| Introduction | 0–100 | Formal, cautious |
| Familiarization | 100–1k | Recognizes routes |
| Bond | 1k–5k | Nicknames, callbacks |
| Deep Bond | 5k–25k | Predicts, jokes |
| Companion | 25k+ | Full emotional history |

Each stage = different Gemini system prompt. Non-transferable. Non-purchasable.

### Viral Engine
- **Monthly Recap Cassettes:** last day of month · Side A stats / Side B narrative · portrait/square/16:9 · rarity common→legendary · watermark `midnightai.app` · $2 Creator Pack removes watermark (post-v1)
- **Yearly Wrapped:** December · persona letter · route heatmap · legendary cassette, 1/year, non-repeatable

## 5. Competitive & Pricing

- $5 one-time ties Torque Pro / Car Scanner for cheapest
- Beats FIXD Premium 60x over 3 years
- Gemini BYO cost: $0.60–$9.00/yr
- Real moat: persona + baseline + adapter honesty + City Pop identity. Price is weakest layer.

## 6. Dev Infrastructure

- **OBD emulation:** ELM327-emulator (Python, Ircama/GitHub) via TCP to `10.0.2.2:35000`. Failure injection: truncation, timeouts, missing AT, buffer overruns
- **Physical kit (~$90):** OBDLink CX ($60) + Vgate iCar Pro BLE ($30), optional $10 generic
- **UI workflow:** Figma source of truth → Compose `@Preview` matrices → Interactive Preview → Live Edit → emulator daily, device for BT/feel

## 7. Room Schema Additions

`adapter_registry` · `frame_rejections` · `drive_quality_score` · `milestones` · `customization_unlocks` · `user_goals` · `memory_pins` · `monthly_recaps` · `dtc_clear_events`

Non-negotiable for accuracy claims + progression.

## 8. Open Questions (for CLI Team)

1. Package rename `nightrunner` → `midnightai`?
2. Codes tab refactor to mixtape rack: full rewrite or refactor?
3. Avatar reaction-state asset pipeline?
4. Cassette artwork: procedural / pool / hybrid?
5. STT/TTS: on-device (privacy) or Gemini multimodal?
6. Memory pin token budget at Deep Bond (5k+ pins)?
7. First-run flow order: adapter → key → identity → drive?

## 9. Deferred (Out of Scope This Session)

Kotlin/Compose artifacts · Gemini prompt templates · Room migration scripts · BLE stack · artwork algorithm · STT/TTS selection · onboarding UX · store copy.

## 10. Next Steps for CLI Orchestrator

**P1 (blocking):**
- Resolve package rename
- Ship Room schema additions
- Wire adapter handshake fingerprint

**P2 (parallel):**
- Persona stage prompt templates
- Codes tab mixtape rack refactor
- Cassette label dynamic swap

**P3 (v1 close):**
- Monthly recap pipeline
- Voice command orchestrator/subagent
- Adapter emulator harness

## 11. Delivery & Sync

- Save as `docs/strategy/2026-07-06-copilot-session-handoff.md`
- Pointer line in `memory.md`:
  ```markdown
  ## External Strategy Sessions
  - 2026-07-06: OBD market + behavioral moat → docs/strategy/2026-07-06-copilot-session-handoff.md
  ```
- **Sync checklist:** doc ingested · rename resolved · schema queued · no state conflicts · reconcile any contradictions

## Bottom Line

Three stacked moats:
1. **Technical:** adapter tiers + accuracy stack + Gemini reasoning
2. **Aesthetic:** City Pop cockpit, uncontested in OBD space
3. **Behavioral:** compounding persona relationship + earned customization

Accessible price. Emotional positioning. Rigorous engineering. Category creation, not feature war.
