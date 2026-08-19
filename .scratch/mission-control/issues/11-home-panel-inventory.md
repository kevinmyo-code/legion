---
map: mission-control
ticket: 11
title: "Per-surface panel inventory: HOME first"
type: grilling
status: resolved
status-detail: ""
blockers: ["05"]
blocked-by: ["[[05-tiling-grammar]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Per-surface panel inventory: HOME first

## Question

Which panels are on HOME, in what order, at what size, showing what?

HOME is the pattern-setter. Once it is right, the other surfaces graduate from fog against the
same method; getting it wrong sets a bad grammar for eight more screens.

**Read first:** `.scratch/cyberdeck-ui/issues/06-today-deck-home.md` (the shipped answer) and
`ui/TodayScreen.kt`. The shipped decisions were: INTAKE hero; fixed order INTAKE / SWEEP / AGENDA /
ALERTS; silent domains stated rather than hidden; zero charts on home; attention shown by tag,
never by reordering.

**What changed.** Tiling. The shipped HOME is a vertical stack in a fixed order; a tiled console
has two axes, so "fixed order" needs restating as a fixed *layout*, and half-width panels can hold
less than full-width ones did.

**Resolves:**

1. **The panel inventory.** Which panels exist, and whether the shipped four are still the right
   four now that more fits on screen. Adding panels because there is room is a trap - say why each
   earns its tile.
2. **Sizes and positions**, against ticket 05's vocabulary. Which panel is the hero and how its
   size expresses that.
3. **Whether "zero charts on home" survives.** Tiling makes a half-width sparkline cheap, and the
   refs' roots are full of small live plots. The shipped rule exists so home stays a glance, not a
   study. Re-decide it deliberately rather than letting it erode.
4. **Silent domains.** "Stated, not hidden" is a shipped rule; in a tiled layout a silent domain
   occupies a tile saying nothing. Decide whether it keeps full size, shrinks, or collapses into a
   shared row - and confirm it still cannot vanish.
5. **Attention and alarm on HOME.** Ticket 04's tiers land here first, since HOME is where a
   quarantine or a fault is meant to be noticed. Confirm "never reorder" survives - it is what
   makes the layout memorizable.
6. **Tap-through.** Which tile leads where, and how a tile that is both a readout and a target
   signals that.
7. **The method**, written down, so BIO / LOG / FLEET / CRED can graduate from fog and be resolved
   the same way rather than re-argued from scratch.

**Deliverable.** HOME's inventory, plus the reusable method. The remaining surface inventories
graduate as tickets from this one.

## Answer

Grilled with Kevin, 2026-08-14. The map's count-the-category rule was applied first, and it found
something before the grilling started.

### 0. "Zero charts on home" was already dead, and this map did not kill it

`cyberdeck-ui` ticket 06 decided HOME carries zero charts. **The shipped app has three sparklines on
HOME** - INTAKE, SLEEP, and a LEDGER month-to-date cumulative - added by the `quant-viz` effort in
`087d8f9` and `f1c396d`, both titled "ticket 11" of *that* map.

So this ticket's question 3 ("does zero charts on home survive?") is **moot**. It did not survive,
it fell before this map existed, and a session that had trusted the cyberdeck answer instead of
grepping would have written a decision reversing something already reversed.

### 1. Inventory

`HomeDigestBuilder` computes **five** domain headlines (bio, cred, fleet, log, goals). The shipped
screen renders **four** panes (INTAKE hero, SYSTEMS SWEEP as one row per domain, AGENDA, ALERTS).
The delta is what tiling gets to spend.

| Panel | Shape | Contents | Earns its tile because |
|---|---|---|---|
| INTAKE | FULL, hero | kcal against target, meter, sparkline | it is the thing checked most often, and the decision it supports (eat or not) is the most frequent |
| BIO | HALF | latest mass, trend | one figure, one qualifier - the exact half-tile shape |
| CRED | HALF | month spend, sparkline | same |
| FLEET | HALF | due count or link state | same |
| LOG | HALF | new/uncategorised count | same |
| AGENDA | FULL | today's timed events | rows, not a figure - needs the width |
| ALERTS | FULL | everything needing action, see section 3 | rows plus tags - needs the width |

**SYSTEMS SWEEP is dissolved.** Its four per-domain rows become four half tiles. This is the only
change on HOME that actually uses the grid, and it converts four cramped rows into four glanceable
tiles with real 48dp-plus tap targets.

All four half-tile figures clear ticket 05's **7-character hero limit**: `82.4`, `$2,418`, `3 DUE`,
`4 NEW`. Checked, not assumed.

### 2. Silent domains: full-size tile, worded

A silent domain **keeps its tile at full size and says so in words**. The grid position never
changes.

This is the tiled equivalent of the shipped never-reorder rule, and the reason is the same:
memorizability. You learn where BIO is and it is there whether or not you logged. A fresh install
shows four honest empty tiles rather than a layout that changes shape depending on what you have
done - which would be the worst possible first impression of a console.

Ticket 05's two-shape vocabulary is preserved; no half-height variant is introduced.

### 3. ALERTS becomes "everything needing you"

ALERTS holds **ALARM items, ADVISORY items and goal exceptions in one pane**, each carrying its
tier's tag from ticket 04, **ALARM always first**. HOME becomes the single place you see everything
asking for action.

**Capped at five, with a worded overflow line** (`AND 2 MORE`). The cap is what stops it becoming a
wall, and the overflow is worded rather than a count badge alone.

This resolves a gap ticket 09 would otherwise have left: `KeyScreen`'s outcomes are ADVISORY, so on
a fresh install with no Gemini key, **HOME now says so**. Under an ALARM-only ALERTS pane the
assistant would simply not work and HOME would never explain why.

Rows are 48dp (ticket 03: a tag needs the tappable row height, a 22dp feed row cannot carry one).

### 4. Attention and ordering

Unchanged from the shipped rules, and re-confirmed rather than assumed:

- **Attention is shown by tag, never by reordering.** The grid makes this stronger, not weaker: a
  tile that moves is a tile you have to find again.
- **Fixed order and fixed grid position**, always.
- Ticket 04's status-line ALARM segment is **not redundant** with the ALERTS pane. The segment says
  *that* something is wrong from any surface; the pane says *what*.

### 5. Tap-through

| Tile | Goes to |
|---|---|
| INTAKE | BIO / INTAKE drilldown |
| BIO | BIO root |
| CRED | LOG root, unfiltered |
| FLEET | FLEET root |
| LOG | LOG root, filtered to uncategorised |
| AGENDA | Notes / Agenda |
| ALERTS row | the thing that needs action, per row |

Every tile is tappable, so every tile clears 48dp - satisfied by construction at these shapes
(ticket 05).

### 6. The reusable method, so BIO / LOG / FLEET / CRED can graduate

This is the part that outlives HOME. For each remaining surface:

1. **Count what the data source can supply**, not what the current screen shows. HOME's digest
   builder offered five domains where the screen showed four. Read the controller or digest builder
   first.
2. **Count what the shipped screen shows.** The delta is the candidate set.
3. **Make each candidate earn its tile by naming the decision it supports.** Adding panels because
   the grid has room is the trap; a tile with no decision behind it is decoration.
4. **Assign shapes from ticket 05's two-shape vocabulary** and check every hero figure against the
   7-character half-tile limit. Check it, do not eyeball it.
5. **Fix the grid positions.** Never reorder; silent entries keep full-size tiles with worded
   empties.
6. **Name the tap-through per tile.**
7. **Check the budget:** hero plus one full row of tiles must fit above the fold in ticket 05's
   584dp. **Corrected 2026-08-14 by ticket 14: the measured budget is 560dp.** The check still
   passes comfortably.
8. **Grep the shipped screen's history before trusting any prior decision about it.** Section 0 is
   why.

### Assumptions ledger

| Claim | Tag |
|---|---|
| HOME already has three sparklines, added by `quant-viz` | **`traced`** - read `TodayScreen.kt` and `git log` for it |
| `HomeDigestBuilder` computes five domain headlines | `traced` - read the builder |
| The shipped screen renders four panes | `traced` - read `TodayScreen.kt` |
| All four half-tile figures fit the 7-character limit | `tested` - counted against ticket 05's measured limit |
| Tiles clear 48dp at these shapes | `traced` - ticket 05 established it by construction |
| A cap of five is the right cap | `reasoned` - judgement, not measured against a real alert load |
| Nothing was rendered or seen on the device | - |

## Correction 2026-08-14: LOG and CRED were mis-identified

**The tap-through table in section 5 and the LOG row in section 1 were wrong.** Traced in
`ui/MainActivity.kt` lines 569-573, which is the authoritative mapping:

| Hard key | Route | What it is |
|---|---|---|
| HOME | `today` | |
| BIO | `body` | |
| **LOG** | **`notes`** | notes, lists, calendar, the inbox |
| FLEET | `fleet` | |
| **CRED** | **`money`** | the ledger |

I had assumed LOG was the ledger and CRED was credentials. Both are wrong: **CRED is money, LOG is
notes.** The mistaken table sent CRED to "LOG root, unfiltered" and described the LOG tile as an
uncategorised-transaction count, which is a ledger concept that belongs to CRED.

**Corrected inventory rows:**

| Panel | Shape | Contents |
|---|---|---|
| CRED | HALF | month spend against target, sparkline (the LEDGER cumulative one already shipping) |
| LOG | HALF | unfiled inbox count, or today's item count |

**Corrected tap-through:**

| Tile | Goes to |
|---|---|
| INTAKE | `body` INTAKE drilldown |
| BIO | `body` |
| CRED | `money` |
| FLEET | `fleet` |
| LOG | `notes` |
| AGENDA | `notes`, calendar view |
| ALERTS row | the thing that needs action, per row |

**A related fact worth recording, since it constrains ticket 12:** there are **six** top-level
destinations (`today`, `money`, `body`, `fleet`, `notes`, `settings`) and only **five** hard keys.
`settings` is deliberately not a key - it is reached through the `SETUP` stamp in `StatusLine`,
which is the only route into it that exists (see that composable's own doc comment; before it was
added, Settings was unreachable on any ordinary device).

Nothing else in the answer changes: the four-half-tile inventory, the silent-domain rule, the ALERTS
contract and the method in section 6 all stand.
