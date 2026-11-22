"""Spare visit households (demo-enter-slow, 2026-11-22; `demo.spares`).

`/api/demo/enter` claims a household seeded ahead of time instead of seeding one inline. What
this file pins:

1. A claim hands out a ready spare, and the visitor's household is then exactly what an inline
   seed makes (row for row, bar keys and the moment of writing).
2. An empty pool, or the pool switched off, still lets the visitor in: the inline seed is the
   fallback, and only a visit that committed starts a refill, after the commit.
3. A spare seeded for another day, by another seed, or too long ago is never handed out, and
   the refill and the hourly prune delete it whole.
4. Concurrent claims never share a household.
5. A visit that fails after its claim puts the spare back: nothing was created.
"""

from __future__ import annotations

import datetime as dt
import threading
from io import StringIO

import pytest
from django.core.cache import cache
from django.core.management import call_command
from django.db import connection, connections, transaction
from django.utils import timezone
from rest_framework.test import APIClient

from demo import spares
from demo.models import DemoCode, DemoVisit, SpareVisitHousehold
from demo.views import SEED_FAILED
from household.models import Household, HouseholdMember
from tests.test_demo_household_seed import _counts, content_of

ENTER = "/api/demo/enter"


@pytest.fixture
def pool(settings, db):
    cache.clear()
    settings.LEGION_DEMO_SPARE_POOL = 2
    yield
    cache.clear()


@pytest.fixture
def code(pool):
    _row, raw = DemoCode.rotate()
    return raw


def _enter(code: str, ref: str = ""):
    browser = APIClient(enforce_csrf_checks=True)
    browser.get("/api/auth/csrf")
    return browser.post(ENTER, {"code": code, "ref": ref}, format="json")


def _visit_household(ref: str) -> Household:
    return DemoVisit.objects.get(ref=ref).household


# =============================================================================
# 1. The claim
# =============================================================================


@pytest.mark.django_db
def test_enter_claims_a_ready_spare_and_the_marker_goes_with_it(code):
    assert spares.refill() == {"discarded": 0, "seeded": 2, "busy": 0}
    waiting = set(SpareVisitHousehold.objects.values_list("household_id", flat=True))
    before = timezone.now()

    response = _enter(code, "acme")

    assert response.status_code == 201, response.data
    household = _visit_household("acme")
    assert household.id in waiting
    assert SpareVisitHousehold.objects.count() == 1
    assert not SpareVisitHousehold.objects.filter(household=household).exists()
    [member] = HouseholdMember.objects.filter(household=household)
    assert member.role == HouseholdMember.RECRUITER
    assert household.is_demo_visit and household.name.startswith("Demo visit ")
    household.refresh_from_db()
    assert household.created_at >= before


@pytest.mark.django_db
def test_a_claimed_spare_is_the_household_an_inline_seed_makes(code, settings):
    since = timezone.now() - dt.timedelta(seconds=1)
    spares.refill(size=1)
    assert _enter(code, "spare").status_code == 201
    settings.LEGION_DEMO_SPARE_POOL = 0  # the next visit seeds inline
    assert _enter(code, "inline").status_code == 201
    claimed, inline = _visit_household("spare"), _visit_household("inline")
    assert SpareVisitHousehold.objects.count() == 0

    assert _counts(inside=True, household=claimed) == _counts(inside=True, household=inline)
    assert content_of(claimed, since=since) == content_of(inline, since=since)
    assert (claimed.timezone, claimed.is_demo_visit) == (inline.timezone, inline.is_demo_visit)
    assert claimed.created_by_id is None and inline.created_by_id is None


# =============================================================================
# 2. Empty pool, pool off, and the refill trigger
# =============================================================================


@pytest.mark.django_db
def test_an_empty_pool_seeds_inline_and_starts_one_refill_after_the_commit(
    code, monkeypatch, django_capture_on_commit_callbacks
):
    spawned = []
    monkeypatch.setattr(spares, "_spawn", spawned.append)
    with django_capture_on_commit_callbacks(execute=True):
        response = _enter(code, "empty")
    assert response.status_code == 201, response.data
    assert _visit_household("empty").is_demo_visit
    assert spawned == [spares._refill_on_thread]


@pytest.mark.django_db
def test_a_refused_entry_starts_no_refill(code, monkeypatch, django_capture_on_commit_callbacks):
    spawned = []
    monkeypatch.setattr(spares, "_spawn", spawned.append)
    with django_capture_on_commit_callbacks(execute=True):
        assert _enter("not-the-code").status_code == 403
    assert spawned == []
    assert not Household.objects.filter(is_demo_visit=True).exists()


@pytest.mark.django_db
def test_pool_off_hands_out_nothing_and_the_refill_deletes_every_spare(code, settings):
    spares.refill(size=1)
    [spare] = SpareVisitHousehold.objects.all()
    settings.LEGION_DEMO_SPARE_POOL = 0
    assert _enter(code, "off").status_code == 201
    assert _visit_household("off").id != spare.household_id
    assert spares.refill() == {"discarded": 1, "seeded": 0, "busy": 0}
    assert not Household.objects.filter(pk=spare.household_id).exists()


# =============================================================================
# 3. Stale spares
# =============================================================================


