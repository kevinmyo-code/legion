"""Event suggestions (Kevin, 2026-10-09): `events.kind = 'suggestion'`.

A suggestion is something the household COULD do on the weekend, shown on the
calendar in its own colour. It is never their plan, so: it takes no reminder
and is never done (serializer and SQL both), push never rings for it, and the
engine's `list_events` hands it back in its own list, never among the plans.
"Add to my plans" is a PATCH to `kind = "event"`; "Not interested" is a DELETE.
"""

from __future__ import annotations

import datetime as dt

import pytest
from django.db import IntegrityError, connection, transaction

from api.event_columns import SUGGESTION_CHECK_NAME
from legacy.models.dates import Event
from push.dispatch import dispatch_household
from tests.test_engine_mcp import call, mcp_on, mcp_write  # noqa: F401  (fixtures)
from tests.test_push import FakeSender, subscribe

pytestmark = pytest.mark.django_db

SUGGESTION = {
    "title": "Victoria Bach Festival",
    "starts_at": "2026-10-10T19:00:00Z",
    "ends_at": "2026-10-10T21:00:00Z",
    "location": "Victoria, TX",
    "notes": "https://example.com/bach - $25",
    "kind": "suggestion",
}


def _make(client, **fields):
    made = client.post("/api/events", {**SUGGESTION, **fields}, format="json")
    assert made.status_code == 201, made.data
    return made.data


def test_a_suggestion_round_trips_shared_by_default(auth_client):
    row = _make(auth_client)
    assert row["kind"] == "suggestion"
    assert row["visibility"] == "shared"
    assert row["remind_minutes_before"] is None
    assert row["notes"] == "https://example.com/bach - $25"


def test_a_wrong_kind_names_suggestion_among_the_allowed(auth_client):
    response = auth_client.post("/api/events", {"title": "x", "kind": "idea"}, format="json")
    assert response.status_code == 400
    assert "suggestion" in str(response.data)


def test_a_suggestion_refuses_a_reminder_in_words(auth_client):
    response = auth_client.post(
        "/api/events", {**SUGGESTION, "remind_minutes_before": 30}, format="json"
    )
    assert response.status_code == 400
    message = str(response.data["remind_minutes_before"][0])
    assert "not a plan" in message and "Nothing was saved" in message
    assert not Event.objects.filter(title=SUGGESTION["title"]).exists()


def test_a_suggestion_cannot_be_ticked(auth_client):
    row = _make(auth_client)
    response = auth_client.patch(f"/api/events/{row['id']}", {"done": True}, format="json")
    assert response.status_code == 400
    assert Event.objects.get(pk=row["id"]).done is False


def test_an_event_with_a_reminder_cannot_become_a_suggestion(auth_client):
    made = auth_client.post(
        "/api/events",
        {"title": "Dentist", "kind": "event", "remind_minutes_before": 30},
        format="json",
    )
    response = auth_client.patch(
        f"/api/events/{made.data['id']}", {"kind": "suggestion"}, format="json"
    )
    assert response.status_code == 400
    assert Event.objects.get(pk=made.data["id"]).kind == "event"


def test_the_database_refuses_a_suggestion_with_a_reminder_without_the_serializer(auth_client):
    row = _make(auth_client)
    with pytest.raises(IntegrityError) as caught, transaction.atomic():
        Event.objects.filter(pk=row["id"]).update(remind_minutes_before=30)
    assert SUGGESTION_CHECK_NAME in str(caught.value)
    with pytest.raises(IntegrityError), transaction.atomic():
        Event.objects.filter(pk=row["id"]).update(done=True)


def test_add_to_my_plans_keeps_time_place_and_notes_and_then_a_reminder_is_allowed(auth_client):
    row = _make(auth_client)
    added = auth_client.patch(f"/api/events/{row['id']}", {"kind": "event"}, format="json")
    assert added.status_code == 200, added.data
    for key in ("title", "starts_at", "ends_at", "location", "notes", "visibility"):
        assert added.data[key] == row[key], key
    assert added.data["kind"] == "event"
    reminded = auth_client.patch(
        f"/api/events/{row['id']}", {"remind_minutes_before": 60}, format="json"
    )
    assert reminded.status_code == 200, reminded.data


