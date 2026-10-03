"""ADR 0052: a row may be private to one member (web-revamp ticket 06).

`tests/test_tenancy.py` proves one household never sees another's rows. This
file proves the other boundary: two members of ONE household, Kevin and Mia,
and the five privacy-bearing tables (events, checklists, items, ticks, and
event skips, which inherit their event's - their routes and their own
checks are in `tests/test_event_skips.py`). Every route and every MCP tool
that reaches those tables is driven as the member who must not see the row:

- a list hides it, or carries it only as a redacted tombstone;
- detail, PATCH and DELETE are 404, the same shape as another household's;
- the changes feed carries only the redacted tombstone, including when a
  row is made private after the other member's replica already holds it;
- POST defaults to shared; the who-may-change rule refuses in words;
- removing a member tombstones their private rows and keeps them private.
"""

from __future__ import annotations

import io

import pytest
from django.core.management import CommandError, call_command
from django.db import IntegrityError, transaction

from checklists.models import Checklist, ChecklistItem, ChecklistTick
from engine_mcp.tools import TOOLS_BY_NAME
from household.tenancy import MAKE_PRIVATE_REFUSAL
from legacy.models.dates import Event
from tests.conftest import _client_for, _member

pytestmark = pytest.mark.django_db

REDACTED_KEYS = {"id", "deleted_at", "updated_at", "redacted"}
SERVER_ONLY = {"owner_user", "owner_user_id", "created_by", "created_by_id"}
EPOCH = "1970-01-01T00:00:00Z"


@pytest.fixture
def kevin(household_user):
    return household_user


@pytest.fixture
def mia(household_a):
    return _member(household_a, "mia@example.com")


@pytest.fixture
def kevin_client(auth_client):
    return auth_client


@pytest.fixture
def mia_client(mia):
    return _client_for(mia)


def _private_world(client, tag="secret"):
    """One private event and one private checklist with an item and a tick,
    created by `client`'s member."""
    event = client.post(
        "/api/events",
        {"title": f"{tag}-event", "starts_at": "2026-10-05T09:00:00Z", "visibility": "private"},
        format="json",
    )
    assert event.status_code == 201, event.data
    checklist = client.post(
        "/api/checklists/", {"name": f"{tag}-list", "visibility": "private"}, format="json"
    )
    assert checklist.status_code == 201, checklist.data
    item = client.post(
        f"/api/checklists/{checklist.data['id']}/items", {"text": f"{tag}-item"}, format="json"
    )
    assert item.status_code == 201, item.data
    tick = client.post(
        f"/api/checklists/{checklist.data['id']}/items/{item.data['id']}/tick",
        {"day": 20366},
        format="json",
    )
    assert tick.status_code == 201, tick.data
    return {
        "event": event.data["id"],
        "checklist": checklist.data["id"],
        "item": item.data["id"],
        "tick": tick.data["id"],
    }


def _assert_redacted(row, row_id):
    assert set(row) == REDACTED_KEYS, row
    assert row["id"] == str(row_id)
    assert row["redacted"] is True
    assert row["deleted_at"] == row["updated_at"]


def _rows_by_id(rows):
    return {str(row["id"]): row for row in rows}


# =============================================================================
# Create: shared by default, private on request, creator from the request
# =============================================================================


def test_post_defaults_to_shared_and_records_the_creator(kevin_client, kevin):
    event = kevin_client.post("/api/events", {"title": "Dinner"}, format="json")
    checklist = kevin_client.post("/api/checklists/", {"name": "Groceries"}, format="json")
    item = kevin_client.post(
        f"/api/checklists/{checklist.data['id']}/items", {"text": "eggs"}, format="json"
    )
    assert event.data["visibility"] == "shared"
    assert checklist.data["visibility"] == "shared"
    stored = Event.objects.get(pk=event.data["id"])
    assert (stored.owner_user_id, stored.created_by_id) == (None, kevin.pk)
    stored = Checklist.objects.get(pk=checklist.data["id"])
    assert (stored.owner_user_id, stored.created_by_id) == (None, kevin.pk)
    assert ChecklistItem.objects.get(pk=item.data["id"]).created_by_id == kevin.pk


def test_post_private_is_private_to_the_poster(kevin_client, kevin):
    event = kevin_client.post(
        "/api/events", {"title": "HW 4", "visibility": "private"}, format="json"
    )
    checklist = kevin_client.post(
        "/api/checklists/", {"name": "Thesis", "visibility": "private"}, format="json"
    )
    assert event.data["visibility"] == "private"
    assert checklist.data["visibility"] == "private"
    assert Event.objects.get(pk=event.data["id"]).owner_user_id == kevin.pk
    assert Checklist.objects.get(pk=checklist.data["id"]).owner_user_id == kevin.pk


