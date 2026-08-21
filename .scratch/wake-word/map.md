---
map: wake-word
title: "Map: Wake word, reachable and honest on a phone"
charted: 2026-08-20
charted-by: "Kevin + Opus"
effort: "`.scratch/wake-word/`"
tickets: 11
open: 8
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
| Vosk model | **CORRECTED 2026-08-20: NOT bundled.** That directory held only its own README; the 40MB model is gitignored and must be fetched per it. Charting claimed `traced` on the strength of the directory existing. Fetched now; the debug APK goes 78MB -> 120MB |
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

- [The Settings toggle that nothing currently writes](issues/02-the-settings-toggle.md) - built and
  verified on the A25; **the engine has now run in LEGION for the first time.** Its own verification
  step caught two defects a compile could not: `refresh()` cannot ignite a stopped engine (it is a
  no-op unless one is already running), and the Vosk model had never been fetched onto this machine.
- [The grammar is still hardcoded to hey moose](issues/09-unhardcode-hey-moose.md) - a 2026-07-21
  head-unit workaround that outlived its hardware. The phone was listening for "hey moose" while the
  Settings row said "hey alfred". Name-driven again, and a blank name now refuses to start rather
  than holding the mic against an empty grammar.
- [Does a foreground service still get the microphone with the screen off?](issues/01-mic-under-doze.md) -
  the manifest already complies and no change is needed there; Doze does not stop a running foreground
  service (**`reasoned` from an absence, not an explicit exemption**); Samsung's deep sleep versus a
  live microphone service is **not established**; and the silent-failure fear is **confirmed** - another
  app taking the mic yields silence with no error, and neither Vosk engine detects it, which is now
  [The wake word cannot tell silence from a quiet room](issues/08-silenced-not-quiet.md).

## Not yet specified

<!-- GRADUATED 2026-08-20: "what hey <name> should DO once it fires" left this section the moment
     Kevin heard the first successful trigger and found the silence wrong. It is now two tickets,
     10 and 11 - the opening line and the closing one. -->

- **Multiple companion profiles.** The grammar is built from one `CompanionProfile.name`. What
  happens when Kevin has two profiles with different names is unasked.
- **Whether "hey" is even the right prefix**, and whether a bare name should trigger.
- **Recovery after the recognizer dies.** There is a watchdog, but nothing has observed it firing on
  a phone across a screen-off period. Narrowed by ticket 01: the watchdog is not the only gap, since
  a *silenced* recognizer has not died and the watchdog would see nothing wrong.

## Out of scope

- **Ambient listening.** Its own map, deliberately: it records whoever is in the room, which is a
  different privacy posture and a different size of job.
- **The Android Auto microphone.** `.scratch/android-auto/` owns that, and it is KIV.
- **Replacing push-to-talk.** Wake word supplements the existing ignition path; it does not become
  the only way in.
