---
map: ambient-listening
ticket: "05"
title: "What the reaction pass costs on Kevin's own key"
type: task
status: open
status-detail: ""
blockers: ["02"]
blocked-by: ["[[02-the-toggle-and-its-words]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# What the reaction pass costs on Kevin's own key

## Question

Nothing to decide. Ambient listening runs the same continuous Vosk model the wake word does **and
then adds a periodic Gemini call on the accumulated transcript**. It cannot be cheaper than the wake
word, and the wake word's own cost is being measured separately.

Two numbers are needed, both on the A25, both on battery:

1. **Battery, above the wake word's baseline.** The wake word map is measuring continuous Vosk in
   [What always-on Vosk actually costs the A25 in a day](../../wake-word/issues/03-measure-the-battery-cost.md).
   What this adds on top is the figure that matters, so run the same protocol and subtract.
2. **Gemini tokens and dollars per hour of conversation.** How often the pass fires, how large the
   transcript is when it does, and what that comes to on Kevin's BYO key. A talkative hour and a
   quiet hour are different numbers - report both.

Then the honest question the numbers answer: **is this affordable to leave on?** A feature that costs
real money per hour of ordinary conversation is one Kevin will want to run in windows, not
continuously, and that changes the toggle's design.

State the window, the conditions, and whether the cabin was talkative. Assumptions ledger per
`CLAUDE.md` sec 8; do not report a projected cost as a measured one.
