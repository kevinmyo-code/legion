---
map: mission-control
ticket: 10
title: "Daylight contrast floors, measured on-device"
type: task
status: resolved
status-detail: ""
blockers: ["01", "02"]
blocked-by: ["[[01-palette-tokens]]", "[[02-bundled-mono]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Daylight contrast floors, measured on-device

## Question

Does the new palette actually read in daylight on the actual phone, and where does it fail?

Dark-only is a decision; daylight readability is the hard rule taken on in exchange. This is the
measurement, and it is a task rather than a decision because the answer is a set of numbers and a
list of failures, not a judgement call.

**Do:**

1. **Compute contrast ratios** for every foreground token from ticket 01 against every ground and
   panel fill it can legally sit on. Report the matrix. Flag anything under 4.5:1 for body text and
   under 3:1 for large text, chrome lines and non-text indicators.
2. **Name the tier most at risk first.** Under MILSPEC that was `DeckMuted #8A8F78`. Under this
   palette it is whatever ticket 01 calls the faint/ghost label tier and the dim chrome line -
   check those before anything else.
3. **Build the palette into a throwaway APK** and install it. Per the memory note, verify the
   installed APK by hash - `adb`/`pm` reporting "Success" has installed a different APK before, and
   it cost a day's data.
4. **Read it outdoors, in direct sun**, on the Oppo A17K, at the brightness the phone actually
   settles at. Photograph what fails. Wireless ADB is available.
5. **Check the bundled face at its smallest tracked size** (ticket 02's caps finding, verified in
   the flesh rather than from a specimen sheet).
6. **Record the floors** as a numbered list the build tickets may not undercut: minimum body size,
   minimum chrome line contrast, minimum label tier.

**Deliverable.** The matrix, the photographs, and the floors. Any token that fails goes back to
ticket 01 as a named revision, not as a note - reopening 01 for a failed value is the correct
outcome, not a setback.

**Note.** `ui/theme/ThemePreview.kt` exists precisely to catch this class of bug and was skipped
once, which is how quarantine-red body text shipped (CLAUDE.md §8, L11). Render it. If it cannot be
rendered, that is a blocking item to surface, not a footnote.

## Added 2026-08-14 (from ticket 08): a failure is already known

Ticket 08 computed WCAG contrast for every token against the `ground` `#000000`. **Two are already
below their floors before anyone goes outside**, so this ticket starts with a known result rather
than an open question:

| Token | Contrast on black | Floor | Status |
|---|---|---|---|
| `ink` `#E4E9EF` | 17.20:1 | 4.5 body | pass |
| `data` `#57EFC6` | 14.57:1 | 4.5 body | pass |
| `amber` `#FFBA1F` | 12.30:1 | 4.5 body | pass |
| `chromeText` `#FF8A6B` | 9.10:1 | 4.5 body | pass |
| `faint` `#8E97A3` | 7.11:1 | 4.5 body | pass |
| `chrome` `#FF5330` | 6.53:1 | 3.0 non-text | pass |
| `ghost` `#58606C` | 3.30:1 | 3.0 non-text | pass as non-text ONLY - **never set body text in it** |
| **`chromeDim` `#5A2317`** | **1.69:1** | 3.0 non-text | **FAIL** |

**`chromeDim` carries the bezel line and every pane outline.** At 1.69:1 the app's entire structural
language is at roughly half the non-text floor, and it may disappear entirely in direct sun. That is
this ticket's first thing to look at, and the likely outcome is a **named revision to ticket 01**
rather than a note here.

Three responses are possible and this ticket should say which:
1. Raise `chromeDim` until it clears 3:1, accepting that panel outlines become more present than
   the "quiet structural tier" ticket 01 intended.
2. Accept it as decorative and make the pane's **fill** (`panel` `#05070C` against `ground`
   `#000000`) carry the separation instead - but that difference is far smaller still, so measure it
   before choosing this.
3. Accept that structure fades in bright sun as long as **content** never does. Defensible only if
   the content genuinely stands alone without the frame, which is exactly what going outside tests.

