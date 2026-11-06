---
name: verify-on-phone
description: Drive LEGION on Kevin's real phone over adb to verify a change yourself - preflight, open the screen from the screen map, act, screenshot and read the PNG, read logcat - and report it tagged on-device instead of "owed on device". Use whenever a phone change's last check is "see it on the phone": a screen opening, a row showing, FLAG_SECURE blanking, back closing a sheet, a typed turn calling a tool. Built on `python tools/phone`, whose rails refuse vault reveals, sends, payments, deletes and installs.
---

# Verify on the phone

**Why this exists (Kevin, 2026-11-05).** Nearly every phone report one week ended "owed on device":
the fingerprint prompt, FLAG_SECURE, the back gesture, pinch zoom, the passport screen opening. Each
of those was a check an agent could have run and handed to Kevin instead. The idea, from a talk on
trusting agents: give the agent a way to drive the real app, a map of how to reach every screen, and
a way to keep the map current, and the human stops being the verifier.

The phone is Kevin's real phone (an A25 when this was written; `devices` says what is attached now,
never this sentence). Everything on it is his: messages, money, memories, contacts, the ID vault.

## When to use it

- A change's verification step says "on the phone" and the phone answers `preflight`.
- Before reporting a phone ticket `built`: if the phone is reachable, run its device steps.
- Not for performance numbers, wake-word tests, or anything needing a voice: those stay Kevin's.

## The rules (the CLI enforces these; the skill only explains them)

1. **The ID vault is looked at, never opened.** Opening the vault list (labels only) and checking
   that a screenshot of it is blank is allowed. `tap` refuses every control in the vault except
   Back, refuses `vault-show`/`vault-copy` anywhere, refuses `type` and `say` there, and masks
   number-like text in a vault dump. No flag lifts any of this. Never call the engine's reveal
   endpoint. In a report, give a vault item COUNT, never a label.
2. **Never uninstall, `pm clear`, or wipe app data; never change system settings.** The CLI has no
   command for them and no raw shell; `tools/hooks/guard_destructive.py` blocks them in Bash too.
   `tap` refuses the phone's Settings app and LEGION's "Phone settings".
3. **No purchases, no sends.** `tap` refuses a control whose words say send, pay, buy, delete,
   forget, ship, install (and call, share, clear, reset, sign out...). `--confirmed-by-kevin` lifts
   that refusal. **Pass it only after Kevin said yes to that exact action in this chat.** Not for
   "he would want it", not for a similar action earlier. The flag is written to the action log.
4. **Screenshots, dumps and logs hold Kevin's personal data.** They go under `.phone-artifacts/`
   (gitignored) by default, or an absolute path in the session scratchpad. The CLI refuses any
   other path inside the repo. The repo is public: never copy a screenshot, dump line or log line
   into a commit, a ticket, a doc or `docs/screen-map.md`.
