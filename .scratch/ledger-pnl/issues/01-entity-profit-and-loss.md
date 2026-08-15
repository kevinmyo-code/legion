# One US entity with a monthly P&L, not a row of account balances

Type: build-spec
Status: resolved (4 calls, Kevin, 2026-08-06)
Blocked by: (none)

## Question

Kevin: "instead of many accounts tracking, just put everything into one US entity. SG entity we'll
do it later. so just a P&L across one entity with multiple accounts in there."

Today the ledger's only aggregate is `BalancesSection` - one row per `accountId`, each showing that
account's last printed balance. There is no notion of an entity, no period, and no income/expense
split. `memory/MEMORY.md` records categorization and insights as never-built, and a read of
`ledger/` confirms it: no category, no P&L, no transfer handling anywhere.

### The trap that would make this lie

Read from the parsers and Kevin's real file, `tested`:

| Source | Row |
|---|---|
| `BofaCardStatementParser.kt:43` | `PAYMENT FROM CHK 5042 CONF#9qz4rmxbf ... -350.00` |
| `BofaCsvStatementParser.kt:31` | `Online Banking transfer from SAV 8267 Confirmation# 2245981037 ... 30.00` |
| `currentTransaction_7823.csv` | `PAYMENT FROM SAV 8267 CONF#v1ikbyqeg ... 1300.00` |

So the US side already holds at least three accounts that move money to each other: checking 3119,
savings 1490, card 4146. Put them in one entity and sum credits and debits naively and the $1,300
card payment lands as **$1,300 of income on the card and $1,300 of expense on the savings account**.
Both sides inflate. If only one of the two statements has been imported, the NET is wrong too, not
just the gross.

A P&L that does not handle this is the confidently-wrong-number failure CLAUDE.md §4 exists to
prevent, one layer up from ingestion. The gate guarantees each row is real; it says nothing about
whether summing those rows means anything.

---

## Resolution

### Call 1 - Transfers: MATCHED PAIRS, WITH A KEYWORD FALLBACK

Primary is pair matching, because it is falsifiable: two rows, same entity, different accounts,
equal magnitude, opposite sign, close in time, is a transfer regardless of how either bank worded
it. Fallback is keyword matching for rows whose other side is not present, reported separately
because it is the weaker claim.

**Rejected: keyword rules alone** - a merchant called `PAYMENT SOLUTIONS` would be silently dropped
from expenses and the P&L would quietly understate. **Rejected: pairs only** - importing the card
statement without the checking one leaves its `+1300` counted as income.

### Call 2 - Entity: BY CURRENCY

`USD` is the US entity, `SGD` is the SG entity. Free, automatic, already on every row, no setup, no
mapping to leave unset when a new account appears. Matches the split exactly today (BofA is USD,
DBS/POSB is SGD), and SG arrives for free when Kevin wants it.

### Call 3 - Period: CALENDAR MONTH, WITH A PICKER

One month at a time, defaulting to the most recent month that has data. Matches how statements
arrive and is the easiest thing to check against a real statement by hand.

### Call 4 - Balances: KEPT, P&L ADDED ABOVE

Balances answer "what do I have now", the P&L answers "what happened this month". Different
questions. The provisional-balance work from ticket 12 stays meaningful.

---

## §0 - COVERAGE, AND WHY IT IS NOT OPTIONAL

**Not a call Kevin was asked to make - it is the §4 posture applied to a total, and it is binding.**

A monthly P&L is only true if every account in the entity has statements covering that whole month.
If the card statement for July is imported and the checking one is not, the P&L is not "roughly
right" - it is missing an unknown amount of both income and expense, and nothing on screen would
say so.

