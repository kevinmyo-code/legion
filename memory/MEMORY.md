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

## Where we stopped - 2026-10-11 (session 8d51fa39)

- **`dev` = `main` = `ff3a824f`, pushed.** Android 4609 / 0 (JUnit XML, 11:25). Server suite NOT
  run this session.
- **Engine live, checked 2026-10-11 14:00 CDT:** revision `legion-00031-2d5` (deployed 07:22 CDT).
  Scheduler `legion-worker-plaid-sync` runs `30 */3 * * *` UTC; runs at 12:30, 15:30 and 18:30 UTC all
  exited 0. First run: 835 new rows, 783 older file rows replaced; later runs 0 new. 5 older rows
  kept each run (categorised file rows with no bank match, four of them $0 interest lines).
  The ledger-mirror freeze (`b25d8ec8`, `de3bec26`) is phone code; unknown whether the A25 has it.
- **Merged since 10-09:** Plaid as ledger truth (ADR 0057), bank sync every 3 h, ledger mirror
  freezes when a pass refuses any row, Quiet (media at half, DND lets media through),
  `home-calendar`, `suggestion-tap`, `perf/home-calendar` (HOME calendar off the main thread).
- **Kevin, 2026-10-11:** the Plaid production secret is swapped in; the HOME calendar and
  suggestions are installed on the A25. "A bunch of it is already done" - the board's `built`
  tickets (about 100) overstate what is still owed on the phone. Flip them as Kevin confirms.
- **Red on `dev` before this session, not cleared:** detekt has 234 findings over its baseline
  (none in today's files); `decision_debt.py` lists 18 resolved decisions with no build ticket.
  Regenerating either baseline is Kevin's call, not a fix.
- **Trap:** `docs_check.py` and `decision_debt.py` false-fail inside `.claude/worktrees/*`
  (`docs_check.py` skips any path containing `.claude/worktrees`). Run them on an export or the
  main checkout.
- **The main checkout is parked on `fix/spotify-song-match` (2026-10-02)** with uncommitted chart
  edits that are all on `dev` already. Work from `.claude/worktrees/dev-merge`.
- **Unconfirmed as of 10-11:** skip Tue Oct 13 in the weekly COSC 4320 series ("Just this one").
- Cloud credits ($250) expire 2026-11-04. Plaid Trial: never click "request production access".
- Older handoffs: [[handoffs-2026-09-11-to-10-09]].

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
