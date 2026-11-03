---
map: procedures
ticket: "18"
title: "Build: web assistant and recruiter visit use run_procedure"
type: build
status: open
status-detail: ""
blockers: ["07", "12", "15"]
blocked-by: ["[[07-recruiter-visits]]", "[[12-build-runner-and-tool]]", "[[15-build-first-procedures]]"]
open-blockers: 3
ready: false
tags: [ticket]
---
# Build: web assistant and recruiter visit use run_procedure

## What

- Web: nothing to declare (`WEB_TOOL_NAMES` reads the registry); the web prompt
  (`assistant/prompt.py`) gets the same "prefer a procedure" clause as the phone.
- `AssistantToolView` passes `conversation_id` into `run_procedure` so the run links to the record.
- Recruiter: the procedure list filtered to recruiter-runnable ones; the guide's capability card
  (recruiter-demo ticket 05) may mention "ask it to check the car and then open 'How LEGION
  answered that'" if ticket "Recruiter visits" says yes.

## Verify

- `tests/test_recruiter_isolation.py`: member-only procedure refused; recruiter run lands only in
  the visit household.
- Playwright/vitest: a car check in the web chat shows one tool call and the inspector link.
