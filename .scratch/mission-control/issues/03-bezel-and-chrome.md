---
map: mission-control
ticket: 03
title: "Bezel, label pills, and panel chrome"
type: prototype
status: resolved
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-palette-tokens]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Bezel, label pills, and panel chrome

## Question

What are the shell's frame and the panel's chrome, precisely enough to build as Compose
components?

The charting decision fixed the dial: **one global bezel drawn once in the shell, flat unwarped
content inside, panels get label pills rather than a second full frame.** This ticket turns that
into geometry.

**Resolves:**

1. **The bezel.** Corner arc radius, hairline weight, inset from the screen edge, how far it
   intrudes on usable width, and what the registration ticks are (`ref-d` has short marks at the
   quarter points and small crosses at the corners). Decide how it behaves against the system
   status bar and gesture nav - edge-to-edge with the bezel drawn inside the insets is the
   expected answer, but draw it and check.
2. **The label pill.** `ref-c`'s `DATA FEED` and `ref-d`'s `HELIO MAP` / `ENCOUNTER RADAR` are an
   outlined rounded box straddling the panel's top rule. Specify: corner radius, outline weight,
   horizontal padding, how the panel's own rule breaks around it, and what happens when the label
   is long enough to run out of width.
3. **Panel structure below the pill.** How many rules, at which of ticket 01's chrome tiers, and
   whether panels carry a full outline or only a top rule plus corner marks.
4. **Dense row rhythm.** `ref-c` runs ~20 mono rows with tight leading and column rails. Specify
   row height, the column rail treatment, and the alternating or grouping device (if any) that
   keeps 20 rows scannable without zebra striping.
5. **The status header line.** Where it sits relative to the bezel, and its format.
6. **Quarantine and alarm surfaces.** How a panel LOOKS when it is in an alarm state, given red is
   now spent on ordinary chrome. Coordinate with ticket 04 - if 04 has not resolved, produce the
   normal-state chrome and leave alarm as an explicit open slot rather than guessing.

**Method.** Extend the winning mock from ticket 01 rather than starting a new one, so the chrome
is judged in the palette it will ship in. Real screens, real data.

**Deliverable.** A dimensioned spec the build ticket can implement without taste calls, plus the
artifact link. Name the Compose components it implies (the shipped equivalents are `DeckPane` and
friends in `ui/common/` - read them first and say which survive, which change shape, and which are
replaced).

## Answer

Dimensioned spec at `https://claude.ai/code/artifact/ff4efeb1-20b4-4c1d-9154-da2be719254f`, which
renders the chrome live at the values below rather than picturing them. All values dp, at ticket
01's resolved palette.

### 1. The bezel

| | |
|---|---|
| Inset from screen edge | 6dp, inside the system insets, not under them |
| Line | 1dp `chromeDim` |
| Corner radius | 14dp arc, not a chamfer |
| Break, top and bottom centre | 64dp, line omitted entirely |
| Registration ticks | 6dp arms, 1dp, `chrome`, inset 5dp inside each corner |
| Content padding | 9 left / 10 top / 9 right / 12 bottom |

The break and the ticks are what make it read as a bezel rather than a rounded card. **The ticks are
the only place full-strength `chrome` appears when nothing is wrong** - everything structural is the
dim tier, per ticket 01's finding.

**It costs 32dp of width, 8.9% of a 360dp phone, and that is not tunable away.** Most of it is the
9dp content padding, which cannot drop much below 8dp without the pill colliding with the frame.
**Ticket 05 must lay out against 328dp, not 360.**

### 2. The label pill

| | |
|---|---|
| Height | 16dp, straddling the pane rule 8 above / 8 below |
| Offset from pane left | 8dp, never centred |
| Horizontal padding | 6dp |
| Corner radius | 2dp - near-square, the bezel owns the roundness |
| Outline | 1dp `chrome` |
| Label | 9sp caps, 0.2em tracking, `chromeText` |
| Fill | **the surface behind the pane**, so `ground` normally and `panelAlarm` on an alarm pane |
| Max width | pane width minus 16dp, ellipsis |

The fill rule is the whole trick and it is an implementation constraint, not a preference: the pill
paints the PARENT's ground, which is what makes the pane's top rule appear to break around it. It
therefore cannot be a child of the pane's clipped content.

**Long labels truncate.** No wrapping, no stepping the type down - a pill at 8sp on one pane and 9sp
on the next destroys the grid rhythm. If a label does not fit at 9sp, the label is wrong, not the
pill: `TRAINING // EXERCISES` becomes `TRAINING` with the qualifier moved into the body. Build
tickets shorten copy; they do not add a second pill size.

### 3. Pane and row rhythm

