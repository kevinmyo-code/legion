"""`remember` after third-party text, refused per turn (round 3, 2026-11-09;
`assistant/read_through.py`).

The phone refuses a memory write in a turn that read mail or a web page. Pinned here, for the
engine's doors: on the web assistant a turn that ran `web_search` or `brief_latest` refuses
`remember` in that turn and lets it through the next; a tab that sends no `turn` fails closed;
nobody's search taints another member's conversation; `/mcp` (no turn concept) may not write a
goal-plan constraint at all; a procedure step's third-party read taints its own request.
"""

from __future__ import annotations

import logging
from types import SimpleNamespace

import pytest
from django.db import DatabaseError

from assistant import read_through
from assistant.models import ReadThroughTurn
from engine_mcp import tools as engine_tools
from engine_mcp import web_search
from member_memory.models import MemberMemory
from tests.conftest import _member
from tests.test_assistant import _start, browser, google  # noqa: F401 - fixture
from tests.test_engine_mcp import call, client_with

pytestmark = [pytest.mark.django_db, pytest.mark.usefixtures("google")]


@pytest.fixture(autouse=True)
def fake_web(monkeypatch):
    """`web_search` answers from a fixed page, never the network."""
    monkeypatch.setattr(
        web_search,
        "search",
        lambda query, **_: web_search.WebAnswer(
            query=query,
            answer="Example Motors says the pump fails at 60,000 miles.",
            sources=[{"title": "Example Motors", "url": "https://example.com/pump"}],
        ),
    )


@pytest.fixture
def mcp_on(settings):
    settings.LEGION_MCP = True


def _mint(client) -> str:
    response = _start(client, utc_offset_minutes=0)
    assert response.status_code == 200, response.data
    return str(response.data["conversation_id"])


def _tool(client, name, args, conversation_id=None, turn=None):
    body = {"name": name, "args": args}
    if conversation_id is not None:
        body["conversation_id"] = conversation_id
    if turn is not None:
        body["turn"] = turn
    response = client.post("/api/assistant/tool", body, format="json")
    assert response.status_code == 200, response.data
    return response.data


def _remember(client, conversation_id=None, turn=None, text="The pump fails at 60,000 miles"):
    return _tool(client, "remember", {"text": text}, conversation_id, turn)


# =============================================================================
# The web assistant: one turn
# =============================================================================


def test_remember_is_refused_in_the_turn_that_searched_the_web(household_user):
    web = browser(household_user)
    conversation = _mint(web)
    searched = _tool(web, "web_search", {"query": "water pump life"}, conversation, turn=0)
    assert searched["is_error"] is False, searched
    result = _remember(web, conversation, turn=0)
    assert result["is_error"] is True
    assert result["text"] == read_through.REFUSAL
    assert result["response"] == {"success": False, "message": read_through.REFUSAL}
    assert "own words next turn" in result["text"]
    assert MemberMemory.objects.count() == 0


def test_the_next_turn_may_remember_what_the_user_says(household_user):
    web = browser(household_user)
    conversation = _mint(web)
    _tool(web, "web_search", {"query": "water pump life"}, conversation, turn=0)
    result = _remember(web, conversation, turn=1)
    assert result["is_error"] is False, result
    assert MemberMemory.objects.count() == 1


def test_a_turn_with_no_third_party_tool_remembers(household_user):
    web = browser(household_user)
    conversation = _mint(web)
    _tool(web, "list_checklists", {}, conversation, turn=0)
    assert _remember(web, conversation, turn=0)["is_error"] is False
    assert not ReadThroughTurn.objects.exists()


def test_the_brief_is_web_text_too(household_user):
    """`brief_latest`'s news section is a search-grounded summary, the twin of `get_sitrep`."""
    web = browser(household_user)
    conversation = _mint(web)
    _tool(web, "brief_latest", {}, conversation, turn=3)
    assert ReadThroughTurn.objects.get().turn == 3
    assert _remember(web, conversation, turn=3)["text"] == read_through.REFUSAL


def test_a_tab_that_sends_no_turn_fails_closed(household_user):
    web = browser(household_user)
    conversation = _mint(web)
    _tool(web, "web_search", {"query": "water pump life"}, conversation)
    assert ReadThroughTurn.objects.get().turn == ReadThroughTurn.UNKNOWN_TURN
    # An unknown turn taints every turn of the conversation, and a check with no turn reads any.
    assert _remember(web, conversation, turn=7)["text"] == read_through.REFUSAL
    other = _mint(web)
    _tool(web, "web_search", {"query": "water pump life"}, other, turn=2)
    assert _remember(web, other)["text"] == read_through.REFUSAL


def test_one_search_marks_its_turn_once(household_user):
    web = browser(household_user)
    conversation = _mint(web)
    for _ in range(2):
        _tool(web, "web_search", {"query": "water pump life"}, conversation, turn=0)
    assert ReadThroughTurn.objects.count() == 1


def test_another_members_search_taints_nobody_else(household_user, household_a):
    mine, theirs = browser(household_user), browser(_member(household_a, "member-b@example.com"))
    their_conversation = _mint(theirs)
    _tool(theirs, "web_search", {"query": "water pump life"}, their_conversation, turn=0)
    # Naming someone else's conversation marks nothing of theirs and reads none of their marks.
    _tool(mine, "web_search", {"query": "x"}, their_conversation, turn=1)
    assert list(ReadThroughTurn.objects.values_list("turn", flat=True)) == [0]
    assert _remember(mine, their_conversation, turn=0)["is_error"] is False
    my_conversation = _mint(mine)
    assert _remember(mine, my_conversation, turn=0)["is_error"] is False


