---
map: ledger-drive-ingestion
ticket: 08
title: "What does the ledger UI show?"
type: prototype
status: resolved
status-detail: ""
blockers: ["02", "03", "05"]
blocked-by: ["[[02-design-language]]", "[[03-ingested-file-ledger]]", "[[05-batch-ingestion-mechanics]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# What does the ledger UI show?

## Question

The ledger surfaces are the reason this effort exists and the hardest thing to make legible: dense
tables of money on a phone screen.

Prototype and settle:

1. **Folder connection.** Picking the Drive folder, showing which folder is connected, and
   changing or disconnecting it. Include the state where the persisted permission has been revoked.
2. **Batch progress.** What sixty files being processed looks like. Per-file rows, an aggregate
   bar, or both, built against the progress contract from the batch-mechanics ticket.
3. **The spend gate.** Where the approval prompt appears in the flow.
4. **Transaction list.** Grouping (by account, by month, flat), what a row shows on a narrow
   screen, and how a `LLM_RECONCILED` row is visually distinguished from a `DETERMINISTIC` one.
   That tag exists for audit and the UI is where it earns its keep.
5. **Balances.** `latestBalanceCents` exists per account. What the top-level number is when several
   accounts and two currencies (SGD and USD) coexist. Do not invent an FX conversion; that is out
   of scope for this map.
6. **Quarantine.** How a failed statement is surfaced. Enough to unblock building; the full review
   workflow is still fog on the map.
7. **Empty states.** No folder connected, folder connected but empty, everything already ingested.

Produce Compose previews to react to, not prose. Reuse whatever the design-language ticket settled
rather than inventing a second visual vocabulary.

---

## Resolution (2026-08-02, prototype rendered on device)

**Prototype branch: `proto/ledger-ui`, commit `476318e`.** Not merged, never to be merged.
`app/src/main/java/com/kevin/legion/ui/ledger/ProtoLedger.kt` plus a temporary `MainActivity` host
driven by an intent extra. Rendered on the **Oppo A17K at 360dp**, not just in a preview pane.

No real statement bytes were read. All fake data, hand-written at realistic lengths. No Gemini key
touched.

### 4. Transaction list: VARIANT B "STREAM" WINS

Three radically different takes were built and rendered:

| Variant | Idea | Rows on screen | Verdict |
|---|---|---|---|
| A "Statement" | Printed-statement mimicry: date / desc / amount / running balance columns | 11 | Rejected |
| B "Stream" | No columns. Description gets full width and never truncates, amount beneath it, date in a gutter. Balance dropped from the row | ~10 | **CHOSEN** |
| C "Register" | Max density. One line per txn, hard truncation, balance cut, provenance as a leading glyph | 11 in ~1/3 the height | Rejected |

**Why B.** On a phone the merchant string is what you are actually scanning for, and truncating it
is the real failure mode. B is the only variant that never truncates. It costs roughly 3x C's
vertical space, and that was accepted deliberately.

**Defects the render exposed, which reading code would not have:**

- **A wraps.** `-1200.00` broke across two lines - the amount column is too narrow at 360dp once the
  balance column is also present. Three numeric columns do not fit.
- **A inverts its own hierarchy.** The balance column rendered visually heavier than the amount, so
  the derived number dominated the actual one.
- **Truncation eats the wrong end.** BofA descriptions are **prefix-heavy**
  (`CHECKCARD 0701 TRADER JOES #452 SAN JOSE CA`), so right-truncation in A and C removes the
  merchant and keeps the boilerplate. This is a data-shape finding, independent of variant.
- Ticket 04's twin transactions render as two identical adjacent rows, correctly and by design.

**Fixes to apply when building B for real:**
1. The date gutter is doing little work - reconsider its width or fold the date into the row.
2. **Strip known description prefixes at display time** (`CHECKCARD \d{4} `, `DES:`, `INDN:`). Not
   required by B since it does not truncate, but it removes noise from the dominant line. Display
   only - the stored `description` is never modified, same rule as ticket 04's normalization.
3. Provenance stays the inline `read by AI` label, which rendered unambiguously. **Not** C's `~`
   glyph, which is cryptic, and not a colour-only signal.

### 5. Balances: per-currency, stacked, NO FX

Rendered and accepted. SGD and USD never combine, and no exchange rate is applied anywhere -
explicitly out of scope for this map.

An explicit line states it: *"Not combined. No exchange rate is applied."* An invented headline
number would be exactly the unstated-value problem CLAUDE.md §4 rule five exists to prevent.

### 1, 2, 3, 6, 7 - built, NOT visually reviewed

Single takes exist in the prototype for folder connection (including the revoked-permission state),
scan progress against ticket 05's `ScanState`, ticket 06's spend gate, quarantine rows, and the
three empty states. **Kevin has not seen these rendered** - the phone re-locked between captures and
the screenshots did not land. Their `@Preview`s are in the prototype file and work in Studio.

Treat them as **provisional**, not settled:

- **Empty states are three distinct messages, not one.** "No folder connected" / "Nothing new" /
  "Folder looks empty, Drive may still be syncing" - the third exists because of the probe's
  stale-listing finding and must never read as an error.
- **Quarantine row** = filename, the gate's own reason in plain language, and a RETRY action, using
  `semantics.quarantined` as a leading 2dp bar rather than a coloured background.
- **Gate** follows ticket 06 exactly: count leads, cost is a labelled secondary estimate in
  `semantics.estimated`.
- **Revoked permission** states plainly that nothing already imported is affected.

### Theme validation, incidental but load-bearing

**The Instrument theme from ticket 02 has now been rendered on hardware for the first time.** It
had only ever compiled. Mono numerals align down the column as intended, hairlines read correctly
against the near-black ground, and `credit` green is the only coloured money. MEMORY.md's "the theme
compiles but has never been rendered" caveat is now closed for the dark scheme. **The light scheme
is still unrendered.**

### What this ticket does NOT settle

- The full quarantine review workflow. Still fog on the map.
- Prefix-stripping rules beyond the three obvious BofA ones.
- Anything about fleet or pantry screens. **Ticket 09.**
