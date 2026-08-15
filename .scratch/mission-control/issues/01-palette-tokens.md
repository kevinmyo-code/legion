# Palette, ground, and the two-hue token table

Type: prototype
Status: resolved

## Question

What are the actual colour values, and what does each one mean?

The charting decision fixed the *structure* - red-orange is chrome, mint is data, amber is
highlights and markers - but not a single hex value, and not the ground.

**The ground is the open sub-question.** The four refs disagree with each other:

| Ref | Ground |
|---|---|
| `ref-a-80s-dash` | warm brown-black |
| `ref-b-avionics-cluster` | pure `#000` |
| `ref-c` / `ref-d` mission control | cool navy-black |

Shipped MILSPEC uses green-black `#0A0D08`. With mint data and red chrome, navy-black is the
likely fit, but that is a guess until it is drawn.

**Method.** Build 2-3 throwaway HTML mocks of REAL LEGION screens with REAL data shapes - HOME,
BIO/MASS, and LOG at minimum, using actual logged values (1840 kcal, 82.4 kg, a quarantined
statement, a `-$41.20` debit, an `UNRECONCILED` row). Vary the ground and the exact hue pairs
across the takes. Kevin reacts, one wins. Do not mock abstract swatch grids - the refs' whole
argument is about how these hues behave in dense composition, and a swatch grid cannot show that.

**Resolves, as a token table ready for `ui/theme/Color.kt`:**

- Ground, panel fill, and the sunken quarantine surface.
- The chrome family: bezel, pill outline, panel rule, hairline, corner tick. How many tiers of
  structural line, and how bright each is (`ref-d` clearly runs at least two).
- The data family: mint for feed rows and values, and whether dim-mint is a separate token for
  ticks, axes and units or just an alpha of the same value.
