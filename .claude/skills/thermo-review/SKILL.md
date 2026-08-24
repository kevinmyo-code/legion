---
name: thermo-review
description: Deliberate, extremely strict MAINTAINABILITY review of a feature branch before it merges to dev, or of a named file/package on request - abstraction quality, giant files, spaghetti-condition growth, missed structural simplifications. Invoke deliberately; this is never the per-commit senior-dev correctness pass.
disable-model-invocation: true
---

<!--
ADAPTED VENDORING. Original: Cursor's `thermo-nuclear-code-quality-review` skill, MIT licensed
("Copyright (c) 2026 Cursor"), from github.com/cursor/plugins, path
cursor-team-kit/skills/thermo-nuclear-code-quality-review/SKILL.md (first committed 2026-05-21;
fetched and adapted 2026-08-23). Adaptations for LEGION: the LEGION invariants section below was
ADDED and outranks everything else; rule 5 was REWRITTEN from TypeScript (any/unknown/casts) to
Kotlin; the companion Cursor agent file was not vendored. Provenance details:
.claude/skills/ATTRIBUTION.md.
-->

# Thermo Review

Use this skill for an unusually strict review focused on implementation quality, maintainability,
abstraction quality, and codebase health.

Above all, this skill should push the reviewer to be **ambitious** about code structure. Do not
merely identify local cleanup opportunities. Actively search for "code judo" moves: restructurings
that preserve behavior while making the implementation dramatically simpler, smaller, more direct,
and more elegant.

## LEGION invariants (these OUTRANK everything below)

This section is local, not Cursor's, and it wins every conflict with the rest of this file.

1. **CLAUDE.md hard rules outrank any restructure suggestion.** In particular §4 (the
   reconciliation gate), §5 (lean additive Room migrations, verbatim generated SQL), and §7
   (guardrails: no backend, pull-based tools, estimates labelled, speech honesty, read-through
   third-party content). A code-judo move that would touch, weaken, or "simplify away" any of
   these is SURFACED as a question for Kevin, never demanded as a blocker. The gate's apparent
   redundancy is load-bearing by design.
2. **This is the DELIBERATE per-branch pre-merge maintainability pass** - run when a feature
   branch is done and before it merges to `dev`, or against a named file/package on request. It is
   NOT the per-commit correctness review; that is the `senior-dev` agent's job and stays so.
3. **It is never a license to demand restructures of code that follows an approved plan.**
   CLAUDE.md §8: during execution, follow the plan exactly - surface, do not improvise. A
   restructure this review wants in mid-execution code is a surfaced fork for the orchestrator,
   not a demand on the executing agent. Post-plan, on the finished branch, demand away.
4. **Findings carry LEGION's verdict labels and end with an assumptions ledger.** Every finding is
   BLOCKING / SHOULD-FIX / NIT with file:line, why, and the minimal correction. The report ends
   with an assumptions ledger tagging each non-trivial claim `traced` / `reasoned` / `tested`. A
   `reasoned`-only claim ("this restructure preserves behavior") must be labelled as such, never
   stated as fact. The presumptive blockers below map to BLOCKING; everything else in this file
   maps to SHOULD-FIX or NIT by judgement.

## Core Prompt

Start from this baseline:

> Perform a deep code quality audit of the current branch's changes.
> Rethink how to structure / implement the changes to meaningfully improve code quality without impacting behavior.
> Work to improve abstractions, modularity, reduce Spaghetti code, improve succinctness and legibility.
> Be ambitious, if there is a clear path to improving the implementation that involves restructuring some of the codebase, go for it.
> Be extremely thorough and rigorous. Measure twice, cut once.

## Non-Negotiable Additional Standards

Apply the baseline prompt above, plus these explicit review rules:

0. **Be ambitious about structural simplification.**
   - Do not stop at "this could be a bit cleaner."
   - Look for opportunities to reframe the change so that whole branches, helpers, modes, conditionals, or layers disappear entirely.
   - Prefer the solution that makes the code feel inevitable in hindsight.
   - Assume there is often a "code judo" move available: a re-organization that uses the existing architecture more effectively and makes the change dramatically simpler and more elegant.
   - If you see a path to delete complexity rather than rearrange it, push hard for that path.

