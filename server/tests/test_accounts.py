"""Signup, invite codes, the household resource, member removal and a
member's own device list - web-and-households ticket 03, over HTTP.

**The session/CSRF half of ticket 03 is NOT here.** It landed first and is
covered by `tests/test_auth_endpoints.py`; this file is everything built on
top of it. `tests/test_add_household_member.py` still owns that command.

Three things this file is deliberately strict about, because each is a rule
rather than a behaviour:

- **A refusal changed nothing.** Every 400 below asserts the absence of the
  thing that was not created - no `User`, no membership, an unspent invite -
  not merely the status code. CLAUDE.md section 7's outcome-verb rule is
  about not claiming an outcome you did not have; the inverse is a refusal
  that quietly half-happened.
- **Owner is the only role and it governs membership only** (ADR 0045). The
  owner-versus-member tests below cover every owner-only route, and there is
  deliberately no test asserting an owner sees more DATA, because they do
  not.
- **A new household is invisible to the old one.** The last section signs
  somebody up into a household founded by a create-code and then asks them
  for household A's rows. `tests/test_tenancy.py` proves the scoping in
  general; this proves the door ticket 03 opened does not walk around it.
"""
from __future__ import annotations

import datetime

import pytest
from django.core.cache import cache
from django.core.management import CommandError, call_command
from django.test import override_settings
from django.utils import timezone
from rest_framework.test import APIClient

from household.models import DeviceToken, Household, HouseholdMember, Invite, User

pytestmark = pytest.mark.django_db

EPOCH = "1970-01-01T00:00:00Z"
GOOD_PASSWORD = "correct horse battery"


@pytest.fixture(autouse=True)
def _reset_throttles():
    """`ScopedRateThrottle` counts in Django's cache, which LocMemCache never
    clears between tests. Signup and the invite preview share a `signup`
    scope of 5/min and this file calls both many times, so without this a
    later test starts pre-throttled by an earlier one and fails with a 429
    that has nothing to do with what it was checking."""
    cache.clear()
    yield
    cache.clear()


def make_member(household, email: str, role: str = HouseholdMember.MEMBER) -> User:
    user = User.objects.create_user(email=email, password=GOOD_PASSWORD)
    HouseholdMember.objects.create(user=user, household=household, role=role)
    return user


def client_for(user) -> APIClient:
    _token, raw_key = DeviceToken.issue(user, "Test client")
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw_key}")
    return client


@pytest.fixture
def owner(household_user):
    """`household_user` from conftest is already an OWNER of household A -
    aliased here so an owner-versus-member test reads as a pair."""
    return household_user


@pytest.fixture
def owner_client(auth_client):
    return auth_client


@pytest.fixture
def member(household_a):
    return make_member(household_a, "member@example.com")


@pytest.fixture
def member_client(member):
    return client_for(member)


def mint(client, **body):
    """POST an invite and return its response data. Defaults are the
    endpoint's own, so a test that does not care about `max_uses` does not
    have to state one."""
    response = client.post("/api/households/me/invites", body, format="json")
    assert response.status_code == 201, response.data
    return response.data


def signup(client=None, **body):
    client = client or APIClient()
    payload = {"device_name": "Her iPhone", **body}
    return client.post("/api/auth/signup", payload, format="json")


# =============================================================================
# Signup with a code that JOINS a household
# =============================================================================


def test_signup_with_a_join_code_lands_in_that_household(owner_client, household_a):
    invite = mint(owner_client)

    response = signup(
        email="wife@example.com",
        password=GOOD_PASSWORD,
        name="The Wife",
        invite_code=invite["code"],
    )

    assert response.status_code == 201, response.data
    user = User.objects.get(email="wife@example.com")
    assert str(response.data["user_id"]) == str(user.id)
    assert user.first_name == "The Wife"

    membership = HouseholdMember.objects.get(user=user)
    assert membership.household_id == household_a.id
    # A join code makes a MEMBER, never an owner: the household already has
    # one, and ADR 0045's role governs who may invite and remove.
    assert membership.role == HouseholdMember.MEMBER
    assert Household.objects.count() == 1


def test_the_token_signup_returns_works_immediately(owner_client):
    invite = mint(owner_client)

    response = signup(
        email="wife@example.com", password=GOOD_PASSWORD, invite_code=invite["code"]
    )

    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {response.data['token']}")
    me = client.get("/api/auth/me")
    assert me.status_code == 200
    assert me.data["email"] == "wife@example.com"
    assert me.data["device_name"] == "Her iPhone"


