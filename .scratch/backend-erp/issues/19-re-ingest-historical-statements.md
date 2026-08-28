---
type: build
status: open
blocked_by: []
map: backend-erp
---

# Re-ingest the historical statements, so the verified ledger history can be uploaded

**Left behind by ticket 12's resolution on 2026-08-28, per CLAUDE.md section 12: a resolved
decision ticket creates its build ticket in the same commit, or a fully-decided and entirely
unbuilt feature vanishes from the board and reads as finished work.**

Ticket 12 chose option 1 - re-ingest the historical statements from Drive so the deterministic
parsers read the anchors off the documents again, legitimately. Everything about that decision is
made. **None of the re-ingestion is built.**

## What exists already

- **The read-only dry run**, built and run on the A25 2026-08-27 (`c53b167`). It enumerates every
  `IngestedFile`, tries to re-open each through its saved `treeUri`, and reports what would be
  recovered. It writes nothing.
- **The two-anchor deterministic branch of `commit_statement`**, applied to `HomeERPBackend` and
  proven by the 17-case corpus. A re-ingested statement that yields a read opening balance, a read
  closing balance and a passing `closing - opening == sum(lines)` commits as `DETERMINISTIC` with a
  NULL `stated_total_cents`. That is what makes this ticket worth doing at all.

## The device half, and it must happen first

The dry run found **107 of 107 files unreachable**, and the cause is not the files - LEGION holds
**zero persisted SAF URI grants**, destroyed by the `connectedAndroidTest` uninstall of 2026-08-26
(the same event that took `files/`, the receipt photos and the Keystore key).

1. Re-connect the Drive statement folder in the app, which re-grants the persisted URI permission.
2. Re-run the dry run.
3. Only then read the number. If files resolve, this ticket is a build. If they do not, the ticket
   changes shape and ticket 12's option 2 comes back onto the table.

**Nobody should build the write path before step 3 reports a non-zero recoverable count.** That is
the whole reason the dry run was built read-only, and it already earned its existence once.

## What to build, once the dry run comes back non-zero

- A real re-ingestion pass over the recoverable files, writing through the SAME `IngestPipeline`
  path a fresh file takes - never a bespoke shortcut that skips the gate.
- Anchor persistence, so this cannot recur: the gate's inputs (opening, closing, stated total when
  the bank prints one) are stored, not just its verdict. This is CLAUDE.md section 4 rule 8, added
  by ticket 12's closure.
- `LedgerReconcile` then uploads `DETERMINISTIC` rows instead of reporting them in `Report.skipped`,
  and `isClean` stops excluding them.

## The gate this ticket holds, and it is real

**The deterministic statement parsers must NOT be retired until this closes.** Ticket 03 ruling 3
retires them; ticket 12 added this second gate on top of C4's. Retiring them first means every
historical statement has to be re-processed by hand through an LLM to recover anchors a parser
reads for free.

## Not blocked on anything in code

`blocked_by` is empty deliberately: nothing in the repo blocks this. It waits on a human with the
phone, which the board has no way to express.

## One adjacent gap, noted here rather than lost

**`LedgerReconcile` has no hands path.** `BackendMigrationScreen` offers places, pantry, events and
fleet; ledger is absent, so the one reconcile that would upload the provisional rows cannot be run
from the app at all - the same "built, tested, and unreachable" defect that screen's own doc comment
was written to fix for the other three. It is left out of this commit deliberately rather than
bolted on: the row is only worth adding once this ticket's re-ingestion decides what `LedgerReconcile`
actually uploads, since today it can upload nothing but `UNRECONCILED` rows.

## DEVICE PASS 2026-08-28: the dry run is still 0/107, and the cause is NOT a lapsed grant

Run from this machine (the A25 came up on wireless debugging for the first time here). Today's build
installed with `adb install -r` - **no uninstall, no data loss** - after proving the installed APK's
signer matches this machine's debug keystore byte for byte
(`fd819ada7fdbf98f4b072b511d2dff33cb3dc68b720b8a9a0ff862ebd03aac01`). That kills a standing premise:
MEMORY.md said device work must happen on the Kwin laptop because the keystore lives there. **Both
machines carry the same debug key.** Install verified by on-device `sha256sum`, not by "Success".

**Ticket 12's diagnosis was incomplete.** It recorded zero persisted URI grants and concluded the
`connectedAndroidTest` uninstall had killed them. A grant EXISTS now - and the dry run still reports
**0 recovered, 107 unreachable**, twice, against two different connected folders.

### What the database actually says

`ingested_files` holds 220 rows across FOUR distinct `treeUri` values plus 7 rows with none:

| rows | state | which folder |
|---|---|---|
| 77 | INGESTED | DBS - `Cashline Statement_*.pdf`, `Deposit Account Statement_*.pdf` |
| 24 | INGESTED | BofA - `eStmt_2025-*.pdf` .. `eStmt_2026-08-06.pdf` |
| 6 | INGESTED | BofA card - `july creditcard.pdf`, `august creditcard.pdf` |
| 7 | INGESTED | no treeUri at all (CSV, picked as files rather than scanned) |
| 24 | DUPLICATE_CONTENT | the tree that WAS connected when this session started |
| 4+36+34+2 | NEW / UNREADABLE | spread across the same trees |

**The folder that was connected held ZERO `INGESTED` rows.** All 28 of its rows are
DUPLICATE_CONTENT or NEW. So the 2026-08-27 note that "the tree URI matched a saved one character
for character" was true and misleading: it matched the *duplicates* folder. The dry run covers
exactly the 107 INGESTED rows that have a treeUri, and none of them lived there.

### Then the second folder failed too, and that is the real finding

Reconnected to the subfolder that visibly CONTAINS those statements (confirmed by eye in the picker
- the BofA PDFs are right there, printing their beginning and ending balances). Re-ran. **Still
0/107.**

Its tree URI is `...LUHeuChw1OnQ6JMp87_VQymie_SXvp1z0sBE5vDzyGOzpt36-g8%3D` - **not equal to any of
the four stored values.** Drive document ids are stable, so this is not the folder those rows were
ingested from. The rows point at *original* documents; this folder holds *copies*, which is exactly
why its own rows are DUPLICATE_CONTENT.

### So the design, not the permission, is what blocks this

**The dry run resolves each row by its saved `treeUri` + `driveFileId`.** That only ever works while
the exact folder a file was ingested from is still connected, still exists, and still holds that
document id. With four historical folders and one grant at a time, it can never clear more than one
folder's worth - and where the originals are gone, it can never clear them at all.

**The fix is to resolve by CONTENT, not by address.** Every row already stores `contentSha256` - the
gate's own identity for a file. Re-ingestion should scan whatever folder IS connected, hash each
file, and match a row by content hash. The duplicate copies are byte-identical to the originals by
construction (that is what made them duplicates), so the anchors are recoverable from them even
though the original document ids are unreachable.

That is a change to what this ticket builds, not a new decision: option 1 said "re-ingest to recover
real anchors", and content-matching is what makes option 1 reachable. It also removes the four-pass
folder dance ticket 12 described.

**Not built in this session.** It is a real design change to the money path and it deserves its own
pass rather than being improvised at the end of a long one.
