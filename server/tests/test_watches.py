"""Standing watches and engine signals (watches map, ADR 0083): the closed registry, the REST
routes, the deterministic evaluator, member privacy (ADR 0052) and household tenancy (ADR 0045).

Every merchant, payer, course, company and amount here is made up.
"""

from __future__ import annotations

import datetime as dt
import io
import uuid
from pathlib import Path

import pytest
from django.core.management import call_command
from django.db import IntegrityError, transaction
from django.db.models.functions import Now
from django.utils import timezone
from rest_framework.test import APIClient

from household.models import DeviceToken, Household, HouseholdMember, User
from legacy.models.dates import Event
from legacy.models.ledger import LedgerTransaction
from tests.conftest import _client_for, _member
from watches.evaluate import evaluate_household
from watches.models import Watch, WatchFiring
from watches.registry import BUILTINS, unknown_kind_refusal

pytestmark = pytest.mark.django_db

URL = "/api/watches/"
FIRINGS = "/api/watches/firings"
ACK = "/api/watches/firings/ack"


@pytest.fixture
def kevin(household_user):
    return household_user


@pytest.fixture
def mia(household_a):
    return _member(household_a, "mia@example.com")


@pytest.fixture
def mia_client(mia):
    return _client_for(mia)


def _set(client, kind, params=None, **extra):
    made = client.post(URL, {"kind": kind, "params": params or {}, **extra}, format="json")
    assert made.status_code == 201, made.data
    return made.data["watch"]["id"]


def _watch(watch_id) -> Watch:
    return Watch.objects.get(pk=watch_id)


def _later(minutes=1):
    return timezone.now() + dt.timedelta(minutes=minutes)


def _txn(
    household,
    cents,
    description="EXAMPLE HARDWARE",
    *,
    last4="1111",
    day=None,
    pending=False,
    bank_id=None,
    stored=None,
):
    row_id = uuid.uuid4()
    LedgerTransaction.objects.create(
        id=row_id,
        household=household,
        account_last4=last4,
        account_nickname="Test account",
        currency="USD",
        txn_date=day or timezone.now().date(),
        description=description,
        amount_cents=cents,
        line_ref=f"plaid:{row_id}",
        category_pending=False,
        provenance="BANK_API",
        bank_transaction_id=bank_id or f"txn-{row_id.hex[:10]}",
        bank_pending=pending,
        created_at=stored or Now(),
    )
    return row_id


def _firings(user, **filters):
    return list(WatchFiring.objects.filter(owner_user=user, **filters).order_by("fired_at", "id"))


# =============================================================================
# The registry and the routes
# =============================================================================


def test_the_first_read_seeds_the_builtins_once_and_lists_every_kind(auth_client, kevin):
    first = auth_client.get(URL)
    assert first.status_code == 200, first.data
    assert sorted(w["builtin"] for w in first.data["results"]) == sorted(b.key for b in BUILTINS)
    assert {k["kind"] for k in first.data["kinds"]} >= {"charge_over", "mail_reply"}
    auth_client.get(URL)
    assert Watch.objects.filter(owner_user=kevin).count() == len(BUILTINS)


def test_a_removed_builtin_is_never_seeded_again(auth_client, kevin):
    rows = auth_client.get(URL).data["results"]
    gone = next(w for w in rows if w["builtin"] == "big_charge")
    assert auth_client.delete(f"{URL}{gone['id']}").status_code == 204
    again = auth_client.get(URL).data["results"]
    assert "big_charge" not in {w["builtin"] for w in again}
    assert Watch.objects.filter(owner_user=kevin, builtin="big_charge").count() == 1


def test_a_watch_is_typed_and_its_label_is_written_from_its_params(auth_client):
    made = auth_client.post(
        URL,
        {
            "kind": "deposit_over",
            "params": {"amount_cents": 150_000, "payer": "Example Payroll"},
            "source": "voice",
        },
        format="json",
    )
    assert made.status_code == 201, made.data
    watch = made.data["watch"]
    assert watch["params"] == {
        "amount_cents": 150_000,
        "currency": "USD",
        "payer": "Example Payroll",
    }
    assert watch["label"] == "A deposit of $1,500.00 or more from Example Payroll"
    assert "cannot fire yet" in made.data["note"] and "a linked bank" in made.data["note"]


