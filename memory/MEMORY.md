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

## Where we stopped - 2026-09-08 late (session 0151LEjT)

**All nine aspects are now wired to Django. Nothing new has run on hardware.**

- **Engine**: `https://legion-757959564788.us-south1.run.app` (project `legion-engine-260906`).
  Redeploy `python deploy/cloudrun/deploy.py --project legion-engine-260906`. **Health is `/health`,
  NOT `/healthz`** - Cloud Run reserves that path and answers it itself.
- **Phone: 3460 tests, 0 failures (JUnit XML, 2026-09-08 late).** `c2bff0f`/`6c0440f`/`4bd90ec`
  routed the last 19 call sites (ledger 8, pantry 4, fleet 7) through `EngineBackends`. **Unpushed.**
- **On DJANGO and hardware-verified: events, checklists, body, memory. Nothing else.** Places, voice
  notes, ledger, pantry and fleet are wired but never run, and still default to SUPABASE -
  `DJANGO_BY_DEFAULT` is unchanged. Wiring flips no default; that belongs with each hardware run.
- Why two fleet reconciles route on Django while four decline: `library/decisions.md` 2026-09-08.

### Next session, in order

1. **Get a phone attached** - `adb devices` was empty all session, so nothing below could start.
2. **Verify places and voice notes**, then flip them. The last two aspects done this way produced
   six bugs a green suite had missed.
3. **Then ledger, pantry, fleet.** `obd_samples` is 20,796 rows, the first sync moving real volume.
4. **Ticket 10**: revoke the anon key, disable Realtime and Auth, drop `supabase-kt`.

### Owed by Kevin - decisions, not code

- **`origin_guid` for a server-created vehicle. Now load-bearing**: `FleetEngineStore` is routed, so
  on Django `upsertVehicle`/`upsertServiceHistory` hit `DjangoFleetBackend`'s two refused functions
  ("THE ONE GAP"). Callers swallow it as a best-effort third write - it fails quietly, but it fails.
- **Fleet provenance differs by transport** (engine: per-table default; Supabase: caller's value).
  §4 makes provenance load-bearing. Settle before the fleet flip.
- **A place deleted on the phone that the engine still holds active**: push the delete (destructive)
  or accept the divergence? Sits in `skippedLocalNewer` forever today.
- **Drive backup has refused every upload since the wipe** (12,435 rows vs a 29,890 baseline). Guard
  right, baseline stale. **No Drive backup from the past week.**
- Rotate the Supabase passwords and the phone's device token when convenient.

### The lesson of this session, in one line

**Roughly a dozen defects found by running it on hardware or querying live data; a green suite caught
none of them.** Three of the four aspects verified had missing PULL paths - writes went up fine and
nothing came down. And `lessons.md` gained the sharpest one: a test had pinned "a blank remember is a
no-op, not a failure" as correct, which is why 3438 passing tests never saw thirteen tool results
claiming a success they had not earned.

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
