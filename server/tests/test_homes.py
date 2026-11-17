"""Engine homes for Room-only data (engine-brain ticket 03): the REST doors, the backfill's batch
door, and the agent tools over goals, pending transactions, advice and raises.

Every row is invented. Tenancy and member privacy are in `test_tenancy.py` and
`test_visibility.py`; this file is behaviour.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime

import pytest

from agents.evals.runner import member_request
from agents.legion.specs import spec as load_spec
from agents.legion.twins import TWINS
from homes import tools as ht
from homes.models import PendingTransaction
from homes.views import BATCH_MAX
from legacy.models.body import Goal

pytestmark = pytest.mark.django_db

GOAL = {
    "lineage_id": 41,
    "aspect": "body",
    "statement": "Swim 1 km without stopping",
    "target_value": 1.0,
    "unit": "km",
    "created_at_client": "2026-03-01T10:00:00Z",
}
PENDING = {
    "account_id": "Test card 1111",
    "currency": "USD",
    "txn_date": "2026-03-02",
    "description": "Bagel at the test bakery",
    "amount_cents": -425,
    "logged_at": "2026-03-02T09:00:00Z",
}
RAISE = {
    "rule_id": "long_drive_break",
    "category": "timing",
    "reason": "two hours of driving without a stop",
    "spoken_at": "2026-03-02T09:00:00Z",
    "delivery": "spoken",
}


# -- REST ----------------------------------------------------------------------------------------


def test_goal_put_is_an_upsert_and_delete_a_tombstone(auth_client):
    first = auth_client.put("/api/body/goals/g-1/", GOAL, format="json")
    assert first.status_code == 200, first.data
    assert first.data["status"] == "active" and first.data["origin_guid"] == "g-1"
    again = auth_client.put("/api/body/goals/g-1/", {**GOAL, "status": "achieved"}, format="json")
    assert again.status_code == 200 and again.data["id"] == first.data["id"]
    assert Goal.objects.count() == 1
    assert auth_client.delete("/api/body/goals/g-1/").status_code == 204
    assert auth_client.get("/api/body/goals/?active=1").data["results"] == []
    feed = auth_client.get("/api/body/goals/").data["results"]
    assert feed[0]["deleted_at"] is not None


def test_a_goal_status_outside_the_set_is_refused_naming_it(auth_client):
    bad = auth_client.put("/api/body/goals/g-2/", {**GOAL, "status": "paused"}, format="json")
    assert bad.status_code == 400
    assert "paused" in str(bad.data)


def test_an_unknown_field_is_refused_never_dropped(auth_client):
    bad = auth_client.put("/api/ledger/pending/p-1/", {**PENDING, "mood": "x"}, format="json")
    assert bad.status_code == 400 and "mood" in str(bad.data)


@pytest.mark.parametrize("cents", [0, 100_000_001, -100_000_001])
def test_a_pending_amount_of_zero_or_a_misheard_size_is_refused(auth_client, cents):
    bad = auth_client.put(
        "/api/ledger/pending/p-1/", {**PENDING, "amount_cents": cents}, format="json"
    )
    assert bad.status_code == 400
    assert not PendingTransaction.objects.exists()


def test_a_pending_update_moves_updated_at(auth_client):
    one = auth_client.put("/api/ledger/pending/p-1/", PENDING, format="json").data
    two = auth_client.put(
        "/api/ledger/pending/p-1/", {**PENDING, "description": "Two bagels"}, format="json"
    ).data
    assert two["updated_at"] >= one["updated_at"] and two["description"] == "Two bagels"
    since = auth_client.get(f"/api/ledger/pending/?since={one['updated_at']}").data["results"]
    assert [r["origin_guid"] for r in since] == ["p-1"]


def test_a_raise_has_no_delete(auth_client):
    assert auth_client.put("/api/proactive/raises/r-1/", RAISE, format="json").status_code == 200
    assert auth_client.delete("/api/proactive/raises/r-1/").status_code == 405


def test_the_routes_need_a_member(client):
    for prefix in ("/api/body/goals/", "/api/advisor/advice/", "/api/ledger/pending/"):
        assert client.get(prefix).status_code in (401, 403)
        assert client.post(f"{prefix}batch/", {"rows": []}, format="json").status_code in (401, 403)


# -- the backfill's batch door -------------------------------------------------------------------


def _rows(n: int) -> list[dict]:
    return [
        {**PENDING, "origin_guid": f"bf-{i}", "description": f"Test item {i}"} for i in range(n)
    ]


def test_batch_is_idempotent_and_says_what_it_moved(auth_client):
    first = auth_client.post("/api/ledger/pending/batch/", {"rows": _rows(5)}, format="json")
    assert first.status_code == 200, first.data
    assert (first.data["created"], first.data["updated"], first.data["unchanged"]) == (5, 0, 0)
    assert first.data["engine_count"] == 5 and first.data["failed"] == []
    assert "created 5" in first.data["says"]
    again = auth_client.post("/api/ledger/pending/batch/", {"rows": _rows(5)}, format="json")
    assert (again.data["created"], again.data["updated"], again.data["unchanged"]) == (0, 0, 5)
    assert PendingTransaction.objects.count() == 5


def test_a_batch_that_stopped_halfway_resumes_without_doubling(auth_client):
    auth_client.post("/api/ledger/pending/batch/", {"rows": _rows(3)}, format="json")
    rows = _rows(6)
    rows[0]["description"] = "Changed on the phone since"
    done = auth_client.post("/api/ledger/pending/batch/", {"rows": rows}, format="json").data
    assert (done["created"], done["updated"], done["unchanged"]) == (3, 1, 2)
    assert PendingTransaction.objects.count() == 6


def test_a_bad_row_is_named_and_the_rest_still_land(auth_client):
    rows = _rows(3)
    rows[1]["currency"] = "EUR"
    rows.append({"description": "no identity"})
    out = auth_client.post("/api/ledger/pending/batch/", {"rows": rows}, format="json").data
    assert out.get("created") == 2, out
    named = {f["origin_guid"] for f in out["failed"]}
    assert named == {"bf-1", None}


def test_a_batch_over_the_limit_or_not_a_list_writes_nothing(auth_client):
    big = auth_client.post(
        "/api/ledger/pending/batch/", {"rows": _rows(BATCH_MAX + 1)}, format="json"
    )
    assert big.status_code == 400 and "Nothing was written" in big.data["detail"]
    odd = auth_client.post("/api/ledger/pending/batch/", {"rows": "x"}, format="json")
    assert odd.status_code == 400
    assert not PendingTransaction.objects.exists()


def test_goal_batch_keeps_each_revision_and_its_link(auth_client):
    rows = [
        {**GOAL, "origin_guid": "rev-1", "statement": "Swim 500 m"},
        {
            **GOAL,
            "origin_guid": "rev-2",
            "supersedes_guid": "rev-1",
            "created_at_client": "2026-03-05T10:00:00Z",
        },
    ]
    out = auth_client.post("/api/body/goals/batch/", {"rows": rows}, format="json").data
    assert out.get("created") == 2, out
    assert Goal.objects.get(origin_guid="rev-2").supersedes_guid == "rev-1"


def test_the_batch_route_is_not_taken_as_an_identity(auth_client):
    assert auth_client.put("/api/body/goals/batch/", GOAL, format="json").status_code == 405


# -- the agent tools -----------------------------------------------------------------------------


@pytest.fixture
def req(household_user):
    return member_request(household_user)


def _goal(household, guid, statement, lineage, created, status="active", **extra):
    return Goal.objects.create(
        id=uuid.uuid4(),
        household=household,
        lineage_id=lineage,
        aspect=extra.pop("aspect", "bio"),
        statement=statement,
        status=status,
        created_at_client=created,
        provenance="USER",
        created_at=created,
        updated_at=created,
        origin_guid=guid,
        **extra,
    )


def _at(day: int) -> datetime:
    return datetime(2026, 3, day, 12, tzinfo=UTC)


def test_the_tools_are_twins_classified_and_in_the_generalist():
    spec = load_spec("generalist")
    for tool in ht.HOME_TOOLS:
        assert TWINS[tool.name] is tool
        assert tool.name in spec.tools
    writes = {t.name for t in ht.HOME_TOOLS if t.writes}
    assert writes == {
        "set_goal",
        "close_goal",
        "log_pending_transaction",
        "clear_pending_transaction",
    }


def test_list_goals_reads_each_lineages_newest_revision(req, household_a):
    _goal(household_a, "a1", "Swim 500 m", 1, _at(1))
    _goal(household_a, "a2", "Swim 1 km", 1, _at(5), supersedes_guid="a1")
    _goal(household_a, "b1", "Learn ten chords", 2, _at(2), status="abandoned")
    out = ht.LIST_GOALS.handler(req, {})
    assert [g["statement"] for g in out.structured["goals"]] == ["Swim 1 km"]
    assert out.structured["goals"][0]["revisions"] == 2
    every = ht.LIST_GOALS.handler(req, {"include_closed": True}).structured["goals"]
    assert {g["statement"] for g in every} == {"Swim 1 km", "Learn ten chords"}


def test_list_goals_says_an_empty_result_is_real(req):
    assert "real empty result" in ht.LIST_GOALS.handler(req, {}).text


def test_set_goal_new_then_revised_keeps_the_history(req, household_a):
    made = ht.SET_GOAL.handler(req, {"statement": "Walk 8000 steps a day", "aspect": "bio"})
    assert not made.is_error and "committed" in made.text
    first = Goal.objects.get()
    revised = ht.SET_GOAL.handler(
        req, {"statement": "Walk 10000 steps a day", "revises_goal_id": first.origin_guid}
    )
    assert not revised.is_error and revised.structured["revised"]
    rows = Goal.objects.order_by("created_at_client")
    assert rows.count() == 2 and rows[1].lineage_id == first.lineage_id
    assert rows[1].supersedes_guid == first.origin_guid and rows[1].aspect == "bio"


def test_set_goal_refuses_without_an_aspect_or_with_an_unknown_revision(req):
    assert ht.SET_GOAL.handler(req, {"statement": "x"}).is_error
    assert ht.SET_GOAL.handler(req, {"statement": "x", "aspect": "pantry"}).is_error
    bad = ht.SET_GOAL.handler(req, {"statement": "x", "revises_goal_id": "nope"})
    assert bad.is_error and "Nothing was written" in bad.text
    assert not Goal.objects.exists()


def test_close_goal_closes_the_current_revision_once(req, household_a):
    _goal(household_a, "a1", "Swim 500 m", 1, _at(1))
    _goal(household_a, "a2", "Swim 1 km", 1, _at(5))
    done = ht.CLOSE_GOAL.handler(req, {"goal_id": "a1", "status": "achieved"})
    assert not done.is_error and "Swim 1 km" in done.text
    assert Goal.objects.get(origin_guid="a2").status == "achieved"
    assert Goal.objects.get(origin_guid="a1").status == "active"
    again = ht.CLOSE_GOAL.handler(req, {"goal_id": "a2", "status": "abandoned"})
    assert again.is_error and "already achieved" in again.text


def _ledger_account(household, nickname, last4, currency="USD"):
    import uuid

    from legacy.models.ledger import LedgerTransaction

    LedgerTransaction.objects.create(
        id=uuid.uuid4(),
        household=household,
        statement=None,
        account_last4=last4,
        account_nickname=nickname,
        currency=currency,
        txn_date=_at(1).date(),
        description="Test shop",
        amount_cents=-100,
        balance_cents=None,
        line_ref=f"t:{uuid.uuid4()}",
        category=None,
        category_pending=False,
        provenance="BANK_API",
        created_at=_at(1),
        bank_transaction_id=str(uuid.uuid4()),
        bank_pending=False,
    )


def test_log_pending_resolves_one_account_and_signs_the_cents(req, household_a):
    _ledger_account(household_a, "Test card 1111", "1111")
    out = ht.LOG_PENDING.handler(
        req, {"account": "1111", "description": "Lunch", "amount": 12.5, "direction": "debit"}
    )
    assert not out.is_error, out.text
    row = PendingTransaction.objects.get()
    assert row.amount_cents == -1250 and row.account_id == "Test card 1111"
    assert "unverified" in out.text


def test_log_pending_asks_when_no_or_several_accounts_match(req, household_a):
    none = ht.LOG_PENDING.handler(req, {"description": "Lunch", "amount": 5, "direction": "debit"})
    assert none.is_error and "No account is on file" in none.text
    _ledger_account(household_a, "Test card 1111", "1111")
    _ledger_account(household_a, "Test savings 2222", "2222")
    several = ht.LOG_PENDING.handler(
        req, {"account": "test", "description": "Lunch", "amount": 5, "direction": "debit"}
    )
    assert several.is_error and "ask which" in several.text
    missing = ht.LOG_PENDING.handler(
        req, {"account": "9999", "description": "Lunch", "amount": 5, "direction": "credit"}
    )
    assert missing.is_error and not PendingTransaction.objects.exists()


def test_list_and_clear_pending(req, household_a):
    PendingTransaction.objects.create(
        household=household_a,
        origin_guid="p-1",
        logged_at=_at(2),
        **{k: v for k, v in PENDING.items() if k not in {"logged_at"}},
    )
    listed = ht.LIST_PENDING.handler(req, {})
    assert "Bagel" in listed.text and "not verified" in listed.text
    cleared = ht.CLEAR_PENDING.handler(req, {"pending_id": "p-1"})
    assert not cleared.is_error
    assert PendingTransaction.objects.get().deleted_at is not None
    assert "real empty result" in ht.LIST_PENDING.handler(req, {}).text
    assert ht.CLEAR_PENDING.handler(req, {"pending_id": "p-1"}).is_error


def test_why_did_you_say_that_reads_the_newest_raise(req, household_a, household_user):
    from homes.models import ProactiveRaise

    assert "real empty result" in ht.WHY_DID_YOU_SAY_THAT.handler(req, {}).text
    for n, reason in enumerate(("an older test reason", "the newest test reason")):
        ProactiveRaise.objects.create(
            household=household_a,
            owner_user=household_user,
            origin_guid=f"r{n}",
            rule_id="test_rule",
            category="timing",
            reason=reason,
            spoken_at=_at(1 + n),
        )
    out = ht.WHY_DID_YOU_SAY_THAT.handler(req, {})
    assert "the newest test reason" in out.text and "said aloud" in out.text


def test_a_write_tool_refuses_in_a_tainted_turn(req, household_a):
    """ADR 0090 through the same door the agents use: a turn that read third-party text writes
    nothing."""
    from assistant import read_through
    from engine_mcp.tools import run_engine_tool

    read_through.before_tool(req, "web_search")
    out = run_engine_tool(req, ht.SET_GOAL, {"statement": "x", "aspect": "bio"})
    assert out.is_error
    assert not Goal.objects.exists()
