---
map: goal-plans
ticket: 05
title: "The Wellbeing switch finally gets content"
type: build
status: open
status-detail: ""
blockers: ["04"]
blocked-by: ["[[04-checklist-and-surfaces]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# The Wellbeing switch finally gets content

## What to build

A scheduled digest raise under `ProactiveCategory.WELLBEING`, which has shipped switched off saying
*"Nothing uses this yet"* since the proactive build.

One line, at a time Kevin picks: *"Today: push session, and 180g protein."*

## The rule that shapes this entirely

**Settled decision 6: scheduled digest ONLY, never per-item.** A nudge per unticked box is the
compulsion mechanic CLAUDE.md §7 bans, wearing a fitness app's clothes. There is no version of
per-item chasing that survives the compulsion test, and this ticket must not look for one.

**Settled decision 8** also applies to any goal-shaped line: it may speak about a deadline Kevin set,
never about drift.

## What it inherits for free

Everything, and it must reinvent none of it: the master kill switch, quiet hours (Wellbeing is the
one category allowed to speak inside the night window - the rest nudge lives there), the three-a-day
cap, the raise history, decline suppression, and the shared register clause.

`ProactiveRaise` needs real `facts` or the gate refuses it - the day's items ARE the facts.

## The flag

`ProactiveCategory.WELLBEING.hasContent` flips to `true` **in the same commit that lands the first
working raise**, never before. That flag is what the settings row reads to decide whether to say
"Nothing uses this yet", and flipping it early makes the row lie.

## Verification

- Suite green.
- On the phone: it fires at the chosen time, it is silenced by the master switch, and it does NOT
  fire per item.
