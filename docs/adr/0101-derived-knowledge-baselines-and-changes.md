---
status: accepted
decided: 2026-11-17
decided-by: Kevin
source: "[[decisions#2026-11-17 - Derived knowledge: baselines and changes, never raw data in memory (Kevin)]]"
tags: [adr]
---

# 101. Derived knowledge: baselines and changes, computed by code, never raw data in memory

## Standing

**Raw data is never copied into memory.** Alfred looks it up live and states it with its as-of time.

**Only conclusions are built from data, and code builds them.** There are two layers:
- **Baselines.** Each is a figure computed on a schedule and stored with the window it covers, its sample size, when it was computed and its method. Below a minimum sample, no baseline is stated.
- **Changes.** One `change_events` log ("what changed since X"): a new fault code, a budget crossing its cap, a balance below his floor, work becoming overdue, a grade posting, a card balance missing. Each entry records its source, its time, the rows it came from, and whether he has been told.

**Patterns, meaning regularities a model notices, are OFF.**

**Rules for both layers:**
- **Tenancy and visibility.** Both are scoped by household and read through `visible()`. A baseline takes the narrowest visibility of its inputs.
- **No read-through input.** Mail and web content are never inputs.
- **Sources are kept,** so "why do you think that?" has an answer.
- **Unprompted mentions are bounded.**
  - Using either in an answer he asked for is always allowed.
  - Raising one unprompted must pass the compulsion test in CLAUDE.md section 7 and the check-in rules of [[0091-alfred-greets-from-your-real-day]].
  - Never his absence, a streak or his engagement. A nudge about a goal may name the goal and its next action, never how long it went unattended.
- **"Forget that" deletes the item and it is never recorded again.**

## Context

Kevin: *"baselines and changes > i want that added to the agent graph plan. maybe patterns too"*. He took baselines and changes and turned patterns off.

The watch evaluator ([[0083-standing-watches]]) already records firings with sources, a baseline flag and an acknowledgement. Overdue work and a missing card balance are computed only when read, so they leave no history today.

## Decision

- **The first five baselines**, each with its minimum sample:
  - spend in a category by this day of the month: 3 months;
  - usual sleep: 10 nights;
  - training sessions per week: 4 weeks;
  - weekly miles per car: 4 weeks;
  - days between buys of a pantry item: 3 buys.
- **`change_events` is one table.** The existing watch evaluator writes it for every known kind, whether or not a watch is armed. A watch firing links to its change, and watches stay the switch for raising.
- **Specialists read both through pull-based tools,** `baselines` and `changes_since`. Nothing is injected.
- **The greeting picker is their first consumer** once it runs on the engine.

## Consequences

- Miles between fuel fills cannot be a baseline yet. No table records fills, and capturing them is an ingestion path under CLAUDE.md section 4.
- Forgetting writes a suppression key with no content, so a detector or a nightly computation never re-creates what he removed.