def test_not_interested_is_a_tombstone(auth_client):
    row = _make(auth_client)
    assert auth_client.delete(f"/api/events/{row['id']}").status_code == 204
    assert Event.objects.get(pk=row["id"]).deleted_at is not None


def test_push_never_reminds_for_a_suggestion_even_if_one_had_a_lead_time(
    auth_client, household_user, household_a
):
    """The CHECK keeps a suggestion's reminder null, so the only way to test
    the reader's own exclusion is to lift the CHECK inside this test's
    transaction (DDL is transactional in Postgres; the wrapper rolls it back)."""
    row = _make(auth_client, starts_at="2026-10-05T15:30:00Z", ends_at=None)
    with connection.cursor() as cursor:
        cursor.execute(f"alter table public.events drop constraint {SUGGESTION_CHECK_NAME}")
    Event.objects.filter(pk=row["id"]).update(remind_minutes_before=30)
    subscribe(household_user, household_a)
    send = FakeSender()
    dispatch_household(household_a, dt.datetime(2026, 10, 5, 15, 0, tzinfo=dt.UTC), send)
    assert send.sent == []


def test_list_events_keeps_suggestions_out_of_the_plans(mcp_write):  # noqa: F811
    is_error, text, made = call(
        mcp_write,
        "add_event",
        {"fields": {**SUGGESTION, "origin_guid": "suggest-1"}},
    )
    assert not is_error, text
    suggestion_id = made["row"]["id"]
    is_error, text, made = call(
        mcp_write,
        "add_event",
        {"fields": {"title": "Dentist", "kind": "event", "starts_at": "2026-10-10T15:00:00Z"}},
    )
    assert not is_error, text
    plan_id = made["row"]["id"]

    is_error, text, sections = call(
        mcp_write, "list_events", {"from": "2026-10-09", "to": "2026-10-12"}
    )
    assert not is_error, text
    assert [r["id"] for r in sections["in_window"]] == [plan_id]
    assert [r["id"] for r in sections["suggestions"]] == [suggestion_id]
    assert all(
        r["id"] != suggestion_id
        for key in ("in_window", "repeating", "undated")
        for r in sections[key]
    )
    assert "NOT plans" in text

    is_error, text, _ = call(
        mcp_write, "update_event", {"id": suggestion_id, "fields": {"kind": "event"}}
    )
    assert not is_error, text
    _, _, sections = call(mcp_write, "list_events", {"from": "2026-10-09", "to": "2026-10-12"})
    assert sorted(r["id"] for r in sections["in_window"]) == sorted([plan_id, suggestion_id])
    assert sections["suggestions"] == []


def test_the_engine_tools_say_a_suggestion_is_not_a_plan():
    from engine_mcp.tools import TOOLS

    by_name = {tool.name: tool for tool in TOOLS}
    assert "not their plan" in by_name["add_event"].description
    assert "not plans" in by_name["list_events"].description
    assert '{"kind": "event"}' in by_name["update_event"].description


def test_a_suggestions_details_round_trip_in_structured_meta(auth_client):
    meta = {
        "city": "Austin",
        "venue": "Zilker Park",
        "address": "2100 Barton Springs Rd",
        "url": "https://example.com/acl",
        "price": "$25",
    }
    row = _make(auth_client, structured_meta=meta)
    assert row["structured_meta"] == meta


def test_a_malformed_suggestion_meta_is_refused_in_words(auth_client):
    response = auth_client.post(
        "/api/events", {**SUGGESTION, "structured_meta": {"city": 5}}, format="json"
    )
    assert response.status_code == 400
    assert "Nothing was saved" in str(response.data["structured_meta"][0])