def test_a_user_id_sent_in_the_body_is_never_honoured(kevin_client, mia, kevin):
    event = kevin_client.post(
        "/api/events",
        {"title": "spoof", "owner_user_id": str(mia.pk), "created_by_id": str(mia.pk)},
        format="json",
    )
    assert event.status_code == 201
    stored = Event.objects.get(pk=event.data["id"])
    assert (stored.owner_user_id, stored.created_by_id) == (None, kevin.pk)


def test_an_unknown_visibility_is_refused_naming_the_set(kevin_client):
    response = kevin_client.post(
        "/api/events", {"title": "x", "visibility": "family"}, format="json"
    )
    assert response.status_code == 400
    assert "shared, private" in str(response.data["visibility"][0])
    response = kevin_client.post(
        "/api/checklists/", {"name": "x", "visibility": "secret"}, format="json"
    )
    assert response.status_code == 400
    assert "shared, private" in str(response.data["visibility"][0])


# =============================================================================
# Lists, detail, PATCH, DELETE: the other member never reaches a private row
# =============================================================================


def test_the_owner_sees_their_private_rows_in_full(kevin_client):
    ids = _private_world(kevin_client)
    events = _rows_by_id(kevin_client.get(f"/api/events?since={EPOCH}").data["results"])
    assert events[ids["event"]]["title"] == "secret-event"
    lists = _rows_by_id(kevin_client.get(f"/api/checklists/?since={EPOCH}").data["results"])
    assert lists[ids["checklist"]]["name"] == "secret-list"
    assert kevin_client.get(f"/api/checklists/{ids['checklist']}").status_code == 200
    items = kevin_client.get(f"/api/checklists/{ids['checklist']}/items").data["results"]
    assert [row["text"] for row in items] == ["secret-item"]


def test_list_routes_carry_another_members_private_rows_only_as_redacted(kevin_client, mia_client):
    ids = _private_world(kevin_client)
    shared = kevin_client.post("/api/events", {"title": "shared-event"}, format="json").data

    events = _rows_by_id(mia_client.get(f"/api/events?since={EPOCH}").data["results"])
    _assert_redacted(events[ids["event"]], ids["event"])
    assert events[str(shared["id"])]["title"] == "shared-event"

    lists = _rows_by_id(mia_client.get(f"/api/checklists/?since={EPOCH}").data["results"])
    _assert_redacted(lists[ids["checklist"]], ids["checklist"])

    items = mia_client.get(f"/api/checklists/{ids['checklist']}/items")
    assert items.status_code == 200
    assert items.data["results"] == []


def test_detail_patch_and_delete_of_another_members_private_row_are_404(kevin_client, mia_client):
    ids = _private_world(kevin_client)
    event, checklist, item = ids["event"], ids["checklist"], ids["item"]
    item_path = f"/api/checklists/{checklist}/items/{item}"

    refused = [
        mia_client.patch(f"/api/events/{event}", {"title": "hijacked"}, format="json"),
        mia_client.patch(f"/api/events/{event}", {"visibility": "shared"}, format="json"),
        mia_client.delete(f"/api/events/{event}"),
        mia_client.get(f"/api/checklists/{checklist}"),
        mia_client.patch(f"/api/checklists/{checklist}", {"name": "hijacked"}, format="json"),
        mia_client.patch(f"/api/checklists/{checklist}", {"visibility": "shared"}, format="json"),
        mia_client.delete(f"/api/checklists/{checklist}"),
        mia_client.post(f"/api/checklists/{checklist}/items", {"text": "x"}, format="json"),
        mia_client.get(item_path),
        mia_client.patch(item_path, {"text": "hijacked"}, format="json"),
        mia_client.delete(item_path),
        mia_client.post(f"{item_path}/tick", {"day": 20367}, format="json"),
        mia_client.delete(f"{item_path}/tick/20366"),
    ]
    for response in refused:
        assert response.status_code == 404, (response.status_code, response.data)

    stored = Event.objects.get(pk=event)
    assert (stored.title, stored.deleted_at, stored.owner_user_id is not None) == (
        "secret-event",
        None,
        True,
    )
    stored = Checklist.objects.get(pk=checklist)
    assert (stored.name, stored.deleted_at) == ("secret-list", None)
    assert ChecklistItem.objects.get(pk=item).text == "secret-item"
    assert ChecklistItem.objects.filter(checklist_id=checklist).count() == 1
    assert ChecklistTick.objects.get(pk=ids["tick"]).deleted_at is None
    assert not ChecklistTick.objects.filter(day=20367).exists()


