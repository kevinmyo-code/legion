---
status: accepted
decided: 2026-08-02
decided-by: Kevin
source: "decisions.md 2026-08-02"
tags: [adr]
---

# 15. Dedup counts matching rows, it does not test existence

## Standing

ACCEPTED.

## Context

BofA prints genuinely identical lines twice within one statement. DBS never does. The LLM path is nondeterministic. One rule has to be correct for all three.

## Decision

`countMatching` returns a count, not a boolean. Dedup counts per `(accountId, txnDate, amountCents, normalizedDescription)` and inserts `max(0, N - M)` rows. Normalisation is comparison-time only; the stored description is untouched.

## Consequences

- Two genuinely separate identical purchases on the same day will collapse into one. That is rare; overlapping statements are routine. The trade was made knowingly.
- An overlapping statement that contributed zero new rows leaves those rows owned by only one `sourceFileId`, so a naive delete-by-file would lose them.
- Fixed 2026-08-02 by resetting overlapping `INGESTED` files to `NEW` on replacement, bounded by the file's own min and max transaction dates.
