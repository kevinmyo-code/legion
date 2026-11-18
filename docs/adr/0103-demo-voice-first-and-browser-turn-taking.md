---
status: accepted
decided: 2026-11-17
decided-by: Kevin
source: "[[decisions#2026-11-17 - The demo goes voice-first; the phone's turn-taking runs in the browser (Kevin)]]"
tags: [adr]
---

# 103. The demo is voice-first, and the browser takes turns the way the phone does

## Standing

- **The browser runs the phone's turn models.** Silero VAD v6.2 and Smart Turn v3.2 (the phone's own files, `server/frontend/public/turn/`, MIT and BSD-2-Clause) on onnxruntime-web (WASM, one thread, in workers), loaded after the first tap. They drive end of turn (the phone's `EndOfTurnPolicy`: 700 ms base, Smart Turn may only extend, 2.5 s cap). The barge-in gate (the phone's `BargeInGate`: echo floor learned per reply, 8 qualifying windows in 16) is built but off (next item). If they fail to load, or a Silero window takes over 16 ms, the page keeps the energy threshold (1.2 s) and half-duplex. Smart Turn slower than 450 ms is dropped and silence alone ends the turn. The openWakeWord models are not used or shipped.
- **Voice barge-in on the web is deferred (2026-11-17): the demo is half-duplex from the start.** Audio heard while a reply plays, and for 300 ms after, is thrown away; the person interrupts by tapping the orb or a dialog's Interrupt button, which stops playback and cancels the engine's turn (`POST /api/assistant/turn/cancel`). Barge-in sits behind one constant in the page, default off.
  - Why: it failed in a real browser after three rounds of fixes (the reply heard back at a quarter level stopped playback and was posted as a turn: 52 stops on the opening, a long answer cut and its echo answered as a question). Headless Chrome has no echo cancellation, and laptop speakers are the demo's normal case, so the gate cannot be trusted there yet.
  - Before it is turned on: a real-device echo test passes (laptop speakers and a phone, the reply playing at normal volume: no stop, no posted turn), and the barge-in turn carries `barge_in=1`.
  - The server side stays: the `barge_in` form field and its echo check (a barge-in turn that repeats the reply that was playing ends as no speech) are unused until then.
- **Early transcripts are OFF** (`LEGION_WEB_EARLY_TRANSCRIPTS`, unset = off: `POST /api/assistant/transcribe` answers 404, makes no paid call and reserves nothing). The second blind review (2026-11-17) found the browser re-sent the whole turn at every pause and could answer from a stale transcript, and the route had no slot, no cap and no cancel. Before it is switched on, all of these must exist: one early transcript in flight per user; a server-side cap per utterance and per minute; the request cancelled when the browser drops it; members' early transcription metered against their cap; and only the audio since the last request sent, never the whole turn again.
- **Fillers name the tool that started** (`server/assistant/fillers.py`), from memory, never twice running, never two in a turn. Slow tools get their line when they start. Fast tools get theirs only after they return, and only if 700 ms passed with nothing said. Writes are silent. Every tool either role can reach is mapped or listed silent, and a test walks both lists.
- **A tool's deadline is enforced where its time is spent, never by a thread** (blind review round 3, design change D1, `server/legion/deadline.py`). The tool runs inline on the turn's thread, in one transaction whose Postgres `statement_timeout` and `lock_timeout` come from the time left, so the database cancels a slow statement or lock wait and the tool's work rolls back; every outbound request a tool can reach passes `timeout=deadline.cap(...)` (a test walks the tool registry). A write either committed inside the deadline or did not happen, so there is no "may still go through". Not covered: Python that loops without a statement or a request.
- **The demo link opens on the orb.** With a code in the link there is no gate: one orb and "Tap and ask me about Kevin". The tap enters the visit, opens the audio and asks for a five-second opening, scripted, spoken verbatim. Without a code, the code box stays. The page has question chips answered aloud, captions, a typed box, the recording notice in full by the mic, a one-minute tour (`server/assistant/tour.py`: code picks each visual through the tool door; facts about Kevin come only from the loaded content), a persistent ask (resume and contact from the content), and a live strip with tool, time and estimated turn cost (recruiter only).
- **The cascade's voice is per role, next to the engine choice** (`assistant/voice_engine.voice_for`). Members (Alfred on LEGION): `en-gb-advisor-3` (Kevin, 2026-11-17: *"this is my choice Authoritative Advisor 3"*). A recruiter's visit and its tour: `Puck` (*"can we go back to puck?"*). Both plain, with no style direction. `LEGION_WEB_TTS_VOICE` and `LEGION_WEB_TTS_VOICE_RECRUITER` override, passed through by the deploy only when set. A voice Google refuses by name (its own words say so; any other 400 fails only that sentence) is spoken in Puck rather than not at all, for five minutes, then tried again; audio spoken in Puck is never kept under the member's voice. Live, the fallback engine, keeps the companion's stored voice. Kept audio (fillers, scripted lines) is keyed by voice and spoken text, so no clip of one voice is played to the other role.
- **Figures are said like a person and shown exactly** (`assistant/spoken_figures.py`, Kevin 2026-11-17: *"round when spoken like a real person. exact on screen for verification"*). This amends [[0102-web-assistant-voice-on-the-engine-cascade]] and [[0094-net-cash]] on how a figure is spoken.
  - Code rounds the dollar amounts and percentages in the text sent to speech. Under $20: dollars and cents. Up to $1,000: the nearest dollar. Up to $1,000,000: three significant figures. Above: three significant figures with "million" or "billion". "about" is added when the value changed, and the sign is kept.
  - Not rounded: ids, codes, dates, times, quantities, a question confirming a change, or a turn where the person asked for the exact figure. Counts are not rounded, because a bare number cannot be told from an id or a year.
  - The model writes every figure exactly. Captions, the record and every check keep the exact one, so the exact figure is on screen in the same turn. The live gate's heard-back check accepts the code-rounded form and rejects any other.