def test_a_request_no_kind_fits_is_refused_naming_the_kinds_and_the_nearest(auth_client):
    refused = auth_client.post(URL, {"kind": "stock_price", "params": {}}, format="json")
    assert refused.status_code == 400
    assert refused.data["detail"].startswith("No watch was set")
    assert "charge_over" in refused.data["detail"] and "grade_posted" in refused.data["detail"]
    assert "Nearest: grade_posted" in unknown_kind_refusal("my_score", "when my grade comes")
    assert "conditions" in unknown_kind_refusal("weather", "tell me if it rains")
    assert not Watch.objects.filter(kind="stock_price").exists()


@pytest.mark.parametrize(
    "params, words",
    [
        ({}, "needs amount_cents"),
        ({"amount_cents": "lots"}, "whole number"),
        ({"amount_cents": 0}, "from 1"),
        ({"amount_cents": 500, "currency": "EUR"}, "USD, SGD"),
        ({"amount_cents": 500, "colour": "red"}, "not colour"),
    ],
)
def test_bad_params_are_refused_in_words_and_nothing_is_set(auth_client, kevin, params, words):
    refused = auth_client.post(URL, {"kind": "charge_over", "params": params}, format="json")
    assert refused.status_code == 400 and words in refused.data["detail"], refused.data
    assert not Watch.objects.filter(owner_user=kevin, builtin__isnull=True).exists()


def test_pause_resume_and_remove(auth_client):
    wid = _set(auth_client, "charge_over", {"amount_cents": 1_000})
    paused = auth_client.patch(f"{URL}{wid}", {"paused": True}, format="json")
    assert paused.status_code == 200 and paused.data["status"] == "paused"
    armed = _watch(wid).armed_at
    resumed = auth_client.patch(f"{URL}{wid}", {"paused": False}, format="json")
    assert resumed.data["status"] == "on" and _watch(wid).armed_at > armed
    assert auth_client.delete(f"{URL}{wid}").status_code == 204
    assert wid not in {w["id"] for w in auth_client.get(URL).data["results"]}
    assert auth_client.patch(f"{URL}{wid}", {"paused": True}, format="json").status_code == 404


# =============================================================================
# Who may use watches
# =============================================================================


def test_a_recruiter_visit_is_refused(household_a):
    demo = Household.objects.create(name="Demo visit test", is_demo_visit=True)
    visitor = User.objects.create_user(email="visitor-test@demo.invalid")
    HouseholdMember.objects.create(user=visitor, household=demo, role=HouseholdMember.RECRUITER)
    client = _client_for(visitor)
    assert client.get(URL).status_code == 403
    assert (
        client.post(
            URL, {"kind": "charge_over", "params": {"amount_cents": 1}}, format="json"
        ).status_code
        == 403
    )
    assert not Watch.objects.filter(owner_user=visitor).exists()


def test_a_claude_worker_token_can_neither_set_nor_read_watches(kevin):
    _token, raw = DeviceToken.issue(kevin, "claude-session-test")
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw}")
    refused = client.post(
        URL, {"kind": "charge_over", "params": {"amount_cents": 1}}, format="json"
    )
    assert refused.status_code == 403 and "Claude session" in refused.data["detail"]
    assert client.get(FIRINGS, {"pending": 1}).status_code == 403
    assert not Watch.objects.filter(owner_user=kevin).exists()


# =============================================================================
# Privacy (ADR 0052) and tenancy (ADR 0045)
# =============================================================================


def test_another_members_watch_and_firings_are_invisible(
    auth_client, kevin, mia_client, household_a
):
    wid = _set(auth_client, "charge_over", {"amount_cents": 1_000})
    _txn(household_a, -2_000)
    evaluate_household(household_a, _later())
    assert len(_firings(kevin)) == 1
    assert wid not in {w["id"] for w in mia_client.get(URL).data["results"]}
    assert mia_client.patch(f"{URL}{wid}", {"paused": True}, format="json").status_code == 404
    assert mia_client.delete(f"{URL}{wid}").status_code == 404
    assert mia_client.get(FIRINGS, {"pending": 1}).data["results"] == []
    firing = _firings(kevin)[0]
    assert (
        mia_client.post(ACK, {"ids": [str(firing.id)], "outcome": "spoken"}, format="json").data[
            "acked"
        ]
        == 0
    )
    assert WatchFiring.objects.get(pk=firing.id).acked_at is None