- Amber: highlights, markers, the active nav key. Refs also use a distinct **yellow** for markers
  (`ref-d`'s diamonds) separate from amber - decide whether that is one token or two.
- Green: money-in and system-ok. Under a mint-dominant palette, decide whether green survives as a
  distinct hue at all, or whether "good" now reads via mint and the word.
- The `LegionSemantics` role mapping: `MoneyPositive`, `ValueEstimated`, `StateQuarantined`, and
  the `faint`/`ghost` label tiers, re-cut to this palette. Field NAMES must stay stable - every
  screen reads them through `LocalLegionSemantics.current.<name>`.

**Constraints that are not up for grabs here:**

- Dark-only. Daylight readability is a hard rule; ticket 10 measures it on-device, but do not
  hand over a table you already know is dim.
- `errorContainer` must not collide by value with any other container role. M3's `contentColorFor`
  resolves by value and tests `errorContainer` first - see CLAUDE.md §8 (L11) and `Theme.kt`'s
  `DarkScheme` note. This is the bug that shipped once already.
- CLAUDE.md §4: colour never carries provenance or quarantine alone. The word does.

The winning table becomes the spec every downstream ticket reads. Link the artifact URL in the
answer.

## Answer

**VACUUM with a tinge of SENTRY** (Kevin, 2026-08-14), from the three-take comparison at
`https://claude.ai/code/artifact/23c1949c-69d1-46b1-af82-58611b7255cd`. The artifact carries the
shipping table at the top and keeps all three original takes below it, because the reasoning for
the splice only reads against them.

HANGAR (warm brown-black, from `ref-a-80s-dash`) was declined outright: warmth costs contrast, and
daylight readability is the hard rule the palette took on in exchange for being dark-only.

### The ground question, settled

Pure black `#000000`. On an OLED phone that is genuinely unlit pixels, which is both the strongest
possible starting point for daylight contrast and the literal reading of `ref-b-avionics-cluster`.

**The move that makes the splice work: SENTRY's ground becomes VACUUM's panel.** The navy-black
`#05070C` that was the whole screen in take A is demoted to the one step above black that separates
a pane from the void. Neither take is diluted; one nests inside the other. Everything else sitting
on that ground is pulled back off VACUUM's full saturation and given a slight blue bias, so the
neutrals read as chosen rather than as default grey.

### Shipping token table

| Token | Value | From | Role |
|---|---|---|---|
| `ground` | `#000000` | VACUUM | Screen ground. Unlit OLED pixels. |
| `panel` | `#05070C` | SENTRY | Pane fill. SENTRY's ground demoted one tier. |
| `panelAlarm` | `#170604` | VACUUM | Sunken alarm surface. M3 `errorContainer`. |
| `ink` | `#E4E9EF` | spliced | Reading text, merchant names, debit descriptions. |
| `faint` | `#8E97A3` | spliced | Labels, units, provenance. Ticket 10 checks this tier FIRST. |
| `ghost` | `#58606C` | spliced | Timestamps, gaps, disabled. |
| `chrome` | `#FF5330` | VACUUM | Pill outline, bezel ticks, alarm border, alarm fill. |
| `chromeText` | `#FF8A6B` | VACUUM | Pill label, section rule label. |
| `chromeDim` | `#5A2317` | VACUUM | Bezel line, pane outline. The structural tier. |
| `rule` | `#1E2530` | spliced | Section boundary. |
| `ruleFaint` | `#141A22` | spliced | Row separator, meter track, chart gridline. |
| `data` | `#57EFC6` | spliced | Every value. Pulled back off VACUUM's full saturation. |
| `amber` | `#FFBA1F` | spliced | Highlights, active key, target line, estimate tag. |
| `marker` | `#FFD84A` | spliced | Chart endpoint and typed markers. |
| `good` | `#7BE86A` | **revised** | Money in, system ok. |

`good` is the one value that is not either parent's. Both takes put their green close enough to the
mint that a credit did not separate from the seven debits above it - the mocks flagged it in
HANGAR's table and it was true of all three. It moves toward leaf. If ticket 10 finds it still too
close in sun, the fallback is **not** a fourth hue: the word `CREDIT` is already on the row.

### Sub-questions the ticket asked, answered

1. **How many chrome tiers?** Three, and two of them are load-bearing. `chromeDim` does the
   structural work (bezel line, every pane outline); `chrome` is reserved for pill outlines, bezel
   ticks and alarm; `chromeText` is the label. **Full-strength red on every pane edge turns the
   screen into a grid of alarms** - this was the clearest finding of the whole exercise.
2. **Is dim-mint a token?** No. Ticks, axes, units and gaps read as `faint` or `ghost`, never as
   dimmed mint. Mint means "this is a value", and diluting it dilutes that claim.
3. **Is marker yellow separate from amber?** Yes, `#FFD84A` vs `#FFBA1F` - but only just, and in
   HANGAR they were nearly indistinguishable. **Typed markers should differ by SHAPE, not by hue**;
   the yellow is a nudge, not a signal. Carry this to ticket 06.
4. **Does green survive?** Yes, as a rare hue, at the revised value above. Money in and system ok
   only.
5. **Debits.** Not red, not dimmed. Ordinary values in mint with a minus sign. Most rows are
   debits; colouring them costs the palette everything. This preserves the shipped `debit` posture
   while changing its hue from ink to data.

### `LegionSemantics` mapping

Field names stay stable - every screen reads `LocalLegionSemantics.current.<name>`, and it is
keeping these names fixed that lets a retheme land without touching screen files.

| Field | New value |
|---|---|
| `credit` | `good` `#7BE86A` |
| `debit` | `data` `#57EFC6` (was `ink` - a real change; see sub-question 5) |
| `estimated` | `amber` `#FFBA1F` |
| `quarantined` | `chrome` `#FF5330`, **provisional** - ticket 04 owns this |
| `rule` | `rule` `#1E2530` |
| `ruleFaint` | `ruleFaint` `#141A22` |
| `faint` | `faint` `#8E97A3` |
| `ghost` | `ghost` `#58606C` |

New fields this palette needs that `LegionSemantics` does not have: `chrome`, `chromeText`,
`chromeDim`, `marker`, `data`. Adding them is a build-ticket concern, not a decision.

### Deliberately left open

- **Quarantine, `UNRECONCILED` and `OVERDUE` are placeholders in the mocks.** They are set in
  chrome red, and they visibly fight the red chrome around them. That is ticket 04's problem
  arriving early, not a proposed treatment. `LegionSemantics.quarantined` above is provisional
  until ticket 04 resolves.
- **M3 `contentColorFor` collision audit.** The hard invariant from `Theme.kt` - no two of the
  twelve early `ColorScheme` roles may share a raw value - is NOT satisfied by this table as
  written, because it is a palette, not a scheme. Assigning these values to M3 roles with the
  required nudges is a build-ticket step with its own verification, exactly as it was in the
  MILSPEC build. This is the bug class that shipped once already (CLAUDE.md §8, L11).
- **Contrast ratios are unmeasured.** Nothing on this page was computed or read on a device.
  Ticket 10 owns it, and a failure there reopens this ticket for a named revision rather than being
  absorbed quietly.

### Assumptions ledger

| Claim | Tag |
|---|---|
| The three takes render as described at 340px | `built` (browser, not a device) |
| VACUUM has the highest contrast of the three | `reasoned` - arithmetic on the values, not measured |
| Pure black is unlit pixels on Kevin's Oppo A17K | `reasoned` - assumes an OLED panel, NOT verified |
| Green needed revising to clear the mint | `built` - visible in the mocks, judged by eye |
| Real data shapes and copy match the shipped screens | `traced` - grepped from `TodayScreen.kt`, `BodyScreen.kt`, `LedgerScreen.kt` |
| Typeface is a placeholder platform mono | `built` - ticket 02 picks the real face |

## Revision 2026-08-14 (from ticket 06)

**`good` `#7BE86A` is REMOVED from the palette. Green is dropped entirely.**

This ticket said a failure would reopen it for a named revision rather than being absorbed quietly.
This is that revision. It arrived from ticket 06 rather than from ticket 10, but the mechanism is
the one this ticket asked for.

[Ticket 06](06-chart-kit-recolour.md) ran the `dataviz` skill's palette validator against this
palette. Green fails separation against mint on normal vision (dE 10.4, floor 15) **and** against
amber under deuteranopia (dE 5.5, floor 8). Four alternative greens were tested; all fail both.
Green is geometrically squeezed between mint and amber and no value exists that clears both.

**Section "Sub-questions the ticket asked, answered" item 4 ("Does green survive? Yes, as a rare
hue") is SUPERSEDED.** So is the `credit` row of the `LegionSemantics` mapping.

| Field | Was | Now |
|---|---|---|
| `credit` | `good` `#7BE86A` | `data` `#57EFC6`, with a leading `+` and the word `CREDIT` |

The field name stays - name stability is what keeps a retheme out of the screen files - and it now
resolves to the same value as `debit`. That is intended, not an oversight: the field still documents
intent at the call site even when the two values coincide.

The palette is now genuinely two-hue: mint is every value, amber is every highlight, red is chrome.

**The honest lesson.** This ticket identified the exact risk in writing - "both takes put their
green close enough to the mint that a credit did not separate from the seven debits above it" -
acted on it by eye, and under-corrected. The eye said "that is better." The arithmetic said it was
still a hard fail. **Judging colour separation by eye is not a check.** The validator exists, it
takes one command, and it should have been run here rather than three tickets later.