- **Expression tags** (`assistant/speech_tags.py`), used liberally in casual sentences, at most one per sentence, no cap per reply.
  - Allowed: the human sounds on Google's speech page that passed a heard test in our shape for both voices on 2026-11-17 (`.legion/reports/tag-heard-test-1117.json`). That is 16 tags, among them `<short pause>`, `<chuckle>`, `<laugh>`, `<sigh>`, `<phew>`, `<gasp>`, `<breath>` and `<tsk>`. Never `<long pause>`, and no distressed or non-human sounds.
  - Code lines carry them on purpose: the tour's lighter lines. The demo opening is spoken plain (it holds the recording notice; round 4, 2026-11-17). The model may write them too; one prompt line lists them.
  - Code enforces the rules per sentence, whatever the model writes: unknown tags are removed. No tag goes on a sentence with a figure, date or time, a refusal or error, or a question confirming a change, nor on verbatim, failure or notice lines.
  - Tags exist only on the text sent to speech. Captions, the conversation record, the next turn's history, logs, the funnel, the strip and the figure and honesty checks see the text without them.
- **How it talks:** one prompt note (`cascade.PERSONA_NOTE`) asks for a friendly, quick, slightly dry colleague: one sentence unless asked, contractions, occasional lead-ins, small talk that never delays the answer, and every figure written exactly.
- **Sounding human** (voice-persona section 8, Kevin: *"yes to all 6"*):
  - The cached prompt carries a few spoken-style example exchanges with invented content (`cascade.SPOKEN_EXAMPLES`).
  - A turn that clearly needs a look gets a short acknowledgement from memory at once (`fillers.ACK`, never twice running); the tool line follows when a tool starts.
  - A long first sentence has its first clause spoken on its own. The rest of each round goes to speech as one request (`SPEECH_GROUPING = "breath"`), kept only if its first audio is no more than about 150 ms behind per-sentence speech.
  - After a barge-in, the next turn's history marks the cut-off reply (`web_conversation.CUT_OFF_NOTE`), so the answer picks up what was said instead of finishing the old one.
  - Audio straight to the text model is measured in the real-model harness only. It is not shipped.
- **The `robotic` marker:** saying "that sounded robotic", or tapping "Sounded robotic" on the demo strip (`POST /api/assistant/robotic`), puts the turn number on the caller's own conversation record and in the log. No words are kept.
- **Pace stays 1.0x in the page.** Pitch-kept speed-up of streamed PCM needs a time-stretch (WSOLA) in an AudioWorklet; that is not a small change, so it is left for its own build.
- **Funnel counts** (`demo.DemoFunnelDay`, migration `demo/0009`, additive) are per day, ref and step, counts only, pruned with visitor retention. `manage.py demo_funnel` prints them.

## Context

Kevin, 2026-11-17: few visitors who opened the demo went on to talk to it, and the opening was too long. He asked for the voice to be the centre of the page, and for the phone's turn-taking (recruiter-demo ticket 12). In an addendum, for all voices: fillers should say what the assistant is actually doing.

## Consequences

- The page fetches about 11 MB of models and the runtime's WASM after the first tap, never before.
- iPhone Safari is not tested here. Its echo cancellation decides whether barge-in can be turned on there.
- Fast tools' fillers can only follow the tool's result, because the engine's stream is held while an engine tool runs.

## Follow-ups (blind review round 5, 2026-11-17; not in the shipped build)

- A prepaid demo web search that never reaches the provider (an empty query, a read-through mark that fails, or the deadline passing before the call) stays charged; give it back.
- The Puck fallback can add one speech call per request inside the same reservation; reserve it.
- Retune the echo check before voice barge-in is ever switched on (it was written for barge-in turns only and has not run against real barge-ins).
- On iOS, playback suspended by the system could leave the orb on "speaking" until a tap.
