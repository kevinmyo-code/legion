---
map: goal-plans
title: "Map: Goals that produce a plan"
charted: 2026-08-21
charted-by: ""
effort: "`.scratch/goal-plans/`"
tickets: 0
open: 0
status: open
tags: [map]
---
# Map: Goals that produce a plan

## Destination

**Kevin states a goal in prose, gets a plan he can argue with, and sees a daily checklist he can
tick.**

Kevin, 2026-08-21: *"I want the ai to generate a list of todos or habits for each aspect based on my
goals. my current bio goal is lose fat, gain muscle etc. via prose. and the ai should generate
calorie targets, macros, a daily checklist of workouts etc."*

And on what "finished" means: *"accept recommended with user being able to tweak the plan or ask the
agent - hey i dont have access to a gym, can we mix in kettlebell workouts etc."*

**That second sentence is half the destination.** A plan you can only accept or reject is a
generator; a plan you can talk to is a coach. Revision by conversation is not a later nicety here.

**The accuracy bar, Kevin 2026-08-21:** *"it doesnt need to be very accurate. just a check list of
recommended workouts to loosely follow etc."* Where a choice is between more accurate and simpler,
simpler wins. **This does not loosen the safety boundaries** - a loose plan has more room to be
wrong, not less.

**This map carries the BUILD.** Kevin's standing rule as of today: anything ready with no decisions
left gets built.

## Notes

**Domain:** LEGION, Android phone app (Kotlin, Compose, Room v29+), `com.kevin.legion`. Read
`CLAUDE.md` for rules and `memory/MEMORY.md` for state first.

**Where this came from.** `goal-keeping` ticket 01 asked what "on track" means per goal shape. Kevin
answered by redrawing the question: *"we need to revamp goals the entire effort."* That ticket is
superseded by this map rather than resolved.

**This EXTENDS, it does not replace.** `GoalController`, `Goal`, `GoalProgress`, the five advisors,
`set_meal_target`, `set_sleep_target`, `create_workout_plan` and `set_goal` all ship and all work.
The revamp is a generator that fills them - **and the advisor's writable-op allowlist keeps governing
every write**, which is why extending is safer than rebuilding: the guard rails already exist and are
already argued for.

**Standing preferences (Kevin, 2026-08-21):**
- Anything ready is built, not parked.
- Bring forks with real cost or taste; decide implementation without asking.
- BYO keys, nothing Kevin-hosted.
- Install and look. Every claim about the phone is owed a run on the phone.

**This map is where the WELLBEING switch finally gets content.** It has shipped saying "Nothing uses
this yet" since the proactive build.

## Settled, carried in - binding on every ticket

| # | Decision | Consequence |
|---|---|---|
| 1 | **A plan is generated ONCE, accepted, and then they are Kevin's targets** - editable, stable, regenerated only when he asks. | Same shape as an advisor proposal: it proposes, he accepts, the app writes. Targets do not drift under him because of a bad week. |
| 2 | **A plan can be REVISED BY CONVERSATION.** *"I don't have a gym, mix in kettlebells"* changes the plan. | Half the destination. Not a v2 feature. |
| 3 | **The recommender is a BIO-shaped playbook topic reusing `PlaybookStore`/`PrimingTopic`.** | The auditable-and-editable machinery already ships, including `requiredPhrases` guards that REFUSE an edit deleting a professional-referral boundary. Nothing new is built for the auditable half. |
| 4 | **Research happens ONCE, offline, and becomes the shipped playbook text.** No runtime web search. | A plan must be reproducible and auditable; one that depends on what a search returned that morning is neither, and health guidance is where a bad source does the most harm. Kevin can then edit the doctrine. |
| 5 | **Do not hedge every number forever.** Say it is a starting point once, at generation; after acceptance it is a target he chose. | CLAUDE.md §4 rule 5 is satisfied at the moment it matters. "Estimated calorie target" said daily trains him to stop hearing the word. |
| 6 | **The checklist may raise ONLY as a scheduled digest, never per-item.** | One line at a time he picks. Per-item chasing of unticked boxes is the compulsion mechanic CLAUDE.md §7 bans, wearing a fitness app's clothes. |
| 7 | **Surface: a revamped Body tab plus a revamped section on Home.** (Kevin, 2026-08-21.) | Not a new tab and not bolted onto Today's agenda. |
| 8 | **Goals may speak on a DEADLINE Kevin set, never on drift.** | *"The savings goal is due Friday"* is a fact about a date he chose; *"you are behind"* is a judgement about his week. Ticket 03's compulsion test permits the latter and this map declines it anyway. |
| 9 | **Extends the existing goals model; nothing is rebuilt.** | Writes go through the tools already in the advisor's writable-op allowlist. |

## Decisions so far

<!-- one line per closed ticket -->

## Not yet specified

- **What a "plan" is as a stored thing.** A set of targets plus a repeating checklist template is the
  obvious shape, but whether it is one Room row, several, or a document is unsettled and depends on
  what revision needs to mutate.
- **How revision interacts with acceptance.** If Kevin changes the plan by talking, is that a new
  plan, a diff, or an edit? Only sharp once the storage shape is chosen.
- **The other aspects. RULED 2026-08-21: BIO-only, and this is now a decision rather than an open
  question** (ticket 02, settled). Kevin said "each aspect" and gave a BIO example. Whether a ledger
  goal or a
  fleet goal generates a checklist at all is genuinely unclear, and BIO is the one with real
  doctrine behind it. Start there; the rest graduates when the shape is proven.
- **What "on track" means**, inherited from `goal-keeping` ticket 01 and still unanswered. It gets
  easier once a plan exists, because the plan states the target rather than the goal implying one.

## Out of scope

- **Runtime web search for health guidance.** Settled decision 4.
- **Per-item nudging of an unticked checklist.** Settled decision 6, and CLAUDE.md §7 permanently.
- **Replacing `GoalController` or the advisor harness.** Settled decision 9.
- **Medical or clinical advice of any kind.** The BIO playbook's existing `requiredPhrases` guards -
  pain and injury, medical conditions, disordered eating, minors, PEDs - bind this map unchanged, and
  a generated plan is exactly where they matter most.
