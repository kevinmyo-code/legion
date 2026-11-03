---
status: accepted
decided: 2026-11-03
decided-by: Kevin
source: "[[decisions#2026-11-03 - Procedures: compiled multi-step answers, run by the engine (Kevin)]]"
tags: [adr]
---

# 70. Procedures: compiled multi-step answers, run by the engine

## Standing

**A recurring multi-step answer ("how's the car", "money", the morning briefing) is a procedure:
a versioned DAG of steps stored in Postgres and run by the Django engine, reached by every client
through ONE engine tool, `run_procedure`.** No graph database, no new service. This amends
[[0065-read-only-lookups-may-be-non-blocking]]: on the phone, a procedure whose computed `writes`
is false joins the non-blocking set.

## Context

Kevin, 2026-11-03, adapting a talk on "graph engineering" to every LEGION client. Traced on dev the
same day: a car or money ask costs 2-5 model round trips (a Live turn per tool, plus a sub-agent
router or `ask_engine`'s up to four POSTs), and the phone computes car and money figures locally
while the engine computes them again in Django. `brief/compose.py` and the phone's `get_sitrep`
already showed the alternative: a fixed sequence composed by code, one call. Kevin ruled all nine
decisions as recommended (*"approve all 9 as recommended"*).

## Decision

- **Storage.** App `server/procedures/`. Tables `procedures` and `procedure_versions` in `public`,
  carrying `household_id` (NULL for a built-in), listed in `household.tenancy.TENANT_TABLES`. A
  version is immutable; a change is a new version. Built-ins are YAML in
  `server/procedures/builtin/`, loaded after every schema migration, a new version only when the
  file's hash changed, so a stranger's clone has them with no manual step.
- **Six step kinds, a closed set:** `tool` (one registry tool through `run_engine_tool`), `compute`
  (a named pure function), `decide`, `ask`, `check`, `say`. A seventh is a ruling, not a refactor.
  **DAG only**: no loops, no recursion, no procedure calling a procedure.
- **`writes` is computed**, true when any reachable `tool` step's tool writes. Clients stamp
  BLOCKING from it.
- **`decide`**: a small model on the household's own key picks one of the branch names the
  definition lists, nothing else; any other output fails the step and the run takes `on_fail`.
  Labelled in the trace (`chosen_by = model`) and in the result ("a model chose"). **Not allowed in
  a procedure that writes.**
- **`ask`**: the run returns `needs_answer` with a resume token and never holds the call open; only
  the very next utterance answers; **2 minutes** and it is cancelled, said in words. **Every write
  step is preceded by an `ask`** unless marked `named_by_request`. Both refused at load.
- **Clients.** Phone and head unit declare `run_procedure` directly, not behind `ask_engine`. On the
  phone a read-only procedure runs NON_BLOCKING `WHEN_IDLE`; a writing one is BLOCKING; the head
  unit stays all-BLOCKING. Web gets it from the registry. Recruiters get only procedures built
  entirely from `RECRUITER_TOOLS`, and the runner applies `surface.refusal_for` per step, because
  that fence is not in `run_tool`.
- **Engine down**: when the engine is reachable, voice speaks the engine's figures; when it is not,
  the phone answers from its own copy and says so, with the sync time.
- **Traces** keep step args and trimmed results like the conversation record, not `McpCall`'s
  no-arguments posture: members 60 days, recruiter visit runs deleted with the visit household
  (30-day cap). A step that would read third-party content is refused, so it is never stored.
- **Distillation** only proposes; Kevin approves every new procedure. Never auto-activated.

## Consequences

- Car and money answers move onto the Django copy of each figure (CLAUDE.md §7: a business rule
  lives in Django once). The phone's local copies stay as the engine-down fallback until an
  evaluation shows where the two disagree.
- `run_procedure` is `writes=False` on the registry so a read-scoped token can run read-only
  procedures; the runner checks the write fence itself before any writing step. A future change
  that let the static flag decide would refuse every procedure to a recruiter.
- The branch report can list "never ran" branches only because the graph is finite. Loops would
  end that, which is why they are out of v1.
