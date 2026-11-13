"""The facts pack (alfred-greets ticket 01, ADR 0091): `GET /api/greeting/facts`,
`POST /api/greeting/topics` and the engine tool `greeting_facts`.

- Each of the five sources in all three states: facts, empty, and unreadable (its reader forced
  to raise), and an unreadable source is never rendered as empty.
- Privacy: another member's private Canvas row never appears (ADR 0052); another household sees
  nothing (ADR 0045); a recruiter's visit is refused (ADR 0068); a Claude worker may read but not
  record.
- Never the same question twice: answered and silenced topics are left out, raised ones listed in
  `said`, and a closed topic is never reopened by a later raise.
- No IANA zone id anywhere in the payload (CLAUDE.md section 1); money in integer cents with the
  projection marked an estimate (section 4).

The clock is pinned: Friday 2026-11-13 18:00 UTC, which is noon in the household's zone (UTC-6 in
November). Every title, place, course and amount here is made up.
"""

from __future__ import annotations

import datetime as dt
import json
import re
import uuid
from types import SimpleNamespace

import pytest
from django.db import IntegrityError, transaction
from django.db.models.functions import Now
from rest_framework.test import APIClient

from assistant.surface import RECRUITER_EXCLUDED, RECRUITER_TOOLS
from body_coach.models import TrainingProgramme
from engine_mcp.tools import TOOLS_BY_NAME
from greeting import facts
from greeting.models import GreetingTopic
from household.models import DeviceToken, Household, HouseholdMember, User
from household.recruiter_routes import RECRUITER_DENIED
from legacy.enums import Provenance
from legacy.models.body import BodyweightLog
from legacy.models.dates import Event
from tests.conftest import _client_for, _member
from tests.finance_synthetic import Caller
from tests.test_engine_mcp import call, client_with
from tests.test_finance_budgets import world  # noqa: F401 - the shared synthetic household

pytestmark = pytest.mark.django_db

FACTS = "/api/greeting/facts"
TOPICS = "/api/greeting/topics"
NOW = dt.datetime(2026, 11, 13, 18, 0, tzinfo=dt.UTC)
ZONE_LIKE = re.compile(r"\b(?:Africa|America|Antarctica|Asia|Atlantic|Australia|Europe|Indian|"
                       r"Pacific|Etc)/[A-Za-z_]+")


@pytest.fixture
def kevin(household_user, household_a):
    household_a.timezone = "America/Chicago"
    household_a.save(update_fields=["timezone"])
    household_user.refresh_from_db()
    return household_user


@pytest.fixture
def mia(household_a):
    return _member(household_a, "mia@example.com")


@pytest.fixture
def mia_client(mia):
    return _client_for(mia)


@pytest.fixture
def pinned(monkeypatch):
    monkeypatch.setattr(facts, "_now", lambda: NOW)


def _at(hours: float) -> str:
    return (NOW + dt.timedelta(hours=hours)).isoformat()


def _event(client, title, *, kind="event", start, end=None, private=False, meta=None, **extra):
    body = {"title": title, "kind": kind, "starts_at": start, **extra}
    if end is not None:
        body["ends_at"] = end
    if private:
        body["visibility"] = "private"
    made = client.post("/api/events", body, format="json")
    assert made.status_code == 201, made.data
    if meta is not None:
        Event.objects.filter(pk=made.data["id"]).update(structured_meta=meta)
    return made.data["id"]


def _day(client):
    """One of each: an event that ended this morning, one two days ago, one this afternoon, one
    tomorrow, a to-do due tonight, a Canvas task due in five hours (private), and one Canvas says
    is submitted."""
    return {
        "ended": _event(client, "Dentist checkup", start=_at(-3), end=_at(-2),
                        location="Example Clinic"),
        "old": _event(client, "Old lunch", start=_at(-50), end=_at(-49)),
        "later": _event(client, "Team sync", start=_at(3), end=_at(4)),
        "tomorrow": _event(client, "Tomorrow thing", start=_at(26), end=_at(27)),
        "todo": _event(client, "Return library book", kind="task", start=_at(9)),
        "canvas": _event(client, "EXAMPLE 101: Essay", kind="task", start=_at(5), private=True,
                         meta={"canvas_assignment_id": 11, "canvas_submitted": False}),
        "submitted": _event(client, "EXAMPLE 101: Quiz", kind="task", start=_at(6), private=True,
                            meta={"canvas_assignment_id": 12, "canvas_submitted": True}),
    }


