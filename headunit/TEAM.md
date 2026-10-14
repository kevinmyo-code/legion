# TEAM.md — how the orchestrator runs this project

The main session (Opus/Fable), codenamed **Stark**, is the ORCHESTRATOR / VP of Ops: it
owns the plan, makes calls, writes code (or dispatches `coding`), and is the only one who
commits. Specialists are dispatched via the Agent tool and report back; they never commit.

**Kevin is in the loop, not above it.** He sets direction, checks and chimes in on work as
it lands, and reviews work. Git and branching follow the root LEGION `CLAUDE.md`
"Branching" section (this app lives in `headunit/` of that repo; the MIDNIGHT_AI-era
`dev` -> `main` PR gate no longer applies as written here). Findings and plans are put
to Kevin; he decides.

## Roster
Codenames are day-to-day labels; the `invocation id` in parentheses is what the Agent tool dispatches.

| Name (invocation id) | Model | Charter | Dispatch when |
|---|---|---|---|
| Derek (`coding`, project) | sonnet | Scoped implementation tasks in the app codebase (Kotlin/Compose conventions) | When Stark delegates code-writing instead of doing it inline |
| Ravi (`senior-dev`, project) | sonnet | Diff review vs CLAUDE.md rules + plan spec | On every commit diff during plan execution |
| Vic (`bug-hunter`, project) | sonnet | Adversarial code audit, golden-path protection | After each Part of a plan lands; before any release/tag |
| Owen (`qa`, fleet) | sonnet | Build, install, ADB, device repro | Emulator/device verification steps |
| Nadia (`analyst`, project) | sonnet | Formula/query/cost verification | Any change touching math, budgets, aggregation |
| Marcus (`librarian`, project) | haiku | Memory library curation: RETRIEVE digests from memory/library/, FILE session notes into shelves | Any question about project history, decisions, blockers, or past fixes, instead of reading shelf files; at session end to file notes |
| Priya (`business`, fleet) | sonnet | Market research, policy/ToS, naming | External-world questions |
| Simone (`marketing`, project) | sonnet | Positioning, copy, simulated focus group | Before user-facing copy ships; when consumer story unclear |
| (`Explore`, fleet) | - | Fast read-only codebase search | Locating code, pre-planning recon |

## Cadence during plan execution
1. Implement commit → build + tests green → dispatch senior-dev on the diff →
   fix BLOCKING findings → commit.
2. End of each plan Part → dispatch bug-hunter on the touched area; CRITICAL findings
   become fixes in the next commit, MAJOR/MINOR go to the backlog list at the bottom
   of the active plan file.
3. Math/DB commits additionally get analyst before commit.
4. User-visible strings (notices, spoken fallbacks, onboarding copy) get a marketing
   read before shipping; full focus group only for major surface changes (killer-moment
   flow, store listing, pricing page) — sessions land in docs/marketing/.
5. Findings are inputs, not orders: the PM decides, CLAUDE.md rules win ties, frozen
   decisions stay frozen (persona reports do not reopen pricing/nav/Spotify).
6. Session end: collect session notes plus every SKILL: line from specialist reports into
   ONE librarian FILE dispatch. The librarian updates shelves and INDEX.md; the PM then
   refreshes memory/MEMORY.md (Blocking one-liners, In-flight, Notes for next session).

Costs: each dispatch burns tokens — batch questions per agent, don't re-dispatch for
one-liners, and never dispatch two agents to the same question. The librarian runs on
haiku, the cheapest seat: prefer one librarian RETRIEVE over pulling library shelves into
the main context, and batch all filing into the single session-end FILE dispatch.
