"""The engine runner: one `run_procedure` call walks one procedure version's steps
(procedures ticket 12; ticket 13 for the trace it writes; ADR 0070).

## The fences, on EVERY step

A `tool` step goes through, in this order (`_tool_step`):

1. `assistant.surface.refusal_for(tool, request.user, args)`: the web door's fence, which lives
   in `AssistantToolView`, NOT in `run_tool`. For a recruiter it is the default-deny allowlist
   and the narrowed tables; for a member, the web surface. Applied with the step's arguments as
   filled in, because the table narrowing reads `args["table"]`.
2. When the step's tool writes: `household.permissions.write_refusal(request)`. `run_procedure`
   itself is `writes=False` on the registry (a read-scoped token may run a read-only
   procedure), so the runner checks the write fence itself before any writing step.
3. `engine_mcp.tools.run_engine_tool`: the registry's own write fence, table fence, schema and
   handler.

A recruiter is also refused, before any step, a procedure that uses any tool outside
`RECRUITER_TOOLS` (`listing.recruiter_refusal`, ruling 07).

## Step kinds

`tool` (above), `compute` (a `procedures/steps.py` function over filled-in args), `check` (a
function; false ends the run with its `fail_sentence`, which is an answer, not a failure),
`decide` (`procedures/decide.py`; the model picks a named branch, else `on_fail`, chosen by code;
labelled "a model chose" in the result and `chosen_by = model` on the trace), `ask` (returns
`needs_answer` with a resume token and ends the call; ruling 03), `say` (the answer, composed
from the template).

**Strictly sequential.** The definition format has one `next` per step (no fan-out), and the
ORM must stay on the request's own thread (`engine_mcp/server.py`), so there are no parallel
siblings in v1.

## Values

`$inputs.x` and `$steps.x[.path]`, as a whole argument string, become the value: a `tool` step's
is `{"text", "structured", "outcome"}`, a `compute` step's its return value, a `check` step's a
bool, a `decide` step's the branch, an `ask` step's the answer. In a `say` template
`{$steps.x}` is the value as words (a tool's text). A step on a branch that did not run reads as
nothing.

## Honesty (CLAUDE.md section 7)

The result lists the steps that ran, the step that stopped the run and why, the steps that did
not run, and says what was written ONLY for write steps whose tool returned ok; otherwise
"Nothing was written." Estimates stay labelled as the tools label them: their words are passed
through, never restated.

## Time (ruling 04)

The engine-side budget is 1.5 s (4 s with a `decide` step), as a p95 target. The runner stops
between steps at TWICE that, returning partial with the unrun steps named: a running tool cannot
be cut off from this thread.
"""

from __future__ import annotations

import datetime
import hashlib
import json
import re
import secrets
import time
import uuid
from dataclasses import dataclass, field

from django.utils import timezone

from devicelogs.web_conversation import CAP_TOOL
from engine_mcp.models import Outcome
from engine_mcp.tools import TOOLS_BY_NAME, ToolResult, ok, refused, run_engine_tool
from household.models import is_recruiter
from household.permissions import write_refusal
from household.tenancy import household_of
from procedures import listing, retention
from procedures.models import (
    CallerKind,
    ChosenBy,
    ProcedureRun,
    ProcedureStep,
    ProcedureVersion,
    RunStatus,
    StepOutcome,
)

ASK_EXPIRY = datetime.timedelta(minutes=2)
BUDGET_MS = 1500
BUDGET_DECIDE_MS = 4000
DEADLINE_FACTOR = 2
CLIENT_HEADER = "X-Legion-Client"

_WHOLE_REF = re.compile(r"^\$(steps|inputs)\.([a-z][a-z0-9_]{0,62}(?:\.[A-Za-z0-9_]+)*)$")
_TEMPLATE_REF = re.compile(r"\{\$(steps|inputs)\.([a-z][a-z0-9_]{0,62}(?:\.[A-Za-z0-9_]+)*)\}")

# Replaced in tests to make the time budget deterministic.
_clock = time.monotonic


def _trim(text: str) -> str:
    return text if len(text) <= CAP_TOOL else text[:CAP_TOOL] + "..."


