---
map: goal-keeping
ticket: 02
title: "What makes a goal trackable, and how set_goal captures it"
type: grilling
status: closed
status-detail: "Archived 2026-08-22 (Kevin): superseded by the goal-plans checklist. Not dead, not queued."
blockers: ["01"]
blocked-by: ["[[01-what-on-track-means]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# What makes a goal trackable, and how set_goal captures it

## Question

Settled decision 3 puts goal SETTING in scope, over a recommendation to leave it out. This is that
decision's first consequence: if the app is going to keep Kevin to his goals, the moment a goal is
stated is the cheapest place to make it keepable.

Today `set_goal` takes whatever prose arrives and stores it. `targetValue`, `unit`, `metricKey` and
`deadlineEpoch` are all optional and an all-null row is explicitly valid - so the easiest goal to
create is the one the app can do least with.

Decide:

1. **Does the assistant push back when a goal arrives untrackable?** "Get fitter" could be met with
   "by when, and how will we know" - or that could be the app being tiresome about something Kevin
   said casually. There is a real cost either way.
2. **How hard may it push?** One clarifying question, then accept whatever comes? Or never accept an
   unmeasurable goal without an explicit "yes, keep it as prose"?
3. **Who assigns `metricKey`?** The model, from the prose? A deterministic mapping from unit words?
   A fixed picker? Getting it wrong silently makes a goal look measurable and report nonsense, which
   is worse than leaving it null.
4. **Does a goal need to say how Kevin wants to be kept to it** at the moment he sets it - checked in
   weekly, left alone until the deadline, told the moment it slips? That is one extra question at
   set time and it may answer most of "when is a moment worth interrupting" for free.
5. **What happens to the goals already in the store**, set before any of this existed. A migration
   that guesses is worse than one that asks.
