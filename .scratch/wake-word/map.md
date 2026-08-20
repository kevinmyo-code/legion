---
map: wake-word
title: "Map: Wake word, reachable and honest on a phone"
charted: 2026-08-20
charted-by: "Kevin + Opus"
effort: "`.scratch/wake-word/`"
tickets: 7
open: 7
status: open
tags: [map]
---
# Map: Wake word, reachable and honest on a phone

## Destination

**Kevin can turn the wake word on from Settings, say "hey <name>" to the A25 while it sits on
battery with the screen off, and know what that costs him in battery.** Execution is in scope: the
end state is a hash-verified install he can talk to, not a pile of decisions.

## Notes

**Domain:** LEGION, Android phone app (Kotlin, Compose, Room v26), `com.kevin.legion`. Read
`CLAUDE.md` for rules and `memory/MEMORY.md` for state before deciding anything.

**Where this came from.** Kevin, 2026-08-20: *"i want wake word and ambient listening. chart both as
separate efforts."* Split into two maps at his direction, with [ambient listening](../ambient-listening/map.md)
downstream of this one - they contend for the same microphone, and this map is where that contest
gets settled.

### What exists today - traced 2026-08-20, not remembered

| Piece | State |
|---|---|
| `service/WakeWordEngine.kt` | **283 lines, complete.** Vosk, runtime-reconfigurable grammar built from `CompanionProfile.name`, 4s trigger floor, watchdog, debug event ring |
| Vosk dependency | Ships. `app/build.gradle.kts:195`, `libs.vosk.android` |
| Vosk model | Bundled. `app/src/main/assets/vosk-model` |
| Service wiring | Live. `AriaForegroundService` calls `start` (:296), `refresh` (:391), `stop` (:815) |
| `service/WakeWordPreferences.kt` | Exists, read by the engine |
| **Anything that WRITES that preference** | **NOTHING.** Zero writers anywhere in `app/src/main`. |

**The structural fact that shapes this map: the feature is finished and unreachable.**
`WakeWordEngine.start()` no-ops unless the toggle is on, and no screen can turn it on. This is not a
build effort. It is a reachability effort followed by an honesty effort.

### The premise that died and was never re-tested

`WakeWordEngine`'s own KDoc records its 2026-07-19 on-hardware validation: *"battery/CPU draw is a
non-issue since the head unit is on shore power the entire time the engine is running (parked-and-off
is not a state this needs to work in)."*

**Phone-only killed that sentence.** A phone is on battery, sleeps, and enters Doze. Every clean
result from that validation was measured under an assumption LEGION no longer holds. Kevin's call
2026-08-20: **always on, but measure first** - the number decides the scope rather than a guess.

### Standing preferences for this effort

- **Kevin is at the abstraction layer.** Bring him forks with real cost or taste; decide
  implementation without asking.
- **Measure before scoping.** No ticket here may assume a battery cost. One ticket exists purely to
  produce that number, and the scope tickets are blocked on it.
- **CLAUDE.md sec 7's outcome-verb rule binds anything this map makes the assistant say.** A wake
  word that fires and then silently fails to open a session must say so.
- **Consent is a written toggle, never an inferred one.** Same posture as
  `service/AssistantIgnition.kt`: the Settings handler is the only writer.

## Decisions so far

<!-- one line per resolved ticket -->

## Not yet specified

- **What "hey <name>" should DO once it fires.** Today it broadcasts `ACTION_TALK`. Whether that
  should open the live session directly, or acknowledge first and wait, hangs on how reliable the
  trigger turns out to be on this hardware.
- **Multiple companion profiles.** The grammar is built from one `CompanionProfile.name`. What
  happens when Kevin has two profiles with different names is unasked.
- **Whether "hey" is even the right prefix**, and whether a bare name should trigger.
- **Recovery after the recognizer dies.** There is a watchdog, but nothing has observed it firing on
  a phone across a screen-off period.

## Out of scope

- **Ambient listening.** Its own map, deliberately: it records whoever is in the room, which is a
  different privacy posture and a different size of job.
- **The Android Auto microphone.** `.scratch/android-auto/` owns that, and it is KIV.
- **Replacing push-to-talk.** Wake word supplements the existing ignition path; it does not become
  the only way in.
