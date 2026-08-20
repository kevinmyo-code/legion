---
map: ambient-listening
title: "Map: Ambient listening, and what it owes the room"
charted: 2026-08-20
charted-by: "Kevin + Opus"
effort: "`.scratch/ambient-listening/`"
tickets: 7
open: 7
status: open
tags: [map]
---
# Map: Ambient listening, and what it owes the room

## Destination

**Ambient listening can be turned on knowingly, shows that it is listening the whole time it runs,
reacts only when it genuinely should, and Kevin knows what it costs per drive.** Execution is in
scope. The end state is an install he can leave running without wondering what it heard.

## Notes

**Domain:** LEGION, Android phone app (Kotlin, Compose, Room v26), `com.kevin.legion`. Read
`CLAUDE.md` for rules and `memory/MEMORY.md` for state before deciding anything.

**Where this came from.** Kevin, 2026-08-20: *"i want wake word and ambient listening. chart both as
separate efforts."* Two maps at his direction.

**This map is downstream of [wake word](../wake-word/map.md), and the local tracker cannot express a
cross-map blocker**, so it is written here instead of as a `Blocked by` line. Two of that map's
answers are inputs to this one, and starting here before they land means deciding them twice:

| Waiting on | Why it matters here |
|---|---|
| [Who owns the microphone, and what yields to what?](../wake-word/issues/05-mic-ownership.md) | The two engines are mutually exclusive today by an explicit guard in both `start()` methods. Ambient inherits that ruling. |
| [What drain is acceptable, and what happens when it is not met?](../wake-word/issues/04-what-drain-is-acceptable.md) | Ambient runs the same Vosk model continuously and then adds periodic Gemini calls. It cannot be cheaper than the wake word. |

### What exists today - traced 2026-08-20, not remembered

| Piece | State |
|---|---|
| `service/AmbientListener.kt` | **279 lines, complete.** Open-vocabulary Vosk transcription, accumulating transcript, periodic `SubAgent` reaction pass through `ProactiveBus.speakIfAllowed` |
| Vosk model | Shared with the wake word. No extra download - a small model already does open dictation |
| Mute as a listening gate | Implemented, and **stricter than the speaking-only mute** other proactive sources use |
| Mic exclusivity with wake word | Implemented, guards in both `start()` methods |
| `service/AmbientListenPreferences.kt` | Exists, read by the engine |
| **Anything that WRITES that preference** | **NOTHING.** Same dead-code shape as the wake word. |
| **The required listening indicator** | **GONE.** Its KDoc points at `ui/CruiseScreen.kt`, which does not exist in LEGION - it died with the city-pop UI. |

**The structural fact that shapes this map: the engine is finished, and the consent surface its own
design calls mandatory was deleted.** `AmbientListener`'s KDoc states a persistent on-screen
indicator *is required whenever this is actually running*. Shipping the engine without rebuilding
that indicator would ship it against its own spec.

### Kevin's ruling on the consent surface, 2026-08-20

Asked directly what the consent surface should be on a phone: **an in-app indicator plus a toggle to
turn it on and off.** Not a notification-only answer. That fixes the shape of tickets 01 and 02 and
is not re-openable inside this map.

### Standing preferences for this effort

- **Kevin is at the abstraction layer.** Bring him forks with real cost or taste; decide
  implementation without asking.
- **This is not the wake word's privacy posture and must never inherit its consent.** The wake word
  discards everything except one fixed phrase. This transcribes whoever is in the room. Off by
  default, opted into knowingly, per the engine's own KDoc and `CLAUDE.md` sec 7.
- **`CLAUDE.md` sec 7's memory rule binds hard here.** A persona may be fond of the driver; it may
  not invent unfalsifiable history with him. An ambient transcript is the easiest possible source of
  exactly that, and the most tempting.
- **Genuine distress routes to `ai/CrisisDetector.kt`** and stops performing the character. An
  always-listening feature makes that path far more likely to fire than push-to-talk ever did.

## Decisions so far

<!-- one line per resolved ticket -->

## Not yet specified

- **Whether ambient listening should subsume the wake word entirely.** The engine's KDoc argues it
  should, since an open transcript already contains "hey <name>". That argument was made for a head
  unit and is re-opened by the wake word map, not here.
- **What the reaction pass is allowed to remember.** Distinct from what is retained on disk: whether
  anything it hears may enter the assistant's durable memory at all.
- **Non-driving contexts.** The engine was designed for a car cabin. A phone is in rooms, at desks,
  and in other people's houses, and nothing in the current design notices the difference.
- **A second speaker who is not a passenger** - a voice on a phone call, a television, a video.

## Out of scope

- **The wake word itself.** Its own map.
- **Cloud-side always-open audio.** Explicitly rejected at design time (2026-07-22) in favour of
  local transcription plus a periodic text call, and this map does not re-open it.
- **Recording or storing audio.** Transcription is local and the audio is not kept. Anything that
  would retain raw audio is a different effort with a different consent conversation.
