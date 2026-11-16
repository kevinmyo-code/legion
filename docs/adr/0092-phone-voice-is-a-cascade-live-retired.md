---
status: accepted
decided: 2026-11-15
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-11-15 - Gemini Live retired on the phone; the cascade is the voice (Kevin)]]"
tags: [adr]
---

# 92. The phone's voice is a cascade; Gemini Live is retired

## Standing

ACCEPTED, BUILT and shipped (stage 1, 2026-11-15). **Every voice conversation on the phone runs on a
cascade, not on Gemini Live:** speech-to-text, a text model with tools, text-to-speech
(`service/cascade/`). A tap, the wake word, Brief me and every proactive line open it. Nothing opens
Live and nothing falls back to it. The phone (`app/`) only; the head unit and the web assistant are
not covered.

- **Two engines** in Settings > Assistant > Voice engine (`ui/settings/VoiceEngineRow.kt`):
  - **Cloud**, the default. Gemini hears the turn (`CloudSpeech.STT_MODEL`, the cascade's own text
    model with the audio inline, one request per turn), the text model answers with tools
    (`CascadeSession.MODEL`, `gemini-3.5-flash-lite`), and Gemini TTS speaks it sentence by sentence
    (`CloudSpeech.TTS_MODEL`, `gemini-3.8-flash-tts`) in the companion's voice setting, Puck by
    default. All on the household's own Gemini key.
  - **Laptop pipeline.** The same text model and tools, with speech on Kevin's own laptop's voice
    service over his private network. When the laptop cannot be used, at open or mid-conversation,
    the conversation runs on Cloud.
- **The text model's prefix (system instruction, tools, tool config) sits in an explicit Gemini
  context cache** (`service/cascade/ContextCacheStore.kt`), so a turn that hits it does not send or
  pay full input price for that prefix again. With no usable cache the turn goes inline and a cache
  is created in the background; it never holds a turn up.
- **The tools are Live's declarations** (`LiveToolbox.declarations()`, run through
  `CascadeRules.declarations`), so ADR 0087's tool budget still governs that set.
- **One flag: `VoiceEngineSettings.LIVE_RETIRED`.** While it is true, Settings offers Cloud and
  Laptop only, a stored Live setting reads as Cloud, and a voice that cannot start or fails ends the
  conversation in words. Live's code is still in the tree, unreachable.

## Context

Kevin, 2026-11-15, of the Cloud voice that had been built as the laptop pipeline's fallback: *"lets
change the fallback to the text+gemini voice, so tht we save costs from the sts"*, then *"ship it
once done. replace the sts live pipeline with it"*. Speech-to-speech was too expensive. He asked for the new path to be auditable too: *"make sure we can audit the costs of
each convo too"*.

Until then Live was the phone's voice by ruling (`library/decisions.md`, 2026-10-02 and
2026-10-27), and the 2026-11-10 Voice engine switch added the laptop pipeline beside it with Live
still the default (merge `2b11d511`). That last ruling was never given an ADR or a log entry; this
ADR and the 2026-11-15 log entry replace it.

## Decision

- Cloud replaces Live as the default and the only cloud voice (`7a7e7f74`). Shipped as stage 1,
  phone 263191730, 2026-11-15.
- Stage 3 (merged 2026-11-15, phone 263200608) made it faster: the greeting pack is fetched before
  the tap, the text model drafts an answer at a likely end of turn and keeps it only if the turn
  ends with the same words (a setting, on by default), and a short scripted filler plays while a
  slow tool runs.
- The rollback is the flag, not a deletion: Live's code stays until Kevin rules it can go.

## Consequences

- **Cost is estimated per conversation** (`e697f9c9`). Each billed call logs a `gemini_usage` line
  (kind, model, token counts) and each conversation ends with a `cascade_usage_summary` line.
  `tools/voice_cost.py` prices those lines from `tools/voice_prices.json`. It is an estimate from
  published prices, not a bill, and it reads counts and model ids only, never conversation text.
- **Lost with Live, known at ship:**
  - The camera. "Look at this" refuses in words. By hand: "Didn't start - looking needs the Live
    voice, which is off". By voice, the `look` action still reaches `LookController`, whose sink
    is the Live session, so it answers "Not opened: no voice conversation is running, so nothing
    could see the picture." (traced, not run; `CascadeRules.LIVE_ONLY_TOOLS` filters a top-level
    `look`, which no longer exists since ADR 0087 folded it into an action tool). ADR 0084 is
    unreachable while the flag holds.
  - A navigation turn cue can overlap a reply: only the Live session registers the cue hold
    (`service/AssistantCueHold.kt`).
  - A finished Claude task is not offered into an open conversation: `LiveNotes`' sink reads the
    Live session only.
  - No resume-music nudge: only the Live session nudges a player it ducked to play again.
  - No server end-of-turn fallback. Barge-in by voice is kept, on the phone's own detector (ADR
    0064, still off on car Bluetooth), but Live's server VAD, which took over when the phone's
    Silero model failed, is gone: a phone-side fault now ends the conversation in words.
- **Rollback:** set `LIVE_RETIRED` to false. Live is then the default and selectable again, every
  Live door reopens, and the cascade hands failures back to Live. A stored Cloud choice stays Cloud
  until Kevin picks Live in Settings. Traced in the code only: no build with the flag flipped has
  been compiled or run.
- Other ADRs with text written for Live: 0064 (server VAD as the fallback), 0084 (the camera), 0091
  ("On Live too"). Their rules still read correctly for Live; on the cascade, this ADR says what
  holds.
