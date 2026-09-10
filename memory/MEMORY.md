# MEMORY.md

The handoff. **MEMORY.md wins for state, CLAUDE.md wins for rules, the board wins for tickets.**

## What belongs here, and why this file is different

CLAUDE.md holds rulings, which do not rot. **This file holds state, so it rots by design** - and
fast. Audited 2026-09-01, three days after its last rewrite: **about a quarter of it was already
wrong.** Fleet described as a projection after the cutover reversed it, a map called closed with
eleven tickets opened since, an agent named that had been retired an hour earlier.

The defence is not writing it better. It is **writing down only what nothing else can tell you**:

| Question | Where it is answered |
|---|---|
| What tickets are open, ready, blocked? | `docs/index.html` - generated from ticket frontmatter |
| What was decided, and when? | `library/decisions.md` |
| What has never run on hardware? | `library/standing-caveats-2026-08.md` |
| What are the rules? | `CLAUDE.md` |
| **Where did we stop, and what does Kevin owe?** | **here, and nowhere else** |

**Every line here carries the date it was true.** A dated claim can be weighed; an undated one gets
believed.

## Where we stopped - 2026-09-10 night (session 0151LEjT, continued)

- **`dev` is `544388e`, pushed.** The tenancy migration IS applied to live now (Kevin approved
  2026-09-10); the `column events.household_id does not exist` warning that headed this section for
  two days is gone. Cloud Run redeployed after it, `/api/auth/csrf` verified 200. **Android CI is
  GREEN for the first time** (`ce75737`) - the three `DatabaseSnapshot` tests assert the outcome now,
  through test-only seams, not which branch ran.
- **The phone is fully on Django.** `EngineTransport.DJANGO_BY_DEFAULT` holds all nine aspects as of
  `f96821e`, not two. Not a waiver of "never ahead of a hardware run": `household_id NOT NULL` has no
  default and the Supabase backends know nothing of households, so Supabase-by-default was a write
  that fails, not a working path. 3485 tests, 0 failures. **Ledger, pantry and fleet have still never
  run against Django on the A25** - compiled and unit-tested only. That is the next hardware session.
- **Oracle is DEAD** (Kevin, 2026-09-10): all three availability domains out of A1 capacity. Staying
  on Cloud Run + Supabase Postgres for two households. `deploy/vm/PROVISION.md` survives for its two
  traps only. Tickets 12/13 need re-resolving against that.
- **Ticket 03** (signup, households, invite codes) was building in `.claude/worktrees/accounts` when
  this session ended. **`feat/openapi-clients` is mid-merge and NOT clean**: its agent died to a rate
  limit leaving ~33 conflict blocks, and it still carries a duplicate ADR 0045 that must renumber to
  0048 (the head-unit one).

### Next session, in order

1. **Hardware run on the A25**: ledger, pantry, fleet against Django. Everything else is unit tests.
2. Finish ticket 03, then 05 (screens), 11 (reports). Rescue or abandon `feat/openapi-clients`.
3. Re-resolve tickets 12/13 now that the VM is dead.

### Owed by Kevin - decisions, not code

- **Android UI restructure, raised 2026-09-10 and unanswered**: retire the Meters page, rename
  Calendar to HOME, retire today's plan in favour of advisor-populated workout todos, add a news
  feed. Most maps to existing tickets (command-center 01/08/12 built, aspect-advisors 04/09 resolved,
  one-today 08/09 open). **Gmail as a feed source is constrained by CLAUDE.md §7 third-party
  read-through; RSS is not.** A ticket was offered and not yet asked for.
- **`origin_guid` for a server-created vehicle.** Load-bearing now that fleet is routed to Django:
  `upsertVehicle`/`upsertServiceHistory` hit `DjangoFleetBackend`'s two refused functions.
- **Voice-note audio still has no durable store** (ADR 0041 keeps all three artefacts together).
- Rotate the Supabase passwords and the phone's device token when convenient.

### The lesson of this session, in one line

**Two tests I "fixed" were describing two different devices.** `EngineBackendsTest` built a signed-OUT
`EngineTransport` while its backends came from a signed-IN config; the assertions only agreed while
both answered SUPABASE, and the default flip pulled them apart. A test whose halves disagree about
the fixture passes for the wrong reason until something moves.

## Read before trusting a green suite

`library/standing-caveats-2026-08.md` - no alarm has ever fired, `sync/` has never executed, no
Compose preview has ever rendered, plus the ADB and device traps that have each cost real time.

The pattern that held all week: **seven defects in one session, every one found by running it, none
by a passing suite.**

## How to update this file

- **The three state sections above stay under 30 lines between them.** The cap is on what rots, not
  on the file - the framing is stable and pays for itself. This reached 960 lines once by appending
  a session section per day, then 95 by appending to the appendings. Append to the library and
  leave a pointer.
- **Do not restate ticket state.** It is generated, it is correct, and a copy here is a copy that
  goes wrong. Name the map, not its contents.
- **Date every claim.** Undated state is what gets believed after it stops being true.
- A decision changing a CLAUDE.md rule is filed to `library/decisions.md` AND applied to CLAUDE.md
  in the same commit; a lesson graduates the same way.
- `memory/library/` catalog: `INDEX.md`. Dispatch `scout` rather than bulk-reading shelves, and
  **verify what it returns.** Most shelves are FROZEN Midnight AI history; read the banner first.
