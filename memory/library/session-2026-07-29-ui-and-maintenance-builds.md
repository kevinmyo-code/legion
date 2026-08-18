---
shelf: session-2026-07-29-ui-and-maintenance-builds
status: frozen
kind: session
tags: [library]
---

# Session 2026-07-29: UI Coherence + Maintenance Schedule

> **STATUS: FROZEN ARCHIVE (banner added 2026-08-01).** This shelf is Midnight AI history: a
> head-unit car launcher with a commercial model and a city-pop design language. All three of
> those premises died in the 2026-07-30/31 pivot to LEGION. Nothing below governs LEGION.
> Read it as reference for why something was built the way it was. **Do not act on its
> blockers, sprints, backlog items, or hardware notes.** Live rules are in CLAUDE.md; live
> state is in memory/MEMORY.md. See CLAUDE.md §11.


Branch: fix/gemini-audit-followups. Date: 2026-07-29.

## Commits landed (verification status)

1. **5dbc483** Mapbox nav tap-to-expand: GesturePlugin consumed all touches; wired directly to `addOnMapClickListener`. Unverified on device.
2. **569c40a** Spotify play_music: foregrounded app instead of playing in place; now checks for active MediaSession first, falls back to system intent. Unverified on device.
3. **b251978** MainActivity launchMode: added explicit `singleTask` to prevent multi-instance creation on OAuth redirect. Unverified on device.
4. **65d0f13**, **52130fa**, **6f0fd03**, **5eae3b6**, **7174600** UI coherence passes 1-5 (Settings typography, glassmorphism removal, backdrop removal, Cruise dock rebuild, app-tray assignment). Unverified on device.
5. Maintenance-schedule feature commits (building the three-state model, dual-axis reporting, DUE tab). Unverified on device.

## What's verified / unverified

**Built + compile green:** all nine fixes above. Frame-clock motion passes, testDebugUnitTest passes.

**Never rendered on device:**
- v11→v12 Room migration (migrate11To12_addsNeverDone written, needs connectedDebugAndroidTest)
- DUE tab Logbook render
- Settings rebuilt with AriaType
- Cruise COMPACT dock (4x2 grid)
- Spotify play_music tool and in-place playback flow
- Mapbox gesture listener integration (tap-to-expand gesture)

**Generated portrait framing:** AvatarVibe promises the head frames inside the top 80.6%, unsure whether real generated art respects `TopCenter` crop bounds. Unverified against actual image-gen output.

## Open items and blockers

1. **Spotify `market` parameter missing.** The `searchTrackUri(query)` call to Spotify Web API never passes the `market` param (affects catalog availability by region). Flagged but impact unverified.

2. **APK size penalty (~95MB unused).** The build includes x86/x86_64 Mapbox libs (ARM-only head unit never uses them). ABI splits are deliberately off to install on any device. An ARM-only demo build would halve size, deferred.

3. **Phase 6 UI work deferred.** Two-pane Settings for EXPANDED (tablet view). Kevin chose to defer in favour of the one-column-works-everywhere approach.

4. **Maintenance feature not production-ready.** Three-state design is solid; bundled defaults must be seeded into the schedule. The backfill walkthrough is conversational (no persisted state). Deferred: custom-service UI, reminder proactives, history export, Build Card integration.

5. **Unconfirmed: companion avatar generation quality.** The portrait STYLE_DNA still encodes old Yoko (1980s city-pop fashion, "large reflective eyes," female-presenting). Zero is androgynous Ghost-in-the-Shell. Portrait prompt must be rewritten before any real Zero art ships. Illustrator ref sheet still uncommissioned ($300-600).

## Lessons filed

Three new improvement-loop entries (L7/L8/L9 in lessons.md):
- L7: Brief numbers must be verified before handing to implementer (maintenance test case was mathematically wrong).
- L8: Brief under-specified control inventory (app-tray button was missing from enumeration).
- L9: Property test caught error in orchestrator reasoning (rounding always/never claim was incomplete).

All three are orchestrator-boundary failures; consider graduating rules into CLAUDE.md §10 or a playbook section.

## Decisions filed

1. **Maintenance schedule feature (three-state design):** ANCHORED/UNKNOWN/NEVER-DONE states, dual-axis reporting, backfill flow, bundled defaults, manual refresh, DUE tab.
2. **UI coherence audit phases 1-5:** Settings typography, glassmorphism removal, Settings backdrop removal, Cruise COMPACT dock rebuild, app-tray assignment. Phase 6 (two-pane) deferred.

## Playbook material filed (9 SKILL lines)

- Room exportSchema timing (runs at compileDebugKotlin)
- LiveToolbox zero-cost vs sub-agent patterns
- AriaBrain cached rules + buildLiveContext fresh facts split
- AriaDimens spacing scale usage
- WindowSize.kt for size-class helpers (not ui/theme/)
- AvatarVibe SQUARE box both size classes
- carImage() = vehicle photo, not companion portrait
- CruiseScreen EXPANDED/MEDIUM predate dock idiom
- VehicleController.NextService construction sites
- Maintenance three-state model and dual-axis reporting details

## Next session direction

- **Device validation needed on all nine fixes.** Start with the Cherokee OBD + voice loop, confirm Spotify plays in-place, tap-expand fires, no multi-instance crashes.
- **Render the DUE tab and COMPACT dock on the head unit.** Verify Settings typography reads well at arm's-reach distance.
- **Run connectedDebugAndroidTest** to confirm v11→v12 migration executes cleanly.
- **Rewrite AvatarStudio.STYLE_DNA + PORTRAIT_PROMPT** to match Zero's androgynous Ghost-in-the-Shell aesthetic (currently still Yoko-coded). Do NOT generate portrait art until this is done.
- **Consider graduating L7/L8/L9 rules into CLAUDE.md or playbook** if brief-quality issues resurface.
