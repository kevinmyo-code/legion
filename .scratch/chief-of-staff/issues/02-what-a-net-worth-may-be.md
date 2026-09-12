---
map: chief-of-staff
ticket: "02"
title: "What a net worth is allowed to be: components, provenance, and how the unverified part is said"
type: decision
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# What a net worth is allowed to be

**Kevin, 2026-09-12:** *"advise me on my net worth"*. There is no assets or liabilities concept
anywhere in LEGION, so this decides the shape before anything is built - the ruling picks the schema,
not the other way round.

## Why this needs a ruling rather than a table

Every figure LEGION shows today comes from ONE provenance. A ledger total is reconciled against a
printed statement. A macro says `estimate` in words. A provisional row says `UNRECONCILED` on every
surface that renders it.

**A net worth is the first figure that would mix them**, and the mix is severe:

| Component | Where the truth comes from | Provenance |
|---|---|---|
| Checking / savings | A statement that prints its own balance | Reconcilable |
| Credit card balance | Same | Reconcilable |
| Car | Nothing prints it. A market guess | Estimate, and it decays |
| 401k / brokerage | A statement nobody ingests, or hand-typed | Unverified unless ingested |
| Mortgage / auto loan | Hand-typed; changes every month whether or not anyone updates it | Unverified and silently stale |

Summing those and rendering `$X` is precisely what §4 rule 5 forbids - *"anything the document does
not state cannot be gated, and must be surfaced as an estimate, never as fact"* - and what rule 7
forbids on surfaces. **It is also the most quotable number the app will ever produce**, which is
what makes getting it wrong expensive: a figure a person repeats to someone else.

## What the decision must settle

1. **Which components count.** Bank accounts and cards are obvious. Cars? A house? Do possessions
   count at all, or is this liquid-plus-debts?
2. **How a component's value is anchored.** §4 rule 8: store the numbers the gate checked against,
   not just a verdict. A hand-typed 401k balance has an anchor - the date it was typed and the
   statement it came from - and both must be stored or the figure cannot be re-verified later.
3. **How the unverified part is said.** The map proposes: **a net worth is never one number.** It is
   the figure plus, in words, how much rests on estimates - "USD 82,400, of which 31,000 is estimated
   and last checked 6 weeks ago". Never a single bold total with an asterisk.
4. **What staleness does to it.** A loan balance typed in March is wrong in September in a knowable
   direction. Does a component expire? Does the figure refuse to render past some age, or does it
   say how old its oldest input is?
5. **Whether the advisor may speak it aloud.** `CredPlaybook` already forbids recommending
   securities or allocations, correctly, and that is untouched. Speaking a net worth is reporting,
   not advice - but a spoken figure loses every visual disclosure, so the §7 outcome-verb posture
   applies: the sentence itself has to carry the uncertainty.

## What is NOT in question

The finance advice boundary. `CredPlaybook` states LEGION is not a licensed financial advisor, tax
professional or insurance agent, never recommends specific securities, funds, tickers or allocations,
and may name account types only generically. **Reporting what Kevin is worth is not advice about what
to do with it**, and this ticket does not widen that line by a word.

## Resolution

Kevin rules on 1-5. Ticket 03 then builds the tables the ruling implies, and 04 renders it.