def test_another_households_member_sees_and_acks_nothing(auth_client, household_a, user_b):
    other = _client_for(user_b)
    wid = _set(auth_client, "charge_over", {"amount_cents": 1_000})
    _txn(household_a, -2_000)
    evaluate_household(household_a, _later())
    assert other.patch(f"{URL}{wid}", {"paused": True}, format="json").status_code == 404
    assert other.get(FIRINGS).data["results"] == []
    ids = [str(f.id) for f in WatchFiring.objects.all()]
    assert other.post(ACK, {"ids": ids, "outcome": "spoken"}, format="json").data["acked"] == 0


def test_the_database_refuses_an_owner_from_outside_the_household(household_a, user_b):
    with pytest.raises(IntegrityError), transaction.atomic():
        Watch.objects.create(
            household=household_a,
            owner_user=user_b,
            kind="charge_over",
            params={"amount_cents": 1},
            label="x",
        )


def test_a_canvas_watch_never_fires_on_another_members_private_item(
    auth_client, kevin, mia, mia_client, household_a
):
    made = auth_client.post(
        "/api/events",
        {
            "title": "EXAMPLE 101: Essay",
            "kind": "task",
            "visibility": "private",
            "starts_at": (timezone.now() + dt.timedelta(hours=5)).isoformat(),
        },
        format="json",
    )
    assert made.status_code == 201, made.data
    Event.objects.filter(pk=made.data["id"]).update(
        structured_meta={"canvas_assignment_id": 1, "canvas_submitted": False}
    )
    _set(auth_client, "canvas_due", {"hours": 24})
    _set(mia_client, "canvas_due", {"hours": 24})
    evaluate_household(household_a, timezone.now())
    (mine,) = _firings(kevin)
    assert "EXAMPLE 101: Essay is due" in mine.facts and "not submitted" in mine.facts
    assert mine.expires_at == Event.objects.get(pk=made.data["id"]).starts_at
    assert _firings(mia) == []


# =============================================================================
# The evaluator, kind by kind
# =============================================================================


def test_a_charge_fires_once_and_never_backfills(auth_client, kevin, household_a):
    _txn(household_a, -90_000, "EXAMPLE HARDWARE", stored=timezone.now() - dt.timedelta(hours=2))
    wid = _set(auth_client, "charge_over", {"amount_cents": 50_000, "merchant": "hardware"})
    _txn(
        household_a, -60_000, "EXAMPLE HARDWARE", day=timezone.now().date() - dt.timedelta(days=40)
    )
    _txn(household_a, -60_000, "EXAMPLE HARDWARE")
    _txn(household_a, -60_000, "EXAMPLE BAKERY")
    _txn(household_a, -100, "EXAMPLE HARDWARE")
    evaluate_household(household_a, _later())
    evaluate_household(household_a, _later(2))
    (firing,) = _firings(kevin, watch_id=wid)
    assert firing.facts.startswith("A charge of $600.00 at EXAMPLE HARDWARE")
    assert firing.detail["amount_cents"] == -60_000
    assert firing.sources[0]["table"] == "ledger_transactions"
    assert _watch(wid).last_fired_at is not None


def test_a_pending_charge_that_posts_is_one_fact(auth_client, kevin, household_a):
    wid = _set(auth_client, "charge_over", {"amount_cents": 50_000})
    pending = _txn(household_a, -55_000, pending=True)
    evaluate_household(household_a, _later())
    # plaid_sync's pending-to-posted: the pending row is deleted (a BANK_API row may be) and the
    # posted one inserted with a new id.
    LedgerTransaction.objects.filter(pk=pending).delete()
    _txn(household_a, -55_000)
    evaluate_household(household_a, _later(2))
    said = _firings(kevin, watch_id=wid, baseline=False)
    assert len(said) == 1 and "pending" in said[0].facts
    assert len(_firings(kevin, watch_id=wid, baseline=True)) == 1


