---
map: one-today
charted: 2026-08-30
---

# One today, one calendar, across every aspect

**Kevin, 2026-08-30:** *"i wanna know what i need to do day to day... 1 calendar, 1 today, what i
need to do, what ive done, what ive yet to do etc. across aspects of life"* and *"everything runs
from the supabase backend"* and *"i queried the voice ai, and it didnt know if a calendar item was
done or not... i wanna be able to cross off things ive done and look back at my day and see how much
ive crossed off, and what i need to cross off tomorrow."*

## What the audit found, and it reframes the problem

**There is not a missing "today". There are TWO, from two different stores, one tap apart.**

- HOME renders an agenda from `events` + **four independent live `CalendarContract` queries**.
- DASHBOARD (the widget pager) renders a genuine cross-aspect agenda from the engine's `dueAt`
  mirror - 272 records.

That is almost certainly why the pager did not stick when it was HOME on 2026-08-25. It was not
wrong-looking; it was DIFFERENTLY right, and two answers to "what is next" is worse than one
mediocre answer.

**Three data paths for one calendar:**

| Path | Read by |
|---|---|
| Live `CalendarContract` | HOME hero, LOG today pane, LOG month grid, InboxScreen, Sitrep - four overlapping fetches |
| `events` where `kind='reminder'` | the same screens, merged in |
| `events` where `kind='appointment'` - **261 imported Google rows** | **nothing on any screen.** Only `DatesAgenda` -> alarms and the spoken opener |

`CalendarImportController` imports Google on every process start and every screen ignores the result.

**The aggregator already exists and feeds no screen.** `advisor/digest/HomeDigestBuilder` computes one
headline per aspect across BIO/CRED/FLEET/LOG and only ever builds a Gemini prompt, while
`ui/TodayGapResolvers` RESTATES three of those same computations for the tiles, with doc comments
admitting it.

## The constraint that decides the architecture

**The PC surface cannot query `CalendarContract`.** ADR 0040 makes the web app the general client and
it can only see the server. So the server-backed `events` table is the only path that can serve both
clients; the live-Android path is structurally phone-only.

That turns "which path should screens read" from taste into arithmetic. **Screens read `events`.**
Kevin's own framing agrees: everything runs from the Supabase backend.

## The tickets

| # | Type | What |
|---|---|---|
| 01 | **build** | **Cut Google entirely.** Ruled 2026-08-30; the binding order is verified satisfied |
| 02 | decision | Ticking an appointment - the `kind` filter conflates alarm ownership with tickability |
| 03 | build | The day in review: what I crossed off, what is left, what is tomorrow |
| 04 | task | Delete the residue the audit found - dead ALERTS machinery and friends |
| 05 | decision | `maintenance_items` has no anchored date axis, so "0 DUE" may mean nothing |

**Order matters.** 01 is load-bearing and everything else is cheap after it. 02 is independent and
can go first if Kevin wants ticking sooner.

## RULED 2026-08-30, and it made 01 simpler rather than harder

Kevin: *"cut everything that was from google. everything CRUD to supabase."* That executes
backend-erp ruling 5, which had been standing since 2026-08-25 and was waiting only on its binding
order - widen the importer, verify, then cut.

**Verified satisfied**: the widened importer ran on 2026-08-27 and the 261 imported rows carry
`allDay` (261), `notes` (222), `location` (104) and the `LEGION::v1` `structuredMeta` blocks (5) -
the class metadata that was the entire reason for waiting. The rows are complete and already on
Supabase.

So 01 stopped being "decide which of three calendar paths wins" and became "delete the two that lose".
And the import-on-change lag question **dissolved** - it only existed in the demoted design that
ruling 5 supersedes. There is nothing to import from. 03 needs both. 04 and 05 are free-standing.
