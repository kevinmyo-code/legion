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

## Where we stopped - 2026-10-24 night (head unit logs + spend)

- **Branch `fix/headunit-logs-and-spend`** (worktree `../legion-hu`, off `dev` `4c198992`), 6 commits,
  NOT merged, NOT pushed unless noted. Head unit 745 / 0, phone 5059 / 0 (JUnit XML). **Server suite
  NOT run** (no local Postgres URL in hand).
  - Engine MCP `monthly_spend` (shares `spend_for` with `GET /api/ledger/spend`).
  - `device_logs.reason` gains `periodic` + migration `devicelogs/0002`.
  - Head unit: voice SpendGate removed (`unlock_spend`/`set_spend_passphrase` gone), uploads every
    3 min, conversation audit `diag/ConvoAudit` (drive log only, never Crashlytics), git sha in
    versionName.
  - Phone: `get_monthly_spend` asks the engine first, phone copy only as a labelled fallback.
- **Why:** all 47 head-unit log batches were `startup`; `drive_end` never fired (ignition cut at the
  stop). The "spend log is locked" refusal was the head unit's SpendGate; the engine had no spend tool.
- **Owed, in order:** (1) server `uv run pytest`; (2) Kevin's OK to deploy engine (migration on live
  DB + Cloud Run) BEFORE installing the head unit APK; (3) install, drive, check `device_logs
  --reason periodic` and `--grep audit`; (4) Kevin: drop SpendGate from the Logbook screen too?
- **Voice latency audit:** `.scratch/voice-latency/audit-2026-10-24.md`. Nothing built; waiting on
  Kevin's picks and on a measured drive.

## Where we stopped - 2026-10-11 (session 8d51fa39)

- **2026-10-14 refs:** `origin/dev` = `768e750d`, `origin/main` = `c4b96481` (dev NOT merged to main).
  Android 4650 / 0 at `768e750d` (JUnit XML, 482 files, 10-11 23:22). Server suite not run 10-11.
- **Engine live, checked 2026-10-11 14:00 CDT:** revision `legion-00031-2d5` (deployed 07:22 CDT).
  Scheduler `legion-worker-plaid-sync` runs `30 */3 * * *` UTC; runs at 12:30, 15:30 and 18:30 UTC all
  exited 0. First run: 835 new rows, 783 older file rows replaced; later runs 0 new. 5 older rows
  kept each run (categorised file rows with no bank match, four of them $0 interest lines).
- **A25 on `fast` (85879b1e, same code as below), AOT-compiled, 2026-10-11 19:05:** launch 2.76 s to
  ~0.6 s. Not debuggable: no `run-as`/`DEBUG_SAY` until `debug` goes over it (`device.md`).
- **Before that, `bb8e8dd9`, installed 2026-10-11 14:10, hash-verified, HOME + ASSISTANT roles kept.** It had
  been on the 10-09 20:45 `perf/home-calendar` build, which predates the phone half of Plaid, so no
  bank rows showed. After install: 906 `BANK_API` rows in Room (2025-04-14 to 2026-10-10), Money
  renders both accounts with "Bank data through Oct 11" (screenshot-verified). The pull happened on
  app launch with no button pressed; which code path runs it was not traced.
- **adb is not on the Bash PATH here:** use `$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe`.
  A bare `adb devices` printing nothing is "command not found", not "no phone".
- **Merged since 10-09:** Plaid as ledger truth (ADR 0057), bank sync every 3 h, ledger mirror
  freezes when a pass refuses any row, Quiet (media at half, DND lets media through),
  `home-calendar`, `suggestion-tap`, `perf/home-calendar`, `fast` build, `retro-games` (ADR 0058).
- **Kevin, 2026-10-11:** the Plaid production secret is swapped in; the HOME calendar and
  suggestions are installed on the A25. "A bunch of it is already done" - the board's `built`
  tickets (about 100) overstate what is still owed on the phone. Flip them as Kevin confirms.
- **Cleared 2026-10-11 (git log):** detekt findings fixed without touching the baseline
  (`4c6dfe6a`); the 18 orphaned decisions carried or closed (`5157bf77`). `decision_debt.py` passes.
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