def test_a_deposit_on_a_card_is_not_a_deposit(auth_client, kevin, household_a):
    from ingest.models import PlaidItem

    PlaidItem.objects.create(
        household=household_a,
        item_id="item-test",
        access_token_ciphertext=b"x",
        accounts=[
            {"account_id": "a1", "mask": "2222", "type": "credit"},
            {"account_id": "a2", "mask": "1111", "type": "depository"},
        ],
    )
    _set(auth_client, "deposit_over", {"amount_cents": 100_000, "payer": "payroll"})
    _txn(household_a, 200_000, "EXAMPLE PAYROLL", last4="2222")
    _txn(household_a, 200_000, "EXAMPLE PAYROLL", last4="1111")
    evaluate_household(household_a, _later())
    (firing,) = _firings(kevin, baseline=False)
    assert firing.facts.startswith(
        "A deposit of $2,000.00 from EXAMPLE PAYROLL, into the account ending 1111"
    )


def test_a_low_balance_fires_on_the_crossing_and_rearms_above(auth_client, kevin, household_a):
    from ingest.models import PlaidItem

    def balance(cents, as_of):
        PlaidItem.objects.update_or_create(
            household=household_a,
            item_id="item-test",
            defaults={
                "access_token_ciphertext": b"x",
                "accounts": [
                    {
                        "account_id": "a1",
                        "mask": "1111",
                        "name": "Everyday",
                        "type": "depository",
                        "balance_available_cents": cents,
                        "balance_currency": "USD",
                        "balance_as_of": as_of,
                    }
                ],
            },
        )

    _set(auth_client, "balance_under", {"amount_cents": 20_000})
    balance(15_000, "2026-11-10T15:00:00+00:00")
    evaluate_household(household_a, _later())
    balance(14_000, "2026-11-10T18:00:00+00:00")
    evaluate_household(household_a, _later(2))
    assert len(_firings(kevin)) == 1
    assert "available balance on Everyday ending 1111 was $150.00" in _firings(kevin)[0].facts
    balance(30_000, "2026-11-11T15:00:00+00:00")
    evaluate_household(household_a, _later(3))
    balance(10_000, "2026-11-12T15:00:00+00:00")
    evaluate_household(household_a, _later(4))
    assert len(_firings(kevin)) == 2


def test_grades_already_on_file_are_baseline_and_a_new_one_fires(auth_client, kevin, household_a):
    def task(title, meta):
        made = auth_client.post(
            "/api/events",
            {
                "title": title,
                "kind": "task",
                "visibility": "private",
                "starts_at": "2026-11-01T05:00:00Z",
            },
            format="json",
        )
        Event.objects.filter(pk=made.data["id"]).update(structured_meta=meta)
        return made.data["id"]

    task("EXAMPLE 101: Quiz 1", {"canvas_assignment_id": 1, "score": 9, "points_possible": 10})
    later = task("EXAMPLE 101: Quiz 2", {"canvas_assignment_id": 2, "score": None})
    _set(auth_client, "grade_posted", {})
    evaluate_household(household_a, _later())
    assert _firings(kevin, baseline=False) == []
    Event.objects.filter(pk=later).update(
        structured_meta={
            "canvas_assignment_id": 2,
            "score": 18,
            "points_possible": 20,
            "grade": "B+",
        }
    )
    evaluate_household(household_a, _later(2))
    (firing,) = _firings(kevin, baseline=False)
    assert firing.facts == "A grade is on Canvas for EXAMPLE 101: Quiz 2: 18 out of 20, grade B+."