def _titles(block) -> list[str]:
    return [item["title"] for item in block["items"]]


def _weigh(household, at):
    return BodyweightLog.objects.create(
        id=uuid.uuid4(),
        household=household,
        weight_value=180.0,
        weight_unit="lbs",
        logged_at=at,
        trust_tier="REPORTED",
        provenance=Provenance.USER,
        created_at=Now(),
        updated_at=Now(),
        origin_guid=str(uuid.uuid4()),
    )


# =============================================================================
# Facts
# =============================================================================


def test_the_event_sources_say_what_is_on_file(kevin, auth_client, pinned):
    ids = _day(auth_client)
    body = auth_client.get(FACTS).data
    sources = body["sources"]

    ended = sources["ended_events"]
    assert ended["state"] == "facts" and _titles(ended) == ["Dentist checkup"]
    (item,) = ended["items"]
    assert item["topic"] == f"event:{ids['ended']}"
    assert item["where"] == "Example Clinic"
    assert item["ended_at"] == "2026-11-13T10:00:00-06:00"
    assert item["says"] == "Dentist checkup ended Fri Nov 13, 10:00 AM at Example Clinic."

    today = sources["today_events"]
    assert today["state"] == "facts" and _titles(today) == ["Team sync"]
    assert today["items"][0]["starts_at"] == "2026-11-13T15:00:00-06:00"
    assert today["items"][0]["says"] == "Team sync at 3:00 PM until 4:00 PM."

    due = sources["due_soon"]
    assert due["state"] == "facts"
    assert _titles(due) == ["EXAMPLE 101: Essay", "Return library book"]
    essay, book = due["items"]
    assert essay["canvas"] is True and essay["canvas_submitted"] is False
    assert essay["topic"] == f"due:{ids['canvas']}"
    assert "Canvas says not submitted." in essay["says"]
    assert book["canvas"] is False and book["due_at"] == "2026-11-13T21:00:00-06:00"

    assert body["today"] == "2026-11-13"
    assert body["utc_offset"] == "-06:00"
    assert body["zone_note"] is None


def test_a_repeating_event_is_keyed_by_its_occurrence(kevin, auth_client, pinned):
    eid = _event(
        auth_client, "Standup", start=(NOW - dt.timedelta(days=7, hours=2)).isoformat(),
        end=(NOW - dt.timedelta(days=7, hours=1)).isoformat(),
        repeat_kind="DAILY", repeat_every=1, repeat_end_kind="NEVER",
    )
    ended = facts.pack(Caller(kevin), NOW)["sources"]["ended_events"]
    # Today's 10:00 to 11:00 local ended in the last 24 hours; yesterday's ended at 11:00 local
    # yesterday, just over 24 hours ago. Keyed by its date, so answering it closes only today's.
    assert [i["topic"] for i in ended["items"]] == [f"event:{eid}:2026-11-13"]


def test_body_says_the_workout_and_the_last_weigh_in(kevin, household_a, pinned):
    TrainingProgramme.objects.create(household=household_a, started_on="2026-11-01")
    _weigh(household_a, NOW - dt.timedelta(days=1))
    block = facts.pack(Caller(kevin), NOW)["sources"]["body"]
    assert block["state"] == "facts"
    workout, weigh_in = block["items"]
    assert workout["kind"] == "workout" and workout["status"] == "not_started"
    assert workout["topic"] == "body:2026-11-13"
    assert workout["week_done"] == 0 and workout["week_planned"] >= 1
    assert "the next one is" in workout["says"]
    assert weigh_in == {
        "kind": "weigh_in",
        "topic": "body:2026-11-13",
        "last_on": "2026-11-12",
        "last_at": "2026-11-12T12:00:00-06:00",
        "today": False,
        "says": "Last weigh-in on file: Thu Nov 12. None yet today.",
        "raised": False,
    }


