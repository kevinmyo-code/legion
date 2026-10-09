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

## Where we stopped - 2026-10-09 (session e4a5a3d6)

- **`dev` is `fa966bc9`, pushed; engine deployed at that point (revision `legion-00029-bbs` + worker job).**
  Android 4472 / 0 failures, server 1652 with 0 failures after the const-val parser fix. Live on dev:
  merchant aliases ("JOHN NAUS" shows as Walmart, rule written on live), voice-tool fixes from the
  54-session voice-log audit (all-day dates, new-code baseline, tick wording, data-access clause),
  nav follow camera, places by address + rename + confirm, event suggestions (118 rows loaded,
  Oct 9 - Nov 1). **Phone is on `b42ad60a`**, so suggestions are not on the A25 yet.
- **Three branches, pushed, NOT merged:**
  - `feat/home-calendar`: HOME is prototype A plus a More sheet. Done, 4515/0, ready to merge.
    Ticket `one-home/11`, on-device checks owed.
  - `feat/suggestion-tap`: tap opens the event page, plus per-member pins (new server table).
    Built, but the builder was stopped before its final review and test pass. Owes the suite.
  - `feat/plaid-ledger`: Plaid as the BofA ledger truth (ADR 0057). **One WIP commit, untested.**
    Stopped mid-build at the CLAUDE.md §4 amendment.
- **Plaid:** Trial applied 2026-10-09, under review 2-3 business days. **Never click "request
  production access"; it ends Trial.** Sandbox `PLAID_CLIENT_ID`/`PLAID_SECRET` are in GCP Secret
  Manager, readable by the runtime SA. Swap in the production secret when Trial is approved.
- **Owed by Kevin:**
  - Skip Tue Oct 13 in the weekly COSC 4320 series via "Just this one". The one-off copy is deleted.
  - Phone checks once suggestions and the home screen are installed.
- **Cloud credits ($250, expire 2026-11-04):** cloud sessions bypass the weekly limit. Use them for
  web, server and doc work; phone and deploy work stays local.
- The voice-log audit report was never committed: the repo is public and it quotes private conversations. Its findings are fixed above.

## Web revamp - 2026-10-03 (session legion-26)

- **`.scratch/web-revamp/` is built end to end and on `dev` (`db73b6e`)**: soft Material light web
  (ADR 0053), family view below 1024 px for Mia's iPhone PWA, workbench above it for Kevin, private
  rows (ADR 0052, CLAUDE.md section 1 amended), spend computed on the engine, repeat skips, push.
  491 vitest / 0; server CI green (1391 passed) for the first time since 2026-09-29.
- **Deployed to live the same evening** (revision `legion-00023-ggf`), school rows made private,
  Canvas re-logged in and syncing. Nobody has used the web on a real device yet, and Mia is not
  invited. What is done and what is owed is in the map's Status section; read it there.
- **ADR numbers 0052/0053 are taken.** A session in the main checkout was writing
  `0050-mapbox-native-navigation.md` on a stale tree; it needs 0054.

## Overnight merges - 2026-09-28 ~01:30 (merge session, while Kevin slept)

- **`dev` = `main` = this commit.** Merged, each only after a green run on the exact tree merged:
  backend-etl 06 + Canvas fixes (`34ae75c`, server 998/0, already live on Cloud Run);
  home-redesign (`cc7949c`, Android 3718/0); web "Canvas says submitted" (`02141e1`, vitest 69/69);
  backend-etl 10 BofA parsers on the server (`aa94b1d`, server 1065/0).
- **Owed by Kevin:** redeploy Cloud Run for the web view and ticket 10 (both NOT deployed).
  Home redesign is installed on the A25 (hash-verified, HOME + ASSISTANT re-granted) but **never
  looked at** - the phone locked. Walk `.scratch/home-launcher/issues/05-ship-pass.md` plus ticket
  06's dock steps. Known, left for Kevin: calendar day view can't untick an item ticked on an earlier day.
- A stray `test_postgres` may be left from a server-suite teardown warning.

## Where we stopped - 2026-09-27 night (session 0151LEjT, "legion-0e")

- **`dev` = `main`, pushed (Quiet fix `6f708d8`, plus backend-etl).** Android 3599 tests / 0 failures. PC shut down for a restart.
- **Landed today, on the A25:** home-app (ADR 0050), one-shot side-key assistant, Quiet toggle
  (header QUIET + tile) with volume restore verified on the phone, drawer cache, GPS network/passive,
  ordered checklist push queue, opener deadlines, news both sources, Gemini 3.8 Live.
- **Owed on the phone:** one-shot hang-up after an answer (needs speech); a real call vibrating
  through Quiet; `get_last_ticked` asked by voice; Quiet restore from a stream already at 0 (unit-tested only).
