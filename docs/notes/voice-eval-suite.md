# Voice eval suite: what exists, and what to add
Saved Nov 25, 2026 after a voice call with Kevin. A NOTE, not committed work and not a ticket.

Kevin's ask, in his words: a suite where "Legion or some agent would put prompts into Alfred... get his answers and his actions and tool calls", run "after every like big chunk of work", with a score, for example when he was just chatting and Alfred called a tool, or asked for a calendar thing and Alfred did something else. Keep the API cost low.

## What already exists (checked on dev `d7e477c`)

A harness exists and is good. It is not run regularly, and it has few "just chat" cases.

- **Voice eval, layer 1 (laptop, text only):** `tools/voice_eval.py laptop` runs the real cascade text loop and real tool code under Robolectric (`VoiceEvalRun`) on a made-up household (`VoiceEvalFixture.kt`). It records the reply, every tool call and its arguments, turn time, tokens and estimated cost, and prints a pass/fail scorecard. Text in, text out, so only the text model is billed. Guide: `tools/voice_eval/README.md`.
- **Voice eval, layer 2 (phone over adb):** `tools/phone_voice_test.py run` sends typed or spoken turns into a test conversation on the real phone. Writes are refused in test mode, and test turns stay out of memory and the conversation record.
- **Cases:** `tools/voice_eval/cases.json` has 65 public cases, and private ones go in `tools/voice_eval/private/` (git-ignored). Each case can check `calls` (must call), `not_calls` (must not call), `args`, `answer_has` / `answer_not`, `no_empty_claim`, `write` and more. 13 cases use `not_calls`.
- **Tool routing eval:** `tools/live_tool_eval.py` sends 115 sentences (`tools/live_tool_eval/cases.jsonl`) with the real system instruction and tool list to a text model and scores which tool it picks. A cheap proxy for the Live model.
- **No-key checks in the normal suite:** `VoiceEvalOfflineTest` and `tools/tests/test_voice_eval.py` check that every expected call exists on the tool surface. These run in CI.
- **CI with a key:** `.github/workflows/gates.yml` runs the 36 cases in `tools/voice_eval/gate_cases.txt` plus the money specialist eval, about $0.10 to $0.20 a run, capped at 6 runs a day. It is manual only (workflow_dispatch) and has never been run. It fails until Kevin adds `GEMINI_API_KEY` to the `gates` environment himself.

## Gaps

1. **Hardly any "just chat" cases.** Only a handful of cases expect no tool at all (`time_now`, `tags_casual`, `lone_word_no_context`, `filler_web`). Nothing checks small talk, personal facts shared in passing, or general knowledge questions.
2. **No check for unwanted memory saves** or for saying "saved to memory".
3. **No summary score.** The scorecard is pass/fail per case; there is no tool-choice accuracy or "tool called while chatting" rate to compare runs.
4. **Moose is not covered.** Nothing under `headunit/` runs these cases.
5. **Not run after big chunks of work.** Gates is manual and has never run; layer 1 runs only on the laptop when someone remembers.
6. **No memory recall cases**, such as "what did we talk about today?" after a closed chat.

## Proposed shape (build on what exists, do not start over)

- **Tag each case with the expected behaviour:** `chat` (no tool, no save), `tool:<name>` (that tool, right arguments), `save:none` or `save:asked` (memory save only when asked). Express these with the existing `calls` / `not_calls` / `answer_not` keys so the scorer needs little change.
- **Add a "chat" set of about 20 cases:** small talk ("ha, long day"), personal facts in passing ("I love hiking"), general knowledge ("how far is the moon?"), opinions. Each with `not_calls` on memory, calendar and records, and `answer_not` on "saved" or "remember".
- **Add a few memory recall cases** once #450 to #452 land: a scripted first chat, close it, then "what did we talk about today?" in a second chat.
- **Score two numbers per run** on top of pass/fail: tool-choice accuracy (right tool on tool cases) and false tool rate (any tool on chat cases). Keep each run's numbers so before and after can be compared.
- **Text only, to keep it cheap:** layer 1 and `live_tool_eval.py`, no speech, the default Flash-Lite model. Speech and the phone (layer 2) stay for before a ship.
- **When to run:** after each big chunk of work, through `gates.yml` (once the key is in the `gates` environment) or by the Legion agent on the laptop with `python tools/voice_eval.py laptop`. Add the chat set to `gate_cases.txt` so a phone ship has to pass it.
- **Cover Moose:** run the same cases against the head unit's prompt and tool list, since Alfred and Moose should behave the same.

## First things it should check

- **PR #449** (chat is the default): the chat set should show the false tool rate drop, with tool cases still passing.
- **#450 save each turn, #451 summary on close, #452 load real context:** the memory recall cases.
- **#453 fewer tools in view:** false tool rate and tool-choice accuracy before and after.
- **#454 retire the model-chosen memory save:** zero unasked saves, and "remember that X" still saved.
