---
title: Voice wire protocol v1
tags: [docs, contract, voice]
---

# Voice wire protocol v1

The bytes between a voice client and the engine. The behaviour each client owes around these
calls is in [voice-client-v1.md](voice-client-v1.md). The decision is
[[0104-one-voice-speech-on-the-engine-shared-voice-module]].

Every item is marked **EXISTS** (with the file and line on `origin/dev` at `7ef07024`, paths under
`server/`; section 0 says what has moved since) or **NEW** (owed by the engine speech stage, V1, and the relay, E2). Nothing NEW is built.

## 0. As built on 2026-11-20

The contract below is the target. The engine's door shipped DARK on 2026-11-20 (merge `a236406d`,
on `dev` at `679f7c15`): the speech stage moved from `server/assistant/` to `server/agents/voice/`,
so the `assistant/` line numbers in this file are those of `7ef07024`. What the shipped door has,
and what is still to build (branch `feat/engine-voice-v1b` is adding `line_id`, `role`,
`contract_version`, `timing`, `ignored` values and the refusal codes):

| Item | As built (paths under `server/`, at `679f7c15`) | Still to build |
|---|---|---|
| Door and flags | `POST /api/agents/turn`, off unless `LEGION_AGENTS_TURN=on` (unset in prod); speech-to-text hedge behind `LEGION_AGENTS_STT_HEDGE` (`agents/legion/flags.py:16`, `:25`; `agents/voice/hedge.py`) | |
| Request | `transcript` (max 4,000) or `audio` (multipart WAV, 16 kHz 16-bit mono, at most 28 s), `kind` (`user` and `say` built; `say` takes `text`, max 600), `speak`, `conversation_id`, `client` (`agents/views.py:124`) | `text` for a user turn, client `turn_id`, `played`, `cut_off`, `backchannel`, `contract_version` on the turn, the other kinds |
| Stream | `turn` (`turn_id`, `conversation_id`, `seq`), `waiting`, `heard`, `filler`, `sentence`, `audio`, `tool`, `speech_stopped` (at most 1,500 characters of an answer spoken, `agents/voice/pipeline.py:78`), `speech_failed`, `done`, `error` | `stream_no`, `line_id`, `role`, `facts`, `done.asked`, `done.follow_up`, `done.timing`, `done.contract_version`, `ignored: noise` |
| Crisis | after the transcript and before the model: one fixed `sentence` and a `crisis` event, then `done` with `crisis` and `end` (`agents/voice/pipeline.py:186-189`; the line is `agents/voice/crisis.py:63`) | |
| Refusals | `{detail, code}`: `bad_request`, `bad_audio`, `too_long`, `kind_not_built`, `throttled`, `member_cap` (`agents/views.py:160`) | the assistant door's codes (`busy`, `duplicate`, `no_key` and the rest) |
| Relay, replay, close | none | `tool_request` / `tool_result`, replay of a re-posted turn, the close call, the idle close, the clip set |

## 1. Doors

