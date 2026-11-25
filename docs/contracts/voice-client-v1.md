---
title: Voice client contract v1
tags: [docs, contract, voice]
---

# Voice client contract v1

What every voice client does around the engine's turn: the shared Android module (`voice/`, used
by the phone and the head unit) and the web client (TypeScript). Three clients built to this page
behave the same. The wire format is [voice-wire-v1.md](voice-wire-v1.md). The decision is
[[0104-one-voice-speech-on-the-engine-shared-voice-module]].

**Status: nothing here is built as a contract yet.** The rules are lifted from code, and each
cites it. The phone's rules shipped to `dev` on 2026-11-20 with the phone speed update. Citations:
- **P** = the phone, on `dev` at `679f7c15`, paths under `app/src/main/java/com/kevin/legion/`.
- **W** = the web, on `dev` at `7ef07024`, paths under `server/frontend/src/assistant/`.
- **E** = the engine, on `dev` at `7ef07024`, paths under `server/`. The speech stage has since moved to `server/agents/voice/` (wire section 0).

Where P and W differ, the table says what each does and what v1 adopts and why. Kevin's rulings on
the questions this raised are in section 14; what is not yet ruled is listed in section 15.

Shared test vectors: `server/contracts/voice/v1/` (`backchannel.json`, `eot.json`, `owed.json`).
Every client's unit tests will load them; none does yet (the engine's shape test is the only reader).

## 1. Session open

