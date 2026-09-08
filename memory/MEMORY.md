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

## Where we stopped - 2026-09-08 early (session 38bf2e3c, ~2 days)

**The port is most of the way done. Django on Cloud Run is real, serving real data, on the phone.**

- **Engine live**: `https://legion-757959564788.us-south1.run.app` (project `legion-engine-260906`,
  us-south1, min-instances 0, max 4, secrets in Secret Manager). Redeploy:
  `python deploy/cloudrun/deploy.py --project legion-engine-260906`. **Health is `/health`, NOT
  `/healthz`** - Cloud Run's frontend reserves that path and answers it itself.
- **Shape**: Supabase Postgres (data, unmoved) -> Django (every rule, only writer) -> Android + a
  future head-unit app + a Django-served PWA. ADR 0044. Cutover moves NO rows.
- **All nine aspects have server routes.** 589 server tests, 85 paths, `server/openapi.yaml` on disk
  with a staleness test (`cd server && uv run manage.py write_openapi`).
- **Phone**: 3460 tests. Nine aspects can speak Django; six are wired.
  - **Hardware-verified and on DJANGO: events, checklists, body, memory.**
  - **Ready to verify: places, voice notes** - pulls and backfills landed but have never run on the
    phone. Both currently on SUPABASE.
  - **Not wired at all: ledger, pantry, fleet.** Backends exist; 19 call sites still hardcode
    Supabase, so flipping them changes nothing.

### Next session, in order

1. **Hardware-verify places and voice notes**, then flip their defaults. The last two aspects
   verified this way produced six bugs a green suite had missed.
2. **Wire ledger, pantry and fleet** (19 call sites), then verify. `obd_samples` is 20,796 rows -
   the first sync here that moves real volume.
3. **Ticket 10**: revoke the anon key, disable Realtime and Auth, drop `supabase-kt`.

### Owed by Kevin - decisions, not code

- **A place deleted on the phone that the engine still holds active**: push the delete (destructive)
  or accept the divergence? It currently sits in `skippedLocalNewer` forever.
- **`origin_guid` for a server-created vehicle**: the contract keys `/api/fleet/vehicles/` on it with
  no collection POST, and the phone's upload carries only `serverId`. No route addresses a live
  upsert.
- **Fleet provenance differs by transport** - the engine sets it from a per-table default, Supabase
  takes the caller's value. §4 makes provenance load-bearing.
- **Drive backup has refused every upload since the wipe** (12,435 rows vs a 29,890 baseline). The
  guard is right, its baseline is stale. **There is no Drive backup from the past week.**
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
