---
title: The LEGION ledger CSV import format
tags: [docs, ledger]
verified: 2026-08-26
---

# The LEGION ledger CSV import format

[[0006-reconciliation-gate|The reconciliation gate]] normally runs against a deterministic
parser's own extraction. Ticket 03 ruling 3 (`.scratch/backend-erp/issues/03-the-gate-server-side.md`)
retires that path for bank statements: **there is no LEGION-owned deterministic reader of a bank's
own PDF or CSV any more.** Instead, a person feeds their statement to their own LLM (any model,
any vendor - LEGION never sees or pays for that call) and asks it to produce a CSV in the format
below. `com.kevin.legion.ledger.parsers.LegionCsvStatementParser` reads that CSV, and its
extraction back into typed rows **is** deterministic - it is only the step upstream of it, turning
a PDF into structured numbers, that is not. That is why every row this parser produces is tagged
`LLM_RECONCILED`, never `DETERMINISTIC` (ruling 6: provenance names the data's origin, not the
last step that touched it).

This file is the entire contract. It is written to be pasted into a prompt for the user's own
model, which is the point: a stranger cloning this repo has nothing else to read to reproduce it.

## The three anchors, and why the format cannot skip them

Ruling 4 requires three numbers printed by the bank itself, independent of the CSV's own line
items: **the statement's printed total, its opening balance, and its closing balance.** An
LLM-produced CSV has its line items *and* its total from one nondeterministic pass, so a single
anchor could be satisfied by a self-consistent hallucination - the exact failure shape CLAUDE.md
section 4 rule 6 exists to close. Requiring three separately-printed figures means a bad extraction
has to be wrong the same way across three numbers that came from different places on the page.

**If a statement does not print all three, it cannot use this format.** There is nothing to fall
back to inside this parser - a statement with fewer than three anchors is a rule 7 provisional
case (unreconciled, transient, superseded the moment a real statement covers the same window), not
a weaker version of this one. `LegionCsvStatementParser` fails fast and names exactly which anchor
is missing, rather than defaulting a missing figure to zero and letting the arithmetic below
"discover" the problem two steps later.

## The two tables

The CSV is two ordinary tables, separated by exactly one blank line. Both are plain
`text/csv` - no quoting is required by this format because no field is allowed to contain a comma
(see "Fields, and why none of them are quoted" below).

**Table 1 - the header, one row of data:**

```
account_last4,account_nickname,currency,stated_total_cents,opening_balance_cents,closing_balance_cents
1234,Kevin Checking,USD,149550,500000,649550
```

**Table 2 - the lines, one row per transaction:**

```
txn_date,description,amount_cents
2026-07-03,COFFEE,-450
2026-07-11,SALARY,300000
2026-07-20,RENT,-150000
```

A full file looks exactly like the two blocks above concatenated with one blank line between them.
Nothing else - no title row, no trailing summary, no second blank line.

## Field rules, stated so an LLM (or a stranger reading this by hand) cannot guess wrong

| Field | Rule |
|---|---|
| `account_last4` | Exactly 4 digits. The account's own last four - never a full account or card number. This is the identity key, paired with the nickname (see below). |
| `account_nickname` | Free text, non-empty, no comma. Whatever the person calls this account. **Load-bearing, not cosmetic**: two accounts can share a last-4 by coincidence (ruling 5), and the nickname is the only thing that tells them apart. |
| `currency` | Exactly `SGD` or `USD`. Nothing else is accepted - these are the only two currencies the schema's own check constraint allows (`supabase/migrations/20260825000300_aspect_ledger_pantry.sql`). |
| `stated_total_cents` | The statement's own printed total or net movement for the period, as a **plain signed integer number of cents**. Never a dollar string. |
| `opening_balance_cents` | The statement's own printed opening/beginning balance, in cents, plain signed integer. |
| `closing_balance_cents` | The statement's own printed closing/ending balance, in cents, plain signed integer. |
| `txn_date` | `YYYY-MM-DD`. Nothing else - no `MM/DD/YYYY`, no month names. |
| `description` | Free text, no comma (a merchant name with a comma in it must have the comma removed or replaced - this format has no quoting to fall back on). |
| `amount_cents` | The line's own amount, in cents, plain signed integer. **Negative for money leaving the account (a debit/withdrawal/payment), positive for money entering it (a credit/deposit).** This is the same convention `LedgerTransaction.amountCents` already uses on-device - the CSV states it explicitly here so an LLM has no ambiguity to resolve. |

