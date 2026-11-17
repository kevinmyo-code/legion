"""User-aspect records on the engine (engine-brain ticket 15): the REST doors, the backfill's batch
door, and the eight agent tools that twin the phone's aspect-engine meta-tools.

Every row is invented. Tenancy is in `test_tenancy.py`, sharing in `test_visibility.py`; this file
is behaviour.
"""

from __future__ import annotations

import json
from datetime import UTC, datetime

import pytest

from agents.evals.runner import member_request
from agents.legion.specs import spec as load_spec
from agents.legion.twins import TWINS
from homes import records_tools as rt
from homes.models import UserAspect, UserFieldDef, UserRecord, UserRecordType
from homes.views import BATCH_MAX

pytestmark = pytest.mark.django_db

AT = "2026-03-01T10:00:00Z"
CLOCKS = {"created_at_client": AT, "updated_at_client": AT}


def _put(client, prefix, guid, body):
    out = client.put(f"/api/records/{prefix}/{guid}/", {**CLOCKS, **body}, format="json")
    assert out.status_code == 200, out.data
    return out.data


def _tracker(client):
    """A "Hikes" tracker over REST, as the phone's backfill sends it (guid-keyed payload)."""
    _put(client, "aspects", "a-1", {"name": "Hikes"})
    _put(client, "types", "t-1", {"aspect": "a-1", "name": "Hike", "primary_amount_field": "f-4"})
    for guid, name, kind, extra in (
        ("f-1", "trail", "TEXT", {"required": True}),
        ("f-2", "miles", "NUMBER", {}),
        ("f-3", "day", "DATE", {}),
        ("f-4", "parking", "MONEY_CENTS", {}),
        ("f-5", "mood", "CHOICE", {"config": json.dumps({"options": ["good", "tired"]})}),
    ):
        # Positions as the phone keeps them: without one, five fields made in the same instant
        # would come back in uuid order.
        position = int(guid.removeprefix("f-")) - 1
        body = {"record_type": "t-1", "name": name, "type": kind, "position": position, **extra}
        _put(client, "fields", guid, body)


# -- REST ----------------------------------------------------------------------------------------


def test_a_child_names_its_parent_by_guid_and_put_is_an_upsert(auth_client):
    _tracker(auth_client)
    entry = _put(
        auth_client, "entries", "e-1", {"record_type": "t-1", "payload": {"f-1": "Test ridge"}}
    )
    assert entry["record_type"] == "t-1" and entry["payload"] == {"f-1": "Test ridge"}
    again = _put(
        auth_client, "entries", "e-1", {"record_type": "t-1", "payload": {"f-1": "Test ridge 2"}}
    )
    assert again["id"] == entry["id"] and UserRecord.objects.count() == 1
    renamed = _put(auth_client, "aspects", "a-1", {"name": "Walks"})
    assert renamed["origin_guid"] == "a-1" and UserAspect.objects.get().name == "Walks"


def test_a_child_whose_parent_is_not_on_the_engine_is_refused_in_words(auth_client):
    out = auth_client.put(
        "/api/records/types/t-9/", {**CLOCKS, "aspect": "nope", "name": "x"}, format="json"
    )
    assert out.status_code == 400
    assert "send the parent first" in json.dumps(out.data)
    assert not UserRecordType.objects.exists()


def test_bad_provenance_or_payload_is_refused_never_dropped(auth_client):
    _tracker(auth_client)
    base = {**CLOCKS, "record_type": "t-1"}
    bad = auth_client.put(
        "/api/records/entries/e-2/", {**base, "payload": {}, "provenance": "GUESS"}, format="json"
    )
    assert bad.status_code == 400 and "provenance" in bad.data
    listed = auth_client.put("/api/records/entries/e-3/", {**base, "payload": [1]}, format="json")
    assert listed.status_code == 400
    unknown = auth_client.put(
        "/api/records/entries/e-4/", {**base, "payload": {}, "colour": "red"}, format="json"
    )
    assert unknown.status_code == 400 and "colour" in json.dumps(unknown.data)
    assert not UserRecord.objects.exists()


