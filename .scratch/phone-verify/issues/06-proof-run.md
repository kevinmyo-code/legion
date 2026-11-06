---
map: phone-verify
ticket: "06"
title: "Proof run on the phone, no install"
type: test
status: resolved
status-detail: "run 2026-11-05 20:31-20:34 CDT on 263092342 fast, nothing installed: all six steps passed, no LEGION crash since the mark"
blockers: ["01", "02", "03", "05"]
blocked-by: ["[[01-phone-cli]]", "[[02-safety-rails]]", "[[03-screen-map]]", "[[05-skills-and-agents]]"]
tags: [ticket]
---
# Proof run on the phone, no install

The `verify-on-phone` skill end to end, on whatever build is installed, installing nothing:

1. `preflight`; `version --min 263092342` (older: report it and stop).
2. `logcat --mark`.
3. `open settings/memory`, screenshot, read the PNG.
4. `open vault` (taps: Data and privacy > ID vault), `screenshot --secure-check`: expect
   `blank: true` (FLAG_SECURE). `dump` shows the labels with no value; report a count only.
5. `back`, `open home`, `logcat`: no LEGION crash since the mark.
6. One `--by-taps` walk (`settings/memory`) to prove a code-derived reach path.

Needs the phone awake, unlocked and on wireless debugging. A locked phone is Kevin's to unlock.

## 2026-11-05 attempt

`adb devices -l` at 19:38 CDT: SM_A256U on wireless debugging. Read-only: versionCode 263092342,
versionName 2026-11-05-c353bcf, `fast` (no DEBUGGABLE), battery 52% not charging, screen off,
keyguard showing, LEGION's MainActivity resumed. One `uiautomator dump` to learn its format (it
returned the keyguard's tree; deleted from the phone, nothing kept). Nothing was tapped while it was
locked. It then left adb and mDNS (wireless debugging drops when the phone sleeps) and did not come
back by 20:20; `tools/phone connect` to its last address timed out. No step above ran.

## 2026-11-05 run, 20:31-20:34 CDT (on-device)

Build 263092342 `fast`, unlocked by Kevin, LEGION in front, battery 46%. Nothing installed.

| Step | Result |
|---|---|
| `preflight`, `version --min 263092342` | ready; exit 0 |
| `logcat --mark` | mark 20:31:16, phone clock |
| `open settings/memory` | deep link; header "What it remembers" seen; screenshot showed the Memory screen with its search field, "Add a memory by hand", a count, and rows with a share toggle, Edit and Forget |
| `open vault` | deep link to Data and privacy, tap "ID vault", VaultActivity in front; `screenshot --secure-check` blank (near-black share 0.9944: status and nav bars only); dump read the list: no items |
| vault rail | `tap --text "Add an item"` refused (exit 3, nothing sent); `tap --desc Back` allowed |
| `open settings/memory --by-taps` | 4 taps from HOME (gear, LEGION settings, Data and privacy, Memory); header seen; 26 s |
| `say "what's on my calendar today"` | typed chat panel; LIVE_AUDIT: `read_calendar` success, reply listed the day's items; screenshot showed it in the panel |
| `logcat` | 6 LEGION lines since the mark, no FATAL / AndroidRuntime |

Phone left on HOME. Screenshots under `.phone-artifacts/` on the laptop (gitignored).
Not covered: test tags as resource ids (ticket 04 needs a build with it installed).
