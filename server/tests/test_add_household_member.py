"""The gap between `createsuperuser`/the admin and `IsHouseholdMember`: a
`User` row alone does not pass that permission. Covers both halves of the
fix - the auto-membership signal for superusers
(`household/signals.py`), and the explicit management command for
everyone else (`household/management/commands/add_household_member.py`).
"""
from __future__ import annotations

import uuid

import pytest
from django.core.management import call_command
from django.core.management.base import CommandError

from household.models import HouseholdMember, User

pytestmark = pytest.mark.django_db


def test_createsuperuser_gets_a_household_member_row_automatically():
    user = User.objects.create_superuser(
        email="admin@example.com", password="correct horse battery"
    )
    assert HouseholdMember.objects.filter(user=user).exists()


def test_ordinary_user_gets_no_automatic_household_member_row():
    user = User.objects.create_user(email="plain@example.com", password="correct horse battery")
    assert not HouseholdMember.objects.filter(user=user).exists()


def test_promoting_an_existing_user_to_superuser_also_grants_membership():
    user = User.objects.create_user(email="promoted@example.com", password="correct horse battery")
    assert not HouseholdMember.objects.filter(user=user).exists()

    user.is_superuser = True
    user.save(update_fields=["is_superuser"])

    assert HouseholdMember.objects.filter(user=user).exists()


def test_add_household_member_creates_then_is_idempotent(capsys):
    user = User.objects.create_user(email="second@example.com", password="correct horse battery")
    assert not HouseholdMember.objects.filter(user=user).exists()

    call_command("add_household_member", "second@example.com")
    assert HouseholdMember.objects.filter(user=user).exists()
    first_output = capsys.readouterr().out
    assert "Added" in first_output

    call_command("add_household_member", "second@example.com")
    assert HouseholdMember.objects.filter(user=user).count() == 1
    second_output = capsys.readouterr().out
    # ADR 0045 changed this sentence and only this sentence: "already a
    # household member" was unambiguous when there was one household, and the
    # command now names which one they are in.
    assert "is already a member of" in second_output


def test_add_household_member_unknown_email_errors_in_words():
    with pytest.raises(CommandError, match="No user with email"):
        call_command("add_household_member", "nobody@example.com")


def test_add_household_member_refuses_to_guess_between_two_households(monkeypatch):
    """ADR 0045's whole point, at the one door that predates it.

    With more than one household and none of them named by
    `LEGION_BOOTSTRAP_HOUSEHOLD_ID`, there is no household this command may
    pick. It says so and adds nobody, rather than choosing the oldest and
    reporting success - putting a person in the wrong family is exactly the
    failure tenancy exists to prevent.

    The env var is pointed at a uuid no household has, rather than unset: an
    UNSET one falls through to "the only household if there is exactly one",
    which is a different branch and is covered by every other test in this
    file. This is the branch where the operator HAS named one and it is gone.
    """
    from household.models import Household

    monkeypatch.setenv("LEGION_BOOTSTRAP_HOUSEHOLD_ID", str(uuid.uuid4()))
    Household.objects.create(name="Second household")
    Household.objects.create(name="Third household")
    user = User.objects.create_user(email="stranded@example.com", password="correct horse battery")

    with pytest.raises(CommandError, match="none of them is named by"):
        call_command("add_household_member", "stranded@example.com")

    assert not HouseholdMember.objects.filter(user=user).exists()


def test_add_household_member_takes_an_explicit_household():
    from household.models import Household

    other = Household.objects.create(name="Parents")
    User.objects.create_user(email="mum@example.com", password="correct horse battery")

    call_command("add_household_member", "mum@example.com", "--household", str(other.id), "--owner")

    member = HouseholdMember.objects.get(user__email="mum@example.com")
    assert member.household_id == other.id
    assert member.role == HouseholdMember.OWNER


def test_add_household_member_password_creates_a_new_user_and_membership(capsys):
    """web-and-households ticket 03 narrow slice, 2026-09-10: this is the
    exact command Kevin runs to onboard his wife. No `User` row exists yet,
    so `--password` is what creates one."""
    assert not User.objects.filter(email="wife@example.com").exists()

    call_command(
        "add_household_member", "wife@example.com", "--password", "correct horse battery"
    )

    user = User.objects.get(email="wife@example.com")
    assert user.check_password("correct horse battery")
    assert HouseholdMember.objects.filter(user=user).exists()

    output = capsys.readouterr().out
    assert "Created user" in output
    assert "correct horse battery" not in output


def test_add_household_member_password_never_resets_an_existing_password(capsys):
    """Idempotent re-run: the second call still carries --password (a
    first-run script that always passes it), and it must not touch the
    live password or even acknowledge it beyond a warning."""
    User.objects.create_user(email="wife@example.com", password="the real password")
    call_command("add_household_member", "wife@example.com", "--password", "the real password")
    capsys.readouterr()

    call_command("add_household_member", "wife@example.com", "--password", "a different one")

    user = User.objects.get(email="wife@example.com")
    assert user.check_password("the real password")
    assert not user.check_password("a different one")
    output = capsys.readouterr().out
    assert "ignored" in output
    assert "a different one" not in output


def test_add_household_member_rejects_a_weak_password():
    with pytest.raises(CommandError, match="was rejected"):
        call_command("add_household_member", "weak@example.com", "--password", "1234")

    assert not User.objects.filter(email="weak@example.com").exists()
