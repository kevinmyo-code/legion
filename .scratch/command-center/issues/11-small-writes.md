---
map: command-center
ticket: "11"
title: "Three small writes: memory, proposals, pendings"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Three small writes: memory, proposals, pendings

Survey runners-up, batched:

1. **`remember` by hand**: MemoryScreen gets an add dialog writing through the same gated path the
   voice tool uses - INCLUDING the read-through gate and category selection. The store is currently
   delete-only by hand.
2. **`log_pending_transaction` by hand**: the pending list renders and clears but cannot add. Add
   dialog, same `LedgerController.logPendingTransaction`. Long cents.
3. **`accept_proposal`**: trace what advisor proposals exist and where they surface. If a pending
   proposal store exists, render it with accept/dismiss through the same function. If proposals are
   ephemeral in-conversation only, SAY SO in this ticket and close that item as
   nothing-to-render rather than inventing a store.

## Verification

- Suite green both ways. Same-function tests per write. On the phone: add a memory, add a pending.