def test_signup_spends_one_use_of_the_code(owner_client):
    invite = mint(owner_client, max_uses=2)

    signup(email="wife@example.com", password=GOOD_PASSWORD, invite_code=invite["code"])

    assert Invite.objects.get(code=invite["code"]).used_count == 1


# =============================================================================
# Signup with a code that CREATES a household
# =============================================================================


def test_a_create_code_founds_a_household_then_later_uses_join_it(owner_client, household_a):
    """ADR 0045's "that is how one code reaches both parents", end to end."""
    invite = mint(owner_client, creates_household=True, max_uses=2)

    first = signup(
        email="dad@example.com",
        password=GOOD_PASSWORD,
        invite_code=invite["code"],
        household_name="The Parents",
    )
    assert first.status_code == 201, first.data

    dad = User.objects.get(email="dad@example.com")
    dad_membership = HouseholdMember.objects.get(user=dad)
    founded = dad_membership.household
    assert founded.name == "The Parents"
    assert founded.id != household_a.id
    # The founder owns what they founded - otherwise the new household has no
    # owner at all and nobody who can ever invite the second parent.
    assert dad_membership.role == HouseholdMember.OWNER

    second = signup(
        email="mum@example.com",
        password=GOOD_PASSWORD,
        invite_code=invite["code"],
        # No `household_name`: the second use joins, it does not found.
    )
    assert second.status_code == 201, second.data

    mum_membership = HouseholdMember.objects.get(user__email="mum@example.com")
    assert mum_membership.household_id == founded.id
    assert mum_membership.role == HouseholdMember.MEMBER
    assert Invite.objects.get(code=invite["code"]).used_count == 2


def test_a_create_code_without_a_household_name_is_refused(owner_client):
    invite = mint(owner_client, creates_household=True)

    response = signup(
        email="dad@example.com", password=GOOD_PASSWORD, invite_code=invite["code"]
    )

    assert response.status_code == 400
    assert "household_name" in response.data["detail"]
    assert not User.objects.filter(email="dad@example.com").exists()
    assert Invite.objects.get(code=invite["code"]).used_count == 0


def test_a_create_code_is_unattached_until_its_first_use(owner_client):
    invite = mint(owner_client, creates_household=True)

    assert Invite.objects.get(code=invite["code"]).household_id is None


# =============================================================================
# Codes that cannot be used
# =============================================================================


def test_a_spent_code_is_refused_in_words(owner_client):
    invite = mint(owner_client, max_uses=1)
    first = signup(email="one@example.com", password=GOOD_PASSWORD, invite_code=invite["code"])
    assert first.status_code == 201

    second = signup(email="two@example.com", password=GOOD_PASSWORD, invite_code=invite["code"])

    assert second.status_code == 400
    assert "already been used" in second.data["detail"]
    assert "No account was created" in second.data["detail"]
    assert not User.objects.filter(email="two@example.com").exists()


def test_an_expired_code_is_refused_in_words(owner_client):
    invite = mint(owner_client)
    Invite.objects.filter(code=invite["code"]).update(
        expires_at=timezone.now() - datetime.timedelta(seconds=1)
    )

    response = signup(
        email="late@example.com", password=GOOD_PASSWORD, invite_code=invite["code"]
    )

    assert response.status_code == 400
    assert "expired" in response.data["detail"]
    assert not User.objects.filter(email="late@example.com").exists()


def test_a_revoked_code_is_refused_in_words(owner_client):
    invite = mint(owner_client)
    revoke = owner_client.delete(f"/api/households/me/invites/{invite['code']}")
    assert revoke.status_code == 204

    response = signup(
        email="nope@example.com", password=GOOD_PASSWORD, invite_code=invite["code"]
    )

    assert response.status_code == 400
    assert "revoked" in response.data["detail"]
    assert not User.objects.filter(email="nope@example.com").exists()


def test_an_unknown_code_is_refused_and_creates_nothing():
    response = signup(
        email="stranger@example.com", password=GOOD_PASSWORD, invite_code="nosuchcode1"
    )

    assert response.status_code == 400
    assert "no invite with that code" in response.data["detail"].lower()
    assert not User.objects.filter(email="stranger@example.com").exists()


