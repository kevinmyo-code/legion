---
map: goal-keeping
ticket: 06
title: Where the advisor learns to hold an opinion about time
type: grilling
status: open
status-detail: ""
blockers: ["04"]
blocked-by: ["[[04-when-a-moment-is-worth-it]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Where the advisor learns to hold an opinion about time

## Question

Settled decision 1 makes `AdvisorAgent` the brain. It was built to answer a question that has just
been asked: one POST, a persona clause, `HarnessPrompt`'s rules, a response schema, a precomputed
digest, and the driver's own question as the question proper.

Speaking first breaks that shape in a specific way: there is no question. Something has to supply
one, and whatever supplies it is the thing that actually decides what gets said.

Decide:

1. **What the advisor is asked when nobody asked anything.** A synthesised question ("what, if
   anything, is worth telling Kevin right now") hands the model the judgement that ticket 04 just
   spent its whole length making deterministic. A narrow one ("phrase this trigger") keeps the split.
2. **Which brief speaks.** HOME is the cross-aspect synthesiser and is deliberately read-only with no
   playbook. A goal-keeping raise may be exactly HOME's job, or exactly not.
3. **Whether `AdvisorAnswer`'s schema survives.** It forces every figure to declare
   `record | estimate | playbook`. A spoken line has nowhere to put a tag - the voice path solved
   this with `Priming.BASIS_CLAUSE`, a prose rule instead of a schema. Same answer here, or does a
   proactive raise stay structured and get rendered down?
4. **What the advice log does with an unprompted exchange.** `advisor_advice` records question, gist
   and outcome, and `outcomeFor` records a conversational answer as `accepted` because nothing was
   pending. An unprompted line nobody replied to is neither.
5. **Cost per raise**, on Kevin's key, against `.scratch/proactive-mode/`'s standing requirement that
   every new domain argues its token cost.
