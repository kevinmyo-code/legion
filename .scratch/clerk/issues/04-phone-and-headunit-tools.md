---
map: clerk
ticket: "04"
title: "Build: delegate_task, check_clerk, confirm_task, the done raise, Clerk screen; head unit tools"
type: build
status: kiv
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Build: phone and head unit tools

Branch `feat/clerk-phone` off `dev`. Phone `app/` and `headunit/`. Built against the API table in
the map; the engine (ticket 02) lands separately, so the Kotlin client is tested with a fake.

## Phone

- `backend/engine/` gets `ClerkApi` (uses `EngineHttp.get/post`), with a small fake for tests.
- `service/LiveToolbox.kt` declares three tools, typed chat gets them too, both through one
  controller `clerk/ClerkController.kt` (ADR 0035: one controller, two paths):
  - `delegate_task(request)`: **write, BLOCKING**, `isMutatingTool` true. Result says exactly one of:
    "queued; the clerk on <machine> is online (seen <n> s ago)", "queued; <machine> has been off
    since <time>, it runs when the laptop is on", "queued; no clerk has ever connected". It never
    says done.
  - `check_clerk(limit?)`: read; the status line plus the newest tasks with status and summary.
    One GET; leave it out of `NON_BLOCKING_READ_TOOLS`.
  - `confirm_task(task_id, answer)`: write; posts a followup task with the answer. The declaration
    says to use it only after the user answered a clerk question out loud.
  - Descriptions name no assistant. Add copy to `tools/voice_guide_copy.py`;
    `python tools/voice_guide.py` must pass.
- Prompt: one sentence in `ai/AriaBrain.kt` at file scope beside `WRITE_ON_REQUEST_CLAUSE`: a
  delegated task is queued, not done; its outcome is read from `check_clerk` or the raise, never
  assumed. Presence test in `AriaBrainHonestyClauseTest`.
- The done raise: find the proactive raise registry (`data/local/ProactiveRaiseRow.kt`,
  `AriaForegroundService.kt`, and `.scratch/proactive-mode/issues/03-compulsion-test.md` for the
  four conditions). Register `clerk_task_finished`: anchor (a) the task Kevin asked for, by its
  request text; (b) actionable: hear the summary; (c) never mentions time since he last talked;
  (d) silenceable: "stop telling me about clerk tasks" sets a pref. Trigger: a poll of
  `GET tasks/?status=done,failed,needs_confirmation&since=<last seen>` from periodic work the app
  already runs (find the freshness or brief poller; do not add a new alarm). If the server has push
  (`server/push/`), prefer a push with the task id over polling and say which you used.
- Hands path: `ui/clerk/ClerkScreen.kt`, soft Material (ADR 0051): status card (machine, online or
  off since), a text field plus Send, the task list with status chips and summaries, tap for the
  full summary and files (open with a share intent). Entry from Settings or the Matrix home where
  other aspect screens are listed (find the pattern). Tests: a Compose test for the three status
  states and the empty list.
- Audit: `LiveAudit` already records tool args and results; nothing new. The task request is
  household data, not third-party, so no redaction.

## Head unit

- `headunit/`: `check_clerk` and `delegate_task` with the same controller shape against the same
  endpoints; its own rules in `headunit/CLAUDE.md`. No screen in v1 (the car is voice-first);
  say so in the report.

## Verification

- Phone: `./gradlew compileDebugKotlin -Pnokey`, `testDebugUnitTest` (JUnit XML totals, 0
  failures), `detekt` (4 pre-existing), `python tools/voice_guide.py`, `python tools/docs_check.py`.
- Head unit: its `testDebugUnitTest` totals from XML.
- README "Tools" line and `docs/voice.html` regenerate through the scripts; `docs/architecture/`
  if it lists aspects.
