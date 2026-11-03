---
map: procedures
ticket: "09"
title: "The first procedures and their exact steps"
type: grilling
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-procedure-format-and-storage]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# The first procedures and their exact steps

## Question

Which 3-4 procedures go first, and what are their steps? Picked from [../research/call-sequences-today.md](../research/call-sequences-today.md) by round trips
saved.

## Recommendation

1. **`car_check`** (phone 2-3 round trips via `ask_fleet`; head unit up to 5 via `ask_engine`;
   web 2):
   `tool next_service` -> `tool vehicle_recalls` (per vehicle, parallel) -> `check` any past due
   -> `say`. States every caveat each tool states: miles-to-go is an ESTIMATE, a failed recall
   lookup means unknown not none, recall applicability is unknown.
2. **`money_check`** (phone 2-3 blocking turns; web 2-3):
   `tool monthly_spend` -> `tool account_balances` -> `tool grocery_spend` (this month) -> `say`.
   Repeats UNVERIFIED where the tools say it, never adds across currencies, "as of" on bank
   balances.
3. **`morning_briefing`**: wraps the brief, does not replace it. `compute brief_latest` (reads
   `brief.models.Brief`, its sections as written by `brief/compose.py`) -> `tool list_events`
   (today) -> `tool list_checklists` (due) -> `say`. Phone keeps calendar + weather it reads
   locally via `get_sitrep`; decide whether `get_sitrep` calls this or stays as is.
4. **Laptop: deferred** to ticket "Laptop worker" (Claude session is parked).

Every `say` states what did not happen ("recalls could not be checked, so whether there are any
is not known").

## Open for Kevin

- Does `get_sitrep` on the phone switch to `morning_briefing`, or stay and only web/head unit use
  the procedure?
