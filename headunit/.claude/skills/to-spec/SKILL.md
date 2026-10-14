---
name: to-spec
description: Turn the current conversation into a spec and publish it to .scratch/<feature>/spec.md — no interview, just synthesis of what you've already discussed.
disable-model-invocation: true
---

This skill takes the current conversation context and codebase understanding and produces a spec (you may know this document as a PRD). Do NOT interview the user — just synthesize what you already know.

This repo's tracker is local markdown: see [issue-tracker.md](../issue-tracker.md). Publish the spec
to `.scratch/<feature-slug>/spec.md`. There is no triage label vocabulary here (the `triage` skill is
not installed), so skip any labelling step. `/setup-matt-pocock-skills` is not installed either.

The spec produced here must satisfy CLAUDE.md §10: plans are execution-specs (exact hex, dp sizes,
file names, layout element-by-element, edge cases, verification section), with no taste calls left
for whoever executes. If a section below would leave a judgment call open, it is not done. Where this
template and §10 disagree, §10 wins.

## Process

1. Explore the repo to understand the current state of the codebase, if you haven't already. This project's vocabulary and locked decisions live in CLAUDE.md (§1 identity, §4.3 the voice-tool map, §5 the Room tables, §12 the design language) and its decision log is `memory/library/decisions.md` — use that vocabulary throughout the spec and respect both. Check the spec against CLAUDE.md §8 frozen decisions and flag explicitly if it touches one.

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

Required by CLAUDE.md §10. What to build and run to confirm this works: `gradlew assembleDebug`,
the relevant unit tests (`gradlew testDebugUnitTest`), and any `connectedDebugAndroidTest` if a Room
migration is touched. Anything that can only be confirmed on real hardware (OBD, voice, head-unit
launcher, BT audio) is listed separately as an on-device QA checklist, since this project's testable
surface is narrower than its shipped surface.

## Further Notes

Any further notes about the feature.

</spec-template>
