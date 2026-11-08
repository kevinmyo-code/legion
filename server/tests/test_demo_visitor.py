"""Who a demo visit was (`demo/visitor.py`, Kevin 2026-11-06: "differentiate myself and others").

- `by_owner`: set from a real member's signed cookie or live session, never by a recruiter.
- Edge details: stored only when the Worker's secret matches; the IP only as an HMAC.
- `prune_demo_visitors` clears the details after 30 days and keeps `by_owner`.
"""

from __future__ import annotations

from datetime import timedelta
from io import StringIO

import pytest
from django.core import signing
from django.core.cache import cache
from django.core.management import call_command
from django.utils import timezone
from rest_framework.test import APIClient

from demo.models import DemoCode, DemoVisit
from demo.visitor import OWNER_COOKIE, OWNER_COOKIE_PATH, OWNER_COOKIE_SALT, hash_ip
from household.models import Household, HouseholdMember, User

pytestmark = pytest.mark.django_db

ENTER = "/api/demo/enter"
MARK = "/api/demo/mark-owner-device"
SECRET = "test-edge-secret-not-a-real-one"
IP = "203.0.113.7"
EDGE = {
    "HTTP_X_EDGE_AUTH": SECRET,
    "HTTP_X_LEGION_CLIENT_IP": "198.51.100.23",
    "HTTP_X_LEGION_COUNTRY": "US",
    "HTTP_X_LEGION_REGION": "Massachusetts",
    "HTTP_X_LEGION_CITY": "S%C3%A3o%20Paulo",
    "HTTP_X_LEGION_ASN": "64500",
    "HTTP_X_LEGION_AS_ORG": "Example%20Corp",
    "HTTP_USER_AGENT": "Mozilla/5.0 test",
}


@pytest.fixture(autouse=True)
def _fresh(settings):
    settings.LEGION_EDGE_SECRET = SECRET
    cache.clear()
    yield
    cache.clear()


@pytest.fixture
def code(db):
    Household.objects.create(name="Demo")
    _row, raw = DemoCode.rotate()
    return raw


def _browser() -> APIClient:
    client = APIClient(enforce_csrf_checks=True)
    client.get("/api/auth/csrf")
    return client


def _enter(client, code, **meta):
    return client.post(ENTER, {"code": code, "ref": "acme"}, format="json", REMOTE_ADDR=IP, **meta)


def _sign_in(client, email="kevin@example.com"):
    response = client.post(
        "/api/auth/session/login",
        {"email": email, "password": "correct horse battery"},
        format="json",
    )
    assert response.status_code == 200, response.data
    return response


# =============================================================================
# Owner marking
# =============================================================================


def test_a_stranger_is_not_the_owner(code):
    assert _enter(_browser(), code).status_code == 201
    assert DemoVisit.objects.get().by_owner is False


def test_signing_in_marks_the_browser_and_a_later_visit_is_the_owners(code, household_user):
    client = _browser()
    response = _sign_in(client)
    cookie = response.cookies[OWNER_COOKIE]
    assert cookie["httponly"] and cookie["secure"]
    assert cookie["path"] == OWNER_COOKIE_PATH
    client.post("/api/auth/session/logout", HTTP_X_CSRFTOKEN=client.cookies["csrftoken"].value)

    assert _enter(client, code).status_code == 201
    assert DemoVisit.objects.get().by_owner is True


def test_an_existing_session_is_marked_by_the_apps_me_call(code, household_user):
    client = _browser()
    _sign_in(client)
    client.cookies.pop(OWNER_COOKIE)
    response = client.get("/api/auth/me")
    assert response.status_code == 200
    assert OWNER_COOKIE in response.cookies


def test_a_live_member_session_on_enter_counts_even_without_the_cookie(code, household_user):
    client = _browser()
    _sign_in(client)
    client.cookies.pop(OWNER_COOKIE)
    response = _enter(client, code)
    assert response.status_code == 201
    assert DemoVisit.objects.get().by_owner is True
    assert OWNER_COOKIE in response.cookies


def test_a_recruiters_own_session_never_counts_and_never_sets_the_cookie(code):
    client = _browser()
    first = _enter(client, code)
    assert OWNER_COOKIE not in first.cookies
    # The browser now holds the first visitor's session; enter again on it.
    second = _enter(client, code)
    assert second.status_code == 201
    assert not DemoVisit.objects.filter(by_owner=True).exists()
    assert OWNER_COOKIE not in second.cookies
    assert client.get("/api/auth/me").cookies.get(OWNER_COOKIE) is None


