---
map: procedures
title: "Procedures: compiled multi-step answers, run by the engine, callable from every LEGION client"
charted: 2026-11-03
charted-by: "Kevin + Opus"
effort: "`.scratch/procedures/`"
tickets: 21
open: 12
status: open
tags: [map]
---

# Procedures

**Kevin, 2026-11-03:** *"adapt the talk ideas. for everything in legion. phone, laptop, recruiter,
headunit"*. The talk: Neo4j's "graph engineering". Its six ideas, adapted:

| Talk | LEGION |
|---|---|
| Procedural graph: a skill compiled to explicit steps, run by a runner | A **procedure**: a versioned DAG of steps in Postgres, run by the engine |
| Decision traces / context graph | **Run traces**: every run and step, linked to the conversation record |
| Branch observability | **Branch report**: which branches ran, never ran, failed |
| Distil procedures from repeated traces | **Proposals** from conversation records, Kevin approves, never auto-active |
| Run inspector | **Run inspector** on the web desk |
| Portable across runtimes | **MCP**: one engine tool `run_procedure` for phone, head unit, web, recruiter, laptop |

Ruled before charting (Kevin): **no Neo4j, no graph DB**; Postgres tables in the Django engine; MCP
is the portability layer; clone-and-run (CLAUDE.md §2).

## Decisions for Kevin

**All nine DECIDED 2026-11-03** (Kevin: *"approve all 9 as recommended"*). Each is ruled in its
ticket's `## Answer`; the standing parts are ADR 0070. The recommendations below are now the rulings.

1. **Storage and format** ([Procedure format](issues/01-procedure-format-and-storage.md)): new
   `server/procedures/` app, versioned definitions in Postgres, built-ins as YAML in the repo loaded
   on migrate; six step kinds (tool, compute, decide, ask, check, say), DAG only, no loops.
   **Decided: yes.**
2. **The decision step** ([Decision step](issues/02-decision-step-small-model.md)): a small model
   (household key, `gemini-3.5-flash-lite`) may only pick one of the branches the definition names,
   never state a fact; labelled "a model chose" in trace and result; not allowed in a procedure that
   writes. **Decided: yes; no `decide` in a writing procedure; none of the first procedures uses one.**
3. **Asking by voice** ([Ask step](issues/03-ask-step-by-voice.md)): the run returns `needs_answer`
   plus a resume token and ends the call; only the very next utterance counts; expires after 2
   minutes; every write is preceded by an ask unless the request named it. **Decided: yes.**
