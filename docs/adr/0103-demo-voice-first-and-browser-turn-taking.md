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
- **Figures are said like a person and shown exactly** (`assistant/spoken_figures.py`, Kevin 2026-11-17: *"round when spoken like a real person. exact on screen for verification"*). This amends [[0102-web-assistant-voice-on-the-engine-cascade]] and [[0094-net-cash]] on how a figure is spoken.
  - Code rounds the dollar amounts and percentages in the text sent to speech. Under $20: dollars and cents. Up to $1,000: the nearest dollar. Up to $1,000,000: three significant figures. Above: three significant figures with "million" or "billion". "about" is added when the value changed, and the sign is kept.
  - Not rounded: ids, codes, dates, times, quantities, a question confirming a change, or a turn where the person asked for the exact figure. Counts are not rounded, because a bare number cannot be told from an id or a year.
  - The model writes every figure exactly. Captions, the record and every check keep the exact one, so the exact figure is on screen in the same turn. The live gate's heard-back check accepts the code-rounded form and rejects any other.
- **Expression tags** (`assistant/speech_tags.py`), used liberally in casual sentences, at most one per sentence, no cap per reply.
  - Allowed: the human sounds on Google's speech page that passed a heard test in our shape for both voices on 2026-11-17 (`.legion/reports/tag-heard-test-1117.json`). That is 16 tags, among them `<short pause>`, `<chuckle>`, `<laugh>`, `<sigh>`, `<phew>`, `<gasp>`, `<breath>` and `<tsk>`. Never `<long pause>`, and no distressed or non-human sounds.
  - Code lines carry them on purpose: the demo opening, the tour's lighter lines. The model may write them too; one prompt line lists them.
  - Code enforces the rules whatever the model writes: unknown tags are removed. No tag goes on a sentence with a figure, date or time, a refusal or error, or a question confirming a change, nor on verbatim, failure or notice lines.
  - Tags exist only on the text sent to speech. Captions, the conversation record, the next turn's history, logs, the funnel, the strip and the figure and honesty checks see the text without them.
- **How it talks:** one prompt note (`cascade.PERSONA_NOTE`) asks for a friendly, quick, slightly dry colleague: one sentence unless asked, contractions, occasional lead-ins, small talk that never delays the answer, and every figure written exactly.
- **Pace stays 1.0x in the page.** Pitch-kept speed-up of streamed PCM needs a time-stretch (WSOLA) in an AudioWorklet; that is not a small change, so it is left for its own build.
- **Funnel counts** (`demo.DemoFunnelDay`, migration `demo/0009`, additive) are per day, ref and step, counts only, pruned with visitor retention. `manage.py demo_funnel` prints them.

## Context

Kevin, 2026-11-17: since 2026-11-06 there were about 30 page loads and one entry. The one outside visitor heard a 16 s opening and left after two turns. He asked for the voice to be the centre of the page, and for the phone's turn-taking (recruiter-demo ticket 12). In an addendum, for all voices: fillers should say what the assistant is actually doing.

## Consequences

- The page fetches about 11 MB of models and the runtime's WASM after the first tap, never before.
- iPhone Safari is not tested here. Its echo cancellation decides whether barge-in holds there; the self-trigger guard is the fallback.
- Fast tools' fillers can only follow the tool's result, because the engine's stream is held while an engine tool runs.
