"""Section 1's trust model as a permission class: two adults, no roles, no
tenancy. Every authenticated request either belongs to the one household or
it is refused outright - there is no partial access to grant."""
from __future__ import annotations

from rest_framework import permissions

from household.models import HouseholdMember


class IsHouseholdMember(permissions.BasePermission):
    def has_permission(self, request, view) -> bool:
        user = request.user
        if not user or not user.is_authenticated:
            return False
        return HouseholdMember.objects.filter(user=user).exists()
