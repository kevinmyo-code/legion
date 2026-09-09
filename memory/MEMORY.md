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

## Where we stopped - 2026-09-08 night (session 0151LEjT)

**`dev` expects a column the live database does not have. Read this before running anything.**

- **`dev` is `cad1706`.** ADR 0045 (households are tenants) is merged: `household_id` on 43 tables,
  one choke point, 651 passed / 0 failures on CI. **The migration is NOT applied to live**, so a row
  fetch against the live database fails with `column events.household_id does not exist`. `/health`
  still answers `{"db":"ok"}`, which makes it look fine until you fetch a row. Either apply it or
  work on a commit before `cad1706`.
- **Apply it with:** `LEGION_BOOTSTRAP_HOUSEHOLD_ID=<a uuid you keep forever>` then
  `manage.py migrate`. Print the SQL first with `manage.py tenancy_sql`. It refuses to run without
  that variable rather than mint one. **Kevin had not approved applying it when the session ended.**
- **Backup, verified, taken 2026-09-08 21:38:**
  `C:\Users\kevin\legion-backups\legion-pre-tenancy-20260908-213847.dump` (custom format, `public`
  + `django`, 56 tables, `pg_restore --list` clean). **The first real backup since the xlsx mirror
  retired** - Drive backup has refused every upload for over a week.
- **New map: `web-and-households`** (14 tickets). Landed today: the React+Vite+TS scaffold served by
  Django (`server/frontend/`), CI for server and Android, an arm64 image with migrate-on-start,
  Caddy, an SSH deploy script. **Hosting reopened: Oracle A1 VM on Pay As You Go, Postgres in
  compose, Cloud Run and Supabase retire** (ticket 12; the move is ticket 13).
- **Android CI is RED and the cause is known.** Three `DatabaseSnapshot` tests assert that
  Robolectric's SQLite *cannot* do `VACUUM INTO`. On Linux it can, so the export takes the fast path
  and the two "the fallback ran" assertions fall with it. Screenshots are fine. The fix is a
  decision: assert the OUTCOME (a readable database file) rather than which branch ran.

### Next session, in order

1. **Decide on the live migration** (the audit was cut short mid-review; it had found the scoping
   solid). Then apply it, or `dev` stays broken against live data.
2. **Fix the three Android tests**, or Android CI stays red for everyone.
3. **Ticket 03** (accounts: signup, invite codes, session login) - the next thing blocking a web app
   Kevin's parents can sign into. Then 05 (screens), 11 (reports).
4. Ticket 13's VM move; django-engine 06 (nightly backups) now GATES that cutover.

### Owed by Kevin - decisions, not code

- **Apply the tenancy migration to live?** 43 tables, 44 unique-index re-keys, backup exists.
- **Oracle:** upgrade the tenancy to Pay As You Go and create the A1 VM (his card, his tenancy).
  PAYG exemption from idle reclamation is REPORTED, not documented - worth one support ticket.
- **`origin_guid` for a server-created vehicle.** Now load-bearing: `FleetEngineStore` is routed, so
  on Django `upsertVehicle`/`upsertServiceHistory` hit `DjangoFleetBackend`'s two refused functions.
- **Fleet provenance differs by transport** - but checked 2026-09-08: the server's per-table default
  equals every existing row's value on all 8 fleet tables, and the phone sends constants. Mechanism
  only, no divergence today.
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
