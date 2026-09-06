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

## Where we stopped - 2026-09-06 early (session 38bf2e3c)

- **The port is live on the laptop.** Django engine runs at `http://192.168.1.117:8000` (`deploy/.env`, gitignored)
  against the Supabase Postgres as `legion_engine`: 11 Django tables in schema `django`, `checklists*` as
  Django's first `public` tables (45 public tables now), superuser + household member kevinmyo@gmail.com.
  Server: 93 tests green. API: /api/events, /api/checklists, /api/changes?since=, /api/schema/. Phone:
  EngineAuth/EngineConfig/EngineTransport (default SUPABASE per aspect), Setup sign-in row, debug-only
  cleartext. Phase 3 RAN 09-06: checklist write reached Postgres in a second, a server write reached the phone by
  poll, an unreachable engine was reported honestly and the queued tick drained. **Three defects: the
  events done-toggle has no push side at all (pre-existing, `NotesController.tickAppointment` is Room
  only), voice claimed "Added oat milk" with no row anywhere (§7 breach, under investigation), and the
  tick backfill aborts on its first refusal. Transport default stays SUPABASE until 1 and 3 are fixed.**
  Next was (`research/execution-plan.md`).
- **Creds Kevin said he will rotate:** legion_reader and legion_engine share the password he pasted; the
  reader's leaked once into an agent traceback.

## Earlier the same session - 2026-09-05

- **Phone at Room v66, all verified on the A25.** Checklists (named lists, measured lines, none/daily/
  weekly schedules, per-day or done-once ticks, history) live on `checklists*` tables - **Room only, no
  server tables yet**, so `bio` and `errands` do not survive a wipe. Recordings moved to METERS;
  transcription works for the first time (a doubled `/files/files/` URL had 404'd every attempt) and
  failures are visible with reason and retry. Month grid marks open todos with a square.
- **Server coursework is truth as of 09-05 03:39Z.** 123 tasks, 80 Canvas-backed with submission
  evidence in `structured_meta`, 22 discussion first-post rows, titles carry course names. MATH dates
  come from the SYLLABUS (WebAssign rolls due dates forward; Canvas placeholders are pointers).
  Refresh = `tmp/canvas_reconcile.py` over a fresh Canvas API read; it is a snapshot, not sync.
- **Google Calendar rows are frozen at 09-01** (importer retired). 18 all-day rows corrected 09-05.
  Decision open: two-clients ticket 06.
- **Django is THE ENGINE (ADR 0044, evening 09-05).** Shape decided 09-05 late: **Supabase Postgres (data
  stays put, Django the only writer) > Django on Google Cloud Run (service + Job + Scheduler, the
  midconerpdash pattern) > Android app and the Django-served PWA.** Media on R2/GCS. Cutover moves no rows. Map `.scratch/django-engine/`, 11 tickets. Every Supabase sync path
  built 09-02..05 is throwaway once Django owns writes. Two-clients map superseded.
- **Android architecture: Hilt on KSP, ViewModel per screen** (CLAUDE.md §8, map `.scratch/architecture/`).
  KSP landed 09-05 (clean compile 3m15s to 2m41s); detekt with a baseline in flight.
- **In flight when this session paused:** one-today ticket 10 slice A (`manage_checklist` voice
  tool), then B (retire grocery trip) and C (retire persistent list). Spotify App Remote: fixed 09-05 by registering this machine's debug SHA-1 in the dashboard.

## Owed by Kevin - 2026-09-05

- **Rotate the Supabase PAT** (`sbp_fce2...`) and the WebAssign login link; both are in the transcript.
- ~~Add the debug SHA-1 to the Spotify dashboard~~ **Done 09-05**, linked. Cause was the second machine's unregistered fingerprint.
- Recordings note 2 is an accidental capture of a real kitchen conversation; delete or keep.
- Phone media volume left at 15/15. "Auntie Greta birthday" is a 1-hour 00:00 UTC event in Google.
- Drive backup guard refuses every upload since the wipe (8k rows vs a 29k baseline); needs a reset.
- The `last_obd_mac` hint is null on all 3 cars; fleet will not survive a second wipe until set.

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
