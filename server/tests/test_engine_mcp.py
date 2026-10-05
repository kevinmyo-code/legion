"""engine-mcp ticket 10: `/mcp`, its tool registry, and the honesty contract.

The tenancy half - two households, every tool, no row of one in the other's
results - lives in `tests/test_tenancy.py` beside every other leak test, as
the ticket asks. This module holds the rest: the switch, auth and scope, the
gate and the memory exclusion, the words on every failure, the audit row and
the throttle. The helpers at the top are imported by that module too.
"""

from __future__ import annotations

import json
import re

import mcp_types
import pytest
from rest_framework.test import APIClient

from engine_mcp.models import McpCall, Outcome
from engine_mcp.tools import (
    DELETABLE,
    EXCLUDED_TABLES,
    GATED_TABLES,
    READABLE,
    TOOLS,
    TOOLS_BY_NAME,
    WRITABLE,
    _say,
)
from engine_mcp.views import McpTokenThrottle
from household.models import DeviceToken
from tests.test_ingest_api import a_receipt

pytestmark = pytest.mark.django_db

MODERN = "2026-07-28"


# =============================================================================
# Helpers
# =============================================================================


@pytest.fixture
def mcp_on(settings):
    settings.LEGION_MCP = True
    return settings


def client_with(user, scope=DeviceToken.SCOPE_WRITE, name="Claude Code"):
    _token, raw_key = DeviceToken.issue(user, name, scope=scope)
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw_key}")
    return client


def rpc(client, method: str, params: dict | None = None, *, modern: bool = True):
    """POST one JSON-RPC request to `/mcp`. Modern requests carry the
    2026-07-28 envelope (headers plus `_meta`); legacy ones carry neither,
    the way a 2025-11-25 client sends them."""
    params = dict(params or {})
    headers = {"HTTP_ACCEPT": "application/json, text/event-stream"}
    if modern:
        params["_meta"] = {
            mcp_types.PROTOCOL_VERSION_META_KEY: MODERN,
            mcp_types.CLIENT_CAPABILITIES_META_KEY: {},
        }
        headers["HTTP_MCP_PROTOCOL_VERSION"] = MODERN
        headers["HTTP_MCP_METHOD"] = method
        if method == "tools/call":
            headers["HTTP_MCP_NAME"] = params["name"]
    body = {"jsonrpc": "2.0", "id": 1, "method": method, "params": params}
    return client.post("/mcp", data=json.dumps(body), content_type="application/json", **headers)


def call(client, name: str, arguments: dict | None = None):
    """`tools/call`, returning `(is_error, text, structured)`."""
    response = rpc(client, "tools/call", {"name": name, "arguments": arguments or {}})
    assert response.status_code == 200, (response.status_code, response.content)
    message = json.loads(response.content)
    assert "result" in message, message
    result = message["result"]
    text = "".join(block["text"] for block in result["content"] if block["type"] == "text")
    return bool(result.get("isError")), text, result.get("structuredContent")


@pytest.fixture
def mcp_write(mcp_on, household_user):
    return client_with(household_user, DeviceToken.SCOPE_WRITE)


@pytest.fixture
def mcp_read(mcp_on, household_user):
    return client_with(household_user, DeviceToken.SCOPE_READ)


# =============================================================================
# The switch, auth, protocol
# =============================================================================


def test_mcp_is_off_by_default_and_says_so(settings, household_user):
    settings.LEGION_MCP = False
    client = client_with(household_user)
    response = rpc(client, "tools/list")
    assert response.status_code == 404
    assert "switched off" in response.json()["detail"]
    assert "Nothing was read or written" in response.json()["detail"]
    assert McpCall.objects.count() == 0


def test_no_token_is_refused(mcp_on):
    response = rpc(APIClient(), "tools/list")
    assert response.status_code == 401
    assert McpCall.objects.count() == 0


def test_a_browser_session_is_not_an_mcp_credential(mcp_on, household_user):
    client = APIClient()
    client.force_login(household_user)
    assert rpc(client, "tools/list").status_code == 401


def test_get_is_refused_in_words(mcp_write):
    response = mcp_write.get("/mcp")
    assert response.status_code == 405
    assert "POST only" in response.json()["detail"]


def test_server_discover_answers_the_modern_revision(mcp_write):
    response = rpc(mcp_write, "server/discover")
    assert response.status_code == 200, response.content
    result = json.loads(response.content)["result"]
    assert MODERN in result["supportedVersions"]
    assert result["capabilities"].get("tools") is not None