def test_an_origin_guid_or_sync_id_retry_never_hands_back_another_members_row(
    kevin_client, mia_client
):
    kevin_client.post(
        "/api/events",
        {"title": "secret", "origin_guid": "guid-1", "visibility": "private"},
        format="json",
    )
    kevin_client.post(
        "/api/checklists/",
        {"name": "secret", "sync_id": "sync-1", "visibility": "private"},
        format="json",
    )
    event = mia_client.post(
        "/api/events", {"title": "mine", "origin_guid": "guid-1"}, format="json"
    )
    checklist = mia_client.post(
        "/api/checklists/", {"name": "mine", "sync_id": "sync-1"}, format="json"
    )
    for response in (event, checklist):
        assert response.status_code == 400, response.data
        assert "secret" not in str(response.data)


# =============================================================================
# The changes feed
# =============================================================================


def test_the_feed_carries_another_members_private_rows_only_as_redacted(kevin_client, mia_client):
    ids = _private_world(kevin_client)
    feed = mia_client.get("/api/changes?aspects=events,checklists").data
    for table, key in (
        ("events", "event"),
        ("checklists", "checklist"),
        ("checklist_items", "item"),
        ("checklist_ticks", "tick"),
    ):
        rows = _rows_by_id(feed[table])
        _assert_redacted(rows[ids[key]], ids[key])
    own = kevin_client.get("/api/changes?aspects=events,checklists").data
    assert _rows_by_id(own["events"])[ids["event"]]["title"] == "secret-event"
    assert _rows_by_id(own["checklist_items"])[ids["item"]]["text"] == "secret-item"


@pytest.mark.django_db(transaction=True)
def test_making_a_row_private_reaches_a_replica_that_already_holds_it(kevin_client, mia_client):
    """Mia's replica pulled the event and the list while they were shared.
    Kevin makes both private. Her next pull from her stored watermark carries
    a redacted tombstone for each, and for the list's item and tick, whose
    own `updated_at` never moved.

    `transaction=True`: the touch trigger stamps `now()`, frozen at the start
    of the wrapping test transaction, which would put the UPDATE before the
    watermark. Each request in its own transaction is what production does.
    """
    try:
        event = kevin_client.post("/api/events", {"title": "Dentist"}, format="json").data
        checklist = kevin_client.post("/api/checklists/", {"name": "Gifts"}, format="json").data
        item = kevin_client.post(
            f"/api/checklists/{checklist['id']}/items", {"text": "scarf"}, format="json"
        ).data
        tick = kevin_client.post(
            f"/api/checklists/{checklist['id']}/items/{item['id']}/tick",
            {"day": 20366},
            format="json",
        ).data

        first = mia_client.get("/api/changes?aspects=events,checklists").data
        assert _rows_by_id(first["events"])[str(event["id"])]["title"] == "Dentist"
        assert _rows_by_id(first["checklist_items"])[str(item["id"])]["text"] == "scarf"
        watermark = first["server_time"]

        for path in (f"/api/events/{event['id']}", f"/api/checklists/{checklist['id']}"):
            made = kevin_client.patch(path, {"visibility": "private"}, format="json")
            assert made.status_code == 200, made.data
            assert made.data["visibility"] == "private"

        later = mia_client.get(
            "/api/changes", {"aspects": "events,checklists", "since": watermark}
        ).data
        for table, row_id in (
            ("events", event["id"]),
            ("checklists", checklist["id"]),
            ("checklist_items", item["id"]),
            ("checklist_ticks", tick["id"]),
        ):
            rows = _rows_by_id(later[table])
            assert str(row_id) in rows, (table, later[table])
            _assert_redacted(rows[str(row_id)], row_id)
            assert rows[str(row_id)]["updated_at"] >= watermark

        listed = mia_client.get("/api/events", {"since": watermark}).data["results"]
        _assert_redacted(_rows_by_id(listed)[str(event["id"])], event["id"])
        listed = mia_client.get("/api/checklists/", {"since": watermark}).data["results"]
        _assert_redacted(_rows_by_id(listed)[str(checklist["id"])], checklist["id"])
    finally:
        Event.objects.all().delete()


