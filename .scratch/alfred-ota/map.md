---
map: alfred-ota
title: "Patch Alfred over the internet: wording from the engine, the app updates itself"
charted: 2026-11-05
charted-by: "Kevin + Opus"
effort: "`.scratch/alfred-ota/`"
tickets: 8
open: 8
status: open
tags: [map]
---

# Patch Alfred over the internet

**Kevin, 2026-11-05:** *"today we deploy via adb installs, is there anyway to patch alfred over the
internet"*, then *"lets do it. write a plan then we implement"*.

Today the only way to change Alfred on the phone is a laptop, wireless adb and `install_phone.sh`.
This map has two halves, and both are needed:

| Half | What changes | How it reaches the phone | When it applies |
|---|---|---|---|
| **A. The Alfred pack** | Wording: prompt clauses, tool descriptions, tool-result framing, feature flags | Engine deploy. The phone fetches the pack at session start | The next conversation |
| **B. Self-update** | Code: anything in Kotlin | The APK goes to a private bucket, the engine records the release, and the phone downloads, verifies, asks, then installs | After Kevin says yes |

Most Alfred fixes so far were wording, not code: the reply-in-English rule, recall embellishment,
tool honesty, holding lines. Half A turns those into an engine deploy with no phone install.

It closes the "Alfred improves Alfred" loop with the worker's `legion-chores` skill:
1. Kevin says the answer was wrong.
2. Claude fixes it on a branch, with tests.
3. A wording fix becomes a pack change and an engine deploy (Kevin runs the deploy).
4. A code fix becomes a published release; the phone says "update ready, fixes X, install?" and Kevin says yes.

## What already exists (checked 2026-11-05, dev `34266cec`)

- **Prompt source of truth.** `server/assistant/shared_clauses.txt` is the source. A generator writes
  `shared_clauses.py`, and `tests/test_assistant_prompt.py` fails if `ai/AriaBrain.kt`'s constants
  drift from it. The phone does not read the engine at runtime: it builds the prompt itself in
  `AriaBrain.assembleBase` (`ai/AriaBrain.kt:212-300`), cached for `BASE_TTL_MS`. The twins test
  is the seam half A replaces.
- **Signing.** The `fast` build (what Kevin runs) is signed with the **debug** key
  (`app/build.gradle.kts:149-151`). Android only accepts an update signed by the same key, so every
  release must come from this laptop's `~/.android/debug.keystore`, or Kevin moves to a real key
  once (which needs one uninstall). See ticket 04.
- **versionCode is always 1** (`app/build.gradle.kts:103`). An update needs a monotonic code.
- **targetSdk 34**: self-updates with no tap (`USER_ACTION_NOT_REQUIRED`, Android 12+) are allowed.
- **The APK is big: 180-220 MB** (fast builds 11-03 and 11-04). It carries the voice models
  (Smart Turn, Silero, wake word and others). Updates are Wi-Fi only unless Kevin asks; moving the
  models out of the APK is ticket 08.
- **No Cloud Storage client in the engine** (`server/requirements.txt`). Signed download URLs need
  `google-cloud-storage` plus `iam.serviceAccounts.signBlob` for the Cloud Run service account.
- **Live session start** already logs the model and tools in LIVE_AUDIT (`sessionStart`). The pack
  version joins that line, so every conversation record says which wording it ran.

## Decisions for Kevin

**All five DECIDED 2026-11-05 (Kevin picked every recommendation):** repo file + deploy; lock the safety set; always ask before install; Kevin or the worker (after his yes) publishes, debug key kept and backed up.

1. **Where the pack lives** ([Pack format](issues/01-alfred-pack-format-and-endpoint.md)).
   - Recommended: a file in the repo (`server/assistant/packs/alfred.yaml`), served by the engine and
     changed by an engine deploy. That keeps it reviewed, tested and in git; rollback = deploy the
     previous commit.
   - Alternative: rows in Postgres, editable without a deploy. Faster, but untested edits go live.
   - **Recommend: repo file.**
2. **What the engine may override** ([Pack format](issues/01-alfred-pack-format-and-endpoint.md)).
   - Recommended: everything in the pack EXCEPT a locked set compiled into the app, which the phone
     ignores even if the pack sends it: CRISIS, SAFETY_INSTRUCTIONS, CANNOT_CLAUSE (honesty), the
     ask-before rules (pay, send, post, delete, book), and the never-type-passwords rule.
   - A compromised engine must not be able to talk Alfred out of these.
3. **Install consent** ([Phone updater](issues/05-phone-updater.md)).
   - Recommended: always ask. A notification plus Alfred saying "update ready, fixes X, install?";
     yes by voice or tap. Matches the ask list ("installing software").
   - Never mid-conversation, never in Matrix or driving mode.
   - Alternative: auto-install when idle and charging.
   - **Recommend: always ask.**
4. **Who publishes a release** ([Publisher](issues/04-version-and-publisher.md)).
   - Recommended: `tools/release_apk.py`. Kevin runs it via `!`, OR the worker runs it inside a
     `legion-chores` task after Kevin's yes (publishing counts as a send).
   - The signing key never leaves this laptop.
5. **Signing key** ([Publisher](issues/04-version-and-publisher.md)).
   - Recommended: keep the debug key for now (no uninstall, nothing lost); back up
     `~/.android/debug.keystore` to a safe place, because losing it means no more updates without
     an uninstall.
   - Move to a real release key later, in one planned reinstall.

## Tickets

| # | Ticket | Half | Type | Blocked by |
|---|---|---|---|---|
| 01 | [Alfred pack: format and engine endpoint](issues/01-alfred-pack-format-and-endpoint.md) | A | build (server) | decisions 1, 2 |
| 02 | [Phone reads the pack](issues/02-phone-reads-the-pack.md) | A | build (phone) | 01 |
| 03 | [Move wording into the pack](issues/03-move-wording-into-the-pack.md) | A | build (both) | 02 |
| 04 | [versionCode, private bucket, releases, publisher](issues/04-version-and-publisher.md) | B | build (server + tools) | decisions 4, 5 |
| 05 | [Phone updater: check, download, verify, ask, install](issues/05-phone-updater.md) | B | build (phone) | 04, decision 3 |
| 06 | [Updates by voice and on a screen](issues/06-voice-and-screen.md) | B | build (phone) | 05 |
| 07 | [Acceptance run on the A25](issues/07-acceptance-run.md) | A+B | device run (Kevin) | 02, 06 |
| 08 | [Later: shrink the APK, head unit, worker publish](issues/08-later.md) | B | backlog | 07 |

Order: 01, 02 and 04 can run in parallel (server, phone and tools are separate files; one Gradle at
a time). Then 03 and 05, then 06, then Kevin's run (07). ADR 0072 is written with 01 and 05, once
the decisions are ruled.

## Rules this map keeps

- Ask before install, send, pay, post, delete or book. A release publish counts as a send; an
  install counts as installing.
- The phone works with the engine down: the bundled pack is the fallback, said in LIVE_AUDIT, never
  an error.
- Persona stays Alfred (no "sir", no "Mr"). Gemini Live native audio stays.
- No profile data in the public repo: the pack holds rules and wording, never Kevin's facts.
- Deploys stay Kevin's.