The contrast figures above are `tested` (computed this session). Whether they predict real
outdoor legibility is precisely what this ticket exists to find out.

## Desk half done 2026-08-14: the full matrix, computed

**This ticket is NOT resolved.** Its outdoor half is unrunnable today, for a reason worth recording
rather than working around - see "Blocking correction" below. What follows is step 1 of the method
(compute the matrix), done now so the outdoor trip is a **confirmation rather than a discovery**.

Contrast ratios, WCAG 2.x relative luminance, every foreground against every ground it can legally
sit on. Floors: 4.5:1 for text, 3.0:1 for non-text UI components.

| Token | ground | panel | panelAlarm | pressed | Floor | |
|---|---|---|---|---|---|---|
| `ink` | 17.20 | 16.51 | 16.16 | 12.62 | 4.5 | ok |
| `marker` | 15.17 | 14.56 | 14.26 | 11.14 | 4.5 | ok |
| `data` | 14.57 | 13.98 | 13.69 | 10.69 | 4.5 | ok |
| `amber` | 12.30 | 11.80 | 11.55 | 9.02 | 4.5 | ok |
| `chromeText` | 9.10 | 8.73 | 8.55 | 6.68 | 4.5 | ok |
| `faint` | 7.11 | 6.82 | 6.68 | 5.22 | 4.5 | ok |
| `chrome` | 6.53 | 6.27 | 6.14 | 4.79 | 3.0 | ok |
| **`ghost`** | 3.30 | 3.17 | 3.11 | 2.43 | 4.5 | **FAIL** |
| **`chromeDim`** | 1.69 | 1.62 | 1.59 | 1.24 | 3.0 | **FAIL** |
| **`rule`** | 1.36 | 1.31 | 1.28 | 1.00 | 3.0 | **FAIL** |
| **`ruleFaint`** | 1.20 | 1.15 | 1.13 | 1.13 | 3.0 | **FAIL** |

Surface-against-surface separations, which carry meaning of their own:

| Separation | Ratio | What it is meant to convey |
|---|---|---|
| `panel` vs `ground` | **1.04:1** | that a pane exists |
| `panelAlarm` vs `panel` | **1.02:1** | that a pane is alarming (ticket 04) |
| `pressed` vs `panel` | **1.31:1** | that a panel is being touched (ticket 05) |

### What this means, stated precisely

**The text tiers are fine.** Every foreground carrying words clears its floor with room, including
against the pressed fill. `data` at 14.57:1 and `ink` at 17.20:1 are genuinely strong.

**One straightforward failure: `ghost` at 3.30:1.** It is used for timestamps, units and gap
markers, which are text, so 4.5:1 is its floor and it misses. This one is a simple value revision.

**The structural failure is the real finding, and it compounds.** `panel` sitting at 1.04:1 against
`ground` was a *deliberate* choice - ticket 01's VACUUM table said so in as many words ("almost
imperceptible, structure carries instead"). That is defensible on its own. But the structure that
was supposed to carry it, `chromeDim`, is 1.62:1 against that pane, and `rule` and `ruleFaint` are
lower still. **So nothing defines a pane above threshold: not its fill, and not its outline.** The
intent was that one of the two would do the work, and neither does.

**Two downstream treatments lean on separations that are effectively invisible:**
- Ticket 04's alarm `panelAlarm` fill is 1.02:1 against an ordinary pane. It contributes
  approximately nothing. The alarm is carried entirely by the inverted pill (6.27:1), the word, and
  the pulse - all of which are strong. **The fill should be recorded as decorative, not as part of
  the escalation.**
- Ticket 05's pressed state is 1.31:1. **A press may not be perceivable at all.** That ticket chose
  a fill change specifically to avoid colliding with ticket 04's alarm border, and the alternative
  it rejected (brightening the border) would have been far more visible. This needs re-deciding, and
  the collision it was avoiding still stands, so it is not simply a matter of reverting.

### Revision candidates, for the outdoor pass to confirm or refute

