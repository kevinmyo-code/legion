---
map: procedures
ticket: "03"
title: "The ask step: a procedure that needs a human answer, by voice"
type: grilling
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# The ask step: a procedure that needs a human answer, by voice

## Question

A procedure reaches a point where it needs Kevin ("log the oil change at 84,200 miles?"). How
does it ask on a Live voice socket without holding a tool call open?

## Recommendation

- **The run returns, it does not wait.** `run_procedure` ends the call with
  `status = needs_answer`, the question in words, the allowed answers, and a `resume` token. The
  run row is `waiting`. The model asks; the user's next utterance is sent as
  `run_procedure(resume=<token>, answer=<words>)`.
- **Same rule as Claude session ruling 2** (`.scratch/claude-session/map.md`): a yes counts only
  as the very next thing the user says. Anything else, or 2 minutes passing, cancels the run, and
  the cancel is stated ("nothing was logged").
- **Every write step is preceded by an ask step unless the user's request already named that
  write.** The runner enforces it at load: a definition with a `tool` step whose tool `writes` and
  no `ask` before it is rejected unless marked `named_by_request: true`.
- **Writes stay BLOCKING** (ADR 0065): the resume call that carries the yes is a blocking call.
- Hands path (ADR 0035): the same question renders as Yes/No buttons in the run inspector and on
  the web assistant.

## Open for Kevin

- 2 minutes, or until the conversation ends?
