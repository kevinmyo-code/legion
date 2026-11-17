"""engine-brain ticket 02: the reusable runtime and graph, with a fake model and a fake gateway.
No database, no network: every model answer here is scripted."""

from __future__ import annotations

import ast
import json
import sys
from pathlib import Path

import pytest

from agents.graph.checkpoint import MemoryCheckpointStore
from agents.graph.graph import Graph, GraphError, NodeResult
from agents.graph.nodes import EDGES, NodeContext, turn_graph
from agents.graph.state import TurnState
from agents.runtime import model as m
from agents.runtime.budget import BudgetUsed, LoopLimits
from agents.runtime.loop import (
    BUDGET_WORDS,
    CANCELLED_WORDS,
    MODEL_WORDS,
    NUDGE,
    TIMEOUT_WORDS,
    LoopHooks,
    SentenceSplitter,
    run_loop,
)
from agents.runtime.spec import AgentSpec
from agents.runtime.tools import ToolOutcome

AGENTS = Path(__file__).resolve().parent.parent / "agents"


# =============================================================================
# The import boundary (ADR 0100)
# =============================================================================


def _imports(path: Path) -> set[str]:
    tree = ast.parse(path.read_text(encoding="utf-8"))
    found = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            found |= {alias.name for alias in node.names}
        elif isinstance(node, ast.ImportFrom) and node.level == 0 and node.module:
            found.add(node.module)
        elif isinstance(node, ast.ImportFrom) and node.level:
            raise AssertionError(f"{path.name}: relative import; name the package")
    return found


@pytest.mark.parametrize("package", ["runtime", "graph"])
def test_the_runtime_and_graph_import_nothing_from_legion(package):
    """`agents/runtime` and `agents/graph` import the standard library and each other only, so a
    second deployment could reuse them. Imports inside functions count too (an AST walk)."""
    files = sorted((AGENTS / package).rglob("*.py"))
    assert files
    bad = {}
    for path in files:
        for name in _imports(path):
            top = name.split(".")[0]
            if top in sys.stdlib_module_names or top == "__future__":
                continue
            if name.startswith(("agents.runtime", "agents.graph")):
                continue
            bad.setdefault(path.name, []).append(name)
    assert not bad, f"LEGION or third-party imports in agents/{package}: {bad}"


def test_the_boundary_check_would_catch_a_legion_import(tmp_path):
    probe = tmp_path / "probe.py"
    probe.write_text("def f():\n    from engine_mcp.tools import TOOLS\n", encoding="utf-8")
    assert "engine_mcp.tools" in _imports(probe)


# =============================================================================
# The loop
# =============================================================================


def _spec(**limits) -> AgentSpec:
    return AgentSpec(
        name="t",
        version=1,
        model="fake",
        prompt="p",
        tools=("read_x", "write_y"),
        limits=LoopLimits(**limits) if limits else LoopLimits(),
    )


def _state(text="hello") -> TurnState:
    return TurnState(
        turn_id="t",
        conversation_id="c",
        seq=1,
        household_id="h",
        member_id="1",
        client="test",
        transcript=text,
        messages=[{"role": "user", "parts": [{"text": text}]}],
    )


class FakeGateway:
    def __init__(self, ok=True, writes=False, slow=()):
        self.calls = []
        self.ok = ok
        self.writes = writes
        self.slow = set(slow)

    def declarations(self, spec):
        return [{"name": n} for n in spec.tools]

    def is_slow(self, name):
        return name in self.slow

    def call(self, state, spec, call, deadline_s):
        self.calls.append((call.name, call.args))
        return ToolOutcome(
            name=call.name,
            ok=self.ok,
            response={"success": self.ok, "message": "read 2 rows"},
            writes=self.writes,
            outcome="ok" if self.ok else "failed",
        )


def _drive(gen):
    events = []
    try:
        while True:
            events.append(next(gen))
    except StopIteration as stop:
        return events, stop.value


