---
name: grillme
description: "User-invoked only. When Kevin types /grillme <prompt>, refuse to implement and instead run a relentless one-question-at-a-time interview that sharpens a half-baked prompt into a tight execution-brief for this project's plan-mode/Sonnet pipeline."
disable-model-invocation: true
---

# grillme

## Core principle

Kevin typed `/grillme <prompt>` because the prompt is half-baked. Your job is NOT to build it,
NOT to plan it, and NOT to guess at what he means. Your job is to interrogate the request against
this repo's actual decision surfaces until it is fully specified, then hand back a brief. Nothing
else.

## Step 1: STOP

Do not implement. Do not enter plan mode. Do not write or edit any code, config, or files. Do not
propose an approach yet. Say plainly that you're entering interview mode and that no code will be
written until the brief is done.

## Step 2: interview, one question at a time

Rules for every question:

- Exactly ONE question per turn. Never batch multiple questions.
- Ask the hardest, most-blocking ambiguity first — the one where a wrong guess would waste the
  most downstream work.
- Every question carries a recommended default so Kevin can answer fast: "yes", "the first one",
  "option A", etc. State the default and why it's the default.
- **Never ask for a fact you could look up.** If the answer lives in the environment — the
  filesystem, the code, git history, CLAUDE.md, MEMORY.md, a tool — go and find it instead of
  spending a turn on it. The *decisions* are Kevin's and only Kevin's; the *facts* are your job.
  Asking him what a function already says is how a grilling loses its authority.
- Wait for his answer before asking the next question. Do not move on until the current branch is
  resolved.

Grill against THIS repo's real decision surfaces, citing CLAUDE.md section numbers when relevant:

| Check | Question shape |
|---|---|
| §2 locked decisions | Does this touch a locked pivot decision (phone-only, no commercial model, no backend, Drive-BYO as the only store, clone-and-run, one global assistant identity, city-pop dead, LLM-only-behind-a-gate)? If yes: flag it explicitly and ask if he's intentionally reopening it. |
| §4 the gate | Does this touch an ingestion path? What does it reconcile against, and what happens on a mismatch? "Quarantine the whole document, write nothing" is the only acceptable answer unless he overrides it deliberately. |
| §4 estimates | Does this surface any value the source document never stated (macros, a cost projection, a category guess)? Then how is it labelled as an estimate, in the tool description AND on screen? |
| Money | Any money in this? `Long` cents, never `Double`. Confirm the whole path, including anything that formats or parses. |
| §5 Room | Which table does this touch, if any? New table or column? Additive migration only, verbatim generated SQL - confirm no destructive fallback is implied. |
| Aspect boundary | Which aspect owns this: fleet, ledger, or pantry? Or is it shell/shared? A feature that spans two aspects needs its seam named explicitly. |
| Voice tool | Does this need a new tool in `LiveToolbox`? snake_case verb_noun, one responsibility - what's the exact name and which category (data read / action / sub-agent delegation)? |
| UI | Which screen does this touch? `ui/` is a clean slate with no design language chosen, so "which screen" may itself be an open question - surface that rather than assuming one exists. |
| §7 no backend | Does anything here want a server, a shared key, or a Firestore? That is a refusal, not a design option. Where does the state live instead - on-device or the user's own Drive? |
| Clone-and-run | Would this still work for a stranger who clones the repo and builds with their own signing cert? |
| Offline | Does any network path here degrade gracefully offline? What's the offline behavior exactly? |
| Edge cases | Fresh install / null state / no key / no OBD connection / no Drive folder connected / first-run - what happens in each? |

Also ask about anything outside this table that's ambiguous: naming, exact copy/wording, which
existing pattern to mirror, scope boundaries (what's explicitly OUT of scope for this brief).

## Step 3: be relentless

No fixed question cap. Keep going until every branch has a concrete, unambiguous answer —
including edge cases and fresh-install/null states. Do not stop early because the request "seems
clear enough." If Kevin's answer to one question opens a new ambiguity, ask that next. Half-answers
("sure, whatever's simplest") still need a concrete follow-up: pin down what "simplest" resolves
to before moving on.

## Step 4: emit the brief and stop

When ambiguity is genuinely exhausted, emit a single EXECUTION-BRIEF in CLAUDE.md §8 shape
(execution-spec, no taste calls left open) and stop. The brief must contain:

1. **Exact files to touch** — full paths, one line each, new vs edit.
2. **Element-by-element behavior** — what each touched piece does, in enough detail that no
   judgment calls remain for whoever implements it.
3. **Edge cases** — fresh install, null/missing state, offline, no-key, no-OBD, no Drive folder
   connected, each with its resolved behavior.
4. **Locked-decision check result** — explicit statement of whether this touches CLAUDE.md §2, and
   if so, the confirmation that Kevin knowingly reopened it.
5. **Verification section** — what to build/run to confirm it works
   (`./gradlew compileDebugKotlin -Pnokey`, `./gradlew testDebugUnitTest`, and on-device QA steps
   where behavior can only be confirmed by running it; ADB works on this project's phone, so
   on-device is a task rather than an excuse).
6. **Verification tags on the brief's own claims.** Where the brief asserts an API fact, say whether
   it was traced in the code or inferred. A specialist implements a brief exactly as written and has
   no way to tell a verified claim from a confident one, and this project has already been bitten by
   exactly that (a signature confirmed by `javap`, its unit assumed and wrong).

After emitting the brief: hand back to Kevin. Do not write code. Do not auto-enter plan mode. The
brief is the deliverable; what happens with it next is his call.
