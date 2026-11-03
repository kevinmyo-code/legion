---
title: Working with AI coding agents
tags: [docs]
---

# How LEGION is built with AI coding agents

Kevin builds LEGION with AI coding agents: Claude Code sessions that plan, write, review and test
the code, while Kevin makes the decisions. This page describes that workflow as the repo itself
records it. Every claim here points at a file in the repo; the numbers are counted from the repo,
and the method is given next to each one.

The binding rules live in `CLAUDE.md` (section 8, Working Model; sections 12 and 13, the vault and
docs layers) and `TEAM.md` (the agent roster). This page summarises them for a reader from outside;
where they disagree, those files win.

## The short version

- **Kevin decides, agents build.** Standing rules carry the name and date of whoever decided them,
  and the decisions are Kevin's. Agents turn rulings into specs, code, tests and docs.
- **The loop is decide, spec, build in parallel, verify, record.** Large efforts are charted as a
  map of decision tickets; each resolved decision leaves a build ticket; anything ready is built.
- **Several agents run at once, each in its own git worktree,** so they never share a checkout.
- **Every agent report ends with an assumptions ledger** that tags each claim with how it was
  checked. An inferred claim is never relayed as a fact.
- **Mistakes become rules.** A failure is written up as a numbered lesson, and the lesson closes
  only when its rule is written into a prompt or a check that the next agent actually reads.

## The tools

- **Claude Code** is the agent runtime: one main session (the orchestrator) that talks to Kevin and
  dispatches project subagents defined in `.claude/agents/`.
- **Models, as the repo names them.** `CLAUDE.md` says: plan with a strong model (Fable or Opus) in
  plan mode, execute approved plans with Sonnet. The subagent definitions pin `model: sonnet`. The
  commit history shows the real mix (see the numbers below): most commits are co-authored by Opus,
  with Sonnet and Fable behind.
- **Hooks** in `.claude/settings.json` run before tool calls: a commit hook, destructive-command
  and generated-file guards, and two warnings. See "How quality is checked".
- **Project MCP servers** in `tools/mcp/` give agents typed access to the ticket board, Gradle runs
  and test totals, and the engine.
- **Skills** in `.claude/skills/`: vendored Kotlin and Compose guidance, plus four repo-specific
  planning skills: `wayfinder`, `to-spec`, `grillme` and `issue-tracker.md`.

## The loop: decide, spec, build in parallel, verify, record

1. **Decide.** A loose idea too big for one session is charted with the `wayfinder` skill as a
   map: `.scratch/<effort>/map.md` plus numbered decision tickets under `.scratch/<effort>/issues/`.
   Decisions are resolved one at a time, usually by interview: the `grillme` skill refuses to
   implement and instead asks one question at a time until a half-formed request is a tight brief.
2. **Spec.** The `to-spec` skill turns the conversation into a spec. Plans are execution specs:
   exact file names, signatures, edge cases and a verification section, with no taste calls left
   to whoever executes them (`CLAUDE.md` section 8).