- **Phone sounds:** notification = Power Rangers communicator (`/sdcard/Notifications`, picked by
  Kevin in Settings; `settings put` alone gets reverted by Samsung). Nokia ringtone tried, rejected, deleted.
- **Other sessions at shutdown:** backend-etl 03/04 were merged into dev by their own session
  (4ee90d4, 502ffba). `feat/home-redesign` is pushed at `0fcb198`, worktree
  `.claude/worktrees/home-redesign`: the tip is an **UNVERIFIED WIP commit of audit fixes - do not
  merge until its suite is green**. Tickets 01-04 were green before it (3677 / 0). Resume steps:
  `.scratch/home-launcher/map.md` under "RESUME HERE". `feat/home-lists` is fully inside it.
- `feat/backend-etl` is pushed at `e2e7a9f` (worktree `.claude/worktrees/backend-etl`). Its `004ac07`
  is an **untested WIP in server/ingest - do not merge or deploy**. Resume: `.scratch/backend-etl/map.md`
  "Where we stopped". backend-etl ticket 12 (Canvas submitted, on the phone) is Android work for app/'s session.
- **Open:** hardening 13 (Room invalidation leak, flaky screenshot test), place-arrivals 01
  (Google refuses geofences). Stale worktree folders (launcher, oneshot, assistlock) can be removed now
  that no Gradle daemon holds them; `quiet` is done too.

## Where we stopped - 2026-09-11 night (session 0151LEjT)

- **`dev` is `f52b355`, pushed, tree clean.** Android 3508 tests / 0 failures; server 717 passed /
  42 skipped. Cloud Run redeployed and verified serving the new bundle.
- **The web app went from a login page to something usable.** Today (events + what is due, tasks
  tickable) and Lists (checklists, tick/untick) are live at
  `https://legion-757959564788.us-south1.run.app`. **Nobody has loaded them against real household
  data** - built and tested against a mock over real HTTP, and the narrow/mobile layout is inferred
  from Tailwind classes, never seen. Kevin is the first real user; treat his first login as the test.
- **Same account, phone and web, was already true** - `DeviceToken.user` is a FK to `User` and
  session login authenticates the same row. Kevin's account has 13 device tokens. He was setting his
  own web password via `manage.py changepassword kevinmyo@gmail.com` (settings.py loads `deploy/.env`,
  so that works against live with no extra setup).
- **`read_calendar` could not see a single assignment** and is fixed. It queried `EventKind.EVENT`;
  every assignment is `EventKind.TASK` - 145 of 314 rows on the A25 - and **no tool in the toolbox
  returned one**, which was also an ADR 0035 gap. It now reads both kinds and reports `kind`/`done`.
  Its description still claimed to read "GOOGLE CALENDAR", false since Google was cut.
- **one-home shipped and was hardware-verified** (ticket 08 resolved on the A25): HOME with no tab
  row, all drill-downs, all seven route strings, the ASK picker, and the newsletter tap owed since
  2026-08-22. Skipped with reasons: airplane-mode, the advisor round-trip, the overnight reset.
- **Board correction:** `web-and-households` 02 read `open` while having been applied to live since
  2026-09-10. `02b` (Postgres RLS) is genuinely still open - checked, no `ROW LEVEL SECURITY` in
  `server/`.

### Next session, in order

1. **Kevin logs into the web app and says what is broken.** Two specific unknowns: do Sunday's seven
   assignments land on Sunday (the timezone fix is ported but unverified against his data), and does
   the bottom nav behave at phone width.
2. **`django-engine` 17** - conversation-audit uploads dead since the tenancy migration re-keyed the
   index its `ON CONFLICT` names. WIP preserved on `fix/audit-to-django` (`d98866f`), **unverified -
   the full server suite was never run against it.**
3. **`web-and-households` 05/06** - the rest of phase 1, then the aspect screens.
4. **Ledger, pantry and fleet on the A25** - still never verified against Django; two attempts today
   died to a flat battery and a token stop.
5. **`feat/openapi-clients`** - 6 commits ahead, merge resolved, ADR renumbered to 0048,
   `compileDebugKotlin` passes. Owes a test run before merging.

### Owed by Kevin - decisions, not code

- **`one-home` ticket 09**: do feed subscriptions sync, or are they device-local config? Built
  local-only; ticket 06's resolution said "synced" and is amended to match the code.
- **Canvas has no sync at all.** The only data is a snapshot from 2026-09-01, ten days stale, whose
  own note says it was truncated at 50KB of an unknown larger total. Not ticketed yet, on purpose.
- **`origin_guid` for a server-created vehicle** - `DjangoFleetBackend`'s two refused functions.
- **Voice-note audio still has no durable store** (ADR 0041).

### The lesson of this session, in one line

**One query against the real database settled in seconds what two source-reading passes got wrong in
opposite directions.** Two stale comments said "nothing writes TASK yet"; 145 rows existed. A stale
comment is not ignored, it is believed - and the phone was attached the whole time.

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