def test_a_legacy_client_initializes_and_lists_without_a_session(mcp_write):
    init = rpc(
        mcp_write,
        "initialize",
        {
            "protocolVersion": "2025-11-25",
            "capabilities": {},
            "clientInfo": {"name": "legacy", "version": "0"},
        },
        modern=False,
    )
    assert init.status_code == 200, init.content
    assert "Mcp-Session-Id" not in init.headers
    listed = rpc(mcp_write, "tools/list", modern=False)
    assert listed.status_code == 200, listed.content
    names = {tool["name"] for tool in json.loads(listed.content)["result"]["tools"]}
    assert names == set(TOOLS_BY_NAME)


def test_tools_list_is_the_registry_with_its_hints(mcp_read):
    response = rpc(mcp_read, "tools/list")
    tools = {tool["name"]: tool for tool in json.loads(response.content)["result"]["tools"]}
    assert set(tools) == set(TOOLS_BY_NAME)
    for name, listed in tools.items():
        tool = TOOLS_BY_NAME[name]
        hints = listed["annotations"]
        assert hints["readOnlyHint"] is (not tool.writes), name
        assert hints["destructiveHint"] is tool.destructive, name
        assert listed["inputSchema"] == tool.input_schema


# =============================================================================
# Reads
# =============================================================================


def test_an_empty_read_says_it_is_empty_not_unreadable(mcp_read):
    is_error, text, _ = call(mcp_read, "read_records", {"table": "places"})
    assert not is_error
    assert "real empty result, not a failure to read" in text


def test_read_records_reads_through_the_rest_shape(mcp_read, auth_client):
    assert (
        auth_client.put(
            "/api/places/home/", {"latitude": 1.5, "longitude": -95.0}, format="json"
        ).status_code
        == 200
    )
    is_error, text, structured = call(mcp_read, "read_records", {"table": "places"})
    assert not is_error, text
    assert [row["label"] for row in structured["rows"]] == ["home"]
    rest = auth_client.get("/api/places/").data["results"]
    assert set(structured["rows"][0]) - {"_engine_says"} == set(rest[0])


def test_a_gated_read_says_provenance_and_estimates_in_words(mcp_read, auth_client):
    assert auth_client.post("/api/ingest/receipt", a_receipt(), format="json").status_code == 201
    is_error, text, structured = call(mcp_read, "read_records", {"table": "receipt_line_items"})
    assert not is_error, text
    milk = next(row for row in structured["rows"] if row["name"] == "MILK")
    assert "ESTIMATES, not measurements" in milk["_engine_says"]
    assert "estimated_calories_kcal" in milk["_engine_says"]
    assert "provenance" in milk["_engine_says"]


def test_unverified_and_unaccounted_are_said_in_words():
    words = _say("receipts", {"provenance": "UNRECONCILED", "unaccounted_cents": 40})
    assert "UNVERIFIED" in words
    assert "never be stated as fact" in words
    assert "not tax" in words


def test_a_garbled_since_is_refused_rather_than_widened(mcp_read):
    is_error, text, _ = call(mcp_read, "read_records", {"table": "places", "since": "soon"})
    assert is_error
    assert text.startswith("Nothing was read.")


def test_memory_tables_are_excluded(mcp_write):
    assert EXCLUDED_TABLES == {"memories", "companion_memories", "memory_audit"}
    for table in EXCLUDED_TABLES:
        assert table not in READABLE
        is_error, text, _ = call(mcp_write, "read_records", {"table": table})
        assert is_error
        assert "Nothing was read" in text
    is_error, text, _ = call(
        mcp_write, "write_record", {"table": "memories", "identity": "x", "fields": {}}
    )
    assert is_error and "Nothing was written" in text


# =============================================================================
# Writes, scope, the gate
# =============================================================================


def test_write_record_goes_through_the_rest_view(mcp_write, auth_client):
    is_error, text, structured = call(
        mcp_write,
        "write_record",
        {"table": "places", "identity": "gym", "fields": {"latitude": 2.0, "longitude": 3.0}},
    )
    assert not is_error, text
    assert "committed" in text
    assert structured["row"]["label"] == "gym"
    rows = auth_client.get("/api/places/").data["results"]
    assert [row["label"] for row in rows] == ["gym"]


def test_a_rejected_write_says_nothing_was_written(mcp_write, auth_client):
    is_error, text, _ = call(
        mcp_write,
        "write_record",
        {"table": "places", "identity": "gym", "fields": {"latitude": "north"}},
    )
    assert is_error
    assert text.startswith("Nothing was written.")
    assert auth_client.get("/api/places/").data["results"] == []


def test_delete_record_tombstones(mcp_write, auth_client):
    auth_client.put("/api/places/gym/", {"latitude": 1.0, "longitude": 1.0}, format="json")
    is_error, text, _ = call(mcp_write, "delete_record", {"table": "places", "identity": "gym"})
    assert not is_error, text
    assert "tombstone" in text
    assert auth_client.get("/api/places/?active=1").data["results"] == []


