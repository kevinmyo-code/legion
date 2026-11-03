---
map: procedures
ticket: "05"
title: "Engine down, and the phone's own copies of car and money figures"
type: grilling
status: resolved
status-detail: "resolved 2026-11-03, Kevin: approve all 9 as recommended"
blockers: ["04"]
blocked-by: ["[[04-how-clients-reach-run-procedure]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Engine down, and the phone's own copies of car and money figures

## Question

The phone's `get_monthly_spend`, `get_balance`, `get_next_service`, `get_grocery_spend` compute
their figures LOCALLY (Room, `FleetEngineStore`), while the engine's `monthly_spend`,
`account_balances`, `next_service`, `grocery_spend` compute them in Django
([../research/call-sequences-today.md](../research/call-sequences-today.md)). A procedure runs on the engine. What does the phone do?

## Recommendation

- **Engine reachable: voice uses the procedure** (engine figures). This moves the spoken answer
  onto the Django copy, which CLAUDE.md §7 says is the one place a business rule lives.
- **Engine unreachable: the phone falls back to today's free routing over its local tools and
  says so in words** ("the engine is unreachable, so this is from the phone's copy, as of
  <sync time>"). CLAUDE.md §7: the server going down loses freshness, never function.
- **Retiring the phone's duplicate computations is OUT of this map**; flag it as follow-up, with
  the evaluation ticket showing where the two copies disagree.

## Open for Kevin

- Accept that the spoken car/money answer comes from the engine when it is up?

## Answer

**Accepted as recommended, including the open question.** Kevin, 2026-11-03: *"approve all 9 as recommended"*.

- **Open question ruled: yes, the spoken car and money answer comes from the engine's procedure when the engine is reachable.**
- Engine unreachable: the phone falls back to today's routing over its local tools and says so in words, with the sync time.
- Retiring the phone's duplicate computations stays OUT of this map; the evaluation ticket shows where the two copies disagree.
- ADR 0070. Build: ticket 16.
