---
map: home-launcher
ticket: "01"
title: "The shape: layout, tiles, list style, lists page, look, scope"
type: decision
status: resolved
status-detail: >
  Resolved 2026-09-27 by Kevin in one interview plus a clickable prototype
  canvas. Today card over a 2 x 4 tile grid; Groceries is a list, not a
  tile; Keep-style list; icon-card Lists page (variant C); softer Material
  dark on Home, Lists and the shell chrome, the rest as touched. Build
  tickets 02, 03, 04 opened in the same commit.
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# The shape

Every answer below is Kevin's, 2026-09-27, in his words where he gave words.

## 1. Which home

**The Android app.** Not the web home (which has a calendar and lists too, and was the other
candidate).

## 2. The landing page

**A today card on top, then big tiles, nothing scrolls.** Picked from three ASCII layouts (today
card + tiles / bare tile grid / tiles + list cards on home).

- Today card: weekday and date, weather, air quality, the next thing on the calendar, a "due today"
  chip and an "overdue" chip. Tapping it opens the calendar.
- **2 columns x 4 rows**, bigger tiles (*"2 x 4, bigger tiles"*), in this order: Calendar, Lists,
  Money, Body, Fleet, Recordings, News, Reports. Each tile: a coloured icon chip, a name, one short
  status line.
- **Groceries is not a tile.** Kevin: *"u put list and groceries as separate things, grocery is just
  a list no?"* Right - the proposed tile opened the pantry (receipts, grocery budget), which is
  spending and lives under Money. The grocery LIST is a card on Lists like any other list; the
  receipts page is reached from Money.
- **"Reports"** is the ASK screen: the closed-enum builder over ledger and pantry.
- **Recordings keeps a one-tap record button on its tile** (*"Record button on the tile"*); the
  tile body opens the recordings list.
- The "Needs you" pane goes; a breach shows as that tile's status, in words and in the alert
  colour.

## 3. A list, opened

**Keep-style.** Tap the box to tick, in the list. Ticked items sink into a collapsible "ticked"
group, struck through. Add an item inline at the bottom. Long-press an item to edit, move or
delete it. Rename, schedule, history, archive and delete live behind the overflow menu.

## 4. The Lists page

**Variant C, icon cards.** Kevin: *"I like C, icon cards. looks clean that way."* Picked from three
clickable prototypes (A: Keep-style masonry with item previews; B: wide cards with a progress bar;
C: a 3-column grid of icon cards with a progress ring, grouped into routines and plain lists).

The prototype canvas: https://claude.ai/artifact/5W4ragAPaJgsQrLR9t652o (private to Kevin). Its
source is kept in `research/prototype-canvas/` as the primary source this decision was made
against.

## 5. The look

**Softer modern Material**, chosen over "mission control, upgraded" and "light, warm": dark grey
surfaces, rounded cards, a colour per area. Standing record:
[[../../../docs/adr/0051-design-language-soft-material|ADR 0051]].

## 6. How far the look reaches now

**Home, Lists, and the shell chrome** (*"Home, Lists, and shell chrome"*): the status line and the
talk bar restyle with them, and the mission-control bezel goes, so home is not framed in the old
look. Money, Body, Fleet, the calendar page and everything else keep mission control until each is
next touched.

## Calls made without Kevin, stated so they can be checked

- **Font: Figtree**, bundled (OFL). The prototype used it; Kevin approved the prototype's look
  without comment on type. One line to change if he objects.
- **Icons: Material Symbols Rounded**, vendored as vector drawables (Apache 2.0).
- **The two Lists sections are "Routines" and "Lists"**, not the prototype's "Daily routines" - a
  weekly list is a routine and is not daily.
- **A list's icon and colour come from its name** (a keyword table, then a stable fallback). No new
  column. A picker would need a synced column on Room and Django; not asked for.
- **Reorder is Move up / Move down in the long-press menu**, not drag. The existing reorder is an
  adjacent swap and this keeps it; drag needs a new dependency or a hand-rolled gesture.
- **The prototype was drawn at 412dp; the A25 is 384dp.** Sizes are fitted to the real phone.
