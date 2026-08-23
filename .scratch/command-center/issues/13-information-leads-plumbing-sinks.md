---
map: command-center
ticket: "13"
title: "The information leads, the plumbing sinks"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# The information leads, the plumbing sinks

Kevin, 2026-08-22: *"not just polish, the UX itself, the way the information is displayed etc."*

Grounded in real screenshots off the A25 (scratchpad `shots/`), not taste. Five findings, each
with its evidence:

## 1. Money leads with plumbing

The tab's ENTIRE first screenful is folder-connection controls, an account-mapping explainer
paragraph, a raw 16-digit folder identifier, and the nomination protocol. Balances, month spend and
budget - the reason the tab exists - are below the fold.

**Fix: payload first, plumbing last.** Balances / month spend / budget vs actual lead the screen.
Folder connection, account mapping and nomination move to the bottom or behind a SOURCES drilldown.
Nothing is deleted - it re-ranks.

## 2. Manual prose rendered as furniture

Paragraphs of doc-comment register ("A file that doesn't state its own account (a CSV export)
takes the account mapped to the folder it's in...") render permanently. Read once, scrolled past
forever.

**Fix: a collapsed help affordance** - a small `?` or WHY row that expands the explanation on tap,
one shared composable so every screen's help behaves identically. The words survive; they stop
being furniture.

## 3. Empty states teach voice while the button whispers

INTAKE renders a giant SAY "LOG A MEAL" with the new `+ LOG` button small beneath it. ADR 0035
made the button the reliable path; the hierarchy is backwards.

**Fix: affordance first.** The button is the empty state's primary line; the voice phrase drops to
a secondary caption ("or say: log a meal"). Same inversion on every empty state that names a voice
phrase - grep for SAY " to find them all.

## 4. Sparklines render broken at low N

MASS is two-thirds void with one floating dot (a one-point sparkline). SLEEP shows a stray dot,
INTAKE a floating diagonal. They read as rendering bugs, not data.

**Fix:** the shared sparkline hides below 3 points, and panes sized for a chart collapse to
content height when the chart is hidden. One rule in the shared component, not per-screen.

## 5. Row grammar drifts

TRAINING: "3 sets" beside "3 sets x 10 @ 30.0lbs" (also decimal noise - 30.0lbs is 30 lbs).
Alerts tags misaligned per row (pane already deleted). TODAY // 21:29 an inch from the status
line's 21:29 - the same clock twice.

**Fix:** one formatter per fact family (weights, set lines, clocks) in a shared file; the Today
header drops its clock (the status line already owns it).

## Rules

- Re-rank and re-house; delete nothing that carries information (the alerts pane is already gone).
- Mission-control palette and components unchanged - this is hierarchy, not a theme change.
- Empty vs unreadable sentences survive every move.
- Verified by BEFORE/AFTER screenshots off the phone, not by adjectives - the before set already
  exists in the scratchpad.

## Verification

- Suite green both ways, one run fresh. docs_check no drift.
- On the phone: Money opens to money; a help row expands; a fresh install's Body shows buttons
  first; no floating dots anywhere.
