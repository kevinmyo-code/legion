"""The runtime's `Waiting` item (perf/try-faster-first-word rule A). Only a model client that reads
the model on its own thread makes one: `assistant.cascade.Ticking`, used by the web cascade's answer
path alone. The engine-brain turn (`agents.graph.nodes`, which drives the same `run_loop`) wraps no
such client, so it never sees a `waiting` event; and for any caller, the events a round makes are the
same with or without `Waiting` items, apart from the `waiting` events themselves."""

from __future__ import annotations

import ast
import os
from pathlib import Path

from agents.runtime import model as m
from agents.runtime.loop import run_loop
from tests.test_agents_runtime import FakeGateway, _drive, _spec, _state

SERVER = Path(__file__).resolve().parents[1]


def _rounds():
    signed = {"functionCall": {"name": "read_x", "args": {}}, "thoughtSignature": "sig"}
    tool = [m.PartEvent(signed), m.ToolCallEvent("id1", "read_x", {}), m.Finish("STOP")]
    return [tool, m.say("Two rows. That is all.")]


def _with_waiting(rounds):
    return [[m.Waiting(), m.Waiting(), *items] for items in rounds]


def _shape(events):
    return [(e.type, e.data) for e in events]


def test_a_model_that_never_waits_gives_no_waiting_event():
    events, _ = _drive(run_loop(_spec(), _state(), m.FakeModel(_rounds()), FakeGateway(), system="s"))
    assert "waiting" not in [e.type for e in events]


def test_waiting_items_add_waiting_events_and_change_nothing_else():
    plain_events, plain = _drive(
        run_loop(_spec(), _state(), m.FakeModel(_rounds()), FakeGateway(), system="s")
    )
    waited_events, waited = _drive(
        run_loop(_spec(), _state(), m.FakeModel(_with_waiting(_rounds())), FakeGateway(), system="s")
    )
    assert [e.type for e in waited_events].count("waiting") == 4
    assert _shape([e for e in waited_events if e.type != "waiting"]) == _shape(plain_events)
    assert (waited.status, waited.final_text, waited.rounds) == (
        plain.status,
        plain.final_text,
        plain.rounds,
    )


def test_only_the_web_cascade_wraps_a_client_that_makes_waiting_items():
    makers = []
    skip = {"tests", ".venv", "migrations", "frontend", "node_modules", "static", "staticfiles"}
    for root, dirs, files in os.walk(SERVER):
        dirs[:] = [d for d in dirs if d not in skip and not d.startswith(".")]
        for name in files:
            if not name.endswith(".py"):
                continue
            path = Path(root) / name
            tree = ast.parse(path.read_text(encoding="utf-8"))
            for node in ast.walk(tree):
                if isinstance(node, ast.Call) and getattr(node.func, "id", None) in (
                    "Waiting",
                    "Ticking",
                ):
                    makers.append(path.relative_to(SERVER).as_posix())
    assert sorted(set(makers)) == ["assistant/cascade.py"]
