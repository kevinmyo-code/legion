# Mid-cycle card CSV: a provisional tier under the reconciliation gate

Type: build-spec
Status: resolved (4 calls, Kevin, 2026-08-06) - ONE flagged collision, see §0
Blocked by: (none)

## Question

`BofaCardCsvStatementParser` is a **named rejection**, not a parser. Bank of America's mid-cycle
card export (`currentTransaction_<last4>.csv`) prints no balance and no total, so it can never
satisfy CLAUDE.md §4 rule 2, and the file is refused outright with a message telling the user to
import the monthly PDF instead.

That is correct and it is also the wrong outcome in practice. The card PDF only exists at cycle
close. Between close and the next close, every card transaction is invisible to LEGION - the exact
mid-month staleness gap `BofaCsvStatementParser` closed for the checking account. Checking could be
closed for free because its CSV prints a running balance; the card CSV prints nothing to check
against.

Kevin's ask (2026-08-06): ingest it anyway. Decide how, without making §4 mean less than it does.

### Facts, read from the real file (`tested`, not assumed)

`C:\Users\Kwin\Downloads\currentTransaction_4146.csv`, read 2026-08-06, not copied into the repo:

| Fact | Value |
|---|---|
| Header | `Posted Date,Reference Number,Payee,Address,Amount` - exact match for `BofaCardCsvStatementParser.HEADER` |
| Line endings | CRLF |
| Data rows | **40** (41 lines total, 1 header, no footer, no trailing blank) |
| Sign split | 38 debits, 2 credits (a merchant refund and a `PAYMENT FROM SAV`) |
| Balance column | **absent** |
| Total row | **absent** |
| Account number | **absent from the file body.** Present only in the filename: `_4146` |

Two consequences fall straight out of that table:

1. **No anchor exists.** Not a weak one, none. The `PrintedTotal` path in `LedgerStatementAgent`
   also refuses mixed-sign lists (`LedgerStatementAgent.kt:250`), so even a hypothetical printed
   total would not have saved this file.
2. **An LLM cannot help.** The gate is not failing on extraction, it is failing on the absence of
   something to extract. A model would read all 40 rows correctly and then hit the same
   `"doesn't print balances or a total to verify against"` quarantine, having spent tokens. The
   format is a fixed 5-column CSV; parsing it needs no model at all.

So the ask is not an ingestion problem. It is a request to store rows that cannot be verified,
which is a §4 rule 2 exemption, which is Kevin's call and not the executor's.

---

## Resolution

**A provisional tier, not an exemption.** Unverified rows may be stored; they may never be
indistinguishable from verified ones, and they are transient by construction - a reconciled
statement covering the same dates deletes them.

This keeps the gate's actual meaning intact. §4 rule 2 says nothing partial is committed *as
fact*. A row tagged `UNRECONCILED` and rendered as such is not being asserted as fact.

### Call 1 - Extraction: DETERMINISTIC

`BofaCardCsvStatementParser` stops being a rejection and becomes a real parser. No LLM, no token
spend, no nondeterminism layered on top of rows that are already unverifiable. Scope stays this
exact header; any other CSV keeps falling through to `StatementDispatcher`'s existing generic
quarantine.

**Explicitly NOT built:** a generic LLM CSV path. It was Kevin's literal original ask and it was
declined on the reasoning above - it costs money to arrive at the same refusal. If a second bank's
CSV shows up later, that is its own ticket and its own deterministic parser.

### Call 2 - Account identity: FILENAME LAST-4

`accountId` is the last-4 parsed from the filename: `currentTransaction_4146.csv` -> `"4146"`.
Self-describing, needs no folder mapping, and correct even in Kevin's mixed
`USA Bank Statements/` folder where `accountHint` may name the checking account.

**Rejected:** `accountHint` (the checking-CSV mechanism) - the mixed folder means it would silently
file card rows under the checking account. **Rejected:** resolving last-4 against known full
accountIds - Kevin chose the plain form knowing the consequence in §0.