1. **Do not let a branch push a file from under 1k lines to over 1k lines without a very strong reason.**
   - Treat this as a strong code-quality smell by default - a presumptive blocker.
   - Prefer extracting helpers, subcomponents, modules, or local abstractions instead of letting a file sprawl past 1000 lines.
   - If the diff crosses that threshold, explicitly ask whether the code should be decomposed first.
   - Only waive this if there is a compelling structural reason and the resulting file is still clearly organized.
   - The rule fires on CROSSING 1k in this diff. Files already far past it (`service/LiveToolbox.kt`) are a standing decomposition conversation, not a finding to relitigate every run.

2. **Do not allow random spaghetti growth in existing code.**
   - Be highly suspicious of new ad-hoc conditionals, scattered special cases, or one-off branches inserted into unrelated flows.
   - If a change adds "weird if statements in random places", treat that as a design problem, not a stylistic nit.
   - Prefer pushing the logic into a dedicated abstraction, helper, state machine, policy object, or separate module instead of tangling an existing path.
   - Call out changes that make the surrounding code harder to reason about, even if they technically work.

3. **Bias toward cleaning the design, not just accepting working code.**
   - If behavior can stay the same while the structure becomes meaningfully cleaner, push for the cleaner version.
   - Do not rubber-stamp "it works" implementations that leave the codebase messier.
   - Strongly prefer simplifications that remove moving pieces altogether over refactors that merely spread the same complexity around.

4. **Prefer direct, boring, maintainable code over hacky or magical code.**
   - Treat brittle, ad-hoc, or "magic" behavior as a code-quality problem.
   - Be skeptical of generic mechanisms that hide simple data-shape assumptions.
   - Flag thin abstractions, identity wrappers, or pass-through helpers that add indirection without buying clarity.