def test_a_plain_answer_streams_sentence_by_sentence():
    model = m.FakeModel([m.say("You have two tasks today. One is a quiz.")])
    state = _state()
    events, result = _drive(run_loop(_spec(), state, model, FakeGateway(), system="s"))
    assert [e.data["text"] for e in events if e.type == "sentence"] == [
        "You have two tasks today.",
        "One is a quiz.",
    ]
    assert result.status == "done" and result.final_text.startswith("You have two")
    assert state.messages[-1]["role"] == "model"
    assert state.budget.rounds == 1 and state.budget.prompt_tokens == 100


def test_a_tool_round_then_an_answer_and_the_model_turn_kept_verbatim():
    signed = {"functionCall": {"name": "read_x", "args": {"a": 1}}, "thoughtSignature": "sig"}
    first = [m.PartEvent(signed), m.ToolCallEvent("id1", "read_x", {"a": 1}), m.Finish("STOP")]
    model = m.FakeModel([first, m.say("Two rows.")])
    gateway = FakeGateway(slow={"read_x"})
    state = _state()
    events, result = _drive(run_loop(_spec(), state, model, gateway, system="s"))
    assert gateway.calls == [("read_x", {"a": 1})]
    assert [e.type for e in events] == ["working", "tool", "sentence"]
    # The model's turn goes back exactly as it came: the thought signature survives.
    assert state.messages[1] == {"role": "model", "parts": [signed]}
    response = state.messages[2]["parts"][0]["functionResponse"]
    assert response == {
        "name": "read_x",
        "id": "id1",
        "response": {"success": True, "message": "read 2 rows"},
    }
    assert state.actions == [
        {"name": "read_x", "writes": False, "ok": True, "outcome": "ok", "guard": None}
    ]
    assert result.final_text == "Two rows."


def test_the_last_round_turns_calling_off_and_carries_the_nudge():
    loop_call = m.call("read_x")
    model = m.FakeModel([loop_call, m.call("read_x"), m.say("Here is what I have.")])
    state = _state()
    _drive(run_loop(_spec(max_rounds=3), state, model, FakeGateway(), system="s"))
    assert [r.allow_tools for r in model.requests] == [True, True, False]
    # The nudge rides with the results that go into the last round.
    assert state.messages[-2]["parts"][-1] == {"text": NUDGE}


def test_cancel_stops_before_the_next_round_and_before_a_tool():
    flags = {"cancel": False}
    gateway = FakeGateway()

    class CancelOnCall(FakeGateway):
        def call(self, state, spec, call, deadline_s):
            flags["cancel"] = True
            return super().call(state, spec, call, deadline_s)

    gateway = CancelOnCall()
    model = m.FakeModel([m.call("read_x"), m.say("never")])
    hooks = LoopHooks(cancelled=lambda: flags["cancel"])
    _, result = _drive(run_loop(_spec(), _state(), model, gateway, system="s", hooks=hooks))
    assert result.status == "cancelled" and result.words == CANCELLED_WORDS
    assert len(model.requests) == 1


def test_a_model_failure_ends_the_turn_in_words():
    _, result = _drive(run_loop(_spec(), _state(), m.FakeModel([]), FakeGateway(), system="s"))
    assert result.status == "failed" and result.words == MODEL_WORDS


def test_the_turn_budget_stops_before_another_round():
    model = m.FakeModel([m.call("read_x"), m.say("never")])
    spec = _spec(max_input_tokens=50)
    _, result = _drive(run_loop(spec, _state(), model, FakeGateway(), system="s"))
    assert result.words == BUDGET_WORDS and len(model.requests) == 1


def test_the_turn_deadline_stops_the_loop():
    clock = {"now": 0.0}

    class SlowGateway(FakeGateway):
        def call(self, state, spec, call, deadline_s):
            clock["now"] = 50.0  # the tool took 50 s
            return super().call(state, spec, call, deadline_s)

    hooks = LoopHooks(clock=lambda: clock["now"], started=0.0)
    model = m.FakeModel([m.call("read_x"), m.say("never")])
    spec = _spec(turn_timeout_s=10)
    _, result = _drive(run_loop(spec, _state(), model, SlowGateway(), system="s", hooks=hooks))
    assert result.words == TIMEOUT_WORDS and len(model.requests) == 1


