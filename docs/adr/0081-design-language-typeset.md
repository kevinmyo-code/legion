---
status: accepted
decided: 2026-11-09
decided-by: Kevin
supersedes: [0051-design-language-soft-material]
source: "[[decisions#2026-11-09 - The phone's design language is Typeset (Kevin)]]"
tags: [adr]
---

# 81. Design language: Typeset

## Standing

ACCEPTED, being built on `feat/typeset-ui`. The phone's design language is **Typeset**, a front
page set in one serif on paper. It supersedes [[0051-design-language-soft-material]]. Screens move
to it in phases; a screen not yet restyled keeps soft Material or mission control until it is.
The web client is not covered.

## Context

The phone shipped three looks at once (mission control, soft Material, Matrix), four answer
surfaces and controls in five places. Kevin was shown three clickable prototypes of one home and
conversation surface (Typeset, Matrix mono, City pop) and picked Typeset: *"i want typeset"*, then
*"build it"*. The prototype is the visual spec.

## Decision

- **Paper and ink.** Paper `#faf8f3`, ink `#1d1a16`, meta `#6b655b`, rule `#dcd6ca`. One accent,
  `#8a2d1e`, spent only on flags (a watch item, an alert, the feed-blocker gate, focus). Buttons
  and bars are ink, not accent.
- **Night edition.** The same page in cream on warm black (`#14120f`, ink `#f1ece2`, accent
  `#e0907a`). It follows the phone's dark theme, and while bedtime (ADR 0079) is on it is the
  default whatever the phone says. A one-tap switch on home overrides it; the override lasts until
  the automatic answer next changes (a build choice, open to Kevin).
- **One face: Source Serif 4**, bundled as static TTFs (minSdk 24 rules out the variable file).
  One scale: 12, 14, 17, 21, 34, 64, 92. Labels are small caps, metadata italic. **Times are
  24-hour** everywhere Typeset shows one (Kevin, 2026-11-10: *"i want 24 hr clock"*). The Text optical
  size up to 34 and the Display optical size for 64 and 92, which is what the prototype's automatic
  optical sizing picks.
- **Square.** No corner radius, except round things (the orb, a status dot).
- **Typeset matter, not boxes.** A card is a rule above and below. Lists are ruled rows.
- **One surface for home and conversation.** The glance (time, next event, due today, budget left)
  gives way to captions and cards when Alfred is asked, and returns when they are cleared. One card
  host for every answer. Captions show the last line; only a typed ask shows a transcript.
- **The contents line** under the figures is two rows of three: Today, Money, Car / Clock, Field,
  Breathe (Kevin, 2026-11-10: box breathing one tap from home, and the Field screen "somewhere
  coherent"; [[decisions#2026-11-10 - Box breathing and Field on the Typeset home (Kevin)]]). Box
  breathing is a native ink animation, never a GIF, opened only by a tap; the blocked-feed screen
  offers "Breathe instead".
- **One Controls panel** with six entries: Quiet, Feed blocker, Clock, Claude sessions, LEGION
  settings, Phone settings. An app dock of six monogram tiles, and an "All apps" tray set as an
  index with a find line.
- Tokens stay in `ui/theme/soft/` under their existing role names, now behind a CompositionLocal
  (`LocalSoftPalette`), so one provider reaches every screen that reads them.

## Voice and cards (Kevin, 2026-11-10)

Ruled with *"123 yes to all per ur picks"*
([[decisions#2026-11-10 - Typeset voice and cards: tap interrupts, typed asks get cards, provenance only when real (Kevin)]]):

- **A tap while Alfred speaks interrupts him** so Kevin can talk. It reuses the barge-in path of
  [[0064-phone-turn-taking-local-end-of-turn-and-barge-in]] (the same flush of queued speech and
  the same "interrupted" event), never a second one. A tap while he is not speaking keeps today's
  meaning. **The masthead's Home link ends the conversation** and clears its cards; the Android
  Home key or gesture only returns to the front page, answers kept until cleared (a pick, Kevin had
  no preference, [[decisions#2026-11-10 - Typeset shows 24-hour time; the masthead Home ends the conversation (Kevin, and a pick)]]).
- **Typed questions get the same answer cards as voice.** The cards stay until the Home button,
  a new conversation, or the next voice conversation's end.
- **A card names its source and age only when the tool result or record carries them** (a live
  read, a stored as-of date, a sync time). Never inferred from the tool's name. No data, no line.

## Consequences

- **Not a reopen of CLAUDE.md section 2.** City pop stays dead (it was one of the three prototypes
  and was not chosen). Only the live-language pointer moves from ADR 0051 to this one.
- **Matrix mode is unchanged and stays reachable** (ADR 0062, its settings row and its tile).
  Typeset replaces the NORMAL home only; Matrix home still replaces it while Matrix mode is on.
- **Behaviours that do not change with the look:** the ADR 0066 feed-blocker gate (60 second wait
  and the typed sentence; turning it on is one tap), the drawer's "blocked" mark from the same
  block list, every hands path (ADR 0035), the home-app rules (ADR 0050, ADR 0062: every other app
  reachable, never a dead tap), Alfred's clock (ADR 0079) and Quiet mode.
- **The phone gets its first light scheme.** Mission control was dark-only by decision; that
  decision binds only the screens still on it. The L11 trap applies to both editions: every
  container slot in each Material scheme is pairwise distinct, and a test says so.
- Trust disclosures stay in words on every surface (CLAUDE.md section 4 rules 5 and 7). With one
  accent, an estimate and an alert share a hue; the words tell them apart.