def _yesterday(spare):
    SpareVisitHousehold.objects.filter(pk=spare.pk).update(
        seeded_for=spare.seeded_for - dt.timedelta(days=1)
    )


def _old_seed(spare):
    SpareVisitHousehold.objects.filter(pk=spare.pk).update(seed_version="an older seed")


def _too_old(spare):
    SpareVisitHousehold.objects.filter(pk=spare.pk).update(
        created_at=timezone.now() - spares.MAX_AGE - dt.timedelta(minutes=1)
    )


def _last_month(spare):
    SpareVisitHousehold.objects.filter(pk=spare.pk).update(
        seeded_for=spare.seeded_for.replace(day=1) - dt.timedelta(days=1)
    )


@pytest.mark.django_db
@pytest.mark.parametrize("staleness", [_yesterday, _last_month, _old_seed, _too_old])
def test_a_stale_spare_is_never_handed_out_and_is_deleted_whole(code, staleness):
    spares.refill(size=1)
    [spare] = SpareVisitHousehold.objects.all()
    staleness(spare)

    assert _enter(code, "visit").status_code == 201
    assert _visit_household("visit").id != spare.household_id
    assert SpareVisitHousehold.objects.filter(pk=spare.pk).exists()

    assert spares.refill(size=1) == {"discarded": 1, "seeded": 1, "busy": 0}
    assert not Household.objects.filter(pk=spare.household_id).exists()
    assert set(_counts(inside=True, household=Household(id=spare.household_id)).values()) == {0}


@pytest.mark.django_db
def test_a_spare_from_the_previous_utc_day_is_stale_even_on_the_same_local_day(code):
    """The demo's zone is behind UTC: 20:00 there is already tomorrow in UTC. A spare is handed
    out only when both days match the moment of the claim."""
    spares.refill(size=1)
    [spare] = SpareVisitHousehold.objects.all()
    now = timezone.now()
    midnight_utc = now.astimezone(dt.UTC).replace(hour=0, minute=0, second=0, microsecond=0)
    SpareVisitHousehold.objects.filter(pk=spare.pk).update(
        created_at=midnight_utc - dt.timedelta(minutes=1)
    )
    with transaction.atomic():
        assert spares.claim() is None


# =============================================================================
# 4. Prune
# =============================================================================


@pytest.mark.django_db
def test_prune_deletes_stale_spares_whole_and_tops_the_pool_up(code, household_user):
    from household.demo_seed import seed_household

    kevin = household_user.household
    seed_household(kevin)
    kevin_before = _counts(inside=True, household=kevin)
    spares.refill(size=2)
    stale, fresh = SpareVisitHousehold.objects.order_by("created_at", "id")
    _yesterday(stale)

    dry = StringIO()
    call_command("prune_demo_visitors", "--dry-run", stdout=dry)
    assert "Would discard 1 spare visit household(s)" in dry.getvalue()
    assert SpareVisitHousehold.objects.count() == 2

    out = StringIO()
    call_command("prune_demo_visitors", stdout=out)
    assert "Spare visit households: 1 discarded, 1 seeded (pool 2)." in out.getvalue()
    assert not Household.objects.filter(pk=stale.household_id).exists()
    assert Household.objects.filter(pk=fresh.household_id).exists()
    assert SpareVisitHousehold.objects.count() == 2
    assert _counts(inside=True, household=kevin) == kevin_before


# =============================================================================
# 5. A failed visit puts the spare back
# =============================================================================


@pytest.mark.django_db
def test_a_visit_that_fails_after_its_claim_puts_the_spare_back(code, monkeypatch):
    spares.refill(size=1)
    [spare] = SpareVisitHousehold.objects.all()

    def _boom(*args, **kwargs):
        raise RuntimeError("visitor broke")

    monkeypatch.setattr("demo.views._make_visitor", _boom)
    response = _enter(code, "broken")
    assert response.status_code == 503
    assert response.data["detail"] == SEED_FAILED
    assert SpareVisitHousehold.objects.filter(pk=spare.pk).exists()
    assert not HouseholdMember.objects.filter(household_id=spare.household_id).exists()
    assert not DemoVisit.objects.exists()


# =============================================================================
# 6. Concurrent claims
# =============================================================================


@pytest.mark.django_db(transaction=True)
def test_concurrent_claims_never_share_a_household(settings):
    """Three claims at once over two spares, each holding its lock until all three have
    claimed: two distinct households and one empty hand, never the same household twice."""
    settings.LEGION_DEMO_SPARE_POOL = 2
    spares.refill()
    assert SpareVisitHousehold.objects.count() == 2
    claimed_all = threading.Barrier(3, timeout=30)
    got: list = []
    errors: list = []

    def claim():
        try:
            with transaction.atomic():
                household = spares.claim()
                got.append(household.id if household else None)
                claimed_all.wait()
        except Exception as exc:  # noqa: BLE001 - reported below
            errors.append(exc)
        finally:
            connections.close_all()

    threads = [threading.Thread(target=claim) for _ in range(3)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(60)
    assert errors == []
    households = [h for h in got if h is not None]
    assert len(got) == 3 and got.count(None) == 1
    assert len(set(households)) == 2
    assert SpareVisitHousehold.objects.count() == 0
    connection.close()