def test_cost_and_model_rows_are_reported_per_round():
    rows = []
    hooks = LoopHooks(cost_of=lambda u: u.prompt_tokens * 2, on_model_call=rows.append)
    state = _state()
    _drive(
        run_loop(
            _spec(),
            state,
            m.FakeModel([m.call("read_x"), m.say("ok.")]),
            FakeGateway(),
            system="s",
            hooks=hooks,
        )
    )
    assert [r["round"] for r in rows] == [0, 1]
    assert state.budget.cost_micro_usd == 400


def test_the_sentence_splitter_holds_an_unfinished_sentence():
    s = SentenceSplitter()
    assert s.feed("It is 3. Then") == ["It is 3."]
    assert s.feed(" more") == []
    assert s.flush() == ["Then more"]


# =============================================================================
# The graph
# =============================================================================


def test_every_path_through_the_turn_graph_ends_at_finish():
    paths = turn_graph().paths()
    assert set(paths) == {
        ("route", "agent", "finish"),
        ("route", "confirm_reply", "agent", "finish"),
        ("route", "finish"),
    }
    assert all(p[-1] == "finish" for p in paths)
    assert EDGES["finish"] == frozenset({None})


def _ctx(model, gateway, route=None, honesty=lambda s: False):
    spec = _spec()
    return NodeContext(
        model=model,
        gateway=gateway,
        route=route or (lambda s: {"agents": ["t"], "reason": "test"}),
        spec=lambda name: spec,
        system_for=lambda sp, st: "s",
        hooks=LoopHooks(),
        is_yes=lambda t: t.lower().startswith("yes"),
        honesty=honesty,
    )


def test_a_turn_checkpoints_after_every_node():
    store = MemoryCheckpointStore()
    events, state = _drive(
        turn_graph().run(_state(), _ctx(m.FakeModel([m.say("Hi.")]), FakeGateway()), store)
    )
    assert [r["node"] for r in store.saved] == ["route", "agent", "finish"]
    assert store.last("agent")["state"]["final_text"] == "Hi."
    assert events[-1].type == "done" and events[-1].data["final_text"] == "Hi."
    assert json.loads(json.dumps(store.saved))  # every checkpoint is JSON


def test_a_refused_route_never_calls_the_model():
    model = m.FakeModel([])
    ctx = _ctx(model, FakeGateway(), route=lambda s: {"agents": [], "refusal": "Not for you."})
    events, state = _drive(turn_graph().run(_state(), ctx, MemoryCheckpointStore()))
    assert model.requests == [] and state.status == "refused"
    assert [e.data.get("text") for e in events if e.type == "sentence"] == ["Not for you."]


def test_a_yes_releases_the_waiting_write_and_anything_else_drops_it():
    for said, released in (("yes, do it", "abc"), ("no wait", None)):
        st = _state(said)
        st.pending_confirm = {"tool": "write_y", "args_sha256": "abc"}
        store = MemoryCheckpointStore()
        _drive(turn_graph().run(st, _ctx(m.FakeModel([m.say("ok.")]), FakeGateway()), store))
        after = store.last("confirm_reply")["state"]
        assert after["confirmed"] == released and after["pending_confirm"] is None


def test_the_honesty_flag_is_set_at_finish_and_blocks_nothing():
    ctx = _ctx(m.FakeModel([m.say("Done. Added.")]), FakeGateway(), honesty=lambda s: True)
    events, state = _drive(turn_graph().run(_state(), ctx, MemoryCheckpointStore()))
    assert state.honesty_flag and events[-1].data["honesty_flag"]
    assert [e.data["text"] for e in events if e.type == "sentence"] == ["Done.", "Added."]


def test_a_node_taking_an_undeclared_edge_fails_loudly():
    def jump(state, ctx):
        return NodeResult(state, "nowhere")
        yield

    graph = Graph({"a": jump}, {"a": frozenset({None})}, start="a")
    with pytest.raises(GraphError):
        _drive(graph.run(_state(), None, MemoryCheckpointStore()))


