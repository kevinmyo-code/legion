---
map: web-and-households
ticket: "11"
title: "Report endpoints: aggregates computed once, described in the contract, unverified carried through"
type: build
status: open
blockers: ["02"]
blocked-by: ["[[02b-rls-belt]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Report endpoints

The dashboard's numbers come from `/api/reports/*`, computed in one place so the web, the phone's
widgets (later) and any AI tool read the same figure. ORM aggregates, household-scoped by the same
choke point as everything else, described by drf-spectacular so the generated client types them.

| Route | Returns | The trust rule |
|---|---|---|
| `GET /api/reports/ledger/monthly?months=12` | per month: spend by category (cents), income, net; `unverified_cents` per month = the sum of `UNRECONCILED` rows in it | A month with any unverified row carries the figure AND the word; the client is not trusted to derive it |
| `GET /api/reports/ledger/budget?month=` | per category: target, actual, `unverified_cents` | Same |
| `GET /api/reports/pantry/spend?months=6` | per month: receipt total, `unaccounted_cents`, line count | `unaccounted` named, never as tax |
| `GET /api/reports/body/series?metric=weight|sleep|calories&days=90` | `[{date, value, estimate: bool}]` | Meals' macros are `estimate: true`, from the receipt path's own labelling |
| `GET /api/reports/fleet/summary` | per vehicle: last service, next due, drives last 30 d, codes active | Read-only; nothing here writes |
| `GET /api/reports/today` | counts for the home strip: events today, ticks due, lists open | Cheap, called on every home load |

Money is `Long` cents on the wire as integers, never floats (§4 rule 3). Every response carries
`generated_at` so a stale tile can say how stale.

## Tests

One test per route with a seeded household, plus the tenancy leak test from ticket 02 extended to
reports (household B's report over A's rows returns zeros, not A's figures). A timing assertion
that each route answers under 500 ms on the test database, so ticket 10's escalation trigger has a
number attached.
