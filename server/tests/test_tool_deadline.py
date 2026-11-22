# ruff: noqa: F811 - fixtures imported from the cascade tests
"""Round 3 design change D1 (`review-demo-voice-first-1117-turn-r3.md` N1, N2, N7): no tool thread
pool. A tool runs inline on the turn's thread, and its deadline is enforced where its time is
spent: Postgres cancels a slow statement or a wait on a lock and rolls the tool's transaction
back, and every outbound request a tool can reach is capped by the time left. A write either
committed inside the deadline or did not happen, so "uncertain" cannot occur.

Every test here runs the real door in autocommit (`transaction=True`), as production does."""

from __future__ import annotations

import ast
import dataclasses
import inspect
import threading
import time
import uuid
from pathlib import Path

import pytest
from django.db import connection, transaction

from engine_mcp.tools import failed, ok
from tests.test_assistant_cascade import (  # noqa: F401 - fixtures
    _clean,
    _events,
    _of,
    _post,
    engine,
    member,
    visitor,
    visitor_user,
)

pytestmark = pytest.mark.django_db(transaction=True)

# The real web search transport, taken at import: `conftest._no_web_search` replaces it per test.
from engine_mcp import web_search as _web_search  # noqa: E402

REAL_WEB_TRANSPORT = _web_search._transport

DEADLINE_S = 0.5


@pytest.fixture
def short_deadline(monkeypatch):
    from assistant import cascade

    monkeypatch.setattr(
        cascade, "LIMITS", dataclasses.replace(cascade.LIMITS, tool_timeout_s=DEADLINE_S)
    )


def _swallowing(work):
    """A handler as `run_engine_tool` runs it: an exception becomes a failed result."""

    def run(request, name, args):
        try:
            return work(request)
        except Exception as exc:  # noqa: BLE001 - the shape run_engine_tool gives every handler
            return failed(f"error {type(exc).__name__}")

    return run


def test_a_slow_read_is_cancelled_by_the_database_at_its_deadline(
    member, engine, short_deadline, monkeypatch
):
    from agents.runtime.model import FakeModel, call, say
    from assistant import cascade

    def slow(request):
        with connection.cursor() as cur:
            cur.execute("SELECT pg_sleep(5)")
        return ok("late")

    monkeypatch.setattr(cascade, "run_tool", _swallowing(slow))
    engine.model = FakeModel([call("list_events", {}), say("That took too long.")])
    started = time.monotonic()
    events = _events(_post(member, text="What's on?"))
    assert time.monotonic() - started < 3.0
    tool = _of(events, "tool")[0]
    assert tool["ok"] is False and "took too long" in tool["text"]


def test_a_write_that_runs_past_its_deadline_is_rolled_back_not_uncertain(
    member, engine, short_deadline, monkeypatch, household_user
):
    from agents.runtime.model import FakeModel, call, say
    from assistant import cascade
    from assistant.models import TurnSeen

    marker = uuid.uuid4()

    def write_then_stall(request):
        TurnSeen.objects.create(turn_id=marker, user=household_user)
        with connection.cursor() as cur:
            cur.execute("SELECT pg_sleep(5)")
        return ok("Added.")

    monkeypatch.setattr(cascade, "run_tool", _swallowing(write_then_stall))
    engine.model = FakeModel([call("add_event", {"fields": {"title": "x"}}), say("Not added.")])
    events = _events(_post(member, text="Add a dentist visit."))
    tool = _of(events, "tool")[0]
    assert tool["ok"] is False and "took too long" in tool["text"]
    assert "Nothing was changed" in tool["text"]
    assert not TurnSeen.objects.filter(turn_id=marker).exists()
    said = " ".join(e["text"] for e in _of(events, "sentence"))
    assert "still running" not in said and "check it" not in said


