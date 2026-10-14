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
| §8 frozen decisions | Is this request on the frozen-decisions list (embedded nav variants, Spotify SDK, real ML, wake word, mood engine, native split-screen, etc.)? If yes: flag it explicitly and ask if he's intentionally reopening it. |
| §5 Room | Which table does this touch, if any? New table or column? Additive migration only — confirm no destructive fallback is implied. |
| §4.3 LiveToolbox | Does this need a new voice tool? snake_case verb_noun, one responsibility — what's the exact name and which category (data read / action / sub-agent delegation)? |
| §13 UI map | Which screen(s) does this touch? New screen or existing? |
| §9 Gemini call path | BYO key direct, or through the entitlement broker (trial/subscribed)? Does `EntitlementManager` need consulting? |
| §9 pull vs push | Pull-based tool, or is this proposing pre-injected context? Tools are the default — confirm or get justification for the exception. |
| §12 motion | Any new UI animation? Confirm frame-clock only via `ui/Motion.kt`, not `AnimatedVisibility`/`tween`/`animate*AsState`/`Crossfade`. |
| Offline | Does any network path here degrade gracefully offline? What's the offline behavior exactly? |
| Edge cases | Fresh install / null state / no OBD connection / no key / first-run — what happens in each? |

Also ask about anything outside this table that's ambiguous: naming, exact copy/wording, which
existing pattern to mirror, scope boundaries (what's explicitly OUT of scope for this brief).

## Step 3: be relentless

No fixed question cap. Keep going until every branch has a concrete, unambiguous answer —
including edge cases and fresh-install/null states. Do not stop early because the request "seems
clear enough." If Kevin's answer to one question opens a new ambiguity, ask that next. Half-answers
("sure, whatever's simplest") still need a concrete follow-up: pin down what "simplest" resolves
to before moving on.

## Step 4: emit the brief and stop

When ambiguity is genuinely exhausted, emit a single EXECUTION-BRIEF in CLAUDE.md §10 shape
(execution-spec, no taste calls left open) and stop. The brief must contain:

1. **Exact files to touch** — full paths, one line each, new vs edit.
2. **Element-by-element behavior** — what each touched piece does, in enough detail that no
   judgment calls remain for whoever implements it.
3. **Edge cases** — fresh install, null/missing state, offline, no-key, no-OBD, etc., each with
   its resolved behavior.
4. **Frozen-list check result** — explicit statement of whether this touches §8, and if so, the
   confirmation that Kevin knowingly reopened it.
5. **Verification section** — what to build/run to confirm it works (`./gradlew assembleDebug`,
   relevant unit tests, on-device QA flags if hardware-dependent).

After emitting the brief: hand back to Kevin. Do not write code. Do not auto-enter plan mode. The
brief is the deliverable; what happens with it next is his call.
