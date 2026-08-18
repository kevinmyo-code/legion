---
map: quant-viz
ticket: 11
title: 11 - Today tab glance viz
type: ""
status: resolved
status-detail: "2026-08-16, verified built in the all-effort sweep"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# 11 - Today tab glance viz

pre-flight check, zero charts") - recorded in map taste call 1. Fixed pane order stays; the
panes gain shape, not new sections.

## Spec (`ui/TodayScreen.kt`, `ui/TodayGapResolvers.kt`)

1. **INTAKE (hero):** keep the number + `DeckMeter`; add a 7-day kcal `DeckSparkline` under the
   meter (same series BodyScreen's intake sparkline uses - reuse the state/source via the same
   controller call, do not duplicate math; unlogged day = null gap).
2. **SYSTEMS SWEEP rows** each gain an inline `DeckSparkline` directly under their `DeckRow`:
   - SLEEP: last 7 nights' duration hours (null gap for unlogged nights) - same series the Body
     sleep sparkline reads.
   - TRAINING WK: skip the sparkline if no cheap series exists on the already-loaded data; do
     NOT add new queries just for this row - state the skip in the ticket-close note.
   - LEDGER <month>: month-to-date cumulative daily spend sparkline built from the SAME
     `budgetVsActual`-window rows the sweep row already loads (coverage gap rule as ticket 03).
     If the row does not already hold per-txn rows, reuse `bucketDailySumCents` over what it
     does hold; if only an aggregate exists, add ONE controller call reusing existing ledger
     reads - never a new SQL definition of spend.
   - FLEET: skip (the sweep row is a status, not a series).
3. **AGENDA:** unchanged.
4. Do not add drilldowns here; taps still go where they already go.

## Verification

- [ ] Any new pure mapping unit-tested.
- [ ] `compileDebugKotlin -Pnokey` + `testDebugUnitTest` green.
- [ ] No duplicated series math (sparkline sources traced to the same controller calls the
      module screens use - name them in the commit message).
- [ ] On-device: Today shows intake + sleep + ledger sparklines at a glance.

## VERIFIED BUILT 2026-08-16 - closed

Swept against HEAD during the all-effort verification. **Every one of this effort's 16 tickets was
built, wired to a production path, and unit-tested where it had a pure layer.** Each has a landing
commit. `MEMORY.md` was right that the effort shipped; **these `Status:` lines were simply never
flipped**, so the tracker counted 16 phantom open tickets and any frontier query was wrong.

Full per-ticket evidence is in the sweep record on `../map.md`.

### Exception on this ticket - the SLEEP sparkline relocated

INTAKE and cumulative-spend sparklines are on Today and wired. TRAINING and FLEET were skipped, which
this ticket explicitly permits. **The SLEEP sparkline was built (`087d8f9`) and then left Today when
SYSTEMS SWEEP was dissolved by `a09aa68`**; the same series now renders on `ui/BodyScreen.kt:439-440`.

**Relocated, not lost** - but this ticket's own Today requirement no longer holds. Recorded rather
than quietly closed.