| Trigger | v1 rule | Source |
|---|---|---|
| Tap (phone) or push-to-talk (head unit) | Open the mic at once, before the turn models and the engine. Play the earcon when capture starts. **No greeting.** | P `service/cascade/CascadeSession.kt:445`, `:3071-3079`, `service/cascade/OpeningRule.kt:23-27` |
| Wake word (phone) | A spoken greeting: a `greeting` turn ([wire section 6](voice-wire-v1.md#6-turn-kinds)) | P `service/cascade/OpeningRule.kt:23-27` |
| An opener (a check-in, Brief me, the demo's opening) | The opener is the opening; it wins over the wake word. An `opener`, `check_in` or `proactive` turn | P `service/cascade/OpeningRule.kt:24` |
| First tap on the web | Today: a `greeting` turn in the same tap, no earcon (W `cascade-client.ts:425-488`). **v1 adopts the phone's rule** for members (tap = earcon, no greeting); the demo's tap is an `opener` (its scripted opening), so the demo keeps its spoken opening | the phone's rule is Kevin's; on the web it is a plan default, not ruled (section 15) |

- **Order on open:** (1) mic capture starts, (2) earcon plays, (3) warm call `HEAD /api/agents/warm` (wire section 9), (4) on a device, limb attach `POST /api/agents/limb` (wire section 11), (5) turn models load, (6) listening. Audio captured before the models are ready goes to the backlog.
- **Earcon:** two tones, 660 Hz for 0.09 s, 0.03 s of silence, 990 Hz for 0.11 s, 15 ms raised-cosine edges, gain 0.25, 24 kHz (P `service/ListenCue.kt:159`, `:195`). Bundled with the client, never fetched.
- **Earcon in half-duplex:** it counts as playback, so the mic tail (section 3) follows it (P `service/cascade/CascadeSession.kt:3101-3120`).
- **Window after a tap:** wait up to 3,000 ms for the earcon (`CHIME_WAIT_MS`, P `service/cascade/CascadeSession.kt:3226`); if the person has not spoken, arm the follow-up window (section 7).
- **Backlog:** at most 10 s of audio (`DEFAULT_CAP_BYTES = 10_000 * 32`, P `service/cascade/MicBacklog.kt:55`); past the cap the oldest audio is dropped. Each chunk keeps whether playback was audible when it was captured. When the turn detector is ready the backlog is fed to it in order and then cleared. It is also cleared on shutdown and on a failed open. The web has no backlog: its mic starts on the tap and its models load after (W `cascade-client.ts:439-458`). v1 adopts the 10 s backlog for all three: on the web it holds audio captured while the models load.
- **Refusals before opening (phone):** on a call, no mic permission, offline: each says why in words (P `service/LiveSessionController.kt:474-488`). v1 drops the "no key" refusal: the key lives on the engine.

## 2. End of speech

Two models on the device: Silero VAD v6.2 (32 ms windows) and Smart Turn v3.2. The web's policy is
a port of the phone's (W `turn/eot-policy.ts`, P `service/turn/EndOfTurnPolicy.kt`). Vectors:
`eot.json`.

| Constant | Value | P | W |
|---|---|---|---|
| Window | 32 ms | `service/turn/EndOfTurnPolicy.kt:269` | `turn/eot-policy.ts:21` |
| Speech start probability | 0.5 | `:270` | `:22` |
| Speech end probability | 0.35 | `:271` | `:23` |
| Start confirmed after | 200 ms (the 7th window) | `:272` | `:24` |
| Smart Turn check at silence | 192 ms | `:273` | `:25` |
| Base end | 700 ms (fires at 704) | `:274` | `:26` |
| End on a Smart Turn "done" | 500 ms | `:277` | `:33` |
| Cap | 2,500 ms | `:278` | `:34` |
| Smart Turn "done" threshold | 0.5 | `:279` | `:35` |
| Early signal (web only) | 300 ms of silence, once per silence | none | `:37` |

Rules (both clients, P `service/turn/EndOfTurnPolicy.kt:8-30`):
- **Start:** a window at 0.5 or more starts a candidate; it holds while windows stay at 0.35 or more and confirms at 200 ms. A window under 0.35 drops it.
- **Silence:** a window under 0.35 while speaking starts silence. Speech resumes only at 0.5 or more (a 0.4 window does not resume).
- **The reason reports the whole turn:** `extended` whenever a hold carried any silence of the turn past the base, even if the person then spoke again and the final silence ended at the base (P `service/turn/EndOfTurnPolicy.kt:23-25`; W `eot-policy.test.ts:137`).
- **One Smart Turn check** at 192 ms of silence.
  - A "not done" (under 0.5) that arrives before the base holds the turn. Checks then run back to back, never overlapping. A held turn ends at the first "done" (`extended`) or at the cap (`cap`).
  - A "done" that arrives before the base ends the turn at 500 ms of silence (`finished`), or at once if 500 ms has passed.
  - A first result after the base changes nothing.
- **"done" is the Smart Turn verdict, not a word.** No client shortens the wait on spoken words.
- **The 500 ms rule applies only when Smart Turn runs on the device** (P `service/cascade/CascadeRules.kt:159-185`). With no Smart Turn the base is 700 ms.
- **End reasons** (wire values): `base`, `finished`, `extended`, `cap`, `fallback`.
- **Pre-roll:** the uploaded turn starts 544 ms before the confirmed onset: `(SPAN_WINDOWS + 1) * 32` (P `service/turn/TurnTaker.kt:407`, `:424`; W `turn/detector.ts:28`, 17 windows). Same on both.
- **Smart Turn's input** starts 200 ms before onset (P `service/turn/TurnTaker.kt:428`) and is at most the newest `WhisperLogMel.N_SAMPLES` (8 s by the code comment at P `service/turn/TurnTaker.kt:384-394`).
- **Utterance cap:** 28 s (P `service/cascade/CascadeTimings.kt:29`; W `endpointer.ts:28`, `turn/detector.ts:35`). At 28 s the turn ends and is sent. The engine refuses more than 28 s (E `assistant/views.py:781-782`).
- **Model speed limits, per client class, from that class's own measured numbers.** The web: a Silero window over 16 ms or a Smart Turn check over 450 ms, measured once at load, means that model is not used (W `turn/detector.ts:30`, `:33`). The phone: no limit; its Smart Turn takes 393 to 819 ms at p95 on the A25 (P `service/turn/EndOfTurnPolicy.kt:8-9`), which the web's 450 ms would switch off, and a late verdict is simply ignored once the turn has ended. The head unit: to be measured on the unit before HU-2 sets its limit.
- **Client noise gate:** nothing is uploaded unless Silero confirmed a start (above). The transcript-side noise rules (a single word with under 60 ms voiced, P `service/cascade/SttGuard.kt:118`) move to the engine with speech-to-text; the engine measures voicing from the audio.

**When the models fail** (load, or a fault mid-conversation):

| P | W | v1 |
|---|---|---|
| Silero not ready in 1,500 ms or Silero throws: the conversation ends in words, "Voice failed - conversation ended, tap to try again" (P `service/cascade/CascadeTimings.kt:26`, `service/cascade/CascadeSession.kt:3185`) | Energy endpointer: 1,200 ms of silence under RMS 330, half-duplex (W `endpointer.ts:20`, `:33`); its 1,200 ms is itself marked an open ruling (`endpointer.ts:16`) | **Kevin, 2026-11-19 (Q1):** every client falls back to the 1.2 s silence rule (RMS 330, half-duplex, W `endpointer.ts:20`, `:33`); the conversation does not end. Smart Turn failing alone: silence only, reason `fallback` |

## 3. Barge-in and half-duplex

**Full duplex** (the person can interrupt by voice) is allowed only when all hold:
- the barge-in setting is on;
- the route is not Bluetooth and no call is active (P `service/cascade/OpeningRule.kt:34-35`); a Bluetooth device connecting mid-conversation turns it off (P `service/cascade/CascadeSession.kt:2508-2517`);
- the turn models loaded;
- the gate has not disabled itself (two self-interrupts);
- on the web, the real-device echo test of ADR 0103 has passed.

Otherwise the conversation is **half-duplex**: the mic is deaf while anything plays, and for a tail after.

| | P | W | v1 |
|---|---|---|---|
| Default | on (P `service/turn/TurnConfig.kt:103`) | off, `VOICE_BARGE_IN = false` (W `deps.ts:297`) | Both defaults stay; the web turns on only after its echo test |
| Tail after playback | 60 ms on the phone speaker or a wired headset, 700 ms on Bluetooth (`MicReopen`, P `service/turn/TurnConfig.kt:160-167`) | 300 ms (`ECHO_TAIL_MS`, W `cascade-client.ts:170`) | Per route: 60 ms speaker or wired, 700 ms Bluetooth, 300 ms in a browser (it cannot see the route). The tail follows every reply, backchannel, filler and earcon |

**The gate** (P `service/turn/BargeInGate.kt:266-290`, W `turn/barge-in-gate.ts:42-66`, same values):
- 32 ms windows. A window qualifies at Silero 0.8 or more and a peak level at or over the threshold.
- Threshold = `max(3000, echo floor * 1.5)`. The echo floor is the previous reply's, or 12,000, until 10 windows are learned; then the 90th percentile of the learned levels.
- Fires at 8 qualifying windows within the last 16 (256 ms in 512 ms). One fire per reply.
- A window is not learned while the person may still be speaking (1,000 ms guard) when its Silero is 0.5 or more.
- **Self-interrupt verdict:** under 160 ms of speech after the fire and a fire level under twice the threshold. Two self-interrupts turn barge-in off for the conversation. The web counts speech only after a 320 ms grace (`VERDICT_GRACE_MS`, W `turn/barge-in-gate.ts:64`); the phone has no grace. **Per client, to be unified after a car measurement** (Kevin, 2026-11-19, Q2).
- On a fire, playback pauses until the verdict. A person: playback stops, the turn is cancelled (wire section 7) and the held audio is sent with `barge_in=1`. Self: playback resumes.

**Half-duplex and the backchannel (blind review M1, 2026-11-19).** In half-duplex no backchannel
plays (skip `half_duplex`). A clip about a second after the last word would deafen the mic while
the person may still be talking. The only exception is behind the setting `backchannel_half_duplex`
(default off, P `service/turn/TurnConfig.kt:63`, `:93-100`): the client keeps capturing during the clip and
hands the audio on (P `service/cascade/ClipListen.kt`: a turn start within 1,500 ms after the clip and its settle end continues his sentence, `:73-74`, and the new turn starts with the first half and the audio kept during the clip; at most 5 s is kept, `:78`; words said wholly inside the clip, with nothing after, are not detected). **A client may turn that setting on only once its
captured audio is replayed into the detector.**

**Speech while a turn is still answering** (P `service/cascade/CascadeRules.kt:144-156`):
- the turn has called no tool, or no write: the new speech is merged into the turn;
- a write was called and barge-in is on: the turn is interrupted;
- otherwise the new speech waits (the write is left to finish).

## 4. Backchannel and fillers

Two kinds of short line fill the wait:
- the **backchannel**, a content-free clip the client plays ("Mm.");
- the **task line**, chosen by the engine when a tool runs ("Checking the car.").

Vectors: `backchannel.json`.

**Backchannel constants** (P `service/cascade/Backchannel.kt`):

| Constant | Value | Line |
|---|---|---|
| Delay after end of speech | 600 ms (about 1.3 s after the last word, the end-of-speech wait included) | `:47` |
| Minimum speech | 1,200 ms | `:50` |
| Tic gap | 20,000 ms | `:53` |
| Spacing before a task line | 400 ms after the backchannel ends | `:56` |
| Rotation | none of the last 5 words, seeded with the last word of the previous conversation | `:59` |
| Takes | 2 renders kept per word, played in turn | `:62` |
| Clip length | 120 to 700 ms, measured after trimming; outside that it is not played | `:65-66` |
| Trim | the clip is cut to its voice with 40 ms kept each side and 5 ms fades | `:76-79` |
| Clip must be in memory within | 300 ms | P `service/cascade/CascadeSession.kt:3219` |
| Lines | "Mm.", "Right.", "Yeah.", "Hmm.", "Okay.", "Mhm.", "Uh-huh.", "Alright." | `:69` |

At most one backchannel a turn. The web has no backchannel today; v1 adds it to the web.

**Skip reasons, checked in this order** (P `service/cascade/Backchannel.kt:112-123`; the first that holds wins):

| Order | Token | When |
|---|---|---|
| 1 | `not_his` | not a turn of the person's (a greeting, an opener, a proactive line) |
| 2 | `quiet` | Quiet mode |
| 3 | `half_duplex` | half-duplex and `backchannel_half_duplex` off |
| 4 | `over_reply` | he started over an audible reply or a turn still running |
| 5 | `answer_to_ask` | the turn before asked him something (`done.asked`, wire section 5) |
| 6 | `short` | speech under 1,200 ms |
| 7 | `tic` | the turn before played one, and this turn started under 20,000 ms after it |

After `decide` passes, at the timer:

| Token | When | Source |
|---|---|---|
| `answer_first` | the answer's audio started, something else is audible, or the turn changed | P `service/cascade/CascadeSession.kt:1218`, `service/cascade/Backchannel.kt:187-188` |
| `answer_text` | the answer's text has arrived | P `service/cascade/CascadeSession.kt:1222` |
| `not_in_memory` | no clip ready in 300 ms | P `service/cascade/CascadeSession.kt:1229` |
| `clip_ms_<n>` | the clip measured `n` ms, outside 120 to 700 | P `service/cascade/CascadeSession.kt:1236` |

**Which clip:** a word not among the last 5 said; if all are, any but the last; then one of that word's takes in memory; none if none is in memory (P `service/cascade/Backchannel.kt:129`).

**Task line (filler) timing.** Chosen by the engine, played by the client:
- A slow tool's line is sent when the tool starts. Any other tool's line is sent only if it is still running after 700 ms (P `service/cascade/ToolFillers.kt:24`; E `assistant/cascade.py:1354`, `FILLER_WAIT_S = 0.7`).
- A write is never slow. Writes are silent on the engine (E `assistant/fillers.py:87`).
- Never two in a turn, never once reply text exists.
- The client plays a task line no sooner than 400 ms after a backchannel ends.

**The engine's acknowledgement** ("Yeah, one sec.", E `assistant/fillers.py:133`, sent as a `filler` with `tool: ""` on the first utterance when the words need a look, E `assistant/cascade.py:1736-1745`) and the client's backchannel could both fall in one turn. **Kevin, 2026-11-19 (Q3):** a client that plays backchannels sends `backchannel: true` on the request, and the engine then sends no acknowledgement. The slow-tool task line stays, no sooner than 400 ms after the backchannel ends (P `service/cascade/Backchannel.kt:56`).

## 5. Playback order and owed lines

Within a turn the client plays, in this order:
1. the `owed` line, if the stream has one (it always comes first);
2. a backchannel or a task line (never both back to back: 400 ms apart);
3. the answer's lines in arrival order, the engine's `write` line among them where the engine placed it.

- A task line whose text arrives after the answer's text is dropped unheard.
- The web schedules chunks back to back with 60 ms lead (W `web-audio.ts:31-33`); the phone serializes the backchannel and the answer through one gate (P `service/cascade/CascadeSession.kt:1239`, `:2091`).

**Owed lines.** The engine holds the ledger (wire section 5a). The client's whole part:
- track which `line_id`s played to the end, and never play a line id twice (a replayed stream repeats ids);
- on every request in the conversation, send `played` (line ids finished since the last report) and `cut_off`;
- on a close with no further turn, post the close call with the same two fields.

What becomes owed (P `service/cascade/OwedWrites.kt:10-32`):
- a write that went through and changed something (`ok && changed && !silent`) whose line did not play to the end;
- after a cut-off turn, also a write whose result never came back, a relayed call that timed out included ("I couldn't confirm that stamps went through."); without a cut-off, that line is said in the turn's own write line and is not owed again, and if it is never heard, any close writes it to the record as not confirmed (wire section 5a);
- never a failed or refused write;
- two identical writes are two debts; a fact once heard is never owed again.

The owed line is the same text as the write line: "Done: <clause>; <clause>." up to 80 characters, the rest counted as "and N more"; "That change went through." for a write with no phrase (P `service/cascade/CascadeRules.kt:448-481`, `:544-553`). Vectors: `owed.json`.

## 6. Missed and noise turns

The engine decides from the audio: a missed line ("Didn't catch that. Say it again?"), silence, or noise.
The client counts the outcomes in a row:

| | P | W | v1 |
|---|---|---|---|
| Not caught | 2 in a row are said; the third closes (`MAX_MISSED_IN_A_ROW = 2`, P `service/cascade/CascadeSession.kt:3193`) | 2 `no_speech` in a row close (W `cascade-client.ts:1125`) | The phone's: two missed lines, the third closes. Why: the phone's count was set on real drive logs, where people are misheard more than at a desk. `done.ignored` `no_speech` and `unsure` count as missed; `not_addressed` counts as neither (wire section 5) |
| Noise | 5 in a row close (`MAX_NOISE_IN_A_ROW = 5`, P `service/cascade/CascadeSession.kt:3194`) | none | 5 in a row close (`done.ignored: noise`). Why: with no cap a noisy room keeps the mic open with no end |

A heard transcript resets both counts.

## 7. The follow-up window

After a reply drains, the client listens for a follow-up without a new tap.

| | P (`service/FollowUpWindow.kt`) | W (`endpointer.ts`) | v1 |
|---|---|---|---|
| Window | 8,000 ms (`:20`) | 8,000 ms (`:22`) | 8,000 ms |
| After a question | 12,000 ms when the last line ends in `?` (`:27`) | 8,000 ms | 12,000 ms, from `done.asked`. Why: an answer to a question takes longer to start than a new request |
| Extension | local speech within the last 1,500 ms re-arms, at most 2 times (`:46-47`) | the same (`:24-26`) | the same |
| While a tool runs | the window stands down, at most 20,000 ms (`:73`) | none | the phone's |

- **Opens:** when the reply drains (and after a tap with no speech, section 1).
- **Cancelled by:** the person's speech, a tool call, a reply becoming audible.
- **On lapse:** the close call (owed and unconfirmed writes written down), the closing tone, the words "Conversation closed".
- **The closing tone:** 150 ms at 880 Hz, gain 0.5 (W `cascade-words.ts:31-45`). **One tone for every client** (Kevin, 2026-11-19, Q4); v1 uses this one (section 15).
- `done.end` closes after playback (W `cascade-client.ts:1089-1092`).

## 8. Limb tool calls

The engine asks a limb to run a tool with `tool_request` and ends the stream (wire section 8).
The client:
1. **Runs a write at most once.** It keys a write on its `fact_id` (a read on its `call_id`) and keeps the result for the conversation. A repeated request gets the stored result posted again; nothing runs again. Several requests in one round run one at a time, in the order received.
2. **Runs it** with its own code, its own confirm gate included (`confirm: "limb"`), and its `needs` checked: `screen`, `unlock`, `parked`. A need it cannot meet is outcome `refused` with words.
3. **Posts each result as soon as it has one**, aiming for receipt plus `deadline_ms` (the engine's clock decides, wire section 8). It does not post `timed_out` for a call that is still running: the engine times the call out on its own clock, and a result posted after that is recorded as late (wire section 8). The client posts `timed_out` when it gave up on the call itself, and `failed` only for a write it knows did not happen. If a write it reported `timed_out` lands on the device later, it posts `ok` with the real result for the same call id. That `ok` replaces the recorded `timed_out` result on the same fact (same `fact_id`, no new fact), moving it from unconfirmed to done; it is the one second result the engine accepts for a call (wire section 8). If the "couldn't confirm" line was already heard or written down, the done line is then owed or written down.
4. **Posts the result even if the turn was cancelled meanwhile**, so the engine's ledger knows about the write.
5. **Refuses a write in a tainted turn** (`tainted: true` on the `tool_request`) even if the engine asked ([[0090-no-write-after-third-party-text]], [[0098-limbs-device-contract-and-relay]]).
6. **Says in words** what did and did not happen (`words`, always present).
7. **Recovers a dropped stream** by posting the same request again (the latest stored result for the call, or the same turn with its `turn_id`), with its latest `played` report; the engine replays and continues it, and the client skips line ids it already played.
8. **On a 202 `waiting_for`**, it at once re-posts the stored result of every listed call id. A listed call it holds the request for but has not finished, it finishes; if that call is a write still unfinished at its deadline, it posts `timed_out` (it may still land), and `failed` only for a write the device knows did not happen. A listed call it never received, it gets by re-posting the turn with its `turn_id`, which replays the stream that carried it.
9. **Never waits without an end.** If the continuation stream has not started by the round's latest deadline plus 10 s on the client's clock, the client first re-posts the turn with its `turn_id` once, which returns the rest of the turn (wire section 8). Only if that fails too does it play the cached `unreachable` clip, show "Not answered: the engine stopped answering. A change may or may not have gone through.", post the close call if it can, and end the conversation. Any close records every unconfirmed write (wire section 5a), and the idle close (wire section 8a) covers a close that never arrives.

While the call runs, the follow-up window stands down and speech follows section 3's merge rule.
The task line for a relayed tool is the engine's, sent before the `tool_request`.

## 9. Engine unreachable

The engine cannot be reached when a post fails, no first event arrives in 20,000 ms, or the turn
takes over 75,000 ms (W `turn-deadline.ts:12-13`).

| | P | W | v1 |
|---|---|---|---|
| Today | "Cloud voice failed - conversation ended, tap to try again" (P `service/cascade/CascadeSession.kt:3180`) | caption "Not answered: couldn't reach the engine. Nothing was changed." and listen again (W `cascade-words.ts:8`, `cascade-client.ts:1039-1046`) | Play the cached `unreachable` clip, show the same words, answer nothing else, close the conversation. Why: listening again with no engine only repeats the clip on every utterance; the next tap tries again |

- The clip is fetched with the clip set (wire section 10) and kept on the device, so it plays offline.
- With no clip cached, the client shows the words and plays the earcon.
- Refusals keep their meaning (wire section 4): `busy` waits `retry_ms` and retries for up to 6,000 ms (W `turn-post.ts:8-10`); `duplicate` (assistant door only) listens again silently, while on the agents door a re-post replays the stream (wire section 8); `no_conversation` re-posts once with no conversation id, except for a re-post of a `turn_id` already sent: that one is never re-sent as a new turn (the conversation was idle-closed and its writes are on the record), and the client ends the conversation.

## 10. What works with no network

With speech-to-text on the engine, **no spoken request works offline.** The limb-local set:
- open and close by tap or push-to-talk, the earcon, the closing tone;
- the wake word's detection (it answers with the `unreachable` clip);
- stopping playback by tap;
- the `unreachable` clip and words.

**The crisis pre-check runs on the engine** (Kevin, 2026-11-19), right after the transcript and before the model; a client runs none of its own once its engine voice client ships. **Known gap:** with speech-to-text on the engine, an unreachable engine means the device has no words, so no client can detect a crisis in v1; the client plays the generic `unreachable` clip. The gap closes only with an on-device recognizer (offline spoken commands, a v1 non-goal). Flagged for Kevin. The `unreachable` clip stays plain, with no crisis resource on it (Kevin, 2026-11-20: *"4 ok"*); the gap stays listed until an on-device recognizer exists.

**A crisis hit** (orchestrator default, not ruled; section 15): the engine stops the turn and sends one fixed code-spoken line carrying the help number, the same on every client and never model text, plus a `crisis` event (wire section 5) so a client with a screen shows its card; a client that shows the card does not also caption that line. The words are the existing crisis card's, the head unit's `CrisisCard.kt:61-70` (in `headunit/app/src/main/java/com/kevin/midnightai/ui/`); no new crisis copy is written. As built on the agents door (E at `679f7c15`, `agents/voice/pipeline.py:186-189`, the line in `agents/voice/crisis.py:63`): the line is spoken as a plain `sentence`, then the `crisis` event, then `done` with `crisis` and `end`.

Local spoken commands (timers, volume, pause): the phone's matcher exists, is off by default and is
wired only on the retired Live path (P `service/turn/TurnConfig.kt:119`, `service/LiveSessionController.kt:1220`).
**Not in v1** (Kevin, 2026-11-19, Q5: later): offline spoken commands are a named non-goal.

## 11. Timing keys

Every client logs these keys per conversation and per turn, under its own log tag, with the
same names and units (ms unless named). The engine adds its own stage times in `done.timing`.

| Key | Meaning | Exists |
|---|---|---|
| `open_kind` | `chime` (tap or push-to-talk), `greeting` (wake word), `opener` | P `service/cascade/CascadeSession.kt:419`, values from `service/cascade/OpeningRule.kt:21` |
| `open_mic_ms` | open to capture started | P `:444` |
| `open_backlog_ms`, `dropped_ms` | backlog fed to the detector, and dropped past the cap | P `:521` |
| `open_models_ms` | open to turn models ready | P `:452` |
| `open_first_audio_ms` | open to the first sound of a reply | P `:2151` |
| `pre_roll_ms` | pre-roll included in the turn | P `service/turn/TurnTaker.kt:310-313` |
| `eot_ms`, `eot_reason`, `st_checks` | the turn's end: its silence, reason and Smart Turn checks | P `service/cascade/CascadeSession.kt:1021`; W `eotMs` |
| `eot_source`, `eot_silence_ms` | who ended the turn (`phone_detector`, `phone_cap`, `service`) and the silence | P `service/cascade/CascadeRules.kt:661-663` |
| `post_ms` | end of speech to the request sent | NEW |
| `first_event_ms` | request sent to the first stream event | NEW |
| `heard_ms` | request sent to `heard` | NEW |
| `first_sentence_ms` | request sent to the first `sentence` | W `reply` (`cascade-client.ts:1207`) |
| `first_audio_ms` | request sent to the first reply sound | W `first_audio` (`cascade-client.ts:724`); P `tts_first_ms` (`:2102`) |
| `backchannel` (`at_ms`, `clip_ms`) or `backchannel_skip` (`reason`) | section 4 | P `:1197-1260` |
| `filler` (`tool`, `at`) or `filler_skip` (`reason`) | section 4 | P `:2067-2076` |
| `barge_fire`, `barge_verdict` (`self`) | section 3 | P `service/turn/TurnTaker.kt:199-220`; W gate events |
| `tool_request_ms`, `tool_result_ms` | relay round trip, device run time | NEW |
| `close_reason` | `follow_up`, `tap`, `dismissed`, `missed`, `noise`, `unreachable`, `end` | W `session_end`; P `shutdown(...)` reasons |

## 12. Versioning

- The client sends `contract_version: 1` on the limb attach and on every turn.
- `contract_version` is one integer. It goes up by one with any change a client of the previous version would get wrong: a constant, an expectation in a vector, a field's meaning, a removed field or event. An addition an older client can ignore (a new optional field, an event it skips) does not change it.
- The engine serves versions N and N-1 in full. A client older than N-1 gets 409 `contract_unsupported` on the limb attach and on every turn, answers nothing by voice and says the app needs an update (wire section 12). Today the engine serves version 1 only (E `agents/legion/limbs.py:4-7`, `:43`).
- The vectors carry `contract_version`; a client's tests fail until its own version matches.

## 13. Phone and web: where they differ today

| Topic | P | W | v1 |
|---|---|---|---|
| Tap opening | earcon, no greeting | spoken greeting | P. Ruled for the phone (Kevin, speed fixes); on the web a plan default, not ruled (section 15) |
| Mic backlog | 10 s | none | P |
| Model failure | conversation ends | energy endpointer, 1.2 s | W (Kevin, Q1) |
| Barge-in default | on | off | both stay until the web's echo test |
| Playback tail | 60 / 700 ms by route | 300 ms | per route; 300 ms in a browser |
| Verdict grace | none | 320 ms | per client until a car measurement (Kevin, Q2) |
| Backchannel | yes | none | P, on all clients |
| Model speed limits | none (late verdicts ignored) | 16 ms Silero, 450 ms Smart Turn | per client class, from its own measurements; head unit to be measured |
| Follow-up after a question | 12 s | 8 s | P |
| Missed / noise closes | third miss; 5 noise | 2 no-speech | P |
| Unreachable | conversation ends | caption, listen again | cached clip, words, close |

## 14. Kevin's rulings on the open questions (2026-11-19)

Kevin, late that night: *"1 ok 2 ok 3 ok 4 ok 5 later 6 ok 7 later"*.

- **Q1. Turn models fail:** every client falls back to the 1.2 s silence rule (section 2); the conversation does not end.
- **Q2. Barge-in verdict grace:** per client (web 320 ms, phone none), to be unified after a measurement in the car.
- **Q3. The engine's acknowledgement:** it stops when the client plays backchannels (`backchannel: true` on the request, wire section 3). The slow-tool task line stays, 400 ms after the backchannel ends.
- **Q4. Closing tone:** one tone for every client (section 7).
- **Q5. Offline spoken commands:** later. A named non-goal of v1 (section 10).
- **Q6. Crisis pre-check:** on the engine, right after the transcript and before the model. With no network the client plays the generic `unreachable` clip, and no crisis detection is possible: a known gap until an on-device recognizer exists (section 10). The clip stays plain, with no crisis resource on it (Kevin, 2026-11-20: *"4 ok"*).
- **Persona (Kevin, 2026-11-20: *"1 yes"*):** the profile's companion on the phone and the web, except surfaces with a fixed persona: Moose on the head unit, the guide on a recruiter's visit.
- **Q7. Recruiter visit on the agents door:** later, after parity. v1 keeps it on the assistant door (wire section 1).

## 15. Not yet ruled by Kevin

These come from the plan (`one-voice-architecture-plan-1119`, written by an agent and condensed by
the orchestrator) or were chosen while writing this contract. Each stands in v1 until Kevin rules
otherwise.

| Item | Source | Where |
|---|---|---|
| Engine unreachable: play the cached clip, answer nothing, close the conversation | plan "unreachable = cached clip and no answer (K3)"; closing chosen here | section 9 |
| The wake word answers the unreachable clip offline | chosen here | section 10 |
| Tap = earcon, no greeting, on the web too (the phone's rule; Kevin ruled it for the phone) | plan "Session open" | section 1 |
| The 10 s backlog, the backchannel, the 5-noise close, the 12 s question window and `unsure` counted as missed, on the web too | chosen here, from the phone | sections 1, 4, 6, 7 |
| The closing tone is the web's (150 ms at 880 Hz) | chosen here; Kevin ruled one tone, not which | section 7 |
| Live stays one more OTA on the head unit as rollback | plan "What changes in HU-1" | ADR 0104, ticket 14 |
| Migration order, web alignment onto the agents door, the phone's own loop retiring last | plan "Order and sizes" | ADR 0104 Decision |
| `voice/` allowed and forbidden dependencies | plan "Shared Android module" | ADR 0104 |
| `check_in` and `proactive` turns made on the engine; the `say` kind | plan "Turn kinds" | wire section 6 |
| No new Moose text-to-speech path besides navigation cues | plan "Do NOT build again" | ADR 0104 |
| Audio-first parked on the phone and on hold on the engine | the orchestrator's call on 2026-11-20 under Kevin's delegation (*"just ship once u thin its ok"*), not his ruling; his words (*"audio first earlier . together with the speed branch."*, *"audio first default at ship"*) could not be met safely | ADR 0104 |
| The phone has no model speed limit | chosen here, from its measured Smart Turn times | section 2 |
| `say` turns: members and devices only, on the caller's turn throttle | chosen here | wire section 6 |
| Relay: give up after the round's latest deadline plus 10 s; engine idle close after 10 minutes | chosen here | section 8; wire section 8a |
| A crisis hit: one fixed code-spoken line with the help number, the same on every client (words reused from the head unit's `CrisisCard.kt:61-70` (in `headunit/app/src/main/java/com/kevin/midnightai/ui/`)), plus a `crisis` event for clients with a screen | orchestrator default, not ruled (Kevin left it to the orchestrator, 2026-11-20) | section 10; wire section 5 |

## 16. Open design decisions (2026-11-20)

Kevin has five decisions pending that may change v1. None is decided here.

1. Whether clients stream audio to the engine while he speaks (streaming speech-to-text, ended by the client's end-of-turn signal). **v1's one POST per utterance may change** under this.
2. Which transcription model.
3. A "think harder" lane on a larger model.
4. How late background answers are spoken.
5. Whether a speech-to-speech front is prototyped on the head unit.

Audio-first is on hold on the engine until these are settled (ADR 0104).
