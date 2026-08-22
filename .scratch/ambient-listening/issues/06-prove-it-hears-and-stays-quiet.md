---
map: ambient-listening
ticket: "06"
title: "Prove it hears, it indicates, and it mostly stays quiet"
type: task
status: closed
status-detail: "Out of scope 2026-08-21 - ambient listening retired"
blockers: ["02", "03"]
blocked-by: ["[[02-the-toggle-and-its-words]]", "[[03-what-is-worth-speaking-into]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Prove it hears, it indicates, and it mostly stays quiet

## Question

Nothing to decide. `AmbientListener` has **never run on this phone**, and the open-vocabulary path is
a harder test than the wake word's fixed grammar - a small Vosk model doing open dictation in a room
is where transcription quality actually shows.

On the A25, APK hash-verified:

1. **It transcribes.** Ordinary conversation, at ordinary volume, at conversational distance. How
   good is the transcript really? A model that mishears half of it feeds garbage to the reaction pass
   and every downstream judgement is built on that.
2. **The indicator is visible the entire time it runs**, including after the app has been
   backgrounded and returned to. If it is not, ticket 01's answer was wrong and should be reopened.
3. **Mute is a listening gate, not a speaking gate.** Mute it and confirm the engine actually stops
   consuming the microphone - the KDoc says this is stricter than the ordinary proactive mute, and
   that claim has never been checked on hardware.
4. **It stays quiet.** Over a realistic session, how often does it speak unprompted, and was it right
   to each time? Ticket 03's bar is the spec; this is the measurement against it.
5. **It yields the microphone** to a live turn and to a phone call, and recovers afterwards.

**The failure to watch for is silence with no error** - the same shape as
`.scratch/android-auto/issues/15-the-live-session-can-be-silenced.md`. An engine that has stopped
hearing looks exactly like a quiet room. Prove the channel before concluding anything from an absence.