def test_a_delete_is_a_tombstone_the_since_feed_carries(auth_client):
    _tracker(auth_client)
    before = auth_client.get("/api/records/fields/").data["results"]
    cursor = max(r["updated_at"] for r in before)
    assert auth_client.delete("/api/records/fields/f-5/").status_code == 204
    assert auth_client.delete("/api/records/fields/f-5/").status_code == 204
    changed = auth_client.get(f"/api/records/fields/?since={cursor}").data["results"]
    gone = [r for r in changed if r["origin_guid"] == "f-5"]
    assert gone and gone[0]["deleted_at"] is not None
    assert len(auth_client.get("/api/records/fields/?active=1").data["results"]) == 4


def test_trash_is_a_value_that_can_be_unset(auth_client):
    _tracker(auth_client)
    body = {"record_type": "t-1", "payload": {"f-1": "Test loop"}}
    _put(auth_client, "entries", "e-1", {**body, "trashed_at": "2026-03-02T00:00:00Z"})
    assert UserRecord.objects.get().trashed_at is not None
    _put(auth_client, "entries", "e-1", {**body, "trashed_at": None})
    assert UserRecord.objects.get().trashed_at is None


def test_the_routes_need_a_member(client):
    for prefix in ("aspects", "types", "fields", "entries"):
        assert client.get(f"/api/records/{prefix}/").status_code in (401, 403)
        assert client.post(f"/api/records/{prefix}/batch/", {"rows": []}).status_code in (401, 403)


# -- the backfill's batch door -------------------------------------------------------------------


def _rows(n):
    return [
        {**CLOCKS, "origin_guid": f"e-{i}", "record_type": "t-1", "payload": {"f-1": f"Trail {i}"}}
        for i in range(n)
    ]


def test_batch_is_idempotent_and_says_what_it_moved(auth_client):
    _tracker(auth_client)
    first = auth_client.post("/api/records/entries/batch/", {"rows": _rows(3)}, format="json")
    assert first.status_code == 200
    assert (first.data["created"], first.data["updated"], first.data["unchanged"]) == (3, 0, 0)
    assert first.data["engine_count"] == 3 and first.data["failed"] == []
    again = auth_client.post("/api/records/entries/batch/", {"rows": _rows(3)}, format="json")
    assert (again.data["created"], again.data["unchanged"]) == (0, 3)
    assert UserRecord.objects.count() == 3


def test_a_batch_that_stopped_halfway_resumes_without_doubling(auth_client):
    _tracker(auth_client)
    auth_client.post("/api/records/entries/batch/", {"rows": _rows(2)}, format="json")
    rest = auth_client.post("/api/records/entries/batch/", {"rows": _rows(5)}, format="json")
    assert (rest.data["created"], rest.data["unchanged"]) == (3, 2)
    assert UserRecord.objects.count() == 5


def test_a_batch_names_a_refused_row_and_the_rest_land(auth_client):
    _tracker(auth_client)
    rows = _rows(2) + [{**CLOCKS, "origin_guid": "orphan", "record_type": "t-x", "payload": {}}]
    out = auth_client.post("/api/records/entries/batch/", {"rows": rows}, format="json")
    assert out.data["created"] == 2
    assert [f["origin_guid"] for f in out.data["failed"]] == ["orphan"]
    assert "record_type" in out.data["failed"][0]["detail"]


def test_a_batch_updates_a_changed_child_and_its_parent_link(auth_client):
    _tracker(auth_client)
    _put(auth_client, "types", "t-2", {"aspect": "a-1", "name": "Climb"})
    auth_client.post("/api/records/entries/batch/", {"rows": _rows(1)}, format="json")
    moved = [{**_rows(1)[0], "record_type": "t-2"}]
    out = auth_client.post("/api/records/entries/batch/", {"rows": moved}, format="json")
    assert out.data["updated"] == 1
    assert UserRecord.objects.get().record_type.origin_guid == "t-2"


def test_a_batch_over_the_limit_writes_nothing(auth_client):
    _tracker(auth_client)
    big = auth_client.post(
        "/api/records/entries/batch/", {"rows": _rows(BATCH_MAX + 1)}, format="json"
    )
    assert big.status_code == 400 and not UserRecord.objects.exists()


