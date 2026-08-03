# Library Index

Card catalog for `memory/library/`. One line per file: what it holds, whether it governs LEGION,
and when it last changed. Rules: no content in this file, pointers only. The librarian bumps the
Updated date on every FILE run and adds a line for every new file. If a file is not listed here it
does not exist.

**The library was copied wholesale from MIDNIGHT_AI on 2026-08-01** rather than rewritten, so
nothing was lost. Most of it is frozen car-launcher history. Every shelf carries a status banner at
the top. Read the banner before acting on a shelf.

- **LIVE** - governs LEGION. Act on it.
- **PARTLY LIVE** - some sections survive the pivot, some do not. The shelf's own banner says which.
- **FROZEN** - Midnight AI archive. Reference for why something was built a certain way. Do not act
  on its blockers, sprints, backlog items, or hardware notes.

| File | Status | What it holds | Updated |
|---|---|---|---|
| decisions.md | **LIVE** (frozen tail) | The 2026-07-31 pivot entries govern LEGION: multi-aspect pivot carry-over inventory ruled on (5 calls), the ledger port, the pantry port. 2026-08-02: ledger sync table registration, ticket 07 theme fix, IngestScanner architectural move. Everything before 2026-07-31 is Midnight AI strategy history (pricing, mascot, positioning, Mapbox, garage, car profiles, Fleet Hub, nav, GPS beacon, maintenance model, UI coherence) | 2026-08-02 |
| lessons.md | **LIVE** (frozen tail) | Improvement loop: agent and orchestrator failure modes plus the rule that prevents recurrence. L1-L9 are Midnight AI but the rules are platform-independent. **L10 (2026-07-31): grep-clean result is not done, run the real build. L11 (2026-08-02): mandated verification steps must not be silently skipped. L12 (2026-08-02): process-wide cache initialization must not live in a conditional service. L13 (2026-08-02): date-only values and instants must not share formatters without zone awareness.** | 2026-08-02 |
| playbook-coding.md | **PARTLY LIVE** | Codebase conventions and gotchas. Its banner carries a section-by-section table. LIVE: sub-agents, Live session, Drive v3 concurrency, tombstones, testing/singletons/composables, credential backup, maintenance model, Material 3 theme gotcha, application initialization, date handling and zone conversions. FROZEN: all UI/city-pop/settings-hub, Mapbox, image generation, flavor splits | 2026-08-02 |
| blocking.md | FROZEN | B-series blocker narratives for the head-unit app (B1-B37). Root causes may still be instructive; the blockers themselves are not LEGION's | 2026-07-29 |
| sprints.md | FROZEN | Midnight AI Sprint 0-6 status. LEGION has no sprint model yet | 2026-07-14 |
| hardware.md | FROZEN | Device validation ledger for the Cherokee head unit + ELM327 + Outlander. The head unit no longer constrains design; ADB is no longer blocked | 2026-07-28 |
| backlog-cruise.md | FROZEN | Cruise screen, deck widget, Lights Out, launcher UI. All dead with the launcher | 2026-07-16 |
| backlog-visuals.md | FROZEN | Wallpaper/avatar generation, image-gen cost control, photo albums. Dead with city-pop | 2026-07-14 |
| backlog-recaps.md | FROZEN | Wrapped family: daily logs, monthly recaps, yearly Wrapped. Controllers survive in `vehicle/`; the backlog items assume the launcher | 2026-07-16 |
| backlog-voice.md | FROZEN | Voice, onboarding, persona, prompt items. Onboarding is being rebuilt; do not mine this for the new identity | 2026-07-14 |
| backlog-obd.md | FROZEN | OBD, telemetry, emulator harness, BLE ELM327, track + drift modes. Closest of the backlogs to still-relevant, since fleet survived, but written against head-unit radio contention that no longer applies | 2026-07-16 |
| backlog-music.md | FROZEN | Music tiers, discovery, mixtape management. Mixtapes retired, tiers collapsed | 2026-07-16 |
| backlog-nav.md | FROZEN | Nav-app picker, embedded Mapbox, routing, Mapbox Geocoding API facts. Mapbox removed entirely | 2026-07-28 |
| playbook-qa.md | FROZEN | Build/install/test procedures and device quirks written for the head unit. LEGION needs its own; ADB works now | 2026-07-08 |
| playbook-business.md | FROZEN | Market, policy and naming knowledge. No commercial model exists | 2026-07-08 |
| archive.md | FROZEN | Closed or superseded Midnight AI items, historical session log | 2026-07-08 |
| session-2026-07-14-onboarding-manager.md | FROZEN | Session notes: OnboardingManager tri-modal refactor, field test, cassette redesign | 2026-07-14 |
| session-2026-07-28-gemini-audit-followups.md | FROZEN | Session notes: audit of the Android Studio Gemini pane's 3 commits, 9 fixes, lessons L4/L5 | 2026-07-28 |
| session-2026-07-29-ui-and-maintenance-builds.md | FROZEN | Session notes: nine commits, maintenance feature, UI coherence phases 1-5, lessons L7-L9 | 2026-07-29 |
| session-2026-08-02-shell-and-pipeline.md | **LIVE** | Session notes: Part 3/4 commits, ledger ingestion pipeline and app shell. Hardware verification (`tested`, `on-device`), three defects found and fixed, lesson L11 (verification step skip), residual known issues, adb operational fact | 2026-08-02 |

## Owed

- **No LEGION-native shelf exists yet.** The pivot, ledger, and pantry records live at the tail of
  the inherited `decisions.md`. Once LEGION accumulates its own history, split it: a fresh
  `decisions.md` for LEGION, the inherited one renamed to `archive-midnight-decisions.md`.
- `playbook-qa.md` needs rewriting for a phone with working ADB before Owen is dispatched.
