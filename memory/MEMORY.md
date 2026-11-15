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

## Where we stopped - 2026-11-15 ~12:15 (laptop going OFF; a phone/cloud session picks this up)

**Live now (2026-11-15):** `dev` = `main` = `6315ebd3`. Engine deployed at 702aa88b's code (grades,
greeting, budget pace migrations applied). Phone OTA **263191730** (stage 1) published 11:48:
- the **cloud cascade replaces Gemini Live on the phone** (Gemini speech-to-text -> Flash-Lite with
  tools and the context cache -> Gemini TTS). Settings, Voice engine: Cloud (default) or Laptop
  pipeline. `VoiceEngineSettings.LIVE_RETIRED` is the one flag; the Live code is still in the tree.
- the opening line no longer waits for the prompt build; per-conversation cost audit
  (`gemini_usage` / `cascade_usage_summary` log lines, `tools/voice_cost.py`, `tools/voice_prices.json`);
- updates install with the car connected, download on mobile data, and are checked every 3 hours.
- **Never run on a phone before it shipped.** Real Gemini calls passed (`CloudVoiceLiveCheck`).
  Kevin's first conversations are the test. If he says the Cloud voice is bad: forward-build
  `fix/install-in-car` (`1a35aa16`: Live kept, new update rules) with a higher versionCode and
  release it; then revert on dev.
- **Known gaps on Cloud:** the camera look refuses in words (Kevin: "camera later"); a navigation
  turn cue can overlap a reply; finished Claude-task notes are not offered into an open
  conversation; no resume-music nudge. Brief me and unprompted lines always use the Cloud voice.

**Kevin's standing word (2026-11-15):** build all of the queue below and ship it in stages at
Claude's judgment, each stage only when its gates are green. Not covered: module writes or
programming, anything outside this queue.

**In flight, each on its own pushed branch (WIP commits possible; read the branch log first):**
| Branch | What | State when the laptop went off |
|---|---|---|
| `feat/voice-fast` | speculative answers at a likely end of turn, prepare-ahead open, filler on slow tools, earlier first clause, `--tts-nq` flag on the laptop voice | `8e2eea84`, on dev `6315ebd3`, all 5 steps committed; needs a full test run and its report read (the agent had not handed back at shutdown), then a phone audit of stage 1 BEFORE it ships |
| `feat/obd-vin` | read the VIN on OBD connect, match a car on file, ask once when unknown or unreadable, attribute all OBD data to that car | `2913645f`, on dev `6315ebd3`. Phone only: no server, Room or tool-declaration change. Full phone suite 6,966 / 0 failed at `1cc21528`; the tip is a detekt clean-up (662 focused tests green). **detekt is still red** in its new files (`VinResponse`, `DongleCar`, long test lines): fix, re-run, then build and ship. Fake dongle replies only; nothing ran against a car. Known limit: the engine stores drive-correction rules but does not apply them to readings already uploaded |
| `feat/cards-expand-r2` (the old `feat/cards-expand` and `feat/cards-expand-1115b` are stale duplicates) | three shared card kinds (readings, status_list, trend) on engine + web + phone, then domain cards (school, money, weather, laptop, trip, body, food, mail draft, memory, drive) | `dade8e36`, on dev `6315ebd3`. Engine + web change, no migration. Kinds and most domain cards done; server 3,498 passed, web 998 / 998, card tests green. NOT run: detekt, the full phone suite, the existing card screenshots. Gaps with no tool behind them: one receipt, weather forecast, leave-by, training trend; the voice-service and voice-cost cards wait on a publish call in `service/cascade/`. Kevin wants to see the renders before it ships (they are on the laptop, not in the repo) |
| queued, not started | brand-neutral diagnostics: module discovery + all-module code read/clear (UDS), per-make extras picked by the VIN (Ford, Mitsubishi), read-only; then OBD cards on the new kinds | after `feat/obd-vin`; pre-CAN cars stay at generic OBD |

