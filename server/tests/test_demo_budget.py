"""recruiter-demo ticket 04: the global daily demo budget, reserved before minting.

The ticket's own Verify line: concurrent mints at the edge of the cap never sum
past it (`test_concurrent_reservations_never_exceed_the_cap`).
"""

from __future__ import annotations

import threading
from datetime import UTC, datetime, timedelta

import pytest
from django.core.cache import cache
from django.db import connection
from rest_framework.test import APIClient

from demo import budget
from demo.models import DemoBudgetDay, DemoCode
from household.models import Household
from ingest.drive import Response
from tests.test_assistant import google  # noqa: F401 - fixture

NOON = datetime(2026, 11, 2, 12, 0, tzinfo=UTC)


@pytest.fixture(autouse=True)
def _fresh_throttle():
    cache.clear()
    yield
    cache.clear()


def _limits(
    settings, *, budget_usd="5", reserve="1", minute="0.20", text="0.01", minutes="5"
):
    """$1.00 a mint unless told otherwise (5 minutes at 20 cents), so the
    counting tests below read in whole dollars."""
    settings.LEGION_DEMO_DAILY_BUDGET_USD = budget_usd
    settings.LEGION_DEMO_TEXT_RESERVE_USD = reserve
    settings.LEGION_DEMO_COST_PER_VOICE_MINUTE_USD = minute
    settings.LEGION_DEMO_COST_PER_TEXT_CALL_USD = text
    settings.LEGION_DEMO_MAX_SESSION_MINUTES = minutes


def _reserved(day=None) -> int:
    row = DemoBudgetDay.objects.filter(day=day or NOON.date()).first()
    return row.reserved_cents if row else 0


# =============================================================================
# The arithmetic
# =============================================================================


def test_defaults_are_the_rulings_and_round_up_to_the_cent(settings):
    lim = budget.limits()
    assert lim.budget_cents == 500
    assert lim.text_reserve_cents == 0
    assert lim.voice_cap_cents == 500
    # Kevin, 2026-11-02: five minutes a conversation, and a mint reserves five.
    assert budget.max_session_minutes() == lim.session_minutes == 5
    assert lim.voice_mint_cents == 5 * 10 == 50
    _limits(settings, minute="0.001")
    assert budget.limits().voice_minute_cents == 1  # never rounds down to free


def test_the_reservation_follows_the_session_cap(settings):
    _limits(settings, minute="0.10", minutes="7")
    assert budget.limits().voice_mint_cents == 70


@pytest.mark.parametrize("bad", ["0", "-5", "five", "2.5", "31", ""])
def test_a_bad_session_cap_refuses_in_words(settings, bad):
    from django.core.exceptions import ImproperlyConfigured

    _limits(settings, minutes=bad)
    with pytest.raises(ImproperlyConfigured, match="LEGION_DEMO_MAX_SESSION_MINUTES"):
        budget.limits()


def test_a_bad_setting_refuses_in_words(settings):
    from django.core.exceptions import ImproperlyConfigured

    _limits(settings, budget_usd="five")
    with pytest.raises(ImproperlyConfigured, match="LEGION_DEMO_DAILY_BUDGET_USD"):
        budget.limits()


@pytest.mark.django_db
def test_voice_stops_at_budget_less_reserve_and_text_runs_to_the_full_budget(settings):
    _limits(settings)
    mints = [budget.reserve_voice_mint(NOON) for _ in range(5)]
    assert [m is not None for m in mints] == [True, True, True, True, False]
    assert _reserved() == 400
    texts = [budget.charge_text_call(NOON) for _ in range(101)]
    assert sum(t is not None for t in texts) == 100
    assert _reserved() == 500
    row = DemoBudgetDay.objects.get(day=NOON.date())
    assert (row.voice_mints, row.text_calls) == (4, 100)


@pytest.mark.django_db
def test_the_day_turns_at_utc_midnight(settings):
    _limits(settings, budget_usd="2", reserve="1")
    late = datetime(2026, 11, 2, 23, 59, 59, tzinfo=UTC)
    assert budget.reserve_voice_mint(late) is not None
    assert budget.reserve_voice_mint(late) is None
    # 23:59:59 in UTC-06:00 is already tomorrow in UTC.
    houston = late.astimezone(UTC) + timedelta(seconds=1)
    assert budget.reserve_voice_mint(houston) is not None
    assert _reserved(late.date()) == 100
    assert _reserved(houston.date()) == 100


@pytest.mark.django_db
def test_release_gives_a_failed_mint_back(settings):
    _limits(settings, budget_usd="2", reserve="1")
    held = budget.reserve_voice_mint(NOON)
    budget.release(held)
    assert _reserved() == 0
    assert budget.reserve_voice_mint(NOON) is not None


@pytest.mark.django_db
def test_a_mint_bigger_than_the_cap_never_fits(settings):
    _limits(settings, budget_usd="1.50", reserve="1")
    assert budget.reserve_voice_mint(NOON) is None
    assert _reserved() == 0


# =============================================================================
# Concurrency: the ticket's Verify line
# =============================================================================