def test_state_round_trips_through_json():
    st = _state()
    st.budget = BudgetUsed(rounds=2, prompt_tokens=9)
    st.add_taint("web_search")
    again = TurnState.from_json(json.loads(json.dumps(st.to_json())))
    assert again.budget.rounds == 2 and again.tainted and again.taint == ["web_search"]


# =============================================================================
# The Gemini seam (no network: a fake transport)
# =============================================================================


class FakeTransport:
    def __init__(self, lines):
        self._lines = lines
        self.seen = []

    def lines(self, url, headers, body, timeout):
        self.seen.append((url, headers, json.loads(body)))
        return iter(self._lines)


def _sse(obj) -> bytes:
    return b"data: " + json.dumps(obj).encode() + b"\n"


def test_gemini_parses_text_calls_usage_and_keeps_raw_parts():
    lines = [
        _sse({"candidates": [{"content": {"parts": [{"text": "Hi there."}]}}]}),
        _sse(
            {
                "candidates": [
                    {
                        "content": {
                            "parts": [
                                {
                                    "functionCall": {
                                        "name": "read_x",
                                        "args": {"a": 1},
                                        "id": "c1",
                                    },
                                    "thoughtSignature": "s",
                                }
                            ]
                        },
                        "finishReason": "STOP",
                    }
                ],
                "usageMetadata": {
                    "promptTokenCount": 120,
                    "cachedContentTokenCount": 100,
                    "candidatesTokenCount": 7,
                    "thoughtsTokenCount": 3,
                    "totalTokenCount": 130,
                },
            }
        ),
    ]
    transport = FakeTransport(lines)
    client = m.GeminiClient(key=lambda: "k-123", transport=transport)
    events = list(
        client.stream(
            m.ModelRequest(
                model="flash", system="sys", contents=[], declarations=[{"name": "read_x"}]
            )
        )
    )
    assert m.TextDelta("Hi there.") in events
    assert m.ToolCallEvent("c1", "read_x", {"a": 1}) in events
    assert m.Usage(120, 100, 10, 130) in events
    url, headers, body = transport.seen[0]
    assert "k-123" not in url and headers["x-goog-api-key"] == "k-123"
    assert body["tools"] == [{"functionDeclarations": [{"name": "read_x"}]}]


def test_the_last_round_request_keeps_the_declarations_but_turns_calling_off():
    body = m.request_body(
        m.ModelRequest(
            model="x", system="s", contents=[], declarations=[{"name": "a"}], allow_tools=False
        )
    )
    assert body["toolConfig"] == {"functionCallingConfig": {"mode": "NONE"}}
    cached = m.request_body(
        m.ModelRequest(model="x", system="s", contents=[], cached_content="cachedContents/1")
    )
    assert cached["cachedContent"] == "cachedContents/1" and "systemInstruction" not in cached


def test_a_gemini_error_line_is_a_model_failure_without_the_key():
    client = m.GeminiClient(
        key=lambda: "k-123", transport=FakeTransport([_sse({"error": {"code": 429}})])
    )
    with pytest.raises(m.ModelFailure) as exc:
        list(client.stream(m.ModelRequest(model="x", system="s", contents=[])))
    assert "k-123" not in str(exc.value)


def test_no_key_sends_nothing():
    transport = FakeTransport([])
    with pytest.raises(m.ModelFailure):
        list(
            m.GeminiClient(key=lambda: None, transport=transport).stream(
                m.ModelRequest(model="x", system="s", contents=[])
            )
        )
    assert transport.seen == []


def test_a_recording_replays_identically():
    inner = m.FakeModel([m.call("read_x", {"a": 1}), m.say("Fine.")])
    rec = m.RecordingModel(inner)
    first = [list(rec.stream(m.ModelRequest(model="x", system="s", contents=[]))) for _ in range(2)]
    replay = m.CassetteModel(json.loads(json.dumps(rec.rounds)))
    again = [
        list(replay.stream(m.ModelRequest(model="x", system="s", contents=[]))) for _ in range(2)
    ]
    assert first == again
    with pytest.raises(m.ModelFailure):
        list(replay.stream(m.ModelRequest(model="x", system="s", contents=[])))
