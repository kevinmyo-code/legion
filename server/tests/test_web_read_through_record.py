"""Third-party text never reaches a stored web conversation record (CLAUDE.md section 7, #475).

The web record is written by the engine (`devicelogs.web_conversation`), so it redacts at write
time the way the phone does on the device: a read-through tool's reply, and the assistant's lines
in that turn and the next (the carry), become `[redacted: third-party content]`. His own words
stay. The cascade door's cases are in `tests/test_web_read_through_cascade.py`.
"""

from __future__ import annotations

import gzip
import json
from datetime import UTC, datetime, timedelta
from io import StringIO
from types import SimpleNamespace

import pytest
from django.core.management import call_command

from agents.legion import redact as trace
from agents.legion.redact import OUTSIDE_ANSWER_NOTE
from assistant import echo
from devicelogs import web_conversation as wc
from devicelogs.models import DeviceLog
from devicelogs.read_through_lines import ECHO_EVENT, REDACTED, echo_print, redact
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
    from procedures import definition

    assert consolidate.READ_THROUGH_TOOLS is trace.READ_THROUGH_TOOLS
    assert definition.READ_THROUGH_TOOLS is trace.PHONE_READ_THROUGH_TOOLS
    assert THIRD_PARTY_TOOLS <= trace.READ_THROUGH_TOOLS


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


def test_the_next_turn_is_redacted_and_the_one_after_is_stored_as_said():
    """The read turn and the carried turn (its answer may still repeat the page), then it resets,
    as the phone's `resetTurn` does. His lines stay his throughout."""
    stored = _texts([_user(0), *_tool(1, "web_search"), _said(3, "It says something.")])
    later = [_user(10, "And the price?"), _said(12, "About forty dollars, the page said.")]
    kept = _texts(redact(stored, later))
    assert kept[0] == later[0][1]
    assert kept[-1].endswith(f"assistant text={MARK}")
    after = [_user(20, "Thanks"), _said(21, "You're welcome.")]
    assert redact(stored + kept, after) == after


def test_words_before_the_read_turn_stay_and_the_read_turns_own_opening_does_not():
    earlier = [_user(0, "Hi"), _said(1, "Morning.")]
    turn = [_user(5), _said(6, "Let me look."), *_tool(7, "web_search"), _said(9, PAGE)]
    kept = _texts(redact([], earlier + turn))
    assert 'assistant text="Morning."' in kept[1]
    said = [text for text in kept[2:] if " assistant text=" in text]
    assert len(said) == 2 and all(text.endswith(f"assistant text={MARK}") for text in said)


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
        assert kept[-1].endswith(f"assistant text={MARK}")


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


def test_history_replays_the_note_only_for_the_withheld_turns():
    """A withheld reply is replayed as the agent trace's note (answered, not kept, done), never
    the marker and never dropped, so his turns stay separate; a later household answer is
    replayed as it was said."""
    lines = redact(
        [],
        [
            _user(0, "Hi"),
            _said(1, "Morning."),
            _user(5),
            *_tool(6, "web_search"),
            _said(8, PAGE),
            _user(10, "And?"),
            _said(11, "That was all it said."),
            _user(15, "What is on my calendar?"),
            _said(16, "Nothing today."),
        ],
    )
    row = _row(lines)
    turns, users = wc.history(row, max_turns=10)
    assert [t["role"] for t in turns] == ["user", "model"] * 4
    replies = [t["parts"][0]["text"] for t in turns][1::2]
    assert replies == ["Morning.", OUTSIDE_ANSWER_NOTE, OUTSIDE_ANSWER_NOTE, "Nothing today."]
    assert REDACTED not in json.dumps(turns) and PAGE not in json.dumps(turns)
    assert users == 4
    assert wc.last_reply(row) == ("Nothing today.", ())


