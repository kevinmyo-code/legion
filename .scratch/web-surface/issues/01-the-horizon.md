---
map: web-surface
ticket: "01"
title: "The horizon: what Today shows beyond tomorrow, and how a cliff reads as a cliff"
type: decision
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# The horizon

Today renders **today and tomorrow**. That came from `docs/design/today.md`'s Cozi finding, and the
research was sound for the product it studied: Cozi is a family calendar, and a week of rows is a
wall of rows.

**Kevin's data is not a family calendar.** Read from the live engine 2026-09-12:

```
today  +1   +2   +3   +4   +5   +6   +7   +8   +9  +10  +11  +12  +13
 1e    9t    -   3e   1t   3e   2t    -   4t    -  3e   2t   3e   1t
```

Nine deadlines tomorrow. Four on +8. Classes in threes. **The screen is honest today only because
tomorrow happens to be the cliff.** On Monday the next four deadlines are six days out and Today
shows nothing at all.

## The question

**How far does Today see, and what does it show of what it sees?**

Not the same question twice. Rendering seven days of rows is the failure Cozi's research correctly
warns about; the point is to show the SHAPE of the week without the rows.

## Options

**A. Today + tomorrow, plus a week strip.** Keep the two expanded days exactly as they are, and add
one compact row above or below them: fourteen cells, one per day, each carrying a count and a
weight. A cliff is then visible as a cliff without a single extra deadline row on screen.

**B. Today + tomorrow + "next up".** Keep two days, and add a short list of the next N deadlines
whenever they fall, with their real dates. Simpler, but it buries the shape - nine on one day and
nine spread over nine days look identical in a list.

**C. Today + the rest of the week, grouped by day.** Expand the horizon to seven days with day
headings and collapsed groups. Closest to what a student actually plans against; the most rows, and
the thing the Cozi research warns against.

**Recommendation: A.** It is the only one that makes load legible rather than enumerable, and it
costs no vertical space on the phone. B and C both answer "what is due" and neither answers "when
does this week get hard".

## What the decision must also settle

1. **Does the horizon differ by surface?** The phone has ~700px; the desktop has 898 and is empty.
   A fourteen-cell strip fits both, but the desktop can afford the strip AND a list.
2. **Does a past-due, undone task keep appearing?** There are 92 past events. A deadline that passed
   unticked is either still work or noise, and the answer changes what Today shows on a Monday.
3. **Is "9 due tomorrow" said in words?** A count is a fact the model of the screen should state,
   not something the user derives by counting rows.

## Not in scope

The look of the strip. That is ticket 02's build detail under the language settled in
`docs/design/canvas/`.