def test_the_batch_route_is_not_taken_as_an_identity(auth_client):
    assert auth_client.put("/api/records/aspects/batch/", {}, format="json").status_code == 405


# -- the agent tools -----------------------------------------------------------------------------


@pytest.fixture
def req(household_user):
    return member_request(household_user)


@pytest.fixture
def hikes(auth_client):
    _tracker(auth_client)
    return UserRecordType.objects.get(origin_guid="t-1")


def _create(req, **fields):
    return rt.CREATE_ASPECT_RECORD.handler(
        req, {"aspect_name": "hikes", "record_type_name": "HIKE", "fields": fields}
    )


def test_the_tools_are_twins_classified_and_in_the_generalist():
    spec = load_spec("generalist")
    for tool in rt.RECORD_TOOLS:
        assert TWINS[tool.name] is tool
        assert tool.name in spec.tools
    writes = {t.name for t in rt.RECORD_TOOLS if t.writes}
    assert writes == {
        "create_aspect_record",
        "update_aspect_record",
        "delete_aspect_record",
        "create_aspect",
        "update_aspect",
    }
    # The registry's generic tools keep their own names; the twins never shadow one.
    from engine_mcp.tools import TOOLS_BY_NAME

    assert not {t.name for t in rt.RECORD_TOOLS} & set(TOOLS_BY_NAME)


def test_list_and_describe_say_an_empty_result_is_real_then_read_the_shape(req, hikes):
    UserAspect.objects.update(archived=True)
    assert "real empty result" in rt.LIST_ASPECTS.handler(req, {}).text
    UserAspect.objects.update(archived=False)
    listed = rt.LIST_ASPECTS.handler(req, {}).structured
    fields = ["trail", "miles", "day", "parking", "mood"]
    assert listed == {
        "aspects": [{"name": "Hikes", "record_types": [{"name": "Hike", "fields": fields}]}]
    }
    shape = rt.DESCRIBE_ASPECT.handler(req, {"aspect_name": "HIKES"}).structured
    fields = shape["record_types"][0]["fields"]
    assert [f["name"] for f in fields] == ["trail", "miles", "day", "parking", "mood"]
    assert fields[4]["options"] == ["good", "tired"] and fields[0]["required"] is True
    assert rt.DESCRIBE_ASPECT.handler(req, {"aspect_name": "Nope"}).is_error


def test_create_writes_the_phones_shapes_and_promotes(req, hikes, household_a):
    out = _create(req, trail="Test ridge", miles=4.5, day="2026-03-04", parking=500, mood="Good")
    assert not out.is_error, out.text
    row = UserRecord.objects.get()
    assert row.provenance == "USER" and row.record_type == hikes
    assert row.payload["f-1"] == "Test ridge" and row.payload["f-2"] == 4.5
    assert row.payload["f-4"] == 500 and row.payload["f-5"] == "good"
    # A DATE is epoch ms of local midnight on the household's clock, as the phone stores it.
    zone = rt._zone(req)
    assert (
        datetime.fromtimestamp(row.payload["f-3"] / 1000, tz=zone)
        .isoformat()
        .startswith("2026-03-04T00:00:00")
    )
    assert row.amount_cents == 500 and "Test ridge" in row.search_text and "good" in row.search_text
    assert out.structured["record"]["fields"]["day"] == "2026-03-04"


@pytest.mark.parametrize(
    "fields,words",
    [
        ({"miles": 3}, "required"),
        ({"trail": "x", "colour": "red"}, "colour"),
        ({"trail": "x", "parking": 4.25}, "whole cents"),
        ({"trail": "x", "mood": "ecstatic"}, "good, tired"),
        ({"trail": "x", "day": "someday"}, "YYYY-MM-DD"),
    ],
)
def test_create_refuses_in_words_and_writes_nothing(req, hikes, fields, words):
    out = _create(req, **fields)
    assert out.is_error and words in out.text and "Nothing was written" in out.text
    assert not UserRecord.objects.exists()