| | |
|---|---|
| Pane outline / fill | 1dp `chromeDim`, full frame / `panel` |
| Pane padding | 9 / 13 / 9 / 9 - the 13 at top clears the pill |
| Pane gap | 9dp |
| Feed row | **22dp, display only** |
| Tappable row | **48dp** |
| Row separator | 1dp dashed `ruleFaint`, 6 on 5 off (carried verbatim from the shipped `DeckRow`) |
| Row columns | 40dp code / flex name / auto value, 8dp gutters |
| Section rule | 11dp above, 5dp below; 9sp `chromeText` label then a `chromeDim` line |
| Status line | 7dp padding below, 9dp margin, 1dp dashed base |

**No zebra striping, ever.** Twenty rows stay scannable on the dashed hairline and the 40dp code
column alone. Striping would introduce a second panel fill, and every fill on this ground already
does semantic work. Grouping is the section rule's job.

### 4. Findings the ticket did not ask for

1. **A 22dp feed row cannot be tappable.** This is the sharpest result of the exercise. A dense feed
   and a tappable list are therefore **different components, not one component with a flag**, and
   any alarm that needs a tag inside a dense feed must promote that row to 48dp to carry it. Handed
   to ticket 04 and ticket 05.
2. **Twenty rows is the phone's ceiling** at 22dp (440dp of a ~640dp content area). The refs' wall
   of forty rows is unreachable and should stop being the target.

### 5. Verdict on every shipped primitive in `ui/common/DeckPanels.kt`

| Component | Verdict | What changes |
|---|---|---|
| `DeckPane` | CHANGED | Signature survives. Internal header `Row` replaced by the pill; the 1dp faint border plus two-corner 2dp brackets replaced by a full 1dp `chromeDim` frame. `headerAccent` loses its green and renders `faint` - an accent clause on every pane would spend a hue ticket 01 kept rare. |
| `DeckRow` | CHANGED, and splits | Dashed hairline and the never-truncate-a-value rule survive verbatim. Value colour moves `primary` (amber) to `data` (mint). Splits into 22dp display and 48dp tappable. |
| `DeckMeter` | CHANGED | Fill amber to mint (a meter shows a value). Pace tick green to amber (a target is a highlight, not a verdict). Height 12dp to 6dp. |
| `DeckTag` / `QuarantineTag` | **SURVIVES** | API shape untouched, deliberately. Keeping red out of `DeckTagStyle` so the only path to it is `QuarantineTag` - auditable in one grep - is **more** load-bearing now that red is ordinary chrome, not less. Rendering is ticket 04's. |
| `StatusLine` | SURVIVES | Moves inside the bezel. The deferred-read cursor carries over unchanged and stays the one ambient animation. SETUP stamp keeps its 48dp target. |
| `DeckBezel` | NEW | Shell level, drawn once, around the nav row as well as the content. |
| `DeckSectionRule` | NEW | Label plus a rule filling the remainder; currently hand-rolled per screen. |

### 6. The alarm slot, left open as instructed

Normal-state chrome only. What ticket 04 inherits:

- The alarm pane's border is **already** full `chrome` against `chromeDim` everywhere else. Real,
  available, and on its own a one-step contrast change a hurried glance will miss.
- **The inverted pill is structurally free and is reserved, not spent.** Because a pill paints
  whatever is behind it, a solid-`chrome` pill with ground-coloured text needs no new component.
  This is the most likely escalation.
- A quarantine tag needs the 48dp row (see finding 1).
- Motion is not spent here. The status cursor is this surface's one ambient element under ticket 07,
  so an alarm that also wants to pulse forces a precedence rule. Ticket 04 decides it, ticket 07
  implements it.

### 7. Fog this closes

The map's "boot sequence content under the new language, revisit after ticket 03" is **absorbed into
ticket 07**, which already carries "the three theatre moments under the bezel, boot in particular
changes". It does not need its own ticket: the bezel now exists as geometry, and what boot does with
it is a motion decision.

### Assumptions ledger

| Claim | Tag |
|---|---|
| Every dp value renders as described | `built` in CSS at those numbers, in a browser. **Not** Compose, **not** on the phone |
| The five shipped primitives and their current values | `traced` - read `ui/common/DeckPanels.kt` in full |
| 32dp width cost, 20-row ceiling, 440dp of ~640dp | `reasoned` - arithmetic on the values above, not measured on a device |
| 48dp tap floor | `traced` - M3's own minimum, and the shipped `StatusLine` already pads to it |
| The pill cannot be a child of the pane's clipped content | `reasoned` - follows from it needing the parent's ground colour; not proven against a Compose clip |
| Bezel sits inside system insets and clears the gesture bar | `reasoned` - **notch and gesture-bar cases untested** |
| Corner arc rendering, dash phase, pill knockout | `reasoned` - named as the three most likely to need a nudge in the build |
