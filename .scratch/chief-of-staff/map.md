---
map: chief-of-staff
title: "Alfred as executive of the estate: what exists, the four gaps, and the one number that could lie"
charted: 2026-09-12
charted-by: "Kevin + Opus"
effort: "`.scratch/chief-of-staff/`"
tickets: 6
open: 6
status: open
tags: [map]
---

# Alfred as executive of the estate

**Kevin, 2026-09-12:** *"the direction i actually wanna go is alfred will be the executive of my
estate, my chief of staff. advise me on my net worth, my stuff that needs doing, errands,
schoolwork, advise on career everything"*

## What already exists, because it is most of the skeleton

This is not a new capability. `aspect-advisors` is a 21-ticket map and **every ticket is resolved**:

| Built | What it is |
|---|---|
| Five advisors | BIO, LOG, FLEET, CRED, **HOME** |
| `HomeDigestBuilder` | **The cross-aspect one** - one headline per aspect, the gap that matters, any goal visibly off track. This IS the chief-of-staff shape |
| Playbooks | Real doctrine per aspect, researched not invented |
| `AdvisorProposalExecutor` | An allowlisted write door - the advisor can ACT, and Kevin accepts first |
| Goal store + advice log | What he is aiming at, and what was said before |

**The finance boundary is already drawn and is correct.** `CredPlaybook` states it is not a licensed
financial advisor, tax professional or insurance agent; never recommends specific securities, funds,
tickers or asset allocations; may name account types only generically. Nothing on this map reopens
that, and "advise me on my net worth" does not require reopening it: **telling Kevin what he is worth
is reporting, and telling him what to buy is advice.** The first is arithmetic over his own records.

So the honest position is: the chief of staff is largely built and has four holes.

## Gap 1: net worth does not exist, and it is the dangerous one

There is **no assets or liabilities concept anywhere** - not on the phone, not on the server. Ledger
holds transactions and bank-account balances. Fleet holds cars with no value. Nothing holds a loan, a
401k, a brokerage account, or a house.

**A net worth figure is the most dangerous number this app could ever display**, and it is worth
saying why before anyone builds it. Every other figure LEGION shows comes from one provenance: a
reconciled statement, or an estimate that says so. A net worth is an aggregate of things whose
provenance is wildly different:

| Component | Provenance |
|---|---|
| Checking balance | Reconciled against a printed statement - `DETERMINISTIC` |
| Car value | A guess. Nothing prints it |
| 401k / brokerage | Hand-typed, or a statement nobody ingested |
| Mortgage / loan | Hand-typed, decays predictably, nobody updates it |

Summing those into one number and rendering it plainly would be **exactly** the failure §4 rule 5
and rule 7 exist to prevent: a figure that reads as fact while resting on guesses. §4 rule 8 bites
too - whatever anchors each component has must be stored beside it, or nobody can ever re-verify the
total.

The rule this map proposes: **a net worth is never one number.** It is a figure plus, in words, how
much of it is verified - and the unverified part is named, not folded in.

## Gap 2: career is not an aspect, and adding one is a ruling

CLAUDE.md §1: *"Six domains, and the list is a decision - a seventh is a ruling, not a refactor."*
There is no career code anywhere. Kevin asked for career advice explicitly, so this is his call to
make rather than something to slide in.

It is also the aspect with the least falsifiable data. Fleet has an OBD dongle; ledger has
statements; career has... what? Without an external anchor, a career advisor is a model talking about
a man's life from memory, which is precisely what CLAUDE.md's §7 memory rule forbids
(*"memory stays anchored to external falsifiable facts"*).

## Gap 3: schoolwork cannot be advised on, because nothing syncs it

`canvas-integration` ticket 01 (filed today) fixed the phone being unable to SEE assignments. It did
not fix the deeper problem: **there is no Canvas sync at all.** The only data is a snapshot pulled
2026-09-01, ten days stale, whose own note says it was truncated at 50KB of an unknown larger total.

An advisor cannot advise on coursework it cannot see, and stale coursework is worse than none -
"nothing due" about a week Canvas knows about is the confident-and-wrong failure this codebase keeps
finding.

## Gap 4: "what needs doing" is scattered across four stores

Errands, chores and to-dos live in `checklists`, in `list_items`, and as `EventKind.TASK` rows. The
HOME digest reads aspect digests, not a unified "what is outstanding across everything" view. Kevin
asked for exactly that view and it does not exist as a single question anyone can ask.

## What this map does NOT reopen

- **The advisor architecture.** Propose-accept-write, the allowlist, the playbooks and the digest
  builders all stand. This map adds data and one aspect; it does not redesign the machinery.
- **The compulsion ban.** A chief of staff that raises things unprompted is governed by §7's
  compulsion test, already written and already testable: anchored to a verifiable fact, actionable
  now, never referencing his absence or engagement, silenceable in one instruction.
- **The finance advice boundary.** `CredPlaybook` has it right.

## The tickets

| # | Type | What | Blocked by |
|---|---|---|---|
| 01 | decision | Is career a seventh aspect, and what falsifiable data would anchor it | - |
| 02 | decision | What a net worth is allowed to be: components, provenance, and how the unverified part is said | - |
| 03 | build | Assets and liabilities: the tables, their anchors, and the hands paths to maintain them | 02 |
| 04 | build | Net worth as a figure plus what is unverified, never one number | 02, 03 |
| 05 | build | One outstanding view: what needs doing, across checklists, reminders and tasks | - |
| 06 | build | Canvas sync on the server, so schoolwork is current enough to advise on | - |

**Order.** 02 before anything touches money - the ruling decides the schema, not the other way round.
05 is independent and is the cheapest real step toward what Kevin asked for. 06 is `django-engine`
work and can run alongside. 01 is his call and gates nothing else.