**Stage order:** 2 `feat/obd-vin` -> 3 `feat/voice-fast` (after reading his phone's stage 1 logs) ->
4 `feat/cards-expand` -> 5 diagnostics. A phone stage ships from GitHub with the laptop off:
Actions, **Release phone**, on `dev`, dry run first; Kevin approves the `production` gate each run
(signing key, wake-word models from the private bucket, engine token: all set 2026-11-15; the dry
run passed, a real publish from GitHub has not been exercised yet). An engine stage is `ship.yml`
(never run yet) or the laptop.

**Only the laptop can do these (owed when it is back on):** read the phone's uploaded logs and
price them (`tools/phone_audit_engine.py`, `tools/voice_cost.py`); restart the laptop voice service
for `feat/voice-fast`'s service-side change; the by-hand real-Gemini check (needs the key file).

**Owed docs:** an ADR for retiring Live on the phone (supersedes the 2026-11-10 "Live stays the
default" ruling), a `library/decisions.md` entry, and CLAUDE.md section 3's "Gemini Live over a
WebSocket" line.

**Owed by Kevin:** install 263191730 and use it; the OBD dongle's brand and model (decides whether
the second CAN network is reachable); on the phone after stage 1: tap + a few asks incl. one tool,
wake word, a barge-in, a car Bluetooth drive, Settings shows Cloud selected.
- **Cloud container notes:** Android SDK at `$HOME/android-sdk`; `~/.gradle/init.d/central-mirror.gradle`
  routes Maven Central and Robolectric to Google's mirror (Central rate-limits these containers);
  the environment must allow `dl.google.com` and `api.mapbox.com`. Clone is shallow: `git fetch
  --unshallow` before a commit, or the hook writes a truncated devlog.

## Proactive voice - 2026-11-10

- `feat/proactive-voice` (pushed, not merged): ADR 0082. No daily cap; speaks with the screen off
  unless silenced; wake-up and home briefs (Digest); Safety ALARM urgency rings. proactive-mode 14.
- Owed by Kevin: check Digest (state unknown; if on, both briefs start at install, no mail read);
  yes/no on the wake brief reading newsletters (`WakeBrief.READS_MAIL`, off); ticket 14 phone steps.

## Field navigation - 2026-11-10

- `feat/field-nav` (pushed, not merged): offline Field screen (app drawer "Field"): compass, true north,
  GPS-only position with MGRS/UTM, waypoints as places (local first via `sync_outbox`), sun, moon,
  turnaround; voice rides `get_current_location`'s result. Owed by Kevin: install `fast`, the compass run
  on the phone (steps in the round 5 report).

## Conditions - 2026-11-10

- `feat/conditions` (pushed, not merged): engine `check_conditions` (NWS alerts, USGS + NWS flood stage,
  NHC, DriveTexas, FIRMS), Conditions screen, severe-alert raise on SAFETY. Owed by Kevin: engine deploy,
  FIRMS MAP_KEY and DriveTexas key (`deploy/cloudrun/README.md`, Conditions keys), a place labelled home.

## GitHub status - 2026-11-10

- `feat/github-status` (pushed, not merged): engine `github_status` (builds, open, commits, pages), GET
  only, via ask_engine and the web assistant. Owed by Kevin: fine-grained read-only token as secret
  LEGION_GITHUB_TOKEN plus LEGION_GITHUB_REPOS, then engine deploy (`deploy/cloudrun/README.md`).

## Workouts - 2026-11-06

- `feat/workouts` (pushed, not merged): deterministic 3-day A/B programme on the engine
  (`body_coach/programme.py`, ADR 0078), session tick-off on the Body tab (direction 2, Kevin's
  pick), `get_todays_workout` behind ask_body, engine tools on ask_engine; BIO Plan tickbox list
  archived once; `create_workout_plan` retired. Fat-loss tickets 23-26.
- Owed by Kevin: engine deploy (`body_coach.0003`), install `fast`, ticket 25's run on the phone;
  rule on Mon/Wed/Fri default days. Web Workouts tab is ticket 26.

## Fat-loss loop and Body screen - 2026-11-05

- `feat/fat-loss` (pushed, not merged): map `.scratch/fat-loss/`, Body is the seventh aspect (ADR
  0077); engine `body_coach` app + web `/body`; phone reads, plan uses the estimate, Monday line,
  Body screen in direction A (Kevin's pick). `fast` 263100519 built, not installed.
- Owed by Kevin: engine deploy with the `body_coach` migration; his profile on web `/body` or the
  phone (his numbers never in the repo); a `bodyweight_lbs` goal with his milestone; install `fast`.

## No idle voice session - 2026-11-05

- `fix/no-idle-session`: no Live socket while idle (Kevin's "OK"); prewarm gone everywhere, one
  door, stale-session events dropped. Owed by Kevin: install `fast`, leave idle 5 min (no
  `session_start`), then tap and wake once each; rule on a cold tap greeting vs listening at once.

## Phone verification - 2026-11-05

- `feat/phone-verify`: `python tools/phone` drives the phone with rails;
  `docs/screen-map.md` generated by `tools/screen_map.py`; skill `verify-on-phone`. Map phone-verify.
- Merged to dev/main. Ticket 06 proof ran 20:31 on 263092342 `fast`: all steps passed, vault screenshot
  blank. Owed: a build with `testTagsAsResourceId` on the phone (ticket 04).

## /try modals - 2026-11-05

- `feat/demo-modals` (pushed, not merged): diagrams and the resume open over the page, diagrams
  first in the recruiter prompt, `show_resume`, no second greeting. arch-diagrams ticket 02.
- Owed by Kevin: engine deploy (demo 0008), `load_recruiter_content` of the full folder (now holds
  `resume.png`), one `/try` run on the Android phone.

## Memory v2 and the ID vault, phone half - 2026-11-05

- `feat/memory-vault-phone` (pushed, not merged): tickets 09-13 built, JVM green. One `memory` tool
  (recall/remember/forget/list) replaced `remember` + `recall_memory` to fit the payload ceiling;
  `show_vault_item`; `VaultActivity` (FLAG_SECURE, BiometricPrompt STRONG or PIN); plate on Specs.
- Owed by Kevin: engine deploy (feat/memory-vault-engine is on dev), `install_schedule.py` for
  `consolidate_memories`, then `manage.py import_legacy_memories --member <email> --dry-run`, then
  without `--dry-run`; install this branch's `fast`; ticket 14's run (first vault item typed on the phone).

## Alfred over the internet (alfred-ota) - 2026-11-05

- `feat/alfred-ota-phone` (pushed, not merged): tickets 02, 04 versionCode, 05, 06 built, JVM green;
  engine half on `feat/alfred-ota-engine`. ADR 0072. versionCode is `yyDDDHHmm` (map said
  `yyMMddHHmm`, which overflows): Kevin to confirm. Live payload ceiling moved for `app_update`.
- Owed by Kevin: engine deploy + `setup_bucket.sh` + `LEGION_APK_BUCKET`; back up
  `~/.android/debug.keystore`; one adb install of this branch's `fast`, "Install unknown apps"
  grant, then publish with `tools/release_apk.py` and ticket 07's run. Ticket 03 next.

## Claude session switched on - 2026-11-03

- `integrate/claude-session-on` = dev + `feat/claude-session` + ticket 09 (HTML delivery:
  `deliver: phone`, signed sandboxed page link, `open_page`, web desk `/claude`). Suites green.
- Owed by Kevin: engine deploy (migrate `claude_session` 0001/0002, `install_schedule.py` for the
  prune, frontend rebuild), THEN `tools/claude_session/install.ps1` on the Alienware (needs his
  LEGION sign-in), then ticket 07's run incl. step 9 (Atlanta day trip page).

## Voice cut-offs - 2026-10-27

- Branch `fix/voice-cutoff-and-latency` (pushed): phone follow-up window and head unit idle timer
  no longer close while the mic hears speech; phone `ask_engine`/`aspect_clerk` get the 45 s timeout.
  JVM-tested only, **never on a device**. Threshold (peak 2000) is guessed.
- Owed by Kevin: install both, one drive, read phone `LIVE_FOLLOWUP` (Car probe screen) and head unit
  `voice_turn_decision` (`device_logs --grep idle`); rule on START_SENSITIVITY_LOW, HU model, filler.

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
