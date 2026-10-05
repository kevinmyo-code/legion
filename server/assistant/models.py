"""The web assistant's two tables (web-assistant ticket 07).

## `public.assistant_companions`: who each member talks to

Ticket 03 (Kevin, 2026-10-04): "companion definitions (name, persona, voice)
must be available to the engine per member, not only in the phone's local
profile store", and Mia's is Dorothy. One row per member: the name the
companion answers to, which built-in register it wears (`persona_key`, one of
`assistant/personas.py`), an optional custom register that replaces it
(`persona_fragment`, for a profile the phone pushes up later), and the Gemini
voice it speaks in.

A built-in register is stored as its KEY, not as a copy of its text, so an
edit to `Personas.kt` (mirrored into `assistant/personas.py` under a drift
test) reaches every member who wears that persona, rather than leaving stale
prose sitting in a row.

## `public.assistant_calls`: the audit trail for the two web doors

Ticket 02: "throttled per member and audited like `McpCall` (tool name,
outcome, no arguments stored)". One row per token mint and per tool call that
got past authentication: who, when, which door, which tool, how it ended.
**Never the prompt, never the arguments, never a result body, never the key
or the token.** A separate table from `mcp_calls` because the caller here is a
signed-in member, not a device token, and `mcp_calls`' shape is pinned by its
own test to exactly the fields a token call has.

Both are born tenanted, in `public`, like `mcp_calls` and `purchases`: in
`household.tenancy.TENANT_TABLES`, and every read is scoped to the request's
household.
"""

from __future__ import annotations

import uuid

from django.db import models
from django.db.models.functions import Now

from engine_mcp.models import Outcome


class Companion(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    # The member this companion speaks to. One each.
    user = models.OneToOneField("household.User", on_delete=models.CASCADE, related_name="+")
    # What the companion is called. The persona's default unless renamed.
    name = models.TextField()
    # A built-in register from `assistant/personas.py`. An unknown key reads as
    # Alfred (`persona_for`), the phone's own fallback.
    persona_key = models.TextField(default="alfred")
    # A custom register. Blank means "the built-in one for `persona_key`".
    persona_fragment = models.TextField(blank=True, default="")
    # A Gemini prebuilt voice name. Blank means the persona's suggested voice.
    voice_name = models.TextField(blank=True, default="")
    created_at = models.DateTimeField(db_default=Now())
    updated_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "assistant_companions"
        constraints = [
            models.CheckConstraint(
                condition=~models.Q(name__regex=r"^\s*$"),
                name="assistant_companions_name_not_blank",
            ),
        ]

    def __str__(self) -> str:
        return f"{self.name} for {self.user}"


class AssistantCall(models.Model):
    SESSION = "session"
    TOOL = "tool"
    KINDS = ((SESSION, "Token mint"), (TOOL, "Tool call"))

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    # SET_NULL: removing a member must not erase what was done in their name.
    user = models.ForeignKey(
        "household.User", on_delete=models.SET_NULL, null=True, blank=True, related_name="+"
    )
    kind = models.TextField(choices=KINDS)
    # Only for a tool call: the tool NAME as asked, whether or not it exists.
    tool = models.TextField(null=True, blank=True)
    outcome = models.TextField(choices=Outcome.choices)
    at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "assistant_calls"
        indexes = [models.Index(fields=["household", "-at"], name="assistant_calls_household_at")]
        constraints = [
            models.CheckConstraint(
                condition=models.Q(outcome__in=Outcome.values),
                name="assistant_calls_outcome_valid",
            ),
            models.CheckConstraint(
                condition=models.Q(kind__in=["session", "tool"]),
                name="assistant_calls_kind_valid",
            ),
        ]
