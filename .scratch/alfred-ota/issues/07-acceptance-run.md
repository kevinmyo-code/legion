---
map: alfred-ota
ticket: "07"
title: "Acceptance run on the A25"
type: test
status: open
status-detail: "charted 2026-11-05; Kevin's run after 02 and 06"
blockers: ["02", "06"]
blocked-by: ["[[02-phone-reads-the-pack]]", "[[06-voice-and-screen]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# Acceptance run on the A25 (Kevin)

1. **Last adb install.** Install the updater build once over adb, then grant LEGION "Install unknown apps".
2. **Pack.**
   - Change one harmless rule on the engine (an `extra_rules` line), deploy, and start a conversation.
   - Expect LIVE_AUDIT to show `pack=<new>/remote`, and Alfred to follow the rule.
   - Turn on airplane mode and start another conversation. Expect `pack=<n>/cache`.
3. **First release.**
   - Build `assembleFast` and run `tools/release_apk.py`.
   - Away from the laptop, on Wi-Fi, say "check for updates". Alfred reads the notes and asks; say yes.
   - Android shows its own confirm this first time. Accept it.
4. **Second release.**
   - Publish another release. This time there should be no Android dialog, only Alfred's ask.
   - After install the app relaunches. Check that the wake word and the HOME role still work, and time the first launch.
5. **Wrong signer.**
   - Build an APK signed with a different key and push it to a test bucket, bypassing the publisher's refusal.
   - The phone must refuse to install it and say so in words.
