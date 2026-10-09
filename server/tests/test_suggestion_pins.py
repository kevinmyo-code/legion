"""Suggestion pins (Kevin, 2026-10-09): one member's "I want to go" on one
suggestion, seen by the whole household. `api/suggestion_pins.py` and
`api/suggestion_pin_views.py`.

A pin is not a plan ("Add to my plans" is untouched), a member pins and
unpins only their own, and a pin has its event's household and visibility.
"""
from __future__ import annotations

import pytest
from django.db import IntegrityError, connection, transaction

from household.households import tombstone_private_rows
from household.models import HouseholdMember
from legacy.models.dates import Event, SuggestionPin
from tests.conftest import _client_for, _member
from tests.test_engine_mcp import call, mcp_on, mcp_write  # noqa: F401  (fixtures)

pytestmark = pytest.mark.django_db

EPOCH = "1970-01-01T00:00:00Z"
FESTIVAL = {
    "title": "Riverfest",
    "kind": "suggestion",
    "starts_at": "2026-10-10T16:00:00Z",
    "structured_meta": {"city": "Austin", "url": "https://example.test/riverfest"},
}


@pytest.fixture
def kevin(household_user):
    household_user.first_name = "Kevin"
    household_user.save(update_fields=["first_name"])
    return household_user


@pytest.fixture
def mia(household_a):
    user = _member(household_a, "mia@example.com")
    user.first_name = "Mia"
    user.save(update_fields=["first_name"])
    return user


@pytest.fixture
def kevin_client(auth_client, kevin):
    return auth_client


@pytest.fixture
def mia_client(mia):
    return _client_for(mia)


def _suggestion(client, **overrides) -> dict:
    made = client.post("/api/events", {**FESTIVAL, **overrides}, format="json")
    assert made.status_code == 201, made.data
    return made.data


def _names(row) -> list[str]:
    return [entry["display_name"] for entry in row["pinned_by"]]


def test_an_unpinned_suggestion_and_a_plan_both_carry_an_empty_list(kevin_client):
    suggestion = _suggestion(kevin_client)
    plan = kevin_client.post("/api/events", {"title": "Dentist", "kind": "event"}, format="json")
    assert suggestion["pinned_by"] == []
    assert plan.data["pinned_by"] == []


def test_pin_then_unpin(kevin_client, kevin):
    row = _suggestion(kevin_client)

    pinned = kevin_client.post(f"/api/events/{row['id']}/pins")
    assert pinned.status_code == 201, pinned.data
    assert pinned.data["id"] == row["id"]
    assert pinned.data["pinned_by"] == [{"user_id": str(kevin.pk), "display_name": "Kevin"}]
    assert pinned.data["kind"] == "suggestion", "a pin is not Add to my plans"

    unpinned = kevin_client.delete(f"/api/events/{row['id']}/pins/mine")
    assert unpinned.status_code == 200, unpinned.data
    assert unpinned.data["pinned_by"] == []
    stored = SuggestionPin.objects.get(event_id=row["id"], user=kevin)
    assert stored.deleted_at is not None, "unpinning tombstones, so replicas learn it"


def test_pinning_twice_is_idempotent_and_repinning_revives_the_same_row(kevin_client, kevin):
    row = _suggestion(kevin_client)
    first = kevin_client.post(f"/api/events/{row['id']}/pins")
    again = kevin_client.post(f"/api/events/{row['id']}/pins")
    assert (first.status_code, again.status_code) == (201, 200)
    assert _names(again.data) == ["Kevin"]
    assert SuggestionPin.objects.filter(event_id=row["id"]).count() == 1

    pin_id = SuggestionPin.objects.get(event_id=row["id"]).id
    kevin_client.delete(f"/api/events/{row['id']}/pins/mine")
    revived = kevin_client.post(f"/api/events/{row['id']}/pins")
    assert revived.status_code == 201
    assert _names(revived.data) == ["Kevin"]
    assert list(SuggestionPin.objects.filter(event_id=row["id"]).values_list("id", flat=True)) == [
        pin_id
    ]


def test_unpinning_with_no_pin_is_still_a_200(kevin_client):
    row = _suggestion(kevin_client)
    response = kevin_client.delete(f"/api/events/{row['id']}/pins/mine")
    assert response.status_code == 200
    assert response.data["pinned_by"] == []


