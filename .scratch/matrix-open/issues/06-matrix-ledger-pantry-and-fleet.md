---
map: matrix-open
ticket: "06"
title: "Build: Matrix ledger, pantry and fleet"
type: build
status: built
status-detail: "Built 2026-10-24, suite green. Owed on the A25: LEGION > Ledger shows balances with their as-of dates and the 20 newest transactions; an unverified or pending row says so in words on its own line; with the engine unreachable the screen says it is the phone copy; LEGION > Pantry opens a receipt to printed prices and then separately labelled estimates; LEGION > Fleet shows each car, its mileage and the next maintenance, matching the Fleet tab row. No writes exist on these three, so the one write for this ticket is none (read-only by scope)."
blockers: []
tags: [ticket]
---

# Build: Matrix ledger, pantry and fleet

Lookups first: ledger balances and recent transactions, pantry stock and recent purchases, fleet cars and next maintenance. Text rows only. Every UNRECONCILED row and every estimate said in words on its row (CLAUDE.md section 4 rule 7, rule 5; never collapsed). Same controllers as the Soft screens.

**Style (Kevin, 2026-10-24): text-only, like Matrix HOME.** Green monospace on black from
`ui/dumb/MatrixScheme.kt`, rows you tap, no cards, icons, images or charts, no rain. A new screen, not
a recolour of the Soft one. Reached from Matrix HOME while the mode is on; the Soft screen is unchanged
when it is off. Every action also says its failure in words.

Verify: ViewModel / UiState tests for the screen's states (loading, empty, unreadable said apart from
empty, error). On the A25: open each from Matrix HOME and do one write.

## What was decided while building

Orchestrator decisions (2026-10-24), as in ticket 03; the LEGION index lists Ledger, Pantry and Fleet.

- **Read only.** The ticket says "lookups first", so there is no write on these three screens and the
  A25 line "do one write" does not apply. Rows that cannot be tapped are plain text rows
  (`MatrixInfoRow`), not buttons that do nothing; only a pantry receipt opens.
- **Ledger.** Balances come from `LedgerController.accountBalances` through `groupAccountBalances`
  (the Soft grouping) and are worded by `MatrixLedgerText.balanceOf`: "not stated" is never 0.00, the
  as-of date or "no balance ever printed" is on the row, and provisional and pending activity use the
  Soft screen's own sentences. Recent transactions are `LedgerController.recentTransactions(20)` with the
  Soft screen's merchant-alias display name. An UNRECONCILED row says "Unverified - no bank statement has
  confirmed this row yet", a voice-logged pending row says "Pending - you logged this yourself ...", a
  category that is still a guess says "(a guess, not confirmed)". `LedgerMirrorStatus.line` is shown
  as the phone-copy sentence when the engine could not be read. Balances and transactions fail apart.
- **Pantry has no stock.** LEGION stores receipts and their lines, not what is on the shelf, so "pantry
  stock" is built as recent receipts plus grocery spend per currency and the screen says "there is no
  stock count". A receipt opens to printed prices, then a separate "Estimates - not on the receipt"
  section with the Soft screen's mandated sentence and an "estimate:" prefix on every macro line (rule
  5). A receipt with an unexplained amount says "Unverified" in words (rule 7), as does a spend total
  with an unreconciled receipt behind it.
- **Fleet.** Cars are `VehicleController.allVehicles` with `mileageLabel` and `nextService`. The status
  sentence is now `CarAspectSummaries.serviceSubtitle`, extracted unchanged from `CarAspectSummaries.fleet`
  so the Fleet tab row and this screen call one function; guess caveats, "odometer not set", and
  "everything scheduled is already due" come with it. Items with no anchor are named as not known. A car
  whose schedule read throws says "couldn't read the maintenance schedule", apart from "no schedule yet".
- **Not shown:** the active-car marker, OBD or telemetry, drives, build sheets, budgets, categories,
  statements; all stay on the Soft screens.