def test_an_email_that_already_exists_is_refused_and_spends_nothing(owner_client, owner):
    invite = mint(owner_client)

    response = signup(email=owner.email, password=GOOD_PASSWORD, invite_code=invite["code"])

    assert response.status_code == 400
    assert "already exists" in response.data["detail"]
    assert Invite.objects.get(code=invite["code"]).used_count == 0
    assert User.objects.filter(email__iexact=owner.email).count() == 1


def test_a_weak_password_is_refused_and_spends_nothing(owner_client):
    invite = mint(owner_client)

    response = signup(email="weak@example.com", password="123", invite_code=invite["code"])

    assert response.status_code == 400
    assert "password" in response.data
    assert not User.objects.filter(email="weak@example.com").exists()
    assert Invite.objects.get(code=invite["code"]).used_count == 0


def test_signup_is_throttled(owner_client):
    """Five a minute per IP, shared with the invite preview. The sixth
    attempt is refused whatever it carries."""
    for _attempt in range(5):
        signup(email="x@example.com", password=GOOD_PASSWORD, invite_code="nosuchcode1")

    sixth = signup(email="x@example.com", password=GOOD_PASSWORD, invite_code="nosuchcode1")

    assert sixth.status_code == 429


# =============================================================================
# LEGION_OPEN_SIGNUP
# =============================================================================


def test_signup_without_a_code_is_refused_by_default(household_a):
    """Invite-only is the default and the switch is off in this suite - so
    this is the shipped behaviour, not an arranged one."""
    response = signup(
        email="stranger@example.com", password=GOOD_PASSWORD, household_name="Theirs"
    )

    assert response.status_code == 400
    assert "invite-only" in response.data["detail"]
    assert not User.objects.filter(email="stranger@example.com").exists()
    assert Household.objects.count() == 1


@override_settings(LEGION_OPEN_SIGNUP=True)
def test_open_signup_founds_a_household_without_a_code(household_a):
    response = signup(
        email="stranger@example.com", password=GOOD_PASSWORD, household_name="Theirs"
    )

    assert response.status_code == 201, response.data
    membership = HouseholdMember.objects.get(user__email="stranger@example.com")
    assert membership.household.name == "Theirs"
    assert membership.household_id != household_a.id
    assert membership.role == HouseholdMember.OWNER


@override_settings(LEGION_OPEN_SIGNUP=True)
def test_open_signup_without_a_household_name_is_refused():
    response = signup(email="stranger@example.com", password=GOOD_PASSWORD)

    assert response.status_code == 400
    assert "household_name" in response.data["detail"]
    assert not User.objects.filter(email="stranger@example.com").exists()


@override_settings(LEGION_OPEN_SIGNUP=True)
def test_open_signup_still_honours_an_invite_code(owner_client, household_a):
    """The switch makes the code OPTIONAL; it does not make it ignored. A
    stranger's second adult joins the first one's household rather than
    founding a rival one."""
    invite = mint(owner_client)

    response = signup(
        email="wife@example.com",
        password=GOOD_PASSWORD,
        invite_code=invite["code"],
        household_name="Ignored",
    )

    assert response.status_code == 201, response.data
    membership = HouseholdMember.objects.get(user__email="wife@example.com")
    assert membership.household_id == household_a.id
    assert Household.objects.count() == 1


# =============================================================================
# GET /api/auth/invite/<code> - describes a code without spending it
# =============================================================================


def test_invite_preview_describes_a_join_code_without_spending_it(owner_client, household_a):
    invite = mint(owner_client, max_uses=2)

    response = APIClient().get(f"/api/auth/invite/{invite['code']}")

    assert response.status_code == 200, response.data
    assert response.data["live"] is True
    assert response.data["creates_household"] is False
    assert response.data["household_name"] == household_a.name
    assert response.data["uses_left"] == 2
    assert response.data["reason"] is None
    assert Invite.objects.get(code=invite["code"]).used_count == 0


def test_invite_preview_of_a_create_code_names_no_household(owner_client):
    invite = mint(owner_client, creates_household=True)

    response = APIClient().get(f"/api/auth/invite/{invite['code']}")

    assert response.status_code == 200
    assert response.data["creates_household"] is True
    assert response.data["household_name"] is None