def test_money_is_integer_cents_and_the_projection_is_an_estimate(world):  # noqa: F811
    household, caller = world
    body = facts.pack(caller, dt.datetime(2026, 11, 15, 18, 0, tzinfo=dt.UTC))
    block = body["sources"]["money"]
    assert block["state"] == "facts"
    lines = {i["category"]: i for i in block["items"]}
    # Housing is a fixed category: checked for being paid, never paced.
    assert set(lines) == {"Groceries", "Dining", "Subscriptions"}
    groceries = lines["Groceries"]
    assert groceries["topic"] == "money:2026-11:Groceries"
    assert (groceries["spent_cents"], groceries["cap_cents"], groceries["pace_cents"]) == (
        30_000, 50_000, 25_000
    )
    assert groceries["projection_cents"] == 60_000
    assert groceries["projection_is_estimate"] is True
    for line in block["items"]:
        for key in ("spent_cents", "cap_cents", "pace_cents", "projection_cents",
                    "pending_cents"):
            assert line[key] is None or type(line[key]) is int, (key, line[key])
    assert "(an estimate)" in groceries["says"]
    assert "$300.00 of $500.00" in groceries["says"]
    # The world's household has no zone: UTC, and said so.
    assert body["utc_offset"] == "+00:00" and "no timezone set" in body["zone_note"]


def test_money_before_day_ten_gives_no_projection(world):  # noqa: F811
    _household, caller = world
    block = facts.pack(caller, dt.datetime(2026, 11, 9, 18, 0, tzinfo=dt.UTC))["sources"]["money"]
    assert block["state"] == "facts"
    assert all(i["projection_cents"] is None for i in block["items"])
    assert "Too early in the month to project" in block["note"]


# =============================================================================
# Empty and unreadable are different sentences
# =============================================================================


def test_nothing_on_file_is_empty_for_every_source(kevin, auth_client, pinned):
    sources = auth_client.get(FACTS).data["sources"]
    for name in facts.SOURCE_NAMES:
        block = sources[name]
        assert block["state"] == "empty", name
        assert block["items"] == [] and block["reason"] is None
        assert block["says"] == facts.EMPTY_WORDS[name]


@pytest.mark.parametrize("name", facts.SOURCE_NAMES)
def test_a_source_whose_read_fails_is_unreadable_never_empty(
    name, kevin, auth_client, household_a, pinned, monkeypatch
):
    _day(auth_client)

    def broken(request, now, zone):
        # A real database error, inside the savepoint, so the next source still reads.
        Event.objects.raw("select * from no_such_table_for_this_test")[0]

    monkeypatch.setitem(facts.READERS, name, broken)
    body = auth_client.get(FACTS).data
    block = body["sources"][name]
    assert block["state"] == "unreadable"
    assert block["items"] == []
    assert "could not read" in block["reason"] and "not an empty one" in block["reason"]
    assert block["says"] == block["reason"]
    assert "no_such_table" not in block["reason"]
    # Every other source still read.
    for other in facts.SOURCE_NAMES:
        if other != name:
            assert body["sources"][other]["state"] != "unreadable", other
    if name != "today_events":
        assert body["sources"]["today_events"]["state"] == "facts"


def test_a_python_error_in_a_reader_is_unreadable_too(kevin, auth_client, pinned, monkeypatch):
    def broken(request, now, zone):
        raise ValueError("boom")

    monkeypatch.setitem(facts.READERS, "money", broken)
    block = auth_client.get(FACTS).data["sources"]["money"]
    assert block["state"] == "unreadable" and "(ValueError)" in block["reason"]


def test_topics_that_cannot_be_read_are_said_and_nothing_is_left_out(
    kevin, auth_client, pinned, monkeypatch
):
    _day(auth_client)

    def broken(request, now):
        raise RuntimeError("down")

    monkeypatch.setattr(facts, "topics", broken)
    body = auth_client.get(FACTS).data
    assert body["topics"]["state"] == "unreadable"
    assert "Nothing was left out" in body["topics"]["reason"]
    assert body["said"] == [] and body["sources"]["ended_events"]["state"] == "facts"