None of these is decided here. The outdoor pass decides, and a confirmed failure goes back as a
named revision to the ticket that owns it.

1. **`ghost`** - raise until it clears 4.5:1 as text, or stop using it for text.
2. **`chromeDim` / `rule` / `ruleFaint`** - the structural tier, ticket 01. Either raise it, or
   accept that structure fades outdoors provided content never does. The second is only defensible
   if the content genuinely stands alone without a frame, which is precisely what going outside
   tests.
3. **Ticket 05's pressed state** - needs a treatment that is perceivable without reusing ticket 04's
   alarm border.
4. **Ticket 04's alarm fill** - downgrade from "part of the treatment" to "decorative", since the
   numbers say it already is.

### Blocking correction

**This ticket was mis-charted as blocked only by 01 and 02.** Its own method step 3 says to build
the palette into a throwaway APK and install it. There is no built palette: `ui/theme/Color.kt` is
still MILSPEC, and the theme build ticket has not graduated from the map's fog yet.

`Blocked by` is corrected to include the theme build. The outdoor half also needs Kevin, the phone,
and direct sun, none of which an agent can arrange.

### Assumptions ledger

| Claim | Tag |
|---|---|
| Every ratio in both tables | **`tested`** - WCAG 2.x relative luminance computed this session |
| `ghost` is used for text (timestamps, units, gaps) | `traced` - ticket 01's token table and the shipped `LegionSemantics` doc |
| `panel` vs `ground` being imperceptible was deliberate | `traced` - ticket 01's VACUUM table says so |
| WCAG floors predict real outdoor legibility | `reasoned` - **they do not, directly.** WCAG assumes indoor viewing; direct sun is worse than any ratio implies. These numbers are a lower bound on trouble, not a verdict |
| Nothing was rendered, built, installed, or taken outside | - |

## Answer

**PASSES. Closed by Kevin, 2026-08-14, from reading the shipped app outdoors.**

The palette is legible in daylight on the Oppo A17K. This is the check the ticket existed to make,
and it is the only kind of evidence that actually answers the question - a human reading the real
screen in real sun. It outranks the arithmetic below, because WCAG's model assumes indoor viewing
and was never a prediction of outdoor legibility in the first place (this ticket said so itself).

### The computed matrix stands, and four tokens remain thin on paper

Recorded not to argue with the verdict but so nobody re-derives it later and thinks a regression has
appeared:

| Token | Worst ratio | Floor | Paper result |
|---|---|---|---|
| `ghost` `#58606C` | 3.30:1 | 4.5 text | fails |
| `chromeDim` `#5A2317` | 1.62:1 | 3.0 non-text | fails |
| `rule` `#1E2530` | 1.31:1 | 3.0 non-text | fails |
| `ruleFaint` `#141A22` | 1.15:1 | 3.0 non-text | fails |

Three of the four are the **structural tier** - the bezel line and pane outlines. Ticket 14's build
found the same thing from the other direction: `panel` against `ground` is 1.04:1 and yet clearly
perceptible on the device, because a luminance-contrast ratio measures text legibility, not surface
separation on an OLED black.

**What this means in practice:** structure may fade in the brightest conditions while content does
not. That is the outcome this ticket named as defensible in its option 3, on the condition that
content stands alone without the frame. Kevin's reading is that it does.

### Not revised

No token values change. Ticket 01's palette stands as shipped, including `ghost`, which is used for
timestamps, units and gap markers - the tier most likely to be the first thing lost in hard sun, and
the first place to look if this is ever reopened.

### Assumptions ledger

| Claim | Tag |
|---|---|
| The palette is legible in daylight on the target device | **`on-device`** - Kevin, reading the shipped app outdoors. Not measured, not photographed, and stronger evidence than either |
| Every contrast ratio in the table | `tested` - WCAG 2.x relative luminance computed 2026-08-14 |
| WCAG floors do not predict outdoor legibility | `reasoned` - stated in this ticket before the reading, not derived from it |
