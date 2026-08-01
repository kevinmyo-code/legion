# What makes two identical transaction lines distinct?

Type: grilling
Status: open
Blocked by: (none)

## Question

`LedgerController.isDuplicate` matches on `accountId + txnDate + amountCents + description`. Two
genuinely separate five-dollar coffees at the same shop on the same day collapse into one, and the
second is silently dropped. Kevin's call is to fix this properly rather than live with it.

`LedgerTransaction` already carries `lineRef` and `sourceFile`, so the raw material may exist.
Decide:

1. **What is in `lineRef` today?** Read both parsers. Is it a stable per-statement line index, a
   raw text fragment, or something re-derived on each parse? Its stability across a re-parse of the
   same file determines whether it can carry dedup weight at all.
2. **The correct dedup key.** Probably account plus date plus amount plus description plus an
   occurrence ordinal within the source statement. Confirm that survives the real case this must
   handle: the same transaction appearing in two overlapping statements from the same account.
3. **Overlapping statements.** Two statements covering overlapping date ranges genuinely restate
   the same transactions. That is the case per-transaction dedup exists for, and it directly
   conflicts with preserving twins. Resolve the conflict explicitly.
4. **Existing rows.** Any already-committed data was written under the old key. Does the migration
   need to do anything, or is the installed base zero? (It is almost certainly zero, but say so.)
5. **Tests.** This is exactly the class of bug that passes a compile and fails on real money.
   Specify the test cases, including the twin case and the overlapping-statement case.

Note the failure mode runs both ways: too strict silently drops real transactions, too loose
silently double-counts them. Both corrupt the balance. State which way this errs and why.
