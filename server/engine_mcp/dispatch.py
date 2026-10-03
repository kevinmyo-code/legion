"""How an MCP write reaches the database: through the REST route, and only
through it.

engine-mcp ticket 06/10 (Kevin, 2026-10-02): writes go "only through the same
Django serializers the REST views use". The narrowest way to make that a fact
rather than a habit is not to call the serializers from here at all, but to
call the ROUTED VIEW: `django.urls.resolve` finds exactly the callable
`/api/...` would dispatch to, and this module hands it a request authenticated
as the MCP caller. So an MCP write and a phone write run the same view, the
same serializer, the same `household_of`, the same `save_or_400`, and - for a
gated table - the same 405 naming the gate. There is no second write path to
drift, because there is no second write path.

The caller's identity is carried with DRF's own forced-authentication hook
(`_force_auth_user` / `_force_auth_token` on the Django request, read by
`rest_framework.request.Request.__init__`), so the inner view sees the same
`request.user` and the same `request.auth` - the `DeviceToken`, scope
included. `IsHouseholdMember` therefore runs again inside, and refuses a
read-scoped token on an unsafe method on its own: the scope check in
`engine_mcp/tools.py` is the one that words the refusal, and this is the
second lock behind it.
"""

from __future__ import annotations

import json
from urllib.parse import quote

from django.test.client import RequestFactory
from django.urls import Resolver404, resolve

_FACTORY = RequestFactory()


class RouteNotFound(Exception):
    """The path names no route. Raised rather than answered, so the tool
    that asked can say in its own words which row it could not find."""


def call_route(outer, method: str, path: str, data: dict | None = None):
    """Run the view `path` routes to, as the caller of `outer`.

    `outer` is the DRF request `/mcp` authenticated. `path` is unquoted
    (`/api/places/home/`); every segment is quoted here exactly once.
    Returns the DRF `Response` the view returned, unrendered: `.status_code`
    and `.data` are what the caller reads.

    `RequestFactory` is Django's documented way to build a request object
    outside the handler. It lives in `django.test` and does nothing
    test-specific: no client, no test database, no signals.
    """
    try:
        match = resolve(path)
    except Resolver404 as exc:
        raise RouteNotFound(path) from exc
    quoted = "/".join(quote(segment, safe="") for segment in path.split("/"))
    body = json.dumps(data) if data is not None else ""
    request = _FACTORY.generic(
        method,
        quoted,
        data=body,
        content_type="application/json",
        secure=outer.is_secure(),
        HTTP_HOST=outer.get_host(),
    )
    request._force_auth_user = outer.user
    request._force_auth_token = outer.auth
    return match.func(request, *match.args, **match.kwargs)