def test_invite_preview_says_why_a_dead_code_is_dead(owner_client):
    invite = mint(owner_client)
    owner_client.delete(f"/api/households/me/invites/{invite['code']}")

    response = APIClient().get(f"/api/auth/invite/{invite['code']}")

    assert response.status_code == 200
    assert response.data["live"] is False
    assert "revoked" in response.data["reason"]


def test_invite_preview_404s_on_an_unknown_code():
    response = APIClient().get("/api/auth/invite/nosuchcode1")

    assert response.status_code == 404
    assert "Nothing was spent" in response.data["detail"]


def test_invite_preview_never_names_the_household_id(owner_client):
    """The wire rule, at the one endpoint a signed-out stranger can reach."""
    invite = mint(owner_client)

    response = APIClient().get(f"/api/auth/invite/{invite['code']}")

    assert "household" not in response.data
    assert "household_id" not in response.data


# =============================================================================
# GET/PATCH /api/households/me
# =============================================================================


def test_household_me_lists_every_member(owner_client, owner, member, household_a):
    response = owner_client.get("/api/households/me")

    assert response.status_code == 200, response.data
    assert str(response.data["id"]) == str(household_a.id)
    assert response.data["name"] == household_a.name
    emails = {row["email"] for row in response.data["members"]}
    assert emails == {owner.email, member.email}
    roles = {row["email"]: row["role"] for row in response.data["members"]}
    assert roles[owner.email] == "owner"
    assert roles[member.email] == "member"


def test_a_member_may_read_the_household(member_client, household_a):
    """Reading the roster is not an owner power - a member who could not see
    who else is here would have no way to check they joined the right
    family."""
    response = member_client.get("/api/households/me")

    assert response.status_code == 200
    assert str(response.data["id"]) == str(household_a.id)


def test_an_owner_renames_the_household(owner_client, household_a):
    response = owner_client.patch("/api/households/me", {"name": "The Wins"}, format="json")

    assert response.status_code == 200, response.data
    assert response.data["name"] == "The Wins"
    household_a.refresh_from_db()
    assert household_a.name == "The Wins"


def test_a_member_cannot_rename_the_household(member_client, household_a):
    original = household_a.name

    response = member_client.patch("/api/households/me", {"name": "Mine"}, format="json")

    assert response.status_code == 403
    household_a.refresh_from_db()
    assert household_a.name == original


def test_the_household_of_another_family_is_invisible(token_b, household_a, household_b):
    response = token_b.get("/api/households/me")

    assert response.status_code == 200
    assert str(response.data["id"]) == str(household_b.id)
    assert str(household_a.id) not in str(response.data)


# =============================================================================
# Invites: owner-only, and the code comes back once
# =============================================================================


def test_minting_returns_the_code_and_a_join_url(owner_client):
    data = mint(owner_client)

    assert data["code"]
    assert data["join_url"].endswith(f"/join/{data['code']}")
    assert data["used_count"] == 0
    assert data["max_uses"] == 2


def test_a_member_cannot_mint_an_invite(member_client):
    response = member_client.post("/api/households/me/invites", {}, format="json")

    assert response.status_code == 403
    assert "owner" in response.data["detail"]
    assert Invite.objects.count() == 0


def test_a_member_cannot_list_invites(owner_client, member_client):
    mint(owner_client)

    response = member_client.get("/api/households/me/invites")

    assert response.status_code == 403


def test_a_member_cannot_revoke_an_invite(owner_client, member_client):
    invite = mint(owner_client)

    response = member_client.delete(f"/api/households/me/invites/{invite['code']}")

    assert response.status_code == 403
    assert Invite.objects.get(code=invite["code"]).revoked_at is None


def test_listing_shows_live_invites_only(owner_client):
    live = mint(owner_client)
    revoked = mint(owner_client)
    owner_client.delete(f"/api/households/me/invites/{revoked['code']}")
    expired = mint(owner_client)
    Invite.objects.filter(code=expired["code"]).update(
        expires_at=timezone.now() - datetime.timedelta(seconds=1)
    )
    spent = mint(owner_client, max_uses=1)
    signup(email="used@example.com", password=GOOD_PASSWORD, invite_code=spent["code"])

    response = owner_client.get("/api/households/me/invites")

    assert response.status_code == 200
    assert [row["code"] for row in response.data] == [live["code"]]


def test_another_households_invite_cannot_be_revoked(owner_client, token_b):
    invite = mint(owner_client)

    response = token_b.delete(f"/api/households/me/invites/{invite['code']}")

    assert response.status_code == 404
    assert Invite.objects.get(code=invite["code"]).revoked_at is None


