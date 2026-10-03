"""The audit trail for `/mcp` (engine-mcp ticket 08, Kevin 2026-10-02: "every
call audited").

One row per MCP request that got past authentication: which token, which
JSON-RPC method, which tool, and how it ended. **Never the arguments and never
the result body.** The arguments of a write tool are the household's own data
and the result of a read tool is the household's own data; copying either into
an audit table would make this table a second, unscoped-by-purpose copy of
everything the model ever asked about. "Who asked what, and did it work" is the
whole question an audit answers here.

**Physically in `public`, like `ingest_runs`**, and for the same reason: it
carries `household_id`, so it is a tenant table, and
`tests/test_tenancy.py` asserts the set of `public` tables carrying that
column is exactly `household.tenancy.TENANT_TABLES`. A tenanted table in the
`django` schema would be invisible to that check, which is the one thing a
tenant table must never be. `migrations/0001_initial.py` uses the same
temporary `search_path` swap as `ingest/migrations/0001_initial.py`.

A request refused before authentication (no token, a bad token, `/mcp` off)
writes nothing: there is no household to scope the row to, and a row scoped to
no household is the shape ADR 0045 exists to forbid.
"""

from __future__ import annotations

import uuid

from django.db import models
from django.db.models.functions import Now


class Outcome(models.TextChoices):
    # The call ran and its result says what it did.
    OK = "ok", "OK"
    # The call was refused before it ran: a read-scoped token on a write tool,
    # a gated or excluded table, an unknown tool, invalid arguments. Nothing
    # was written.
    REFUSED = "refused", "Refused"
    # The call ran and failed: a serializer rejected the write, the view
    # answered 4xx/5xx, an exception. Nothing was written.
    FAILED = "failed", "Failed"
    # The per-token throttle refused the request before the SDK saw it.
    THROTTLED = "throttled", "Throttled"


class McpCall(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    # SET_NULL rather than CASCADE: deleting a token row must not erase the
    # record of what it did. `token_name` keeps the row legible afterwards.
    token = models.ForeignKey(
        "household.DeviceToken",
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        related_name="+",
    )
    token_name = models.TextField()
    token_scope = models.TextField()
    # The JSON-RPC method (`tools/call`, `tools/list`, `server/discover`...),
    # or the literal `unparseable` for a body that was not JSON-RPC.
    method = models.TextField()
    # Only for `tools/call`: the tool NAME the caller asked for, as asked,
    # whether or not such a tool exists. Never its arguments.
    tool = models.TextField(null=True, blank=True)
    outcome = models.TextField(choices=Outcome.choices)
    at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "mcp_calls"
        indexes = [models.Index(fields=["household", "-at"], name="mcp_calls_household_at")]
        constraints = [
            models.CheckConstraint(
                condition=models.Q(outcome__in=Outcome.values),
                name="mcp_calls_outcome_valid",
            ),
        ]