def test_service_due_by_date_is_fact_and_by_miles_is_an_estimate(auth_client, kevin, household_a):
    vehicle = auth_client.put(
        "/api/fleet/vehicles/car-1/",
        {
            "name": "Testcar",
            "make": "Example",
            "model": "One",
            "year": 2010,
            "confirmed": True,
            "archived": False,
        },
        format="json",
    ).data["id"]
    today = timezone.now().date()
    six_months_ago = (today - dt.timedelta(days=176)).isoformat()
    for path, body in [
        (
            "service_history/s1/",
            {
                "vehicle_id": vehicle,
                "service_name": "Oil change",
                "kind": "OBSERVED",
                "service_date": six_months_ago,
                "mileage": 50_000,
            },
        ),
        (
            "service_history/s2/",
            {
                "vehicle_id": vehicle,
                "service_name": "Spark plugs",
                "kind": "OBSERVED",
                "service_date": "2024-01-10",
                "mileage": 30_000,
            },
        ),
        (
            "code_events/c1/",
            {
                "vehicle_id": vehicle,
                "occurred_at": timezone.now().isoformat(),
                "mileage": 59_800,
                "codes": ["P0000"],
            },
        ),
        (
            f"maintenance_schedules/{vehicle}/oil change/",
            {"interval_months": 6, "interval_source": "SEEDED", "never_done": False},
        ),
        (
            f"maintenance_schedules/{vehicle}/spark plugs/",
            {"interval_miles": 30_000, "interval_source": "SEEDED", "never_done": False},
        ),
    ]:
        response = auth_client.put(f"/api/fleet/{path}", body, format="json")
        assert response.status_code == 200, (path, response.data)
    _set(auth_client, "service_due", {"days": 14, "miles": 500})
    evaluate_household(household_a, _later())
    texts = sorted(f.facts for f in _firings(kevin))
    assert len(texts) == 2, texts
    assert texts[0].startswith("Testcar: oil change is due") and "by the dates on file" in texts[0]
    assert "spark plugs comes due at 60,000 miles, about 200 miles to go" in texts[1]
    assert texts[1].endswith("an estimate.")


def test_a_one_shot_watch_is_spent_after_its_first_firing(auth_client, kevin, household_a):
    wid = _set(auth_client, "charge_over", {"amount_cents": 1_000}, repeat=False)
    _txn(household_a, -2_000)
    evaluate_household(household_a, _later())
    _txn(household_a, -3_000)
    evaluate_household(household_a, _later(2))
    assert len(_firings(kevin, watch_id=wid)) == 1
    assert _watch(wid).spent_at is not None
    listed = {w["id"]: w for w in auth_client.get(URL).data["results"]}
    assert listed[wid]["status"] == "done"


def test_a_mail_reply_watch_is_stored_but_never_evaluated_on_the_engine(
    auth_client, kevin, household_a
):
    made = auth_client.post(
        URL,
        {"kind": "mail_reply", "params": {"companies": ["Acme", "Example Corp", "acme"]}},
        format="json",
    )
    assert made.status_code == 201, made.data
    assert made.data["watch"]["params"] == {"companies": ["Acme", "Example Corp"]}
    assert made.data["watch"]["evaluated_on"] == "phone"
    assert made.data["note"] is None
    report = evaluate_household(household_a, _later())
    assert not WatchFiring.objects.filter(kind="mail_reply").exists()
    assert report.errors == []


# =============================================================================
# The phone's side: pending and ack
# =============================================================================


def test_pending_is_unsaid_live_unexpired_firings_and_ack_takes_them_out(
    auth_client, kevin, household_a
):
    wid = _set(auth_client, "charge_over", {"amount_cents": 1_000})
    _txn(household_a, -2_000, "EXAMPLE ONE")
    _txn(household_a, -3_000, "EXAMPLE TWO")
    evaluate_household(household_a, _later())
    pending = auth_client.get(FIRINGS, {"pending": 1}).data["results"]
    assert [p["facts"][:30] for p in pending] == [
        f.facts[:30] for f in _firings(kevin, baseline=False)
    ]
    assert all(p["watch_label"] == "A charge of $10.00 or more" for p in pending)
    acked = auth_client.post(ACK, {"ids": [pending[0]["id"]], "outcome": "spoken"}, format="json")
    assert acked.data["acked"] == 1
    assert len(auth_client.get(FIRINGS, {"pending": 1}).data["results"]) == 1
    WatchFiring.objects.filter(owner_user=kevin).update(expires_at=timezone.now())
    assert auth_client.get(FIRINGS, {"pending": 1}).data["results"] == []
    WatchFiring.objects.filter(owner_user=kevin).update(expires_at=_later(60))
    auth_client.delete(f"{URL}{wid}")
    assert auth_client.get(FIRINGS, {"pending": 1}).data["results"] == []
    assert len(auth_client.get(FIRINGS).data["results"]) == 2


