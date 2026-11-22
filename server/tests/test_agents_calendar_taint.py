"""Calendar entries other people wrote count as outside text on the engine brain (relay report
12.4 / 13.4, owed before LEGION_AGENTS_TURN goes on): an IMPORTED event (any source but the
household's own entries) whose organizer is someone outside the household, or (the strict default,
`calendar_taint.UNKNOWN_ORGANIZER_IS_OUTSIDE`) not known, marks the turn as having read outside
text when a tool returns it. Events he made himself never do. The organizer is a new nullable,
write-only column on `events`. Every name, address and event is invented."""

from __future__ import annotations

import datetime as dt
import uuid
from datetime import date

import pytest

from agents.evals import fixture
from agents.evals.runner import member_request
from agents.legion import calendar_taint
from agents.legion.store import TurnStore
from agents.legion.turn import open_conversation, run_turn
from agents.models import AgentTurn
from agents.runtime import model as m
from legacy.models.dates import Event

pytestmark = pytest.mark.django_db

# 21:00 in Chicago on Tuesday 2026-03-10 is 03:00 UTC on Wednesday the 11th.
EVENING_UTC = dt.datetime(2026, 3, 11, 3, 0, tzinfo=dt.UTC)
DAY = "2026-03-10"
STRANGER = "planner@example.org"


@pytest.fixture
def seeded(household_user, household_a, monkeypatch):
    monkeypatch.setattr("engine_mcp.tools._now", lambda: EVENING_UTC)
    fixture.seed(household_a, household_user, date(2026, 3, 10))
    return household_user


def imported(household, *, organizer=None, source="google", title="Budget review invite"):
    now = dt.datetime.now(dt.UTC)
    return Event.objects.create(
        id=uuid.uuid4(),
        household=household,
        title=title,
        kind="event",
        starts_at=dt.datetime(2026, 3, 10, 22, 0, tzinfo=dt.UTC),
        ends_at=dt.datetime(2026, 3, 10, 23, 0, tzinfo=dt.UTC),
        all_day=False,
        source=source,
        organizer=organizer,
        done=False,
        exact=False,
        exact_downgraded=False,
        provenance="USER",
        created_at=now,
        updated_at=now,
    )


def turn(user, text, rounds, conversation=None):
    """One agent turn; returns (events, observed calls, the request's conversation)."""
    seen = []
    request = member_request(user)
    conversation = conversation or open_conversation(request, None, "test")
    model = m.FakeModel(rounds)
    events = list(
        run_turn(
            request, text, model, conversation=conversation, now=EVENING_UTC, observe=seen.append
        )
    )
    return events, seen, conversation


def done_of(events) -> dict:
    return next(e.data for e in events if e.type == "done")


def read_then_write(tool="read_calendar"):
    return [
        m.call(tool, {"from": DAY, "to": DAY}),
        m.call("add_checklist_item", {"checklist": "Todo", "text": "buy stamps"}),
        m.say("Done."),
    ]


def write_guard(seen) -> str | None:
    return next(c["guard"] for c in seen if c["name"] == "add_checklist_item")


# ---- who wrote it decides ------------------------------------------------------------------------


@pytest.mark.parametrize("tool", ["read_calendar", "list_events"])
def test_an_imported_event_with_an_outside_organizer_taints_and_refuses_the_write(
    seeded, household_a, tool
):
    imported(household_a, organizer=STRANGER)
    events, seen, _c = turn(
        seeded, "what's on today, and add stamps to my list", read_then_write(tool)
    )
    assert done_of(events)["tainted"] is True
    assert write_guard(seen) == "tainted"


def test_an_imported_event_with_no_organizer_taints_under_the_strict_default(seeded, household_a):
    assert calendar_taint.UNKNOWN_ORGANIZER_IS_OUTSIDE is True
    imported(household_a, organizer=None)
    events, seen, _c = turn(seeded, "what's on today", read_then_write())
    assert done_of(events)["tainted"] is True
    assert write_guard(seen) == "tainted"