`ingested_files` already holds what is needed: `accountId`, `minTxnDate`, `maxTxnDate`, and
`state = 'INGESTED'` (only an INGESTED file passed the gate, which is what makes its window a
completeness claim - see `LedgerDedup.kt`'s `LedgerCoveredWindow` doc).

So every P&L carries a coverage statement, and it is rendered, never just computed:

- Which accounts in the entity have INGESTED files overlapping the month.
- For each, whether the covered window spans the **whole** month or only part of it.
- A plain sentence when coverage is incomplete: which account, and which days are missing.

**A P&L for a month no file fully covers is labelled incomplete. It is still shown** - a partial
answer the user can see the shape of beats a blank screen - but it must never be presented as the
month's result. Same posture as ticket 12's provisional rows: weak is fine, weak-looking-strong is
not.

---

## Build spec

### 1. `ledger/LedgerEntity.kt` (new)

```kotlin
/**
 * A reporting entity: a set of accounts whose money is treated as one pot.
 * Derived from currency rather than configured (ticket call 2) - see this
 * file's doc for why that is not a shortcut.
 */
enum class LedgerEntity(val displayName: String, val currency: LedgerCurrency) {
    US("US", LedgerCurrency.USD),
    SG("Singapore", LedgerCurrency.SGD);

    companion object {
        fun of(currency: LedgerCurrency): LedgerEntity =
            entries.first { it.currency == currency }
    }
}
```

One entity per currency means **no FX ever enters a P&L**, which is the same reason
`BalancesSection` refuses to combine SGD and USD (`ui/ledger/LedgerRows.kt`, "Not combined. No
exchange rate is applied."). Do not add a combined all-entity view; it would need a rate nobody
printed, which is CLAUDE.md §4 rule 5.

### 2. `ledger/LedgerTransfers.kt` (new) - pure, no Room, no Android

```kotlin
/** Why a row was kept out of the P&L. */
enum class ExclusionReason { MATCHED_TRANSFER, SUSPECTED_TRANSFER }

data class ExcludedRow(val txn: LedgerTransaction, val reason: ExclusionReason, val pairedWith: Long?)

data class TransferAnalysis(
    val operating: List<LedgerTransaction>,
    val excluded: List<ExcludedRow>,
)

fun analyzeTransfers(
    inPeriod: List<LedgerTransaction>,
    pairingWindow: List<LedgerTransaction>,
    maxDaysApart: Int = 5,
): TransferAnalysis
```

**Pass 1, matched pairs.** A pair is two rows where all of: different `accountId`; `a.amountCents ==
-b.amountCents`; `abs(a.txnDate - b.txnDate) <= maxDaysApart` in days; neither already consumed.
Greedy, each row consumed at most once - the same credit-consuming discipline `resolveDedup` uses,
and for the same reason: one row must not absorb two.

Prefer the **closest date** among candidate partners, so a monthly repeating transfer of the same
amount pairs with its own month rather than a neighbouring one. Deterministic tie-break on `id` so
the result never depends on list order.

**`pairingWindow` is wider than `inPeriod` on purpose**: the period ± `maxDaysApart` days. A
transfer initiated 30 July and posted 2 August has one leg in each month, and only pairing against a
wider window can see it. Only rows in `inPeriod` are ever returned in `operating` or `excluded` -
the window exists solely to find partners.

**Pass 2, keyword fallback.** Rows surviving pass 1 whose description matches, case-insensitively:
`payment from`, `payment to`, `online banking transfer`, `transfer from`, `transfer to`, or
`conf#`. Marked `SUSPECTED_TRANSFER` and excluded.

**Known false-positive risk, to be stated in the doc comment rather than hidden:** a $50 charge on
one account and an unrelated $50 refund on another within five days will pair and both will drop out
of the P&L. This is why `excluded` is returned rather than discarded, and why the UI lists it. The
alternative - not pairing - double-counts every real transfer, which is the larger and more common
error.

### 3. `ledger/LedgerProfitAndLoss.kt` (new) - pure

```kotlin
data class ProfitAndLoss(
    val entity: LedgerEntity,
    val month: java.time.YearMonth,
    val incomeCents: Long,          // sum of positive operating rows
    val expenseCents: Long,         // sum of negative operating rows, NEGATIVE
    val netCents: Long,             // income + expense
    val operatingCount: Int,
    val excluded: List<ExcludedRow>,
    val accountIds: List<String>,   // accounts contributing operating rows
    val hasProvisionalRows: Boolean,
    val coverage: List<AccountCoverage>,
) {
    val isComplete: Boolean get() = coverage.isNotEmpty() && coverage.all { it.coversWholeMonth }
}

data class AccountCoverage(
    val accountId: String,
    val coversWholeMonth: Boolean,
    val coveredFromMs: Long?,
    val coveredToMs: Long?,
)

fun buildProfitAndLoss(...): ProfitAndLoss
```

`Long` cents throughout, never `Double` (CLAUDE.md §4 rule 3). `expenseCents` stays **negative** so
`income + expense == net` holds without a sign flip anywhere; format it for display, do not negate
it in the model.

`hasProvisionalRows` is true when any operating row is `IngestMethod.UNRECONCILED` - rule 7 requires
any figure containing one to be labelled.

### 4. DAO + controller

`LedgerTransactionDao`:
```kotlin
@Query("SELECT * FROM ledger_transactions WHERE currency = :currency AND txnDate BETWEEN :fromMs AND :toMs ORDER BY txnDate ASC, id ASC")
suspend fun getForCurrencyInRange(currency: LedgerCurrency, fromMs: Long, toMs: Long): List<LedgerTransaction>

@Query("SELECT MIN(txnDate) FROM ledger_transactions WHERE currency = :currency")
suspend fun earliestTxnDate(currency: LedgerCurrency): Long?

@Query("SELECT MAX(txnDate) FROM ledger_transactions WHERE currency = :currency")
suspend fun latestTxnDate(currency: LedgerCurrency): Long?
```

`IngestedFileDao` - coverage for §0:
```kotlin
@Query("SELECT accountId, minTxnDate AS fromMs, maxTxnDate AS toMs FROM ingested_files WHERE state = 'INGESTED' AND accountId IS NOT NULL AND minTxnDate <= :toMs AND maxTxnDate >= :fromMs")
suspend fun coverageInRange(fromMs: Long, toMs: Long): List<AccountCoverageRow>
```
A projection data class, NOT an `@Entity` - must not be added to `CarDatabase`'s entity list (same
posture as `CoveredWindowRow`).

`LedgerController`:
```kotlin
suspend fun profitAndLoss(context: Context, entity: LedgerEntity, month: YearMonth): ProfitAndLoss
suspend fun monthsWithData(context: Context, entity: LedgerEntity): List<YearMonth>
```

Month boundaries in **UTC**, matching every parser's `atStartOfDay(ZoneOffset.UTC)` convention. Do
not introduce a third date convention here; MEMORY.md records a dates-a-day-early bug already.

### 5. UI - `ui/ledger/ProfitAndLossSection.kt` (new)

Above `BALANCES`, under a `SectionHeader("US PROFIT & LOSS")`. Follows ticket 08's "Instrument"
language: mono numerals, hairlines, no cards, `LegionType.amount` for figures.

- Month row with previous/next affordances. Disabled past the ends of `monthsWithData`; never let
  the user page into months that cannot have data.
- Three lines: `Income`, `Expenses`, `Net`. Net takes `sem.credit` when positive, `sem.debit` when
  negative.
- `hasProvisionalRows` -> figures take `sem.estimated` and a stamp reads
  `"includes pending transactions not yet on a statement"`. Same treatment as `AccountBalanceRow`.
- **Coverage, always rendered when incomplete**, in words: which account and what is missing. Not a
  colour, not an icon.
- Excluded transfers: a count plus total, expandable to the rows, each saying `matched transfer` or
  `suspected transfer`. A number the app removed from a total must be inspectable.
- Empty month: say the month has no transactions, not `0.00`.

Wire `state.profitAndLoss` + `state.pnlMonth` into `LedgerUiState`, loaded in `LedgerScreen`'s
existing reload effect and re-keyed on `reloadNonce` so purge and import both refresh it.

### 6. Docs, same commit

- `memory/library/decisions.md` - the four calls, §0, and the false-positive tradeoff.
- CLAUDE.md §10 lists "Ledger categorization / FX / insights" as not built; narrow that line to
  categorization and FX, since a P&L now exists.

---

## Tests

Pure functions, plain JVM. Fixtures **invented**, never Kevin's real rows.

`LedgerTransfersTest`
1. Two rows, different accounts, +1300/-1300, two days apart -> both excluded `MATCHED_TRANSFER`.
2. Same amount, **same** account -> NOT a pair (an account cannot transfer to itself here).
3. Same amount, opposite sign, 30 days apart -> not paired at the 5-day default.
4. Three rows of +50/-50/-50 -> exactly one pair, one row survives. Pins greedy single-consumption.
5. Two candidate partners at 1 day and 4 days -> pairs with the 1-day one.
6. A row whose partner sits outside the month but inside `pairingWindow` -> the in-period row is
   excluded, and the out-of-period row is NOT returned in either list.
7. Unpaired `PAYMENT FROM SAV 8267` -> `SUSPECTED_TRANSFER`.
8. `KROGER #115` -> not excluded (no keyword, no pair).
9. Reversing the input list order changes nothing (deterministic tie-break).

`LedgerProfitAndLossTest`
10. Income, expenses, net computed from operating rows only; `income + expense == net`.
11. Excluded transfers change none of the three figures.
12. Any `UNRECONCILED` operating row sets `hasProvisionalRows`.
13. `isComplete` false when one account's INGESTED window covers only part of the month.
14. `isComplete` false when the entity has accounts but coverage is empty - **assert this
    explicitly**: an empty coverage list must never read as complete (§4 rule 6, a check satisfiable
    by the empty set is not a gate).
15. Only the entity's own currency contributes - an SGD row in range never reaches a US P&L.
16. A month with no transactions returns zeros and `isComplete == false`, not a silent success.

## Verification gates

Binding, per CLAUDE.md §8 L11 - each accounted for as done / deferred-with-a-named-follow-up /
impossible-and-why before this is reported built.

1. `./gradlew compileDebugKotlin -Pnokey` clean (`JAVA_HOME` per CLAUDE.md §6).
2. `./gradlew testDebugUnitTest -Pnokey` green; 269 currently pass and that must not go down.
3. **Check one month by hand against the real statements** (L14). Import Kevin's real BofA files,
   pick that month, and verify income and expenses against the statements' own printed totals with a
   calculator - not against the app's own numbers. Then delete the files. A fixture built from this
   spec proves the code matches the spec, not the bank.
4. **Confirm the card payment appears exactly once as excluded, and in neither income nor expenses.**
   This is the specific failure this ticket exists to prevent; assert it on real data, not only in
   the unit tests.
5. Render the new previews before calling the UI done (ticket 07's gate, L11).
6. On device: page the month picker to both ends and confirm it stops rather than showing empty
   months forever.

## Out of scope

- SG entity as a user-facing surface. The enum carries it, the UI shows US only.
- Any FX or cross-entity total. Needs a rate nobody printed (§4 rule 5).
- Spend categorization (groceries/fuel/etc). Still unbuilt, still its own ticket.
- Budgets, forecasts, trends across months.
- Editing or re-classifying a transfer by hand. The excluded list is inspectable, not editable.
