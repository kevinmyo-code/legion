---
name: prototype
description: Build a throwaway prototype to answer a design question. Use when the user wants to sanity-check whether a state model or logic feels right, or explore what a UI should look like.
---

# Prototype

A prototype is **throwaway code that answers a question**. The question decides the shape.

## Pick a branch

Identify which question is being answered — from the user's prompt, the surrounding code, or by asking if the user is around:

- **"Does this logic / state model feel right?"** → [LOGIC.md](LOGIC.md). Drive the state machine through cases that are hard to reason about on paper, from a throwaway JVM test.
- **"What should this look like?"** → [UI.md](UI.md). Several radically different Compose variants, flipped between with `@Preview` (and a debug variant switcher when it needs real data).

The two branches produce very different artifacts — getting this wrong wastes the whole prototype. If the question is genuinely ambiguous and the user isn't reachable, default to whichever branch better matches the surrounding code (a controller, DAO, or agent → logic; a screen or composable → UI) and state the assumption at the top of the prototype.

## Rules that apply to both

1. **Throwaway from day one, and clearly marked as such.** Locate the prototype code close to where it will actually be used (next to the composable or controller it's prototyping for) so context is obvious — but prefix the file `Proto` so a casual reader can see it's a prototype, not production. Don't invent a new top-level package.
2. **One command to run.** `gradlew testDebugUnitTest --tests "<pattern>"` for a logic prototype; Android Studio's `@Preview` pane, or Kevin's usual Run button onto the emulator, for a UI one. Kevin runs this project from Android Studio's play button — never assume a CLI-only workflow.
3. **No persistence by default.** State lives in memory. Persistence is the thing the prototype is _checking_, not something it should depend on. If the question explicitly involves a database, hit a scratch DB or a local file with a clear "PROTOTYPE — wipe me" name.
4. **Skip the polish.** No tests, no error handling beyond what makes the prototype _runnable_, no abstractions. The point is to learn something fast.
5. **Surface the state.** After every action (logic) or on every variant switch (UI), print or render the full relevant state so the user can see what changed.
6. **Capture it when done.** Fold any validated decision into the real code, then capture the prototype itself as a **primary source**: commit it to a throwaway branch, out of main, and leave a context pointer to that branch on the ticket (`.scratch/<effort>/issues/NN-<slug>.md`, see [issue-tracker.md](../issue-tracker.md)). Capture the answer too — the verdict and the question it settled. The main branch keeps only the validated decision.

## MIDNIGHT AI rules (ADAPTED)

These are not optional just because the code is throwaway:

- **The motion ban is CI-enforced.** CLAUDE.md §9 bans `AnimatedVisibility`, `tween(`, `infiniteTransition`, `animate*AsState`, and `Crossfade` anywhere in `ui/` (only `ui/Motion.kt` is excepted), and a CI grep-check fails on them. A prototype living under `ui/` that reaches for `tween` **will break the build**. Use `ui/Motion.kt` (`animPhase`/`triangle`/`wave`/`rememberRamp`), or keep the prototype out of `ui/`. Head units run animator scale 0, so duration-based animation is a silent freeze on the actual target hardware anyway — a prototype that uses it is lying to you.
- **Use the design tokens.** `AriaColors` / `AriaType` / `AriaDimens`, never ad-hoc hex. A prototype in the wrong palette answers the wrong question, because §12's coherence law is the thing being judged.
- **Read the references before a UI prototype.** `C:\Users\kevin\OneDrive\Pictures\UI Style References` — re-read before designing surfaces or generating art, per §12.
- **Never prototype against a real Gemini key or the broker.** Fake the response. A prototype's job is shape, not spend, and every Live call bills someone.
- **No Room migration in a prototype.** §5 allows additive migrations only, verbatim generated SQL. If the shape question is about schema, prototype the *reasoning* in a JVM test against fakes, then let the real change go through the migration process properly.
- **Where prototypes live**: `app/src/test/java/com/kevin/midnightai/proto/` for logic, or a `Proto`-prefixed file next to the composable for UI. Both are throwaway and neither ships.