# =============================================================================
# Who may change visibility
# =============================================================================


def test_only_the_creator_may_make_a_shared_row_private(kevin_client, mia_client, mia):
    event = mia_client.post("/api/events", {"title": "Mia's dentist"}, format="json").data
    checklist = mia_client.post("/api/checklists/", {"name": "Mia's list"}, format="json").data
    for path in (f"/api/events/{event['id']}", f"/api/checklists/{checklist['id']}"):
        refused = kevin_client.patch(
            path, {"visibility": "private", "title": "x", "name": "x"}, format="json"
        )
        assert refused.status_code == 403, refused.data
        assert refused.data["detail"] == MAKE_PRIVATE_REFUSAL
    # Nothing in the refused PATCH landed, the title included.
    assert Event.objects.get(pk=event["id"]).title == "Mia's dentist"
    assert Event.objects.get(pk=event["id"]).owner_user_id is None
    assert Checklist.objects.get(pk=checklist["id"]).name == "Mia's list"

    for path in (f"/api/events/{event['id']}", f"/api/checklists/{checklist['id']}"):
        made = mia_client.patch(path, {"visibility": "private"}, format="json")
        assert made.status_code == 200
        assert made.data["visibility"] == "private"
    assert Event.objects.get(pk=event["id"]).owner_user_id == mia.pk


def test_anyone_may_make_a_row_with_no_recorded_creator_private(kevin_client, kevin):
    event = kevin_client.post("/api/events", {"title": "legacy"}, format="json").data
    Event.objects.filter(pk=event["id"]).update(created_by=None)
    made = kevin_client.patch(
        f"/api/events/{event['id']}", {"visibility": "private"}, format="json"
    )
    assert made.status_code == 200
    assert Event.objects.get(pk=event["id"]).owner_user_id == kevin.pk


def test_the_owner_may_share_a_private_row(kevin_client, mia_client):
    ids = _private_world(kevin_client)
    for path in (f"/api/events/{ids['event']}", f"/api/checklists/{ids['checklist']}"):
        shared = kevin_client.patch(path, {"visibility": "shared"}, format="json")
        assert shared.status_code == 200
        assert shared.data["visibility"] == "shared"
    assert mia_client.get(f"/api/checklists/{ids['checklist']}").status_code == 200
    items = mia_client.get(f"/api/checklists/{ids['checklist']}/items").data["results"]
    assert [row["text"] for row in items] == ["secret-item"]


def test_asking_for_what_a_row_already_is_is_never_refused(kevin_client, mia_client):
    event = mia_client.post("/api/events", {"title": "shared"}, format="json").data
    same = kevin_client.patch(f"/api/events/{event['id']}", {"visibility": "shared"}, format="json")
    assert same.status_code == 200


def test_the_database_refuses_an_owner_from_outside_the_household(kevin_client, user_b):
    event = kevin_client.post("/api/events", {"title": "x"}, format="json").data
    checklist = kevin_client.post("/api/checklists/", {"name": "x"}, format="json").data
    with pytest.raises(IntegrityError), transaction.atomic():
        Event.objects.filter(pk=event["id"]).update(owner_user=user_b)
    with pytest.raises(IntegrityError), transaction.atomic():
        Checklist.objects.filter(pk=checklist["id"]).update(owner_user=user_b)


# =============================================================================
# Removing a member
# =============================================================================


def test_removing_a_member_tombstones_their_private_rows_and_keeps_them_private(
    kevin_client, mia_client, mia
):
    ids = _private_world(mia_client, tag="mia")
    shared = mia_client.post("/api/events", {"title": "mia-shared"}, format="json").data

    removed = kevin_client.delete(f"/api/households/me/members/{mia.pk}")
    assert removed.status_code == 204, removed.data

    event = Event.objects.get(pk=ids["event"])
    assert event.deleted_at is not None
    assert event.owner_user_id == mia.pk
    checklist = Checklist.objects.get(pk=ids["checklist"])
    assert checklist.deleted_at is not None
    assert checklist.owner_user_id == mia.pk
    still = Event.objects.get(pk=shared["id"])
    assert (still.deleted_at, still.owner_user_id) == (None, None)

    feed = kevin_client.get("/api/changes?aspects=events,checklists").data
    _assert_redacted(_rows_by_id(feed["events"])[ids["event"]], ids["event"])
    _assert_redacted(_rows_by_id(feed["checklists"])[ids["checklist"]], ids["checklist"])
    assert "mia-event" not in str(feed) and "mia-list" not in str(feed)
    assert _rows_by_id(feed["events"])[str(shared["id"])]["title"] == "mia-shared"
    # The other member's pulls, through both list routes, also see only redacted rows.
    for path, key in (("/api/events", "event"), ("/api/checklists/", "checklist")):
        listed = _rows_by_id(kevin_client.get(path, {"since": EPOCH}).data["results"])
        _assert_redacted(listed[ids[key]], ids[key])
    for table in ("checklist_items", "checklist_ticks"):
        for row in feed[table]:
            assert set(row) == {"id", "deleted_at", "updated_at", "redacted"}, (table, row)