### Call 3 - Supersede: DELETE ON RECONCILED COMMIT

When any file passes the gate and commits, delete `UNRECONCILED` rows for that card inside the
committing file's `[minTxnDate, maxTxnDate]` window. Provisional rows can never double-count
against verified ones because they no longer exist once verified ones arrive.

Note this is the **inverse** of the existing `IngestedFileDao.enumeratedWindows` machinery, which
drops *incoming* rows a prior committed file already enumerated. This deletes *already-committed*
provisional rows when a reconciled file lands. New logic, not reuse. Session 4's second dedup pass
is untouched.

### Call 4 - UI math: INCLUDED IN THE TOTAL, TOTAL MARKED PROVISIONAL

Provisional rows count toward the displayed balance so the mid-cycle figure is actually current,
and any figure containing one is labelled unverified.

---

## §0 - THE ONE FLAGGED COLLISION (read before building)

**Calls 2 and 3+4 do not compose as stated.** Kevin was shown this consequence when picking call 2
and picked it anyway, so the stored value stands; the mechanism has to absorb it.

- Call 2 stores `accountId = "4146"`.
- The card PDF (`BofaCardStatementParser`) derives `accountId = "4111111111114146"` from the
  document's own printed account.
- These are different strings. Every mechanism in the ledger keys on `accountId` **equality**.

Left alone: supersede never fires (call 3 is dead), the adjusted balance never combines (call 4 is
dead), and `BalancesSection` lists the same physical card twice - which is the exact bug the
whitespace-stripping comment at `LedgerStatementAgent.kt:116` was written to fix, reintroduced
through a different door.

**Resolution: match on a last-4 SUFFIX relation, never on equality, in exactly three places.**
The stored `accountId` is never rewritten - a stored value that says what the source said stays
that way, same posture as `description`.

```kotlin
/** In LedgerDedup.kt or a new ledger/LedgerAccountIdentity.kt. */
fun sameCard(a: String, b: String): Boolean =
    a == b || (a.length >= 4 && b.length >= 4 && a.takeLast(4) == b.takeLast(4))
```

The three places: the supersede delete (§3), the adjusted-balance pairing (§5), and
`BalancesSection` grouping (§5). Nowhere else. In particular **`resolveDedup` is NOT changed** -
loosening its key on a suffix would let a checking account ending 4146 absorb card rows.

**Known weakness, stated rather than hidden:** a suffix match collides if two accounts share a
last-4. Kevin has four accounts today and no collision. `sameCard` must carry that in a doc comment
so the next person meets it as a documented limit, not a surprise.

---

## Build spec

### 1. `data/local/LedgerTransaction.kt`

```kotlin
enum class IngestMethod { DETERMINISTIC, LLM_RECONCILED, UNRECONCILED }
```

**No Room migration. No version bump. DB stays at v5.** `ingestMethod` is `TEXT NOT NULL` with no
CHECK constraint (`app/schemas/com.kevin.legion.data.local.CarDatabase/5.json:1522`), so adding a
constant changes zero SQL and the identity hash holds. Verified by reading the schema JSON, not
assumed. Do not touch `Migrations.kt`.

Doc-comment the new constant with what it means and what it costs: extracted deterministically,
**never reconciled against anything**, transient, deleted when a reconciled file covers it.

### 2. `ledger/parsers/BofaCardCsvStatementParser.kt` - rewrite

Signature changes from `Nothing` to a real return:

```kotlin
fun parse(fileName: String, input: InputStream): List<LedgerTransaction>
```

- Recognition unchanged: first line trimmed must equal `HEADER`, else `UnrecognizedLayoutException`.
  Keep the existing `String(bytes, UTF_8) never throws` comment and its reasoning.
- Reuse `BofaCsvStatementParser.parseCsvLine` - promote it to an internal top-level function in the
  `parsers` package rather than duplicating it. It already handles quoted fields, embedded commas,
  and `""` escapes, which is exactly what `Payee`/`Address` need.
