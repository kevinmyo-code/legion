# Map: Cyberdeck UI overhaul

Label: wayfinder:map
Charted: 2026-08-07 (Kevin + Fable)

> **PARTLY SUPERSEDED 2026-08-14 by `.scratch/mission-control/`.** This map SHIPPED and stays here
> as history; do not resume it. Superseded by that effort: ticket 01 (the MILSPEC palette), ticket
> 03 (semantic colour), ticket 04 (the motion ration), build tickets 12-20, and the "utility screens
> are out of scope" ruling below.
>
> **The reversal to know about before reading ticket 03:** it locked "amber = data, green = good,
> red = needs-you, EXCLUSIVELY". Mission-control reverses that. Red-orange is now ordinary CHROME
> (pill outlines, bezel, frames), mint carries data, amber is highlights and markers.
>
> Still binding and NOT reopened: dark-only, no new data collection, CLAUDE.md §4's worded
> provenance and quarantine states, `Long` cents, Alfred's locked register.

## Destination

LEGION's shell, navigation, and all nine data surfaces (Today, Body, Ledger + drilldowns + budget,
Fleet, Telemetry, Pantry, Notes, Agenda, Lists) rebuilt and **shipped on-device** in a diegetic
cyberpunk-cyberdeck aesthetic with a real visualization layer - trends, charts, history - not
repainted lists. Biohacking frame: Body is biometrics telemetry, Ledger is a resource monitor,
Fleet is a vehicle uplink, the assistant is the deck's resident AI. Utility screens (Settings, Key,
DriveSync, Cars, Companions) inherit the new theme tokens only. The map closes when the screens are
shipped, not when the spec is written.

## Notes

- **Execution is IN SCOPE for this map** (Kevin, 2026-08-07) - deliberate override of wayfinder's
  plan-don't-do default. Build tickets graduate from fog as decisions land.
- Decisions grilled at charting, binding on every ticket:
  - **Instrument is replaced.** The 2026-08-01 design-language decision (ledger-drive-ingestion
    ticket 02) is reopened and superseded by this effort. City-pop stays dead (CLAUDE.md §2).
  - **Diegetic dial at (2) of 3**: the app IS a deck - framed panels, corner brackets, status
    headers, boot/scan transitions, ambient motion. Full-theatre effects (scanlines, glitch,
    CRT) available but RATIONED. Numbers stay the hero; legibility beats vibe on every conflict.
  - **Dark-only**, as a stated decision. The OS light/dark toggle stops mattering. Hard rule in
    exchange: daylight readability - bright foregrounds, no dim-gray-on-black body text.
  - **Chrome speaks deck, Alfred stays Alfred.** Screen headers, panel labels, status lines get
    the diegetic voice (`BIOMETRIC UPLINK // LIVE`). The assistant's spoken/text register
    (CLAUDE.md §1, locked) does not change.
  - **No new data collection.** This visualizes what is already logged.
- CLAUDE.md §4 semantics survive the skin: provenance/quarantine/estimate/UNRECONCILED states
  stay "said in words", never colour or glyph alone. Money stays Long cents, mono, right-aligned.
- Skills every session should consult: `frontend-design:frontend-design`, `dataviz` (any chart,
  any medium), the vendored Compose skills (`compose-animations` for motion work,
  `compose-modifier-and-layout-style`, `compose-recomposition-performance` for ambient motion).
- Prototypes as claude.ai artifacts worked for the Instrument decision; same mechanism here.

## Decisions so far

<!-- one line per closed ticket: gist + link -->

Build tickets closed (2026-08-08, each ticket holds its build report):
- [Build: MILSPEC theme and tokens](issues/12-build-theme.md) - landed with zero screen edits;
  senior-dev review clean, two should-fixes applied in a follow-up commit.
- [Build: shell, hard keys, status line, boot](issues/13-build-shell.md) - SYNC segment means
  connected, not recently-succeeded (named gap).
- [Build: deck chart kit](issues/14-build-chart-kit.md) - 18 unit tests; null = gap end to end.
- [Build: HOME rebuild](issues/15-build-home.md) - red removed from the screen entirely;
  module tap-through wired at merge.
