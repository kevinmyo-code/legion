"""engine-brain plan 1119 section 5: outside text never lands in what the engine stores or logs.

A marker string (`CANARY`) is put inside outside text: (a) a mail-class call through the limb
(must never even be requested), (b) a relayed `browser read` result, (c) an engine `web_search`
result, (d) an engine `brief_latest` result, (e) a web search followed by a relayed device call.
The fake model echoes the marker in its reply and in a later call's arguments. After each turn
every text and JSON column of every `agents_*` table, `llm_usage` and `devicelogs` is scanned, and
the captured log; the marker must be in none of them. What MAY carry it: the live `sentence`
events of that same response (plan section 5: "Live answer: unchanged"). Nothing else the engine
emits: not `done`, not a `tool_request`.

Redaction is by CLASS (the call's outside-text class), never by name: `browser` matches no mail
pattern and is still stubbed. The phone half (an engine turn whose `done.tainted` is true writes a
`read_through` audit line, so consolidation drops it) is shown on consolidation's own reader.
"""

# ruff: noqa: F811 - the `engine` fixture is imported from the voice turn tests

from __future__ import annotations

import logging

import pytest
from django.db import connection

from agents.models import AgentTurn
from agents.runtime import model as m
from engine_mcp.tools import ok
from tests.test_agents_relay_api import (
    attach,
    calls,
    events_of,
    kinds,
    of,
    post_result,
    say_turn,
)
from tests.test_engine_voice_turn import engine  # noqa: F401

pytestmark = pytest.mark.django_db

CANARY = "CANARY-7f3a"
OUTSIDE = f"An invented page says: {CANARY} is the answer."


def _scan() -> list[str]:
    """Every table and text/json column holding the marker, as `table.column`."""
    found = []
    with connection.cursor() as cursor:
        cursor.execute(
            """
            select table_name, column_name from information_schema.columns
            where table_schema = 'public'
              and (table_name like 'agents\\_%%' or table_name in ('llm_usage')
                   or table_name like 'devicelogs\\_%%')
              and data_type in ('text', 'jsonb', 'json', 'character varying')
            """
        )
        columns = cursor.fetchall()
        for table, column in columns:
            cursor.execute(
                f'select count(*) from "{table}" where "{column}"::text like %s',  # noqa: S608
                [f"%{CANARY}%"],
            )
            if cursor.fetchone()[0]:
                found.append(f"{table}.{column}")
    return found


@pytest.fixture
def limb(auth_client, engine):
    from django.core.cache import cache

    cache.clear()
    return attach(auth_client)


@pytest.fixture
def engine_outside(monkeypatch):
    """`web_search` and `brief_latest` answer with the marker; every other tool runs as built."""
    from agents.legion import gateway

    real = gateway.run_engine_tool

    def fake(request, tool, args):
        if tool.name in ("web_search", "brief_latest"):
            return ok(OUTSIDE)
        return real(request, tool, args)

    monkeypatch.setattr(gateway, "run_engine_tool", fake)


def _emitted_outside_sentences(events) -> list:
    return [(k, d) for k, d in events if k != "sentence" and CANARY in str(d)]


def test_a_mail_class_call_is_never_requested(auth_client, limb, engine, caplog):
    caplog.set_level(logging.DEBUG)
    engine.rounds = [
        calls(("look_up", {"domain": "ask_mail"}), ("ask_mail", {})),
        m.say("I can't read mail that way."),
    ]
    events = say_turn(auth_client, "what did the invented shop write")
    assert not of(events, "tool_request")
    assert _scan() == [] and CANARY not in caplog.text


