---
map: drive-test-2026-08-18
ticket: 04
title: "What the assistant must say when it cannot do something"
type: grilling
status: open
status-detail: ""
blockers: ["03"]
blocked-by: ["[[03-no-navigation-capability]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# What the assistant must say when it cannot do something

## Question

[Ticket 03](03-no-navigation-capability.md) is one instance. **This is the general problem behind
it**, and it is the thing on this map most likely to recur.

Kevin asked for navigation. There was no tool. The model produced *"opening it"* and nothing opened.
Building the navigation tool closes that instance and closes nothing about the class.

### The rule already exists and it did not bind

`ai/AriaBrain.kt:77-78`, inside `sharedInstructions`:

> "Always call the matching tool before claiming you've done something - never say you're pulling up
> music unless you actually called the tool for it."

Verified 2026-08-18. **Its only worked example is music.** And a rule of that shape can only bind
where a tool exists to be called - "call the matching tool first" says nothing at all when there is
no matching tool. The navigation case fell straight through it.

### The codebase's own correct precedent

The garage relay (`ai/AriaBrain.kt:85-89`) is the pattern that works:

> "The garage relay is a single-button toggle: you cannot know or promise whether the door will open
> or close, so never say 'opening' or 'closing' - say 'triggering' or 'hitting' the garage."

Because the app **cannot observe the door's outcome**, the prompt does not ask the model to be
careful. It **forbids the two words that assert an outcome** and mandates two that assert only the
action taken. That is a constraint on vocabulary, not an appeal to judgement, and it is why it
holds.

This is CLAUDE.md sec 4's reconciliation posture applied to speech, by analogy: **do not claim what
you did not observe.** The gate quarantines a document whose numbers it could not verify; the garage
clause quarantines a verb whose outcome it could not verify. The navigation failure is the same sin
in the same house - an unverified claim asserted as fact - and it shipped because nobody had written
the clause for it.

## Grill

1. **Does the prompt enumerate what LEGION cannot do, and does that scale?** A negative list is the
   obvious fix and the obviously fragile one: it is correct only until the next tool lands, and it
   burns tokens on every turn describing absences. Is there a version that scales, or is a
   maintained negative list simply the cost?
2. **Is a prompt rule sufficient here at all, or does this need a structural guard?** The existing
   rule is a prompt rule and it failed. Argue both sides: what would a structural guard even look
   like for free-text speech, given the model can say anything and nothing inspects the audio? If
   the honest answer is that the prompt is the only lever, say so explicitly rather than leaving it
   implied - and then the question becomes how strong the clause has to be.
3. **What should it say instead?** The garage clause's method is a forbidden-vocabulary list, not an
   instruction to be careful. Does the general case get the same treatment - a standing list of
   outcome-asserting verbs that may only follow an observed tool result - or something else?
4. **How would a regression be caught?** This one was found by a human on a motorway. What is the
   cheapest check that would have caught it earlier, and does it exist anywhere today? An eval that
   asks for capabilities the app does not have and inspects the reply is the obvious candidate;
   whether it is worth building for a two-person app is a real question, not a rhetorical one.
5. **Where does the clause live?** Per-persona in `ai/Personas.kt`, or once in
   `sharedInstructions`? The honesty rules already live inside each persona's own clause, which
   `.scratch/hands-and-senses/issues/12-assistant-identity.md` flags as a known weakness - a freeform
   persona could omit them. Do not repeat that mistake here.

## Blocked by

[Ticket 03](03-no-navigation-capability.md). Deliberately. Deciding the general clause before the
concrete instance is built means writing the rule against a hypothetical rather than against the
real tool result that ticket 03's requirement 5 produces. Build the honest tool first, then decide
what is said when there is no tool.
