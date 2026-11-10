---
map: worker-workflows
ticket: "01"
title: "Rulings: where workflows live, trigger, unattended runs, miner sources"
type: grilling
status: resolved
status-detail: "Resolved 2026-11-09: Kevin took every recommendation (\"All ur picks\"); build ordered 2026-11-10 (\"yeah as per ur recs\"). Build tickets 02 to 05 and 15 carry it."
size: S
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Rulings for Kevin

Four questions. Each has a recommendation; every build ticket in this map assumes it.

## R1. Where does a workflow live?

| Option | For | Against |
|---|---|---|
| **A. Worker skill + `workflow.json` sidecar in the private skills folder** (`%APPDATA%\legion-claude\work\.claude\skills\<name>\`) | Already how helpers run (main session prompt rule 11). Already outside the public repo, so a skill can name private paths. Already guarded: the ask-list hook asks before any helper writes there. One place to edit | Not versioned in git. Backup is Not yet specified (a private repo, or the `.legion` folder) |
| B. Engine registry (Postgres table, edited on the web desk) | Visible from every client | The body is Claude instructions that run on the laptop and name laptop paths. Two copies (engine row, laptop skill) drift. The engine would hold private paths |
| C. Repo files (`tools/claude_session/workflows/*.md`) | Versioned, reviewed | Public repo: no private paths, no personal details. Most of today's skills could not live there |

**Recommend A.** The engine keeps a derived cache only (names, one-liners, param names, ask lines,
schedules), refreshed by the worker's heartbeat. The repo keeps the format, the parser, the tests
and invented fixtures.

## R2. How does Alfred trigger one?

The Live setup payload has ~17 estimated tokens of headroom (`LiveSetupPayloadSizeTest`, chars/4).

| Option | Setup cost (est.) | Notes |
|---|---|---|
| **A. New `action` value `"workflow"`; name and params in `request`; engine resolves** | ~3 tokens (`,"workflow"` is 11 chars) | Explicit. An unknown name comes back with the machine's known names |
| B. No schema change: engine prefix-matches every `send` against the catalog | 0 | Silently reclassifies a free-form task on a near miss; hard to explain when it misfires |
| C. A `workflow` string property | ~8 tokens | Same as A, costs more |
| D. An enum of workflow names | 15+ and grows | Every new workflow is a phone release. No |

**Recommend A**, with no change to `CLAUDE_SESSION_CLAUSE`: Alfred uses `workflow` only when Kevin
names Claude ("have Claude audit my logs"), exactly as ruling 2 holds for `send`. Letting a bare
workflow name count as naming Claude would cost ~10 more tokens of clause and leave ~4. Discovery
costs nothing in setup: `check` lists workflow names in its result text. The phone ticket measures
before it commits; if A does not fit, B is the fallback.

## R3. What may a scheduled (unattended) run do?

ADR 0067 ruling 15: *"what still needs a yes per run is decided then."* Now is then.

**Recommend:**

- A scheduled run does its read-only and own-work-folder steps, and stops at every ask-list step
  (delete or overwrite, send, book or pay, install, system settings) and every ship step with
  `needs_confirmation`, exactly like a task Kevin sent. No token is issued for a scheduled run.
- Kevin's yes to that question is the ordinary `answer` follow-up. It covers that run only.
- **One standing yes:** a workflow may declare `"deliver": "email_self"`, so each run mails its
  result to Kevin only. Installing the workflow (itself on the ask list) is the yes, as naming his
  own email is the yes in ruling 17. Mail to anyone else, and any deploy, never stands.
- A scheduled run that ends `needs_confirmation` raises a phone notification (the result watcher
  only watches tasks the phone sent).

## R4. What does the repeated-task miner read?

**Recommend:** Claude worker tasks only. Engine task rows (30 days) plus a local task log on the
Alienware (`%USERPROFILE%\.legion\task-log.jsonl`, request text, 180 days, never leaves the
laptop). The bar is procedures ruling 8's: 3+ matching requests over 2+ separate days. Kevin's
interactive Claude Code sessions stay out of v1: they are his working conversations, far larger,
and the jobs worth a workflow tend to show up as worker tasks once workflows exist. Revisit if the
first month of proposals is thin.

## Answer

Kevin, 2026-11-09: *"All ur picks"*. R1 A, R2 A, R3 and R4 as recommended. Build
ordered 2026-11-10: *"yeah as per ur recs"*. Amended into ADR 0067 (ticket 15).
