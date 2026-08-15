# Alarm escalation when red is chrome

Type: grilling
Status: resolved
Blocked by: 01

## Question

When red-orange outlines every panel, how does a genuine "look now" state announce itself?

This is the load-bearing consequence of the charting decision, not a detail. Shipped MILSPEC gave
red a monopoly - `DeckRed` was documented as the one value nothing else in the app may use,
precisely so that its appearance meant something. This effort spends that hue on decoration. The
signal has to be rebuilt out of other materials.

**The states that need to escalate**, in rough severity order:

| State | Where |
|---|---|
| Quarantined document (failed the §4 gate) | Ledger, Pantry, ingest surfaces |
| `UNRECONCILED` provisional rows (§4 rule 7) | Ledger |
| Active vehicle fault / DTC | Fleet |
| Sync failure, expired credential | shell status line, CRED |
| Crisis-tier state routed to `CrisisDetector` | assistant surfaces |

These are not one severity. Decide how many tiers there actually are and which states sit in each
- collapsing them into one "red" is what the old palette did, and it is not obviously right.

**Materials available instead of hue:**

- **Solid fill inversion** - the alarm panel's chrome fills rather than outlines, so it reads as a
  filled block in a screen of outlines. This is the charting decision's named escalation.
- **Motion** - a slow pulse or blink, spending the surface's single ambient-motion budget from
  ticket 07. Note the interaction: an alarming surface may not be able to afford its normal
  ambient element at the same time.
- **The word.** CLAUDE.md §4 already requires it. It is not the escalation, it is the floor.
- **Position** - promotion to the top of the surface, or into the global status line.
- **Persistence** - an alarm that does not scroll away.
- **Sound / haptics** - probably out, but rule it in or out explicitly.

**Resolves:**

1. The tier count and which state sits in which tier.
2. The exact visual treatment per tier, expressed against ticket 01's tokens.
3. **The reduced-motion answer.** If a tier's escalation is motion, it must survive
   reduced-motion collapsing to instant. An escalation that disappears for an accessibility
   setting is not an escalation.
4. **The daylight answer.** These are the states most likely to be read outdoors in a hurry.
5. Whether the global status line gets an alarm segment, and what it displaces.

**Constraint.** CLAUDE.md §4 is not reopened. The word always appears. This ticket decides what
appears *in addition*, so the state is noticed before it is read.

## Answer

Grilled with Kevin, 2026-08-14.

### 0. The premise was wrong, and that reframed the ticket

This ticket was written believing red was exclusive and that five states needed re-homing. **It was
not exclusive.** `sem.quarantined` appears **50 times across 25 files**. `QuarantineTag` was guarded
so red TAGS are greppable, but the raw semantic field was never guarded and had been used for at
least six unrelated things:

| What it actually marked | Examples |
|---|---|
| Failed the reconciliation gate | `QuarantineTag`, `LedgerRows`, `LedgerScanRows` |
| Active fault | `FleetRows` DTC code |
| Destructive action label | `DELETE`, `END CALL`, `CLEAR`, `PURGE LEDGER` |
| Form validation error | `CarRows`, `BudgetSection`, `LedgerCategoryDrilldown` |
| Not configured yet | `KeyScreen`, `DriveSyncScreen`, `GoogleAccessScreen`, `SpotifyRows` |
| Capability blocked | `AssistantStrip` mic blocked |

So this ticket is not replacing one signal. **It is sorting a pile that was never sorted.** The
charting decision to spend red on chrome forced the sort, which is a better outcome than the
decision it was worried about.

### 1. Three tiers, and DESTRUCTIVE is not one of them

| Tier | Contains |
|---|---|
| **ALARM** | Failed the gate (quarantine), active fault (DTC) |
| **ADVISORY** | Not configured, validation error, blocked capability, `UNRECONCILED`, `SET PLAN`, `PACING HOT` |
| **DESTRUCTIVE** | `DELETE`, `PURGE LEDGER`, `END CALL`, `CLEAR` - **outside the alarm scheme entirely** |

A destructive control is a control, not a state. The app is not telling you something is wrong when
it draws a DELETE button; it is offering you a thing you can do. Conflating the two is most of why
50 usages accumulated.

**Crisis is in none of these tiers.** See section 5.

### 2. ALARM

- **Inverted pill**: solid `chrome` fill, `ground`-coloured text. Structurally free per ticket 03 -
  a pill already paints whatever is behind it, so this needs no new component.
- **`panelAlarm` fill** on the pane, and the pane's border at full `chrome` (ticket 03 already
  specified both).
