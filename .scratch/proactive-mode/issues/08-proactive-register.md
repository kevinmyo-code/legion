---
map: proactive-mode
ticket: 08
title: How Alfred sounds when nobody asked him anything
type: grilling
status: resolved
status-detail: "2026-08-21, Kevin - one shared clause, silent fixed-window suppression"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# How Alfred sounds when nobody asked him anything

## Question

*"It's past 10pm, perhaps rest is in order"* is the whole brief in one line: dry, deferential, easy
to ignore. It **offers** rather than instructs, and it never nags twice.

The assistant's core register already exists - `ai/Personas.kt` ships ALFRED and DOROTHY with full
clauses (verified 2026-08-16; the "voice not written" claim was false). **This ticket does not
redefine that voice.** It writes the proactive-specific rules that sit on top of it, and hands them
to whatever owns the persona copy.

Decide:

1. **The proactive rules**, as clauses a persona must obey when it initiates rather than answers.
   Proposed, to accept or redraw: never repeat a declined nudge; never escalate tone across
   attempts; one line, never a paragraph; always offer, never instruct; **never mention that it is
   the second time**.
2. **How a raise differs by category.** A Safety warning at 3am and a Wellbeing rest nudge cannot
   share a register - one should be flat and urgent, the other soft and skippable. **Is the register
   a property of the category?**
3. **Does a declined nudge change the next one?** "Never nag twice" implies memory of the refusal,
   which is fog on this map. If the answer is that Alfred simply never repeats within a window,
   say so and keep it stateless.
4. **What Alfred says when Kevin asks "why did you say that?"** - if [the trigger
   engine](02-trigger-engine.md) carries a reason, this is where its wording is decided.
5. **Where these clauses live.** `Personas.kt` per-persona, or one shared proactive clause that
   every persona inherits? **The honesty rules already live inside each persona's own clause**
   (`.scratch/hands-and-senses/issues/12-assistant-identity.md`), which is a known weakness - a
   freeform persona could omit them. **Do not repeat that mistake here.**

## Resolution - 2026-08-21 (Kevin, 2 calls; the rest follows from the map)

### 5, taken first because it decides the shape: ONE shared clause every persona inherits

This ticket named the trap itself - the honesty rules already live inside each persona's own clause,
so a freeform persona could omit them - and said do not repeat that mistake. It is not repeated.

`PROACTIVE_CLAUSE` sits at **file scope** in `ai/AriaBrain.kt`, exactly where `CANNOT_CLAUSE` sits and
for exactly the same reason: written once, appended by the resolver, unskippable, and it survives a
persona nobody has written yet. Never per-persona, never in `Personas.kt`.

The third option - register as a property of the CATEGORY rather than the persona - is the more
correct answer and is **not lost, only deferred**: a 3am Safety warning and a Wellbeing rest nudge
genuinely cannot share a delivery. It needs the categories built
([ticket 04](04-categories-storage-and-surface.md)) and it belongs on top of this clause, not instead
of it. Filed under "not yet specified" on the map.

### 3. A brush-off suppresses that rule for a fixed window, silently

A no means that rule is quiet for a set period. When it returns, **the tone is identical to the first
time** - it never mentions there was a first time, never escalates, never softens into pleading.

This is what makes proposed clause "never mention that it is the second time" enforceable rather than
aspirational: there is no state in the prompt for the model to leak, because the suppression is
handled before the raise ever reaches it.

Stateless-within-a-window was chosen over "suppress until the underlying fact changes" (sharper, but
needs per-rule re-arm design) and over "a no kills the rule forever" (one bad night silently loses a
nudge Kevin wanted).

### 1. The clauses

Accepted as proposed, with one addition forced by [ticket 03](03-compulsion-test.md):

- One line. Never a paragraph.
- Always offer, never instruct.
- Never repeat a declined nudge inside its suppression window, and **never reference that a previous
  attempt happened**.
- Never escalate tone across attempts.
- **A goal nudge may name the goal, its deadline, or its next action. It may never characterise how
  long it has gone unattended.** This clause is carrying real weight: ticket 03 ruled the ignored-goal
  nudge PERMITTED and pushed the useful/guilt line into tone, so this sentence is the only thing
  standing where a checkable rule would otherwise have stood. **It is a prompt rule and therefore the
  weakest lever available** - the same limit `CANNOT_CLAUSE` documents about itself.

### 2. Register by category - deferred, see call 5.

### 4. "Why did you say that?"

[Ticket 02](02-trigger-engine.md) settled that every raise carries its reason, so the answer is a
stored fact rather than a reconstruction. Wording: **name the rule and the fact that fired it, in one
line, and never justify the nudge itself.** "Your calendar had nothing after six and your sleep target
is eight hours" - not "I thought you seemed tired."

The distinction matters because a justification is unfalsifiable and a fact is checkable, which is
the same split the reconciliation gate makes (CLAUDE.md section 4).

## Built - 2026-08-21

**Status stays `resolved`, not `built`.** This was a DECISION ticket and the decision is what it
owed; the code is recorded here so the two are not confused. `built` on this map means a ticket
whose own deliverable was code and which is waiting on hardware.

Landed in `f9201c7` (Room v28 storage), `2243b85` (typed raise, gate, register clause, settings
rows) and `f1eff72` (delivery). Suite green at 1794 tests.

**Nothing in this effort has run on the phone**, and these are the parts a suite cannot reach:

- whether the assistant actually obeys `PROACTIVE_CLAUSE` - nothing inspects the spoken audio, the
  same limit `CANNOT_CLAUSE` documents about itself;
- whether screen-on plus the live-calendar check picks the right moments in real use;
- whether the Room-backed switches and the raise history survive the `START_STICKY` restart they
  exist to survive;
- whether a fired reminder now delivers exactly once - a change to behaviour Kevin already had,
  rather than a pure addition.

**Calls 3 and 4 are wired 2026-08-21.** Suppression fires for real (see ticket 05), and `why_did_you_say_that` reads the stored rule and fact off the raise row rather than letting the model reconstruct one.