# =============================================================================
# Never the same question twice
# =============================================================================


def _record(client, key, state):
    response = client.post(TOPICS, {"topic_key": key, "state": state}, format="json")
    assert response.status_code == 200, response.data
    return response.data


def test_answered_and_silenced_are_left_out_and_raised_are_said(kevin, auth_client, pinned):
    ids = _day(auth_client)
    _record(auth_client, f"event:{ids['ended']}", "raised")
    _record(auth_client, f"due:{ids['todo']}", "answered")
    _record(auth_client, f"event:{ids['later']}", "silenced")
    _record(auth_client, "place:Example Town", "answered")
    body = auth_client.get(FACTS).data
    sources = body["sources"]

    (ended,) = sources["ended_events"]["items"]
    assert ended["raised"] is True
    assert body["said"] == [f"event:{ids['ended']}"]

    assert _titles(sources["due_soon"]) == ["EXAMPLE 101: Essay"]
    assert sources["due_soon"]["left_out"] == 1
    assert sources["due_soon"]["items"][0]["raised"] is False

    today = sources["today_events"]
    assert today["state"] == "empty" and today["left_out"] == 1
    assert "already answered or silenced" in today["says"]
    assert set(body["closed"]) == {
        f"due:{ids['todo']}", f"event:{ids['later']}", "place:Example Town"
    }


def test_a_closed_topic_is_never_reopened_by_a_raise(kevin, auth_client):
    first = _record(auth_client, "money:2026-11:Dining", "raised")
    assert first["changed"] is True and first["state"] == "raised"
    assert _record(auth_client, "money:2026-11:Dining", "silenced")["changed"] is True
    again = _record(auth_client, "money:2026-11:Dining", "raised")
    assert again["changed"] is False and again["state"] == "silenced"
    assert _record(auth_client, "money:2026-11:Dining", "answered")["state"] == "answered"
    assert GreetingTopic.objects.filter(owner_user=kevin).count() == 1


def test_a_malformed_topic_or_state_is_refused_in_words(kevin, auth_client):
    for body in (
        {"topic_key": "no colon here", "state": "raised"},
        {"topic_key": "Event:1", "state": "raised"},
        {"topic_key": "event:1\nsecond line", "state": "raised"},
        {"topic_key": "event:1", "state": "forgotten"},
    ):
        response = auth_client.post(TOPICS, body, format="json")
        assert response.status_code == 400, body
        assert response.data["detail"].startswith("Nothing was recorded."), body
    assert not GreetingTopic.objects.exists()


def test_one_members_topics_never_close_anothers(kevin, auth_client, mia, mia_client, pinned):
    eid = _event(auth_client, "Family dinner", start=_at(2), end=_at(3))
    _record(mia_client, f"event:{eid}", "silenced")
    mine = auth_client.get(FACTS).data
    assert _titles(mine["sources"]["today_events"]) == ["Family dinner"]
    assert mine["closed"] == []
    assert mia_client.get(FACTS).data["closed"] == [f"event:{eid}"]


def test_the_database_refuses_a_topic_owned_by_a_non_member(household_a, user_b):
    with pytest.raises(IntegrityError), transaction.atomic():
        GreetingTopic.objects.create(
            household=household_a, owner_user=user_b, topic_key="event:1", state="raised"
        )


# =============================================================================
# Privacy and tenancy
# =============================================================================


