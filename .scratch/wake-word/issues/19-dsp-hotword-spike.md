---
map: wake-word
ticket: 19
title: "Can the A25's always-on audio chip listen for our phrase?"
type: task
status: resolved
status-detail: "Ran 2026-10-03 on the A25: SoundTrigger HAL present and enabled, zero enrolled keyphrase or generic models, and no keyphrase enrollment app. The DSP path is closed to LEGION; ticket 18 runs on the CPU."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Can the A25's always-on audio chip listen for our phrase?

Siri's first stage runs on an always-on co-processor, not the main CPU. Android's equivalent is the
SoundTrigger path behind `VoiceInteractionService` + `AlwaysOnHotwordDetector` /
`HotwordDetectionService` ([AOSP sample](https://android.googlesource.com/platform/development/+/main/samples/VoiceInteractionService/)).
LEGION holds the ASSISTANT role, so it may ask. **Reasoned, not verified:** the DSP only runs
keyphrases the OEM shipped a sound model for (on a Samsung likely "Hi Bixby" / "Hey Google"), so a
custom "hey <name>" probably cannot run there.

## Do

On the A25: list the keyphrases its SoundTrigger module supports (`dumpsys soundtrigger`, and
`AlwaysOnHotwordDetector` availability for an enrolled keyphrase), and report whether a custom phrase
can be enrolled. If none can, record that and close; ticket 18's CPU pipeline is the path.

## Result (on-device, A25, 2026-10-03)

- `dumpsys soundtrigger`: the SoundTrigger device is present and ENABLED, but "Enrolled
  GenericSoundModels" and "Enrolled KeyphraseSoundModels" are both empty.
- `dumpsys voiceinteraction`: "(No active implementation)": LEGION holds the ASSISTANT role but does
  not implement a `VoiceInteractionService`, and no keyphrase model is enrolled for anyone.
- `pm query-activities -a com.android.intent.action.MANAGE_VOICE_KEYPHRASES`: **No activities found.**
  `AlwaysOnHotwordDetector` needs that OEM enrollment app to enrol any keyphrase, so it cannot be used
  on this phone. Loading our own generic sound model needs `MANAGE_SOUND_TRIGGER`, which is
  signature|privileged (reasoned from the platform permission, not tried).

**Conclusion:** no DSP stage for a custom phrase on the A25. Siri's shape is kept, on the CPU:
[[18-two-stage-detector]].