def test_the_one_line_flip_lets_an_unknown_organizer_through_but_never_a_stranger(
    seeded, household_a, monkeypatch
):
    monkeypatch.setattr(calendar_taint, "UNKNOWN_ORGANIZER_IS_OUTSIDE", False)
    imported(household_a, organizer=None)
    events, seen, _c = turn(seeded, "what's on today", read_then_write())
    assert done_of(events)["tainted"] is False
    assert write_guard(seen) is None
    imported(household_a, organizer=STRANGER, title="Vendor call")
    events, seen, _c = turn(seeded, "what's on today", read_then_write())
    assert done_of(events)["tainted"] is True


@pytest.mark.parametrize("organizer", ["self", "MEMBER"])
def test_an_imported_event_he_or_a_member_organized_does_not_taint(seeded, household_a, organizer):
    who = seeded.email.upper() if organizer == "MEMBER" else calendar_taint.ORGANIZER_SELF
    imported(household_a, organizer=who)
    events, seen, _c = turn(seeded, "what's on today", read_then_write())
    assert done_of(events)["tainted"] is False
    assert write_guard(seen) is None


def test_his_own_entries_never_taint_whatever_the_organizer_field_says(seeded, household_a):
    imported(household_a, organizer=STRANGER, source="legion", title="Dentist")
    events, seen, _c = turn(seeded, "what's on today", read_then_write())
    assert done_of(events)["tainted"] is False
    assert write_guard(seen) is None


def test_a_read_that_does_not_return_the_imported_event_does_not_taint(seeded, household_a):
    imported(household_a, organizer=STRANGER)
    rounds = [m.call("read_calendar", {"from": "2026-03-13", "to": "2026-03-13"}), m.say("Clear.")]
    events, _seen, _c = turn(seeded, "what's on friday", rounds)
    assert done_of(events)["tainted"] is False


# ---- what follows the mark -----------------------------------------------------------------------


def test_the_reply_after_an_imported_read_is_not_kept_and_the_next_turn_is_carried(
    seeded, household_a
):
    imported(household_a, organizer=STRANGER)
    rounds = [m.call("read_calendar", {"from": DAY, "to": DAY}), m.say("A budget review at four.")]
    events, _seen, conversation = turn(seeded, "what's on today", rounds)
    row = AgentTurn.objects.filter(conversation=conversation).order_by("-seq").first()
    assert "calendar" in row.taint_sources
    assert "Budget review invite" not in str(row.history)
    assert "Budget review invite" not in (row.final_text or "")
    # ADR 0090 amended: the carry refuses a write in his next turn too.
    rounds = [m.call("add_checklist_item", {"checklist": "Todo", "text": "milk"}), m.say("Ok.")]
    _events, seen, _c = turn(seeded, "add milk", rounds, conversation=conversation)
    assert write_guard(seen) == "tainted"


def test_when_the_mark_cannot_be_saved_the_rows_never_reach_the_model(
    seeded, household_a, monkeypatch
):
    imported(household_a, organizer=STRANGER)
    monkeypatch.setattr(TurnStore, "mark_taint", lambda self, state: False)
    model = m.FakeModel([m.call("read_calendar", {"from": DAY, "to": DAY}), m.say("Sorry.")])
    request = member_request(seeded)
    conversation = open_conversation(request, None, "test")
    seen = []
    list(
        run_turn(
            request,
            "what's on today",
            model,
            conversation=conversation,
            now=EVENING_UTC,
            observe=seen.append,
        )
    )
    response = model.requests[1].contents[-1]["parts"][0]["functionResponse"]["response"]
    assert response["success"] is False
    assert response["message"] == calendar_taint.MARK_FAILED
    assert "Budget review invite" not in str(model.requests[1].contents)
    assert seen[0]["guard"] == "tainted"