There are two turn doors today with the same stream format. Contract v1 makes the agents door the
one door for every device and keeps the assistant door for the web until the web alignment step
(the web's move is a plan default, not ruled: client contract section 15).

| Door | Status | Auth | Who |
|---|---|---|---|
| `POST /api/assistant/turn` | EXISTS `assistant/urls.py:19`, view `assistant/views.py:1163` | session cookie only (`views.py:127-137`) | web members, recruiter visits |
| `POST /api/agents/turn` | EXISTS `agents/urls.py:12-16`, transcript only (`agents/views.py:96-99`); audio is NEW | device token or session (`agents/views.py:77-81`); recruiters denied (`household/recruiter_routes.py:245-249`) | phone, head unit; web members after alignment |

- The agents door is off unless `LEGION_AGENTS_TURN=on` (EXISTS `agents/legion/flags.py:16-20`). Off, it answers 404.
- A recruiter visit stays on the assistant door in v1; it moves to the agents door later, after parity (Kevin, 2026-11-19, Q7).

## 2. Auth per client kind

| Client | Credential | Status |
|---|---|---|
| Phone | `Authorization: Token <key>`, a `DeviceToken` minted by `POST` login with `{email, password, device_name}` | EXISTS `household/authentication.py:21-58`, `household/serializers.py:36-43` |
| Head unit | the same `DeviceToken` type, handed over by HU-1 pairing | token type EXISTS; the pairing hand-over is NEW (HU-1) |
| Web member | session cookie plus CSRF | EXISTS `legion/settings.py:565-575` |
| Recruiter visit | session cookie on a visitor user, `recruiter` role | EXISTS (assistant door only) |

There is no token kind per device. The device kind is declared, never trusted for privilege: the
limb manifest's `kind` (EXISTS `agents/legion/limbs.py:110-118`), the turn's `client` field
(EXISTS `agents/views.py:96-99`: `phone | web | headunit`, default `phone`).

## 3. The turn request

One multipart POST per utterance: **this shape may change** under Kevin's open design decision on streaming audio while he speaks (client contract section 16). The assistant door takes raw 16-bit little-endian mono PCM at 16 kHz, no header; the agents door as built takes a WAV of the same (section 0).

| Field | Type | Status |
|---|---|---|
| `audio` | file, at most 28 s (`MAX_UTTERANCE_SECONDS = 28`, `MAX_AUDIO_BYTES = 28*16000*2`) | EXISTS assistant door `assistant/views.py:781-782`; NEW on the agents door |
| `text` | string, at most 2,000 characters | EXISTS assistant door (`MAX_TYPED_CHARS`). CHANGE on the agents door: it takes `transcript` (max 4,000) today; v1 accepts `text` and keeps accepting `transcript` while today's callers use it |
| `kind` | one of `user`, `greeting`, `opener`, `check_in`, `proactive`, `say` (section 6) | NEW. Today the assistant door infers the kind from flags (`greet`, `preface`; `tour` until it was removed on 2026-11-22) |
| `turn_id` | UUID made by the client | EXISTS assistant door `assistant/views.py:871`; NEW on the agents door (its turn id is made by the server today) |
| `conversation_id` | UUID or empty | EXISTS both doors |
| `client` | `phone`, `headunit`, `web`. With the caller's role it picks the persona: the profile's companion, Moose for `headunit`, the guide for a recruiter's visit (ADR 0104) | EXISTS agents door; the persona choice is NEW |
| `contract_version` | integer, `1` | NEW on the turn (EXISTS only on the limb manifest, `agents/legion/limbs.py:43`) |
| `utc_offset_minutes`, `client_now_ms` | integers | EXISTS assistant door |
| `barge_in` | bool: the utterance interrupted a reply | EXISTS assistant door `assistant/views.py:861` |
| `cut_reply` | the text of the reply the person cut off, up to where playback stopped | NEW (the web marks the cut-off reply server-side today, `web_conversation.CUT_OFF_NOTE`) |
| `backchannel` | bool: the client plays backchannels in this conversation, so the engine sends no acknowledgement filler (Kevin, 2026-11-19) | NEW |
| `played` | JSON list of the `line_id`s that played to the end since the client's last report (section 5a) | NEW |
| `cut_off` | bool: the client stopped a line of the last turn before its end (tap, barge-in, stop word, voice failure) | NEW |
| `greeting_history` | JSON `{"today":[...],"last":kind,"hellos":[...]}`, max 2,000 | EXISTS assistant door (parsed at `assistant/views.py:1044`) |
| `chip`, `preface`, `spoken` | demo-only fields (`tour` removed 2026-11-22) | EXISTS assistant door; not part of the device contract |

Validation (EXISTS `assistant/views.py:1235-1291`): exactly one of `audio` or `text` for a user
turn; a greeting carries neither. A `say` turn carries `text` and is spoken verbatim (NEW).
The engine counts a conversation's utterances itself (EXISTS `assistant/views.py:1412`); no
client field carries that count.

## 4. Refusals

A refused turn is JSON `{detail, code[, retry_ms]}` and no stream. Codes EXIST at
`assistant/views.py:1071-1084`: `bad_request` 400, `no_conversation` 404, `live_engine` 409,
`time_limit` 409, `busy` 409 with `retry_ms` (1,000, `BUSY_RETRY_MS`, :1085), `duplicate` 409,
`too_long` 413, `member_cap` 429, `demo_full` 429, `budget` 429, `throttled` 429, `no_key` 503.
The agents door answers `{detail}` only (400, 503) today: it adopts the same codes (NEW), plus
`contract_unsupported` 409 (EXISTS on the limb door, `agents/views.py:198-266`).

One turn in flight per caller; a second waits up to 2.5 s (`OWN_TURN_WAIT_S`, EXISTS
`assistant/turn_slots.py:47`) and then gets `busy`. A re-sent `turn_id` that was already answered
gets `duplicate` (EXISTS `TurnSeen`, `assistant/models.py:158`, `assistant/views.py:1109-1141`).
CHANGE on the agents door: a re-sent `turn_id` replays that turn's stream instead (section 8, "A dropped first stream").

## 5. The reply stream

`text/event-stream`, `Cache-Control: no-cache, no-store`, `X-Accel-Buffering: no` (EXISTS
`assistant/views.py:1437-1442`). Each event is `event: <name>\ndata: <json>\n\n` (EXISTS
`agents/runtime/events.py:24-26`).

| Event | Fields | Status |
|---|---|---|
| `turn` | `conversation_id`, `turn` (assistant door); `turn_id`, `conversation_id`, `seq` (agents door; `seq` is the turn's number in the conversation, `agents/legion/turn.py:145`); NEW: `stream_no` (1 for the turn's first stream, 2 and up for relay continuations) | EXISTS `assistant/cascade.py:1516`, `agents/legion/turn.py:179-181`. v1 sends `turn_id`, `conversation_id`, `seq`, `stream_no` on both |
| `heard` | `text`: the transcript | EXISTS assistant door `assistant/cascade.py:1571`; NEW on the agents door |
| `greeting` | `kind`, `text` | EXISTS `assistant/cascade.py:2297` |
| `working` | `tool` | EXISTS `agents/runtime/loop.py:210` |
| `waiting` | `{}` | EXISTS `agents/runtime/events.py:40-42` |
| `filler` | `tool` (`""` for the acknowledgement), `text`; NEW: `line_id` | EXISTS `assistant/cascade.py:1744`, `:1807` |
| `tool` | `name`, `ok`, `outcome`, `text`, `ms?`, `card?`; the agents door adds `guard` | EXISTS `assistant/cascade.py:1905-1915`, `agents/runtime/events.py:45-46` |
| `sentence` | `text`; NEW: `line_id`, `role` (`answer`, `write`, `owed`), `facts` on `write` and `owed` lines (section 5a) | EXISTS `assistant/cascade.py:1847` and others |
| `audio` | `pcm` (base64 16-bit LE mono), `rate`, `filler?`; NEW: `line_id` | EXISTS assistant door `assistant/cascade.py:774` (`audio_event`); NEW on the agents door |
| `speech_failed` | `reason: "tts"` | EXISTS `assistant/cascade.py:814` |
| `speech_stopped` | `reason: "limit"` | EXISTS `assistant/cascade.py:742` |
| `tool_request` | `turn_id`, `call_id`, `name` (the tool; CHANGE from `tool`, as built), `args`, `writes`, `read_through` (its result is outside text: the engine marked the turn tainted before sending it), `actuates`, `tainted` (bool: the turn's mark BEFORE this call's own, so a write must be refused on the limb), `deadline_ms` (a duration; section 8), `needs` (`screen`, `unlock`, `parked`), `confirm` (`engine` or `limb`), `limb_id`; NEW: `fact_id` (a write only, section 5a) | BUILT dark (E2, `agents/legion/relay.py`), except `fact_id`. One per call; `done` with `awaiting_limb` follows; section 8 |
| `done` | `status`, `final_text`, `writes_ran`; see below | EXISTS `agents/runtime/events.py:49`, `agents/graph/nodes.py:85-91` |
| `error` | `words` | EXISTS `assistant/views.py:1000`, `agents/views.py:110` |
| `crisis` | `text`: the fixed crisis line, also spoken as a `sentence` with `role: "answer"` and its `line_id`; a client with a screen shows its crisis card and does not also caption that line. The turn ends after it | EXISTS on the agents door as built (section 0); the behaviour is an orchestrator default, not ruled (client contract section 15) |

`done` fields:
- EXISTS: `status` (`done`, `cancelled`, `nothing_heard`, `greeting`, `not_addressed`, `unsure`, `robotic`; `tour` until 2026-11-22), `final_text`, `writes_ran`; agents door `honesty_flag`, `pending_confirm`; assistant door `end`, `ignored` (`no_speech`, `not_addressed`, `unsure`), `words`, `offer`, and for testers `cost_usd`, `tools_ms` (`assistant/cascade.py:2084-2126`).
- NEW: `ignored` on the agents door, with the same values plus `noise` (the engine's noise rule dropped it). The client counts `no_speech` and `unsure` as missed, `noise` as noise, and `not_addressed` as neither (client contract section 6).
- NEW: `status: "awaiting_limb"` with `calls` (the round's call ids) when the stream carried `tool_request`s (the status EXISTS as `TURN_AWAITING_LIMB`, `agents/models.py:52`, and nothing sets it yet); `asked` (bool: the reply asked the person something, from the tool fields `needs_answer`, `needs_confirmation` or a draft, or the last line ends in a question mark; drives the backchannel `answer_to_ask` skip and the 12 s follow-up window); `follow_up` (bool: open the follow-up window); `timing` (engine-side stage times in ms, keys in the client contract section 11); `contract_version`.
- BUILT dark (slice E3, `agents/legion/spoken.py`): agents door `end` (true when the model called `end_conversation` or his words were only a stop phrase; the limb closes after playback), and `final_text` is exactly the sentences the stream sent (code's lines included; the outside-text stub when outside text was read). Code's write line (`done_lines.py`) is a plain `sentence` for now, without `role: "write"` or `facts` (owed with the fact ledger).

### 5a. Line ids, write facts and owed lines

NEW. The phone does this on the device today (`OwedWrites.kt`, `CascadeRules.kt:448`, shipped
to `dev` at `679f7c15`); v1 moves the ledger to the engine. The web keeps today's
behaviour (no line ids, no ledger) until the web alignment step.

**Ids.**
- `line_id`: `"<seq>.<stream_no>.<n>"`: the turn's `seq` in the conversation, the stream's `stream_no` in the turn, and `n` counting the lines in that stream. Unique within the conversation, so `played` can list ids from any turn. Every `sentence`, `filler` and `audio` event carries one; all audio of one line shares its id.
- `fact_id`: `"f<n>"`, unique within the conversation. One per write the turn made (engine-run or relayed), whatever its outcome.
- A `sentence` with `role: "write"` or `role: "owed"` carries `facts`: the fact ids its words cover. One line may cover several facts.

**Roles on `sentence`:** `answer` (the model's words), `write` (code's line for the turn's writes, from their results, never the model's words), `owed` (the line for facts owed from earlier turns). Fillers and the acknowledgement are `filler` events only, never a `sentence`. There is no separate `owed` event.

**The ledger is the engine's**, per conversation. The client holds none of it. The rule is the one in `server/contracts/voice/v1/owed.json`, whose `played` lists are fact ids (the engine derives them from line ids):
- A fact is **heard** when a line whose `facts` include it is listed in `played`. A line that did not play to the end pays none of its facts, even if part of it played.
- When a turn's last stream has ended and the client's next report arrives, each fact of that turn that is not heard becomes **owed** if it went through and changed something (`ok`, `changed`, not silent), or, only when that report says `cut_off: true`, if its result never came back (not confirmed). A failed or refused write is never owed. A fact once heard is never owed again.
- The next turn's first stream starts with one `owed` line for everything owed, before any other line, and nothing else ever repeats it.

**Reports.** Every request in a conversation (the next turn, a `tool_result`, the close call) carries:
- `played`: the line ids that played to the end since the client's last report, from any stream of any turn;
- `cut_off`: true if the client stopped any line of the last turn before its end (tap, barge-in, stop word, voice failure).

**Close.** When the conversation closes with no further turn (follow-up window lapsed, dismissal, too many misses), the client posts `POST /api/agents/conversation/<conversation_id>/close` with `played` and `cut_off` (NEW). Same auth as the turn door. 204. The engine writes into the conversation record, once and silently, everything owed **plus every unconfirmed fact not heard, cut off or not**: no close drops an unconfirmed or timed-out write. CHANGE from the phone's `OwedWrites.writeDown`, which writes only what is owed, so an unconfirmed write not cut off was never written down. A second close writes nothing and answers 204.

## 6. Turn kinds

| Kind | What the engine does | Status |
|---|---|---|
| `user` | transcribe the audio (or take `text`), run the turn, speak the reply | EXISTS (default) |
| `greeting` | a spoken greeting chosen by code from the day; no audio in | EXISTS as `greet=true` (`assistant/cascade.py:2229`) |
| `opener` | a scripted opening line spoken verbatim (the demo's opening) | EXISTS only as `preface` and `OPENED_LINE` (`assistant/views.py:798`, `:1394`); NEW as a kind |
| `check_in` | an unprompted check-in (ADR 0091), facts fetched first | NEW on the engine (the phone makes it today) |
| `proactive` | a raise from the raise registry, with its anchor | NEW on the engine (the phone makes it today) |
| `say` | speak `text` verbatim in the persona's voice; no model. Members and devices only, never a recruiter; counts against the caller's turn throttle | NEW |

## 7. Cancel

- Assistant door: `POST /api/assistant/turn/cancel`, body `turn_id`; always 204, idempotent (EXISTS `assistant/views.py:1640`). The turn checks it at stage boundaries and every 250 ms while it waits on speech (`assistant/views.py:1641-1648`).
- Agents door: `POST /api/agents/turn/<turn_id>/cancel`, no body; 200 or 404 (EXISTS `agents/views.py:147-161`, helper `agents/legion/turn.py:228-235`).
- CHANGE: v1 makes the agents door answer 204 for its own turn, an already-finished turn and a repeat (today 200). 404 stays for a turn that is not the caller's, and nothing is cancelled (the ownership rule of section 8, "Results").

## 8. Tool relay

BUILT dark in E2 (`agents/legion/relay.py`, `agents/legion/relay_turn.py`, `agents/legion/relay_resume.py`, `agents/relay_views.py`; no migration): requests, `done {status: awaiting_limb, calls}`, results one post per call with 202 `waiting_for`, the continuation stream (`turn` with `stream_no`), call-id idempotency, per-call deadlines (below), the one `ok` after `timed_out`, no retry of a timed-out write, a cancelled, stale or dropped turn closed with every result it got still recorded (a result after the close is recorded too and answers 200 `{recorded, status, detail}`), `outcome: not_run` for a call the limb never started, `GET /api/agents/turn/<id>` (the turn's status and its calls still waited for, whole, for the device asked: how a limb that lost the stream picks the turn up again), the idle close (a turn paused 10 minutes, or a continuation silent 2 minutes, closes on the member's next request), at most 3 pauses per turn, and the refusals `no_turn` (404), `unknown_call` and `wrong_tool` (409), `length_required` (411), `result_too_large` (413, `result` at most 16 KiB). The result is recorded before the engine refuses to go on: 503 `no_key` (`recorded: true`; the turn keeps waiting, the same post again resumes it) and 429 `member_cap` (`recorded: true`; the turn closes). Limb tools are deny by default: a manifest tool needs a `tool_class` the engine knows (plain: `device`, `vehicle`, `place`, `household`; outside text, every call taints: `web`, `notifications`, `contacts`, `clipboard`, `screen_text`, `camera`, `files`), a mail- or message-like name is never relayed, an outside-sounding name with a plain class is refused; a limb write or actuating call with `confirm: engine` (the default) waits for his yes in a later turn, only `confirm: limb` goes at once. NOT built yet, still owed: replay of a stream from stored events (the GET above is the minimal recovery), a client `turn_id` and the re-post of a turn, the fact ledger (`fact_id`, `played`, `cut_off` are accepted and ignored). A turn that read outside text never pauses (plan 1119 section 5): a limb call in it is refused in words, or, for a tool the owner's K1 ruling names (`relay.AFTER_OUTSIDE_TEXT`, empty today), sent as `done.device_actions` (each the same data as a `tool_request` plus `after_reply: true`) for the limb to run once the reply ends and post its result like any call.

**Requests.** When the model asks for limb tools in one round, the stream carries one `tool_request` per call, in the model's order, then `done {status: "awaiting_limb", calls: [call ids]}`, and ends. No engine thread waits ([[0098-limbs-device-contract-and-relay]]).
- The client runs the calls **one at a time, in the order received**: writes stay ordered, and a limb has one screen and one confirm dialog.
- `needs` and `confirm` are copied from the limb's own manifest. If they differ from the limb's manifest, the stricter wins (the union of `needs`; `confirm: "limb"` if either says so).
- `deadline_ms` is a duration. The engine's clock decides, and the client runs a round's calls one at a time, so each call is timed from when the client can start it: call N times out at the time the engine sent the round plus the `deadline_ms` of calls 1 to N plus 5 s of grace (the grace is NEW and unmeasured). A long confirm screen on call 1 never makes call 2 late. The client aims for its own start of each call plus that call's `deadline_ms`, which is always earlier.

**Results.** The client posts each call's result as soon as it has one: `POST /api/agents/turn/<turn_id>/tool_result` with JSON. Same auth as the turn door. Only the caller that owns the turn, on the limb device that received the `tool_request`, may post; any other caller gets 404 and nothing is recorded.

| Field | Type |
|---|---|
| `call_id` | the id from `tool_request` |
| `outcome` | one of `ok`, `failed`, `refused`, `timed_out` |
| `result` | JSON object, the tool's output (absent unless `ok`) |
| `words` | what did or did not happen, in words (always present) |
| `ms` | how long the call ran on the device |
| `played`, `cut_off` | the report of section 5a |

Answers:
- The post that completes the round (every call of it has a result) answers with the **continuation stream**: the rest of the turn, same events, starting with a `turn` event that has the same `turn_id`, `conversation_id` and `seq`, and the next `stream_no`.
- A post that leaves calls of the round still open answers 202 `{waiting_for: [call ids]}`.
- **A repeated post for a call id already recorded is idempotent for the result:** it runs nothing and records no second result. **One exception:** a call whose recorded result is `timed_out` (the engine's or the client's) accepts exactly one later `ok` for the same call id. It replaces the recorded result on that same fact (same `fact_id`, no new fact), moving it from unconfirmed to done; nothing is executed. If the "couldn't confirm" line was already heard or written down, the done line is then owed or written down. Any other second result is ignored. **Its `played` / `cut_off` report is always applied.** Reports only add: a line once reported played stays played, and `cut_off` once true for a turn stays true. It answers what the first post would answer now: 202, or the continuation stream replayed from its first event and then continued live. The client plays only line ids it has not played. This is also how a client recovers a continuation stream that dropped: post the same result again. Never `duplicate`.
- The engine keeps a turn's events in its database, so any instance can replay them, until the conversation closes or its idle limit passes (section 8a, NEW). A re-post that arrives while the stream is still being produced follows the stored events as they are written; it never starts a second run.
- **After a call's engine deadline passes**, no engine thread runs the rest of the turn on its own: it is built on demand by the next request for that turn. A `tool_result` post for any call of the round, or a re-post of the turn with its `turn_id`, then answers with the continuation stream, built with the timed-out call recorded as `timed_out` (not confirmed) if it was not built yet, or replayed if it was. A result in a post that arrives after the deadline replaces the call's `timed_out` result under the one exception above. Once the turn's stream has ended, a late post answers 200 `{recorded: true, status: "late"}` and no stream.
- A post for a turn that was cancelled while it waited answers 200 `{recorded: true, status: "cancelled"}` and no stream. A write in it enters the ledger the same way.

**A write runs at most once on the device.** The device keys a write's execution on its `fact_id` (a read's on its `call_id`) and keeps the result for the conversation. A request for a fact or call it already ran (a replayed stream) is not run again: the device posts the stored result.

**After a write tool times out, the engine issues no further call of that tool in the turn,** whatever the arguments, until that call's result arrives or the turn ends: a retry with changed arguments is exactly the double run this prevents. The timed-out call stays one unconfirmed fact under the one owed rule (section 5a). A model call of that tool meanwhile is refused in words and sends no `tool_request`; the refusal is a refused fact, never owed. Two intended writes of one tool in one turn still both run when neither has timed out. Vectors: `owed.json`, "after a write tool timed out ..." and "a write whose line played ..." (two writes of one tool).

**A dropped first stream.** The client posts the turn again with the same `turn_id`, the same audio or text and its new `played` report. CHANGE: today the engine answers `duplicate` for a turn it already answered (section 4); in v1 it replays that turn's stream from its first event and continues it live, and never runs the turn twice. Only the caller that owns the turn may re-post it; any other caller gets 404 and nothing is replayed or recorded (the ownership rule of "Results" above).

### 8b. The hold, speech off, client facts (E2b, BUILT dark)

`agents/legion/hold.py`, `agents/legion/client_facts.py`, `agents/turn_options.py`; no migration.
- **`go`** (bool, default true) on `POST /api/agents/turn` and in the stream's `M` frame. `go: false` HOLDS the turn: the engine streams its thinking and reads at once, but runs no write, no `run_procedure`, no relay request and no late-answer start until `POST /api/agents/turn/<id>/go`; `POST .../take` keeps the turn on the phone (cancelled, nothing acts, nothing recorded as said). Both answer 200 `{decision, status}`; 404 `no_turn`; 409 `already_go`, `taken`, `turn_over`. No decision 5 s after the turn began is a cancel. The turn's record waits for the decision (after `done`).
- **`speak`** (bool): false makes no speech: no text-to-speech, no `audio` events, nothing metered as speech; unset keeps the door's default (audio spoken, transcript not).
- **`client_facts`** (object): `language`, `carry_tainted`, `prior_line` (max 300), `test_mode`, `utc_offset_minutes` (-840..840), `heard_until {turn_id, chars}` (what he heard of an earlier answer before he cut in; that turn's stored answer is cut there).
- **The brain at the turn doors:** a device whose resolved brain is `phone` gets 409 `brain_phone` (not on a `test_mode` turn). The attach now stores `brain_asked` with the manifest.
- **Contract v2 additions (BUILT dark on `feat/engine-voice-e2b`):** every engine-STT turn sends `go: false` (after `heard` nothing acts until `go`; `take` covers every phone takeover: noise, language, crisis, backchannel, stop, mail, mixed conversation). A client `turn_id` (UUID) on both routes; a repeat answers 409 `{code: duplicate, turn_id, status}` and the turn is readable at `GET /api/agents/turn/<turn_id>`. The batch route holds a per-conversation lock (wait 1.5 s, then 409 `conversation_busy`); the stream route waits 1.5 s for the member's previous stream before 409 `stream_busy`. The attach answers `dropped: [{name, reason}]` (`engine_tool`, `mail_like`, `unknown_class`, `outside_name_plain_class`); the lists are `server/contracts/limb/v1/tool_classes.json`. `client_facts.phone_turns` (max 6, 4,000 chars): `[{said, answer, tainted}]`, kept as marked device-supplied context; a tainted answer is blanked.
- **Review 1121 fixes:**
  - An absent `go` holds on the stream route and for an `audio` clip on the batch route; it means go only for a `transcript`.
  - The hold also covers engine reads that leave the engine (`engine_tools_leaving_engine` in `server/contracts/limb/v1/tool_classes.json`).
  - A take or a timeout keeps none of the turn's words: transcript, final text, history, checkpoints and tool-call text are never written or are blanked.
  - A go after the timeout answers 409 `turn_over`.
  - `test_mode` never bypasses the brain.
  - A head unit gets 403 `headunit_client_facts`.
  - The stream route also takes the per-conversation lock (`stream_busy`).
  - The phone reuses its `turn_id` on a stream-to-batch fallback, and drops everything streamed for a turn it takes.
- **Events:** `working {tool, kind}` for every call on the agents door (`kind`: `local`, `slow`, `write`, `relay`); `tool` carries `write_fact {tool, title?, list?}` for a write that went through (engine or relayed).

### 8a. Idle close

NEW. A client that dies posts no close, so the engine closes a conversation itself after **10
minutes** with no request on it. Ten minutes is far above the longest gap a live conversation has
between two requests (a 12 s follow-up window, a 20 s tool hold, a 75 s turn deadline, a relay
deadline plus 5 s, or a person reading a confirm screen), and short enough that stored events,
audio included, do not pile up.

At the idle close the engine:
- writes into the conversation record every fact not heard: a write that went through as done, and an unconfirmed one (a timed-out relay call included, cut off or not) as not confirmed, late results included. Beyond the close call's write-down it also writes said facts not heard, because no client reported what it played;
- deletes the conversation's stored events;
- answers any later request on that conversation with `no_conversation` (404), so the client starts a new one.

No write is left unrecorded when a client dies.

## 9. Warm

`HEAD /api/agents/warm` answers 204 (EXISTS `agents/views.py:269-273`, behind `LEGION_AGENTS_TURN`).
The client sends it when the session opens (tap, push-to-talk or wake word), before the first
utterance is finished.

## 10. Clip set

NEW. `GET /api/agents/voice/clips?client=<phone|headunit|web>&version=<n>`: the clips a client
plays without a turn, in the persona's voice. Answers `{version, voice, clips: [{key, text, pcm,
rate}]}` or 304 when `version` is current. Keys:
- `unreachable`: "can't reach the engine" (played with no network, so it must be cached first);
- `ack.*`: the acknowledgement lines (EXISTS as text: `assistant/fillers.py:133`);
- `backchannel.*`: the short backchannel sounds.

The earcon is bundled with the client, not fetched.

## 11. Limb attach

`POST /api/agents/limb` (EXISTS `agents/views.py:198-266`, `agents/legion/limbs.py`): device token
only, `Content-Length` required, manifest at most 256 KiB. Body: `contract_version`, `kind`
(`phone`, `headunit`), `app_version`, `brain_asked` (`phone`, `engine`), `tools` (each with
`name`, `description`, `parameters`, `writes`, `read_through`, `actuates`, `needs`, `confirm`),
`streams`. Answer: `limb_id`, `contract_version`, `brain`, `brain_reason`. A version the engine does
not serve gets 409 `contract_unsupported`.

Limb attach happens once per conversation open, after the mic opens and before the first turn is
posted (one upload per conversation open: `agents/legion/limbs.py:9`). A client that gets
`contract_unsupported` answers nothing by voice: it shows the engine's words and says the app needs
an update. The web is not a limb kind today (`kind` is `phone` or `headunit`): it attaches nothing,
has no limb tools, and its turns never get a `tool_request`.

## 12. Versioning

- The engine serves contract versions N and N-1 ([[0098-limbs-device-contract-and-relay]]). Today it serves version 1 only and refuses any other (EXISTS `agents/legion/limbs.py:4-7`, `:43`); with one version there is no N-1 yet.
- `contract_version` is one integer. It goes up by one with any change a client of the previous version would get wrong: a constant, an expectation in a vector, a field's meaning, a removed field or event. An addition an older client can ignore (a new optional field, an event it skips) does not change it.
- A client sends `contract_version` on the limb attach and (NEW) on every turn. A version older than N-1 gets 409 `contract_unsupported` on both, and the client answers nothing by voice. For voice clients this replaces [[0098-limbs-device-contract-and-relay]]'s "text-only turns": a voice client has no text-only mode.
- No HTTP header carries a version; none is added.
