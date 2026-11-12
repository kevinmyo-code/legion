---
map: daily-loops
ticket: "02"
title: "Canvas grades and what-I-need (engine)"
type: build
status: open
status-detail: ""
blocked-by: []
tags: [ticket]
---

# Canvas grades and what-I-need (engine)

The Canvas poll also reads course totals (`include[]=total_scores`) and assignment groups (weights,
drop rules) into a table private to the member. A calculator answers "what do I need on X for a B"
with exact arithmetic; with drop rules the answer is an estimate and says so. API routes and two
engine tools (`canvas_grades`, `what_i_need`). A course that hides totals stores NULL and says
Canvas did not give one; nothing substitutes a computed figure for Canvas's.

## Verification

Server suite green, including privacy (another member, another household) and tenancy. One real
poll against Canvas after deploy: the totals match what Canvas's Grades page shows.
