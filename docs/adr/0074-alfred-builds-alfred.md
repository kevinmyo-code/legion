---
status: accepted
decided: 2026-11-05
decided-by: Kevin
source: "[[decisions#2026-11-05 - Alfred builds Alfred: fix, build and ship LEGION from the phone (Kevin)]]"
tags: [adr]
---

# 74. Alfred builds Alfred: one yes ships a change

## Standing

**The Claude worker on the Alienware may fix, build and ship LEGION itself, and ships nothing
without Kevin's one yes to "Ready: <one line>. Ship it?"** A fix, a feature and a book take the
same path: Claude works on a branch in its own work folder, tests, asks; on the yes the worker
merges to dev and main, pushes, deploys the engine and/or publishes the phone update, or adds
the book to the household library. Red tests never reach the question. Pushing, deploying and
publishing happen only inside `tools/claude_session/ship.py` and `undo.py`, which check the yes
token; the ask-list hook denies them everywhere else, token or not. This amends ADR 0067 (which
left deploys to Kevin) for this path only; Kevin can still deploy by hand.

## Context

Kevin wanted "build me Snake", "that was wrong, fix it" and "find me a horror book" to work from
the phone, away from the laptop (map `.scratch/alfred-builds/`, 2026-11-05). ADR 0067 already
gives Alfred a Claude session on the Alienware with an ask list and confirmation tokens; what was
missing was a way to read the conversation that went wrong, one controlled door out of the
laptop, a way back, and a guard against the laptop overheating under two builds.

## Decision

- **The ship step** (ticket 03). `ship.py ask` rebases on origin/dev (a conflict stops it), runs
  the targeted tests (red stops it), and prints the question, adding "This also changes <X>"
  when the diff touches a locked safety clause, a CLAUDE.md ruling or ADR, auth or household
  isolation, the section 4 money gate, the recruiter demo or the worker's ask list (ruling 4).
  `ship.py run --token` ships exactly the branch head Kevin was asked about, in order: rebase,
  targeted tests plus the suites for what changed, merge and push dev then main, the engine
  deploy with the new revision's `/health` checked, `assembleFast` then release_apk.py
  (alfred-ota, ADR 0072's publisher; called, never reimplemented). One token, one ship, recorded
  in `work/ships.jsonl`.
- **Undo** (ticket 05). "Undo the last update" is the same question-and-yes: traffic back to the
  revision before the ship, a revert commit on dev and main, the previous phone ship rebuilt with
  a new versionCode (Android never downgrades), or the book removed.
- **The hook** (`asklist_hook.py`). `git push`, the deploy scripts, `release_apk.py` and `gcloud`
  are denied to every helper; only this folder's own `ship.py` / `undo.py`, run alone, pass.
  Kevin's private skills (`work/.claude`) are his files: a helper writing one asks. The worker's
  own files (tokens, config, ship history) are never a helper's to write.
- **Records** (ticket 02). `GET /api/device-logs/` and `/api/device-logs/<id>/render` give a
  household member (the worker's token) the same rendered conversation `manage.py device_logs
  --render` prints; mail stays redacted as stored; a recruiter visit's record has no household
  and is never readable.
- **One build at a time** (ticket 08). `build_lock.py`: an OS file lock, and a pause before each
  build step while laptopctl reports over 92 C, until under 80 C. No CPU cap (Kevin).
- **Books** (tickets 06, 07). A household library on the engine (`book_library`), EPUB only, with
  a required licence note and source. The book-finder skill adds only legal free copies
  (Standard Ebooks, Project Gutenberg, Wikisource, public-domain Internet Archive items, the
  author's own), never a pirate site; with none, it says where to borrow or buy.

## Consequences

- The worker can now change production after one spoken yes. The guard is the question's
  content (the one line, the guarded-area line) and the tests; a wrong yes is undone by "undo".
- The hook matches commands by pattern. A helper that writes a script doing a push itself is not
  seen by it; the prompt rule ("never work around it") holds that, as ADR 0067 already accepts.
- Phone shipping needs alfred-ota merged (release_apk.py, versionCode from build time)
  and `ship.apk_bucket` in the worker config; until then `ship.py` refuses a phone ship in words.
- Book files live in Postgres like Claude task files; a bucket (the alfred-ota pattern) can
  replace that without changing any client, because downloads already go through signed links.
- Source: `.scratch/alfred-builds/map.md`, tickets 02 to 08.
