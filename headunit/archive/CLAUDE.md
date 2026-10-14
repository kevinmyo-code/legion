# CLAUDE.md — Midnight AI Project Agent Instructions

You are assisting Kevin on **Midnight AI** (ミッドナイト AI, formerly Nightrunner/Moose/Aria), an Android in-car AI companion for aftermarket head units in classic/enthusiast cars (1998 Jeep Cherokee XJ, BMW E46, JDM builds). Solo developer project. Hardware-validated on the Cherokee.

Always check `memory/MEMORY.md` before responding for current backlog state; `MOOSE_PROJECT_CONTEXT.md` is the legacy handoff doc (predates the rebrand). The 2026-07-01 brainstorm summary is the canonical direction doc: `C:\Users\kevin\Downloads\brainstormsession070126.txt`.

---

## Working Model (how Kevin runs sessions)

- **Plan with a strong model (Fable/Opus) in plan mode; execute approved plans with Sonnet.**
- Plans must be execution-specs: exact hex values, dp sizes, file names, layout element-by-element, edge cases, and a verification section. Do not leave taste calls to execution time.
- During execution: follow the plan exactly. If something unexpected surfaces (weird device behavior, a design question the plan doesn't answer, an architectural fork), STOP and surface it rather than improvising — that goes back to a planning pass.
- One logical change per commit; build (`gradlew assembleDebug`) + unit tests green before each commit.
- Team roster + orchestration cadence: see TEAM.md (.claude/agents/ holds project specialists).

---

## Project Identity

- **Product:** "the car that knows itself" — AI companion grounded in live OBD data + compounding per-car history + late-Showa city-pop aesthetic
- **Mascot:** FEMALE, lofi-girl-but-Showa city-pop style (name: Yoko; a marketable character, not a placeholder). Professional illustrator reference sheet planned ($300–600).
- **Target user:** JDM/affordable-enthusiast tribe, out-of-warranty cars, cheap Chinese double-DIN Android head units
- **Moat:** live OBD pipe + stateful per-car memory + chassis quirk index + character bonded to that data. Nobody else combines them.
- **Killer moment:** first night drive — Lights Out on, cassette spinning, the companion chimes in
- **Distribution wedge:** enthusiast YouTubers using the Cruise screen as cinematic B-roll; shared Build Cards with install QR

---

## Pricing (LOCKED 2026-07-01 — do not reopen)

- **$5 upfront, one-time. No tiers, no unlocks, no subscription. BYO Gemini API key required.**
- Billing model (decided 2026-07-02): **PAID APP** — Play charges $5 at install. ZERO billing code in the app; never propose BillingClient/IAP.
- The API-key proxy server and per-user token budget were **DELETED 2026-07-02** — never propose them.
- Distribution: Play Store primary; Gumroad/itch.io maybe v1.1.

---

## Tech Stack (do not deviate without explicit approval)

- **Platform:** Android (Kotlin, Jetpack Compose)
- **Voice AI:** Gemini Live API (WebSocket STS, server VAD, half-duplex) — the orchestrator
- **Sub-agents:** Gemini Flash REST one-shot workers (`SubAgent.kt`): DiagnosticAgent, MaintenanceAgent, SymptomAgent (+ planned QuirkAgent, foresight, cold-start)
- **Local DB:** Room — clean v1 (`nightrunner_database`, reset 2026-07-02 with the package rename) incl. obd_samples, code_events, oil_analyses, chassis_quirks, foresight_notes. From here: verbatim generated SQL, additive-only migrations.
- **BYO key:** FirstRunScreen gate (paste + 1-token validation ping) → KeyVault (Android Keystore AES/GCM; NOT the deprecated security-crypto) → GeminiKeyProvider cache. Direct-to-Google only.
- **OBD:** ELM327 Bluetooth RFCOMM, mutex-guarded command port
- **Music:** MediaSession/AVRCP only (phone over BT or any app on the unit). Spotify SDK is KILLED.
- **Nav:** Google Maps / Waze via intent only. Embedded nav is KILLED. NavState tracks the last-sent destination for context.
- **Images:** Gemini gemini-2.5-flash-image (Nano Banana) via AvatarStudio
- **Background:** AriaForegroundService (FGS microphone type). The floating overlay button is REMOVED — the app is the head unit's home screen; talk = tap the Cruise avatar (or long-press in Lights Out).
- **Crash/observability:** Firebase Crashlytics + MooseEvents breadcrumbs (ADB logcat is blocked on the head unit)

---

## Architecture North Star

**"Build like Claude Code"** — lean orchestrator prompt (the Live STS session), pull-based tools and sub-agents instead of dumping car data into context every turn.

### Hard Architectural Rules
- **Pull-based tools always.** New domains default to tools/sub-agents, NOT pre-injected context blocks.
- **Lean Room migrations.** Copy generated SQL verbatim, additive only, NO destructive fallback ever. (Moot briefly at the v1 reset, then applies again.)
- **Companion = global** (app install). Per-car data keyed by OBD MAC (VIN-key migration planned).
- **No StateFlow Bitmaps.** Decode at render time with inSampleSize.
- **Warm-session model.** Live socket idles 3 min post-conversation for instant resume.
- **Split system instruction:** buildBaseInstruction() (cached 2 min) + buildLiveContext() (fresh).
- **Frame-clock motion only.** All UI animation goes through `ui/Motion.kt` (`animPhase`/`triangle`/`wave`/`rememberRamp`), NOT Compose tween/infiniteTransition — head units ship with the system animator scale disabled, which silently freezes duration-based animations. CI-check by grep: `AnimatedVisibility`/`tween(`/`infiniteTransition`/`animate*AsState`/`Crossfade` are banned in `ui/` (Motion.kt excepted).
- **No real ML.** Predictions = Gemini-as-reasoner over compressed trend summaries (foresight notes). Never frame as "AI predicts" — the mascot *notices*.
- **No backend.** Everything on-device + user's own Gemini key. No comparative/anonymized data collection.

---

## Frozen / Killed Decisions (DO NOT REOPEN unless Kevin explicitly says so)

- ❌ Embedded navigation (Stadia/MapLibre/Google Nav SDK/Mapbox) — KILLED 2026-07-01, twice. Maps/Waze intents only.
- ❌ Spotify SDK (App Remote + Web API) — KILLED (5-user dev cap; 250k MAU + business entity for extended quota). MediaSession/AVRCP only.
- ❌ API key proxy + token budget — OBSOLETE under BYO-key pricing.
- ❌ Free tier — killed; $5 one-time only.
- ❌ Photo diagnosis — DEFERRED until a phone port exists (head unit has no camera).
- ❌ Real ML (TF Lite, training pipelines) — Gemini-as-reasoner instead.
- ❌ Comparative anonymized fleet data; brand-specific enhanced PIDs (Carly's turf).
- ❌ Local LLM on Jetson/RK3588 — roadmap, not current build.
- ❌ iOS / Apple CarPlay — dropped entirely. NOTE: "CarPlay-style split" was reinterpreted 2026-07-04 as **Split-Screen Companion Mode** — letting another *Android* app (nav/music) take part of the head-unit screen while Midnight AI holds the rest. That is now a SHIPPED feature (see below). Apple CarPlay/iOS itself stays killed.
- ❌ Wake word — PTT/tap only.
- ❌ Google Calendar / OAuth / play-services-auth — removed.
- ❌ Mood/emotion engine — character lives in the system prompt only.
- ⚠️ Floating overlay button — removed 2026-07-01. REVIVED 2026-07-04 (Kevin-authorized) NOT as the old talk button but as the **split-screen companion panel**: a `TYPE_APPLICATION_OVERLAY` window (`CompanionPanelController` + `OverlayOwners`) hosting the compact HUD, the reliable fallback where a head unit lacks native multi-window. The old always-on talk-button overlay stays killed (talk = tap the Cruise avatar).

## Split-Screen Companion Mode (SHIPPED 2026-07-04, hardware-unvalidated)

- Opt-in; full-bleed Cruise stays the default (protects the killer moment). Entered from the Cruise **◧** button (tap opens the remembered side app beside us, long-press re-picks) or by voice.
- Two paths, picked per device (Appearance → Split screen: AUTO/NATIVE/PANEL/OFF): **native multi-window** (`FLAG_ACTIVITY_LAUNCH_ADJACENT`, `resizeableActivity=true`, Cruise reflows to `CompactCruise` via `BoxWithConstraints` when `maxWidth < 480.dp`) and the **floating panel** fallback (needs SYSTEM_ALERT_WINDOW; Setup grant row).
- Everything routes through `SplitLauncher.open(context, intent)` (AUTO = try adjacent, fall back to panel via `SplitState`). Voice: `start_navigation` and the new `open_music` tool open nav/music INTO the split so the companion stays beside them; phone-BT streaming + `control_music` transport are untouched.
- The compact HUD (`CompactCruise` in CruiseScreen.kt) is shared by both paths (`CompanionHud` collects state for the panel). Driving surface: SPARE, tokens only, frame-clock only.

If Kevin asks to revisit any of these, remind him it's frozen and ask whether this is intentional re-opening.

---

## Current Priority Stack (post-brainstorm 2026-07-01)

1. **Sprint 0 — DONE 2026-07-02 (code):** package = com.kevin.nightrunner; Room clean v1 incl. all five new tables; AriaPalette wired. Open (non-code): mascot art (self-draw vs GenAI, illustrator parked), trademark/name checks, bundled assets/, re-register Firebase for the new package (google-services.json is a placeholder).
2. **Sprint 1 — DONE 2026-07-02:** paid-app billing (no code); FirstRunScreen key gate + validation ping; KeyVault encrypted key storage; proxy/TokenBudget deleted; defaults = Yoko/Sulafat. Hardware-unvalidated.
3. **Sprint 2 — DONE 2026-07-02:** TelemetryRecorder (30s obd_samples while engine runs), Mode-02 freeze frames into code_events, MPG from MAF+GPS, get_trend/get_mpg/check_readiness/check_cold_start tools, ColdStartAgent. Hardware-unvalidated.
4. **Sprint 3 — Content layer:** chassis quirk index (bundled YAML → Room) + QuirkAgent; UOA (Blackstone) entry + trends; foresight nightly aggregation.
5. **Sprint 4 — Distribution:** logbook CODES integration, Build Card export (watermark + QR), cinematic mode, event log.
6. **Sprint 5 — Charm:** bundled fonts, AriaSounds WAVs, ritual micro-animations, voice sample clips.
7. **Sprint 6 — Wrapped + launch:** Midnight AI Wrapped, hardware validation pass, creator seeding.

Check `memory/MEMORY.md` for live status before assuming order.

---

## Communication Style (match these preferences)

- **No emojis.** Ever.
- **No fluff, no embellishment, no verbose explanations.**
- **Code paste-ready.** Full file or full function, imports included, no "..." elisions, no TODO placeholders.
- **Direct and actionable.** Numbered lists for steps, tables for comparisons.
- **Acknowledge errors and fix without defensiveness.**
- **Business-audience switches:** for manager/stakeholder drafts, drop jargon; otherwise stay technical and dense.

---

## When Adding Features — Required Checks

- [ ] Pull-based tool, or are you pre-injecting context? (use a tool)
- [ ] Room change? (verbatim generated SQL, additive only)
- [ ] Gemini call? (goes on the user's key; keep one-shot sub-agents cheap, cache results on disk where sensible — see dtc_descriptions.json pattern)
- [ ] Network call? (degrade gracefully offline)
- [ ] UI? (AriaColors/AriaType/AriaDimens tokens; frame-clock motion only)
- [ ] Asset? (bundle in assets/ or res/, never fetch at runtime)
- [ ] On the frozen list? (refuse and flag)
- [ ] Voice tool? (snake_case verb_noun, one responsibility, update the tool map in MOOSE_PROJECT_CONTEXT.md)

---

## Design Language (enforce ruthlessly)

- **Aesthetic:** retro-futuristic late-Showa city-pop; "Stardew Valley philosophy" — out-charm corporate, don't out-polish it. Anchors: Hiroshi Nagai, Eizin Suzuki, Tatsuyuki Tanaka, FM STATION covers, Night Tempo/Pacific Breeze cassettes.
- **Coherence law:** every surface reads like a frame from the same 1988 city-pop cassette broadcast. Night palette = AriaColors/AriaPalette tokens only (no ad-hoc hex in screen code; widget colorways are the one exception, as named sets in the widget file). Character art = 90s cel anime. On-media captions = cream lower-third bar. Katakana as the secondary label line. The dual-offset cream display type is a BRAND moment (FirstRun cold-open, Build Card, Wrapped) — never body UI.
- **Reference images:** `C:\Users\kevin\OneDrive\Pictures\UI Style References` — re-read before generating art or designing surfaces. Anchors: `3.png` = **character/Yoko art direction** (1980s–90s Japanese cel anime — flat cel shading, bold lineart, large reflective eyes; the `AvatarStudio.STYLE_DNA` constant encodes it; NOT modern moe / 3D / painterly); `casette reference.png` = widget shells; `Binder style.jpg` (FM STATION/Eizin Suzuki) = logbook masthead; `Screenshot 2026-07-01 221037.png` = ScreenSaver + cream lower-third caption bar; `maxresdefault.jpg` = brand display type.
- **On-media captions:** cream lower-third bar, dark ink, optional katakana secondary line — never floating neon text over imagery (Lights Out is exempt; minimal-photons rule wins there).
- **Night surface (AriaColors):** background 0xFF0D1418, amber phosphor 0xFFFFB02E, neon magenta 0xFFFF2E97, cyan 0xFF35E0E6, warm off-white text. A user-selectable palette system (curated Showa colorways incl. SHOWA PURPLE; never a free color picker) is drafted in `theme/AriaPalette.kt` — pending approval/wiring.
- **Paper surface:** aged cream, sepia ink, stamp red, patina, chrome binder hardware — constant across any night palette.
- **Lights Out:** city-pop night — dim amber numerals + dim magenta trim on pure black, backlight to 4%, minimal photons.
- **Rules:** ONE cohesive material; UI sounds on interactions, not car actions; driving surfaces SPARE, parked surfaces RICH; widgets are physical objects (cassette/vinyl shells have their own colorways and don't re-theme).
- **Animated wallpaper (approved exception, Kevin 2026-07-04):** the shared `ui/AmbientWallpaper.kt` backdrop (Ken Burns drift + diagonal light sheen + drifting light specks, all frame-clock) runs always-on behind BOTH the Cruise screen and the screen saver. Kevin explicitly authorized animating the Cruise *background layer* despite "driving surfaces SPARE" — the HUD/controls on top stay spare; only the backdrop moves. Do not revert to a static Cruise backdrop without asking.

---

## Current UI Map (2026-07-02)

- **CruiseScreen** — home/launcher: clock + weekday kanji, dock tiles, TAPE/VINYL/EQ picker, deck object + prominent track + transport row (− ◀◀ ▶ ▶▶ ＋, volume meter), portrait avatar (tap to talk), telemetry strip (GPS speed + OBD rpm/temp/volts), CODES pull tab, gear + logbook binder, lights-out + app tray buttons.
- **LightsOutScreen** — night HUD: clock, magenta shimmer, gauges, now-playing, conversation caption; tap wakes, long-press talks.
- **ScreenSaverScreen** — idle Ken Burns wallpaper + breathing avatar card + track bar (configurable timeout).
- **DtcSheet** — readable code list; DiagnosticAgent.describeCodes + disk cache; ASK MOOSE handoff.
- **LogbookScreen** — binder tabs: LOG / TO-DO / MODS / SPECS / ALBUM (polaroid grid, CITY-POP IT, set-wallpaper).
- **ControlPanelScreen** — flat settings list; AvatarStudio portraits derive automatically for the Cruise avatar.

---

## Final Rule

When in doubt, **ship the killer moment first**: the first night drive — Lights Out, cassette turning, live OBD telemetry, the companion chiming in — more reliable, more beautiful, or easier to share. Everything else is secondary.