def test_the_command_runs_every_household_and_says_what_it_did(auth_client, household_a):
    _set(auth_client, "charge_over", {"amount_cents": 1_000})
    _txn(household_a, -2_000)
    out = io.StringIO()
    call_command("evaluate_watches", stdout=out)
    assert "evaluate_watches:" in out.getvalue() and "1 fired" in out.getvalue()


def test_the_crontab_runs_evaluate_watches_every_fifteen_minutes():
    crontab = Path(__file__).resolve().parents[2] / "deploy" / "crontab"
    assert (
        "7,22,37,52 * * * * python manage.py evaluate_watches" in crontab.read_text().splitlines()
    )


def test_sixty_days_on_a_grade_already_said_is_not_said_again(auth_client, kevin, household_a):
    made = auth_client.post(
        "/api/events",
        {
            "title": "EXAMPLE 101: Quiz 3",
            "kind": "task",
            "visibility": "private",
            "starts_at": "2026-11-01T05:00:00Z",
        },
        format="json",
    )
    _set(auth_client, "grade_posted", {})
    evaluate_household(household_a, _later())
    Event.objects.filter(pk=made.data["id"]).update(
        structured_meta={"canvas_assignment_id": 3, "score": 7, "points_possible": 10}
    )
    evaluate_household(household_a, _later(2))
    assert len(_firings(kevin, baseline=False)) == 1
    report = evaluate_household(household_a, timezone.now() + dt.timedelta(days=61))
    assert report.fired == 0
    assert len(_firings(kevin, baseline=False)) == 1


def test_a_canvas_line_states_an_absolute_time_never_hours_left(auth_client, kevin, household_a):
    made = auth_client.post(
        "/api/events",
        {
            "title": "EXAMPLE 101: Lab",
            "kind": "task",
            "visibility": "private",
            "starts_at": (timezone.now() + dt.timedelta(hours=3)).isoformat(),
        },
        format="json",
    )
    Event.objects.filter(pk=made.data["id"]).update(structured_meta={"canvas_assignment_id": 9})
    _set(auth_client, "canvas_due", {"hours": 24})
    evaluate_household(household_a, timezone.now())
    (firing,) = _firings(kevin)
    assert "hours" not in firing.facts and " is due " in firing.facts


def test_a_household_with_no_zone_hears_utc_said_as_utc(auth_client, kevin, household_a):
    household_a.timezone = None
    household_a.save(update_fields=["timezone"])
    made = auth_client.post(
        "/api/events",
        {
            "title": "EXAMPLE 101: Essay 2",
            "kind": "task",
            "visibility": "private",
            "starts_at": (timezone.now() + dt.timedelta(hours=2)).isoformat(),
        },
        format="json",
    )
    Event.objects.filter(pk=made.data["id"]).update(structured_meta={"canvas_assignment_id": 11})
    _set(auth_client, "canvas_due", {"hours": 24})
    evaluate_household(household_a, timezone.now())
    (firing,) = _firings(kevin)
    assert firing.facts.endswith(" UTC.")


def test_firings_from_before_a_pause_are_not_said_on_resume(auth_client, kevin, household_a):
    wid = _set(auth_client, "charge_over", {"amount_cents": 1_000})
    _txn(household_a, -2_000)
    evaluate_household(household_a, timezone.now())
    assert len(auth_client.get(FIRINGS, {"pending": 1}).data["results"]) == 1
    auth_client.patch(f"{URL}{wid}", {"paused": True}, format="json")
    auth_client.patch(f"{URL}{wid}", {"paused": False}, format="json")
    assert auth_client.get(FIRINGS, {"pending": 1}).data["results"] == []
