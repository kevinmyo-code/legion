"""`/api/checklists` (django-engine ticket 04, Phase 2 slice). Exercised
over HTTP with DRF's test client. `ChecklistController.kt` is the
semantics; the exact measured-tick refusal wording is asserted verbatim
against `checklists/serializers.py`'s own copy of it.
"""
from __future__ import annotations

from urllib.parse import quote

import pytest
from rest_framework.test import APIClient

from checklists.models import Checklist, ChecklistItem, ChecklistTick

pytestmark = pytest.mark.django_db


def _make_checklist(auth_client, name="bio"):
    # Trailing slash required here, and only here: `checklists/urls.py`'s
    # list-root pattern is `""` under the `api/checklists/` include prefix
    # (which itself ends in a slash), so the combined pattern is
    # `api/checklists/` exactly - every OTHER route in this file names its
    # own suffix after the id and needs no trailing slash, matching
    # `/api/events`'s own no-trailing-slash convention.
    return auth_client.post("/api/checklists/", {"name": name}, format="json").data


def _make_item(auth_client, checklist_id, text="squats", **extra):
    body = {"text": text, **extra}
    return auth_client.post(f"/api/checklists/{checklist_id}/items", body, format="json").data


def test_unauthenticated_is_401_everywhere():
    client = APIClient()
    checklist_id = "00000000-0000-0000-0000-000000000000"
    item_id = "00000000-0000-0000-0000-000000000000"
    assert client.get("/api/checklists/").status_code == 401
    assert client.post("/api/checklists/", {"name": "x"}, format="json").status_code == 401
    assert client.get(f"/api/checklists/{checklist_id}").status_code == 401
    assert client.patch(f"/api/checklists/{checklist_id}", {}, format="json").status_code == 401
    assert client.delete(f"/api/checklists/{checklist_id}").status_code == 401
    assert client.get(f"/api/checklists/{checklist_id}/items").status_code == 401
    items_url = f"/api/checklists/{checklist_id}/items"
    assert client.post(items_url, {}, format="json").status_code == 401
    tick_url = f"/api/checklists/{checklist_id}/items/{item_id}/tick"
    assert client.post(tick_url, {"day": 1}, format="json").status_code == 401
    assert client.delete(f"{tick_url}/1").status_code == 401


def test_repeated_post_with_same_sync_id_is_idempotent(auth_client):
    """Rule 5: "origin_guid/sync_id from the client is honoured on POST for
    idempotent upsert (the phone retries)" - a retried checklist create
    with the same sync_id must return the ROW THAT ALREADY EXISTS, not a
    duplicate. checklists' equivalent of test_events_api.py's
    test_repeated_post_with_same_origin_guid_is_idempotent."""
    body = {"name": "bio", "sync_id": "phone-sync-1"}
    first = auth_client.post("/api/checklists/", body, format="json")
    assert first.status_code == 201

    second = auth_client.post("/api/checklists/", body, format="json")
    assert second.status_code == 200
    assert second.data["id"] == first.data["id"]
    assert Checklist.objects.filter(sync_id="phone-sync-1").count() == 1


def test_create_checklist_and_item_round_trip(auth_client):
    checklist = _make_checklist(auth_client, "fitness")
    assert checklist["name"] == "fitness"
    assert Checklist.objects.filter(pk=checklist["id"]).exists()

    item = _make_item(auth_client, checklist["id"], "walk 10k steps", measure_unit="steps")
    assert item["measure_unit"] == "steps"
    assert ChecklistItem.objects.filter(pk=item["id"], checklist_id=checklist["id"]).exists()


