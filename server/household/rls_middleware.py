"""Puts each request under Postgres row-level security (`household/rls.py`).

On when `settings.LEGION_RLS` is true (`LEGION_RLS=on`); otherwise it passes the request
straight through and nothing about the request changes.

When on, a request runs inside one transaction that starts with

    SET LOCAL ROLE legion_app;                       -- no BYPASSRLS, owns nothing
    SELECT set_config('app.household_id', '', true); -- no household yet: tenant tables empty

and the household is bound as soon as the user is known: here for a session user (Django's
AuthenticationMiddleware runs first), in `DeviceTokenAuthentication` for a token. Both are
`LOCAL`, so they end with the transaction and never leak into the next request on the same
connection. Inside a test's wrapping transaction they are reset explicitly on the way out, so
fixture code after a request is not left running as `legion_app`.

The transaction is the request's: a request that already used `ATOMIC_REQUESTS` or its own
`transaction.atomic()` blocks gets savepoints inside it, and a 500 rolls the request back as a
whole. A streaming response's body is produced after the transaction ends, under the connecting
role; nothing in this server streams tenant rows (noted in `deploy/rls/README.md`).
"""
from __future__ import annotations

from django.conf import settings
from django.db import connection, transaction

from household import rls


class RlsMiddleware:
    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        if not getattr(settings, "LEGION_RLS", False):
            return self.get_response(request)
        with transaction.atomic():
            with connection.cursor() as cursor:
                cursor.execute(f"set local role {rls.RLS_ROLE}")
                cursor.execute(
                    "select set_config(%s, '', true), set_config(%s, '', true)",
                    [rls.HOUSEHOLD_SETTING, rls.USER_SETTING],
                )
            connection.legion_rls_active = True
            try:
                rls.bind_user(getattr(request, "user", None))
                response = self.get_response(request)
            finally:
                connection.legion_rls_active = False
                if not connection.needs_rollback:
                    with connection.cursor() as cursor:
                        cursor.execute("set local role none")
                        cursor.execute(
                            "select set_config(%s, '', true), set_config(%s, '', true)",
                            [rls.HOUSEHOLD_SETTING, rls.USER_SETTING],
                        )
        return response