# One valid call per write tool, so the read-scope test reaches every one.
def _write_calls(checklist_id, item_id, event_id, purchase_id):
    return {
        "write_record": {"table": "places", "identity": "gym", "fields": {"latitude": 1.0}},
        "delete_record": {"table": "places", "identity": "home"},
        "add_event": {"fields": {"title": "MOT"}},
        "update_event": {"id": event_id, "fields": {"title": "changed"}},
        "delete_event": {"id": event_id},
        "add_checklist_item": {"checklist_id": checklist_id, "text": "squats"},
        "tick_checklist_item": {
            "checklist_id": checklist_id,
            "item_id": item_id,
            "date": "2026-10-02",
        },
        "log_purchase": {"item": "shampoo", "date": "2026-10-02"},
        "delete_purchase": {"id": purchase_id},
    }


@pytest.fixture
def seeded(auth_client):
    auth_client.put("/api/places/home/", {"latitude": 1.0, "longitude": 1.0}, format="json")
    event = auth_client.post(
        "/api/events", {"title": "dentist", "starts_at": "2026-10-05T09:00:00Z"}, format="json"
    ).data
    checklist = auth_client.post("/api/checklists/", {"name": "gym"}, format="json").data
    item = auth_client.post(
        f"/api/checklists/{checklist['id']}/items", {"text": "bench"}, format="json"
    ).data
    purchase = auth_client.post(
        "/api/purchases/", {"item": "soap", "bought_on": 20365}, format="json"
    ).data
    return {
        "event": event["id"],
        "checklist": checklist["id"],
        "item": item["id"],
        "purchase": purchase["id"],
    }


def test_every_write_tool_refuses_a_read_token_and_writes_nothing(mcp_read, auth_client, seeded):
    calls = _write_calls(
        seeded["checklist"], seeded["item"], seeded["event"], seeded["purchase"]
    )
    assert set(calls) == {tool.name for tool in TOOLS if tool.writes}

    def everything():
        feed = dict(auth_client.get("/api/changes").data)
        feed.pop("server_time", None)
        # The bought log is not in the changes feed (online only, ticket 04).
        feed["purchases"] = auth_client.get("/api/purchases/").data["results"]
        return feed

    before = everything()
    for name, arguments in calls.items():
        is_error, text, _ = call(mcp_read, name, arguments)
        assert is_error, name
        assert text.startswith("Nothing was written."), (name, text)
        assert "read-only" in text, name
    assert everything() == before
    assert set(McpCall.objects.filter(tool__in=calls).values_list("outcome", flat=True)) == {
        Outcome.REFUSED
    }


def test_the_rest_permission_is_a_second_lock_behind_the_tool(household_user):
    """A read-scoped token through REST directly: the same refusal, from
    `IsHouseholdMember`, which the dispatched view also runs."""
    client = client_with(household_user, DeviceToken.SCOPE_READ)
    response = client.put("/api/places/gym/", {"latitude": 1.0}, format="json")
    assert response.status_code == 403
    assert "Nothing was written" in response.data["detail"]


@pytest.mark.parametrize("table", sorted(GATED_TABLES))
def test_gated_tables_are_unwritable_and_the_refusal_names_the_gate(mcp_write, table):
    assert table not in WRITABLE and table not in DELETABLE
    is_error, text, _ = call(
        mcp_write, "write_record", {"table": table, "identity": "x", "fields": {}}
    )
    assert is_error
    assert "Nothing was written" in text
    assert "reconciliation gate" in text
    is_error, text, _ = call(mcp_write, "delete_record", {"table": table, "identity": "x"})
    assert is_error
    assert "Nothing was deleted" in text


def test_events_round_trip(mcp_write):
    is_error, text, structured = call(
        mcp_write,
        "add_event",
        {"fields": {"title": "MOT", "starts_at": "2026-10-03T09:00:00Z", "origin_guid": "g1"}},
    )
    assert not is_error, text
    event_id = structured["row"]["id"]
    is_error, text, _ = call(
        mcp_write, "add_event", {"fields": {"title": "MOT", "origin_guid": "g1"}}
    )
    assert not is_error and text.startswith("Nothing was created")

    is_error, text, sections = call(
        mcp_write, "list_events", {"from": "2026-10-01", "to": "2026-10-10"}
    )
    assert not is_error, text
    assert [row["id"] for row in sections["in_window"]] == [event_id]
    assert "NOT expanded" in text

    is_error, text, _ = call(mcp_write, "update_event", {"id": event_id, "fields": {"done": True}})
    assert not is_error, text
    is_error, text, sections = call(
        mcp_write, "list_events", {"from": "2026-10-01", "to": "2026-10-10"}
    )
    assert sections["in_window"] == []
    assert "real empty result" in text

    is_error, text, _ = call(mcp_write, "delete_event", {"id": event_id})
    assert not is_error, text
    is_error, text, _ = call(mcp_write, "delete_event", {"id": "not-a-uuid"})
    assert is_error