def test_the_turn_must_be_a_count(household_user):
    web = browser(household_user)
    conversation = _mint(web)
    response = web.post(
        "/api/assistant/tool",
        {"name": "remember", "args": {"text": "x"}, "conversation_id": conversation, "turn": -1},
        format="json",
    )
    assert response.status_code == 400
    assert MemberMemory.objects.count() == 0


# =============================================================================
# Fail closed: a mark that cannot be written, or read
# =============================================================================


@pytest.fixture(autouse=True)
def no_failed_marks():
    read_through._UNMARKED.clear()
    yield
    read_through._UNMARKED.clear()


def _fail(*_args, **_kwargs):
    raise DatabaseError("the database is gone")


def test_a_mark_that_cannot_be_written_stops_the_search_and_every_later_remember(
    household_user, monkeypatch
):
    web = browser(household_user)
    conversation = _mint(web)
    record = read_through._record
    monkeypatch.setattr(read_through, "_record", _fail)
    searched = _tool(web, "web_search", {"query": "water pump life"}, conversation, turn=0)
    assert searched["is_error"] is True and searched["text"] == read_through.MARK_FAILED
    monkeypatch.setattr(read_through, "_record", record)
    for turn in (0, 1, None):
        result = _remember(web, conversation, turn=turn)
        assert result["is_error"] is True and result["text"] == read_through.REFUSAL, turn
    assert MemberMemory.objects.count() == 0
    # Another conversation is untouched.
    assert _remember(web, _mint(web), turn=0)["is_error"] is False


def test_marks_that_cannot_be_read_refuse_remember(household_user, monkeypatch):
    web = browser(household_user)
    conversation = _mint(web)
    _tool(web, "web_search", {"query": "water pump life"}, conversation, turn=0)
    monkeypatch.setattr(read_through, "_marked", _fail)
    for turn in (0, 1):
        result = _remember(web, conversation, turn=turn)
        assert result["is_error"] is True and result["text"] == read_through.REFUSAL, turn
    # Fail closed even in a conversation that never searched.
    assert _remember(web, _mint(web), turn=0)["text"] == read_through.REFUSAL
    assert MemberMemory.objects.count() == 0


# =============================================================================
# No turn the engine can see: /mcp, and a web call naming no conversation
# =============================================================================


@pytest.mark.parametrize("text", ["Plan constraint: no gym access", "  Plan constraint: no dairy"])
def test_mcp_may_not_write_a_goal_constraint(household_user, mcp_on, text):
    is_error, said, _ = call(client_with(household_user), "remember", {"text": text})
    assert is_error and said == read_through.CONSTRAINT_REFUSAL
    assert MemberMemory.objects.count() == 0


def test_mcp_still_remembers_anything_else(household_user, mcp_on):
    claude = client_with(household_user)
    call(claude, "web_search", {"query": "water pump life"})
    is_error, said, _ = call(claude, "remember", {"text": "The gym opens at six"})
    assert not is_error, said
    assert MemberMemory.objects.count() == 1
    assert not ReadThroughTurn.objects.exists()


def test_a_web_call_naming_no_conversation_takes_the_mcp_rule(household_user):
    web = browser(household_user)
    refused = _remember(web, text="Plan constraint: no gym access")
    assert refused["text"] == read_through.CONSTRAINT_REFUSAL
    assert _remember(web)["is_error"] is False


# =============================================================================
# One request that reads the web and then writes (a procedure's steps)
# =============================================================================


def test_run_engine_tool_flags_a_third_party_tool_before_its_handler(household_user):
    request = SimpleNamespace(user=household_user, path="/mcp", auth=None)
    engine_tools.run_tool(request, "legion_docs", {"query": "reconciliation gate"})
    assert not read_through.read_third_party(request)
    engine_tools.run_tool(request, "brief_latest", {})
    assert read_through.read_third_party(request)
    assert read_through.remember_refusal(request, "anything") == read_through.REFUSAL


def _read_through_lines(caplog):
    return [r.getMessage() for r in caplog.records if r.getMessage().startswith("read_through")]


def test_a_web_turn_logs_read_through_with_the_source_and_no_content(household_user, caplog):
    caplog.set_level(logging.INFO, logger="assistant.read_through")
    web = browser(household_user)
    conversation = _mint(web)
    _tool(web, "web_search", {"query": "example private query"}, conversation, turn=0)
    assert _read_through_lines(caplog) == ["read_through source=web_search"]
    logged = " ".join(r.getMessage() for r in caplog.records if r.name == "assistant.read_through")
    for content in ("example private query", "Example Motors", "example.com", conversation):
        assert content not in logged


def test_each_door_logs_read_through_and_other_tools_log_nothing(household_user, caplog):
    caplog.set_level(logging.INFO, logger="assistant.read_through")
    request = SimpleNamespace(user=household_user, path="/mcp", auth=None)
    engine_tools.run_tool(request, "legion_docs", {"query": "reconciliation gate"})
    assert _read_through_lines(caplog) == []
    engine_tools.run_tool(request, "brief_latest", {})
    assert _read_through_lines(caplog) == ["read_through source=brief_latest"]


def test_the_third_party_set_is_what_the_module_doc_says():
    assert read_through.THIRD_PARTY_TOOLS == {"web_search", "brief_latest", "github_status"}
    assert read_through.THIRD_PARTY_TOOLS <= set(engine_tools.TOOLS_BY_NAME)
