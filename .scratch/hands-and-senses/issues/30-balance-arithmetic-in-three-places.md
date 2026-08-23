---
map: hands-and-senses
ticket: "30"
title: "The balance formula is written out in three places again"
type: bug
status: built
status-detail: "Fixed: both voice sites read availableCents; a pinning test proves it by failing when the formula is re-derived."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# The balance formula is written out in three places again

Found by the bug hunt, in the one place the codebase already carries a written warning against it.

`AccountBalance.availableCents` (`ledger/LedgerController.kt:1230`) exists BECAUSE of a real
incident on 2026-08-07: the screen and the voice tool each summed the balance formula by hand, the
screen's copy dropped a term, and the app said 316.89 out loud while showing 440.68. Its doc comment
says, in words, *"Every surface must read it from here rather than adding the terms itself."*

Two voice call sites add the terms themselves:

- `service/LiveToolbox.kt:3529` (`get_balance`) - `(postedBalanceCents ?: 0L) + provisionalDeltaCents + pendingDeltaCents`
- `service/LiveToolbox.kt:3632` (`log_pending_transaction`, the balance it speaks back) - the same
  sum plus the new amount

The screen reads the property correctly (`ui/ledger/LedgerRows.kt:174`).

**Arithmetically identical today**, so nothing is wrong on Kevin's phone right now. That is exactly
what makes it worth fixing: the next change to `availableCents` - a term, an exclusion, a rounding
rule - updates the screen and silently does not update what the assistant says. The same fact, the
same file, the same shape, the second time.

## Build

1. Both call sites read `availableCents` (and the pending one adds only its own new amount to that
   property's value, never re-summing the base).
2. **A test that pins them together** - the guard the 2026-08-07 fix never got. Something that fails
   if any surface re-derives the formula: assert the tool's spoken figure equals the property for a
   fixture with all three terms non-zero and mutually distinct, so a dropped term cannot pass.
3. While in there: `ledger/LedgerDedup.kt` was NOT audited this session. Say whether it holds a
   duplicated fact or not - a one-line honest verdict, not a rewrite.

## Verification

- Suite green both ways, one run fresh. `python tools/voice_guide.py` exit 0 (no copy change
  expected - if a spoken figure's wording changes, the copy follows).
