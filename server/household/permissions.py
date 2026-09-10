"""Section 1's trust model as a permission class.

**It used to read** "two adults, no roles, no tenancy. Every authenticated
request either belongs to the one household or it is refused outright."
ADR 0045 changed the middle clause and left the rest exactly as it was: there
are households now, so this answers "is this user in ANY household" and
`household.tenancy.household_of` answers "which one". Inside a household
access is still all-or-nothing - no partial access to grant, no role that
gates data - so this class is unchanged in code, and that is the point worth
recording rather than the diff.

**A second class joined it 2026-09-10** (web-and-households ticket 03):
`IsHouseholdOwner`, for the four membership verbs ADR 0045's one role
exists for. It does not weaken the paragraph above - inside a household
access is still all-or-nothing, and the owner role still gates no data.
"""
from __future__ import annotations

from rest_framework import permissions

from household.models import HouseholdMember


class IsHouseholdMember(permissions.BasePermission):
    def has_permission(self, request, view) -> bool:
        user = request.user
        if not user or not user.is_authenticated:
            return False
        return HouseholdMember.objects.filter(user=user).exists()


class IsHouseholdOwner(IsHouseholdMember):
    """The ONE role, and it governs membership only (ADR 0045).

    Guards exactly four verbs, all in `household/households.py`: rename the
    household, mint an invite, revoke an invite, remove a member. It must
    never appear on a data route - an owner and a member see exactly the same
    rows, and the day this class guards a read is the day "no roles inside a
    household" stopped being true.

    Subclasses `IsHouseholdMember` rather than restating it: an owner is a
    member first, and the household-membership check is the one that decides
    whether the caller is inside the tenancy at all.
    """

    message = (
        "Nothing was changed. Only an owner of this household may rename it, invite "
        "people or remove them. An owner and a member see exactly the same data - the "
        "role governs membership and nothing else."
    )

    def has_permission(self, request, view) -> bool:
        if not super().has_permission(request, view):
            return False
        return HouseholdMember.objects.filter(
            user=request.user, role=HouseholdMember.OWNER
        ).exists()