def test_another_members_private_canvas_row_never_appears(
    kevin, auth_client, mia, mia_client, pinned, settings
):
    settings.LEGION_MCP = True
    _event(mia_client, "MIA 201: Lab report", kind="task", start=_at(4), private=True,
           meta={"canvas_assignment_id": 99, "canvas_submitted": False})
    _day(auth_client)
    kevins = auth_client.get(FACTS).data
    assert "MIA 201" not in json.dumps(kevins)
    assert "EXAMPLE 101: Essay" in json.dumps(kevins)
    mias = mia_client.get(FACTS).data
    assert _titles(mias["sources"]["due_soon"]) == ["MIA 201: Lab report", "Return library book"]
    assert "EXAMPLE 101" not in json.dumps(mias)
    is_error, text, _ = call(client_with(mia), "greeting_facts", {})
    assert not is_error and "EXAMPLE 101" not in text and "MIA 201: Lab report" in text


def test_another_household_sees_nothing(kevin, auth_client, user_b, token_b, pinned):
    _day(auth_client)
    _record(auth_client, "event:x", "raised")
    body = token_b.get(FACTS).data
    for name in facts.SOURCE_NAMES:
        assert body["sources"][name]["state"] == "empty", name
    assert body["said"] == [] and body["closed"] == []
    text = json.dumps(body)
    for mark in ("Dentist", "Team sync", "EXAMPLE 101", "library book"):
        assert mark not in text


def test_a_recruiter_visit_is_refused(household_a):
    demo = Household.objects.create(name="Demo visit test", is_demo_visit=True)
    visitor = User.objects.create_user(email="visitor-greeting@demo.invalid")
    HouseholdMember.objects.create(user=visitor, household=demo, role=HouseholdMember.RECRUITER)
    client = _client_for(visitor)
    assert client.get(FACTS).status_code == 403
    refused = client.post(TOPICS, {"topic_key": "event:1", "state": "raised"}, format="json")
    assert refused.status_code == 403
    assert not GreetingTopic.objects.filter(owner_user=visitor).exists()
    # The tool: off the recruiter's list, with the route's own reason, and refused again inside.
    assert "greeting_facts" not in RECRUITER_TOOLS
    assert RECRUITER_EXCLUDED["greeting_facts"] == RECRUITER_DENIED[
        "greeting.views.GreetingFactsView"
    ]
    result = TOOLS_BY_NAME["greeting_facts"].handler(SimpleNamespace(user=visitor), {})
    assert result.is_error and result.text.startswith("Nothing was read.")


def test_a_claude_worker_may_read_but_never_records(kevin, pinned):
    _token, raw = DeviceToken.issue(kevin, "claude-session-test")
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw}")
    assert client.get(FACTS).status_code == 200
    refused = client.post(TOPICS, {"topic_key": "event:1", "state": "answered"}, format="json")
    assert refused.status_code == 403 and "Claude session" in refused.data["detail"]
    assert not GreetingTopic.objects.exists()


# =============================================================================
# No zone id, and the tool
# =============================================================================


def test_no_iana_zone_id_anywhere_in_the_payload(
    kevin, auth_client, household_a, pinned, settings
):
    settings.LEGION_MCP = True
    _day(auth_client)
    TrainingProgramme.objects.create(household=household_a, started_on="2026-11-01")
    _weigh(household_a, NOW - dt.timedelta(hours=2))
    text = json.dumps(auth_client.get(FACTS).data)
    assert not ZONE_LIKE.search(text), ZONE_LIKE.search(text).group(0)
    assert "Chicago" not in text and "-06:00" in text
    _is_error, tool_text, structured = call(client_with(kevin), "greeting_facts", {})
    assert not ZONE_LIKE.search(tool_text) and "Chicago" not in tool_text


def test_the_tool_reads_the_same_pack_and_says_its_rules(kevin, auth_client, pinned, settings):
    settings.LEGION_MCP = True
    _day(auth_client)
    tool = TOOLS_BY_NAME["greeting_facts"]
    assert tool.writes is False
    assert "ESTIMATE" in tool.description and "unreadable" in tool.description
    is_error, text, structured = call(client_with(kevin), "greeting_facts", {})
    assert not is_error
    assert "ONLY things a greeting or check-in may mention" in text
    assert "- ended_events (facts): 1 on file." in text
    assert "Dentist checkup ended Fri Nov 13, 10:00 AM at Example Clinic." in text
    assert structured["sources"] == auth_client.get(FACTS).data["sources"]