@pytest.mark.django_db(transaction=True)
def test_concurrent_reservations_never_exceed_the_cap(settings):
    _limits(settings, budget_usd="4", reserve="1")  # voice cap 300: three mints fit
    DemoBudgetDay.objects.create(day=NOON.date())
    start = threading.Barrier(12)
    results: list[bool] = []
    lock = threading.Lock()

    def mint():
        try:
            start.wait()
            got = budget.reserve_voice_mint(NOON) is not None
            with lock:
                results.append(got)
        finally:
            connection.close()

    threads = [threading.Thread(target=mint) for _ in range(12)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert len(results) == 12
    assert sum(results) == 3
    assert _reserved() == 300


@pytest.mark.django_db(transaction=True)
def test_concurrent_first_reservations_of_a_day_make_one_row(settings):
    _limits(settings, budget_usd="4", reserve="1")
    start = threading.Barrier(6)

    def mint():
        try:
            start.wait()
            budget.reserve_voice_mint(NOON)
        finally:
            connection.close()

    threads = [threading.Thread(target=mint) for _ in range(6)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert DemoBudgetDay.objects.filter(day=NOON.date()).count() == 1
    assert _reserved() == 300


# =============================================================================
# At the door: POST /api/assistant/session
# =============================================================================


@pytest.fixture
def recruiter(db):
    Household.objects.create(name="Demo")
    _row, code = DemoCode.rotate()
    browser = APIClient()
    assert browser.post("/api/demo/enter", {"code": code}, format="json").status_code == 201
    return browser


def _today() -> int:
    return _reserved(datetime.now(UTC).date())


def test_a_recruiter_mint_reserves_and_the_cap_refuses_in_words(
    recruiter, google, settings  # noqa: F811
):
    _limits(settings, budget_usd="3", reserve="1")  # two mints
    for _ in range(2):
        assert recruiter.post("/api/assistant/session", {}, format="json").status_code == 200
    assert _today() == 200
    sent = len(google.sent)
    refused = recruiter.post("/api/assistant/session", {}, format="json")
    assert refused.status_code == 429
    assert refused.data == {"detail": "Today's demo budget is used up. Try again tomorrow."}
    assert len(google.sent) == sent  # no token was asked for
    from assistant.models import AssistantCall
    from engine_mcp.models import Outcome

    refusals = AssistantCall.objects.filter(kind=AssistantCall.SESSION, outcome=Outcome.REFUSED)
    assert refusals.exists()


def test_a_recruiter_token_dies_at_the_session_cap(recruiter, google, settings):  # noqa: F811
    """Kevin, 2026-11-02: five minutes, enforced by the token Google is asked
    for, not only by the page. A member's stays 30 (`test_assistant.py`)."""
    _limits(settings)
    before = datetime.now(UTC).replace(microsecond=0)
    response = recruiter.post("/api/assistant/session", {}, format="json")
    after = datetime.now(UTC) + timedelta(seconds=1)
    assert response.status_code == 200, response.data
    expire = datetime.fromisoformat(google.sent[0]["body"]["expireTime"])
    assert before + timedelta(minutes=5) <= expire <= after + timedelta(minutes=5)
    assert datetime.fromisoformat(response.data["expires_at"].replace("Z", "+00:00")) == expire
    settings.LEGION_DEMO_MAX_SESSION_MINUTES = "3"
    assert recruiter.post("/api/assistant/session", {}, format="json").status_code == 200
    third = datetime.fromisoformat(google.sent[1]["body"]["expireTime"])
    assert third <= datetime.now(UTC) + timedelta(minutes=3, seconds=1)


def test_a_mint_google_refuses_gives_its_reservation_back(recruiter, google, settings):  # noqa: F811
    _limits(settings)
    google.reply = Response(503, {}, b"{}")
    assert recruiter.post("/api/assistant/session", {}, format="json").status_code == 503
    assert _today() == 0


def test_members_are_never_counted_or_blocked(household_user, google, settings):  # noqa: F811
    _limits(settings, budget_usd="0", reserve="0")
    web = APIClient()
    web.force_login(household_user)
    for _ in range(3):
        assert web.post("/api/assistant/session", {}, format="json").status_code == 200
    assert not DemoBudgetDay.objects.exists()


# =============================================================================
# Kevin's tester code (Kevin, 2026-11-04: "a code that has no budget limit")
# =============================================================================


@pytest.fixture
def tester(db):
    Household.objects.create(name="Demo")
    DemoCode.rotate()  # the shared code, live beside it
    _row, code = DemoCode.rotate(unmetered=True)
    browser = APIClient()
    assert browser.post("/api/demo/enter", {"code": code}, format="json").status_code == 201
    return browser


def test_a_tester_visitor_is_never_counted_or_blocked(tester, google, settings):  # noqa: F811
    _limits(settings, budget_usd="0", reserve="0")
    for _ in range(3):
        response = tester.post("/api/assistant/session", {}, format="json")
        assert response.status_code == 200, response.data
    assert not DemoBudgetDay.objects.exists()
    # Still a visitor: the five-minute token, not a member's thirty.
    expire = datetime.fromisoformat(google.sent[0]["body"]["expireTime"])
    assert expire <= datetime.now(UTC) + timedelta(minutes=5, seconds=1)


def test_a_shared_code_visitor_is_still_counted_beside_a_tester(
    tester, recruiter, google, settings  # noqa: F811
):
    _limits(settings, budget_usd="0", reserve="0")
    assert recruiter.post("/api/assistant/session", {}, format="json").status_code == 429
    assert tester.post("/api/assistant/session", {}, format="json").status_code == 200


def test_the_two_codes_rotate_and_close_apart(db):
    shared, _ = DemoCode.rotate()
    test_code, _ = DemoCode.rotate(unmetered=True)
    DemoCode.rotate()
    test_code.refresh_from_db()
    assert test_code.revoked_at is None
    DemoCode.close_all(unmetered=True)
    test_code.refresh_from_db()
    assert test_code.revoked_at is not None
    assert DemoCode.live().filter(unmetered=False).count() == 1
    shared.refresh_from_db()
    assert shared.revoked_at is not None  # replaced by the second rotate