3. **Build in parallel.** A resolved decision that authorises code must leave a build ticket behind
   in the same commit. **Anything ready is built, not parked** (Kevin's standing ruling): a ready
   ticket with no open decisions is unstarted work, and building may run in parallel with grilling
   the next effort.
4. **Verify.** The real build and the real test suite, not a grep. One logical change per commit,
   with compile and unit tests green before each commit. Anything that only hardware can settle is
   marked `built` and owes a run on the real device.
5. **Record.** The ticket's status and verification detail are updated, the decision is logged, the
   board regenerates on commit, and any mistake found on the way becomes a lesson.

## How decisions are made and recorded

- **Kevin makes the rulings.** Most rules in `CLAUDE.md` and `TEAM.md` carry his name and a date; the rest are lessons, named by number, often
  with his words quoted. Agents may propose and argue; a fork the plan does not answer means stop
  and surface it, not improvise (`CLAUDE.md` section 8).
- **Two decision stores with different jobs.** `memory/library/decisions.md` says what happened and
  when. `docs/adr/` says what is binding now. Every decision gets a dated entry; only standing ones
  also get an ADR. Superseded ADRs keep their original text and link to their replacement.
- **Decisions are re-opened on merit.** The dependency-injection choice was decided twice in one
  day: the morning research said manual DI, Kevin reopened it, a survey re-argued it, and the
  second ruling (Hilt on KSP) stands with the first kept in the dated log.
- **Tickets are files with frontmatter.** Status lives in YAML (`open`, `resolved`, `built`,
  `kiv`, and a few others). `built` means built and suite green but owing a run on real hardware;
  `kiv` means parked on purpose by Kevin. `tools/obsidian_sync.py` regenerates the board and the
  per-map dependency graphs from that frontmatter, and `tools/pending_wiki.py` renders the public
  ticket board.
- **A resolved decision with no build ticket is caught.** `tools/decision_debt.py` exists because a
  fully decided feature once vanished from the board, which lists only open tickets, and looked
  finished when nothing had been built. It runs on every commit.

## What Kevin does and what agents do

| Kevin | Agents |
|---|---|
| Sets the destination and makes every ruling | Chart maps, research options, argue trade-offs |
| Answers grilling interviews | Turn answers into specs and build tickets |
| Accepts or rejects an unmet verification step, in so many words | Report every unmet step as a gate, never a footnote |
| Uses the product for real, by voice and on the phone | Build, test, review and document; drive the attached phone over adb for reproductions |
| Decides when the rules change | Write lessons and graduate them into prompts and checks |

The main session dispatches subagents by default, not as an escalation: Kevin gave that as a
standing instruction so he does not have to ask per task. Judgement still applies; a one-line fix
does not need an agent.

## The agent team

Five seats, defined in `.claude/agents/` and explained in `TEAM.md`:

| Seat | Role |
|---|---|
| `builder` | Writes code. Executes a spec exactly. |
| `auditor` | Reads code adversarially: defects, arithmetic, data integrity, diff review against the rules. Runs after a feature lands and before anything ships. |
| `device` | Anything that needs the real phone: build, install, adb, database pulls, reproducing a bug on hardware. |
| `scout` | Read-only breadth search across code, the library or the ticket maps, returning only the conclusion. |
| `frontend` | The web client's UI: studies how shipped apps solve a screen, writes notes, then designs and builds it. |

**An agent file states no fact that can go stale.** No counts, versions, package maps or device
models: only the role, guardrails that were paid for, how to check things, and the reporting
contract. This rule came from a real failure: an old roster named the wrong phone, and three agents
in one session faithfully reported testing on a device that was not attached. In the words of
`TEAM.md`: a stale instruction is not ignored, it is obeyed.

## How parallel agents avoid conflicts

- **Every concurrent agent gets its own git worktree** (Kevin's standing rule in `TEAM.md`): a
  separate checkout, build directory and Gradle daemon over a shared `.git`. The repo's guards
  assume a tree routinely has two or more agents working in it.
- **Never `git add -A` in a tree another agent is working in.** Stage your own paths and commit
  with a pathspec, so another agent's half-finished work never rides along in your commit.
- **Copy the gitignored local config into a fresh worktree** before dispatching an agent that must
  build; otherwise the build cannot find the SDK and the isolation that prevents one failure causes
  another.
- **Why these rules exist** is lesson L32: two agents in one checkout produced commits that
  contained another agent's in-flight files, a generated page documenting code that was not
  committed, and two Gradle failures that looked exactly like test failures but were one agent
  stopping the other's daemon. The agents' code was correct; the shared workspace was not.
- **Completion contract.** A turn does not end with a subagent unfinished or its result
  unrelayed. Independent dispatches go out together so they run in parallel; dependent ones wait.

## How quality is checked

- **The assumptions ledger.** Every agent report ends with each non-trivial claim tagged by how it
  was checked:

  | Tag | Means |
  |---|---|
  | `built` | Compiled |
  | `tested` | A unit or integration test ran |
  | `traced` | The code path was followed to its leaf calls |
  | `reasoned` | Inferred, not executed |
  | `on-device` | Validated on real hardware |

  A report without a ledger is sent back.
- **The relay rule.** The orchestrator carries a subagent's tag unchanged when reporting to Kevin.
  "The agent reasoned X" never becomes "X is true." Briefs going down to an agent carry tags too:
  a verified signature is not a verified meaning (lesson L3: a signature was checked, the brief
  asserted milliseconds, the value was nanoseconds).
- **Grep-clean is not done (L10).** A reconciliation pass fixed everything a grep could find, then
  the real compile caught a query on a dropped column, a deleted file core state depended on, and
  files that were never copied. Nothing greps for an absence. Run the real build.
- **A ticket's verification steps are gates, not notes (L11).** A skipped preview step let a colour
  bug ship into a first-run screen. The executing agent had reported the step as unmet; the
  failure was treating that as a footnote. Now every verification step is accounted for as done,
  deferred with a named follow-up, or impossible and why, before a ticket is reported built.
- **Hooks turn rules into checks** (`.claude/settings.json`, scripts in `tools/hooks/`):
  - Before every `git commit`: regenerate the board, the public ticket wiki, the voice guide, the
    devlog and the ADR index and stage them, so the generated layer never lags the commit. It also
    runs the decision-debt check.
  - `guard_destructive.py` blocks commands that have destroyed data here or would: uninstalling the
    app (which wipes its keystore-held key), forced pushes to the public repo, `git reset --hard`
    in a tree other agents are using.
  - `guard_generated.py` blocks hand edits to generated files.
  - `warn_long_kotlin.py` warns when a staged Kotlin file passes 1000 lines; detekt runs in the
    build with a baseline, so only new debt turns it red.
- **Docs are checked, not trusted.** `tools/docs_check.py` fails on a source path named in `docs/`
  that no longer exists, on invalid ADR frontmatter, on a one-sided supersession link and on a
  broken wikilink. `tools/voice_guide.py` fails if a voice tool has no user-facing copy.
- **Prompt rules get an eval.** `tools/evals/` is a prompt-obedience harness that turns "does the
  model follow the speech rules" into a number. It runs on demand, not in CI, because every run
  spends real API calls.
- **Test totals come from the test reports,** not from memory or an agent's summary.

## How mistakes become rules (so agents do not make the same mistake twice)

Agents are not fine-tuned. The only way an agent learns is that a lesson graduates into a prompt
surface it reads on its next run: its `.claude/agents/*.md` file, `CLAUDE.md`, or a playbook. The
ledger is `memory/library/lessons.md`, and its loop has five steps:

1. **Capture.** The assumptions ledger flags every `reasoned` claim as a candidate failure.
2. **Catch.** Review or the orchestrator finds a `reasoned` claim that turned out wrong.
3. **Distill.** One numbered entry: what was claimed, what was true, the root-cause class, and the
   rule that would have caught it. Classes include unverified reachability, false success and
   relay without verify.
4. **Write back.** The rule graduates into an agent file or `CLAUDE.md`, and the entry records
   where. An entry closes only when its rule lives in a surface something reads.
5. **Measure.** One cheap regression check per entry: a grep, a test, a hook or a review-rubric
   line, so the same class of mistake cannot silently return.

Examples where the rule became a check rather than a hope: L32 became the worktree rule in
`TEAM.md`; the forgotten decision became `tools/decision_debt.py`; L24 (the repo is ahead of its
docs) became `tools/docs_check.py`; destroyed data became `guard_destructive.py`.

## Auditing real conversations

The voice assistant is improved from recordings of real use, not from impressions. When Kevin asked
for a way to audit what was said, an agent built a conversation audit log: one line per event (what
the user said, what the assistant said, each tool call and result, interruptions), with third-party
mail content redacted and keys scrubbed. `tools/voice_audit.py` turns a capture into a readable
timeline and summary, and `tools/voice_latency.py` does the same for timings.

Each real run read through those tools produced follow-up tickets that were then built: for
example, the async-lookups map has tickets for the third run's follow-ups and for the fifth and
sixth runs' follow-ups, covering holding lines that went dead, a "still checking" nudge, sign-off
behaviour and spoken figures. The audit tool itself was fixed in the same loop.

## Honest limits

- **Agents' claims are re-verified, not trusted.** That is the point of the ledger and the relay
  rule: a `reasoned` claim stays labelled `reasoned` until something runs it.
- **The phone is the final check.** A ticket that compiles and passes its suite is `built`, not
  done, until it has run on real hardware. The README's status table separates what was verified on
  hardware from what is built and not yet run.
- **Rules came from failures.** Most of the rules above exist because the failure happened first,
  sometimes twice. The lessons file records those failures plainly, including the orchestrator's
  own.
- **Evals are not a gate.** The prompt-obedience harness costs real API calls and runs on demand.
- **Merges and pushes are ruled, not improvised.** Agents may merge `dev` into `main` and push it,
  by Kevin's ruling, and only when `dev` is green; forced pushes are blocked by a hook.

## By the numbers

Counted from the repository on 2026-11-03.

| Measure | Count | How it was computed |
|---|---|---|
| Architecture decision records | 66 | Files matching `docs/adr/[0-9]*.md`. Numbered up to 0069 with gaps; superseded ADRs are kept |
| Planning maps | 58 | Files matching `.scratch/*/map.md`. Two more effort folders hold tickets without a map file, so the board counts 60 |
| Tickets | 639 | Files matching `.scratch/*/issues/*.md` |
| Ticket statuses | 314 resolved, 146 built, 110 open, 46 closed, 17 kiv, 6 other | The `status:` frontmatter line of each ticket |
| Open work on the board | 273 | `python tools/obsidian_sync.py --check`; open counts tickets in `open`, `built` and `kiv` |
| Lessons | 45 | Headings in `memory/library/lessons.md`: 37 numbered `L1` to `L37` plus 8 dated `L-YYYY-MM-DD` entries; one further dated section holds two unnumbered lessons |
| Agent seats | 5 | Files in `.claude/agents/` |
| Skills | 44 | Directories in `.claude/skills/` |
| Commits | 1,550 | `git rev-list --count` on `dev`, first commit 2026-07-31 |
| Commits co-authored by a Claude model | 1,319 | Commits with a `Co-Authored-By: Claude` trailer: Opus 1,173, Sonnet 72, Fable 74 |
| Android unit tests | 5,379, 0 failures | README status table, 2026-10-27 |
| Server tests | 718 passed, 42 skipped, against real Postgres | README status table, 2026-09-16, not recounted |

## Same approach at work

At his data-engineering internship, Kevin runs multi-agent development with Claude Code, plus a
weekly review that turns repeated agent mistakes into automated checks.