def test_a_second_member_pins_and_both_are_listed_oldest_first(
    kevin_client, mia_client, kevin, mia
):
    row = _suggestion(kevin_client)
    mia_client.post(f"/api/events/{row['id']}/pins")
    kevin_client.post(f"/api/events/{row['id']}/pins")

    seen_by_kevin = kevin_client.get(f"/api/events?since={EPOCH}").data["results"][0]
    assert seen_by_kevin["pinned_by"] == [
        {"user_id": str(mia.pk), "display_name": "Mia"},
        {"user_id": str(kevin.pk), "display_name": "Kevin"},
    ]
    seen_by_mia = mia_client.get(f"/api/events?since={EPOCH}").data["results"][0]
    assert _names(seen_by_mia) == ["Mia", "Kevin"]


def test_a_member_cannot_unpin_another_members_pin(kevin_client, mia_client):
    row = _suggestion(kevin_client)
    mia_client.post(f"/api/events/{row['id']}/pins")

    # The only unpin route is "mine": Kevin unpinning removes Kevin's (none),
    # and Mia's stays.
    response = kevin_client.delete(f"/api/events/{row['id']}/pins/mine")
    assert response.status_code == 200
    assert _names(response.data) == ["Mia"]
    # There is no route that names a member.
    assert kevin_client.delete(f"/api/events/{row['id']}/pins/{row['id']}").status_code == 404


def test_a_pin_is_refused_in_words_on_anything_but_a_suggestion(kevin_client):
    plan = kevin_client.post("/api/events", {"title": "Dentist", "kind": "event"}, format="json")
    response = kevin_client.post(f"/api/events/{plan.data['id']}/pins")
    assert response.status_code == 400
    assert "Only a suggestion can be pinned" in response.data["detail"]
    assert "Nothing was pinned" in response.data["detail"]
    assert not SuggestionPin.objects.filter(event_id=plan.data["id"]).exists()


def test_after_add_to_plans_the_event_no_longer_lists_pins(kevin_client):
    row = _suggestion(kevin_client)
    kevin_client.post(f"/api/events/{row['id']}/pins")
    added = kevin_client.patch(f"/api/events/{row['id']}", {"kind": "event"}, format="json")
    assert added.status_code == 200, added.data
    assert added.data["pinned_by"] == []
    refused = kevin_client.post(f"/api/events/{row['id']}/pins")
    assert refused.status_code == 400


def test_a_missing_or_deleted_event_is_a_404(kevin_client):
    row = _suggestion(kevin_client)
    kevin_client.delete(f"/api/events/{row['id']}")
    assert kevin_client.post(f"/api/events/{row['id']}/pins").status_code == 404
    assert (
        kevin_client.post("/api/events/00000000-0000-0000-0000-000000000000/pins").status_code
        == 404
    )


def test_the_database_refuses_a_pin_on_a_plan_without_the_view(kevin_client, kevin):
    plan = kevin_client.post("/api/events", {"title": "Dentist", "kind": "event"}, format="json")
    event = Event.objects.get(pk=plan.data["id"])
    with pytest.raises(IntegrityError), transaction.atomic():
        with connection.cursor() as cursor:
            cursor.execute(
                "insert into public.suggestion_pins (household_id, event_id, user_id) "
                "values (%s, %s, %s)",
                [str(event.household_id), str(event.pk), str(kevin.pk)],
            )


def test_the_database_refuses_a_pin_by_someone_outside_the_household(kevin_client, user_b):
    row = _suggestion(kevin_client)
    event = Event.objects.get(pk=row["id"])
    with pytest.raises(IntegrityError), transaction.atomic():
        with connection.cursor() as cursor:
            cursor.execute(
                "insert into public.suggestion_pins (household_id, event_id, user_id) "
                "values (%s, %s, %s)",
                [str(event.household_id), str(event.pk), str(user_b.pk)],
            )


def test_the_database_refuses_a_second_live_row_for_one_member(kevin_client, kevin):
    row = _suggestion(kevin_client)
    kevin_client.post(f"/api/events/{row['id']}/pins")
    event = Event.objects.get(pk=row["id"])
    with pytest.raises(IntegrityError), transaction.atomic():
        with connection.cursor() as cursor:
            cursor.execute(
                "insert into public.suggestion_pins (household_id, event_id, user_id) "
                "values (%s, %s, %s)",
                [str(event.household_id), str(event.pk), str(kevin.pk)],
            )


