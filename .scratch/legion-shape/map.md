---
map: legion-shape
title: "Map: What LEGION actually is"
charted: 2026-08-06
charted-by: /07
effort: "`.scratch/legion-shape/`"
tickets: 12
open: 0
status: closed
tags: [map]
---
# Map: What LEGION actually is

## Destination

**A decided shape for LEGION, detailed enough that any one domain can be planned and built without
re-opening what the app is.**

Reached when: the shared plan-versus-actual concept is specified once, the two trust tiers are
specified once, and each of the five domains is scoped to its own first closed loop. Not reached by
building any of them - this map produces decisions, and the builds are separate efforts after it.

## Notes

**Domain:** LEGION, Android phone app (Kotlin, Compose, Room v5), `com.kevin.legion`. Branch
`feat/ledger-ingestion`. Read `CLAUDE.md` for rules and `memory/MEMORY.md` for state before deciding
anything. Most of `memory/library/` is FROZEN Midnight AI history and carries a status banner.

**Why this map exists.** Kevin, 2026-08-06: *"we need to figure out what this app is really trying
to do. i feel like we are lost in the ledger stuff."* The instinct was measurable, not a mood: 17 of
37 test files are ledger, 22 of the last 30 commits are ledger or its Drive plumbing, and the voice
path - the thing `CLAUDE.md` §1 calls the product - has one test file and **has never once run**.

The cause was not misplaced effort. It was that **nothing ever said when a domain was finished**, so
every session found one more true thing to fix. Each fix was real. There was no line to cross.

**Skills each session should consult:** `/grilling` and `/domain-modeling` for the HITL tickets,
`/prototype` for prototype tickets, `/research` for research tickets.

**Standing preferences for this effort (Kevin, 2026-08-06):**
- Simple first. Each domain does the small obvious thing before it does anything clever.
- Nothing on the cut list was cut. Music, places/reminders, weather and the garage door all stay.
  Scope did not shrink, so ordering is what has to do the work.
- `/gauntlet-loop` (github.com/robonuggets/gauntlet-loop, CC BY 4.0) was considered and **parked**:
  it needs a real, named, fetchable thing to beat, and no such artifact exists for "what should this
  app be". Revisit it for Alfred's writing, which is the one part of LEGION with published prose to
  measure against. Would need an `.claude/skills/ATTRIBUTION.md` entry if installed.

**HARD PROCESS RULE.** `.scratch/` was lost once in a machine port and cost a 15-ticket map. `map.md`,
`issues/**` and `research/**` are now git-tracked - commit them like any other file. File decisions
to `memory/library/decisions.md` when they are made, not when the effort ends.

## Decisions so far

- [What is LEGION?](issues/01-what-is-legion.md) — a **personal record of your life that you talk
  to**. The data is the product; voice is how you write to it *and* read from it; the persona is the
  front door. Five domains: workouts, meals, expenses, bank statements, car data.
- [How many kinds of truth does the record hold?](issues/02-trust-tiers.md) — **two, and they never
  mix.** *Proven* has an outside source that agrees; *reported* is you saying so. Every row says
  which. Any figure built from both says so out loud. `IngestMethod.UNRECONCILED` already is this.
- [Is plan-versus-actual one idea or four?](issues/03-plan-versus-actual.md) — **one idea, four
  coats.** Shared vocabulary and shared rules, **separate storage per domain**. Explicitly not one
  generic engine.
- [What happens to the P&L?](issues/04-pnl-becomes-budget.md) — **replaced by budget-versus-actual.**
  A P&L answers "what happened last month"; Kevin wants "how much of groceries is left". The
  transfer-matching and coverage layer underneath survives unchanged.
- [Is there a keyless food/macro data source?](issues/12-macro-data-source.md) — **no; use the LLM
  estimate and label it.** USDA's API needs a data.gov key (keyless `DEMO_KEY` caps at 50/day, so it
  fails clone-and-run); USDA's bulk data is CC0 and clean but its shippable subsets carry no grocery
  brands, and the branded set is 2.9 GB; Open Food Facts is keyless to read but ODbL, so bundling it
  publishes a Derivative Database with share-alike duties. `PantryReceiptAgent` already does exactly
  the needed thing. Unblocks 09's macro question; 09 still waits on 05 and 07.

- [What is a target, a log entry, and a gap?](issues/05-target-log-gap-vocabulary.md) — a target has
  a period, copies forward, and sits **outside** both trust tiers (an intention is not a claim). A log
  entry shares four things: when, tier, how much, what about. **The gap is one subtraction shown four
  ways.** One reported actual makes the whole gap reported. Shared code is a `plan/` package of words
  only — if it grows a DAO, this decision has been violated.
- [The ledger's budget](issues/06-budget-versus-actual.md) — plain subtraction, uncategorised spend
  in its own loud bucket, provisional rows counted and marked, missing coverage stated in words.
- [Categories](issues/07-categorisation.md) — fixed list; substring rules on the uppercased
  description; AI guesses only unknown merchants, behind the spend gate; **confirming a guess writes
  the rule**, so nothing is guessed twice; recategorising rewrites history.
- [Workouts](issues/08-workouts-domain.md) — loose plan (exercises per week), logged per set, gap is
  sessions done vs planned. **Bodyweight is its own thing**, not a workout field.
- [Meals](issues/09-meals-domain.md) — voice or photo, daily calorie/macro target, an unlogged day
  reads "not logged" and never zero. Cross-check with groceries deferred.
- [Cars](issues/10-cars-domain.md) — **`MaintenanceItem` already is a target**; no reshaping needed.
  Gap is whichever comes first, miles or date. OBD is a log with no target. Recaps, wrapped, drive
  logs, build sheets and telemetry are **parked**.
- [Voice logging](issues/11-voice-logging.md) — **read and write both run on hardware** (Kevin,
  2026-08-07; MEMORY.md had said otherwise since session 3). No confirm step; the assistant states
  what it wrote. Partial input asks once. Corrections via an undo tool. **Tier tagging at the tool
  layer**, so no domain can forget it.

## Not yet specified

- **Weight tracking.** Kevin named it inside workouts ("i log my workouts and my weight"). Whether
  weight is part of the workout log, its own domain, or a plain reported measurement is unclear.
- **How the AI makes a workout plan.** He wants one generated. Whether that is a one-shot sub-agent,
  a conversation, or a template is not decided, and it hangs on ticket 05's definition of a target.
- **Cross-domain checks.** "meals... maybe cross checked against groceries and food spending." The
  only genuinely novel idea in the set, and the only one that spans two domains. Needs 07 and 09
  first before the question is even sharp.
- **What the plan-versus-actual screen looks like.** Ticket 02 of the ledger effort chose
  "Instrument" as the design language; nothing has decided what a gap or a remaining-budget reads
  like in it. Blocked behind 05 and probably a `/prototype`.
- **Sync.** Every new domain adds tables, and `sync/` has still never executed. Drive OAuth is still
  blocking (MEMORY.md). Whether new domains sync from day one is unasked.
- **Where the non-record features sit.** Music, places, weather and the garage all survived, but they
  are not part of the record and have no place in the plan-versus-actual shape. They need a home in
  the story, not deletion.

## Out of scope

- **Building any domain.** This map produces decisions. Each domain's build is a separate effort.
- **The SG entity.** Kevin: "SG entity we'll do it later." US only for now.
- **Deleting anything.** The cut list came back with everything kept (2026-08-06).
- **Commercial anything.** Dead since the 2026-07-31 pivot and not re-openable here.
