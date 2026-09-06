"""`/api/events` (django-engine ticket 04, Phase 2 slice). Exercised over
HTTP with DRF's test client, same posture as `test_auth_endpoints.py`.

**Two tests here are `transaction=True` on purpose** - see
`test_since_feed_excludes_rows_before_the_watermark_and_includes_tombstones`
for the full reasoning. Short version: the suite's default wrapper puts a
whole test inside ONE transaction, and Postgres's `now()` (what the
`touch_updated_at` trigger stamps `updated_at` with) is frozen at that
transaction's start, while `statement_timestamp()` (what `Now()` compiles
to, and what an INSERT here uses) advances per statement. Inside one
transaction an UPDATE's timestamp therefore lands BEFORE an INSERT's, which
is an artefact of the test wrapper and never happens in production, where
each request is its own transaction. `transaction=True` gives each HTTP
call its own real transaction and removes the artefact.
"""
from __future__ import annotations

from urllib.parse import quote

import pytest
from django.utils.dateparse import parse_datetime
from rest_framework.test import APIClient

from legacy.models.dates import Event

pytestmark = pytest.mark.django_db


def _clear_events():
    """`public.events` is a `managed = False` legacy table, so pytest-django's
    post-test flush (which only truncates tables belonging to MANAGED models)
    leaves rows written by a `transaction=True` test behind for every test
    that follows. Committed rows leaking forward would break, among others,
    `test_repeated_post_with_same_origin_guid_is_idempotent`, which expects
    its first POST to be a 201. Every transactional test in this module calls
    this in a `finally`."""
    Event.objects.all().delete()


def test_unauthenticated_get_events_is_401():
    response = APIClient().get("/api/events")
    assert response.status_code == 401


def test_unauthenticated_post_event_is_401():
    response = APIClient().post("/api/events", {"title": "test"}, format="json")
    assert response.status_code == 401


def test_create_event_returns_the_row_as_stored(auth_client):
    body = {"title": "COSC 3334 exam", "starts_at": "2026-10-01T09:00:00Z"}
    response = auth_client.post("/api/events", body, format="json")
    assert response.status_code == 201
    body = response.data
    assert body["title"] == "COSC 3334 exam"
    assert body["kind"] == "reminder"  # DB default, applied when the caller omits it
    assert body["source"] == "legion"
    assert body["provenance"] == "USER"
    assert body["done"] is False
    assert body["deleted_at"] is None
    assert Event.objects.filter(pk=body["id"]).exists()


def test_repeated_post_with_same_origin_guid_is_idempotent(auth_client):
    """Rule 5: "origin_guid/sync_id from the client is honoured on POST for
    idempotent upsert (the phone retries)" - a retried create with the same
    origin_guid must return the ROW THAT ALREADY EXISTS, not a duplicate."""
    body = {"title": "class reminder", "origin_guid": "phone-guid-1"}
    first = auth_client.post("/api/events", body, format="json")
    assert first.status_code == 201

    second = auth_client.post("/api/events", body, format="json")
    assert second.status_code == 200
    assert second.data["id"] == first.data["id"]
    assert Event.objects.filter(origin_guid="phone-guid-1").count() == 1


def test_wrong_kind_is_400_naming_the_allowed_set(auth_client):
    response = auth_client.post(
        "/api/events", {"title": "bad kind", "kind": "appointment"}, format="json"
    )
    assert response.status_code == 400
    body_text = str(response.data)
    assert "reminder" in body_text and "event" in body_text and "task" in body_text
    assert not Event.objects.filter(title="bad kind").exists()


def test_patch_done_round_trips_and_derives_done_at(auth_client):
    created = auth_client.post("/api/events", {"title": "todo"}, format="json").data

    ticked = auth_client.patch(f"/api/events/{created['id']}", {"done": True}, format="json")
    assert ticked.status_code == 200
    assert ticked.data["done"] is True
    assert ticked.data["done_at"] is not None

    unticked = auth_client.patch(f"/api/events/{created['id']}", {"done": False}, format="json")
    assert unticked.status_code == 200
    assert unticked.data["done"] is False
    assert unticked.data["done_at"] is None


def test_delete_is_a_soft_delete_and_idempotent(auth_client):
    created = auth_client.post("/api/events", {"title": "to remove"}, format="json").data

    first = auth_client.delete(f"/api/events/{created['id']}")
    assert first.status_code == 204
    event = Event.objects.get(pk=created["id"])
    assert event.deleted_at is not None

    second = auth_client.delete(f"/api/events/{created['id']}")
    assert second.status_code == 204


