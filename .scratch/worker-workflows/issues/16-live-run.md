---
map: worker-workflows
ticket: "16"
title: "Live run on the Alienware"
type: task
status: open
status-detail: ""
size: S
blockers: ["06", "09", "11", "12", "14"]
blocked-by: ["[[06-phone]]", "[[09-wf-log-audit]]", "[[11-wf-ship-round]]", "[[12-wf-memory-review]]", "[[14-workflow-miner]]"]
open-blockers: 5
ready: false
tags: [ticket]
---
# Live run on the Alienware

After the engine deploy (Kevin's yes) and a phone update. With Kevin, on the A25:

1. "What workflows does Claude have?" Alfred lists names from `check`.
2. "Have Claude audit my logs from this morning." Sent as a workflow; the report lands in
   `.legion\reports\log-audit\`; the summary is spoken; "want it on your phone?" works.
3. "Have Claude fix finding one." Ends at the ship question; Kevin says no; nothing ships.
4. "Have Claude run a log autopsy" (no such workflow): Alfred reads back the known names.
5. Same as 2 from the Claude session screen's Run button.
6. Laptop off overnight, on in the morning: exactly one memory review runs after 10 idle minutes.
7. A ship round: question asked with the branch list; deploy only on Kevin's spoken yes.
8. Workflow miner by hand ("what do I keep asking Claude?") with a seeded task log: one question;
   a no records the dismissal.
9. Logs on the laptop and engine carry no request, params or report text.

Each step: pass, fail with what was heard, or deferred with a named follow-up (CLAUDE.md §8 L11).
