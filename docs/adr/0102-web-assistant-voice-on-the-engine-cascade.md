---
status: accepted
decided: 2026-11-17
decided-by: Kevin
source: "[[decisions#2026-11-17 - The web assistant's voice moves to the engine's cascade; recruiter first (Kevin)]]"
tags: [adr]
---

# 102. The web assistant's voice runs on the engine's cascade, recruiter first

## Standing

**The web assistant has two voice engines, chosen by the server per role.** `live` is Gemini Live with a locked token the engine mints ([[0056-the-household-key-serves-the-web-assistant]], `server/assistant/live.py`). `cascade` is the phone's cloud voice shape on the engine: speech-to-text, a text model with the web assistant's tools, text-to-speech.
- Default in code: **`cascade` for a recruiter's demo visit, `live` for a household member**, until Kevin says otherwise.
- Override: `LEGION_WEB_VOICE` (members) and `LEGION_WEB_VOICE_RECRUITER` (visits), `live` or `cascade`. Neither reaches Cloud Run until its name is added to the deploy pass-through list. Rollback is one variable.
- Each engine's door refuses a caller on the other engine (409), so the choice is enforced, not advised.

## Context

Kevin, 2026-11-17: *"recruiter demo > can it [go] to our current cloud pipeline too"*. The reason is cost. The phone already left Live for a cascade ([[0092-phone-voice-is-a-cascade-live-retired]]). The recruiter page is public, so its spend is the one that grows with strangers.

## Decision

- **One door, `POST /api/assistant/turn`** (`server/assistant/views.py`, `server/assistant/cascade.py`). The browser records one utterance (16 kHz PCM16, the capture worklet Live already used), posts it with its session cookie and CSRF token, and reads the reply as Server-Sent Events: transcript, tool activity, sentences, audio. A typed message uses the same door without the speech steps and gets no audio. The Gemini key never leaves the engine and the browser holds no token.
- **The loop is the engine runtime's** (`agents.runtime.run_loop`), with a gateway over the **web tool door's own code**: `tools_for`, `refusal_for`, `recruiter_only_tool`, `run_engine_tool`, the audit row and the conversation record. Not the agent graph (`agents/legion`): its router refuses recruiters (ticket 13 keeps that), and its confirm gate would add refusals the web never had. `/api/agents/turn` is untouched and stays dark.
- **The recruiter's fence is unchanged**: same tools, same refusals, same isolation, because it is the same code. No mail tool exists on either door.
- **ADR 0090 holds** by the door's own guard: one HTTP request is one turn, and a third-party read flags the request before it runs, so a write after it in the same turn refuses in words.
- **Speech shapes are the phone's working code** (`CloudSpeech.kt`): speech-to-text on the cascade's text model with the utterance inline as WAV (not `gemini-3.5-transcribe`, which the phone left for price), and text-to-speech on `gemini-3.8-flash-tts` in the companion's voice, with the phone's one retry on the older voice shape.
- **Money is spoken by code** where a tool already writes the sentence: `account_balances` carries its net cash sentence as `speak_verbatim` ([[0094-net-cash]]). It is spoken as written, and the model's own money sentences after it in that turn are dropped. Every other figure is still the model's wording of a code-written result, as on Live.
- **History is the conversation record's own engine-written lines** (the last eight exchanges). No new table, and a browser upload can never become the assistant's words.
- **What a recruiter's turn stores** is the existing visit conversation record (`DeviceLog`, no household, `demo_visitor`, 30 days, read only on the engine): the visitor's words as transcribed or typed, the reply's words, each tool call with its arguments and trimmed result, and one timing line of numbers. No audio, ever. Plus the existing audit rows (`AssistantCall`: who, when, which tool, outcome).
- **Cost guard:** a recruiter's turn reserves its worst case from the demo budget first (four text calls, plus speech for a spoken turn, against the voice cap; typed against the full budget) and gives back the rounds it did not use. Turns are throttled per member and, for visits, per IP address. A demo conversation still ends at `LEGION_DEMO_MAX_SESSION_MINUTES`. An utterance is capped at 15 s, a turn at four model rounds and eight tool calls.
- **End of speech is the browser's**: tap to talk, about 1.2 s of quiet ends the utterance, a second tap sends it early, a tap during the reply interrupts it.

## Consequences

- A step toward [[0095-alfreds-brain-runs-on-the-engine]] for the web: the web's turn already runs on the engine with the runtime's loop. Ticket 13 (web onto the engine's turn endpoint) still owns moving members onto the agent graph; recruiters keep this door.
- The Live path stays whole behind the switch until Kevin retires it.
- Not yet measured: latency, cost per turn, a real browser (Safari included), and how the reply sounds. Those are the checks owed before the switch is relied on.