**Every `*_cents` field is required to be an integer token matching `-?[0-9]+` and nothing else.**
No `$`, no thousands separators, no decimal point, no scientific notation, no leading `+`. A
statement's own PDF might print `$1,234.56` or `1,234.56` - the instruction to the user's LLM is to
do that arithmetic itself and emit the integer number of cents, not to hand LEGION a
human-formatted string to reinterpret.

**This is deliberate, and narrower than [[0006-reconciliation-gate|the money parsing]] the retired
PDF parsers used.** Those parsers read `$1,234.56` directly off a real, physically fixed page
layout, where "reinterpret a human-formatted number" was a bounded, testable problem against one
bank's own printing convention. Here the number has already passed through a model, on an
unbounded set of source documents, with no way to verify its formatting assumptions against
anything - so `LegionCsvStatementParser` **rejects** any `*_cents` field it cannot parse as a bare
integer rather than trying to helpfully coerce one. A parse that "helpfully" strips a `$` or a
comma is exactly how a wrong digit would enter the one place the gate cannot catch it: the gate
checks that numbers are *consistent* with each other, never that any one of them is correct, so an
input that's already been silently reinterpreted defeats it before it runs.

## Fields, and why none of them are quoted

RFC-4180 quoting (`"like, this"`) is not supported by this format. That is a simplicity choice, not
an oversight: the whole point of this format is that a paragraph of instructions to an arbitrary
LLM has to produce it correctly on the first try, and "quote a field if and only if it contains a
comma, and double any quote characters inside it" is exactly the kind of conditional rule that
model output gets right most of the time and wrong on the file that matters. Telling the model to
simply never emit a comma inside `account_nickname` or `description` - stripping or replacing one
if the real name has one - is a rule with no edge case.

## What happens when a rule is broken

- **A missing or unparseable anchor** (any of the three `*_cents` header fields, or the header row
  is missing or malformed) is a hard, immediate failure. The parser names which anchor it could not
  read. Nothing is imported.
- **An unparseable `amount_cents` on any line** fails the whole file - not a skipped row. CLAUDE.md
  section 4 rule 6: a line the parser cannot read is a hard failure, never a silent drop, because a
  silently dropped row is exactly as dangerous as one that was silently accepted wrong.
- **Zero data rows** fails the whole file, for the same rule 6 reason: an empty extraction must
  never be able to satisfy the arithmetic below by having nothing to disagree with.
- **The reconciliation check** (CLAUDE.md section 4 rule 2, ruling 4's three-anchor form) then
  runs, checking `sum(amount_cents) == stated_total_cents` and
  `closing_balance_cents - opening_balance_cents == sum(amount_cents)`. Either mismatch quarantines
  the whole file - nothing is written. This is the same arithmetic `public.commit_statement`
  (`supabase/migrations/20260825000600_commit_statement_rpc.sql`) runs server-side; both sides are
  checked against the shared corpus (`app/src/test/resources/gate-corpus.json`) so they cannot
  silently disagree, and the phone's own check exists only so a bad extraction fails fast, locally,
  before any network round trip - the server's check is the one that is actually authoritative
  (ticket 03 ruling 2).

## A prompt paragraph you can paste into your own LLM

> Read the attached bank statement. Produce a CSV with two tables separated by one blank line.
> Table 1 has the header `account_last4,account_nickname,currency,stated_total_cents,opening_balance_cents,closing_balance_cents`
> and exactly one data row: the account's last 4 digits, a short nickname for it, the currency
> (`SGD` or `USD`), and the statement's own printed total, opening balance, and closing balance -
> each as a plain signed whole number of CENTS (multiply dollars/etc by 100, no `$`, no commas, no
> decimal point). Table 2 has the header `txn_date,description,amount_cents` and one row per
> transaction: the date as `YYYY-MM-DD`, a description with no commas in it, and the amount in
> cents, negative for money leaving the account and positive for money entering it. Use only the
> numbers actually printed on the statement - do not compute or guess a total, opening balance, or
> closing balance that isn't stated.