@pytest.mark.django_db(transaction=True)
def test_pinning_moves_the_events_updated_at_so_a_since_feed_resends_it(kevin_client):
    # Committed per request: inside one wrapping test transaction `now()` never
    # moves, so the bump could not be seen (test_events_api.py's own note).
    row = _suggestion(kevin_client)
    before = kevin_client.get(f"/api/changes?since={EPOCH}&aspects=events").data["server_time"]
    kevin_client.post(f"/api/events/{row['id']}/pins")

    events = kevin_client.get(f"/api/events?since={before}").data["results"]
    assert [(e["id"], _names(e)) for e in events] == [(row["id"], ["Kevin"])]


def test_the_changes_feed_carries_pinned_by_and_the_pin_rows(kevin_client, mia_client, mia):
    row = _suggestion(kevin_client)
    mia_client.post(f"/api/events/{row['id']}/pins")

    body = kevin_client.get(f"/api/changes?since={EPOCH}&aspects=events").data
    assert [_names(e) for e in body["events"]] == [["Mia"]]
    assert [(p["event"], p["user_id"], p["deleted_at"]) for p in body["suggestion_pins"]] == [
        (row["id"], str(mia.pk), None)
    ]

    mia_client.delete(f"/api/events/{row['id']}/pins/mine")
    after = kevin_client.get(f"/api/changes?since={EPOCH}&aspects=events").data
    assert after["events"][0]["pinned_by"] == []
    assert after["suggestion_pins"][0]["deleted_at"] is not None


def test_a_pin_on_a_private_suggestion_has_its_events_visibility(kevin_client, mia_client):
    row = _suggestion(kevin_client, visibility="private")
    kevin_client.post(f"/api/events/{row['id']}/pins")

    # Mia cannot reach Kevin's private suggestion, so cannot pin it either.
    assert mia_client.post(f"/api/events/{row['id']}/pins").status_code == 404
    assert mia_client.delete(f"/api/events/{row['id']}/pins/mine").status_code == 404
    # And his pin on it reaches her feed only as a redacted tombstone.
    pins = mia_client.get(f"/api/changes?since={EPOCH}&aspects=events").data["suggestion_pins"]
    assert len(pins) == 1
    assert set(pins[0]) == {"id", "deleted_at", "updated_at", "redacted"}


def test_another_household_can_neither_see_nor_pin(kevin_client, token_b):
    row = _suggestion(kevin_client)
    kevin_client.post(f"/api/events/{row['id']}/pins")

    assert token_b.post(f"/api/events/{row['id']}/pins").status_code == 404
    assert token_b.delete(f"/api/events/{row['id']}/pins/mine").status_code == 404
    body = token_b.get(f"/api/changes?since={EPOCH}&aspects=events").data
    assert body["suggestion_pins"] == []
    assert body["events"] == []
    assert SuggestionPin.objects.filter(event_id=row["id"], deleted_at__isnull=True).count() == 1


def test_a_name_never_set_falls_back_to_the_email(kevin_client, household_a):
    sam = _member(household_a, "sam.smith@example.com")
    row = _suggestion(kevin_client)
    response = _client_for(sam).post(f"/api/events/{row['id']}/pins")
    assert _names(response.data) == ["sam.smith"]


def test_a_removed_member_takes_their_pins_with_them(kevin_client, mia_client, mia, household_a):
    row = _suggestion(kevin_client)
    mia_client.post(f"/api/events/{row['id']}/pins")
    kevin_client.post(f"/api/events/{row['id']}/pins")

    removed = tombstone_private_rows(household_a, mia)
    assert removed["suggestion_pins"] == 1
    HouseholdMember.objects.filter(user=mia).delete()
    assert _names(kevin_client.get(f"/api/events?since={EPOCH}").data["results"][0]) == ["Kevin"]


def test_the_mcp_list_events_says_who_wants_to_go(mcp_write, kevin_client, mia_client):  # noqa: F811
    row = _suggestion(kevin_client, starts_at="2026-10-10T16:00:00Z")
    mia_client.post(f"/api/events/{row['id']}/pins")

    is_error, text, sections = call(
        mcp_write, "list_events", {"from": "2026-10-09", "to": "2026-10-12"}
    )
    assert not is_error, text
    assert [_names(s) for s in sections["suggestions"]] == [["Mia"]]
    assert "pinned_by" in text