- Split on `"\r\n", "\n", "\r"` (file is CRLF).
- Each data row: exactly 5 fields, else `GenericStatementParseException`. **A row that does not
  parse is a hard failure for the whole file, never a skip** - §4 rule 6.
- `Posted Date` is `MM/dd/yyyy` -> `atStartOfDay(ZoneOffset.UTC).toEpochMilli()`, same as
  `BofaCsvStatementParser.parseDate`. (Note the dates-a-day-early bug in MEMORY.md; UTC start-of-day
  is what every other parser does, so match it and do not invent a third convention.)
- `description` = the `Payee` field trimmed. **`Address` is discarded** - it is whitespace-padded
  city/state already embedded in `Payee`, and `Reference Number` is discarded too. Neither has a
  column and neither is worth one.
- Blank `Payee` or blank `Amount` -> `GenericStatementParseException`.
- `amountCents = parseMoneyCents(amountToken)`. Signs are already correct in the file (debits
  negative), so **do not negate**.
- `currency = LedgerCurrency.USD`, `balanceCents = null`, `ingestMethod = IngestMethod.UNRECONCILED`.
- `lineRef = "$fileName:'${row.take(60)}'"`, matching `BofaCsvStatementParser`. The `Reference
  Number` is in the first 60 chars, so this is unique per row here - better than the checking CSV
  manages. Do not claim dedup weight from it (ticket 04 §1).
- `accountId`: from the filename, per call 2.

```kotlin
private val FILENAME_LAST4 = Regex("""currentTransaction_(\d{4})\.csv$""", RegexOption.IGNORE_CASE)
```

  No match -> `GenericStatementParseException` with a user message naming the expected filename
  shape. **No placeholder, no guess** - same posture as `UnmappedAccountException`.
- Zero data rows -> `GenericStatementParseException`. An empty file must not commit as a
  successful import of nothing (§4 rule 6).
- **Delete the whole `GenericStatementParseException` rejection block and its user message.** Update
  the class doc comment: it is no longer a named rejection, and the comment currently asserts the
  file "must never be handed to an LLM fallback either" - that reasoning survives and should be
  kept, restated as why this parser exists and why its rows are `UNRECONCILED`.

### 3. `ledger/parsers/StatementDispatcher.kt`

The `BofaCardCsvStatementParser` block (`StatementDispatcher.kt:100-108`) currently calls a function
returning `Nothing` and discards it. Change to the standard shape:

```kotlin
try {
    val transactions = BofaCardCsvStatementParser.parse(fileName, ByteArrayInputStream(bytes))
    return DeterministicResult.Success(transactions)
} catch (e: UnrecognizedLayoutException) {
    // fall through to the next parser
} catch (e: StatementParseException) {
    return DeterministicResult.Quarantined(e.userMessage ?: e.message ?: "...")
}
```

Ordering is unchanged and must stay unchanged: after `BofaCsvStatementParser`, before
`looksLikePdf`. Both CSV parsers key on distinct exact header lines and cannot shadow each other.

### 4. `data/local/LedgerTransactionDao.kt` - supersede

```kotlin
/**
 * Deletes provisional rows a reconciled file has now superseded. Suffix-matched
 * on the last 4 - see [com.kevin.legion.ledger.sameCard] and ticket 12 §0.
 */
@Query(
    "DELETE FROM ledger_transactions WHERE ingestMethod = 'UNRECONCILED' " +
        "AND substr(accountId, -4) = substr(:accountId, -4) " +
        "AND txnDate BETWEEN :fromMs AND :toMs"
)
suspend fun deleteSupersededProvisional(accountId: String, fromMs: Long, toMs: Long): Int
```

`substr(x, -4)` is SQLite's last-4. The literal `'UNRECONCILED'` matches Room's TEXT enum storage.

### 5. `ledger/IngestPipeline.kt` - wire the supersede

In the `LedgerIngestResult.Success` branch, **inside `db.withTransaction`**, after the replace-flow
reset and **before** `txnDao.getForAccountInRange(...)`:

