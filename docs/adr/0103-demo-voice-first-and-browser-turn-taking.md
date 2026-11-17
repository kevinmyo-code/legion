---
status: accepted
decided: 2026-11-17
decided-by: Kevin
source: "[[decisions#2026-11-17 - The demo goes voice-first; the phone's turn-taking runs in the browser (Kevin)]]"
tags: [adr]
---

# 103. The demo is voice-first, and the browser takes turns the way the phone does

## Standing

- **The browser runs the phone's turn models.** Silero VAD v6.2 and Smart Turn v3.2 (the phone's own files, `server/frontend/public/turn/`, MIT and BSD-2-Clause) on onnxruntime-web (WASM, one thread, in workers), loaded after the first tap. They drive end of turn (the phone's `EndOfTurnPolicy`: 700 ms base, Smart Turn may only extend, 2.5 s cap) and barge-in (the phone's `BargeInGate`: echo floor learned per reply, 8 qualifying windows in 16). If they fail to load, or a Silero window takes over 16 ms, the page keeps the energy threshold (1.2 s) and half-duplex. Smart Turn slower than 450 ms is dropped and silence alone ends the turn. The openWakeWord models are not used or shipped.
- **Barge-in stops playback at once and cancels the engine's turn** (the stream is aborted). A barge-in followed by nothing new (the gate's own verdict, an empty transcript, or a transcript that only repeats the reply) is counted as the assistant's own voice. After two, the conversation goes half-duplex for good, without a word. A tap still interrupts.
- **Speculation:** at 300 ms of silence the page sends what was said to `POST /api/assistant/transcribe` (no answer, no tool, nothing stored, charged to the demo budget like a speech share). If the turn ends without more speech, those words go to `POST /api/assistant/turn` as `text` with `spoken`. If not, the transcript is dropped.
- **Fillers name the tool that started** (`server/assistant/fillers.py`), from memory, never twice running, never two in a turn. Slow tools get their line when they start. Fast tools get theirs only after they return, and only if 700 ms passed with nothing said. Writes are silent. Every tool either role can reach is mapped or listed silent, and a test walks both lists.
- **The demo link opens on the orb.** With a code in the link there is no gate: one orb and "Tap and ask me about Kevin". The tap enters the visit, opens the audio and asks for a five-second opening, scripted, spoken verbatim. Without a code, the code box stays. The page has question chips answered aloud, captions, a typed box, the recording notice in full by the mic, a one-minute tour (`server/assistant/tour.py`: code picks each visual through the tool door; facts about Kevin come only from the loaded content), a persistent ask (resume and contact from the content), and a live strip with tool, time and estimated turn cost (recruiter only).
- **The cascade's voice is per role, next to the engine choice** (`assistant/voice_engine.voice_for`). Members: `en-us-neno`. A recruiter's visit: `Puck`. Both are plain, with no style direction. `LEGION_WEB_TTS_VOICE` and `LEGION_WEB_TTS_VOICE_RECRUITER` override. A voice Google refuses by name is spoken in Puck rather than not at all. Live, the fallback engine, keeps the companion's stored voice. Kept audio (fillers, scripted lines) is keyed by voice and spoken text.
- **Expression tags** (`assistant/speech_tags.py`): `<chuckle>`, `<laugh>`, `<sigh>` and `<short pause>` only, never `<long pause>`.
  - A few code lines carry one (the demo opening, the tour's lighter lines). The model may use one rarely; one prompt line tells it so.
  - Code enforces the rules whatever the model writes: unknown tags are removed; at most one per sentence and two per reply; none on a sentence with a figure, nor on verbatim, refusal, failure or filler lines.
  - Tags exist only on the text sent to speech. Captions, the conversation record, the next turn's history, logs, the funnel, the strip and the figure and honesty checks all see the text without them.
- **Funnel counts** (`demo.DemoFunnelDay`, migration `demo/0009`, additive) are per day, ref and step, counts only, pruned with visitor retention. `manage.py demo_funnel` prints them.

## Context

Kevin, 2026-11-17: since 2026-11-06 there were about 30 page loads and one entry. The one outside visitor heard a 16 s opening and left after two turns. He asked for the voice to be the centre of the page, and for the phone's turn-taking (recruiter-demo ticket 12). In an addendum, for all voices: fillers should say what the assistant is actually doing.

## Consequences

- The page fetches about 11 MB of models and the runtime's WASM after the first tap, never before.
- iPhone Safari is not tested here. Its echo cancellation decides whether barge-in holds there; the self-trigger guard is the fallback.
- Fast tools' fillers can only follow the tool's result, because the engine's stream is held while an engine tool runs.