- **The word**, always. CLAUDE.md §4's floor, unchanged.
- **A slow pulse on the pill, ~0.5Hz.** Spends the surface's one ambient element.
- **A persistent `ALARM` segment in the global status line**, so an alarm on LOG is visible from BIO.

**Precedence (hands the rule to ticket 07): while an alarm is present on a surface, that surface's
ambient element stops.** The alarm pulse IS the surface's one animating element for the duration.

**Reduced motion.** The pulse collapses to solid. This is safe *only* because the static treatment
above already carries the whole meaning - the pulse is a bonus, never the carrier. Any future change
that makes motion load-bearing here breaks the accessibility case and must be rejected.

### 3. ADVISORY

**The shipped `DeckTagStyle` ladder is reused exactly as-is. No API change**, so the one-line
`QuarantineTag` grep audit keeps working.

| Style | Means | Examples |
|---|---|---|
| `INVERTED_AMBER` (solid fill) | act on this | `NO KEY`, `NOT CONNECTED`, `UNRECONCILED`, `SET PLAN` |
| `OUTLINE_MUTED` | just know this | `EST`, `REPORTED` |

`UNRECONCILED` sits in the filled tier deliberately. CLAUDE.md §4 rule 7 requires every surface
carrying one to say so; quieting it would be a regression against a rule, not a style choice.

**Advisories do not reach the status line.** Only ALARM gets the global segment - derived, not
grilled: an advisory is by definition something you deal with when you are on that surface.

### 4. DESTRUCTIVE

**Neutral until commit.** The everyday label renders `ink` with an outline, like any other control.
Full `chrome` appears only on the confirming step - the point of no return - where a solid fill is
correct because at that moment red means what it means everywhere else: this is happening now.

`LedgerScreen`'s existing `PURGE LEDGER` / `YES, PURGE THE LEDGER` / `CANCEL` two-step is already
the right shape and only needs recolouring.

### 5. Crisis leaves the deck language entirely

**No pill, no pulse, no chrome, no bezel theatre, no instrument voice.** Plain high-contrast type on
the ground, the resource, and a way out.

The reasoning is CLAUDE.md §7's, not taste: the deck IS the persona, and §7 says the persona stops
performing at exactly this moment. A crisis screen that looks like a quarantined bank statement is
the persona still performing. This is the one part of the answer that is a safety rule rather than a
design preference, and it should be treated as such by whoever builds it.

**Known gap carried forward, not introduced here:** the crisis resource is US-only (988).

### 6. The status line

While an ALARM is present, the segment **replaces `SYNC` and `OBD`**; they return when it clears.
An alarm outranks routine status, and those two are precisely what you do not need to read while
something is actually wrong. The clock and date stay.

Tapping the segment navigates to the alarm.

**One case checked:** if the alarm were itself a sync failure, displacing `SYNC` would hide the
cause. It does not arise - a sync failure or expired credential is **ADVISORY**, not ALARM, so it
never produces a segment in the first place.

### 7. Handed onward

- **Ticket 07** gets the precedence rule in section 2, and the fact that the alarm pulse consumes
  the surface's ambient budget.
- **Ticket 05** gets: an ALARM pane cannot sit in a half-width tile if the pill plus the word will
  not fit at 9sp.
- **Ticket 03's finding stands and now has a consequence**: a 22dp feed row cannot carry a tag, so
  **an alarm inside a dense feed promotes that row to 48dp.** Derived from ticket 03, restated here
  because build tickets will hit it.
- **A cleanup this ticket creates, for a build ticket:** all 50 `sem.quarantined` call sites must be
  re-homed to one of the three tiers. Most become ADVISORY or DESTRUCTIVE. This is mechanical but it
  is not small, and skipping it leaves the app exactly as unsorted as it is now.

### Assumptions ledger

| Claim | Tag |
|---|---|
| 50 usages across 25 files, and the six categories | `traced` - grepped and read the call sites |
| Red was never exclusive outside `QuarantineTag` | `traced` - the grep is the proof |
| The inverted pill needs no new component | `traced` - follows from ticket 03's pill fill rule |
| `LedgerScreen` already has a two-step purge confirm | `traced` - read in `LedgerScreen.kt` |
| Crisis resource is US-only 988 | `traced` - CLAUDE.md §7 states it as a known gap |
| A 0.5Hz pulse is noticeable but not theatre | `reasoned` - not rendered, not seen on a device |
| The status line fits the segment at 328dp | `reasoned` - **not measured**; ticket 03's width figure is itself unmeasured |
| Advisories should not reach the status line | `reasoned` - derived, not grilled |
| Alarm-in-a-feed promotes to 48dp | `reasoned` - follows from ticket 03's row split |
