---
status: accepted
decided: 2026-11-17
decided-by: Kevin
source: "[[decisions#2026-11-17 - The web assistant's voice moves to the engine's cascade; recruiter first (Kevin)]]"
tags: [adr]
---

# 102. The web assistant's voice runs on the engine's cascade, with the phone's behaviour

## Standing

**The web assistant has two voice engines, chosen by the server per role.** `live` is Gemini Live with a locked token the engine mints ([[0056-the-household-key-serves-the-web-assistant]], `server/assistant/live.py`). `cascade` is the phone's cloud voice shape on the engine: speech-to-text, a text model with the web assistant's tools, text-to-speech.
- Default in code: **`cascade` for every role** (Kevin, 2026-11-17: *"same voice behavior across everything"*). Live stays whole behind the switch as the fallback for any role.
- Override: `LEGION_WEB_VOICE` (members) and `LEGION_WEB_VOICE_RECRUITER` (visits), `live` or `cascade`. The deploy scripts pass either one when it is set in `.env`. Rollback is one variable and a deploy.
- Each engine's door refuses a caller on the other engine (409), so the choice is enforced, not advised.

## Context

Kevin, 2026-11-17: *"recruiter demo > can it [go] to our current cloud pipeline too"*. The reason is cost. The same day: *"same voice behavior across everything. midcon demo and legion"*, *"keep listening unless user says stop or taps to close"*, *"context aware spoken greetings on open too"* and *"the exact same legion cloud pipeline, par the wakeword"*. The phone already left Live for a cascade ([[0092-phone-voice-is-a-cascade-live-retired]]). The recruiter page is public, so its spend is the one that grows with strangers.

## Decision

- **One door, `POST /api/assistant/turn`** (`server/assistant/views.py`, `server/assistant/cascade.py`). The browser records one utterance (16 kHz PCM16, the capture worklet Live already used), posts it with its session cookie and CSRF token, and reads the reply as Server-Sent Events: transcript, tool activity, sentences, audio. A typed message uses the same door without the speech steps and gets no audio. The Gemini key never leaves the engine and the browser holds no token.
- **The loop is the engine runtime's** (`agents.runtime.run_loop`), with a gateway over the **web tool door's own code**: `tools_for`, `refusal_for`, `recruiter_only_tool`, `run_engine_tool`, the audit row and the conversation record. Not the agent graph (`agents/legion`): its router refuses recruiters (ticket 13 keeps that), and its confirm gate would add refusals the web never had. `/api/agents/turn` is untouched and stays dark.
- **The recruiter's fence is unchanged**: same tools, same refusals, same isolation, because it is the same code. No mail tool exists on either door. The one addition, for every role, is `end_conversation`, a control the page acts on that reaches no data.
- **ADR 0090 holds** by the door's own guard: one HTTP request is one turn, and a third-party read flags the request before it runs, so a write after it in the same turn refuses in words.
- **Speech shapes are the phone's working code** (`CloudSpeech.kt`): speech-to-text on the cascade's text model with the utterance inline as WAV (not `gemini-3.5-transcribe`, which the phone left for price), and text-to-speech on `gemini-3.8-flash-tts` in the companion's voice, with the phone's one retry on the older voice shape.
- **No claimed change without a change**: a sentence that claims an outcome ("I added ...") when no write succeeded in the turn is not said; the turn says plainly that nothing was changed instead (the runtime's own `claims_unbacked_outcome`, used here to block, not only to flag). Measured on the first real run: after a web search, the model claimed it had added a date to a list. A turn whose tools ran but whose answer came back empty or filler-only is asked once more without tools (the phone's note).
- **A stop phrase closes even if the model only signs off**: an utterance that is nothing but one of `end_conversation`'s phrases ("that will be all", "never mind", "I'm good"...) ends the conversation after the reply, in code; a plain "no" never does. Measured in a real browser: the model said goodbye without calling the tool, and the page kept listening.
- **A read-through turn that makes no change says ADR 0090's reason in code**, never the model's own (it invented "that only works in the phone app"); a false phone-only reason for something the web's tools do is replaced the same way.
- **An interrupt stops the server's turn**: the page aborts the request, and the engine stops its loop and speech and records the turn as cancelled.
- **Rollback is one `.env` line and a deploy**: the deploy scripts pass `LEGION_WEB_VOICE` and `LEGION_WEB_VOICE_RECRUITER` when set.
- **Money is spoken by code** where a tool already writes the sentence: `account_balances` carries its net cash sentence as `speak_verbatim` ([[0094-net-cash]]). It is spoken as written, and the model's own money sentences after it in that turn are dropped. Every other figure is still the model's wording of a code-written result, as on Live.
- **History is the conversation record's own engine-written lines** (the last eight exchanges). No new table, and a browser upload can never become the assistant's words.
- **What a recruiter's turn stores** is the existing visit conversation record (`DeviceLog`, no household, `demo_visitor`, 30 days, read only on the engine): the visitor's words as transcribed or typed, the reply's words, each tool call with its arguments and trimmed result, and one timing line of numbers. No audio, ever. Plus the existing audit rows (`AssistantCall`: who, when, which tool, outcome).
- **Cost guard:** a recruiter's turn reserves its worst case from the demo budget first (four text calls, plus speech for a spoken turn, against the voice cap; typed against the full budget; a greeting one call) and gives back the rounds it did not use. Turns are throttled per member and, for visits, per IP address. A demo conversation still ends at `LEGION_DEMO_MAX_SESSION_MINUTES`. An utterance is capped at 28 s, a turn at four model rounds and eight tool calls.
- **The phone's behaviour, from its code** (the behaviour spec of 2026-11-17, kept privately with the reports): the follow-up window (8 s, extended at most twice by a late sound), the phone's closing tone and "Conversation closed", `end_conversation` with its stop phrases and sign-off, fillers from memory for slow tools, a reply's leading filler dropped, speech two sentences at a time, the phone's failure lines, a loudness gate before transcription, the 28 s utterance cap.
- **Adapted for a browser**: end of speech is an energy threshold with 1.2 s of quiet (no Silero or Smart Turn in a browser; an open ruling). **Half-duplex**: the page does not listen while the assistant thinks or speaks, so it never hears itself; a tap interrupts. A tap while listening closes the conversation.
- **A spoken greeting on open**, started in the opening tap: for a member, the phone's greeting rules over the engine's own facts pack (`greeting.facts`), one text call within 2.5 s, else a scripted line; for a demo visit, which may not read the facts pack, a scripted demo hello.
- **The prompt prefix is an explicit Gemini context cache**, the phone's rules (TTL 1,200 s, 120 s margin, background create, back-off, inline on a broken cache and on the tool-free last round); the clock and who is speaking move into each turn so the prefix stays the same. About 10,000 tokens for a demo visit and 13,000 for a member, by character count.

