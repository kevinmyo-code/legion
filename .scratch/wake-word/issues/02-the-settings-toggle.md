---
map: wake-word
ticket: "02"
title: "The Settings toggle that nothing currently writes"
type: task
status: resolved
status-detail: "Resolved on the A25 2026-08-20, hash-verified. The wake word has now run in LEGION for the first time. Its own verification step caught two defects: refresh() cannot ignite the engine, and the Vosk model was never on this machine."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# The Settings toggle that nothing currently writes

## Question

Nothing to decide. `WakeWordPreferences` is read by `WakeWordEngine` and written by **nothing** -
the feature cannot be turned on. This ticket makes it reachable, and it unblocks the measurement
ticket, which cannot run an engine that will not start.

Add a wake-word row to `ui/SettingsScreen.kt` following the existing `ui/SettingsRows.kt`
conventions and the pattern `service/AssistantIgnition.kt` already sets:

- The toggle handler is the **only** writer of the preference. Consent changes in exactly one place.
- Off by default. It stays a supplement to push-to-talk, never a replacement.
- Flipping it on must call through to `WakeWordEngine.refresh(context)` so the running service picks
  it up without a restart - `AriaForegroundService:391` already exposes that path.
- The row says what it costs in plain words once ticket 03 has a number. Until then it must not
  claim a cost it does not know.

**Verification:** flip it on, flip it off, kill the app, reopen it, confirm the state survives and
that `WakeWordEngine` actually starts and stops. Read the state back from the device, not from the
UI - a toggle that renders On while the engine is dead is precisely the bug
`AssistantIgnition.resumeIfEnabled` exists to fix.

## Answer

Built and verified on the A25 on 2026-08-20, APK hash-verified. **The engine has now run in LEGION
for the first time.**

`ui/SettingsRows.kt` gains `WakeWordRow` and `ui/SettingsScreen.kt` wires it, with the handler as the
only writer of `WakeWordPreferences`. Three previews cover on, off, and the on-with-no-companion
state. The row renders the companion's real name rather than a hardcoded one.

### The verification step earned its place. It caught two defects the compile could not.

**1. `refresh()` cannot start the engine.** The first wiring called `WakeWordEngine.refresh(context)`,
which reads as the obvious choice and is wrong: it opens with `val loadedModel = model ?: return`, so
it is a no-op unless the engine is ALREADY running. It rebuilds a live grammar; it does not ignite
one. The toggle wrote the preference and started nothing, and the row then read "On" over a dead
engine - **the exact defect this ticket's verification text predicted in writing**. Now calls
`start()` / `stop()`, which are idempotent and read the preference themselves.

**2. The Vosk model was never on this machine.** `assets/vosk-model/` held only its own README - the
40MB model is deliberately gitignored and must be fetched per that README, which had never been done
here. The engine failed with `Failed to create a model`. Fetched; the debug APK goes from 78MB to
120MB, which is the honest cost of this feature and a number the map did not have before.

**This also corrects a claim in the map.** Charting listed the model as "Bundled" on the strength of
the directory existing. Nobody looked inside it. `traced` was claimed where only `ls` had been run.

### What was verified on the device, not reasoned

| Step | Result |
|---|---|
| Row renders, correct name | Yes - "Off - press to talk..." then "On - say \"hey alfred\"..." |
| Preference written | `wake_word_preferences.xml` did not exist before this; now written |
| Engine starts from the toggle | `UpdateGrammarFst(): ["hey alfred", "[unk]"]`, without a service restart |
| Engine stops from the toggle | Pref `false`, Vosk torn down, mic released |
| State survives a kill | Force-stopped and cold-started; pref held and the engine came up on its own |

### Owed onward

Turning it on also exposed that the grammar was hardcoded to "hey moose" - filed and fixed as
[The grammar is still hardcoded to hey moose](09-unhardcode-hey-moose.md). Whether the phrase is
actually RECOGNIZED when spoken is
[Prove hey-name fires on the A25, screen off, on battery](06-prove-it-on-the-phone.md), untouched by
this ticket.