def test_query_filters_searches_and_gives_record_ids(req, hikes):
    _create(req, trail="Test ridge", mood="good")
    _create(req, trail="Test creek", mood="tired")
    base = {"aspect_name": "Hikes", "record_type_name": "Hike"}
    tired = rt.QUERY_ASPECT_RECORDS.handler(req, {**base, "filters": {"mood": "tired"}})
    assert tired.structured["count"] == 1
    assert tired.structured["records"][0]["fields"]["trail"] == "Test creek"
    found = rt.QUERY_ASPECT_RECORDS.handler(req, {**base, "search_text": "RIDGE"}).structured
    assert found["count"] == 1 and found["records"][0]["record_id"]
    assert rt.QUERY_ASPECT_RECORDS.handler(req, {**base, "filters": {"nope": 1}}).is_error
    none = rt.QUERY_ASPECT_RECORDS.handler(req, {**base, "search_text": "zzz"})
    assert "real empty result" in none.text


def test_update_changes_only_the_named_fields(req, hikes):
    _create(req, trail="Test ridge", miles=3, parking=200)
    guid = UserRecord.objects.get().origin_guid
    out = rt.UPDATE_ASPECT_RECORD.handler(req, {"record_id": guid, "fields": {"miles": 5}})
    assert not out.is_error, out.text
    row = UserRecord.objects.get()
    assert row.payload["f-2"] == 5.0 and row.payload["f-1"] == "Test ridge"
    assert row.amount_cents == 200
    cleared = rt.UPDATE_ASPECT_RECORD.handler(req, {"record_id": guid, "fields": {"trail": None}})
    assert cleared.is_error and "cannot be emptied" in cleared.text
    assert rt.UPDATE_ASPECT_RECORD.handler(req, {"record_id": "nope", "fields": {}}).is_error


def test_delete_one_trashes_at_once_and_a_set_asks_first(req, hikes):
    for trail in ("Test a", "Test b", "Test c"):
        _create(req, trail=trail, mood="tired")
    one = UserRecord.objects.filter(payload__contains={"f-1": "Test a"}).get()
    out = rt.DELETE_ASPECT_RECORD.handler(req, {"record_id": one.origin_guid})
    assert not out.is_error and "Test a" in out.text and "30 days" in out.text
    one.refresh_from_db()
    assert one.trashed_at is not None and one.deleted_at is None
    bulk = {"aspect_name": "Hikes", "record_type_name": "Hike", "filters": {"mood": "tired"}}
    asked = rt.DELETE_ASPECT_RECORD.handler(req, bulk)
    assert asked.structured["committed"] is False and asked.structured["matched"] == 2
    assert UserRecord.objects.filter(trashed_at__isnull=True).count() == 2
    done = rt.DELETE_ASPECT_RECORD.handler(req, {**bulk, "confirm": True})
    assert done.structured == {"committed": True, "trashed": 2}
    assert not UserRecord.objects.filter(trashed_at__isnull=True).exists()


@pytest.mark.parametrize("policy", ["BLOCK", "CASCADE", "NULLIFY"])
def test_delete_follows_the_reference_policy(req, hikes, auth_client, policy):
    """`RecordStore.delete`'s rule: a BLOCK referrer stops everything, CASCADE trashes the
    referrer too, NULLIFY clears the reference."""
    _put(auth_client, "types", "t-photo", {"aspect": "a-1", "name": "Photo"})
    config = json.dumps({"targetRecordType": "t-1", "deletePolicy": policy})
    _put(
        auth_client,
        "fields",
        "f-ref",
        {"record_type": "t-photo", "name": "hike", "type": "REFERENCE", "config": config},
    )
    _create(req, trail="Test ridge")
    hike = UserRecord.objects.get()
    made = rt.CREATE_ASPECT_RECORD.handler(
        req,
        {"aspect_name": "Hikes", "record_type_name": "Photo", "fields": {"hike": hike.origin_guid}},
    )
    assert not made.is_error, made.text
    photo = UserRecord.objects.exclude(pk=hike.pk).get()
    assert photo.payload["f-ref"] == hike.origin_guid
    out = rt.DELETE_ASPECT_RECORD.handler(req, {"record_id": hike.origin_guid})
    hike.refresh_from_db()
    photo.refresh_from_db()
    if policy == "BLOCK":
        assert out.is_error and "still points at it" in out.text
        assert hike.trashed_at is None and photo.trashed_at is None
    elif policy == "CASCADE":
        assert out.structured["trashed"] == 2 and photo.trashed_at is not None
    else:
        assert hike.trashed_at is not None and photo.trashed_at is None
        assert photo.payload["f-ref"] is None


