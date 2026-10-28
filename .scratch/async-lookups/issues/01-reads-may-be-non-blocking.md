---
map: async-lookups
ticket: "01"
title: "Decision: read-only lookups may run non-blocking; writes stay blocking"
type: decision
status: resolved
status-detail: "Kevin 2026-10-28: non-blocking lookups plus a 3 s target, build it. ADR 0065 amends the 2026-09-27 all-blocking ruling"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Decision: read-only lookups may run non-blocking

**Ruling (Kevin, 2026-10-28).** Offered "non-blocking lookups plus the 3 s target" as the pick from
the MoshiRAG comparison, with the note that it reopens the 2026-09-27 all-blocking ruling; Kevin:
*"make an implementation plan and spin up opus agents to do it."*

## What it amends

Commit `29057645` (2026-09-27) stamped `behavior: BLOCKING` on every declaration because
`gemini-3.8-live` made NON_BLOCKING the default and the model could say "done" before a result
existed, which makes CLAUDE.md section 7's outcome-verb rule (ADR 0031, `CANNOT_CLAUSE`)
unobeyable.

That reasoning is about **outcomes**. A read-only lookup has no outcome to misreport: nothing is
sent, booked, played or cleared. Its failure mode is different, a **fact** spoken before the data
exists, and that is closed the same way the outcome rule is: the model may say a holding line and
nothing factual until the result arrives, and the result itself carries the figures.

## The rule

1. **Writes stay BLOCKING.** `LiveToolbox.isMutatingTool(name)` true means BLOCKING, no exceptions,
   and a test asserts the non-blocking set is disjoint from it.
   1a. **What a write is (Kevin, 2026-10-28, asked during the ticket 02 build):** a user-visible
   record change the assistant could claim as an outcome. Internal bookkeeping does not count:
   `lookup_vin`'s spec-cache refresh and `ask_advisor`'s advice-log row are bookkeeping, so
   `ask_fleet` and `ask_goals` stay in the set.
2. **Read-only, slow lookups may be NON_BLOCKING**, opted in by name, on the Live socket only. The
   first set: `ask_fleet`, `ask_body`, `ask_goals`, `ask_pantry`, `ask_advisor`, `diagnose_codes`,
   `triage_symptom`, `ask_maintenance`, `check_cold_start`. Each is read-only today; the builder
   verifies that before adding it.
3. **Not in v1:** `ask_engine` and `aspect_clerk` (their tools can write), `ask_mail` (the
   read-through-only rule in section 7 keys off the turn a mail tool ran in;
   non-blocking blurs that boundary, and nobody has traced it yet).
4. **The result is delivered `WHEN_IDLE`**, never `INTERRUPT`: a holding line in progress finishes,
   then the answer. `SILENT` is never used, because a lookup the user asked for must be spoken.
5. **The prompt says what the holding line may contain:** one short in-character line, no figure,
   no fact, no outcome, then wait. A presence test pins the clause like `AriaBrainHonestyClauseTest`.
6. **It is a setting, default ON, with the off switch beside "Smart end of turn".** Off means every
   declaration is BLOCKING, as before.
7. **The typed path is untouched.** `behavior` is stamped in `GeminiLiveSession.buildSetup` only;
   REST declarations never carry it.
8. **Head unit out of scope**: `gemini-3.1-flash-live-preview` has no asynchronous calls.

## Where it is recorded

- `docs/adr/0065-read-only-lookups-may-be-non-blocking.md`, referencing ADR 0031 and this ticket.
- `memory/library/decisions.md`, dated 2026-10-28.
- The `withBlockingBehavior` doc comment and the README "Tools" bullet carry the same sentence.
