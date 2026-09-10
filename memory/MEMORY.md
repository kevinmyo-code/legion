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

## Where we stopped - 2026-09-10 late (session 0151LEjT, continued)

- **`dev` is `711e2be`, pushed, 3487 tests / 0 failures.** Earlier today: tenancy migration applied
  to live, Cloud Run redeployed, Android CI green for the first time, phone flipped fully to Django
  (all nine aspects in `DJANGO_BY_DEFAULT`).
- **New map `one-home`, charted and almost entirely built** (Kevin: *"chart it and work on it"*, then
  *"run everything with your taste"* - so tickets 01, 04 and 06 were decided by Opus, not by him, and
  the tickets and `library/decisions.md` both say so). CALENDAR is HOME; METERS and the whole tab row
  are deleted; the ASK picker lives at `LegionRoute.ASK`; the advisor writes a real recurring
  `checklists` row instead of `"Plan: "`-prefixed `list_items`. **Ticket 07 (news surface) is the only
  unbuilt one** and is in flight on `feat/news-surface`.
- **Three bugs found that were not the task**, all now fixed: a migration writing
  `ADD COLUMN ... DEFAULT NULL`, which records a literal `'NULL'` default Room's generated schema does
  not have and **fails the upgrade for every existing install**; three dangling deep-link route
  strings (`notes` and `today` were already a crash on tapping an old notification, before any of
  today's work); and a generated schema JSON never staged.
- **`web-and-households` 03 merged** - signup, invite codes, household members, session auth.
  Merging it conflicted only on the two GENERATED files; the fix is to re-run
  `obsidian_sync.py` + `pending_wiki.py` on the merge result, never to pick a side.
- **No phone.** `adb devices` is empty (checked with the full SDK path, daemon restarted). Everything
  hardware-dependent is parked in one-home ticket 08 rather than claimed.

### Next session, in order

1. **Get the phone attached, then run one-home ticket 08.** The queue is real: cold start on HOME, a
   notification tap (the renamed route), the ASK picker in its new home, a checklist surviving a
   night, and command-center 12's newsletter tap owed since 2026-08-22.
2. Finish/merge `feat/news-surface`. Rescue or abandon `feat/openapi-clients` (still mid-merge, ~33
   conflicts, duplicate ADR 0045 to renumber to 0048).
3. Ledger, pantry and fleet against Django on the A25 - still never run.
4. Re-resolve `web-and-households` 12/13 now the Oracle VM is dead.

### Owed by Kevin - decisions, not code

- **Any of the three taste calls above is cheap to overturn** - each ticket records the reasoning and
  what it rejected. 01 (no tab row) is the one that changes the most if he disagrees.
- **`origin_guid` for a server-created vehicle**: fleet is routed to Django, so `upsertVehicle`/
  `upsertServiceHistory` hit `DjangoFleetBackend`'s two refused functions.
- **Voice-note audio still has no durable store** (ADR 0041 keeps all three artefacts together).
- Rotate the Supabase passwords and the phone's device token when convenient.

### The lesson of this session, in one line

**A decision that names a file to delete should name the PROPERTY that makes deleting it safe.**
Ticket 04 said to delete `GoalChecklistPanel` because the calendar rendered it; ticket 02 removed that
call site hours later, leaving `BodyScreen` - which hosts `+ LOG SET` inside the panel - as the only
caller. Obeying the ticket literally would have deleted `log_workout_set`'s hands path, the exact ADR
0035 failure the same map was enforcing one screen over.

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
