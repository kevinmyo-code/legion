---
map: ambient-listening
ticket: "02"
title: "The toggle, and the words next to it"
type: task
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-the-listening-indicator]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# The toggle, and the words next to it

## Question

Nothing to decide about the mechanism: `AmbientListenPreferences` is read by the engine and written
by nothing, exactly like the wake word's. This ticket makes it reachable and writes the copy.

The mechanism, following `ui/SettingsRows.kt` and the single-writer pattern
`service/AssistantIgnition.kt` sets:

- The toggle handler is the only writer. Off by default.
- Turning it on must not be reachable by accident, and must not inherit the wake word's consent -
  a separate, deliberate act, per the engine's own KDoc.
- Wire the indicator from [What the listening indicator looks like, and where it lives](01-the-listening-indicator.md)
  to the same state.

The copy is the actual work, and it is harder than the switch:

1. **Say that it transcribes everyone in the room, not just Kevin.** That is the fact that makes this
   different from the wake word, and burying it would be the whole failure.
2. **Say that transcription is local and audio is not stored**, because that is true and it is the
   thing that makes the feature defensible.
3. **Say that some text goes to Gemini periodically**, because that part is not local.
4. **No estimate stated as fact.** If the cost per drive is not yet measured, the copy does not name
   a number - `CLAUDE.md` sec 7 already binds estimates to be labelled as estimates.

**Verification:** flip it on, confirm the engine starts and the indicator appears; flip mute on,
confirm the engine actually stops listening rather than merely stops speaking; kill and reopen the
app and confirm the state and the engine agree.
