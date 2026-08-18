---
map: goal-keeping
ticket: 05
title: "What prompts a revision, and what an abandoned goal costs"
type: grilling
status: open
status-detail: ""
blockers: ["01", "03"]
blocked-by: ["[[01-what-on-track-means]]", "[[03-the-check-in-record]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# What prompts a revision, and what an abandoned goal costs

## Question

`Goal` already carries `status` (`active`/`achieved`/`abandoned`), `closedAt`, and a full revision
lineage. Nothing ever prompts any of it. A goal set once stays active forever, and a keeper pointed
at a goal Kevin quietly gave up on is the fastest way to make him stop believing the whole feature.

Decide:

1. **What triggers a review.** The deadline passing, a run of check-ins that say "stalled", a long
   silence, or a fixed cadence.
2. **Who proposes the change.** The advisor already has a propose-accept protocol and `set_goal` in
   its allowlist; retiring a goal is a write and must not happen without Kevin's explicit yes.
3. **Is "abandoned" a failure or a fact?** The register matters. CLAUDE.md sec 7 permits warmth and
   bans guilt; a goal being dropped is information, and the app must be able to record it without
   editorialising.
4. **What the app does with a goal that got easier.** The lineage makes this visible and the
   `aspect-advisors` work names it as the coaching payoff. Does anything ever say it out loud, and
   is that help or judgement?
5. **Does an achieved goal leave anything behind** - a record Kevin can look at, or does it just
   stop appearing.