## Consequences

- A step toward [[0095-alfreds-brain-runs-on-the-engine]] for the web: the web's turn already runs on the engine with the runtime's loop. Ticket 13 (web onto the engine's turn endpoint) still owns moving members onto the agent graph; recruiters keep this door.
- The Live path stays whole behind the switch until Kevin retires it.
- Not yet measured: latency, cost per turn, a real browser (Safari included), and how the reply sounds. Those are the checks owed before the switch is relied on.
- Money figures (2026-11-18, `assistant/cascade.py` `backed_cents`): in a turn where a money tool ran (a result holding a `*_cents` amount), a model sentence whose two-decimal figure that tool did not return is not said. Stated limits, each with an invented example:
  - Whole-dollar figures are not checked: "Housing took 1451" passes when the tool said 1,450.00 (a bare whole number cannot be told from a count, a year or an id).
  - Any other two-decimal number in a money turn is dropped as unbacked: "version 3.12", "74.88%", "2.50 lb", a figure the model added up itself.
  - A failed, timed-out or EMPTY money result (no `*_cents` key, e.g. `{"currencies": []}`) does not make a money turn, so an invented figure then is spoken. Smallest structural fix (not built): count any call whose result holds a `*_cents` key of any value as money; a truly empty list shape still needs the tool to say it is a money tool (a flag on `EngineTool`).
  - A follow-up answered from history with no tool call is not checked ("and groceries?" answered from the last reply).
  - A swapped label or a flipped sign passes: utilities' 99.12 said as groceries, a refund of 99.12 said as a spend (membership only).
  - Any model sentence said before the turn's first money tool has returned is not checked, whether it came in the same round as the money call or in an earlier round; a held claim is checked when it is said at the end. Closing it means holding every sentence that carries a two-decimal figure until the turn's money tools have run, which moves such sentences later in the answer.
  - In a money turn, a figure the person said themselves, or a correct figure from an earlier turn that no tool returned in this turn, is dropped: "you said 84.50" or "up from last month's 1,962.48".
- Lookup lines (2026-11-19, perf/try-faster-first-word): a network lookup's line is said when it starts; any other lookup's line only if no answer is in hand 0.7 s after it started, and never once one is. Stated limits, each with an invented example:
  - The line follows the turn's first successful tool only. If that tool is a silent write ("add milk", then a list read), the later read gets no line.
  - A delayed line that has started plays to its end, so an answer ready just after it starts waits up to the clip's length (the shortest clip is used).
  - A local tool that blocks gets its line only when it returns: nothing can be said during a tool call.