def test_a_reference_must_name_a_live_entry_of_its_target(req, hikes, auth_client):
    _put(auth_client, "types", "t-photo", {"aspect": "a-1", "name": "Photo"})
    config = json.dumps({"targetRecordType": "t-1", "deletePolicy": "BLOCK"})
    _put(
        auth_client,
        "fields",
        "f-ref",
        {"record_type": "t-photo", "name": "hike", "type": "REFERENCE", "config": config},
    )
    out = rt.CREATE_ASPECT_RECORD.handler(
        req, {"aspect_name": "Hikes", "record_type_name": "Photo", "fields": {"hike": "nope"}}
    )
    assert out.is_error and "record_id" in out.text


def test_a_computed_field_is_never_typed_in(req, hikes, auth_client):
    _put(
        auth_client,
        "fields",
        "f-c",
        {"record_type": "t-1", "name": "pace", "type": "COMPUTED", "config": "{}"},
    )
    out = _create(req, trail="Test ridge", pace=3)
    assert out.is_error and "computed" in out.text


_DRAFT = {
    "aspect_name": "Reading",
    "record_types": [
        {
            "name": "Book",
            "fields": [
                {"name": "title", "type": "TEXT", "required": True},
                {"name": "pages", "type": "NUMBER"},
                {"name": "format", "type": "CHOICE", "options": ["paper", "audio"]},
            ],
        }
    ],
}


def test_create_aspect_drafts_then_commits_only_the_shape_read_back(req):
    draft = rt.CREATE_ASPECT.handler(req, _DRAFT)
    assert draft.structured["committed"] is False and "NOT created" in draft.text
    assert not UserAspect.objects.exists()
    token = draft.structured["draft_token"]
    changed = {**_DRAFT, "aspect_name": "Books", "confirm": True, "draft_token": token}
    assert rt.CREATE_ASPECT.handler(req, changed).is_error
    assert rt.CREATE_ASPECT.handler(req, {**_DRAFT, "confirm": True}).is_error
    done = rt.CREATE_ASPECT.handler(req, {**_DRAFT, "confirm": True, "draft_token": token})
    assert not done.is_error, done.text
    book = UserRecordType.objects.get(name="Book")
    fields = list(UserFieldDef.objects.filter(record_type=book).order_by("position"))
    assert [(f.name, f.type, f.position) for f in fields] == [
        ("title", "TEXT", 0),
        ("pages", "NUMBER", 1),
        ("format", "CHOICE", 2),
    ]
    assert json.loads(fields[2].config) == {"options": ["paper", "audio"]}
    again = rt.CREATE_ASPECT.handler(req, {**_DRAFT, "confirm": True, "draft_token": token})
    assert again.is_error and "already exists" in again.text
    assert UserAspect.objects.count() == 1


@pytest.mark.parametrize(
    "bad,words",
    [
        ({"aspect_name": "Fleet"}, "built-in"),
        ({"record_types": [{"name": "Book", "fields": []}]}, "at least one field"),
        (
            {"record_types": [{"name": "B", "fields": [{"name": "x", "type": "PHOTO"}]}]},
            "PHOTO",
        ),
        (
            {"record_types": [{"name": "B", "fields": [{"name": "x", "type": "CHOICE"}]}]},
            "options",
        ),
    ],
)
def test_create_aspect_refuses_a_bad_draft(req, bad, words):
    out = rt.CREATE_ASPECT.handler(req, {**_DRAFT, **bad})
    assert out.is_error and words in out.text
    assert not UserAspect.objects.exists()