def test_tick_without_value_on_measured_item_is_400_with_exact_sentence_and_stores_nothing(
    auth_client,
):
    checklist = _make_checklist(auth_client, "fitness")
    item = _make_item(auth_client, checklist["id"], "walk 10k steps", measure_unit="steps")

    response = auth_client.post(
        f"/api/checklists/{checklist['id']}/items/{item['id']}/tick", {"day": 20000}, format="json"
    )

    assert response.status_code == 400
    expected_sentence = (
        '"walk 10k steps" is measured in steps - give a number to tick it, nothing was recorded.'
    )
    assert expected_sentence in str(response.data)
    assert not ChecklistTick.objects.filter(item_id=item["id"], day=20000).exists()


def test_tick_with_value_on_measured_item_succeeds(auth_client):
    checklist = _make_checklist(auth_client, "fitness")
    item = _make_item(auth_client, checklist["id"], "walk 10k steps", measure_unit="steps")

    response = auth_client.post(
        f"/api/checklists/{checklist['id']}/items/{item['id']}/tick",
        {"day": 20000, "value": 8400},
        format="json",
    )

    assert response.status_code == 201
    assert response.data["value"] == 8400.0


def test_binary_item_tick_needs_no_value(auth_client):
    checklist = _make_checklist(auth_client, "chores")
    item = _make_item(auth_client, checklist["id"], "take out trash")

    response = auth_client.post(
        f"/api/checklists/{checklist['id']}/items/{item['id']}/tick", {"day": 20000}, format="json"
    )
    assert response.status_code == 201
    assert response.data["value"] is None


def test_duplicate_tick_on_same_item_and_day_is_a_no_op_not_a_500(auth_client):
    checklist = _make_checklist(auth_client, "chores")
    item = _make_item(auth_client, checklist["id"], "take out trash")

    first = auth_client.post(
        f"/api/checklists/{checklist['id']}/items/{item['id']}/tick", {"day": 20000}, format="json"
    )
    assert first.status_code == 201

    second = auth_client.post(
        f"/api/checklists/{checklist['id']}/items/{item['id']}/tick", {"day": 20000}, format="json"
    )
    assert second.status_code == 200
    assert ChecklistTick.objects.filter(item_id=item["id"], day=20000).count() == 1
    # The FIRST tap's ticked_at survives - a double-tap does not overwrite it.
    assert second.data["ticked_at"] == first.data["ticked_at"]


def test_untick_then_retick_revives_with_a_fresh_ticked_at(auth_client):
    checklist = _make_checklist(auth_client, "chores")
    item = _make_item(auth_client, checklist["id"], "take out trash")

    first = auth_client.post(
        f"/api/checklists/{checklist['id']}/items/{item['id']}/tick", {"day": 20000}, format="json"
    )
    first_ticked_at = first.data["ticked_at"]

    untick = auth_client.delete(f"/api/checklists/{checklist['id']}/items/{item['id']}/tick/20000")
    assert untick.status_code == 204
    tick = ChecklistTick.objects.get(item_id=item["id"], day=20000)
    assert tick.deleted_at is not None

    retick = auth_client.post(
        f"/api/checklists/{checklist['id']}/items/{item['id']}/tick", {"day": 20000}, format="json"
    )
    assert retick.status_code == 200
    assert retick.data["deleted_at"] is None
    assert retick.data["ticked_at"] != first_ticked_at
    # Still exactly one physical row for (item, day) - a revive is an
    # UPDATE, never a second INSERT.
    assert ChecklistTick.objects.filter(item_id=item["id"], day=20000).count() == 1


def test_untick_is_idempotent(auth_client):
    checklist = _make_checklist(auth_client, "chores")
    item = _make_item(auth_client, checklist["id"], "take out trash")

    # No tick exists at all yet - unticking is still a clean 204.
    tick_url = f"/api/checklists/{checklist['id']}/items/{item['id']}/tick/20000"
    response = auth_client.delete(tick_url)
    assert response.status_code == 204


