"""`POST /api/demo/enter` and its commands (recruiter-demo ticket 02, ADR 0068).

Verify, from the ticket: wrong code, expired token, revoked code all refuse
in words; the per-IP limit on `/enter` holds.
"""

from __future__ import annotations

from datetime import timedelta
from io import StringIO

import pytest
from django.core.cache import cache
from django.core.management import call_command
from django.utils import timezone
from rest_framework.test import APIClient

from assistant.models import AssistantCall
from demo.models import DemoCode, DemoVisit, hash_demo_code
from demo.views import (
    DOOR_CLOSED,
    NO_DEMO_HOUSEHOLD,
    RETIRED_CODE,
    TOO_MANY,
    VISIT_LENGTH,
    WRONG_CODE,
)
from household.models import DeviceToken, Household, HouseholdMember, User
from household.permissions import RECRUITER_ROUTE_REFUSAL
from tests.test_assistant import _tool

pytestmark = pytest.mark.django_db

ENTER = "/api/demo/enter"


@pytest.fixture(autouse=True)
def _fresh_throttle():
    cache.clear()
    yield
    cache.clear()


@pytest.fixture
def demo_household(db):
    return Household.objects.create(name="Demo")


@pytest.fixture
def code(demo_household):
    _row, raw = DemoCode.rotate()
    return raw


def _browser() -> APIClient:
    client = APIClient(enforce_csrf_checks=True)
    client.get("/api/auth/csrf")
    return client


def _enter(client, ip="203.0.113.7", **body):
    return client.post(ENTER, body, format="json", REMOTE_ADDR=ip)


# =============================================================================
# The happy path
# =============================================================================


def test_the_code_makes_one_recruiter_visitor_in_the_demo_household(code, demo_household):
    client = _browser()
    before = timezone.now()
    response = _enter(client, code=code, ref="acme")
    assert response.status_code == 201, response.data
    assert response["Cache-Control"] == "no-store"
    assert set(response.data) == {"token", "expires_at", "ref"}
    assert response.data["ref"] == "acme"

    [visit] = DemoVisit.objects.all()
    assert visit.ref == "acme"
    user = visit.user
    member = HouseholdMember.objects.get(user=user)
    assert (member.household_id, member.role) == (demo_household.id, HouseholdMember.RECRUITER)
    assert not user.has_usable_password()
    assert not user.is_staff and not user.is_superuser

    token = DeviceToken.objects.get(user=user)
    assert visit.token_id == token.pk
    assert token.scope == DeviceToken.SCOPE_READ
    assert before + VISIT_LENGTH <= token.expires_at <= timezone.now() + VISIT_LENGTH
    assert token.key_hash != response.data["token"]


def test_the_raw_code_is_never_stored(code):
    assert not DemoCode.objects.filter(code_hash=code).exists()
    assert DemoCode.objects.filter(code_hash=hash_demo_code(code)).exists()
    assert code not in str(DemoCode.objects.get())


def test_each_entry_is_its_own_visitor(code):
    first = _enter(_browser(), code=code)
    second = _enter(_browser(), code=code)
    assert first.status_code == second.status_code == 201
    assert first.data["token"] != second.data["token"]
    assert DemoVisit.objects.values("user").distinct().count() == 2


def test_the_token_reads_and_the_browser_session_reaches_the_assistant(code):
    client = _browser()
    response = _enter(client, code=code)
    token_client = APIClient()
    token_client.credentials(HTTP_AUTHORIZATION=f"Token {response.data['token']}")
    assert token_client.get("/api/places/").data["detail"] == RECRUITER_ROUTE_REFUSAL

    client.credentials(HTTP_X_CSRFTOKEN=client.cookies["csrftoken"].value)
    result = _tool(client, "list_events", {})
    assert result.status_code == 200, result.data
    assert result.data["is_error"] is False
    [call] = AssistantCall.objects.all()
    assert call.user_id == DemoVisit.objects.get().user_id


def test_ref_is_optional_and_must_be_plain(code):
    assert _enter(_browser(), code=code).status_code == 201
    response = _enter(_browser(), code=code, ref="<script>")
    assert response.status_code == 400
    assert response.data["detail"].startswith("Nothing was created.")