def test_a_relayed_read_through_result_is_stubbed_everywhere(auth_client, limb, engine, caplog):
    caplog.set_level(logging.DEBUG)
    engine.rounds = [m.call("browser", {"action": "read"})]
    first = say_turn(auth_client, "read me that page")
    turn_id = first[0][1]["turn_id"]
    call_id = of(first, "tool_request")[0]["call_id"]
    # The model echoes the marker, then tries a device call carrying it (refused: no pause), then
    # says it again.
    engine.rounds = [
        [m.PartEvent({"text": f"It says {CANARY}."}), m.TextDelta(f"It says {CANARY}. ")]
        + calls(("set_timer", {"seconds": 5, "label": CANARY}))[:-2]
        + [m.Usage(100, 0, 5, 105), m.Finish("STOP")],
        m.say(f"Again: {CANARY}."),
    ]
    rest = events_of(post_result(auth_client, turn_id, call_id, words=OUTSIDE))
    assert CANARY in str(of(rest, "sentence")), "the live answer is unchanged"
    assert not of(rest, "tool_request")
    assert _emitted_outside_sentences(rest) == []
    assert _scan() == []
    assert CANARY not in caplog.text
    turn = AgentTurn.objects.get(pk=turn_id)
    assert turn.final_text == "[reply after an outside read: not kept]"
    assert turn.pending_relay is None


@pytest.mark.parametrize("tool", ["web_search", "brief_latest"])
def test_an_engine_third_party_result_is_stubbed_everywhere(
    auth_client, limb, engine, engine_outside, caplog, tool
):
    caplog.set_level(logging.DEBUG)
    args = {"query": "the invented ferry"} if tool == "web_search" else {}
    engine.rounds = [
        m.call(tool, args),
        calls(("list_events", {"query": CANARY})),
        m.say(f"The answer is {CANARY}."),
    ]
    events = say_turn(auth_client, "look it up")
    assert CANARY in str(of(events, "sentence"))
    assert _emitted_outside_sentences(events) == []
    assert events[-1][1]["tainted"] is True
    assert _scan() == []
    assert CANARY not in caplog.text


def test_a_search_then_a_device_call_never_pauses_with_the_text(
    auth_client, limb, engine, engine_outside
):
    engine.rounds = [
        m.call("web_search", {"query": "the invented depot"}),
        m.call("navigate", {"to": CANARY}),
        m.say("I'll navigate when you ask again."),
    ]
    events = say_turn(auth_client, "find the depot and take me there")
    assert "tool_request" not in kinds(events)
    turn = AgentTurn.objects.get(pk=events[0][1]["turn_id"])
    assert turn.pending_relay is None and turn.status == "done"
    assert _scan() == []


def test_with_k1_permissive_the_device_action_is_emitted_live_and_never_stored(
    auth_client, limb, engine, engine_outside, monkeypatch
):
    from agents.legion import relay

    monkeypatch.setattr(relay, "AFTER_OUTSIDE_TEXT", frozenset({"navigate"}))
    engine.rounds = [
        m.call("web_search", {"query": "the invented depot"}),
        m.call("navigate", {"to": CANARY}),
        m.say("Navigation starts after this."),
    ]
    events = say_turn(auth_client, "find the depot and take me there")
    assert events[-1][1]["device_actions"] == [{"name": "navigate", "args": {"to": CANARY}}]
    assert _scan() == []


def test_the_phone_marks_a_tainted_engine_turn_so_consolidation_drops_it():
    """P1 writes `read_through source=engine` from `done.tainted`; consolidation's reader then
    keeps nothing of that turn."""
    from member_memory.consolidate import transcript

    raw = "\n".join(
        [
            '11-05 10:00:01.000  1 2 I LIVE_AUDIT: user final=true text="Read me that page."',
            "11-05 10:00:01.100  1 2 I LIVE_AUDIT: read_through source=engine",
            f'11-05 10:00:01.500  1 2 I LIVE_AUDIT: assistant text="It says {CANARY}."',
            "11-05 10:00:02.000  1 2 I LIVE_AUDIT: turn_complete",
        ]
    )
    assert CANARY not in transcript(raw).text


def test_the_scan_finds_the_marker_where_it_is(auth_client, limb, engine):
    """The control: the scan is not blind. His own words are kept (a transcript is his), so a
    marker he says is found."""
    engine.rounds = [m.say("Noted.")]
    say_turn(auth_client, f"remember the code word {CANARY}")
    assert "agents_turn.transcript" in _scan()
