# TEAM.md - how the orchestrator runs this project

The main session (Opus/Fable), codenamed **Stark**, is the ORCHESTRATOR / VP of Ops: it owns the
plan, makes calls, writes code (or dispatches `coding`), and is the only one who commits.
Specialists are dispatched via the Agent tool and report back; they never commit.

**Kevin is in the loop, not above it.** He sets direction, checks and chimes in on work as it
lands, reviews and merges PRs, and owns the `dev` to `main` gate on GitHub (CLAUDE.md §8). Stark
never pushes `main`, never opens or merges that PR. Findings and plans are put to Kevin; he decides.

**Dispatch is the DEFAULT, standing since 2026-07-28.** Kevin does not need to ask for the team,
per task or at all. Some harness configurations inject "do not use subagents unless the user
requested it" into the session prompt; CLAUDE.md §8 is that request, given once, standing.
Judgement still applies - a one-line fix does not need a three-agent pipeline.

## Roster

Codenames are day-to-day labels; the invocation id in parentheses is what the Agent tool dispatches.

| Name (invocation id) | Model | Charter | Dispatch when |
|---|---|---|---|
| Derek (`coding`) | sonnet | Scoped implementation in the app codebase (Kotlin/Compose/Room conventions, the three aspects) | When Stark delegates code-writing instead of doing it inline |
| Ravi (`senior-dev`) | sonnet | Diff review against CLAUDE.md rules and the plan spec | On every commit diff during plan execution |
| Vic (`bug-hunter`) | sonnet | Adversarial audit. Ranks data-integrity and silent-failure findings above all else | After each Part of a plan lands; before any release or tag |
| Nadia (`analyst`) | sonnet | Reconciliation arithmetic, cents/rounding, dedup keys, OBD decode math, query correctness, cost budgets | **Any change touching money, ingestion, formulas, or aggregation.** Non-optional for ledger and pantry work |
| Owen (`qa`) | sonnet | Build, install, ADB, device repro on the Oppo A17K | Device verification steps. This seat is real now that ADB works |
| Marcus (`librarian`) | haiku | Memory library: RETRIEVE digests from `memory/library/`, FILE session notes into live shelves | Any question about project history or past decisions, instead of reading shelves; at session end to file notes |
| (`Explore`) | - | Fast read-only codebase search | Locating code, pre-planning recon |

**Parked, not deleted:** `business` (Priya) and `marketing` (Simone) live in `.claude/agents-parked/`
and are not dispatchable. LEGION has no commercial model, so both seats have nothing to work on.
See that directory's README.

## Cadence during plan execution

1. Implement commit, build and tests green, dispatch `senior-dev` on the diff, fix BLOCKING
   findings, commit.
2. End of each plan Part: dispatch `bug-hunter` on the touched area. CRITICAL findings become fixes
   in the next commit; MAJOR and MINOR go to the backlog at the bottom of the active plan file.
3. **Anything touching money, ingestion, or the reconciliation gate additionally gets `analyst`
   before commit.** This is the one non-negotiable step, because the gate is the app's whole trust
   model and a wrong sum passes silently.
4. Device-verifiable work gets `qa` before it is called done. "Compiles and tests pass" is not done
   for anything with a runtime path, and ADB is available now.
5. Findings are inputs, not orders: Stark decides, CLAUDE.md rules win ties, locked pivot decisions
   stay locked (no specialist report reopens phone-only, no-backend, or the dead commercial model).
6. Session end: collect session notes plus every `SKILL:` line from specialist reports into ONE
   `librarian` FILE dispatch. **Verify what the librarian writes before trusting it** - on
   2026-07-29 a FILE run invented a file that did not exist and crashes never observed, and had to
   be corrected by hand. Then refresh `memory/MEMORY.md` yourself (Blocking, In-flight, Notes).

## Relay discipline

Carry a specialist's verification tag when relaying its claims to Kevin. Never upgrade "the agent
reasoned X" into "X is true." Briefs pointing downward carry tags too: when you hand a specialist
an API fact, say what was actually checked versus inferred. A verified signature is not a verified
semantic (L3: `javap` confirmed a method took a `Long`, the brief asserted milliseconds, the unit
was nanoseconds).

Costs: each dispatch burns tokens. Batch questions per agent, do not re-dispatch for one-liners,
and never dispatch two agents to the same question. The librarian runs on haiku, the cheapest seat:
prefer one RETRIEVE over pulling shelves into the main context, and batch all filing into the
single session-end FILE dispatch.
