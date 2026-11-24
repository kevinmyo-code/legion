"""The owner role is scoped to the household the request acts on (2026-11-24).

A RED review read `IsHouseholdOwner.has_permission` as unscoped: it asked
`HouseholdMember.objects.filter(user=..., role=OWNER).exists()`, which is "is
this caller an owner SOMEWHERE", while every route it guards acts on
`household.tenancy.household_of(request)`. Those are different questions and
the class answered the wrong one.

**It was not exploitable, and the first test below is why.**
`HouseholdMember.user` is a `OneToOneField` (a unique index since migration
0001), so a user holds exactly one membership: the row the role came from was
always the row `household_of` read the household from, and no credential
reaches the engine naming any other household. The fix closes the gap at the
resolver rather than relying on that invariant two files away, so the tests
that prove it build the mismatch where the resolver reads it - there is no
request that can carry it.

The last group is the other half: every owner-only route still works for the
owner of their own household, and still refuses a plain member. A fix to a
fence that quietly locked Kevin out of renaming his own household would be a
worse bug than the one it closed.
"""

from __future__ import annotations

import pytest
from django.db import IntegrityError, transaction
from rest_framework.test import APIClient, APIRequestFactory

from household.households import InviteListCreateView
from household.models import Household, HouseholdMember, User
from household.permissions import IsHouseholdOwner, is_engine_owner, is_owner_of
from tests.conftest import _client_for

pytestmark = pytest.mark.django_db


def _plain_member(household, email: str):
    user = User.objects.create_user(email=email, password="correct horse battery")
    HouseholdMember.objects.create(user=user, household=household, role=HouseholdMember.MEMBER)
    return _client_for(user)


def _owner_request(user, acting_on: Household):
    """A request from `user` that the engine would scope to `acting_on`.

    `household_of` reads `User.household`, a `cached_property` over that
    user's one membership row, so priming it is how a mismatch between the
    role's household and the request's household is expressed at all: the
    database refuses the second membership row that would otherwise produce
    one (see the first test), and the wire has no field for a household.
    """
    request = APIRequestFactory().post("/api/households/me/invites", {}, format="json")
    request.user = user
    request.auth = None
    user.__dict__["household"] = acting_on
    return request


# =============================================================================
# The invariant the old check leaned on, written down and runnable
# =============================================================================


def test_a_user_cannot_hold_a_second_membership(household_a, household_b, household_user):
    """One membership per user, refused by the database and not by Python.

    This is the whole of why the unscoped check was never reachable through
    the API, so it is the one claim in this file worth being able to re-run.
    """
    with pytest.raises(IntegrityError), transaction.atomic():
        HouseholdMember.objects.create(
            user=household_user, household=household_b, role=HouseholdMember.MEMBER
        )
    assert HouseholdMember.objects.filter(user=household_user).count() == 1


# =============================================================================
# The owner check names the household the request acts on
# =============================================================================


def test_is_owner_of_answers_per_household(household_a, household_b, user_b):
    assert is_owner_of(user_b, household_b) is True
    assert is_owner_of(user_b, household_a) is False


def test_an_owner_of_another_household_fails_the_owner_check(household_a, household_b, user_b):
    """`user_b` owns household B. A request of theirs scoped to household A
    is refused the membership verbs: the role they hold is not a role in the
    household this request would change.

    Red before the fix (the old query found their household B owner row and
    returned True, whatever household the request went on to act on).
    """
    request = _owner_request(user_b, household_a)
    assert IsHouseholdOwner().has_permission(request, InviteListCreateView()) is False


def test_the_same_owner_passes_on_their_own_household(household_a, household_b, user_b):
    """The control for the test above: same user, same route, same role row,
    and the only difference is the household the request acts on."""
    request = _owner_request(user_b, household_b)
    assert IsHouseholdOwner().has_permission(request, InviteListCreateView()) is True


