"""Auto-membership for superusers.

`manage.py createsuperuser` (and the admin's own "add user" form with the
superuser box ticked) calls `User.objects.create_superuser`, which saves a
plain `User` row - it has no idea `household.models.HouseholdMember`
exists at all, so a fresh admin account got a bare 403 from
`IsHouseholdMember` on every authenticated endpoint until someone added the
row by hand (found the hard way, 2026-09-05, during the live migrate). A
two-adult household with no roles has no reason a superuser would ever NOT
also be a household member, so this closes the gap automatically rather
than leaving it as a step nobody remembers.

`household/management/commands/add_household_member.py` stays the explicit
path for the SECOND adult, who is a household member but not necessarily a
superuser - this signal only ever fires for `is_superuser = True`.
"""
from __future__ import annotations

from django.db.models.signals import post_save
from django.dispatch import receiver

from household.models import HouseholdMember, User


@receiver(post_save, sender=User)
def ensure_household_member_for_superuser(sender, instance: User, **kwargs) -> None:
    """Runs on every save of a `User`, not just creation - `get_or_create`
    makes this idempotent either way, and a `User` promoted to superuser
    later (rather than created as one) should gain membership at that
    point too, not only at the moment of creation."""
    if instance.is_superuser:
        HouseholdMember.objects.get_or_create(user=instance)
