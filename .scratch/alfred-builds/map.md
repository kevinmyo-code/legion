---
map: alfred-builds
title: "Alfred builds Alfred: fix, build and ship LEGION from the phone through the Claude worker"
charted: 2026-11-05
charted-by: "Kevin + Opus"
effort: "`.scratch/alfred-builds/`"
tickets: 9
open: 9
status: open
tags: [map]
---

# Alfred builds Alfred

**Kevin, 2026-11-05:** *"i wanna try things like, hey alfred i wanna have snake the game on my phone,
lets build it, it calls claude, builds, pushes and boom we have snake on my games section. same goes
for books etc. hey alfred i wanna read something horror, recommend me, he recommends, i choose, asks
him to find a free epub if available, if not then pdf then i ask him to convert to epub ... it gets
pushed to engine etc. all from the phone away from my laptop"*.

There are three kinds of request, and all of them take the same path:

| Kind | Example | What Claude does on the Alienware | What ships |
|---|---|---|---|
| **Fix** | "Alfred, that was wrong" (voice), or Report on a reply | Reads that conversation's record, finds the cause, fixes it with a test | Wording: an engine deploy. Code: an app update |
| **Feature** | "build me Snake in my games" | A short spec, at most 2 questions back to Kevin, then the build and tests | An app update and/or an engine deploy |
| **Content** | "find me a horror book", then "that one" | Recommends a few, finds a legal free copy (EPUB, else PDF, then converts it) | The book lands in LEGION's library on the engine, and the phone downloads it |

**The loop:**
1. Kevin asks by voice.
2. Alfred hands the task to the Claude worker (ADR 0067).
3. Claude works on a branch in its own work folder and runs the tests.
4. Alfred asks: **"Ready: <one line>. Ship it?"**
5. On one yes, the worker merges to dev, pushes, deploys the engine, and/or builds and publishes the app update.
6. The phone then asks "Install?" (alfred-ota, ADR 0072).

Kevin never needs the laptop.

## Rulings (Kevin, 2026-11-05)

1. **Trigger:** voice ("that was wrong", "build me X", "find me a book") AND a Report button on a reply in the conversation screen. Every voice capability has a hands path (ADR 0035).
2. **Autonomy:** Claude fixes and tests on its own. **One yes per change ships it.** Nothing merges, deploys or publishes without that yes.
3. **Deploys:** after Kevin's yes the worker may run the engine deploy itself (amends "deploys are Kevin's" for this path only). Kevin can still deploy by hand.
4. **Scope:** anything in the repo, behind tests and the yes. Two guard rails stay:
   - The ship question names a change that touches a locked safety clause, a CLAUDE.md ruling, auth or household isolation, the §4 money gate or the recruiter demo ("this also changes X"), so the yes is informed.
   - Red tests block the ship question.

## Built in (not a ruling, the law and the existing rules)

- **Books come only from legal free sources:** public domain (Project Gutenberg, Standard Ebooks, Wikisource, public-domain items on the Internet Archive) and copies the author or publisher gives away. Never a pirate site. If no legal free copy exists, Alfred says so and names where to buy or borrow it (Libby through a library card, a store).
- The ask list still binds every task (CLAUDE.md, ADR 0067): no passwords, no bot or sign-in bypass, no purchases, no sending outside the ship step.
- **One build at a time on the Alienware:** a lock, plus a heat check (the laptop cut out under load on 2026-11-05).

## Depends on

- **alfred-ota** (pack + self-update): needed for "ship" to reach the phone without adb. Its phone half is building on `feat/alfred-ota-phone`.
- **claude-session** (ADR 0067) and the worker skills (legion-chores, skill-miner), already live.

## Tickets

| # | Ticket | Where | Blocked by |
|---|---|---|---|
| 01 | [Report a problem: voice and button](issues/01-report-a-problem.md) | phone + engine | - |
| 02 | [Conversation records the worker can read](issues/02-records-for-the-worker.md) | engine | - |
| 03 | [The ship step](issues/03-ship-step.md) | worker + tools | alfred-ota 04 |
| 04 | [Worker skills: fix-alfred, build-feature, ship](issues/04-worker-skills.md) | worker | 02, 03 |
| 05 | [Undo the last ship](issues/05-undo.md) | worker + tools | 03 |
| 06 | [Book library on the engine, synced to the phone](issues/06-book-library.md) | engine + phone | - |
| 07 | [book-finder skill](issues/07-book-finder.md) | worker | 06 |
| 08 | [One build at a time, with a heat check](issues/08-build-lock-and-heat.md) | worker | - |
| 09 | [Kevin's first run: a fix, Snake, a horror book](issues/09-first-run.md) | Kevin | 01-08 |

Order:
1. 02, 08 and 03 (no Gradle).
2. 04 and 05.
3. 06 and 07.
4. 01 (phone, after the alfred-ota phone build frees Gradle).
5. Kevin's run.

ADR 0074 is written with 03.