# ---- the column and the API ----------------------------------------------------------------------


def test_the_api_takes_an_organizer_and_never_shows_it(auth_client):
    body = {
        "title": "Quarterly planning",
        "starts_at": "2026-03-12T15:00:00Z",
        "source": "google",
        "google_event_id": "evt-test-1",
        "organizer": STRANGER,
    }
    created = auth_client.post("/api/events", body, format="json")
    assert created.status_code == 201, created.data
    assert "organizer" not in created.data
    row = Event.objects.get(pk=created.data["id"])
    assert row.organizer == STRANGER
    listed = auth_client.get("/api/events")
    assert all("organizer" not in r for r in listed.data["results"])
    # A client that does not know the field leaves it empty.
    plain = auth_client.post(
        "/api/events", {"title": "Gym", "starts_at": "2026-03-12T18:00:00Z"}, format="json"
    )
    assert plain.status_code == 201 and Event.objects.get(pk=plain.data["id"]).organizer is None


def test_an_organizer_past_the_cap_is_refused(auth_client):
    body = {"title": "x", "starts_at": "2026-03-12T15:00:00Z", "organizer": "a" * 321}
    response = auth_client.post("/api/events", body, format="json")
    assert response.status_code == 400 and "organizer" in response.data


# ---- review 1121 C1: who wrote it cannot be rewritten ----


def test_source_and_organizer_cannot_change_after_create(auth_client, household_a):
    invite = imported(household_a, organizer=STRANGER)
    body = {"source": "legion", "organizer": calendar_taint.ORGANIZER_SELF, "title": "Renamed"}
    response = auth_client.patch(f"/api/events/{invite.pk}", body, format="json")
    assert response.status_code == 200, response.data
    invite.refresh_from_db()
    assert (invite.source, invite.organizer, invite.title) == ("google", STRANGER, "Renamed")


def test_a_client_sending_the_stored_source_on_update_is_unaffected(auth_client, household_a):
    # The phone's PATCH sends every field, `source` included, as it holds it.
    invite = imported(household_a, organizer=STRANGER)
    response = auth_client.patch(
        f"/api/events/{invite.pk}", {"source": "google", "done": True}, format="json"
    )
    assert response.status_code == 200 and response.data["source"] == "google"


def test_the_engine_tools_never_set_who_wrote_an_event(seeded, household_a):
    from engine_mcp.tools import TOOLS_BY_NAME, run_engine_tool

    request = member_request(seeded)
    fields = {
        "title": "Lunch",
        "starts_at": "2026-03-12T12:00:00",
        "source": "google",
        "organizer": STRANGER,
    }
    made = run_engine_tool(request, TOOLS_BY_NAME["add_event"], {"fields": fields})
    assert not made.is_error, made.text
    row = Event.objects.get(title="Lunch")
    assert (row.source, row.organizer) == ("legion", None)
    invite = imported(household_a, organizer=STRANGER)
    changed = run_engine_tool(
        request,
        TOOLS_BY_NAME["update_event"],
        {"id": str(invite.pk), "fields": {"source": "legion", "organizer": "self"}},
    )
    assert not changed.is_error, changed.text
    invite.refresh_from_db()
    assert (invite.source, invite.organizer) == ("google", STRANGER)


# ---- review 1121 C2: a read inside another tool (a procedure's step) counts ----------------------


def test_an_event_read_inside_another_tool_marks_it_and_stops_its_later_writes(seeded, household_a):
    from engine_mcp.tools import TOOLS_BY_NAME, run_engine_tool

    imported(household_a, organizer=STRANGER)
    request = member_request(seeded)
    with calendar_taint.watching(request) as watch:
        read = run_engine_tool(request, TOOLS_BY_NAME["list_events"], {"from": DAY, "to": DAY})
        assert not read.is_error
        write = run_engine_tool(
            request,
            TOOLS_BY_NAME["add_event"],
            {"fields": {"title": "Planted", "starts_at": "2026-03-12T09:00:00"}},
        )
    assert watch.outside is True
    assert write.is_error and "read outside text" in write.text
    assert not Event.objects.filter(title="Planted").exists()