# =============================================================================
# A user who owns private rows cannot be hard-deleted (spec D3, corrected)
# =============================================================================


def _refused_delete(user):
    from django.db.models import ProtectedError, RestrictedError

    with pytest.raises((IntegrityError, ProtectedError, RestrictedError)), transaction.atomic():
        user.delete()


def test_a_user_who_owns_a_private_event_cannot_be_hard_deleted(mia_client, mia):
    """SET NULL would have turned the event shared, title and all, in the
    statement that deleted its owner. RESTRICT refuses instead, in Django and
    again in SQL."""
    event = mia_client.post(
        "/api/events", {"title": "mia-secret", "visibility": "private"}, format="json"
    ).data
    _refused_delete(mia)
    with pytest.raises(IntegrityError), transaction.atomic():
        from django.db import connection

        with connection.cursor() as cursor:
            cursor.execute("delete from household_user where id = %s", [mia.pk])
    assert Event.objects.get(pk=event["id"]).owner_user_id == mia.pk


def test_a_user_who_owns_a_private_checklist_cannot_be_hard_deleted(mia_client, mia):
    checklist = mia_client.post(
        "/api/checklists/", {"name": "mia-secret", "visibility": "private"}, format="json"
    ).data
    _refused_delete(mia)
    assert Checklist.objects.get(pk=checklist["id"]).owner_user_id == mia.pk


def test_a_removed_member_still_cannot_be_hard_deleted_while_their_tombstones_are_private(
    kevin_client, mia_client, mia
):
    ids = _private_world(mia_client, tag="mia")
    assert kevin_client.delete(f"/api/households/me/members/{mia.pk}").status_code == 204
    _refused_delete(mia)
    assert Event.objects.get(pk=ids["event"]).owner_user_id == mia.pk


def test_a_user_who_only_created_shared_rows_can_be_deleted_and_attribution_goes(
    mia_client, mia
):
    """`created_by_id` is attribution only, so it stays ON DELETE SET NULL."""
    event = mia_client.post("/api/events", {"title": "mia-shared"}, format="json").data
    checklist = mia_client.post("/api/checklists/", {"name": "mia-shared"}, format="json").data
    item = mia_client.post(
        f"/api/checklists/{checklist['id']}/items", {"text": "eggs"}, format="json"
    ).data
    mia.delete()
    assert Event.objects.get(pk=event["id"]).created_by_id is None
    assert Checklist.objects.get(pk=checklist["id"]).created_by_id is None
    assert ChecklistItem.objects.get(pk=item["id"]).created_by_id is None


# =============================================================================
# The MCP tools
# =============================================================================


