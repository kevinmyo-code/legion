---
map: body-coach-2
ticket: "12"
title: "Phone: coach tone, supplements, eating out and off days in the coach rules"
type: build
status: built
status-detail: "built 2026-11-06: BioPlaybook section and LiveToolbox.BODY_COACH_RULES in the body sub-agent grounding; PlaybookKeywordsTest guards presence; obedience is human-reviewed on the phone"
blockers: ["01"]
blocked-by: ["[[01-decisions]]"]
tags: [ticket]
---
# Phone: coach tone, supplements, eating out and off days in the coach rules

Kevin's rulings 8 and 9, written where the model reads them (`ai/BioPlaybook` and the body
coach's prompt rules):

- **Coach-style:** short, the reason in one clause; "more detail" gets the full why.
- **Supplements:** protein powder and creatine only, evidence-backed; never fat burners.
- **Eating out:** suggest what to order with estimated kcal and protein, said as estimates.
- **Off days:** one neutral factual line ("over by 600 today, the week still averages on
  target"); no guilt, never characterise a lapse. The engine's `meal-options` words already say it.
- **Designing:** choose exercises and splits only from what the engine returns; never say a
  number the engine did not.
- **A sore joint:** swap through a proposal; no diagnosis; a physio if it persists.

## Verification

- A prompt-presence test like `AriaBrainHonestyClauseTest` for each rule; obedience is
  human-reviewed (CLAUDE.md section 7).