```kotlin
if (stamped.first().ingestMethod != IngestMethod.UNRECONCILED) {
    txnDao.deleteSupersededProvisional(accountId, minDate, maxDate)
}
```

Three things this ordering buys, all load-bearing:

1. **The guard.** Without it, importing the card CSV twice makes the second import delete the
   first's rows and then re-insert them - churn that looks like data loss in any observer.
   A provisional file never supersedes anything.
2. **Before `getForAccountInRange`.** That read feeds `resolveDedup`. If provisional rows were
   still present, the reconciled statement's genuine rows would match them as duplicates and get
   dropped - the verified row deleted in favour of the unverified one, precisely backwards.
3. **Inside the transaction.** A crash between delete and insert must not leave the account with
   neither.

`CommitOutcome.Ingested` should carry the deleted count so the import surface can say
"12 pending transactions replaced by the statement" rather than silently shrinking a total.

### 6. Balances and the provisional total

`LedgerController.accountBalances` reads `latestBalanceCents`, which is the newest **non-null**
`balanceCents`. Card CSV rows are all `balanceCents = null`, so today they would change the
displayed balance by exactly zero - call 4 would be a no-op. It has to be built explicitly.

Extend `AccountBalance`:

```kotlin
data class AccountBalance(
    val accountId: String,
    val currency: LedgerCurrency,
    val balanceCents: Long?,
    /** Sum of UNRECONCILED rows dated after the row [balanceCents] came from. Zero when none. */
    val provisionalDeltaCents: Long = 0L,
    /** True when [provisionalDeltaCents] is non-zero. The label is driven by this, never by a colour alone. */
    val isProvisional: Boolean = false,
)
```

- Needs a DAO query for the `txnDate` of the row `latestBalanceCents` came from, then a
  `SUM(amountCents)` over `UNRECONCILED` rows for the same card (suffix-matched) **strictly after**
  that date. Provisional rows dated before the last printed balance are already inside it.
- When there is no printed balance at all (`balanceCents == null`) but provisional rows exist,
  display the delta alone, clearly marked - never render it as a balance.
- **`BalancesSection` groups on `sameCard`**, so `4146` and `4111111111114146` render as one row,
  not two.

### 7. UI

`ui/ledger/LedgerRows.kt:91` already has the provenance slot and the resolution §4 fix-3 rule:
inline text label, **never a glyph, never colour-only**. Follow it.

- Transaction row: `UNRECONCILED` -> `"pending, not verified"` in `LegionType.stamp` / `sem.faint`.
  Do not reuse `"read by AI"`; these are different claims and one is weaker.
- `AccountBalanceRow`: when `isProvisional`, the figure is `balanceCents + provisionalDeltaCents`
  with a stamp beneath naming what it includes, e.g.
  `"includes 12 pending transactions not yet on a statement"`.
- Update the two preview fixtures (`LedgerRows.kt:272`, `:289`, `ui/LedgerScreen.kt:574`, `:586`) to
  include an `UNRECONCILED` row and a provisional balance.

### 8. Docs, in the same commit

- **CLAUDE.md §4** gains rule 7: unreconciled rows may be stored when no anchor exists in the
  source, provided they are tagged `UNRECONCILED`, labelled in every surface that renders them, and
  deleted when a reconciled file covers their dates. Rules 1-6 are unchanged; this narrows what
  "commit" means, it does not weaken what "verified" means.
- **`memory/library/decisions.md`** gets the 2026-08-06 entry with all four calls and §0. Per the
  map's HARD PROCESS RULE, this happens before the effort ends, not after.
- **CLAUDE.md §3** says Room v3. It is v5. Fix while in there.
- **`.scratch/ledger-drive-ingestion/map.md`** decisions list gains a line for this ticket.

---

## Tests

Unit, no device. Fixtures are **invented**, never Kevin's real rows (MEMORY.md standing rule).

