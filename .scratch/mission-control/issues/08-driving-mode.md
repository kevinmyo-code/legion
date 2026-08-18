---
map: mission-control
ticket: 08
title: "Driving mode: aesthetic vs the zero-theatre safety rule"
type: grilling
status: resolved
status-detail: ""
blockers: ["01", "04"]
blocked-by: ["[[01-palette-tokens]]", "[[04-alarm-without-hue]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Driving mode: aesthetic vs the zero-theatre safety rule

## Question

How much of the mission-control language reaches driving mode, and what is not allowed to?

**Start from the default that the safety rule wins.** `cyberdeck-ui` ticket 11 settled driving
mode as: offered on OBD connect, never automatic; three giant readouts maximum; one EXIT key;
voice primary; **zero theatre**. That rule exists because a glance costs road attention, not
because the previous palette was dull. `ref-a-80s-dash.jpeg` is literally a driving surface and is
the most seductive of the four refs - which is exactly why this ticket is charted with the burden
of proof on the aesthetic.

**Resolves:**

1. **Tokens: yes.** Driving mode inherits ticket 01's palette. Confirm the mint-on-dark readouts
   survive a sunlit windscreen better or worse than the old amber - this is a real question, not a
   formality, and it may be the one surface where amber stays.
2. **The bezel: probably not.** A global frame that eats edge space is the opposite of what a
   glance surface wants. Decide explicitly.
3. **Tiling: no.** Three giant readouts is a shipped safety decision. Confirm it stands.
4. **Motion: what, if anything.** The zero-theatre rule says none. Decide whether even a status
   cursor is allowable, and rule on the ticket 07 interaction.
5. **Chrome density.** Label pills, corner ticks, tick rails - each one is pixels that are not the
   number. Decide how much chrome a glance surface may carry.
6. **Alarm in the car.** A fault while driving is the highest-stakes alarm LEGION has and it
   arrives under the worst reading conditions. Ticket 04's escalation must be checked specifically
   here; it may need a stronger or different treatment than any other surface.
7. **Whether the 80s-dash ref contributes anything at all**, or is inspiration for the rest of the
   app that stops at the car door.

**Verification, binding on whoever builds this.** Read on-device, in daylight, in a car, at a
glance. Not in a preview and not indoors. If that cannot be done, say so as a named blocking item
per CLAUDE.md §8 (L11) rather than carrying it silently.

## Answer

Grilled with Kevin, 2026-08-14.

### 0. Point 1 was computable, and it is falsified

The ticket asked whether mint survives a sunlit windscreen better or worse than the old amber, and
flagged driving mode as possibly the one surface where amber stays. Measured as WCAG contrast
against the `ground` `#000000`:

| Token | Contrast on black |
|---|---|
| `ink` `#E4E9EF` | 17.20:1 |
| **`data` mint `#57EFC6`** | **14.57:1** |
| **`amber` `#FFBA1F`** | **12.30:1** |
| `chromeText` `#FF8A6B` | 9.10:1 |
| `faint` `#8E97A3` | 7.11:1 |
| `chrome` `#FF5330` | 6.53:1 |
| `ghost` `#58606C` | 3.30:1 |
| `chromeDim` `#5A2317` | **1.69:1** |

**Mint is the higher-contrast choice.** There is no reason to split the palette; driving mode keeps
mint like everything else.

**A separate finding, flagged for ticket 10 and possibly ticket 01: `chromeDim` is 1.69:1.** WCAG
asks 3:1 for non-text UI components, and the bezel line and every pane outline sit at less than
half that. **The app's entire structural language may vanish in direct sun.** It surfaces here
because driving mode is the worst-case lighting, but it is an app-wide problem, not a driving-mode
one. `ghost` at 3.30:1 also clears only the non-text bar, never body text.

### 1. Chrome: the full deck language, bigger

**Kevin's call, and it goes against this ticket's charted default.** The ticket was written with the
burden of proof on the aesthetic. The decision is the aesthetic's: bezel, label pills, corner ticks
and section rules all reach driving mode, with three giant readouts inside.

**Recorded honestly, because a later reader deserves to know it was a live call and not an
oversight:** the concern raised was glance complexity, not scale. Quantified, the scale cost is
mild - three readouts on an 806dp screen leave each pane roughly 190dp, so digits land near 120sp
against maybe 140sp bare. The cost is the number of elements to parse per look. That is a testable
claim, and section 5's verification is where it gets tested rather than argued.

### 2. Unchanged from `cyberdeck-ui` ticket 11

Not reopened, and re-stated so a build ticket does not have to chase two documents:

- **Trigger is an OFFER on OBD connect, never an auto-switch.**
- **Exit is one giant EXIT key, or the link dropping.** No confirmation dialogs while driving, ever.
- **Three readouts maximum.** No lists, no charts, no stream, no navigation.
- **Voice stays the primary interface.**
- `service/GlanceCardController` and `service/Phase` remain **candidate** machinery, to be verified
  at build time rather than assumed (L10).

### 3. Alarm: ticket 04's full treatment, pulse included

**Kevin's call.** A fault takes over a readout slot with the inverted pill, `panelAlarm` fill, the
word, and the 0.5Hz pulse. Alfred announces it as well, since voice is primary here.

**Named risk, for section 5:** peripheral motion is the specific thing that pulls eyes off a road,
which makes this the highest-risk element in the app. It is not an argument against the decision;
it is the thing the in-car check exists to clear.

### 4. Ambient motion: the uplink sweep runs

**Kevin's call.** Consistent with ticket 07's rule as written - the link is live and polling, which
is exactly the condition that earns a sweep.

**The collision this creates resolves itself, derived rather than re-grilled.** Ticket 07's
precedence stack is alarm pulse > surface ambient > shell cursor, so **during a fault the sweep
stops** and only the alarm pulses. Exactly one non-data element moves at any moment, and the
invariant holds in the car as everywhere else.

### 5. What is allowed to interrupt, and what is not

**Derived, not grilled: only vehicle-domain alarms surface in driving mode.** A quarantined bank
statement, an expired credential or an unconfigured integration must not interrupt driving. They
are waiting when the session ends. The rule is that an alarm reaches the car only if acting on it
is a driving decision.

Theatre remains **fully suppressed** (cyberdeck ticket 11 item 5, not reopened): no boot, no ingest
sweep, no quarantine glitch. Boot cannot arise anyway - driving mode is entered from a running app.

### 6. Verification, binding on whoever builds this (CLAUDE.md §8, L11)

**Read on-device, in daylight, in a car, at a glance.** Not in a preview and not indoors. Three
specific things to clear, all of them decisions taken against the conservative default:

1. **Glance complexity.** Can the number be read in one glance with the bezel, pill and ticks
   present? If not, chrome comes out - and that is the finding, not a failure of the build.
2. **The alarm pulse.** Does it pull the eye off the road? This is the highest-risk element in the
   app.
3. **`chromeDim` at 1.69:1.** Does the frame survive direct sun, or does the whole structure
   disappear? This one is app-wide and feeds ticket 10.

If any cannot be performed, it is a **blocking item to surface, not a footnote to carry**.

### Assumptions ledger

| Claim | Tag |
|---|---|
| Every contrast figure in section 0 | **`tested`** - WCAG 2.x relative luminance computed this session against `#000000` |
| The shipped driving-mode rules in section 2 | `traced` - read `.scratch/cyberdeck-ui/issues/11-driving-mode.md` |
| ~190dp per pane, ~120sp vs ~140sp digits | `reasoned` - arithmetic on ticket 05's measured 806dp, not rendered |
| Peripheral motion pulls eyes off the road | `reasoned` - general claim, not measured for this UI |
| Only vehicle-domain alarms should interrupt | `reasoned` - derived from the surface's purpose, not grilled |
| Nothing here was rendered, installed, or driven with | - |
