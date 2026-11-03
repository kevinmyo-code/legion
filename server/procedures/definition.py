"""What a procedure definition may say, checked before it is ever stored (procedures ticket 11).

The rulings this enforces (ADR 0070, `.scratch/procedures/issues/01`, `02`, `03`, `06`):

- **Six step kinds, a closed set**: `tool`, `compute`, `decide`, `ask`, `check`, `say`.
- **A DAG**: steps run from the first one; no cycles, no step that can never run, no procedure
  calling a procedure. Every path ends at a `say`.
- **`tool`** names a tool in the engine's registry (`engine_mcp.tools.TOOLS_BY_NAME`), never a
  read-through (third-party content) tool and never `run_procedure`.
- **`compute` / `check`** name a function in `procedures/steps.py`.
- **`decide`** (ruling 02) lists the branch names a model may pick and an `on_fail` branch among
  them, and **may not appear in a procedure that writes**.
- **`ask`** (ruling 03) lists its allowed answers. **Every `tool` step whose tool writes must be
  reachable only through an `ask`**, unless it is marked `named_by_request: true` (the user's
  request already named that write).
- **`writes` is computed** (ruling 01): true when any `tool` step's tool writes.

`validate(definition)` returns a `Checked` (the definition plus computed `writes`) or raises
`DefinitionError`, whose `problems` are sentences each naming the procedure and the step.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from jsonschema import Draft202012Validator

STEP_KINDS: tuple[str, ...] = ("tool", "compute", "decide", "ask", "check", "say")

# Mirrors `LiveToolbox.EPISODIC_EXCLUDED_TOOLS` on the phone (CLAUDE.md section 7, ruling 06): a
# step that reads third-party content would be stored in a run trace, so it is refused here,
# before any trace exists. No engine tool reads mail today (`tests/test_engine_mcp.py`); this
# keeps a future one out. `tests/test_procedures_definition.py` fails while the two sets differ.
READ_THROUGH_TOOLS: frozenset[str] = frozenset(
    {"search_mail", "read_mail", "ask_mail", "get_sitrep", "track_package", "flight_status"}
)
# No procedure calls a procedure in v1 (ruling 01).
PROCEDURE_TOOL = "run_procedure"

_NAME = r"^[a-z][a-z0-9_]{1,62}$"
_STEP_ID = r"^[a-z][a-z0-9_]{0,62}$"
_REF = re.compile(r"\$steps\.([a-z][a-z0-9_]{0,62})")
_INPUT_REF = re.compile(r"\$inputs\.([a-z][a-z0-9_]{0,62})")

_TARGETS = {"type": "object", "minProperties": 1, "additionalProperties": {"type": "string"}}

SCHEMA: dict = {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "type": "object",
    "required": ["name", "description", "steps"],
    "additionalProperties": False,
    "properties": {
        "name": {"type": "string", "pattern": _NAME},
        "description": {"type": "string", "minLength": 1},
        # Named inputs a caller may pass (`run_procedure(name, inputs)`), each described in words.
        "inputs": {
            "type": "object",
            "propertyNames": {"pattern": _STEP_ID},
            "additionalProperties": {
                "type": "object",
                "required": ["description"],
                "additionalProperties": False,
                "properties": {
                    "description": {"type": "string", "minLength": 1},
                    "required": {"type": "boolean"},
                },
            },
        },
        "steps": {
            "type": "array",
            "minItems": 1,
            "items": {
                "type": "object",
                "required": ["id", "kind"],
                "properties": {
                    "id": {"type": "string", "pattern": _STEP_ID},
                    "kind": {"enum": list(STEP_KINDS)},
                },
                "allOf": [
                    {
                        "if": {"properties": {"kind": {"const": "tool"}}},
                        "then": {
                            "required": ["tool"],
                            "additionalProperties": False,
                            "properties": {
                                "id": {},
                                "kind": {},
                                "tool": {"type": "string", "minLength": 1},
                                "args": {"type": "object"},
                                "named_by_request": {"type": "boolean"},
                                "next": {"type": "string"},
                            },
                        },
                    },
                    {
                        "if": {"properties": {"kind": {"enum": ["compute", "check"]}}},
                        "then": {
                            "required": ["function"],
                            "properties": {"function": {"type": "string", "minLength": 1}},
                        },
                    },
                    {
                        "if": {"properties": {"kind": {"const": "compute"}}},
                        "then": {
                            "additionalProperties": False,
                            "properties": {
                                "id": {},
                                "kind": {},
                                "function": {},
                                "args": {"type": "object"},
                                "next": {"type": "string"},
                            },
                        },
                    },
                    {
                        "if": {"properties": {"kind": {"const": "check"}}},
                        "then": {
                            "required": ["fail_sentence"],
                            "additionalProperties": False,
                            "properties": {
                                "id": {},
                                "kind": {},
                                "function": {},
                                "args": {"type": "object"},
                                "fail_sentence": {"type": "string", "minLength": 1},
                                "next": {"type": "string"},
                            },
                        },
                    },
                    {
                        "if": {"properties": {"kind": {"const": "decide"}}},
                        "then": {
                            "required": ["question", "branches", "on_fail"],
                            "additionalProperties": False,
                            "properties": {
                                "id": {},
                                "kind": {},
                                "question": {"type": "string", "minLength": 1},
                                "args": {"type": "object"},
                                "branches": {**_TARGETS, "minProperties": 2},
                                "on_fail": {"type": "string"},
                            },
                        },
                    },
                    {
                        "if": {"properties": {"kind": {"const": "ask"}}},
                        "then": {
                            "required": ["question", "answers"],
                            "additionalProperties": False,
                            "properties": {
                                "id": {},
                                "kind": {},
                                "question": {"type": "string", "minLength": 1},
                                "answers": _TARGETS,
                            },
                        },
                    },
                    {
                        "if": {"properties": {"kind": {"const": "say"}}},
                        "then": {
                            "required": ["template"],
                            "additionalProperties": False,
                            "properties": {
                                "id": {},
                                "kind": {},
                                "template": {"type": "string", "minLength": 1},
                            },
                        },
                    },
                ],
            },
        },
    },
}
_VALIDATOR = Draft202012Validator(SCHEMA)


class DefinitionError(ValueError):
    """A definition that may not be stored. `problems` are sentences, each naming the step."""

    def __init__(self, name: str, problems: list[str]):
        self.name = name
        self.problems = problems
        super().__init__(f"Procedure {name!r} was refused: " + " ".join(problems))


@dataclass(frozen=True)
class Checked:
    definition: dict
    writes: bool
    # Every branch the graph has, as (step id, branch name, target): the finite list the branch
    # report (ticket 14) checks "never ran" against.
    edges: tuple[tuple[str, str, str], ...]


def successors(step: dict) -> list[tuple[str, str]]:
    """(branch name, target step id) for every way out of a step. `next` is branch "next"."""
    if step.get("kind") == "decide":
        return list(step.get("branches", {}).items())
    if step.get("kind") == "ask":
        return list(step.get("answers", {}).items())
    return [("next", step["next"])] if "next" in step else []


def _schema_problems(name: str, definition) -> list[str]:
    problems = []
    for error in sorted(_VALIDATOR.iter_errors(definition), key=lambda e: list(e.absolute_path)):
        path = list(error.absolute_path)
        where = "the definition"
        if len(path) >= 2 and path[0] == "steps" and isinstance(path[1], int):
            steps = definition.get("steps") if isinstance(definition, dict) else None
            step = steps[path[1]] if isinstance(steps, list) and path[1] < len(steps) else None
            sid = step.get("id") if isinstance(step, dict) else None
            where = f"step {sid!r}" if sid else f"step {path[1] + 1}"
            rest = path[2:]
        else:
            rest = path
        field = ".".join(str(p) for p in rest)
        problems.append(
            f"In {name!r}, {where}{(' field ' + field) if field else ''}: {error.message}."
        )
    return problems


def _tools() -> dict:
    from engine_mcp.tools import TOOLS_BY_NAME

    return TOOLS_BY_NAME


def _functions() -> dict:
    from procedures.steps import FUNCTIONS

    return FUNCTIONS


def validate(definition) -> Checked:
    """The definition, checked against every load-time rule, or `DefinitionError`."""
    name = definition.get("name") if isinstance(definition, dict) else None
    name = name if isinstance(name, str) and name else "(unnamed)"
    problems = _schema_problems(name, definition)
    if problems:
        raise DefinitionError(name, problems)

    steps: list[dict] = definition["steps"]
    by_id: dict[str, dict] = {}
    for step in steps:
        if step["id"] in by_id:
            problems.append(
                f"In {name!r}, two steps are called {step['id']!r}; a step id is unique."
            )
        by_id[step["id"]] = step
    if problems:
        raise DefinitionError(name, problems)

    tools, functions = _tools(), _functions()
    edges: list[tuple[str, str, str]] = []
    for step in steps:
        sid, kind = step["id"], step["kind"]
        for branch, target in successors(step):
            if target not in by_id:
                problems.append(
                    f"In {name!r}, step {sid!r} goes to {target!r} on {branch!r}, "
                    "and there is no step by that name."
                )
            elif target == sid:
                problems.append(
                    f"In {name!r}, step {sid!r} goes to itself; a procedure has no loops."
                )
            edges.append((sid, branch, target))
        if kind != "say" and not successors(step):
            problems.append(
                f"In {name!r}, step {sid!r} ({kind}) leads nowhere; "
                "every path must end at a say step."
            )
        if kind == "tool":
            tool = step["tool"]
            if tool == PROCEDURE_TOOL:
                problems.append(
                    f"In {name!r}, step {sid!r} calls run_procedure; a procedure may not call a "
                    "procedure."
                )
            elif tool in READ_THROUGH_TOOLS:
                problems.append(
                    f"In {name!r}, step {sid!r} calls {tool!r}, which reads third-party content; "
                    "a run trace would store it, so no procedure may call it."
                )
            elif tool not in tools:
                problems.append(
                    f"In {name!r}, step {sid!r} calls {tool!r}, which is not an engine tool."
                )
            elif step.get("named_by_request") and not tools[tool].writes:
                problems.append(
                    f"In {name!r}, step {sid!r} marks {tool!r} named_by_request, but that tool "
                    "does not write; the mark is only for a write."
                )
        if kind in ("compute", "check") and step["function"] not in functions:
            problems.append(
                f"In {name!r}, step {sid!r} names the function {step['function']!r}, which is "
                "not in procedures/steps.py."
            )
        if kind == "decide" and step["on_fail"] not in step["branches"]:
            problems.append(
                f"In {name!r}, decide step {sid!r} falls back to {step['on_fail']!r} on failure, "
                "which is not one of its branches."
            )
    if problems:
        raise DefinitionError(name, problems)

    start = steps[0]["id"]
    order = _topological(name, start, by_id, problems)
    if problems:
        raise DefinitionError(name, problems)
    unreachable = [s["id"] for s in steps if s["id"] not in order]
    for sid in unreachable:
        problems.append(
            f"In {name!r}, step {sid!r} can never run: no path from {start!r} reaches it."
        )

    ancestors = _ancestors(order, by_id)
    inputs = definition.get("inputs", {})
    for step in steps:
        for ref in sorted(set(_INPUT_REF.findall(_text(step)))):
            if ref not in inputs:
                problems.append(
                    f"In {name!r}, step {step['id']!r} reads $inputs.{ref}, which the definition "
                    "does not declare under inputs."
                )
        for ref in sorted(set(_REF.findall(_text(step)))):
            if ref not in by_id:
                problems.append(
                    f"In {name!r}, step {step['id']!r} reads $steps.{ref}, "
                    "and there is no such step."
                )
            elif ref not in ancestors.get(step["id"], set()):
                problems.append(
                    f"In {name!r}, step {step['id']!r} reads $steps.{ref}, which does not run "
                    "before it."
                )

    writing = [
        s for s in steps if s["kind"] == "tool" and s["tool"] in tools and tools[s["tool"]].writes
    ]
    writes = bool(writing)
    if writes:
        for s in steps:
            if s["kind"] == "decide":
                problems.append(
                    f"In {name!r}, decide step {s['id']!r} is in a procedure that writes; a model "
                    "may not steer which write happens."
                )
        for s in writing:
            if s.get("named_by_request"):
                continue
            if _reachable_avoiding_ask(start, s["id"], by_id):
                problems.append(
                    f"In {name!r}, step {s['id']!r} writes with {s['tool']!r} and can be reached "
                    "without an ask step first; ask before it, or mark it named_by_request if the "
                    "request itself names that write."
                )
    if problems:
        raise DefinitionError(name, problems)
    return Checked(definition=definition, writes=writes, edges=tuple(edges))


def _text(step: dict) -> str:
    import json

    return json.dumps({k: v for k, v in step.items() if k not in ("id", "kind")})


def _topological(name: str, start: str, by_id: dict, problems: list[str]) -> list[str]:
    """Steps reachable from `start` in an order where every step follows its parents; a cycle
    is a problem naming one step on it."""
    state: dict[str, int] = {}  # 1 visiting, 2 done
    out: list[str] = []

    def visit(sid: str) -> bool:
        if state.get(sid) == 2:
            return True
        if state.get(sid) == 1:
            problems.append(
                f"In {name!r}, step {sid!r} is on a cycle; a procedure is a graph with no loops."
            )
            return False
        state[sid] = 1
        for _, target in successors(by_id[sid]):
            if not visit(target):
                return False
        state[sid] = 2
        out.append(sid)
        return True

    visit(start)
    return list(reversed(out))


def _ancestors(order: list[str], by_id: dict) -> dict[str, set[str]]:
    anc: dict[str, set[str]] = {sid: set() for sid in order}
    for sid in order:
        for _, target in successors(by_id[sid]):
            anc[target] |= anc[sid] | {sid}
    return anc


def _reachable_avoiding_ask(start: str, goal: str, by_id: dict) -> bool:
    """True when some path from `start` reaches `goal` passing through no `ask` step."""
    seen: set[str] = set()
    stack = [start]
    while stack:
        sid = stack.pop()
        if sid == goal:
            return True
        if sid in seen or by_id[sid]["kind"] == "ask":
            continue
        seen.add(sid)
        stack.extend(target for _, target in successors(by_id[sid]))
    return False
