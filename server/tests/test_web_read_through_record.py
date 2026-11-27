"""Third-party text never reaches a stored web conversation record (CLAUDE.md section 7, #475).

The web record is written by the engine (`devicelogs.web_conversation`), so it redacts at write
time the way the phone does on the device: a read-through tool's reply, and every assistant line
from that turn on, become `[redacted: third-party content]`. His own words stay.
"""

from __future__ import annotations

import gzip
import json
from datetime import UTC, datetime, timedelta
from io import StringIO
from types import SimpleNamespace

import pytest
from django.core.management import call_command

from devicelogs import web_conversation as wc
from devicelogs.models import DeviceLog
from devicelogs.read_through_lines import READ_THROUGH_TOOLS, REDACTED, redact
from engine_mcp import web_search
from member_memory import consolidate
from tests.test_assistant import _start, browser, google  # noqa: F401 - fixture

T0 = datetime(2026, 11, 26, 9, 0, tzinfo=UTC)
PAGE = "Example Motors says the pump fails at 60,000 miles."
MARK = json.dumps(REDACTED)


def _at(seconds: float) -> datetime:
    return T0 + timedelta(seconds=seconds)


def _user(s, text="What does the web say about the pump?"):
    return wc.engine_user_line(_at(s), text, typed=True)


def _said(s, text):
    return wc.engine_assistant_line(_at(s), text)


def _tool(s, name, text=PAGE):
    return wc.tool_lines(
        call_id=f"c{s}",
        name=name,
        args={"query": "pump"},
        started=_at(s),
        ended=_at(s + 0.5),
        success=True,
        outcome="ok",
        text=text,
    )


def _texts(stamped):
    return [text for _, text in stamped]


def test_one_list_of_read_through_tools():
    """Memory consolidation and the record redact by the same set, which holds every engine
    third-party tool."""
    from assistant.read_through import THIRD_PARTY_TOOLS

    assert consolidate.READ_THROUGH_TOOLS is READ_THROUGH_TOOLS
    assert THIRD_PARTY_TOOLS <= READ_THROUGH_TOOLS


# The engine's three third-party tools, and a mail-shaped name from the phone's half of the list
# (no engine tool reads mail, `tests/test_engine_mcp.py`; the guard still holds for the name).
@pytest.mark.parametrize("tool", ["web_search", "brief_latest", "github_status", "read_mail"])
def test_a_read_through_tools_reply_and_the_answer_after_it_are_redacted(tool):
    fresh = [_user(0), *_tool(1, tool), _said(3, f"It says: {PAGE}")]
    body = "\n".join(_texts(redact([], fresh)))
    assert PAGE not in body
    assert f"name={tool} success=true" in body  # the call itself stays on the record
    assert f"response={MARK}" in body
    assert f"assistant text={MARK}" in body
    assert 'user final=true typed=true text="What does the web say about the pump?"' in body


def test_a_household_tools_reply_and_answer_stay():
    fresh = [_user(0), *_tool(1, "list_checklists", "Groceries."), _said(3, "You have Groceries.")]
    assert redact([], fresh) == fresh


def test_a_later_assistant_line_in_the_same_conversation_is_redacted():
    """Sticky, as on the phone: the search ran in an earlier write; the next turn's answer may
    still repeat the page, and its user line stays his."""
    stored = _texts([_user(0), *_tool(1, "web_search"), _said(3, "It says something.")])
    later = [_user(10, "And the price?"), _said(12, "About forty dollars, the page said.")]
    kept = _texts(redact(stored, later))
    assert kept[0] == later[0][1]
    assert kept[1].endswith(f"assistant text={MARK}")


def test_words_before_the_read_turn_stay_and_the_read_turns_own_opening_does_not():
    earlier = [_user(0, "Hi"), _said(1, "Morning.")]
    turn = [_user(5), _said(6, "Let me look."), *_tool(7, "web_search"), _said(9, PAGE)]
    kept = _texts(redact([], earlier + turn))
    assert 'assistant text="Morning."' in kept[1]
    assert all(text.endswith(f"assistant text={MARK}") for text in (kept[3], kept[7]))


def test_a_read_through_or_already_redacted_line_taints_what_follows():
    """`read_through source=carry` (the cascade's carry) and a redacted reply from a procedure
    count as a read, as memory consolidation counts them."""
    for marker in (
        wc.engine_read_through_line(_at(1), "carry"),
        (
            _at(1),
            wc.line(
                _at(1),
                2,
                wc.AUDIT,
                f"tool_result id=p name=run_procedure success=true "
                f"ms=1 scheduling=none sent=true response={MARK}",
            ),
        ),
    ):
        kept = _texts(redact([], [_user(0), marker, _said(2, PAGE)]))
        assert kept[2].endswith(f"assistant text={MARK}")


def test_the_replay_line_is_redacted_too():
    replay = wc.engine_replay_line(_at(3), PAGE)
    kept = _texts(redact([], [_user(0), *_tool(1, "web_search"), replay]))
    assert kept[-1].endswith(f"assistant_replay text={MARK}")