def _sha(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def _jsonable(value):
    return json.loads(json.dumps(value, default=str))


def caller_kind(request) -> str:
    from household.models import DeviceToken

    if is_recruiter(getattr(request, "user", None)):
        return CallerKind.RECRUITER
    if isinstance(getattr(request, "auth", None), DeviceToken):
        # A label for the trace, never a privilege: the token's own scope still decides.
        said = (getattr(request, "headers", {}).get(CLIENT_HEADER) or "").strip().lower()
        return said if said in (CallerKind.PHONE, CallerKind.HEADUNIT) else CallerKind.MCP
    return CallerKind.WEB


def conversation_of(request, args: dict) -> uuid.UUID | None:
    """The argument's `conversation_id`, else the web door's body field beside the call."""
    raw = args.get("conversation_id")
    if not raw:
        try:
            raw = request.data.get("conversation_id")
        except Exception:  # noqa: BLE001 - no parsed body (the MCP door): no conversation
            raw = None
    try:
        return uuid.UUID(str(raw)) if raw else None
    except ValueError:
        return None


# =============================================================================
# One walk
# =============================================================================


@dataclass
class _Walk:
    request: object
    run: ProcedureRun
    version: ProcedureVersion
    by_id: dict
    inputs: dict
    values: dict = field(default_factory=dict)
    seq: int = 0
    began: float = 0.0
    deadline_ms: int = 0
    # Every step row of this run, as dicts, in order (earlier calls included on a resume).
    done: list = field(default_factory=list)
    notes: list = field(default_factory=list)

    @property
    def name(self) -> str:
        return self.version.procedure.name


def _lookup(walk: _Walk, source: str, path: str):
    head, *rest = path.split(".")
    value = (walk.inputs if source == "inputs" else walk.values).get(head)
    for key in rest:
        value = value.get(key) if isinstance(value, dict) else None
    return value


def _fill(walk: _Walk, obj):
    if isinstance(obj, str):
        match = _WHOLE_REF.match(obj)
        return _lookup(walk, *match.groups()) if match else obj
    if isinstance(obj, dict):
        return {k: _fill(walk, v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_fill(walk, v) for v in obj]
    return obj


def _words(value) -> str:
    if value is None:
        return ""
    if isinstance(value, dict) and isinstance(value.get("text"), str):
        return value["text"]
    if isinstance(value, bool):
        return "yes" if value else "no"
    if isinstance(value, str):
        return value
    return json.dumps(value, ensure_ascii=False, default=str)


def _render(walk: _Walk, template: str) -> str:
    return _TEMPLATE_REF.sub(lambda m: _words(_lookup(walk, *m.groups())), template).strip()


def _record(
    walk: _Walk,
    step: dict,
    started,
    t0: float,
    *,
    outcome,
    result="",
    branch="",
    chosen_by=ChosenBy.CODE,
    tool="",
    args=None,
    choice=None,
    usage=None,
) -> None:
    walk.seq += 1
    row = {
        "step_id": step["id"],
        "kind": step["kind"],
        "tool": tool,
        "outcome": outcome,
        "branch": branch,
        "chosen_by": chosen_by,
        "result": _trim(result),
    }
    model_fields = {}
    if choice is not None:
        model_fields = {
            "model": choice.model,
            "prompt_tokens": choice.prompt_tokens,
            "output_tokens": choice.output_tokens,
            "cost_estimate_micro_usd": choice.cost_estimate_micro_usd,
        }
    elif usage:
        model_fields = {
            "prompt_tokens": usage.get("prompt_tokens"),
            "output_tokens": usage.get("output_tokens"),
            "cost_estimate_micro_usd": usage.get("cost_estimate_micro_usd"),
        }
    ProcedureStep.objects.create(
        run=walk.run,
        household_id=walk.run.household_id,
        seq=walk.seq,
        args=_jsonable(args) if args is not None else None,
        started_at=started,
        ms=max(int((_clock() - t0) * 1000), 0),
        **row,
        **model_fields,
    )
    walk.done.append(row)


@dataclass
class _Stop:
    status: str
    text: str
    # The ToolResult outcome when the run did not finish.
    outcome: str = Outcome.OK
    question: dict | None = None


def _tool_step(walk: _Walk, step: dict, started, t0):
    request = walk.request
    name = step["tool"]
    args = _fill(walk, step.get("args", {}))
    tool = TOOLS_BY_NAME.get(name)
    from assistant.surface import refusal_for

    # The fences, per step (module doc): the caller's surface, then the write fence, then the
    # registry's own checks inside run_engine_tool.
    refusal = refusal_for(name, request.user, args)
    if refusal is None and tool is None:
        refusal = f"Nothing was read or written. This engine has no tool called {name!r}."
    if refusal is None and tool.writes:
        refusal = write_refusal(request)
    result = refused(refusal) if refusal is not None else run_engine_tool(request, tool, args)
    walk.values[step["id"]] = {
        "text": result.text,
        "structured": _jsonable(result.structured),
        "outcome": result.outcome,
    }
    if result.outcome == Outcome.OK and not result.is_error:
        _record(
            walk,
            step,
            started,
            t0,
            outcome=StepOutcome.OK,
            result=result.text,
            branch="next",
            tool=name,
            args=args,
        )
        return step["next"]
    outcome = StepOutcome.REFUSED if result.outcome == Outcome.REFUSED else StepOutcome.FAILED
    _record(walk, step, started, t0, outcome=outcome, result=result.text, tool=name, args=args)
    return _Stop(
        status=RunStatus.FAILED,
        text=f"Step {step['id']!r} ({name}) {'was refused' if outcome == 'refused' else 'failed'}"
        f": {result.text}",
        outcome=Outcome.REFUSED if outcome == StepOutcome.REFUSED else Outcome.FAILED,
    )


def _function_step(walk: _Walk, step: dict, started, t0):
    from procedures.steps import FUNCTIONS

    args = _fill(walk, step.get("args", {}))
    try:
        value = _jsonable(FUNCTIONS[step["function"]](**args))
    except Exception as exc:  # noqa: BLE001 - the result must say which step failed and how
        text = (
            f"Step {step['id']!r} ({step['function']}) hit an error ({type(exc).__name__}), so "
            "nothing is known from it."
        )
        _record(walk, step, started, t0, outcome=StepOutcome.FAILED, result=text, args=args)
        return _Stop(status=RunStatus.FAILED, text=text, outcome=Outcome.FAILED)
    if step["kind"] == "compute":
        walk.values[step["id"]] = value
        _record(
            walk,
            step,
            started,
            t0,
            outcome=StepOutcome.OK,
            result=_words(value),
            branch="next",
            args=args,
        )
        return step["next"]
    passed = bool(value)
    walk.values[step["id"]] = passed
    _record(
        walk,
        step,
        started,
        t0,
        outcome=StepOutcome.OK,
        branch="pass" if passed else "fail",
        result="passed" if passed else step["fail_sentence"],
        args=args,
    )
    if passed:
        return step["next"]
    return _Stop(status=RunStatus.OK, text=step["fail_sentence"])


def _decide_step(walk: _Walk, step: dict, started, t0):
    from demo import budget
    from procedures import decide

    args = _fill(walk, step.get("args", {}))
    branches = list(step["branches"])
    reservation = None
    try:
        if is_recruiter(walk.request.user):
            reservation = budget.charge_text_call()
            if reservation is None:
                raise decide.DecideFailed(budget.BUDGET_USED_UP, refused=True)
        choice = decide.choose(step["question"], args, branches)
    except decide.DecideFailed as exc:
        if reservation is not None and exc.refused:
            budget.release(reservation)
        branch = step["on_fail"]
        walk.values[step["id"]] = branch
        text = f"{exc.message} The definition's fallback {branch!r} was taken, chosen by code."
        _record(
            walk,
            step,
            started,
            t0,
            outcome=StepOutcome.FAILED,
            result=text,
            branch=branch,
            args=args,
            usage=exc.usage,
        )
        walk.notes.append(f"No model choice at {step['id']!r}: the fallback {branch!r} was taken.")
        return step["branches"][branch]
    walk.values[step["id"]] = choice.branch
    _record(
        walk,
        step,
        started,
        t0,
        outcome=StepOutcome.OK,
        result=f"a model chose {choice.branch}",
        branch=choice.branch,
        chosen_by=ChosenBy.MODEL,
        args=args,
        choice=choice,
    )
    walk.notes.append(f"(At {step['id']!r} a model chose {choice.branch!r}.)")
    return step["branches"][choice.branch]


def _ask_step(walk: _Walk, step: dict, started, t0):
    token = secrets.token_urlsafe(24)
    expires = timezone.now() + ASK_EXPIRY
    run = walk.run
    run.waiting_step = step["id"]
    run.resume_token_sha256 = _sha(token)
    run.resume_expires_at = expires
    _record(walk, step, started, t0, outcome=StepOutcome.ASKED, result=step["question"])
    run.resume_state = {"values": walk.values, "seq": walk.seq}
    return _Stop(
        status=RunStatus.WAITING,
        text=step["question"],
        question={
            "question": step["question"],
            "answers": list(step["answers"]),
            "resume_token": token,
            "expires_at": expires.isoformat(),
        },
    )


def _safe_walk(walk: _Walk, current: str) -> _Stop:
    """`_walk_from`, but an unexpected error still ends in `_finish`, so the result names any
    write that already committed from the rows observed, rather than the registry's generic
    "nothing is known" (a write may have happened before the error)."""
    try:
        return _walk_from(walk, current)
    except Exception as exc:  # noqa: BLE001 - the result must say what did and did not happen
        last = walk.done[-1]["step_id"] if walk.done else "the start"
        return _Stop(
            status=RunStatus.FAILED,
            text=f"The engine hit an error after {last} ({type(exc).__name__}).",
            outcome=Outcome.FAILED,
        )


def _walk_from(walk: _Walk, current: str) -> _Stop:
    while True:
        step = walk.by_id[current]
        elapsed_ms = int((_clock() - walk.began) * 1000)
        if elapsed_ms > walk.deadline_ms:
            return _Stop(
                status=RunStatus.FAILED,
                text=(
                    f"The run stopped at its time limit ({walk.deadline_ms / 1000:.1f} s) before "
                    f"step {current!r}."
                ),
                outcome=Outcome.FAILED,
            )
        started, t0 = timezone.now(), _clock()
        kind = step["kind"]
        if kind == "tool":
            nxt = _tool_step(walk, step, started, t0)
        elif kind in ("compute", "check"):
            nxt = _function_step(walk, step, started, t0)
        elif kind == "decide":
            nxt = _decide_step(walk, step, started, t0)
        elif kind == "ask":
            nxt = _ask_step(walk, step, started, t0)
        else:  # say
            text = _render(walk, step["template"])
            walk.values[step["id"]] = text
            _record(walk, step, started, t0, outcome=StepOutcome.OK, result=text)
            return _Stop(status=RunStatus.OK, text=text)
        if isinstance(nxt, _Stop):
            return nxt
        current = nxt


# =============================================================================
# The result
# =============================================================================


def _summary(walk: _Walk, stop: _Stop) -> tuple[str, dict]:
    ran = [d["step_id"] for d in walk.done]
    ran_set = set(ran)
    not_run = [sid for sid in walk.by_id if sid not in ran_set]
    written = [
        d
        for d in walk.done
        if d["tool"] and d["outcome"] == StepOutcome.OK and TOOLS_BY_NAME[d["tool"]].writes
    ]
    parts = [f"Procedure {walk.name} v{walk.version.version}"]
    if ran:
        parts.append("ran " + ", ".join(ran))
    if not_run:
        label = "did not run" if stop.status != RunStatus.OK else "not on this path"
        parts.append(f"{label}: " + ", ".join(not_run))
    footer = "; ".join(parts) + "."
    if written:
        footer += (
            " Written (the tool said committed): "
            + ", ".join(f"{d['step_id']} ({d['tool']})" for d in written)
            + "."
        )
    else:
        footer += " Nothing was written."
    if walk.notes:
        footer += " " + " ".join(walk.notes)
    structured = {
        "status": "needs_answer" if stop.status == RunStatus.WAITING else stop.status,
        "procedure": walk.name,
        "version": walk.version.version,
        "writes": walk.version.writes,
        "run_id": str(walk.run.id),
        "steps": [
            {k: d[k] for k in ("step_id", "kind", "tool", "outcome", "branch", "chosen_by")}
            for d in walk.done
        ],
        "not_run": not_run,
        "written": [d["step_id"] for d in written],
    }
    if stop.question:
        structured.update(stop.question)
    return footer, structured


def _finish(walk: _Walk, stop: _Stop, extra_ms: float) -> ToolResult:
    status = stop.status
    if status == RunStatus.FAILED and any(
        d["outcome"] == StepOutcome.OK and d["kind"] in ("tool", "compute") for d in walk.done
    ):
        status = RunStatus.PARTIAL
    stop.status = status
    footer, structured = _summary(walk, stop)
    if status == RunStatus.WAITING:
        text = (
            f"{stop.text} (Waiting for an answer: {', '.join(structured['answers'])}. Call "
            f"run_procedure again with name {walk.name!r}, resume_token "
            f"{structured['resume_token']} and the answer, on the very next thing said and "
            "within two minutes; anything else cancels it.) " + footer
        )
    elif status in (RunStatus.PARTIAL, RunStatus.FAILED):
        heard = [
            f"{d['step_id']}: {walk.values[d['step_id']]['text']}"
            for d in walk.done
            if d["kind"] == "tool"
            and d["outcome"] == StepOutcome.OK
            and isinstance(walk.values.get(d["step_id"]), dict)
        ]
        text = f"The procedure {walk.name} did not finish. {stop.text} " + footer
        if heard:
            text += " What the steps that ran said: " + " | ".join(heard)
    else:
        text = f"{stop.text}\n\n({footer})"
    run = walk.run
    run.status = status
    run.ms += int(extra_ms)
    # The token is stored only as its hash: the stored answer names it in words.
    stored = (
        text.replace(stop.question["resume_token"], "[resume token]") if stop.question else text
    )
    run.result = _trim(stored)
    if status != RunStatus.WAITING:
        run.waiting_step, run.resume_token_sha256 = "", ""
        run.resume_expires_at, run.resume_state = None, None
    else:
        run.resume_state = _jsonable(run.resume_state)
    run.save()
    _note_in_conversation(walk, status)
    if status in (RunStatus.PARTIAL, RunStatus.FAILED):
        return ToolResult(text=text, is_error=True, outcome=stop.outcome, structured=structured)
    return ok(text, structured)


def _note_in_conversation(walk: _Walk, status: str) -> None:
    if walk.run.conversation_id is None:
        return
    from devicelogs import web_conversation

    web_conversation.record_procedure_run(
        walk.request,
        walk.run.conversation_id,
        run_id=str(walk.run.id),
        name=walk.name,
        version=walk.version.version,
        status=status,
        steps=len(walk.done),
        ms=walk.run.ms,
        branches=[
            f"{d['step_id']}:{d['branch']}"
            for d in walk.done
            if d["kind"] in ("decide", "ask", "check") and d["branch"]
        ],
    )


# =============================================================================
# The tool's handler
# =============================================================================


def _cancel_waiting(user, household) -> list[str]:
    """Ruling 03: only the very next utterance answers. A new call that is not a resume is
    something else, so this user's waiting runs are cancelled. Returns their names."""
    waiting = ProcedureRun.objects.filter(
        household=household, user=user, status=RunStatus.WAITING
    ).select_related("version__procedure")
    names = [r.version.procedure.name for r in waiting]
    waiting.update(
        status=RunStatus.CANCELLED,
        resume_state=None,
        resume_token_sha256="",
        resume_expires_at=None,
        waiting_step="",
        result="Cancelled: another request came before the answer. Nothing after the question "
        "ran, and nothing after it was written.",
    )
    return names


def _input_refusal(definition: dict, inputs: dict) -> str | None:
    declared = definition.get("inputs", {})
    unknown = sorted(set(inputs) - set(declared))
    if unknown:
        return (
            f"Nothing ran. {definition['name']} takes no input called {', '.join(unknown)}; "
            f"its inputs: {', '.join(declared) or 'none'}."
        )
    missing = sorted(k for k, v in declared.items() if v.get("required") and k not in inputs)
    if missing:
        return f"Nothing ran. {definition['name']} needs {', '.join(missing)}."
    return None


def _deadline(definition: dict) -> int:
    has_decide = any(s["kind"] == "decide" for s in definition["steps"])
    return (BUDGET_DECIDE_MS if has_decide else BUDGET_MS) * DEADLINE_FACTOR


def run_procedure(request, args: dict) -> ToolResult:
    began = _clock()
    user = request.user
    household = household_of(request)
    name = args["name"]
    if args.get("resume_token"):
        return _resume(request, household, args, began)
    if args.get("answer") is not None:
        return refused(
            "Nothing ran. An answer goes with the resume_token the question came with; without "
            "it there is no question to answer."
        )
    cancelled = _cancel_waiting(user, household)
    retention.prune()
    version = listing.find(household, name)
    if version is None:
        return refused(
            f"Nothing ran. There is no procedure called {name!r} here. Procedures: "
            f"{', '.join(v.procedure.name for v in listing.runnable(user, household)) or 'none'}."
        )
    definition = version.definition
    if is_recruiter(user):
        refusal = listing.recruiter_refusal(definition)
        if refusal is not None:
            return refused(refusal)
    inputs = args.get("inputs") or {}
    refusal = _input_refusal(definition, inputs)
    if refusal is not None:
        return refused(refusal)
    run = ProcedureRun.objects.create(
        household=household,
        user=user if getattr(user, "is_authenticated", False) else None,
        version=version,
        caller_kind=caller_kind(request),
        conversation_id=conversation_of(request, args),
        status=RunStatus.FAILED,
        inputs=_jsonable(inputs),
        started_at=timezone.now(),
    )
    walk = _Walk(
        request=request,
        run=run,
        version=version,
        by_id={s["id"]: s for s in definition["steps"]},
        inputs=inputs,
        began=began,
        deadline_ms=_deadline(definition),
    )
    if cancelled:
        walk.notes.append(
            f"(A question from {', '.join(cancelled)} was waiting; it was cancelled, and nothing "
            "after it ran.)"
        )
    stop = _safe_walk(walk, definition["steps"][0]["id"])
    return _finish(walk, stop, (_clock() - began) * 1000)


def _resume(request, household, args: dict, began: float) -> ToolResult:
    token = str(args["resume_token"])
    run = (
        ProcedureRun.objects.filter(
            household=household,
            user=request.user,
            status=RunStatus.WAITING,
            resume_token_sha256=_sha(token),
        )
        .select_related("version__procedure")
        .first()
    )
    if run is None or run.version.procedure.name != args["name"]:
        return refused(
            "Nothing ran. That resume_token matches no question waiting for you: it was "
            "already answered, it expired, another request cancelled it, or it is not yours."
        )
    state = run.resume_state or {}
    # Claim it: one answer per question, whatever arrives at the same time.
    claimed = ProcedureRun.objects.filter(
        pk=run.pk, status=RunStatus.WAITING, resume_token_sha256=run.resume_token_sha256
    ).update(
        status=RunStatus.FAILED, resume_state=None, resume_token_sha256="", resume_expires_at=None
    )
    if not claimed:
        return refused("Nothing ran. That question was already answered or cancelled.")
    expired = run.resume_expires_at is not None and timezone.now() > run.resume_expires_at
    run.status = RunStatus.FAILED
    definition = run.version.definition
    walk = _Walk(
        request=request,
        run=run,
        version=run.version,
        by_id={s["id"]: s for s in definition["steps"]},
        inputs=run.inputs or {},
        values=state.get("values", {}),
        seq=state.get("seq", 0),
        began=began,
        deadline_ms=_deadline(definition),
        done=[
            {
                "step_id": s.step_id,
                "kind": s.kind,
                "tool": s.tool,
                "outcome": s.outcome,
                "branch": s.branch,
                "chosen_by": s.chosen_by,
                "result": s.result,
            }
            for s in run.steps.order_by("seq")
        ],
    )
    step = walk.by_id[run.waiting_step]
    answer = " ".join(str(args.get("answer") or "").split()).lower()
    started, t0 = timezone.now(), _clock()
    if expired or answer not in step["answers"]:
        why = (
            "the answer came after the two minutes a question waits"
            if expired
            else f"{answer or 'nothing'!r} is not one of {', '.join(step['answers'])}"
        )
        text = f"Cancelled: {why}. Nothing after the question ran."
        _record(
            walk,
            step,
            started,
            t0,
            outcome=StepOutcome.REFUSED,
            result=text,
            chosen_by=ChosenBy.USER,
        )
        return _finish(
            walk, _Stop(status=RunStatus.CANCELLED, text=text), (_clock() - began) * 1000
        )
    walk.values[step["id"]] = answer
    _record(
        walk,
        step,
        started,
        t0,
        outcome=StepOutcome.OK,
        result=f"answered {answer}",
        branch=answer,
        chosen_by=ChosenBy.USER,
    )
    stop = _safe_walk(walk, step["answers"][answer])
    return _finish(walk, stop, (_clock() - began) * 1000)
