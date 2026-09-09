"""Section 1's trust model as a permission class.

**It used to read** "two adults, no roles, no tenancy. Every authenticated
request either belongs to the one household or it is refused outright."
ADR 0045 changed the middle clause and left the rest exactly as it was: there
are households now, so this answers "is this user in ANY household" and
`household.tenancy.household_of` answers "which one". Inside a household
access is still all-or-nothing - no partial access to grant, no role that
gates data - so this class is unchanged in code, and that is the point worth
recording rather than the diff.
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