def test_a_recruiter_cannot_mark_their_device(code):
    client = _browser()
    _enter(client, code)
    response = client.post(MARK, HTTP_X_CSRFTOKEN=client.cookies["csrftoken"].value)
    assert response.status_code == 403
    assert OWNER_COOKIE not in response.cookies


def test_a_member_marks_their_device_explicitly(code, auth_client):
    response = auth_client.post(MARK)
    assert response.status_code == 204
    assert OWNER_COOKIE in response.cookies


def test_a_forged_or_stale_cookie_counts_for_nothing(code, household_user):
    client = _browser()
    client.cookies[OWNER_COOKIE] = "forged"
    _enter(client, code)
    # A properly signed cookie whose user is no longer a member of a real household.
    gone = User.objects.create_user(email="gone@example.com")
    client = _browser()
    client.cookies[OWNER_COOKIE] = signing.dumps({"u": str(gone.pk)}, salt=OWNER_COOKIE_SALT)
    _enter(client, code)
    # And a signed cookie for a recruiter visitor.
    visitor = DemoVisit.objects.exclude(user=None).first().user
    client = _browser()
    client.cookies[OWNER_COOKIE] = signing.dumps({"u": str(visitor.pk)}, salt=OWNER_COOKIE_SALT)
    _enter(client, code)
    assert DemoVisit.objects.count() == 3
    assert not DemoVisit.objects.filter(by_owner=True).exists()


def test_a_member_of_a_demo_visit_household_is_not_real(code, household_user):
    household = Household.objects.create(name="x", is_demo_visit=True)
    HouseholdMember.objects.filter(user=household_user).update(household=household)
    client = _browser()
    response = _sign_in(client)
    assert OWNER_COOKIE not in response.cookies


# =============================================================================
# Edge details
# =============================================================================


def test_with_the_edge_secret_the_visit_stores_place_network_and_a_hashed_ip(code):
    assert _enter(_browser(), code, **EDGE).status_code == 201
    visit = DemoVisit.objects.get()
    assert visit.ip_hash == hash_ip("198.51.100.23")
    assert len(visit.ip_hash) == 64 and "198.51" not in visit.ip_hash
    assert (visit.country, visit.region, visit.city) == ("US", "Massachusetts", "São Paulo")
    assert (visit.asn, visit.as_org) == (64500, "Example Corp")
    assert visit.user_agent == "Mozilla/5.0 test"


@pytest.mark.parametrize("auth", [None, "wrong"])
def test_without_the_right_secret_nothing_from_the_edge_is_stored(code, auth):
    meta = dict(EDGE)
    if auth is None:
        meta.pop("HTTP_X_EDGE_AUTH")
    else:
        meta["HTTP_X_EDGE_AUTH"] = auth
    assert _enter(_browser(), code, **meta).status_code == 201
    visit = DemoVisit.objects.get()
    assert (visit.ip_hash, visit.country, visit.city, visit.as_org) == ("", "", "", "")
    assert visit.asn is None
    assert visit.user_agent == "Mozilla/5.0 test"


def test_an_unset_secret_on_the_engine_trusts_nothing(code, settings):
    settings.LEGION_EDGE_SECRET = ""
    meta = dict(EDGE, HTTP_X_EDGE_AUTH="")
    assert _enter(_browser(), code, **meta).status_code == 201
    assert DemoVisit.objects.get().ip_hash == ""


def test_the_same_ip_hashes_the_same_and_another_differs():
    assert hash_ip("198.51.100.23") == hash_ip("198.51.100.23")
    assert hash_ip("198.51.100.23") != hash_ip("198.51.100.24")


# =============================================================================
# Retention
# =============================================================================


def test_prune_clears_visitor_details_after_30_days_and_keeps_the_rest(code):
    _enter(_browser(), code, **EDGE)
    _enter(_browser(), code, **EDGE)
    old, fresh = DemoVisit.objects.order_by("pk")
    DemoVisit.objects.filter(pk=old.pk).update(
        created_at=timezone.now() - timedelta(days=31), by_owner=True
    )

    out = StringIO()
    call_command("prune_demo_visitors", "--dry-run", stdout=out)
    assert "clear the visitor details of 1 visit(s)" in out.getvalue()
    old.refresh_from_db()
    assert old.ip_hash

    out = StringIO()
    call_command("prune_demo_visitors", stdout=out)
    assert "cleared the visitor details of 1 visit(s)" in out.getvalue()
    old.refresh_from_db()
    fresh.refresh_from_db()
    assert (old.ip_hash, old.country, old.region, old.city, old.as_org, old.user_agent) == (
        "", "", "", "", "", ""
    )
    assert old.asn is None
    assert old.by_owner is True and old.ref == "acme"
    assert fresh.ip_hash and fresh.city == "São Paulo" and fresh.asn == 64500
