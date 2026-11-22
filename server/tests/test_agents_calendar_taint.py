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