def test_every_mcp_tool_respects_member_privacy(settings, kevin_client, kevin, mia):
    from tests.test_engine_mcp import call, client_with

    settings.LEGION_MCP = True
    ids = _private_world(kevin_client)
    mcp_mia = client_with(mia)
    mcp_kevin = client_with(kevin)
    secret_marks = ("secret-event", "secret-list", "secret-item")
    covered = set()

    def mia_reads(name, arguments):
        covered.add(name)
        is_error, text, _ = call(mcp_mia, name, arguments)
        assert not is_error, (name, text)
        assert not [mark for mark in secret_marks if mark in text], (name, text)
        assert not SERVER_ONLY & set(text.split('"')), (name, text)

    window = {"from": "2026-10-01", "to": "2026-10-10"}
    mia_reads("list_events", window)
    mia_reads("list_checklists", {"date": "2026-10-05"})
    # The control: the owner's own tools see them.
    assert "secret-event" in call(mcp_kevin, "list_events", window)[1]
    assert "secret-item" in call(mcp_kevin, "list_checklists", {"date": "2026-10-05"})[1]

    def mia_refused(name, arguments):
        covered.add(name)
        is_error, text, _ = call(mcp_mia, name, arguments)
        assert is_error, (name, text)
        assert text.startswith("Nothing was"), (name, text)

    mia_refused("update_event", {"id": ids["event"], "fields": {"title": "hijacked"}})
    mia_refused("delete_event", {"id": ids["event"]})
    mia_refused("add_checklist_item", {"checklist_id": ids["checklist"], "text": "x"})
    mia_refused(
        "tick_checklist_item",
        {"checklist_id": ids["checklist"], "item_id": ids["item"], "date": "2026-10-07"},
    )
    covered.add("add_event")
    is_error, text, structured = call(mcp_mia, "add_event", {"fields": {"title": "via mcp"}})
    assert not is_error, text
    assert Event.objects.get(title="via mcp").owner_user_id is None

    assert Event.objects.get(pk=ids["event"]).title == "secret-event"
    assert Event.objects.get(pk=ids["event"]).deleted_at is None
    assert ChecklistItem.objects.filter(checklist_id=ids["checklist"]).count() == 1
    # Every tool that touches the five tables was driven here; the rest
    # (`list_tables`, `read_records`, `write_record`, `delete_record`) read
    # the synced registry, which holds none of them.
    touching = {name for name in TOOLS_BY_NAME if "event" in name or "checklist" in name}
    assert touching == covered, sorted(touching ^ covered)


# =============================================================================
# manage.py make_private
# =============================================================================


def _make_private(*args) -> str:
    out = io.StringIO()
    call_command("make_private", *args, stdout=out)
    return out.getvalue()


def test_make_private_counts_on_a_dry_run_changes_once_then_changes_nothing(
    kevin_client, household_a, kevin, mia_client
):
    for aid in (1, 2, 3):
        kevin_client.post(
            "/api/events", {"title": f"HW {aid}", "origin_guid": f"canvas:{aid}"}, format="json"
        )
    kevin_client.post(
        "/api/events",
        {"title": "COSC 4320", "structured_meta": {"course": "COSC4320"}},
        format="json",
    )
    kevin_client.post("/api/events", {"title": "Dinner", "origin_guid": "dinner"}, format="json")
    base = ["--household", str(household_a.id), "--user-email", kevin.email]

    dry = _make_private(*base, "--origin-prefix", "canvas:", "--dry-run")
    assert "Dry run, nothing was changed. 3 shared" in dry
    assert not Event.objects.exclude(owner_user__isnull=True).exists()

    real = _make_private(*base, "--origin-prefix", "canvas:")
    assert "Made 3 shared" in real
    assert Event.objects.filter(owner_user=kevin).count() == 3

    again = _make_private(*base, "--origin-prefix", "canvas:")
    assert "Made 0 shared" in again
    assert "3 were already private" in again

    course = _make_private(*base, "--structured-meta-key", "course")
    assert "Made 1 shared" in course
    assert Event.objects.get(title="COSC 4320").owner_user_id == kevin.pk
    assert Event.objects.get(title="Dinner").owner_user_id is None

    titles = {
        row.get("title") for row in mia_client.get(f"/api/events?since={EPOCH}").data["results"]
    }
    assert titles == {None, "Dinner"}


def test_make_private_by_title_prefix_reaches_rows_with_no_metadata(
    kevin_client, household_a, kevin
):
    for title in ("COSC 4320 Software Engineering", "COSC 3318 Python Programming"):
        kevin_client.post("/api/events", {"title": title}, format="json")
    kevin_client.post("/api/events", {"title": "cosc lowercase is not a class"}, format="json")
    kevin_client.post("/api/events", {"title": "Dentist"}, format="json")
    base = ["--household", str(household_a.id), "--user-email", kevin.email]

    dry = _make_private(*base, "--title-prefix", "COSC ", "--dry-run")
    assert "Dry run, nothing was changed. 2 shared events whose title starts with 'COSC '" in dry
    assert "Made 2 shared" in _make_private(*base, "--title-prefix", "COSC ")
    assert set(Event.objects.filter(owner_user=kevin).values_list("title", flat=True)) == {
        "COSC 4320 Software Engineering",
        "COSC 3318 Python Programming",
    }
    assert "Made 0 shared" in _make_private(*base, "--title-prefix", "COSC ")


def test_make_private_refuses_a_non_member_in_words(household_a, user_b):
    with pytest.raises(CommandError, match="not a member"):
        _make_private(
            "--household",
            str(household_a.id),
            "--user-email",
            user_b.email,
            "--origin-prefix",
            "canvas:",
        )
