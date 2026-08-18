---
map: aspect-advisors
ticket: 05
title: "Research: the LOG planning playbook"
type: research
status: resolved
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Research: the LOG planning playbook

## Question

Assemble the planning framework the LOG advisor ships with: task-management best practice
(GTD-style capture/clarify/review, weekly review structure, next-action phrasing), prioritization
schemes (Eisenhower, MITs), time-blocking and calendar hygiene, and overload/backlog triage
heuristics (when to prune vs defer). Note that LEGION's LOG is notes/lists/reminders plus a
read-only view of Google Calendar (Google owns appointments). Sources licensing-clean to
paraphrase into a shipped prompt. Deliverable: a distilled playbook draft in
`research/log-playbook.md` sized for a SubAgent brief, with a sources list.

## Answer

Playbook drafted at `research/log-playbook.md` (~170 lines, brief-sized). Nine sections: role/stance
(pull-only, no compulsion, LLM advises / app computes), GTD capture-clarify loop with the 2-minute
rule, next-action phrasing tests, calendar hygiene built on GTD's "hard landscape" rule (fits the
Google-owns-appointments split exactly: sparse calendar = trustworthy calendar; wishes become LEGION
reminders, never calendar entries), Eisenhower + 3 MITs with an Ivy Lee evening variant,
time-blocking as advice-only with buffers, a Get Clear / Get Current / Get Creative weekly review,
overload triage (prune before sort; stale items get do/schedule/shrink/delete, never keep-unchanged),
and fixed answer shapes per question type. All frameworks paraphrased from public methodology
descriptions, no verbatim book text.

Assumptions ledger:
- GTD five steps, weekly review structure, hard-landscape calendar rule: researched (todoist,
  gettingthingsdone.com, GTD forums).
- Eisenhower quadrants and urgency trap: researched (Todoist, Asana, Decision Lab).
- MITs = 3/day with one goal-serving: researched (zenhabits.net primary post).
- Ivy Lee six-task method, 2-minute rule: researched (jamesclear.com, Taskade et al).
- Time-blocking mechanics (evening planning, buffers, revise-not-abandon): researched
  (calnewport.com primary post).
- Backlog triage age bands and repeated-deferral signal: researched (KVI playbook, oxmaint,
  nimblework) but these are ops/PM sources, adapted to personal tasks: reasoned.
- Digest supplies ages/counts so the advisor never computes: reasoned (map's LLM-advises rule
  applied; digest contract not yet specified, ticket dependency on advisor-contract work).
- "Delegate" quadrant collapsed to decline-or-automate for a solo user: reasoned.
