---
map: fat-loss
ticket: "17"
title: "G7: answer the question asked; appetite and habits doctrine"
type: build
status: built
status-detail: "built 2026-11-05 on feat/fat-loss, targeted JVM tests green; appetite section ~300 tokens by chars/4, not countTokens-measured; owed: a boredom-eating question on the phone"
blockers: ["01"]
blocked-by: ["[[01-kevins-rulings]]"]
tags: [ticket]
---
# G7: answer the question asked; appetite and habits doctrine

- `HarnessPrompt.RULES`: the example "You planned four sessions and logged one" as always fine is
  removed. New rule: answer the question asked; raise an adherence gap only when the question is
  about adherence.
- `BioPlaybook` gains an appetite and habits section (boredom eating; protein and fibre, water,
  planned snacks, environment, sleep), from primary sources, inside the 2,500-token ceiling, every
  `requiredPhrases` guard kept.
