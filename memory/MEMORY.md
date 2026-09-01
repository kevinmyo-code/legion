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

## Where we stopped - 2026-09-01

- **Backend is live and carrying real data.** Every aspect writes to Supabase; fleet finished its
  cutover 08-29 (nine tables, four identity shapes). Room migrated v49 to v55 on the phone against
  26k real rows with no loss.
- **Google Calendar is cut** (09-01). The 261 imported appointments are ordinary rows now, visible
  on screen for the first time, and tickable. No live `CalendarContract` query remains - a
  structural test enforces it.
- **`legion-web` is scaffolded** at `~/projects/legion-web`, two commits, no GitHub remote yet. It
  is the general client; LEGION is the specialized one (ADR 0040).
- **Next:** `one-today` tickets 03-05 - the day in review, deleting the dead ALERTS machinery, and
  the maintenance date axis.

## Owed by Kevin - none of it is code

- **The OBD re-run.** 12,807 of 26,059 samples uploaded 08-29 then halted on a placeholder vehicle;
  the fix landed, so a re-run should carry ~7,989 more.
- **The voice modals have never been tried by voice** - built and installed 08-28.
- **A second household account**, dashboard-only by design (backend-erp 23). The real test is
  proving RLS shows her the same rows; an empty result looks identical to a working sign-in.
- **Two placeholder vehicles** (`default`, an OBD-MAC row) carry year 0, and 5,263 OBD samples plus
  16 maintenance items hang off cars that do not exist. Give them a year or decide to drop them.

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
