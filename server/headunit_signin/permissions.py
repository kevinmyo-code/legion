"""The permission half of the head-unit fence (T3b).

`HeadUnitScope` refuses a head-unit credential on any (route, method) `scope.ROUTES` does not
name. `household.permissions.IsHouseholdMember` runs it on every request it guards, so it holds
where the authentication class never runs: a request a turn's tool dispatches to a routed view
(`engine_mcp.dispatch.call_route` forces the caller's identity onto it, skipping authentication).
"""

from __future__ import annotations

from rest_framework import permissions

from headunit_signin import scope
from headunit_signin.authentication import ROUTE_REFUSAL


class HeadUnitScope(permissions.BasePermission):
    message = ROUTE_REFUSAL

    def has_permission(self, request, view) -> bool:
        return not scope.is_headunit(request) or scope.route_allowed(request)
