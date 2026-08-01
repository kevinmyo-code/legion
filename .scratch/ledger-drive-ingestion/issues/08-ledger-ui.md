# What does the ledger UI show?

Type: prototype
Status: open
Blocked by: 02, 03, 05

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
