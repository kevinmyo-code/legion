---
name: to-spec
description: Turn the current conversation into a spec and publish it to .scratch/<feature>/spec.md — no interview, just synthesis of what you've already discussed.
disable-model-invocation: true
---

This skill takes the current conversation context and codebase understanding and produces a spec (you may know this document as a PRD). Do NOT interview the user — just synthesize what you already know.

This repo's tracker is local markdown: see [issue-tracker.md](../issue-tracker.md). Publish the spec
to `.scratch/<feature-slug>/spec.md`. There is no triage label vocabulary here (the `triage` skill is
not installed), so skip any labelling step. `/setup-matt-pocock-skills` is not installed either.

The spec produced here must satisfy CLAUDE.md §8: plans are execution-specs (exact file names,
signatures, dp sizes, edge cases, verification section), with no taste calls left for whoever
executes. If a section below would leave a judgment call open, it is not done. Where this template
and §8 disagree, §8 wins.

## Process

1. Explore the repo to understand the current state of the codebase, if you haven't already. This project's vocabulary and locked decisions live in CLAUDE.md (§1 identity and the three aspects, §2 the locked pivot decisions, §4 the reconciliation gate, §5 the Room tables, §6 the codebase map) and its decision log is `memory/library/decisions.md` — use that vocabulary throughout the spec and respect both. Check the spec against CLAUDE.md §2 and flag explicitly if it touches a locked decision. **Note that most of `memory/library/` is FROZEN Midnight AI history carrying a status banner; do not import vocabulary or constraints from a frozen shelf.**

2. Sketch out the seams at which you're going to test the feature. Existing seams should be preferred to new ones. Use the highest seam possible. If new seams are needed, propose them at the highest point you can. The fewer seams across the codebase, the better - the ideal number is one.

Check with the user that these seams match their expectations.

3. Write the spec using the template below, then publish it to `.scratch/<feature-slug>/spec.md`. No triage label applies in this repo.

<spec-template>

## Problem Statement

The problem that the user is facing, from the user's perspective.

## Solution

The solution to the problem, from the user's perspective.

## User Stories

A LONG, numbered list of user stories. Each user story should be in the format of:

1. As an <actor>, I want a <feature>, so that <benefit>

<user-story-example>
1. As a mobile bank customer, I want to see balance on my accounts, so that I can make better informed decisions about my spending
</user-story-example>

This list of user stories should be extremely extensive and cover all aspects of the feature.

## Implementation Decisions

A list of implementation decisions that were made. This can include:

- The modules that will be built/modified
- The interfaces of those modules that will be modified
- Technical clarifications from the developer
- Architectural decisions
- Schema changes
- API contracts
- Specific interactions

Do NOT include specific file paths or code snippets. They may end up being outdated very quickly.

Exception: if a prototype produced a snippet that encodes a decision more precisely than prose can (state machine, reducer, schema, type shape), inline it within the relevant decision and note briefly that it came from a prototype. Trim to the decision-rich parts — not a working demo, just the important bits.

## Testing Decisions

A list of testing decisions that were made. Include:

- A description of what makes a good test (only test external behavior, not implementation details)
- Which modules will be tested
- Prior art for the tests (i.e. similar types of tests in the codebase)

## Out of Scope

A description of the things that are out of scope for this spec.

## Verification

Required by CLAUDE.md §8. What to build and run to confirm this works:
`gradlew compileDebugKotlin -Pnokey`, the relevant unit tests (`gradlew testDebugUnitTest`), and any
`connectedDebugAndroidTest` if a Room migration is touched.

Anything that can only be confirmed on a real device (OBD, voice, camera, Drive authorization, a
real statement PDF) is listed separately as an on-device QA checklist. **ADB works on this project's
phone, so an on-device item is a task, not an excuse** - do not let "verified by compile" stand in
for behavior that a five-minute install would settle. Nothing in this app has ever run on hardware,
so the on-device list is the honest part of the spec.

## Further Notes

Any further notes about the feature.

</spec-template>
