---
name: prototype
description: Build a throwaway prototype to answer a design question. Use when the user wants to sanity-check whether a state model or logic feels right, or explore what a UI should look like.
---

# Prototype

A prototype is **throwaway code that answers a question**. The question decides the shape.

## Pick a branch

Identify which question is being answered - from the user's prompt, the surrounding code, or by asking if the user is around:

- **"Does this logic / state model feel right?"** -> [LOGIC.md](LOGIC.md). Drive the state machine through cases that are hard to reason about on paper, from a throwaway JVM test.
- **"What should this look like?"** -> [UI.md](UI.md). Several radically different Compose variants, flipped between with `@Preview` (and a debug variant switcher when it needs real data).

The two branches produce very different artifacts - getting this wrong wastes the whole prototype. If the question is genuinely ambiguous and the user isn't reachable, default to whichever branch better matches the surrounding code (a controller, DAO, or agent -> logic; a screen or composable -> UI) and state the assumption at the top of the prototype.

## Rules that apply to both

1. **Throwaway from day one, and clearly marked as such.** Locate the prototype code close to where it will actually be used (next to the composable or controller it's prototyping for) so context is obvious - but prefix the file `Proto` so a casual reader can see it's a prototype, not production. Don't invent a new top-level package.
2. **One command to run.** `gradlew testDebugUnitTest --tests "<pattern>"` for a logic prototype; Android Studio's `@Preview` pane, or the usual Run button onto the phone, for a UI one. Kevin runs this project from Android Studio's play button - never assume a CLI-only workflow.
3. **No persistence by default.** State lives in memory. Persistence is the thing the prototype is _checking_, not something it should depend on. If the question explicitly involves a database, hit a scratch DB or a local file with a clear "PROTOTYPE - wipe me" name.
4. **Skip the polish.** No tests, no error handling beyond what makes the prototype _runnable_, no abstractions. The point is to learn something fast.
5. **Surface the state.** After every action (logic) or on every variant switch (UI), print or render the full relevant state so the user can see what changed.
6. **Capture it when done.** Fold any validated decision into the real code, then capture the prototype itself as a **primary source**: commit it to a throwaway branch, out of `dev`, and leave a context pointer to that branch on the ticket (`.scratch/<effort>/issues/NN-<slug>.md`, see [issue-tracker.md](../issue-tracker.md)). Capture the answer too - the verdict and the question it settled. `dev` keeps only the validated decision.

## LEGION rules (ADAPTED)

These are not optional just because the code is throwaway:

- **Motion is legal, and there is no CI grep-check.** Midnight AI banned `AnimatedVisibility`, `tween(`, `infiniteTransition`, `animate*AsState`, and `Crossfade` in `ui/` because cheap head units run animator scale 0 and duration-based animation silently froze. **That constraint is gone** - LEGION is phone-only (CLAUDE.md §2, §7). Use normal Compose animation.
- **There are no design tokens yet, and that is the point.** `ui/` is a clean slate; the city-pop language died with the pivot and nothing replaced it. A UI prototype here is often *establishing* the vocabulary. If a design-language decision has landed, use it; if not, state which baseline you assumed at the top of the prototype.
- **Never prototype against a real Gemini key.** Fake the response. A prototype's job is shape, not spend, and every call bills Kevin's own key. This matters more for ledger and pantry than it did for anything in Midnight AI, because their LLM paths run over whole documents.
- **Never prototype against real financial documents you then throw away silently.** If a ledger prototype reads a real statement, say so and say where the bytes went.
- **No Room migration in a prototype.** CLAUDE.md §5 allows additive migrations only, verbatim generated SQL. If the shape question is about schema, prototype the *reasoning* in a JVM test against fakes, then let the real change go through the migration process properly.
- **The reconciliation gate is not prototypable away.** If a prototype touches an ingestion path, it must still quarantine on a total mismatch rather than writing partial data - even in throwaway code, because the throwaway code is where the wrong instinct gets learned.
- **Where prototypes live**: `app/src/test/java/com/kevin/legion/proto/` for logic, or a `Proto`-prefixed file next to the composable for UI. Both are throwaway and neither ships.