def test_update_aspect_adds_a_field_a_type_or_renames_after_a_yes(req, hikes):
    add = {
        "aspect_name": "Hikes",
        "change": "add_field",
        "target_record_type": "Hike",
        "field": {"name": "notes", "type": "TEXT"},
    }
    draft = rt.UPDATE_ASPECT.handler(req, add)
    assert "NOT applied" in draft.text
    assert not UserFieldDef.objects.filter(name="notes").exists()
    done = rt.UPDATE_ASPECT.handler(
        req, {**add, "confirm": True, "draft_token": draft.structured["draft_token"]}
    )
    assert not done.is_error, done.text
    assert UserFieldDef.objects.get(name="notes").position == 5
    rename = {"aspect_name": "Hikes", "change": "rename", "new_name": "Walks"}
    token = rt.UPDATE_ASPECT.handler(req, rename).structured["draft_token"]
    rt.UPDATE_ASPECT.handler(req, {**rename, "confirm": True, "draft_token": token})
    assert UserAspect.objects.get().name == "Walks"
    assert UserAspect.objects.get().origin_guid == "a-1"
    kind = {
        "aspect_name": "Walks",
        "change": "add_record_type",
        "record_type": {"name": "Gear", "fields": [{"name": "item", "type": "TEXT"}]},
    }
    token = rt.UPDATE_ASPECT.handler(req, kind).structured["draft_token"]
    rt.UPDATE_ASPECT.handler(req, {**kind, "confirm": True, "draft_token": token})
    assert UserRecordType.objects.filter(aspect__name="Walks").count() == 2


def test_the_tools_never_reach_another_households_rows(hikes, req, user_b):
    _create(req, trail="Alpha secret trail")
    guid = UserRecord.objects.get().origin_guid
    theirs = member_request(user_b)
    assert "real empty result" in rt.LIST_ASPECTS.handler(theirs, {}).text
    query = {"aspect_name": "Hikes", "record_type_name": "Hike"}
    assert rt.QUERY_ASPECT_RECORDS.handler(theirs, query).is_error
    assert rt.UPDATE_ASPECT_RECORD.handler(
        theirs, {"record_id": guid, "fields": {"trail": "x"}}
    ).is_error
    assert rt.DELETE_ASPECT_RECORD.handler(theirs, {"record_id": guid}).is_error
    assert rt.CREATE_ASPECT_RECORD.handler(theirs, {**query, "fields": {"trail": "x"}}).is_error
    row = UserRecord.objects.get()
    assert row.payload["f-1"] == "Alpha secret trail" and row.trashed_at is None


def test_a_write_tool_refuses_in_a_tainted_turn(req, hikes):
    """ADR 0090 through the agents' door: a turn that read third-party text writes nothing."""
    from assistant import read_through
    from engine_mcp.tools import run_engine_tool

    read_through.before_tool(req, "web_search")
    out = run_engine_tool(
        req,
        rt.CREATE_ASPECT_RECORD,
        {"aspect_name": "Hikes", "record_type_name": "Hike", "fields": {"trail": "x"}},
    )
    assert out.is_error
    assert not UserRecord.objects.exists()
    token = rt.CREATE_ASPECT.handler(req, _DRAFT).structured["draft_token"]
    refused = run_engine_tool(
        req, rt.CREATE_ASPECT, {**_DRAFT, "confirm": True, "draft_token": token}
    )
    assert refused.is_error and not UserAspect.objects.filter(name="Reading").exists()


def test_trashed_entries_are_not_read_and_dates_render_on_the_household_clock(req, hikes):
    _create(req, trail="Test gone", day="2026-03-09")
    UserRecord.objects.update(trashed_at=datetime(2026, 3, 10, tzinfo=UTC))
    query = {"aspect_name": "Hikes", "record_type_name": "Hike"}
    assert rt.QUERY_ASPECT_RECORDS.handler(req, query).structured["count"] == 0


def test_a_recruiter_gets_none_of_the_records_tools(household_a):
    """Default deny (ADR 0068): no records twin is on the recruiter list, so the gateway's guard
    refuses each one in words before it runs."""
    from assistant.surface import refusal_for
    from household.models import HouseholdMember, User

    visitor = User.objects.create_user(email="visitor-records@example.com", password=None)
    HouseholdMember.objects.create(
        user=visitor, household=household_a, role=HouseholdMember.RECRUITER
    )
    for tool in rt.RECORD_TOOLS:
        assert refusal_for(tool.name, visitor, {}), tool.name