5. **A typed turn can call real tools.** `say` sends a message to the assistant, which may act on
   it. Ask read-only questions ("what's on my calendar today", "what does my memory say about
   X") unless the task needs a write, and say in the report which you sent.
6. **Never drive a phone Kevin is using.** `preflight` names what is in front. If it is another app,
   or the phone is locked, stop and ask. `wake` lights the screen; unlocking is always Kevin's.

## Steps

All commands from the repo root. adb comes from `$ADB`, then the SDK under `%LOCALAPPDATA%`.

### 1. Preflight

```
python tools/phone devices          # what is attached, by model
python tools/phone connect          # if nothing: reconnect wireless debugging over mDNS
python tools/phone preflight        # build, battery, awake, locked, what is in front; exit 0 = ready
python tools/phone version --min <versionCode the change landed in>
```

Wireless debugging drops when the phone sleeps (`connect` brings it back if the phone is on the
same Wi-Fi). A new laptop needs pairing with the code on the phone: that is Kevin's.

`version` says `fast` or `debug`. **Which build matters** (CLAUDE.md section 6):

| Build | What it is | What it cannot do |
|---|---|---|
| `fast` | The daily driver: release-signed with the debug key, not debuggable, AOT-compiled | `run-as`, `DEBUG_SAY`, debug-only rows |
| `debug` | Debuggable, about 4x slower to launch on the A25 | Nothing; it is just slow |

- If the installed build is older than the change, **report that and stop.** Do not install unless
  the task asked for an install.
- If the task needs `debug` (a database pull, `DEBUG_SAY`), install it over `fast` with
  `adb install -r` (never uninstall; same key, data kept), and **put `fast` back after**:
  `adb install -r app/build/outputs/apk/fast/app-fast.apk`, then
  `adb shell cmd package compile -m speed -f com.kevin.legion` (a reinstall drops the compile).
  Verify an install by hash (`.claude/agents/device.md`), and say which build you left on the phone.

### 2. Mark the log, then navigate with the map

```
python tools/phone logcat --mark                   # the phone's clock, not the laptop's
python tools/phone screens                         # every screen id
python tools/phone open settings/memory            # deep link: same EXTRA_ROUTE a notification uses
python tools/phone open settings/memory --by-taps  # walk the hands path instead (checks ADR 0035)
python tools/phone open vault                      # link to Data and privacy, then tap "ID vault"
```

`docs/screen-map.md` has every screen's id, its taps from launch, its key controls and the voice
tools that open it. A screen with no deep link of its own (the vault is not exported, a Fleet
drilldown is in-screen state) is reached by linking to the nearest screen that has one and tapping
the rest. `open` then waits for the screen's header text, or for the activity to be in front, and
exits non-zero if it never shows.
Matrix mode changes HOME: `open` checks which home is up and refuses to toggle Matrix mode.

### 3. Act, then screenshot

```
python tools/phone dump                        # compact nodes: text, desc, tag, bounds, C=clickable
python tools/phone tap --text "Memory"         # or --desc "Back", --tag memory-message, --xy X Y
python tools/phone wait-for --text "Saved places" --timeout 10
python tools/phone scroll-to --text "Updates"
python tools/phone back | home | swipe up
python tools/phone screenshot memory.png       # -> .phone-artifacts/memory.png
python tools/phone screenshot vault.png --secure-check   # + near_black_fraction, blank: true|false
```

Every selector command takes a FRESH dump (a stale dump file reads exactly like the current
screen). Prefer text and desc; tags show only on a build with `testTagsAsResourceId` (phone-verify
ticket 04 onward). `--xy` is checked against the node under the point, so it is not a way past a
refusal. `uiautomator dump` can fail under an endless animation (Matrix code rain); screenshot
then.

### 4. Look at the screenshot

Read the PNG with the Read tool. Say what is on it in words: which screen, which rows, what state.
A black frame is either FLAG_SECURE (expected on the vault: `--secure-check` says `blank: true`)
or the keyguard (`preflight` says `locked`). A screenshot you did not open is not evidence.

### 5. Read the log

```
python tools/phone logcat                  # LEGION's pids + LIVE_AUDIT/LIVE_LATENCY/... + LEGION crashes, since the mark
python tools/phone logcat --grep tool=     # filter
python tools/phone logcat --out run.txt    # full text to .phone-artifacts/
```

`LIVE_AUDIT` lines carry what was said and every tool call with its result. A crash shows as
`FATAL EXCEPTION` under `AndroidRuntime`.

### 6. A typed turn (only if the change is about the assistant)

```
python tools/phone open home
python tools/phone say "what's on my calendar today"
```

On `debug` this is the `DEBUG_SAY` broadcast (the reply is spoken). On `fast` it opens the chat
panel (`Chat with ...`), types into `Type to ...` and presses its Send button: the one Send the CLI
presses, because `say` is itself the request. Read the reply with `dump` or a screenshot, and the
tool calls with `logcat`.

### 7. Report

- Tag what you saw `on-device`, with the build and versionCode it ran on.
- Name each screenshot by its path and say what it showed.
- Say what you could not check and why ("phone locked: Kevin unlocks", "no fixed tap path").
- Say which build you left on the phone, and that you left it on a neutral screen (`open home`).
- `.phone-artifacts/actions.log` lists every tap, refusal and flag this session used.

If `preflight` fails, the report says so in one line with the reason, and the check stays owed:
that is still better than "owed on device" with no attempt.
