# Voice eval: test Alfred and his tools without speaking

Two layers, one set of cases, one scorer (`tools/voice_eval.py`).

| Layer | Runs | Data | Speech | Cost |
|---|---|---|---|---|
| 1. Laptop | the real cascade text loop and the real tool code under Robolectric | made-up household (`VoiceEvalFixture.kt`) | none: text in, text out | the text model only, real Gemini |
| 2. Phone | the real app over adb, in a test conversation | Kevin's real data, writes refused | typed turns, or a WAV fed in place of the mic | the whole conversation |

Run layer 1 on every change to prompts, tool descriptions or routing; layer 2 before a ship.

## Layer 1: laptop, no phone

```
export LEGION_GEMINI_KEY_FILE=/path/to/a/file/holding/the/key     # never printed
python tools/voice_eval.py laptop                                  # all cases, gemini-3.5-flash-lite
python tools/voice_eval.py laptop --only due_open,due_today        # a few
python tools/voice_eval.py laptop --model gemini-3.5-flash         # the same set on another model
python tools/voice_eval.py score ~/.legion/voice-eval/laptop-A.log ~/.legion/voice-eval/laptop-B.log   # side by side
```

On this laptop, run Gradle under the shared lock: set
`LEGION_GRADLE="bash C:/Users/kevin/.legion/scripts/g.sh <worktree> <name>"` first.

`laptop` runs `VoiceEvalRun` (skipped in the normal suite: it needs both the key file and
`LEGION_VOICE_EVAL`) with `--rerun`, writes the phone's own log lines to
`~/.legion/voice-eval/laptop-<stamp>-<model>.log`, then prints the scorecard: pass or fail per case,
the tools called, the answer, turn time, tokens and the estimated cost. It refuses to run if an
engine is configured in the test JVM, so no tool can write to a real household. Not on the laptop
path: speech, speculative drafts, screens (screen tools answer success), and the cost of the ask_*
routers' own sub-agent calls.

The no-key half runs in the suite: `VoiceEvalOfflineTest` (every expected call exists on the voice
surface; the real tools read the fixture) and `python -m pytest tools/tests/test_voice_eval.py`.

## Layer 2: the phone over adb

On the phone, once: Settings, Assistant, Testing, **Allow test turns from a computer** (off by
default, in every build). Then, with wireless or USB debugging connected:

```
python tools/phone_voice_test.py status
python tools/phone_voice_test.py run                                  # typed turns, all cases
python tools/phone_voice_test.py run --only due_open --audio          # cases marked "audio": true as speech
python tools/phone_voice_test.py run --only write_todo --allow-writes write_todo   # real write, real data
python tools/phone_voice_test.py end                                  # close a test conversation
```

Each case opens a test conversation, sends its turns, waits for the reply to finish, and ends the
conversation. The scorecard adds open time (`open_first_audio_ms`, the greeting plan and any
fallback to the scripted line) and turn time (`eot_reply_ms`). The run log is real data and is
written to `~/.legion/voice-eval/phone-<stamp>.log`, never into the repo.

Safety lives on the phone (`app/.../service/testturn/`):

- the door is a content provider that answers only adb's shell (or root) uid, and only while the
  switch is on;
- a test conversation runs every tool through `TestTurnPolicy`: reads run on real data, anything
  that changes data or acts (add, edit, delete, send, call, control, open a screen) is refused with
  "Test mode: not done" and `test_mode: true` in the result, unless the run names that case in
  `--allow-writes`;
- a test conversation never opens the real microphone; its words stay out of every record: its
  `LIVE_AUDIT` lines go to logcat only (never the engine's conversation spool, which memory
  consolidation reads), no conversation audit rows, no assistant panel (typed chat's history), and
  its greeting does not use up a topic. Timing lines (`LIVE_LATENCY`, no words) still spool. It
  closes itself after 90 s
  without a command or 15 minutes in all; a real tap (or Brief me, or a proactive line) ends it at
  once and then runs normally. The wake word is not listening while it is open, as in any
  conversation, so the CLI ends it after every case.

Audio cases are spoken by the laptop voice service (Kyutai) only when its `/health` says ready with
no connection and an empty queue, cached under `~/.legion/voice-eval/clips/`; otherwise the case is
sent typed and the scorecard says why.

## Cases

`cases.json` is public and FAKE. Kevin's own cases go in `tools/voice_eval/private/*.json`
(git-ignored), which both commands read by default.

```json
{"cases": [
  {"id": "due_today", "say": "what's due today", "audio": true,
   "expect": {"calls": ["day/read_calendar"],
              "args": {"day/read_calendar": {"from": {"lte": "{today}"}, "to": {"gte": "{today}"}}},
              "no_empty_claim": true},
   "fixture": {"answer_has": ["quiz"]},
   "grade": "Names every open task due today and does not say the day is free."},
  {"id": "then_tomorrow", "turns": [{"say": "what's due today", "expect": {}}, {"say": "and tomorrow?", "expect": {}}]},
  {"id": "write_todo", "say": "add buy stamps to my to-do list", "write": true, "expect": {"calls": ["manage_checklist/add"]}}
]}
```

- `calls`: each entry must be called; `a|b` is either; `tool/action` matches the action (or router
  `domain`) the model sent, a bare name matches a tool or an action. `not_calls`: must not be.
- `args`: on a matching call, each argument equals the value, or `{"lte"|"gte": ...}`, or
  `{"re": ...}`. `{today}`, `{today+1}`, `{today-1}` are the run's date.
- `args_all`: like `args`, but EVERY matching call must fit (a wrong first call the model later
  corrects still fails).
- `answer_has` / `answer_not`: regexes on the reply (case-insensitive).
- `no_empty_claim`: fails a reply that says nothing is due while a tool result in that turn shows an
  open task (`"done":false`).
- `succeeded`: a matching call came back `success: true`; `result_has`: a successful call's result
  matches the regex. Outcome checks, so a write may reach its row by any tool (2026-11-15: a tick
  sent to the wrong list still completes the right row). Skipped on a phone write that test mode
  refuses.
- `fixture`: checks that hold only on the made-up data (layer 1; `answer_has`, `answer_not`,
  `result_has`); skipped on the phone.
- `write`: on the phone the write must be refused in test mode and the reply must not claim it.
- `read_day`: the day a calendar read covered (laptop: the window read, after the phone's day-word
  fix; phone: every calendar call's own `from`/`to` argument).
- `missed`: the transcript guard dropped the words and the spoken miss was said (laptop; a model
  saying "didn't catch" does not count).
- `proves_nothing_on` (case level): the layer where the case cannot fail when its feature breaks,
  with why and the unit test that does prove it. It still runs; read its PASS there as nothing.
- `grade`: a rubric for the optional LLM grader (`--grade`), shown in its own LLM column; it never
  changes PASS or FAIL.