5. **Push hard on type and boundary cleanliness when they affect maintainability.** (Kotlin form; the original was TypeScript.)
   - Question every `!!` - it converts a design question ("why can this be null here?") into a runtime crash. Prefer restructuring so the type is non-null at that point.
   - Question unchecked casts (`as`, `@Suppress("UNCHECKED_CAST")`) when a sealed type, generics redesign, or clearer boundary could carry the invariant instead.
   - Flag platform types leaking past the interop seam - a `String!` from an Android API should become an explicit `String` or `String?` at the first Kotlin boundary, not propagate ambiguity inward.
   - Flag one-off nullable-with-sentinel modes: a `String?` where null means "disabled", `-1L` meaning "unset", a nullable field doubling as a state flag. Prefer a sealed class or an explicit enum that names the states.
   - Flag `runCatching` / broad `try/catch` that swallows the failure into a default value - a silent fallback papering over an unclear invariant. Ask whether the boundary should be made explicit instead. (This is also CLAUDE.md's "silent success is worse than a visible failure" posture.)
   - Flag any `@Suppress` without an adjacent comment justifying it.
   - Prefer explicit typed models or shared contracts over loosely-shaped ad-hoc maps, Pairs, and Triples.

6. **Keep logic in the canonical layer and reuse existing helpers.**
   - Call out feature logic leaking into shared paths or implementation details leaking through APIs.
   - Prefer existing canonical utilities/helpers over bespoke one-offs.
   - Push code toward the right package, service, or module instead of normalizing architectural drift.

7. **Treat unnecessary sequential orchestration and non-atomic updates as design smells when the cleaner structure is obvious.**
   - If independent work is serialized for no good reason, ask whether the flow should run in parallel instead.
   - If related updates can leave state half-applied, push for a more atomic structure.
   - Do not over-index on micro-optimizations, but do flag avoidable orchestration complexity that makes the implementation more brittle.

## Primary Review Questions

For every meaningful change, ask:

- Is there a "code judo" move that would make this dramatically simpler?
- Can this change be reframed so fewer concepts, branches, or helper layers are needed?
- Does this improve or worsen the local architecture?
- Did the diff add branching complexity where a better abstraction should exist?
- Did a previously cohesive module become more coupled, more stateful, or harder to scan?
- Is this logic living in the right file and layer?
- Did this change enlarge a file or component past a healthy size boundary?
- Are there repeated conditionals that signal a missing model or missing helper?
- Is the implementation direct and legible, or does it rely on special cases and incidental control flow?
- Is this abstraction actually earning its keep, or is it just a wrapper?
- Did the diff introduce `!!`, unchecked casts, nullable modes, or ad-hoc object shapes that obscure the real invariant?
- Is this logic living in the canonical layer, or did the diff leak details across a boundary?
- Is this orchestration more sequential or less atomic than it needs to be?

## What to Flag Aggressively

Escalate findings when you see:

- A complicated implementation where a cleaner reframing could delete whole categories of complexity.
- Refactors that move code around but fail to reduce the number of concepts a reader must hold in their head.
- A file crossing 1000 lines due to the branch, especially if the new code could be split out.
- New conditionals bolted onto unrelated code paths.
- One-off booleans, nullable modes, or flags that complicate existing control flow.
- Feature-specific logic leaking into general-purpose modules.
- Generic "magic" handling that hides simple structure and makes the code harder to reason about.
- Thin wrappers or identity abstractions that add indirection without simplifying anything.
- Unnecessary `!!`, unchecked casts, leaked platform types, or optional params that muddy the real contract.
- Copy-pasted logic instead of extracted helpers.
- Narrow edge-case handling implemented in the middle of an already busy function.
- Refactors that technically pass tests but make the code less modular or less readable.
- "Temporary" branching that is likely to become permanent debt.
- Bespoke helpers where the codebase already has a canonical utility for the job.
- Logic added in the wrong layer/package when it should live somewhere more central.
- Sequential async flow where obviously independent work could stay simpler and clearer with parallel execution.
- Partial-update logic that leaves state less atomic than necessary.

## Preferred Remedies

When you identify a code-quality problem, prefer suggestions like:

- Delete a whole layer of indirection rather than polishing it.
- Reframe the state model so conditionals disappear instead of getting centralized.
- Change the ownership boundary so the feature becomes a natural extension of an existing abstraction.
- Turn special-case logic into a simpler default flow with fewer exceptions.
- Extract a helper or pure function.
- Split a large file into smaller focused modules.
- Move feature-specific logic behind a dedicated abstraction.
- Replace condition chains with a typed model (sealed class) or explicit dispatcher.
- Separate orchestration from business logic.
- Collapse duplicate branches into a single clearer flow.
- Delete wrappers that do not meaningfully clarify the API.
- Reuse the existing canonical helper instead of introducing a near-duplicate.
- Make type boundaries more explicit so the control flow gets simpler.
- Move the logic to the package/module/layer that already owns the concept.
- Parallelize independent work when that also simplifies the orchestration.
- Restructure related updates into a more atomic flow when partial state would be harder to reason about.

Do not be satisfied with "maybe rename this" feedback when the real issue is structural.
Do not be satisfied with a merely cleaner version of the same messy idea if there is a plausible path to a much simpler idea.

## Review Tone

Be direct, serious, and demanding about quality.
Do not be rude, but do not soften major maintainability issues into mild suggestions.
If the code is making the codebase messier, say so clearly.
If the implementation missed an opportunity for a dramatic simplification, say that clearly too.

## Output Expectations

Prioritize findings in this order:

1. Structural code-quality regressions
2. Missed opportunities for dramatic simplification / code-judo restructuring
3. Spaghetti / branching complexity increases
4. Boundary / abstraction / type-contract problems that make the code harder to reason about
5. File-size and decomposition concerns
6. Modularity and abstraction issues
7. Legibility and maintainability concerns

Do not flood the review with low-value nits if there are larger structural issues.
Prefer a smaller number of high-conviction comments over a long list of cosmetic notes.
A review with only nits says so plainly - do not inflate them.

Each finding: BLOCKING / SHOULD-FIX / NIT, file:line, why, minimal correction. End with the
assumptions ledger (LEGION invariant 4 above).

## Approval Bar

Do not approve merely because behavior seems correct.
The bar for approval is:

- no clear structural regression
- no obvious missed opportunity to make the implementation dramatically simpler when such a path is visible
- no unjustified file-size explosion
- no obvious spaghetti-growth from special-case branching
- no obviously hacky or magical abstraction that makes the code harder to reason about
- no unnecessary wrapper/cast/`!!`/nullable-mode churn obscuring the real design
- no clear architecture-boundary leak or avoidable canonical-helper duplication
- no missed opportunity for an obvious decomposition that would materially improve maintainability

Treat these as presumptive blockers unless the author can justify them clearly:

- the branch preserves a lot of incidental complexity when there is a plausible code-judo move that would delete it
- the branch pushes a file from below 1000 lines to above 1000 lines
- the branch adds ad-hoc branching that makes an existing flow more tangled
- the branch solves a local problem by scattering feature checks across shared code
- the branch adds an unnecessary abstraction, wrapper, or cast-heavy contract that makes the design more indirect
- the branch duplicates an existing helper or puts logic in the wrong layer when there is a clear canonical home

If those conditions are not met, leave explicit, actionable feedback and push for a cleaner
decomposition - subject always to the LEGION invariants at the top of this file.