def test_checklists_round_trip(mcp_write, seeded):
    is_error, text, structured = call(
        mcp_write, "add_checklist_item", {"checklist_id": seeded["checklist"], "text": "squats"}
    )
    assert not is_error, text
    squats = structured["row"]["id"]
    is_error, text, _ = call(
        mcp_write,
        "tick_checklist_item",
        {"checklist_id": seeded["checklist"], "item_id": squats, "date": "2026-10-02"},
    )
    assert not is_error, text
    is_error, text, structured = call(mcp_write, "list_checklists", {"date": "2026-10-02"})
    assert not is_error, text
    gym = next(row for row in structured["checklists"] if row["name"] == "gym")
    items = {row["text"]: row["ticked_on_date"] for row in gym["items"]}
    assert items == {"bench": False, "squats": True}


# =============================================================================
# The honesty contract over the registry
# =============================================================================


@pytest.mark.parametrize("tool", TOOLS, ids=lambda tool: tool.name)
def test_every_tool_fails_in_words(mcp_write, tool):
    """Ticket 06's contract as a test over the registry: a failure result with
    no words fails the suite. Arguments the schema forbids are the one failure
    every tool can be driven into without knowing its domain."""
    is_error, text, _ = call(mcp_write, tool.name, {"not_an_argument": True})
    assert is_error, tool.name
    assert text.startswith(f"Nothing was {tool.verb}."), (tool.name, text)


def test_an_unknown_tool_fails_in_words(mcp_write):
    is_error, text, _ = call(mcp_write, "summarise_inbox")
    assert is_error
    assert text.startswith("Nothing was read or written.")


def test_no_tool_reaches_third_party_content_or_the_gate():
    """CLAUDE.md section 7: mail never reaches the server, so no tool may
    offer it. Ticket 07 (Kevin, 2026-10-02): no ingestion over MCP until the
    statement CSV format exists."""
    third_party = re.compile(r"\b(mail|gmail|inbox|e-?mail|sms|message)s?\b", re.IGNORECASE)
    ingestion = re.compile(r"ingest|submit|upload|statement|receipt", re.IGNORECASE)
    for tool in TOOLS:
        assert not third_party.search(f"{tool.name} {tool.title} {tool.description}"), tool.name
        assert not ingestion.search(tool.name), tool.name
    assert not any("ingest/" in tool.description for tool in TOOLS)


def test_write_tools_name_the_scope_and_the_outcome_rule():
    for tool in TOOLS:
        if tool.writes:
            assert "write scope" in tool.description, tool.name
            assert "committed" in tool.description, tool.name


# =============================================================================
# Audit and throttle (ticket 08)
# =============================================================================


def test_every_call_is_audited_without_its_arguments(mcp_write, household_user):
    call(
        mcp_write,
        "write_record",
        {
            "table": "places",
            "identity": "secret-spot",
            "fields": {"latitude": 1.0, "longitude": 1.0},
        },
    )
    rpc(mcp_write, "tools/list")
    rows = list(McpCall.objects.order_by("at"))
    assert [(row.method, row.tool, row.outcome) for row in rows] == [
        ("tools/call", "write_record", Outcome.OK),
        ("tools/list", None, Outcome.OK),
    ]
    assert rows[0].token_name == "Claude Code"
    assert rows[0].token_scope == DeviceToken.SCOPE_WRITE
    assert rows[0].household_id == household_user.household.id
    stored = json.dumps([list(row.__dict__.values()) for row in rows], default=str)
    assert "secret-spot" not in stored
    assert {f.name for f in McpCall._meta.get_fields()} == {
        "id",
        "household",
        "token",
        "token_name",
        "token_scope",
        "method",
        "tool",
        "outcome",
        "at",
    }


def test_the_throttle_is_per_token_and_audited(mcp_on, household_user, monkeypatch):
    monkeypatch.setattr(McpTokenThrottle, "rate", "2/min", raising=False)
    looping = client_with(household_user, name="looping model")
    phone = client_with(household_user, name="phone")
    assert rpc(looping, "tools/list").status_code == 200
    assert rpc(looping, "tools/list").status_code == 200
    third = rpc(looping, "tools/list")
    assert third.status_code == 429
    assert "Nothing was read or written" in third.json()["detail"]
    assert rpc(phone, "tools/list").status_code == 200
    assert (
        McpCall.objects.filter(outcome=Outcome.THROTTLED, token_name="looping model").count() == 1
    )