def test_a_write_waiting_on_a_locked_row_gives_up_at_its_deadline(
    member, engine, short_deadline, monkeypatch, household_user
):
    from agents.runtime.model import FakeModel, call, say
    from assistant import cascade
    from assistant.models import TurnSeen

    row = TurnSeen.objects.create(turn_id=uuid.uuid4(), user=household_user)
    locked, release = threading.Event(), threading.Event()

    def holder():
        from django.db import connections

        try:
            with transaction.atomic():
                TurnSeen.objects.select_for_update().get(pk=row.pk)
                locked.set()
                release.wait(10)
        finally:
            connections.close_all()

    thread = threading.Thread(target=holder)
    thread.start()
    assert locked.wait(5)

    def update(request):
        TurnSeen.objects.filter(pk=row.pk).update(turn_id=uuid.uuid4())
        return ok("Updated.")

    try:
        monkeypatch.setattr(cascade, "run_tool", _swallowing(update))
        engine.model = FakeModel([call("update_event", {"id": "x"}), say("Not changed.")])
        started = time.monotonic()
        events = _events(_post(member, text="Move it."))
        assert time.monotonic() - started < 3.0
    finally:
        release.set()
        thread.join(10)
    tool = _of(events, "tool")[0]
    assert tool["ok"] is False and "took too long" in tool["text"]
    row.refresh_from_db()
    assert TurnSeen.objects.filter(pk=row.pk, turn_id=row.turn_id).exists()


def test_a_tool_runs_on_the_turns_own_thread(member, engine, monkeypatch):
    from agents.runtime.model import FakeModel, call, say
    from assistant import cascade

    seen = []

    def where(request, name, args):
        seen.append(threading.get_ident())
        return ok("Nothing on.")

    monkeypatch.setattr(cascade, "run_tool", where)
    engine.model = FakeModel([call("list_events", {}), say("Nothing on.")])
    here = threading.get_ident()
    _events(_post(member, text="What's on?"))
    assert seen == [here]
    assert not hasattr(cascade, "_TOOL_POOL")
    assert not hasattr(cascade, "CHANGE_UNCERTAIN")


def test_an_outbound_request_inside_a_tool_is_capped_by_the_time_left():
    from legion import deadline

    assert deadline.cap(10) == 10
    with deadline.within(0.4):
        assert deadline.cap(10) <= 0.4
        assert deadline.cap(0.1) == 0.1


# ---- the registry walk: every outbound call a tool can reach passes a capped timeout ------------

SERVER = Path(__file__).resolve().parent.parent
# Calls that open a connection to another host.
OUTBOUND = {"urlopen", "HTTPConnection", "HTTPSConnection", "create_connection"}


def _project_module(name: str) -> Path | None:
    first = name.split(".")[0]
    if not (SERVER / first).is_dir() or first == "tests":
        return None
    path = SERVER / Path(*name.split("."))
    if path.with_suffix(".py").is_file():
        return path.with_suffix(".py")
    if (path / "__init__.py").is_file():
        return path / "__init__.py"
    return None


def _imports(tree: ast.AST, package: str) -> set[str]:
    out = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            out.update(a.name for a in node.names)
        elif isinstance(node, ast.ImportFrom):
            if node.level:
                base = package.rsplit(".", node.level - 1)[0] if node.level > 1 else package
                mod = f"{base}.{node.module}" if node.module else base
            else:
                mod = node.module or ""
            out.add(mod)
            out.update(f"{mod}.{a.name}" for a in node.names)
    return out


def _outbound_calls(tree: ast.AST):
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        func = node.func
        name = func.attr if isinstance(func, ast.Attribute) else getattr(func, "id", "")
        opener = (
            isinstance(func, ast.Attribute)
            and func.attr == "open"
            and "OPENER" in ast.unparse(func.value).upper()
        )
        if name in OUTBOUND or opener:
            yield node


def _capped(node: ast.Call) -> bool:
    timeout = next((k.value for k in node.keywords if k.arg == "timeout"), None)
    if timeout is None:
        return False
    return any(
        isinstance(n, ast.Call) and ast.unparse(n.func).split(".")[-1] == "cap"
        for n in ast.walk(timeout)
    )