def test_a_withheld_reply_keeps_only_a_keyed_fingerprint_the_echo_check_reads():
    """Review F2: the reply's words are not stored, its HMAC 3-gram print is; the answer heard
    back matches it, his own different words do not."""
    answer = f"It says: {PAGE} Worth checking before a long drive."
    lines = redact([], [_user(0), *_tool(1, "web_search"), _said(3, answer)])
    body = "\n".join(_texts(lines))
    assert PAGE not in body and "Worth checking" not in body
    reply, print_ = wc.last_reply(_row(lines))
    assert reply == "" and len(print_) == len(echo.words(answer)) - 2
    assert all(len(h) == echo.PRINT_HEX and h not in answer for h in print_)
    assert echo.is_echo_of_print("says the pump fails at 60,000 miles worth checking", print_)
    assert not echo.is_echo_of_print("can you add a reminder for the garage friday", print_)
    printed = next(text for text in _texts(lines) if f" {ECHO_EVENT} " in text)
    assert echo_print(printed) == print_


def test_a_withheld_user_line_counts_as_his_turn_and_holds_no_words():
    """The race (a barge-in before the read turn's reply reached the record) keeps no words."""
    row = _row([_user(0, "Hi"), _said(1, "Morning."), wc.engine_withheld_user_line(_at(5))])
    turns, users = wc.history(row, max_turns=10)
    assert users == 2
    assert "withheld" not in json.dumps(turns)


def _call(s, name, args, text, ok=True):
    return wc.tool_lines(
        call_id=f"x{s}",
        name=name,
        args=args,
        started=_at(s),
        ended=_at(s + 0.1),
        success=ok,
        outcome="ok" if ok else "refused",
        text=text,
    )


def test_tool_arguments_after_a_read_are_blanked_by_the_trace_rule():
    """Review F3: the search's own query and a later call's arguments, as `agents.legion.redact`
    keeps them; a call before the read keeps its arguments."""
    before = _call(0.5, "list_events", {"day": "today"}, "Nothing.")
    later = _call(4, "add_checklist_item", {"item": "pump recall 24V-123"}, "Not added.", ok=False)
    kept = "\n".join(
        _texts(redact([], [*before, _user(1), *_tool(2, "web_search"), _said(3, PAGE), *later]))
    )
    blank = json.dumps(trace.OUTSIDE_ARGS, separators=(",", ":"))
    assert 'args={"day":"today"}' in kept
    assert "24V-123" not in kept and '"query":"pump"' not in kept
    assert kept.count(f"args={blank}") == 2


def test_a_stage_four_mail_name_reads_outside_text():
    kept = "\n".join(_texts(redact([], [_user(0), *_tool(1, "gmail_search"), _said(3, PAGE)])))
    assert PAGE not in kept


def _old_briefing():
    old = [_user(0), *_tool(1, "run_procedure"), _said(3, PAGE)]
    old[1] = (old[1][0], old[1][1].replace('{"query":"pump"}', '{"name":"morning_briefing"}'))
    return old


def test_an_old_procedure_reply_is_found_by_the_procedure_it_ran():
    """Review F4: an old record's `run_procedure` reply carries no listed name; the procedure's
    own name, read from its arguments, decides."""
    old = _old_briefing()
    assert redact([], old) == old  # an unknown procedure: left alone
    kept = "\n".join(_texts(redact([], old, procedures=frozenset({"morning_briefing"}))))
    assert PAGE not in kept and "morning_briefing" not in kept


def test_a_read_just_before_midnight_on_new_years_eve_still_taints_the_answer():
    """Review F5: the stamp has no year; a record opened on 12-31 sorts 01-01 after it."""
    eve = datetime(2026, 12, 31, 23, 59, 50, tzinfo=UTC)
    opened = wc.line(eve, 2, wc.AUDIT, "session_start model=x async_set=none")
    read = wc.tool_lines(
        call_id="c1",
        name="web_search",
        args={"query": "pump"},
        started=eve + timedelta(seconds=2),
        ended=eve + timedelta(seconds=3),
        success=True,
        outcome="ok",
        text=PAGE,
    )
    answer = wc.engine_assistant_line(eve + timedelta(seconds=15), PAGE)
    kept = _texts(redact([opened], [wc.engine_user_line(eve, "pump?", typed=True), *read, answer]))
    assert kept[-1].endswith(f"assistant text={MARK}")
    assert PAGE not in "\n".join(kept)


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
    assert "2 scanned, 1 the current rule would redact, 3 lines" in printed
    assert "pump" not in printed.lower()
    assert (
        gzip.decompress(bytes(DeviceLog.objects.get(batch_id=cid).body_gz)).decode().count(PAGE)
        == 2
    )  # nothing was rewritten


