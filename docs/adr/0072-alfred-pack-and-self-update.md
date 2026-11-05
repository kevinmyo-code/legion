---
status: accepted
decided: 2026-11-05
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-11-05 - Patch Alfred over the internet: the pack and self-update (Kevin)]]"
tags: [adr]
---

# 72. Alfred's wording comes from the engine; the phone updates itself, and asks first

## Standing

**Two halves, both binding** (map `.scratch/alfred-ota/`, Kevin ruled all five decisions as
recommended on 2026-11-05).

**A. The Alfred pack.** Alfred's wording (prompt clauses, tool wording, flags, extra rules) is a
versioned pack in the repo, `server/assistant/packs/alfred.yaml`, generated from
`shared_clauses.txt` plus `alfred.source.yaml` and changed only by an engine deploy (decision 1).
The engine serves it at `GET /api/assistant/pack` with an ETag. The phone (`ai/AlfredPack.kt`)
fetches it at app start and whenever the cached prompt is dropped, with a 2 s timeout, keeps the
last good pack on disk, and otherwise runs the pack its APK was built with. Engine down or any
error is silent: only LIVE_AUDIT's `session_start ... pack=<version>/<remote|cache|bundled>` says
which wording a conversation ran. A remote pack with a lower version than the APK's is ignored.

**The locked set is compiled into the app** (decision 2): `CRISIS`, `SAFETY_INSTRUCTIONS`,
`CANNOT_CLAUSE`, `ASK_BEFORE` and `NO_PASSWORDS`. The engine refuses to load a pack that names
one; the phone drops them anyway, as a clause, a tool override or a flag, in any letter case. A
compromised engine cannot talk Alfred out of these. The layer order of the prompt stays in code;
only slot text comes from the pack.

**B. Self-update.** A release is an APK in a private bucket, recorded on the engine
(`server/releases/`) by `tools/release_apk.py`, which Kevin runs, or the worker after his yes
(decision 4: a publish counts as a send). The `fast` build stays signed with this laptop's debug
key (decision 5); `deploy/release/signer.sha256` pins it. The phone (`update/`) checks
`/api/releases/latest`, downloads on Wi-Fi only unless Kevin allows mobile data, and refuses in
words a file whose sha256 differs from the release's or whose signer is not the installed app's.

**Always ask** (decision 3). A notification with Install and Later, and Alfred says it once at a
natural pause. Kevin's yes, by voice or tap, is required every time. **Never installed
mid-conversation, in Matrix mode, or with the car connected:** a yes given in a conversation
installs when the conversation ends; Matrix or the car refuses in words. `PackageInstaller`
commits with `USER_ACTION_NOT_REQUIRED` on Android 12+, so after the first update Kevin's yes is
the only prompt. `MY_PACKAGE_REPLACED` restarts the assistant service. The voice tool `app_update`
and Settings, Updates call the same `UpdateController` (ADR 0035).

**versionCode is `yyDDDHHmm` UTC** for `fast` and `release` (year, day of year, hour, minute;
monotonic, under Android's 2,100,000,000 cap until 2099), `debug` stays 1 and never self-updates.
The map first said `yyMMddHHmm`, which is already past both the Int and Android caps.
versionName is `<yyyy-MM-dd>-<short sha>`, or `-dev` with no git.

## Context

The only way to change Alfred on the phone was a laptop, wireless adb and an install, and most
Alfred fixes are wording. An APK is 180-220 MB, and Android only accepts an update signed by the
installed app's key.

## Consequences

- A wording fix is an engine deploy; the next conversation runs it. A code fix is a published
  release and Kevin's yes.
- Losing `~/.android/debug.keystore` means no more updates without an uninstall. Back it up.
- The first update after an adb install still shows Android's own confirm (adb is the installer
  of record); after it LEGION is.
- Rollback is a new build of the old code with a higher versionCode. Nothing downgrades.
- Every word the pack adds to the prompt is billed on every Live reconnect, like any clause
  (`LiveSetupPayloadSizeTest` measures the bundled pack, not what the engine later sends).