# Outbound transports the import walk reaches that no tool calls inside a turn: scheduled jobs and
# other routes. Each is named with what does call it; they keep their own timeouts unchanged. A
# new outbound call anywhere else must be capped (or listed here with its reason).
NOT_IN_A_TURN = {
    "brief/news.py": "the morning brief job (`morning_brief`); `brief_latest` reads the stored one",
    "finance/fx.py": "the `fx_rates` job; money tools read stored rates (`fx.rate_on`)",
    "ingest/drive.py": "the Drive statements job; web_search borrows only its opener object",
    "ingest/statements.py": "the statements job (PDF extraction)",
    "member_memory/consolidate.py": "the memory consolidation job",
}
# The transports tools DO call in a turn, each capped: web_search, github_status,
# vehicle_recalls, check_conditions, and run_procedure's decision step.
IN_A_TURN = {
    "engine_mcp/web_search.py",
    "engine_mcp/github_status.py",
    "engine_mcp/recalls.py",
    "conditions/sources.py",
    "procedures/decide.py",
}


def test_every_outbound_call_a_tool_can_reach_is_capped_by_the_deadline():
    from assistant.surface import RECRUITER_ONLY_TOOLS, web_tools
    from engine_mcp.tools import TOOLS

    tools = [*TOOLS, *RECRUITER_ONLY_TOOLS.values(), *web_tools()]
    todo = {inspect.getmodule(t.handler).__name__ for t in tools}
    seen: set[str] = set()
    found: set[str] = set()
    missing = []
    while todo:
        name = todo.pop()
        if name in seen:
            continue
        seen.add(name)
        path = _project_module(name)
        if path is None:
            continue
        tree = ast.parse(path.read_text(encoding="utf-8"))
        package = name if path.name == "__init__.py" else name.rsplit(".", 1)[0]
        todo.update(m for m in _imports(tree, package) if m not in seen)
        where = path.relative_to(SERVER).as_posix()
        for node in _outbound_calls(tree):
            found.add(where)
            if where not in NOT_IN_A_TURN and not _capped(node):
                missing.append(f"{where}:{node.lineno}")
    assert len(seen) > 20  # the walk really followed the imports
    assert not missing, f"outbound calls without timeout=deadline.cap(...): {missing}"
    assert IN_A_TURN <= found, IN_A_TURN - found
    assert set(NOT_IN_A_TURN) <= found, set(NOT_IN_A_TURN) - found  # no stale entry


def test_the_capped_timeout_reaches_the_transport(monkeypatch):
    """The runtime half: inside a deadline, the web search transport is handed the time left."""
    from engine_mcp import web_search
    from legion import deadline

    handed = []

    class Reply:
        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def read(self):
            return b"{}"

        status = 200
        headers: dict = {}

    def open_(request, timeout):
        handed.append(timeout)
        return Reply()

    monkeypatch.setattr(web_search._OPENER, "open", open_)
    with deadline.within(0.3):
        try:
            REAL_WEB_TRANSPORT("POST", "https://example.invalid/", {}, b"{}")
        except Exception:  # noqa: BLE001 - only the timeout handed over matters here
            pass
    assert handed and handed[0] <= 0.3


# ---- round 4 turn review R1: a web search never holds the demo budget row during its call --------


def test_a_search_in_flight_does_not_hold_the_demo_budget_row(visitor, engine, monkeypatch):
    from agents.runtime.model import FakeModel, call, say
    from demo import budget
    from demo.models import DemoBudgetDay
    from engine_mcp import web_search

    in_flight, finish = threading.Event(), threading.Event()

    def slow_search(query, **_kw):
        in_flight.set()
        finish.wait(5)
        return web_search.WebAnswer(query=query, answer="Sunny all week.")

    monkeypatch.setattr(web_search, "search", slow_search)
    engine.model = FakeModel([call("web_search", {"query": "weather"}), say("Sunny all week.")])
    result = {}

    def turn():
        try:
            result["events"] = _events(_post(visitor, text="What's the weather?"))
        finally:
            from django.db import connections

            connections.close_all()

    thread = threading.Thread(target=turn)
    thread.start()
    try:
        assert in_flight.wait(5)
        started = time.monotonic()
        other = budget.reserve_cascade_turn(rounds=1, spoken=False)  # another visitor's turn
        waited = time.monotonic() - started
    finally:
        finish.set()
        thread.join(10)
    assert other is not None and waited < 0.5, waited
    tool = _of(result["events"], "tool")[0]
    assert tool["name"] == "web_search" and tool["ok"] is True
    assert DemoBudgetDay.objects.get().web_searches == 1  # charged once, and kept