def test_revoking_twice_is_the_same_answer(owner_client):
    invite = mint(owner_client)

    first = owner_client.delete(f"/api/households/me/invites/{invite['code']}")
    second = owner_client.delete(f"/api/households/me/invites/{invite['code']}")

    assert first.status_code == 204
    assert second.status_code == 204


# =============================================================================
# Removing a member
# =============================================================================


def test_removing_a_member_revokes_their_device_tokens(owner_client, member):
    phone, phone_key = DeviceToken.issue(member, "Her phone")
    laptop, _ = DeviceToken.issue(member, "Her laptop")

    response = owner_client.delete(f"/api/households/me/members/{member.id}")

    assert response.status_code == 204, response.data
    phone.refresh_from_db()
    laptop.refresh_from_db()
    assert phone.is_revoked
    assert laptop.is_revoked

    # And the revoked key really is dead on the wire, not merely flagged.
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {phone_key}")
    assert client.get("/api/auth/me").status_code == 401


def test_removing_a_member_leaves_their_account_but_no_household(owner_client, member):
    owner_client.delete(f"/api/households/me/members/{member.id}")

    assert User.objects.filter(id=member.id).exists()
    assert not HouseholdMember.objects.filter(user=member).exists()


def test_removing_a_member_revokes_the_invites_they_minted(owner_client, household_a):
    """A removed person keeping a live invite is the same failure as keeping
    a working phone, one step removed."""
    second_owner = make_member(household_a, "co-owner@example.com", HouseholdMember.OWNER)
    theirs = mint(client_for(second_owner))

    owner_client.delete(f"/api/households/me/members/{second_owner.id}")

    assert Invite.objects.get(code=theirs["code"]).revoked_at is not None


def test_the_last_owner_cannot_remove_themself(owner_client, owner):
    response = owner_client.delete(f"/api/households/me/members/{owner.id}")

    assert response.status_code == 400
    assert "last owner" in response.data["detail"]
    assert "Nobody was removed" in response.data["detail"]
    assert HouseholdMember.objects.filter(user=owner).exists()


def test_an_owner_may_leave_once_there_is_a_second_owner(owner_client, owner, household_a):
    make_member(household_a, "co-owner@example.com", HouseholdMember.OWNER)

    response = owner_client.delete(f"/api/households/me/members/{owner.id}")

    assert response.status_code == 204, response.data
    assert not HouseholdMember.objects.filter(user=owner).exists()


def test_a_member_cannot_remove_anyone(member_client, owner):
    response = member_client.delete(f"/api/households/me/members/{owner.id}")

    assert response.status_code == 403
    assert HouseholdMember.objects.filter(user=owner).exists()


def test_a_member_of_another_household_cannot_be_removed(owner_client, user_b):
    response = owner_client.delete(f"/api/households/me/members/{user_b.id}")

    assert response.status_code == 404
    assert "Nobody was removed" in response.data["detail"]
    assert HouseholdMember.objects.filter(user=user_b).exists()


# =============================================================================
# GET/DELETE /api/auth/devices
# =============================================================================


def test_devices_lists_my_own_live_tokens_and_marks_the_current_one(owner, member):
    _token, raw_key = DeviceToken.issue(owner, "Kevin's phone")
    DeviceToken.issue(member, "Somebody else's phone")
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw_key}")

    response = client.get("/api/auth/devices")

    assert response.status_code == 200, response.data
    assert [row["name"] for row in response.data] == ["Kevin's phone"]
    assert response.data[0]["current"] is True
    # Never the key itself - the server holds only its hash.
    assert "key" not in response.data[0]
    assert "key_hash" not in response.data[0]


def test_revoking_a_device_stops_it_authenticating(owner):
    _keep, keep_key = DeviceToken.issue(owner, "Kept")
    drop, drop_key = DeviceToken.issue(owner, "Dropped")
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {keep_key}")

    response = client.delete(f"/api/auth/devices/{drop.id}")

    assert response.status_code == 204
    dropped = APIClient()
    dropped.credentials(HTTP_AUTHORIZATION=f"Token {drop_key}")
    assert dropped.get("/api/auth/me").status_code == 401
    # And the one that made the request still works.
    assert client.get("/api/auth/me").status_code == 200