def test_checklist_delete_does_not_cascade_to_items(auth_client):
    checklist = _make_checklist(auth_client, "bio")
    item = _make_item(auth_client, checklist["id"], "squats")

    response = auth_client.delete(f"/api/checklists/{checklist['id']}")
    assert response.status_code == 204

    checklist_row = Checklist.objects.get(pk=checklist["id"])
    assert checklist_row.deleted_at is not None

    item_row = ChecklistItem.objects.get(pk=item["id"])
    assert item_row.deleted_at is None  # untouched - a checklist's own delete never cascades


@pytest.mark.django_db(transaction=True)
def test_since_feed_excludes_checklists_before_the_watermark(auth_client):
    """`transaction=True`, not the suite's default wrapped-transaction
    style, and deliberately so. `Checklist.updated_at` is DB-stamped
    (`db_default=Now()` on INSERT, the `checklists_touch_updated_at()`
    trigger on UPDATE - and the trigger overwrites UNCONDITIONALLY, so
    there is no way to backdate it from the ORM either). Postgres's own
    `now()` is fixed for the lifetime of one transaction - under the
    suite's default style (pytest-django wraps each test in one), "older"
    and "newer" would receive the IDENTICAL `updated_at`, since both
    requests run inside that same frozen transaction (confirmed the hard
    way: a version of this test using a manually-backdated `.update()`
    silently had it overwritten straight back to "now" by the trigger,
    which is otherwise exactly the guarantee that trigger exists to
    provide - see `test_checklists_models.py`'s own
    `test_updated_at_touch_trigger_overrides_whatever_the_caller_supplies`).
    `transaction=True` makes each HTTP call its own real, committed
    transaction, so genuine wall-clock time elapses between them and the
    two rows' `updated_at` values are genuinely, not just nominally,
    different - the same shape `test_events_api.py`'s equivalent test gets
    "for free" (events stamp `updated_at` from Python, not from a DB
    default, so it never hit this gotcha at all).

    **The watermark itself is derived from "older"'s own `updated_at`, not
    from a fresh local `timezone.now()` call - a second gotcha found the
    same way as the first.** `Checklist.updated_at` is stamped by
    POSTGRES's own clock (a remote AWS host); a local `timezone.now()`
    computed on the machine running this test is a DIFFERENT clock domain
    entirely, and the first version of this test (comparing a local
    timestamp against a Postgres-stamped one) failed exactly the way real
    clock skew would predict - "older" still matched a watermark taken
    after it was created. Adding one microsecond to "older"'s own,
    already-Postgres-stamped `updated_at` stays inside that same clock
    domain and sidesteps the comparison entirely, matching this ticket's
    own `ChangesView.server_time` reasoning (never let a client-computed
    local timestamp stand in for the server's own clock).

    **A third gotcha, found the same empirical way after the first two:**
    the reconstructed watermark's `+00:00` UTC offset (Python's
    `isoformat()` never emits the `Z` suffix DRF's own rendering uses) is a
    literal `+` character, and a raw, un-percent-encoded `+` in a query
    string decodes to a SPACE by the `application/x-www-form-urlencoded`
    convention every URL parser follows - `parse_since` then failed to
    parse the corrupted string and fell back to EPOCH (its own, correct,
    documented "unparsable means fetch everything" rule), which is exactly
    why "older" kept reappearing despite the filter logic itself being
    proven correct in isolation (a direct queryset over the same watermark
    value, bypassing the HTTP layer entirely, excluded "older" the whole
    time). A real HTTP client library encodes its own query parameters and
    would never hit this; a hand-built f-string, as this test originally
    was, is exactly the case that does. `urllib.parse.quote` is the fix.
    """
    from datetime import timedelta

    from django.utils.dateparse import parse_datetime

    older = _make_checklist(auth_client, "older")
    watermark = (parse_datetime(older["updated_at"]) + timedelta(microseconds=1)).isoformat()
    newer = _make_checklist(auth_client, "newer")
    assert newer["updated_at"] != older["updated_at"]

    response = auth_client.get(f"/api/checklists/?since={quote(watermark)}")
    names = {row["name"] for row in response.data["results"]}
    assert "newer" in names
    assert "older" not in names