# =============================================================================
# Refusals, in words, creating nothing
# =============================================================================


def _nothing_created():
    assert not DemoVisit.objects.exists()
    assert not HouseholdMember.objects.filter(role=HouseholdMember.RECRUITER).exists()


def test_a_wrong_code_refuses_in_words(code):
    response = _enter(_browser(), code="not-the-code")
    assert response.status_code == 403
    assert response.data["detail"] == WRONG_CODE
    _nothing_created()


def test_a_revoked_code_refuses_in_words(code):
    DemoCode.rotate()
    response = _enter(_browser(), code=code)
    assert response.status_code == 403
    assert response.data["detail"] == RETIRED_CODE
    _nothing_created()


def test_off_closes_the_door(code):
    out = StringIO()
    call_command("demo_code", "off", stdout=out)
    assert "closed" in out.getvalue()
    response = _enter(_browser(), code=code)
    assert response.status_code == 403
    assert response.data["detail"] == DOOR_CLOSED
    _nothing_created()


def test_no_demo_household_refuses_in_words(db):
    _row, raw = DemoCode.rotate()
    response = _enter(_browser(), code=raw)
    assert response.status_code == 503
    assert response.data["detail"] == NO_DEMO_HOUSEHOLD
    _nothing_created()
    assert not User.objects.filter(email__endswith="@demo.invalid").exists()


def test_an_expired_visitor_token_refuses_in_words(code):
    response = _enter(_browser(), code=code)
    DeviceToken.objects.update(expires_at=timezone.now() - timedelta(seconds=1))
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {response.data['token']}")
    refused = client.get("/api/auth/me")
    assert refused.status_code == 401
    assert "expired" in str(refused.data["detail"])


def test_the_per_ip_limit_holds(code, monkeypatch):
    from demo.views import DemoEnterThrottle

    rates = {**DemoEnterThrottle.THROTTLE_RATES, "demo_enter": "3/hour"}
    monkeypatch.setattr(DemoEnterThrottle, "THROTTLE_RATES", rates)
    for _ in range(3):
        assert _enter(_browser(), code="wrong", ip="198.51.100.1").status_code == 403
    limited = _enter(_browser(), code=code, ip="198.51.100.1")
    assert limited.status_code == 429
    assert str(limited.data["detail"]).startswith(TOO_MANY)
    # Another address still gets in.
    assert _enter(_browser(), code=code, ip="198.51.100.2").status_code == 201
    assert DemoVisit.objects.count() == 1


# =============================================================================
# The commands
# =============================================================================


def test_rotate_prints_a_working_code_once(demo_household):
    out = StringIO()
    call_command("demo_code", "rotate", stdout=out)
    new_code = out.getvalue().strip().splitlines()[-1]
    assert _enter(_browser(), code=new_code).status_code == 201
    call_command("demo_code", "rotate", stdout=StringIO())
    assert _enter(_browser(), code=new_code).data["detail"] == RETIRED_CODE
    assert DemoCode.live().count() == 1


def test_prune_deletes_expired_visitors_and_keeps_the_visit(code, household_user):
    live = _enter(_browser(), code=code, ref="live")
    gone = _enter(_browser(), code=code, ref="gone")
    assert live.status_code == gone.status_code == 201
    DemoVisit.objects.filter(ref="gone").update(expires_at=timezone.now() - timedelta(minutes=1))

    out = StringIO()
    call_command("prune_demo_visitors", "--dry-run", stdout=out)
    assert "Would delete 1" in out.getvalue()
    assert DemoVisit.objects.filter(user__isnull=False).count() == 2

    call_command("prune_demo_visitors", stdout=StringIO())
    gone_visit = DemoVisit.objects.get(ref="gone")
    assert gone_visit.user_id is None and gone_visit.token_id is None
    assert DemoVisit.objects.get(ref="live").user_id is not None
    assert HouseholdMember.objects.filter(role=HouseholdMember.RECRUITER).count() == 1
    assert User.objects.filter(pk=household_user.pk).exists()