`BofaCardCsvStatementParserTest` - the file currently asserts the rejection; those assertions
invert.

1. Recognizes the exact header; 3 invented rows parse to 3 transactions.
2. Wrong first line -> `UnrecognizedLayoutException`.
3. Every row is `IngestMethod.UNRECONCILED` and `balanceCents == null`.
4. Signs preserved: a negative charge stays negative, a positive refund stays positive.
5. Quoted `Payee` containing a comma parses as one field.
6. `accountId` == the filename's last-4.
7. Filename not matching `currentTransaction_<4 digits>.csv` -> `StatementParseException`.
8. A 4-field row -> `StatementParseException`, and **nothing is returned** (§4 rule 6: a row the
   parser does not recognize fails the file, never gets skipped).
9. Header present, zero data rows -> `StatementParseException`.
10. CRLF input parses identically to LF input.

`StatementDispatcherTest`

11. Card CSV bytes -> `DeterministicResult.Success`, not `Quarantined`.
12. Checking CSV bytes still route to `BofaCsvStatementParser` (no shadowing, either direction).
13. Real PDF bytes still reach the PDF parsers untouched.

`LedgerDedupTest` / supersede

14. `sameCard`: exact match true; `"4146"` vs `"4111111111114146"` true; `"4146"` vs `"1234"` false;
    strings under 4 chars do not throw.
15. `resolveDedup` is unchanged by this ticket - assert an `UNRECONCILED` row and a
    `DETERMINISTIC` row with the same key still dedup exactly as before.

Instrumented (`app/src/androidTest`), because supersede is a transaction-ordering property and
`IngestPipelineReplaceFlowTest` already establishes the pattern:

16. Commit provisional rows, then commit a reconciled statement covering the same window ->
    provisional rows gone, reconciled rows present, count correct.
17. The reconciled statement's own rows are **not** dropped as duplicates of the provisional rows
    they replaced (the §5 ordering bug, asserted directly).
18. A reconciled statement covering a *different* window leaves provisional rows alone.
19. Importing the card CSV twice does not delete-then-reinsert (the §5 guard).

---

## Verification gates

Binding, not notes. CLAUDE.md §8 L11: account for every one as **done / deferred-with-a-named-
follow-up / impossible-and-why** before reporting this built. A surfaced gap is a blocking item.

1. `./gradlew compileDebugKotlin -Pnokey` clean. (`JAVA_HOME` per CLAUDE.md §6 - there is no JDK on
   Kevin's PATH.)
2. `./gradlew testDebugUnitTest` green, 221 existing + the new cases.
3. **Run the real file, count its rows independently of the parser** (L14). Copy
   `currentTransaction_4146.csv` in, import it, confirm **exactly 40 transactions**, **38 negative
   and 2 positive**, then DELETE the file. Those counts came from `grep -c` on the real file, not
   from the parser. A fixture built from this ticket's own spec proves the parser matches the spec,
   not the bank.
4. **Render the previews** in `ui/ledger/LedgerRows.kt` for the provisional row and the provisional
   balance before calling the UI done. This is ticket 07's gate and L11's origin story - the red
   body-text bug shipped because it was skipped once already.
5. **On device:** import the card CSV, confirm the pending label renders and the balance says what
   it includes; then import the card PDF for a month the CSV covers and confirm the pending rows
   disappear without the statement's own rows going with them. MEMORY.md: five bugs have survived
   compile, the suite, and review. ADB is unpaired (`adb pair`, `192.168.4.x`).
6. Confirm the Room identity hash is unchanged - the app must open an existing v5 DB with no
   migration. If Room complains, the enum assumption in §1 is wrong and this stops.

## Out of scope

- A generic LLM CSV path (declined, call 1).
- Any other bank's CSV export.
- Resolving whether `4146` should have been the full PAN - §0 absorbs it in three places; revisiting
  the stored value is a separate call.
- Card PAN masking (Kevin declined, MEMORY.md).
- Ledger categorization, FX, insights.
