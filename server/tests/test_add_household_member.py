"""The gap between `createsuperuser`/the admin and `IsHouseholdMember`: a
`User` row alone does not pass that permission. Covers both halves of the
fix - the auto-membership signal for superusers
(`household/signals.py`), and the explicit management command for
everyone else (`household/management/commands/add_household_member.py`).
"""
from __future__ import annotations

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
    assert "already a household member" in second_output


def test_add_household_member_unknown_email_errors_in_words():
    with pytest.raises(CommandError, match="No user with email"):
        call_command("add_household_member", "nobody@example.com")