@pytest.mark.django_db
@pytest.mark.usefixtures("google")
def test_count_command_finds_an_old_morning_briefing_reply(household_user):
    cid = _mint(browser(household_user))
    row = DeviceLog.objects.get(batch_id=cid)
    old = _texts(_old_briefing())
    row.body_gz = gzip.compress(("\n".join(old) + "\n").encode())
    row.save(update_fields=["body_gz"])
    out = StringIO()
    call_command("count_read_through_web", stdout=out)
    # The call's arguments, its reply and the answer after it; his line is never counted.
    assert "1 scanned, 1 the current rule would redact, 3 lines" in out.getvalue()


# ---- the echo print is transient ---------------------------------------------------------------


def _printed_turn(s):
    """A read turn as the cascade stores it: his words, the search, the withheld reply with its
    print, and the turn's timing line."""
    timing = (_at(s + 4), wc.line(_at(s + 4), 2, "LIVE_LATENCY", "cascade_turn status=done"))
    return _texts(redact([], [_user(s), *_tool(s + 1, "web_search"), _said(s + 3, PAGE), timing]))


def _prints(lines):
    return [text for text in lines if f" {ECHO_EVENT} " in text]


def test_a_print_lives_until_the_next_turn_completes():
    from devicelogs.read_through_lines import drop_spent_prints

    lines = _printed_turn(0)
    assert len(_prints(lines)) == 1
    now = _at(30)
    assert drop_spent_prints(lines, now) == lines  # the read turn's own timing keeps it
    heard = lines + [_user(10, "And?")[1]]
    assert len(_prints(drop_spent_prints(heard, now))) == 1  # the next turn is still running
    done = heard + [wc.line(_at(12), 2, "LIVE_LATENCY", "cascade_turn status=done")]
    kept = drop_spent_prints(done, now)
    assert _prints(kept) == [] and len(kept) == len(done) - 1


def test_a_print_lives_at_most_five_minutes():
    from devicelogs.read_through_lines import ECHO_TTL, drop_spent_prints

    lines = _printed_turn(0)
    assert len(_prints(drop_spent_prints(lines, _at(3) + ECHO_TTL - timedelta(seconds=1)))) == 1
    assert _prints(drop_spent_prints(lines, _at(3) + ECHO_TTL)) == []


@pytest.mark.django_db
@pytest.mark.usefixtures("google")
def test_the_hourly_job_sweeps_a_print_left_behind(household_user):
    """A conversation that simply stopped: no next turn ever closed the window."""
    cid = _mint(browser(household_user))
    row = DeviceLog.objects.get(batch_id=cid)
    stamped = datetime.now(UTC) - timedelta(minutes=10)
    lines = [
        text
        for _, text in redact(
            [],
            [
                wc.engine_user_line(stamped, "pump?", typed=True),
                *wc.tool_lines(
                    call_id="c1",
                    name="web_search",
                    args={"query": "pump"},
                    started=stamped,
                    ended=stamped,
                    success=True,
                    outcome="ok",
                    text=PAGE,
                ),
                wc.engine_assistant_line(stamped, PAGE),
            ],
        )
    ]
    assert len(_prints(lines)) == 1
    row.body_gz = gzip.compress(("\n".join(lines) + "\n").encode())
    row.save(update_fields=["body_gz"])
    out = StringIO()
    call_command("prune_demo_visitors", stdout=out)
    assert "1 echo fingerprint(s) past their window" in out.getvalue()
    body = _body(cid)
    assert _prints(body.splitlines()) == []
    assert f"assistant text={MARK}" in body  # the rest of the record is untouched


def test_a_retried_upload_is_redacted_the_same_way():
    """A repeated `user` line from a retry must not start a turn of its own and move the next
    answer out of the carried turn (CI, round 3)."""
    fresh = [
        _said(3, PAGE),
        (_at(4), wc.line(_at(4), 1, wc.AUDIT, "turn_complete")),
        _user(6, "Thanks"),
        _said(7, PAGE),
    ]
    stored = _texts([_user(0), *_tool(1, "web_search")])
    first = _texts(redact(stored, fresh))
    again = _texts(redact(stored + first, fresh))
    assert PAGE not in "\n".join(first + again)
