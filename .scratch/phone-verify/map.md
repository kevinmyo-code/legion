---
map: phone-verify
title: "Agents verify phone work on the real phone"
charted: 2026-11-05
charted-by: "Kevin + Opus"
effort: "`.scratch/phone-verify/`"
tags: [map]
---

# Agents verify phone work on the real phone

**Kevin, 2026-11-05:** *"yeah do the phone driving skill plus screen map"*, after a Cursor engineer's
talk on trusting agents: give the agent a skill to drive the real app (taps, screenshots, traces,
logs), a feature map of how to reach every screen, and a skill that keeps the map current, and the
human stops being the verifier. Almost every LEGION phone report that week ended "owed on device":
the fingerprint prompt, FLAG_SECURE, the back gesture, pinch zoom, the passport screen opening.

## Destination

An agent finishing phone work runs `python tools/phone preflight`, opens the screen from
`docs/screen-map.md`, acts, screenshots, reads the PNG and the log, and reports `on-device`. Kevin
gets results, not homework. The rails that keep it safe on his real phone are code, not prose.

## Decisions (taken in charting, Opus; open to Kevin)

1. **Plain adb + Python stdlib, no Maestro or Appium.** Both need a JVM or Node server on the laptop
   and an agent APK or driver on the phone; Appium's UiAutomator2 server is an install on Kevin's
   phone. `uiautomator dump`, `input` and `screencap` already do what verification needs, and
   every rail sits in one place we own.
2. **`open` uses the deep link by default**: `am start -n .../.ui.MainActivity --es route <route>`,
   the `EXTRA_ROUTE` notifications already use. One step, no tap risk. `--by-taps` walks the
   map's selectors instead, which is how a hands path (ADR 0035) gets checked. Only routes in the
   map are ever sent, because Navigation throws on a route the graph does not have.
3. **Selectors are text and content descriptions first, test tags second.** The installed build has
   no `testTagsAsResourceId`, and the app has few tags. Ticket 04 turns tags on at the window roots
   and adds them where a rail needs them (`vault-show`, `vault-copy`) or text is dynamic (chat).
4. **Generated map, hand copy, drift fails** (the `voice_guide.py` posture). Routes, edges, tap
   labels, header text and tags are read from code; names, descriptions, voice tools and reach
   overrides live in `tools/screen_map_copy.py`. Every manifest activity and `MatrixDest` must be
   mapped or listed as ignored with a reason. Hooked into the commit hook beside `voice_guide.py`.
5. **Rails, in code, three kinds.** Absolute: any vault tap but Back, typing or `say` in the vault,
   the reveal tags anywhere, the phone's Settings. Confirmable with `--confirmed-by-kevin` (only
   with his yes in chat): controls whose words say send, pay, buy, delete, forget, ship, install,
   call, share, clear, reset, sign out; any tap with another app in front. Paths: artifacts only
   under gitignored `.phone-artifacts/` or outside the repo. No command exists for uninstall,
   `pm clear`, wipes or a raw shell.
6. **`say` on `fast` uses the typed chat panel** (no `DEBUG_SAY` there): the one Send the CLI
   presses, because `say` is the request. Read-only questions unless the task needs a write.
7. **The phone's clock marks the log** (`logcat --mark`), not the laptop's; `phone_audit.sh` used
   host time.
8. **Never toggle Matrix mode** (it changes Do Not Disturb). `open` checks which HOME is up.
9. **Never unlock.** `wake` lights the screen; a PIN or fingerprint is Kevin's.

## Kevin's to decide

- Whether the confirmable word list is right (it refuses more than the brief's seven words: call,
  share, clear, reset, sign out, disconnect, remove, order...).
- Whether agents may install a `debug` build to use `DEBUG_SAY` without asking each time (the skill
  says only when the task asks for an install).

## Tickets

| # | Ticket | Status |
|---|---|---|
| 01 | [[01-phone-cli]] | resolved |
| 02 | [[02-safety-rails]] | resolved |
| 03 | [[03-screen-map]] | resolved |
| 04 | [[04-test-tags-as-ids]] | built |
| 05 | [[05-skills-and-agents]] | resolved |
| 06 | [[06-proof-run]] | resolved |
