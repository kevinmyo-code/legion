---
status: accepted
decided: 2026-11-16
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-11-16 - Net cash at a glance: checking minus every card, pending counted as spent (Kevin)]]"
tags: [adr]
---

# 94. Net cash: checking minus every card, pending counted as spent

## Standing

ACCEPTED, BUILT on `feat/net-cash` (engine route and tool, phone Home, Money and `get_balance`),
not yet run on a phone. **Net cash is the household's linked checking account minus what is owed on
every linked credit card, with charges still pending at the bank counted as already spent, from the
last normal bank sync, always with its as-of time.** It is worked out once, in Django
(`server/finance/net_cash.py`), and every surface says the engine's figure.

## Context

Kevin, 2026-11-16: *"i want to know like my account balance at a glance on the home screen and
finance screens. basically, the 2 accounts checkings and credit card, i want to know how much net
cash i have. so as if the credit card is not credit but instantly deducted like a debit. bcuz
today its hard to know how much exactly i have left at current."* His interview answers are the
rulings below. Safe to spend (ADR 0073) already existed but subtracts bills and rests on the income
plan, an estimate; he asked for the plain figure.

## Decision

**What counts (Kevin).** Checking minus the balance owed on EVERY linked credit card. No other
account (savings, brokerage, the Singapore side). "Linked" means on the bank feed (Plaid): the
accounts in `PlaidItem.accounts` with `type = depository, subtype = checking` and `type = credit`.
More than one linked checking account is summed (a pick; today there is one).

**Pending (Kevin): charges still pending at the bank count as spent.** Plaid's own definitions
(Transactions API reference, `accounts[].balances`):

- `current`: "The total amount of funds in or owed by the account. For credit accounts a positive
  balance indicates the amount owed." Nothing about pending.
- `available`, depository: "typically equals the `current` balance less any pending outflows plus
  any pending inflows", and "does not include the overdraft limit". Credit: the limit less current,
  "less any pending outflows plus any pending inflows": credit left, not a balance.

So `current` is the posted figure and pending sits outside it, which is how this repo already reads
it (`finance/snapshots.py` keeps pending "beside it, never in it"; `finance/runway.py` owes a
card's current plus its pending). Per account, in the ledger's sign:

| Account | Figure | Basis label |
|---|---|---|
| Checking, `available` stored | the bank's `available` (pending already netted by the bank; not added again) | `bank_available` |
| Checking, no `available` | `current` + its pending bank-feed rows | `current_plus_pending` |
| Card | minus `current` + its pending bank-feed rows (a pending charge adds to what is owed, a pending payment or refund takes from it) | `current_plus_pending` |

Pending rows are `ledger_transactions` with `provenance = BANK_API`, `bank_pending = true`, filed
under the account's four-digit mask. A charge Kevin logged by voice as pending (`pending_logged_at`)
is not the bank's and is not in net cash. Counting a pending CREDIT on the card (a payment in
flight) is a pick, so that a card payment with both legs pending does not move net cash.

**What the stored data cannot prove.** That a given bank's card `current` leaves out pending is
Plaid's definition, not checked per bank. The bank's own check (limit minus available equals
current plus pending) needs `balances.limit`, which `ingest.plaid_sync.account_record` does not
store. If a bank folded pending into `current`, pending card charges would count twice. Every
surface names the basis ("owed, pending charges included"), so the figure is labelled by what it is.

**Missing (a pick).** A checking or card record with no balance, no as-of time, no currency or no
four-digit mask is listed in `missing` with the reason in words, and there is NO net figure: never
a partial sum shown as net cash. No linked checking account gives none; parts in two currencies
give none (never summed across currencies). No linked card is fine: net cash is then checking.

**As of (a pick).** The older of the parts' as-of times. No extra bank call and no refresh button
(Kevin): the figure is as fresh as the normal sync. Older than 24 hours, every surface says so in
words; staleness is judged by the reader against its own clock, since a saved copy ages offline.

**Not safe to spend (Kevin).** No bills, no pay, no estimate. Net cash is fact from the bank feed
(ADR 0057), shown plainly.

**The 30-day line: posted basis.** Past pending cannot be rebuilt (a pending row is deleted when it
posts), so each past day is the end-of-day posted figure: each account's signed `current` walked
back through its posted feed rows dated after that day. The last point is the headline figure,
pending included. The line starts at the latest of each account's earliest posted feed row less a
day, and at most 30 days back; shorter, the screen says from when. A card payment moves both sides
by the same amount, so net cash does not move, except between the two legs posting when they post
on different days: the line dips for those days. Tested both ways (`server/tests/test_net_cash.py`).

**Surfaces.**

- Home (Typeset, ADR 0081): one big number above the time headline, the as-of line under it, on
  every fit tier; net only. Tap opens Money.
- Money: the working (checking, each card with its last four and "of which pending", a rule, net
  cash, as-of), the 30-day line with "posted figures only; pending counted in today's point", and
  the floor.
- Voice: `get_balance` with no account named leads with net cash and its as-of time (a
  `say_first` field in its result); the engine's `account_balances` leads the same way. No new tool
  and no description change (the voice setup payload is near its ceiling). Hands and voice read the
  same figure (ADR 0035).
- Offline: the phone shows its saved copy of the engine's answer with "last known" and its age.

**Low cash (Kevin): on screen only.** Below a floor Kevin sets, or below zero, net cash is drawn in
the accent AND says so in words ("net cash, below zero", "below your $500 floor"). Alfred never
raises it unprompted: no greeting, no check-in, no watch. With no floor set (the default), only
below zero is marked. The floor is phone-local (SharedPreferences, like the Money view's other
phone settings): the engine's synced money settings (`FinancePolicy`) have no field for it and one
would need a migration.

## Consequences

- One route, `GET /api/finance/net-cash`, read-only, scoped by `household_of()` / `scoped()`; the
  recruiter visit reads its own copy, which links no bank, and gets "no checking account is linked".
- Open: whether the floor should sync to the web; whether `balances.limit` should be stored so the
  card's pending basis can be checked per bank; the web Money page's net cash line (same route).