def test_the_batch_takes_exactly_what_the_phone_sends(auth_client):
    """The key sets `HomesRecords.kt` emits, explicit nulls included, for all four tables."""
    clocks = {"created_at_client": AT, "updated_at_client": AT}
    sent = (
        (
            "aspects",
            {
                "origin_guid": "pa",
                "name": "Test tracker",
                "icon": "",
                "color": "",
                "position": 0,
                "archived": False,
                "archived_at": None,
                **clocks,
            },
        ),
        (
            "types",
            {
                "origin_guid": "pt",
                "aspect": "pa",
                "name": "Entry",
                "primary_amount_field": None,
                "primary_due_date_field": None,
                **clocks,
            },
        ),
        (
            "fields",
            {
                "origin_guid": "pf",
                "record_type": "pt",
                "name": "what",
                "type": "TEXT",
                "required": False,
                "position": 0,
                "config": None,
                **clocks,
            },
        ),
        (
            "entries",
            {
                "origin_guid": "pe",
                "record_type": "pt",
                "due_at": None,
                "amount_cents": None,
                "search_text": "",
                "provenance": "USER",
                "payload": {"pf": None, "phone_field:9": "kept"},
                "trashed_at": None,
                **clocks,
            },
        ),
    )
    for prefix, row in sent:
        out = auth_client.post(f"/api/records/{prefix}/batch/", {"rows": [row]}, format="json")
        assert out.status_code == 200 and out.data["failed"] == [], (prefix, out.data)
        assert out.data["created"] == 1
        again = auth_client.post(f"/api/records/{prefix}/batch/", {"rows": [row]}, format="json")
        assert again.data["unchanged"] == 1, (prefix, again.data)
    assert UserRecord.objects.get().payload == {"pf": None, "phone_field:9": "kept"}


# -- fewer hops: forgiving names, a catalogue on a miss, one entry by a search phrase ------------


def test_names_are_forgiving_and_one_record_type_needs_no_name(req, hikes):
    _create(req, trail="Test ridge")
    for aspect, kind in (
        ("hikes", "Hike"),
        ("my hikes tracker", ""),
        ("Hike", "hikes"),
        ("Hkes", ""),
    ):
        out = rt.QUERY_ASPECT_RECORDS.handler(
            req, {"aspect_name": aspect, "record_type_name": kind}
        )
        assert not out.is_error and out.structured["count"] == 1, (aspect, kind, out.text)


def test_a_miss_names_his_trackers_types_and_fields(req, hikes):
    out = rt.QUERY_ASPECT_RECORDS.handler(req, {"aspect_name": "Gardening"})
    assert out.is_error
    assert "Hikes > Hike (trail, miles, day, parking, mood)" in out.text


def test_one_entry_is_changed_or_trashed_by_a_search_phrase(req, hikes):
    _create(req, trail="Test ridge", mood="good")
    _create(req, trail="Test creek", mood="tired")
    changed = rt.UPDATE_ASPECT_RECORD.handler(
        req, {"aspect_name": "hikes", "search_text": "the ridge hike", "fields": {"miles": 7}}
    )
    assert not changed.is_error, changed.text
    ridge = UserRecord.objects.get(payload__contains={"f-1": "Test ridge"})
    assert ridge.payload["f-2"] == 7.0
    both = rt.UPDATE_ASPECT_RECORD.handler(
        req, {"aspect_name": "hikes", "search_text": "test", "fields": {"miles": 1}}
    )
    assert both.is_error and "2 Hike entries match" in both.text
    gone = rt.DELETE_ASPECT_RECORD.handler(req, {"aspect_name": "hikes", "search_text": "creek"})
    assert not gone.is_error and "Test creek" in gone.text
    assert UserRecord.objects.get(payload__contains={"f-1": "Test creek"}).trashed_at is not None
    # Several matches never trash at once: they are counted first, as a set.
    _create(req, trail="Test creek two")
    _create(req, trail="Test creek three")
    asked = rt.DELETE_ASPECT_RECORD.handler(req, {"aspect_name": "hikes", "search_text": "creek"})
    assert asked.structured["committed"] is False and asked.structured["matched"] == 2