def test_an_owner_of_another_household_is_refused_on_every_owner_route(
    household_a, household_b, user_b, household_user
):
    """The same mismatch through the real URLs, so each SUBCLASS is covered
    and not only the base class: `IsOwnerForHouseholdPatch` overrides
    `has_permission`, `IsHouseholdOwnerForBank` and
    `IsHouseholdOwnerForSessions` carry their own refusal wording, and a
    regression in any of them would be invisible to the test above.

    `force_authenticate` puts THIS user instance on the request, so the
    primed household is the one `household_of` reads - the same seam, driven
    end to end.
    """
    client = APIClient()
    user_b.__dict__["household"] = household_a
    client.force_authenticate(user=user_b)
    routes = (
        ("patch", "/api/households/me", {"name": "Taken Over"}),
        ("get", "/api/households/me/invites", None),
        ("post", "/api/households/me/invites", {"creates_household": False}),
        ("delete", "/api/households/me/invites/whatever1234", None),
        ("delete", f"/api/households/me/members/{household_user.id}", None),
        ("post", "/api/ingest/plaid/link-token", None),
        ("post", "/api/ingest/plaid/update-link-token", None),
        ("post", "/api/ingest/plaid/exchange", {"public_token": "public-sandbox-fake"}),
        ("put", "/api/ingest/sessions/canvas", {"username": "fake", "password": "fake"}),
    )
    for verb, path, body in routes:
        call = getattr(client, verb)
        response = call(path, body, format="json") if body is not None else call(path)
        assert response.status_code == 403, (verb, path, response.status_code, response.data)

    household_a.refresh_from_db()
    assert household_a.name != "Taken Over"
    assert HouseholdMember.objects.filter(user=household_user).exists()


def test_a_member_of_the_acting_household_still_fails(household_a, household_user):
    """Scoping the query did not turn a member into an owner."""
    HouseholdMember.objects.filter(user=household_user).update(role=HouseholdMember.MEMBER)
    request = _owner_request(household_user, household_a)
    assert IsHouseholdOwner().has_permission(request, InviteListCreateView()) is False


# =============================================================================
# Engine-wide owner: a particular owner, not "an owner"
# =============================================================================


def test_only_the_engine_households_owner_is_an_engine_owner(
    household_a, household_b, household_user, user_b
):
    """`household_a` is the bootstrap household, the one that runs the engine
    (`tests/conftest.py`), so its owner is the engine's and household B's
    owner is not. The fence `releases`, the garage tool and the recruiter
    page's preview share."""
    assert is_engine_owner(household_user) is True
    assert is_engine_owner(user_b) is False


# =============================================================================
# Kevin is unaffected: every owner-only route, end to end
# =============================================================================

OWNER_ROUTES = (
    ("patch", "/api/households/me", {"name": "Still His House"}),
    ("get", "/api/households/me/invites", None),
    ("post", "/api/households/me/invites", {"creates_household": False}),
    ("post", "/api/ingest/plaid/link-token", None),
    ("put", "/api/ingest/sessions/canvas", {"username": "fake", "password": "fake"}),
)


@pytest.mark.parametrize(("verb", "path", "body"), OWNER_ROUTES)
def test_the_owner_of_this_household_is_not_refused(auth_client, verb, path, body):
    """`auth_client` is the owner of household A. None of these answers 403:
    whatever each route then does with the request, the owner fence let it
    through."""
    call = getattr(auth_client, verb)
    response = call(path, body, format="json") if body is not None else call(path)
    assert response.status_code != 403, (path, response.status_code, response.data)


@pytest.mark.parametrize(("verb", "path", "body"), OWNER_ROUTES)
def test_a_plain_member_of_this_household_is_still_refused(household_a, verb, path, body):
    client = _plain_member(household_a, "member@example.com")
    call = getattr(client, verb)
    response = call(path, body, format="json") if body is not None else call(path)
    assert response.status_code == 403, (path, response.status_code)


def test_the_owner_still_revokes_an_invite_and_removes_a_member(
    auth_client, household_a, household_user
):
    """The two owner-only routes the table above cannot reach: both need a
    row the owner has to mint first."""
    minted = auth_client.post(
        "/api/households/me/invites", {"creates_household": False}, format="json"
    )
    assert minted.status_code == 201, minted.data
    code = minted.data["code"]
    assert auth_client.delete(f"/api/households/me/invites/{code}").status_code == 204

    other = User.objects.create_user(email="parent@example.com", password="correct horse battery")
    HouseholdMember.objects.create(
        user=other, household=household_a, role=HouseholdMember.MEMBER
    )
    removed = auth_client.delete(f"/api/households/me/members/{other.id}")
    assert removed.status_code == 204, removed.data
    assert not HouseholdMember.objects.filter(user=other).exists()