def test_another_persons_device_cannot_be_revoked(owner_client, member):
    theirs, their_key = DeviceToken.issue(member, "Her phone")

    response = owner_client.delete(f"/api/auth/devices/{theirs.id}")

    assert response.status_code == 404
    theirs.refresh_from_db()
    assert not theirs.is_revoked
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {their_key}")
    assert client.get("/api/auth/me").status_code == 200


def test_revoked_devices_are_not_listed(owner):
    _keep, keep_key = DeviceToken.issue(owner, "Kept")
    drop, _ = DeviceToken.issue(owner, "Dropped")
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {keep_key}")
    client.delete(f"/api/auth/devices/{drop.id}")

    response = client.get("/api/auth/devices")

    assert [row["name"] for row in response.data] == ["Kept"]


# =============================================================================
# Tenancy: the door ticket 03 opened does not walk around ADR 0045
# =============================================================================


def test_a_signup_into_a_new_household_sees_none_of_the_old_ones_rows(owner_client):
    written = owner_client.put(
        "/api/places/kitchen/", {"latitude": 1.0, "longitude": 2.0}, format="json"
    )
    assert written.status_code == 200, written.data

    invite = mint(owner_client, creates_household=True)
    joined = signup(
        email="dad@example.com",
        password=GOOD_PASSWORD,
        invite_code=invite["code"],
        household_name="The Parents",
    )
    assert joined.status_code == 201, joined.data

    theirs = APIClient()
    theirs.credentials(HTTP_AUTHORIZATION=f"Token {joined.data['token']}")
    assert theirs.get(f"/api/places/?since={EPOCH}").data["results"] == []
    # And household A still has it - an isolation test that passes because
    # nobody can see anything is not an isolation test.
    assert len(owner_client.get(f"/api/places/?since={EPOCH}").data["results"]) == 1


def test_a_join_code_signup_shares_the_household_rows(owner_client):
    """The other direction: joining household A means seeing household A."""
    owner_client.put("/api/places/kitchen/", {"latitude": 1.0, "longitude": 2.0}, format="json")
    invite = mint(owner_client)

    joined = signup(email="wife@example.com", password=GOOD_PASSWORD, invite_code=invite["code"])

    hers = APIClient()
    hers.credentials(HTTP_AUTHORIZATION=f"Token {joined.data['token']}")
    assert len(hers.get(f"/api/places/?since={EPOCH}").data["results"]) == 1


# =============================================================================
# manage.py create_household
# =============================================================================


def test_create_household_makes_a_household_with_an_owner(household_a):
    call_command(
        "create_household",
        "--name",
        "The Parents",
        "--owner-email",
        "dad@example.com",
        "--password",
        GOOD_PASSWORD,
    )

    membership = HouseholdMember.objects.get(user__email="dad@example.com")
    assert membership.household.name == "The Parents"
    assert membership.role == HouseholdMember.OWNER
    assert membership.household_id != household_a.id


def test_create_household_pins_the_id_when_asked(db):
    call_command(
        "create_household",
        "--name",
        "Pinned",
        "--owner-email",
        "dad@example.com",
        "--password",
        GOOD_PASSWORD,
        "--id",
        "11111111-1111-4111-8111-111111111111",
    )

    assert Household.objects.filter(id="11111111-1111-4111-8111-111111111111").exists()


def test_create_household_refuses_someone_already_in_one(owner, household_a):
    with pytest.raises(CommandError) as refusal:
        call_command("create_household", "--name", "Second", "--owner-email", owner.email)

    assert "already a member" in str(refusal.value)
    assert Household.objects.count() == 1


def test_create_household_refuses_a_new_user_with_no_password(db):
    with pytest.raises(CommandError) as refusal:
        call_command("create_household", "--name", "Theirs", "--owner-email", "new@example.com")

    assert "--password" in str(refusal.value)
    assert not User.objects.filter(email="new@example.com").exists()
    assert not Household.objects.filter(name="Theirs").exists()


def test_create_household_refuses_a_weak_password(db):
    with pytest.raises(CommandError) as refusal:
        call_command(
            "create_household",
            "--name",
            "Theirs",
            "--owner-email",
            "new@example.com",
            "--password",
            "123",
        )

    assert "rejected" in str(refusal.value)
    assert not User.objects.filter(email="new@example.com").exists()
    assert not Household.objects.filter(name="Theirs").exists()
