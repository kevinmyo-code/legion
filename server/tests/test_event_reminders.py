"""`events.remind_minutes_before` (web-revamp ticket 14, spec D7).

Accepted values round-trip, others are refused naming the allowed set, null
clears, and the CHECK in SQL refuses an out-of-set value even from a writer
that skips the serializer.
"""

from __future__ import annotations

import pytest
from django.db import IntegrityError, transaction

from api.event_columns import REMIND_MINUTES_CHOICES
from legacy.models.dates import Event

pytestmark = pytest.mark.django_db


@pytest.mark.parametrize("minutes", REMIND_MINUTES_CHOICES)
def test_every_offered_lead_time_round_trips(auth_client, minutes):
    created = auth_client.post(
        "/api/events",
        {"title": "Dentist", "starts_at": "2026-10-05T15:00:00Z", "remind_minutes_before": minutes},
        format="json",
    )
    assert created.status_code == 201, created.data
    assert created.data["remind_minutes_before"] == minutes
    assert Event.objects.get(pk=created.data["id"]).remind_minutes_before == minutes


def test_an_event_created_without_one_has_no_reminder(auth_client):
    created = auth_client.post("/api/events", {"title": "Dentist"}, format="json")
    assert created.status_code == 201
    assert created.data["remind_minutes_before"] is None


@pytest.mark.parametrize("minutes", [1, 45, -5, 2880])
def test_a_lead_time_outside_the_set_is_refused_naming_it(auth_client, minutes):
    response = auth_client.post(
        "/api/events", {"title": "Dentist", "remind_minutes_before": minutes}, format="json"
    )
    assert response.status_code == 400
    message = str(response.data["remind_minutes_before"][0])
    assert "0, 5, 10, 15, 30, 60, 120, 1440" in message
    assert "Nothing was saved" in message
    assert not Event.objects.filter(title="Dentist").exists()


def test_patch_changes_and_null_clears(auth_client):
    created = auth_client.post(
        "/api/events", {"title": "Dentist", "remind_minutes_before": 30}, format="json"
    )
    pk = created.data["id"]

    changed = auth_client.patch(f"/api/events/{pk}", {"remind_minutes_before": 60}, format="json")
    assert changed.status_code == 200
    assert changed.data["remind_minutes_before"] == 60

    refused = auth_client.patch(f"/api/events/{pk}", {"remind_minutes_before": 7}, format="json")
    assert refused.status_code == 400
    assert Event.objects.get(pk=pk).remind_minutes_before == 60

    cleared = auth_client.patch(f"/api/events/{pk}", {"remind_minutes_before": None}, format="json")
    assert cleared.status_code == 200
    assert cleared.data["remind_minutes_before"] is None
    assert Event.objects.get(pk=pk).remind_minutes_before is None


def test_the_database_refuses_an_out_of_set_value_without_the_serializer(auth_client):
    created = auth_client.post("/api/events", {"title": "Dentist"}, format="json")
    with pytest.raises(IntegrityError), transaction.atomic():
        Event.objects.filter(pk=created.data["id"]).update(remind_minutes_before=7)