def test_stored_lines_are_never_rewritten():
    stored = _texts([_user(0), *_tool(1, "web_search")])
    before = list(stored)
    redact(stored, [_said(3, PAGE)])
    assert stored == before


def _row(lines) -> DeviceLog:
    body = ("\n".join(_texts(lines)) + "\n").encode()
    return DeviceLog(body_gz=gzip.compress(body))


def test_history_and_last_reply_never_hand_back_the_marker():
    lines = [
        _user(0, "Hi"),
        _said(1, "Morning."),
        _user(5),
        *redact([], [*_tool(6, "web_search"), _said(8, PAGE)]),
    ]
    row = _row(lines)
    turns, users = wc.history(row, max_turns=10)
    assert REDACTED not in json.dumps(turns) and PAGE not in json.dumps(turns)
    assert users == 2
    assert wc.last_reply(row) == ""


# ---- through the doors, on a database -------------------------------------------------------


@pytest.fixture
def fake_web(monkeypatch):
    monkeypatch.setattr(
        web_search,
        "search",
        lambda query, **_: web_search.WebAnswer(
            query=query,
            answer=PAGE,
            sources=[{"title": "Example Motors", "url": "https://example.com/pump"}],
        ),
    )


def _mint(client) -> str:
    response = _start(client, utc_offset_minutes=0)
    assert response.status_code == 200, response.data
    return str(response.data["conversation_id"])


def _body(cid) -> str:
    return gzip.decompress(bytes(DeviceLog.objects.get(batch_id=cid).body_gz)).decode()


@pytest.mark.django_db
@pytest.mark.usefixtures("google")
def test_the_tool_door_stores_no_web_text_and_the_browsers_answer_after_it_neither(
    household_user, fake_web
):
    web = browser(household_user)
    cid = _mint(web)
    answer = web.post(
        "/api/assistant/tool",
        {"name": "web_search", "args": {"query": "pump"}, "conversation_id": cid, "turn": 0},
        format="json",
    )
    assert answer.status_code == 200 and answer.data["is_error"] is False, answer.data
    now_ms = int(datetime.now(UTC).timestamp() * 1000) + 5000
    events = [
        {"kind": "assistant", "at_ms": now_ms, "text": f"It says: {PAGE}"},
        {"kind": "turn_complete", "at_ms": now_ms + 100},
        {"kind": "user", "at_ms": now_ms + 2000, "text": "Thanks", "typed": True},
        {"kind": "assistant", "at_ms": now_ms + 2500, "text": "The pump fails at 60,000 miles."},
    ]
    stored = web.post(
        "/api/assistant/conversation",
        {"conversation_id": cid, "events": json.dumps(events)},
        format="multipart",
    )
    assert stored.status_code == 200, stored.data
    body = _body(cid)
    assert "60,000" not in body
    assert body.count(f"assistant text={MARK}") == 2
    assert 'user final=true typed=true text="Thanks"' in body
    # A retried upload stores nothing new.
    again = web.post(
        "/api/assistant/conversation",
        {"conversation_id": cid, "events": json.dumps(events)},
        format="multipart",
    )
    assert again.data["stored_lines"] == 0


@pytest.mark.django_db
@pytest.mark.usefixtures("google")
def test_a_request_that_read_outside_text_redacts_any_tools_reply(household_user):
    """`run_procedure` is not in the list, but `morning_briefing` returns `brief_latest`'s text
    under its name: the request's own read-through flag decides."""
    cid = _mint(browser(household_user))
    request = SimpleNamespace(user=household_user, legion_read_third_party=True)
    wc.record_tool(
        request,
        cid,
        call_id="p1",
        name="run_procedure",
        args={"name": "morning_briefing"},
        started=_at(0),
        ended=_at(1),
        success=True,
        outcome="ok",
        text=PAGE,
    )
    body = _body(cid)
    assert PAGE not in body and "name=run_procedure success=true" in body


@pytest.mark.django_db
@pytest.mark.usefixtures("google")
def test_count_command_counts_old_records_and_prints_no_words(household_user):
    cid = _mint(browser(household_user))
    row = DeviceLog.objects.get(batch_id=cid)
    old = _texts([_user(0), *_tool(1, "web_search"), _said(3, PAGE)])
    row.body_gz = gzip.compress(("\n".join(old) + "\n").encode())  # written before the fix
    row.save(update_fields=["body_gz"])
    _mint(browser(household_user))  # a clean one
    out = StringIO()
    call_command("count_read_through_web", stdout=out)
    printed = out.getvalue()
    assert "2 scanned, 1 hold third-party text in clear, 2 lines" in printed
    assert "pump" not in printed.lower()
    assert (
        gzip.decompress(bytes(DeviceLog.objects.get(batch_id=cid).body_gz)).decode().count(PAGE)
        == 2
    )  # nothing was rewritten
