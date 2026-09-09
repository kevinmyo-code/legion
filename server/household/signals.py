"""Auto-membership for superusers, in the bootstrap household.

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
from household.tenancy import resolve_default_household


@receiver(post_save, sender=User)
def ensure_household_member_for_superuser(sender, instance: User, **kwargs) -> None:
    """Runs on every save of a `User`, not just creation - the membership
    check below makes this idempotent either way, and a `User` promoted to
    superuser later (rather than created as one) should gain membership at
    that point too, not only at the moment of creation.

    **This sentence used to credit `get_or_create`**, which is what did the
    idempotency before ADR 0045. It is an explicit `exists()` now, because
    `get_or_create` would have had to be handed a `household` in its
    `defaults` - and resolving one costs a query and can fail, which is work
    that must not happen on the save of a user who is already a member.

    **WHICH household, now that there can be more than one (ADR 0045).**
    `resolve_default_household` answers it, and refuses in words when it
    cannot: the bootstrap household when `LEGION_BOOTSTRAP_HOUSEHOLD_ID`
    names a real one, otherwise the only household if there is exactly one,
    otherwise nothing. On an engine holding several families, a new superuser
    is created and then added to a household deliberately - guessing which
    family the operator meant is precisely the thing tenancy exists to stop.
    """
    if not instance.is_superuser:
        return
    if HouseholdMember.objects.filter(user=instance).exists():
        return
    household = resolve_default_household()
    if household is None:
        # Silence here would be the old bug wearing a new hat: a superuser
        # with no membership row gets a bare 403 from `IsHouseholdMember` on
        # every endpoint. Saying so at creation time is the whole point of
        # this signal.
        raise RuntimeError(
            f"{instance.email} was created as a superuser but was NOT added to a "
            f"household, because this engine holds more than one and none of them is "
            f"named by LEGION_BOOTSTRAP_HOUSEHOLD_ID. Until it is in one, "
            f"IsHouseholdMember will refuse every authenticated request from it. Add it "
            f"with `manage.py add_household_member {instance.email} --household <uuid>`."
        )
    HouseholdMember.objects.create(
        user=instance, household=household, role=HouseholdMember.OWNER
    )