4. **How clients call it** ([Client reach](issues/04-how-clients-reach-run-procedure.md)): phone and
   head unit declare `run_procedure` DIRECTLY, not behind `ask_engine`; on the phone a read-only
   procedure runs NON_BLOCKING / `WHEN_IDLE` (amends ADR 0065's fixed set), any writing one
   BLOCKING. **Decided: yes.**
5. **Engine down** ([Engine down](issues/05-engine-down-and-two-implementations.md)): voice answers
   car and money from the engine's procedure when it is up, and falls back to the phone's local
   tools, said in words, when it is not. **Decided: yes; retiring the phone's duplicate figures is
   a later effort.**
6. **Trace content and retention** ([Traces](issues/06-trace-content-and-retention.md)): store step
   args and trimmed results like the conversation record (not `McpCall`'s no-args posture); members
   60 days; recruiter runs deleted with the visit household, 30-day cap. **Decided: yes.**
7. **Recruiters** ([Recruiter visits](issues/07-recruiter-visits.md)): recruiters get
   `run_procedure` for procedures built only from their allowlist, and see the inspector for their
   own visit's runs. **Decided: yes.**
8. **Distillation** ([Distillation policy](issues/08-distillation-policy.md)): proposals from 3+
   matching successful conversations over 2+ days; never from recruiter visits or mail-touching
   turns; Kevin approves in the inspector; never auto-activated. **Decided: yes.**
9. **First procedures** ([First procedures](issues/09-first-procedures.md)): `car_check`,
   `money_check`, `morning_briefing` (wraps the existing brief); laptop deferred.
   **Decided: yes; the phone's `get_sitrep` stays as is until the evaluation says otherwise.**

## Destination

Kevin asks "how's the car" on the phone, in the car, at the desk, or a recruiter asks on a visit,
and every client makes ONE `run_procedure` call; the engine runs the same steps, gives the same
answer with every caveat, and the run's steps, branch and timings are readable in the inspector.
Kevin approves new procedures proposed from his own conversations. Measured: fewer round trips and
lower latency than today's free routing, on the same asks.

## Notes

- Execution in scope once decisions land (CLAUDE.md §12: anything ready gets built). Decision
  tickets are `grilling`; build tickets list the decisions they wait on as blockers.
- Honesty binds every step (CLAUDE.md §4, §7): a result says what did NOT happen; estimates
  labelled; outcome verbs only after an observed ok; writes BLOCKING; Gemini on the household's
  own key; third-party content never stored.
- **The runner must apply the recruiter fence per step** (`assistant.surface.refusal_for`), because
  that fence lives in `AssistantToolView`, not in `run_tool`. A runner that calls `run_tool` alone
  leaks.
- New tables: `public` schema, `household.tenancy.TENANT_TABLES`, tenancy leak test (ADR 0045).
- ADR: **0070** (`docs/adr/0070-procedures-run-by-the-engine.md`). 0066 and 0067 are taken on unmerged
  branches, 0068 and 0069 on dev (checked 2026-11-03).
- Skills: `/grilling` and `/domain-modeling` for decision tickets; `/tdd` for builds.

## What exists, so nothing here is invented (traced 2026-11-03 on origin/dev 36ef92ef)

Full trace with line numbers: [research/call-sequences-today.md](research/call-sequences-today.md).

- **Registry**: `server/engine_mcp/tools.py` `TOOLS` / `run_tool` (2115) / `run_engine_tool`
  (2131): write fence, table fence, schema, handler. `ToolResult` carries `outcome` ok / refused /
  failed. Audit: `engine_mcp.models.McpCall`, no args, no results.
- **Web door**: `server/assistant/views.py` `AssistantToolView.post` (423): `refusal_for`, then
  `run_tool`, then the call written into the conversation record.
- **Recruiter surface**: `server/assistant/surface.py` `RECRUITER_TOOLS` default deny;
  `tests/test_recruiter.py` fails on an unclassified registry tool.
- **Phone**: `LiveToolbox.kt`. Car via `ask_fleet` router (non-blocking), money via direct local
  tools (blocking), morning via `get_sitrep` (one tool). Engine via `ask_engine` (sub-agent, up to
  4 model calls, blocking). **Car and money figures are computed locally on the phone**, separately
  from the engine's parity tools.
- **Head unit**: one `ask_engine`, no NON_BLOCKING, 5 s glance cards, audio-first.
- **Prior art**: `server/brief/compose.py` (deterministic sections, only News uses a model) and the
  phone's `get_sitrep` ("ONE tool, never six"). Job runs: `ingest.jobs.run_job` / `IngestRun`.
- **Conversation records**: `DeviceLog` reason `conversation`, LIVE_AUDIT lines, 60 days; web
  record `devicelogs/web_conversation.py`; recruiter visit records `demo_visitor`, 30 days
  (`prune_demo_visitors`, hourly cron).
- **Laptop**: Claude session map parked; nothing on dev.

## Tickets

| # | Title | Type | Blocked by |
|---|---|---|---|
| 01 | Procedure format, step kinds and where a definition lives | grilling | - |
| 02 | The decision step: what a small model may choose and how it is labelled | grilling | - |
| 03 | The ask step: a procedure that needs a human answer, by voice | grilling | - |
| 04 | How each client reaches run_procedure | grilling | 01 |
| 05 | Engine down, and the phone's own copies of car and money figures | grilling | 04 |
| 06 | What a run trace stores, and how long | grilling | 01 |
| 07 | Recruiter visits: run_procedure and the run inspector | grilling | 06 |
| 08 | Distillation: when a repeated trace becomes a proposed procedure | grilling | 06 |
| 09 | The first procedures and their exact steps | grilling | 01 |
| 10 | Baseline: measure round trips, latency and tool errors from recorded conversations | task | - |
| 11 | Build: procedures app, definition schema, built-in loader | build | 01, 02, 03, 06 |
| 12 | Build: engine runner and the run_procedure MCP tool | build | 04, 07, 11 |
| 13 | Build: run traces, linked to conversation records, with retention | build | 06, 11 |
| 14 | Build: branch report and run inspector on the web desk | build | 07, 13 |
| 15 | Build: car_check, money_check, morning_briefing as built-ins | build | 09, 12 |
| 16 | Build: phone declares run_procedure | build | 04, 05, 12, 15 |
| 17 | Build: head unit declares run_procedure directly | build | 04, 12, 15 |
| 18 | Build: web assistant and recruiter visit use run_procedure | build | 07, 12, 15 |
| 19 | Laptop worker: procedures for the Claude session | grilling | 12 (+ Claude session map unparked) |
| 20 | Build: distillation proposals and approval in the inspector | build | 08, 13, 14 |
| 21 | Build: replay recorded asks against procedures vs free routing | build | 10, 15 |

## Decisions so far

2026-11-03, Kevin: *"approve all 9 as recommended"*. ADR 0070; `memory/library/decisions.md` entry
"2026-11-03 - Procedures: compiled multi-step answers, run by the engine (Kevin)".

- [Procedure format](issues/01-procedure-format-and-storage.md): `server/procedures/`, Postgres,
  built-in YAML loaded on migrate, six step kinds, DAG only, computed `writes`.
- [Decision step](issues/02-decision-step-small-model.md): picks a named branch only, household key,
  labelled; **not allowed in a procedure that writes**.
- [Ask step](issues/03-ask-step-by-voice.md): `needs_answer` plus resume token; very next utterance;
  **2 minutes**; every write preceded by an ask unless named by the request.
- [Client reach](issues/04-how-clients-reach-run-procedure.md): declared directly on phone and head
  unit; read-only procedures NON_BLOCKING on the phone (amends ADR 0065).
- [Engine down](issues/05-engine-down-and-two-implementations.md): **engine's answer when up**, phone's
  local copy said in words when down.
- [Traces](issues/06-trace-content-and-retention.md): args and trimmed results, 60 days, visit runs die
  with the visit (30-day cap), third-party reads never stored.
- [Recruiter visits](issues/07-recruiter-visits.md): `run_procedure` from their allowlist; inspector on
  their own visit only.
- [Distillation](issues/08-distillation-policy.md): proposals only, 3+ conversations over 2+ days,
  Kevin approves.
- [First procedures](issues/09-first-procedures.md): `car_check`, `money_check`, `morning_briefing`;
  **`get_sitrep` stays**; laptop deferred.

## Not yet specified

- Retiring the phone's local copies of car and money figures once the engine's are the spoken
  ones (depends on Engine down and the evaluation).
- Hands paths for each procedure on the phone screens beyond car and money.
- Whether Mia's household view gets its own built-ins or shares Kevin's.
- Procedures that span devices (phone asks, laptop finds, engine stores): waits on the laptop
  ticket.

## Out of scope

- Neo4j or any graph database (Kevin, before charting).
- Auto-activating a distilled procedure. Proposals need Kevin's approval, always.
- A procedure language with loops, recursion or procedures calling procedures (v1).