@pytest.mark.django_db(transaction=True)
def test_since_feed_excludes_rows_before_the_watermark_and_includes_tombstones(auth_client):
    """A watermark this API handed a client returns EXACTLY the rows changed
    at or after it - the earlier row absent, the boundary row present
    (`since` is inclusive), and a row that was DELETED after the watermark
    present as a tombstone.

    **This test used to pass only in company.** Run alone it failed with
    `assert 'newer' in {'older'}` - the tombstone silently missing from the
    feed - and that failure was real, not a test bug: `updated_at` was
    stamped from the PYTHON clock on INSERT and from the DATABASE clock by
    the `touch_updated_at` trigger on UPDATE, so the delete moved the row's
    `updated_at` onto a different machine's clock and it fell behind a
    watermark taken moments earlier. It happened to pass in a full-file run
    because the two clocks' 0.53s offset was smaller than the time between
    the test transaction opening and its first POST in that ordering, and
    larger in isolation. A test that reports a guarantee only when its
    neighbours run first is worse than a red one; `api/events.py` now takes
    every timestamp from Postgres (`Now()`), which is the actual fix.

    **`transaction=True`, and it is load-bearing.** With the whole test
    inside one transaction, `Now()` (`STATEMENT_TIMESTAMP()`, per statement)
    and the trigger's `now()` (transaction start, frozen) still disagree
    about ORDER - an UPDATE would time-stamp before an INSERT that ran
    before it. That is an artefact of the wrapper, not of production, where
    every request is its own transaction. Giving each HTTP call its own real
    transaction is what `test_checklists_api.py`'s equivalent test already
    does, for the same reason, and it is why nothing here compares a
    timestamp minted by this process against one minted by Postgres.

    **Do not read this test as the regression guard for the clock defect.**
    Checked by reverting `api/events.py` and running it: with
    `transaction=True` it PASSES against the old two-clock code, because
    removing the wrapper artefact also removes the interaction that made the
    old code fail here, and the measured skew's direction (Python behind)
    happens to be the harmless one. The guard is the sibling test,
    `test_updated_at_never_precedes_created_at_and_the_update_reaches_the_since_feed`,
    which fails against the old code deterministically. This test's job is
    the feed's SEMANTICS - exactly the changed rows, tombstones included -
    and it now does that job in any ordering.
    """
    try:
        before = auth_client.post("/api/events", {"title": "before"}, format="json").data
        assert before["id"]

        older = auth_client.post("/api/events", {"title": "older"}, format="json").data
        watermark = older["updated_at"]

        newer = auth_client.post("/api/events", {"title": "newer"}, format="json").data
        assert auth_client.delete(f"/api/events/{newer['id']}").status_code == 204

        response = auth_client.get(f"/api/events?since={quote(watermark)}")
        assert response.status_code == 200
        rows = {row["title"]: row for row in response.data["results"]}

        # EXACTLY these two: "before" is older than the watermark and must
        # not appear; "older" was created AT the watermark instant (it is
        # its own updated_at) and must, because `since` is inclusive (>=) -
        # matching the phone's own fetchChangedSince contract ("sinceMs is
        # inclusive... a row updated in the exact same millisecond as the
        # stored watermark must still be seen"); "newer" must, as a
        # tombstone, because a delete is a change like any other and a phone
        # that has not synced since learns of it only here.
        assert set(rows) == {"older", "newer"}
        assert rows["newer"]["deleted_at"] is not None
        assert rows["older"]["deleted_at"] is None
    finally:
        _clear_events()


@pytest.mark.django_db(transaction=True)
def test_updated_at_never_precedes_created_at_and_the_update_reaches_the_since_feed(auth_client):
    """The invariant the two-clock defect broke, stated directly: a row that
    is created and then updated has `updated_at >= created_at`, and a
    `?since=` feed keyed on the timestamp the client was handed AT CREATE
    TIME returns that update.

    Under the old code both halves rested on luck - `created_at` came from
    Django's clock and `updated_at` from Postgres's, so which one was
    "later" was decided by whichever machine's clock happened to be ahead.
    Measured on this machine 2026-09-06 the skew was 0.53s with Python
    behind, which is the SAFE direction (the feed over-returns); with the
    skew reversed, the update would have sorted before its own creation and
    a client holding the create-time watermark would never have seen it.
    Nothing in the old code chose the safe direction, which is the whole
    point.
    """
    try:
        created = auth_client.post("/api/events", {"title": "two clocks"}, format="json").data
        # One `STATEMENT_TIMESTAMP()` per statement, and both columns are
        # written by the same INSERT - so a never-updated row's two
        # timestamps are not merely close, they are identical. That is the
        # signature of one clock; two clocks cannot produce it.
        assert created["created_at"] == created["updated_at"]
        watermark = created["created_at"]

        patched = auth_client.patch(
            f"/api/events/{created['id']}", {"title": "one clock"}, format="json"
        )
        assert patched.status_code == 200
        assert patched.data["title"] == "one clock"
        # The PATCH response is the row AS STORED: `updated_at` here is the
        # value the trigger wrote, read back, not the stale one Django sent.
        assert parse_datetime(patched.data["updated_at"]) >= parse_datetime(
            patched.data["created_at"]
        )
        assert patched.data["updated_at"] != created["updated_at"]

        feed = auth_client.get(f"/api/events?since={quote(watermark)}")
        rows = {row["id"]: row for row in feed.data["results"]}
        assert created["id"] in rows, "the update never reached a feed keyed on its own create"
        assert rows[created["id"]]["title"] == "one clock"
    finally:
        _clear_events()


def test_since_feed_omits_rows_updated_before_it(auth_client):
    first = auth_client.post("/api/events", {"title": "first"}, format="json").data
    second = auth_client.post("/api/events", {"title": "second"}, format="json").data
    # These two really do differ even inside pytest-django's
    # one-transaction-per-test wrapping, because `EventSerializer.create`
    # writes `Now()`, which compiles to `STATEMENT_TIMESTAMP()` on Postgres
    # and advances per statement - unlike the transaction-frozen `now()` a
    # bare `default now()` column or the `touch_updated_at` trigger reads
    # (see `test_checklists_models.py`'s own doc comment for that gotcha,
    # found the hard way while writing this ticket's tests).
    #
    # This comment used to claim the two timestamps differ because they are
    # "stamped from Python (django.utils.timezone.now(), EventSerializer.create),
    # not deferred to Postgres's own now()". That was accurate about the code
    # at the time and was exactly the defect: two clocks in one ordering.
    # Both columns now come from Postgres; the assertion below is unchanged
    # because statement_timestamp still separates two INSERTs.
    assert first["updated_at"] != second["updated_at"]

    response = auth_client.get(f"/api/events?since={second['updated_at']}")
    titles = {row["title"] for row in response.data["results"]}
    assert "second" in titles
    assert "first" not in titles