def test_watching_is_inert_for_a_request_no_one_watches(seeded, household_a):
    from engine_mcp.tools import TOOLS_BY_NAME, run_engine_tool

    imported(household_a, organizer=STRANGER)
    request = member_request(seeded)
    run_engine_tool(request, TOOLS_BY_NAME["list_events"], {"from": DAY, "to": DAY})
    write = run_engine_tool(
        request,
        TOOLS_BY_NAME["add_event"],
        {"fields": {"title": "Fine", "starts_at": "2026-03-12T09:00:00"}},
    )
    assert not write.is_error, write.text


# ---- review 1121 C3: a write that ran stays a write that ran ----


def test_a_failed_mark_after_an_event_write_keeps_the_write_and_withholds_the_row(
    seeded, household_a, monkeypatch
):
    invite = imported(household_a, organizer=STRANGER)
    monkeypatch.setattr(TurnStore, "mark_taint", lambda self, state: False)
    rounds = [
        m.call("update_event", {"id": str(invite.pk), "fields": {"done": True}}),
        m.say("Marked."),
    ]
    model = m.FakeModel(rounds)
    request = member_request(seeded)
    conversation = open_conversation(request, None, "test")
    seen = []
    events = list(
        run_turn(
            request,
            "mark the budget review done",
            model,
            conversation=conversation,
            now=EVENING_UTC,
            observe=seen.append,
        )
    )
    assert seen[0]["ok"] is True and seen[0]["outcome"] == "ok"
    assert done_of(events)["writes_ran"] is True
    invite.refresh_from_db()
    assert invite.done is True
    response = model.requests[1].contents[-1]["parts"][0]["functionResponse"]["response"]
    assert response["success"] is True
    assert response["message"] == calendar_taint.WRITE_KEPT_ROW_WITHHELD
    assert "Budget review invite" not in str(model.requests[1].contents)


# ---- review 1121 C4: the words say which outside text it was ----


def said(events) -> list[str]:
    return [e.data["text"] for e in events if e.type == "sentence"]


def test_a_calendar_taint_refuses_and_closes_in_calendar_words(seeded, household_a):
    imported(household_a, organizer=STRANGER)
    events, seen, conversation = turn(
        seeded, "what's on today, and add stamps to my list", read_then_write()
    )
    refusal = next(c["text"] for c in seen if c["name"] == "add_checklist_item")
    assert "calendar entry someone else wrote" in refusal
    assert "web search" not in refusal
    words = " ".join(said(events))
    assert "calendar entry someone else wrote" in words
    assert "from the web" not in words
    # The next turn is carried, in calendar words too.
    rounds = [m.call("add_checklist_item", {"checklist": "Todo", "text": "milk"}), m.say("Ok.")]
    events, seen, _c = turn(seeded, "add milk", rounds, conversation=conversation)
    refusal = next(c["text"] for c in seen if c["name"] == "add_checklist_item")
    assert "The turn before this one read outside text" in refusal
    assert "calendar entry someone else wrote" in refusal
    words = " ".join(said(events))
    assert "calendar entry someone else wrote" in words and "from the web" not in words


def test_only_a_calendar_only_taint_gets_the_calendar_words():
    name = "add_checklist_item"
    assert calendar_taint.refusal(["web_search"], name) is None
    assert calendar_taint.refusal(["calendar", "web_search"], name) is None
    assert calendar_taint.refusal(["previous_turn"], name, carried=True) is None
    assert "calendar entry" in calendar_taint.refusal(["calendar", "read_calendar"], name)
    carried = calendar_taint.refusal(["previous_turn", calendar_taint.CARRIED], name, carried=True)
    assert "The turn before this one read outside text" in carried
