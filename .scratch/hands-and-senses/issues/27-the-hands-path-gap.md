---
map: hands-and-senses
ticket: 27
title: "Measure which voice capabilities have no hands path"
type: task
status: resolved
status-detail: "Surveyed 2026-08-22: 69 tools, 32 covered / 15 partial / 22 none. The table is the stock-take input; the build order is the Fable session, not this ticket."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Measure which voice capabilities have no hands path

## Why this is a measurement first

ADR 0035 (Kevin, 2026-08-22): **anything LEGION can do by voice must also be doable by hand.**

The existing surface does not comply, and the size of the gap is **unknown**. 66 tools are declared
to the model. Some are plainly covered - `log_meal` has a Body screen, `manage_grocery` has a list.
Some plainly are not - `answer_call` has no button, which is the case that motivated the rule.
Most are unclassified, and nobody has looked.

**This repeats the socket lesson deliberately.** That ticket guessed 101 declarations and the truth
was 66; the guess was wrong in a way that would have changed what got built. A UI backlog argued
from a guessed number is the same mistake with a bigger budget attached.

## What to produce

A per-tool table: tool name, what capability it reaches, whether a hands path exists today, and
where. Three honest verdicts only:

- **covered** - a screen reaches the same capability, named with its file.
- **partial** - the data is visible but the ACTION is not (a value can be read on a screen but only
  changed by voice). This bucket matters most: it looks covered from a distance and is not.
- **none** - voice only.

**A tool that only observes and speaks is covered by the screen that already renders its data.**
`get_sitrep` and `ask_fleet` do not need a button; the rule is about capabilities, not prompts.

## What NOT to do here

**Do not build any UI in this ticket.** The output is the table and a recommended order, nothing
else. Building the easy ones while surveying is how the hard ones end up unmeasured and unbuilt -
and the hard ones are where the rule earns its keep, because they are hard precisely when voice is
least likely to work.

## Verification

- Every one of the 66 declared tools appears in the table exactly once. A tool missing from it is
  the failure mode; a count that does not reconcile against `LiveToolbox.declarations()` is a bug in
  the survey, not a rounding difference.
- Each `covered` verdict names the file that covers it, so the claim is checkable rather than
  asserted.

---

## Survey, 2026-08-22

Produced read-only against the tree at `f2b57d2`. Write/action verdicts are traced (the
`onClick`/controller call site was read, not just an import); negatives are traced by grepping the
whole `ui/` tree for the controller entry point; read-only verdicts are reasoned from the screen's
render surface at capability level, per this ticket's own rule. A reasoned verdict is a candidate
for revision, not a fact.

### The count: 69, derived

`allDeclarations()` holds 94 `fn(name = ...)` entries; `declarations()` filters out the 30 in
`DISPATCHED` (fleet 17, body 7, goals 2, pantry 2, mail 2) and appends the 5 `ask_*` dispatchers.
94 - 30 + 5 = **69**, which reconciles with the earlier measured 66 plus the three that landed this
week (`track_package`, `flight_status`, `place_call`).

### The verdicts: 32 covered / 15 partial / 22 none

| Domain | Covered | Partial | None | The short version |
|---|---|---|---|---|
| Money (12) | 11 | 1 | 0 | Essentially complies. `log_pending_transaction` renders but has no add. |
| Notes/lists/calendar/memory (7) | 6 | 1 | 0 | Complies. `remember` has no add on MemoryScreen - the store is delete-only by hand. |
| Goals (5) | 4 | 0 | 1 | `accept_proposal` has no consent surface. |
| Fleet (10) | 5 | 2 | 3 | Half-built. Build sheet is a whole store with write, read and spend tools and NO screen - FleetScreen even loads `buildSheetCount` and never renders it. `log_service` by hand moves the maintenance clock but never creates a `service_records` row, so cost capture is voice-only. |
| Body (8) | 1 | 7 | 0 | **The largest looks-covered-from-a-distance case.** The tab shows all four data streams (meals, sleep, weight, sets) and can write none of them. Targets are drawn on meters the user cannot type a number into - settable only by accepting a whole generated plan. `undo_last_log` means a misheard log is unfixable by hand. |
| Media (5) | 0 | 0 | 5 | Voice-only wholesale: transport, volume, play, browse, queue. The domain most used in a loud car, which is where the mic fails. |
| Phone (3) | 0 | 0 | 3 | `answer_call`/`decline_call`/`place_call` - the ADR's founding case, confirmed: zero `ui/` callers of `CallActions`. The only dial site in `ui/` is a debug screen with a hardcoded number. |
| Location (5) | 1 | 0 | 4 | `tag_place`/`forget_place`/`get_current_location`/`open_navigation` all voice-only. "Save this as home" is said standing in a driveway; a misheard `forget_place` is permanent. |
| Outside world (4) | 0 | 0 | 4 | `area_info`, crime history, `track_package`, `flight_status` - no renderer for any. |
| Session/system (5) | 2 | 2 | 1 | `why_did_you_say_that` reads an audit trail no screen shows. `get_sitrep` has no weather or newsletter surface and no on-demand button. `end_conversation` by hand only exists as killing the whole assistant. |
| Dispatchers (5) | 2 | 2 | 1 | `ask_mail` is voice-only by explicit prior decision - ADR 0035 now contradicts that ruling, and the conflict needs a decision, not a quiet fix. |

### The ten most consequential gaps, weighted toward mutation and voice-failure moments

1. **answer_call / decline_call** - ringing phone, misheard word, no button, call gone.
2. **place_call** - same class, outbound.
3. **activate_garage** - acts on the physical world at the exact moment (arriving home, engine
   running) voice is least reliable. No button at all.
4. **The four Body logs** (meal, sleep, bodyweight, sets) - one gap, four tools. The tab renders
   everything and writes nothing.
5. **set_meal_target / set_sleep_target** - meters drawn against a number that cannot be typed.
6. **undo_last_log** - voice can write a wrong row and only voice can take it back.
7. **log_service / log_past_service** - cost never reaches `service_records` by hand, and the
   fleet-spend panel keeps reporting "0 of N records have a cost".
8. **The five media tools** - loud car, failing mic, no buttons.
9. **log_build_entry / get_spend** - an entire store with no screen.
10. **The four location tools** - and `forget_place` makes a misheard label permanent.

Runners-up: `remember` (add), `accept_proposal`, `log_pending_transaction`, `ask_mail` (a decision
conflict, not an oversight).

### What this ticket deliberately does not do

Order the work. That is the stock-take session's call, with this table in front of it.