- [Build: BIO rebuild](issues/16-build-bio.md) - five drilldowns, progression chart, DST test.
- [Build: FLEET rebuild](issues/18-build-fleet.md) - FAULTS folded into UPLINK (accepted call);
  FLEET_TELEMETRY route kept for deep-link safety.
- [Build: LOG timeline and Pantry reskin](issues/19-build-log-pantry.md) - tap counts unchanged.
- [Build: driving mode](issues/20-build-driving-mode.md) - strip offer deferred (strip has no
  action-tap mechanism); GlanceCardController deliberately not reused.

- [Deck design language](issues/01-deck-design-language.md) - MILSPEC: avionics console, phosphor
  amber `#FFB000` on green-black `#0A0D08`, stencil caps, corner brackets, dashed rules, checklist
  status copy; full token table on the ticket.
- [Driving mode](issues/11-driving-mode.md) - offered on OBD connect (never auto), three giant
  readouts max, one EXIT key, voice primary, zero theatre.
- [Pantry + Notes/Agenda/Lists surfaces](issues/10-pantry-notes-surfaces.md) - Pantry inherits
  panels and skips charts; LOG gets a mission-log day timeline, lists stay lists.
- [Fleet + Telemetry surface](issues/09-fleet-telemetry-surface.md) - merged into one FLEET
  module; UPLINK leads always (worded staleness); MAINTENANCE/DRIVES/CARS follow; driving-mode
  offer lives on UPLINK.
- [Ledger surface: resource monitor](issues/08-ledger-surface.md) - BURN/BALANCES/FLOW sparkline
  panels with chart drilldowns; plumbing collapses to one OPS row (red on quarantine); STREAM
  stays an inline list.
- [Body surface: biometrics telemetry](issues/07-body-surface.md) - four sparkline panels
  (MASS/INTAKE/SLEEP/TRAINING), full charts + history in drilldowns, per-exercise progression at
  the bottom of TRAINING, not-logged days are gaps never zeros.
- [Today as the deck home](issues/06-today-deck-home.md) - INTAKE hero, fixed order
  (INTAKE/SWEEP/AGENDA/ALERTS), silent domains stated not hidden, zero charts on home,
  attention by tag never reordering.
- [Navigation shell](issues/05-navigation-shell.md) - hard-key row: five stencil keys
  (HOME/BIO/LOG/FLEET/CRED), active key inverts amber; global status line top; Alfred strip
  pinned above keys; module launcher declined.
- [Motion vocabulary](issues/04-motion-vocabulary.md) - boot on cold start only; ~350ms one-shot
  draw-ins; one ambient cursor; theatre spent on exactly three moments (boot, ingest commit,
  quarantine); reduced-motion collapses to instant.
- [Semantic color under the deck](issues/03-semantic-color.md) - amber = data, green = good,
  red = needs-you (exclusively - never debits); exception tagging with a fixed tag-weight ladder;
  universal states stated once at panel level.
- [Chart rendering: library or hand-rolled Canvas](issues/02-chart-rendering.md) - hand-rolled
  Canvas/DrawScope, no dependency; `TelemetryChart` proves the pattern, ~350-550 lines estimated;
  Vico (with a BOM bump) is the escape hatch if pan/zoom ever needed.

## Not yet specified

All decision fog has graduated: build tickets 12-21 now exist (2026-08-08). What remains here is
detail that resolves INSIDE those builds, not before them:

- **Empty/offline/loading state copy** - the exact deck wording per surface; the rule (worded,
  never colour/glyph alone) is already law, the copy is written per build ticket.
- **Daylight contrast floors** - measured against the real MILSPEC palette during the theme
  build; `muted #8A8F78` is the tier to check first; verified on-device in the ship pass.

## Out of scope

- Bespoke redesign of utility screens (Settings, Key, DriveSync, Cars, Companions) - they inherit
  theme tokens automatically; anything more is a later effort.
- Alfred's persona/voice copy - §1 locked register, untouched by this map.
- Light mode - dark-only is a charting decision above.
- New data collection features - visualization of existing data only.
