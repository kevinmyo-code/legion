"""`public.briefs`: the daily brief (ai-upgrade ticket 02).

One row per BUILD for one member: the 05:00 job writes one per member of the
household, and a Refresh writes one for the member who pressed it. Rows older
than `KEEP_DAYS` household-local days are deleted by the next build (ruling 6:
"holding the last 14 days").

**Per member, not per household**, because the Canvas section reads `events`
through `household.tenancy.visible()`: a Canvas task is private to the member
whose session the poller used (ADR 0052), and one household-wide brief would
hand it to every other member. Money and Car are household-wide data and come
out the same for every member; News is ONE Gemini call per household per run,
copied onto each member's row with the same `news_call_id`, so the cost of a
run is the distinct calls, never the sum of rows.

**Physically in `public`, like `mcp_calls`**: it carries `household_id`, so it
is a tenant table and `tests/test_tenancy.py` holds it to
`household.tenancy.TENANT_TABLES`. `user` is never served.

**Read-through (CLAUDE.md section 7) holds:** `sections` carries web news and
the household's own records only. Nothing from mail is ever composed here.
"""

from __future__ import annotations

import uuid

from django.conf import settings
from django.db import models
from django.db.models.functions import Now

KEEP_DAYS = 14

# The served shape of `sections` (`brief/compose.py`, `brief/views.py`).
SECTION_KEYS: tuple[str, ...] = ("canvas", "money", "car", "news")
SECTION_STATUSES: tuple[str, ...] = ("ok", "empty", "failed")


class Trigger(models.TextChoices):
    # The 05:00 job.
    SCHEDULED = "scheduled", "Scheduled"
    # `POST /api/brief/refresh`.
    REFRESH = "refresh", "Refresh"


class Brief(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    # The member this brief was composed for. CASCADE: a brief is derived,
    # holds nothing that exists nowhere else, and is private to its member.
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="+")
    # The household-local date the brief is for (UTC when the household has
    # no timezone set; the brief says so in words).
    brief_date = models.DateField()
    built_at = models.DateTimeField(db_default=Now())
    trigger = models.TextField(choices=Trigger.choices)
    # The composed sections, exactly as served (`brief/compose.py`).
    sections = models.JSONField()
    # Sentences about the whole brief (no household timezone, say), served
    # before the sections.
    notes = models.JSONField(default=list)
    # False when the News section could not be built; its sentence says why.
    news_ok = models.BooleanField(default=False)
    # Shared by every member's row built from one Gemini call. Null when no
    # call was made (no key, or the call failed before it was sent).
    news_call_id = models.UUIDField(null=True, blank=True)
    # Per-call usage, as Gemini reported it (`usageMetadata`). Null when no
    # call was made or Gemini did not say.
    news_model = models.TextField(null=True, blank=True)
    prompt_tokens = models.IntegerField(null=True, blank=True)
    output_tokens = models.IntegerField(null=True, blank=True)
    thinking_tokens = models.IntegerField(null=True, blank=True)
    tool_prompt_tokens = models.IntegerField(null=True, blank=True)
    total_tokens = models.IntegerField(null=True, blank=True)
    search_queries = models.IntegerField(null=True, blank=True)
    # An ESTIMATE in integer micro-dollars from named rate constants
    # (`brief/news.py`), never a billed figure. Counts every search query at
    # the paid rate, so it is an upper bound inside the monthly free allowance.
    cost_estimate_micro_usd = models.BigIntegerField(null=True, blank=True)

    class Meta:
        db_table = "briefs"
        indexes = [
            models.Index(fields=["household", "user", "-built_at"], name="briefs_hh_user_built"),
            models.Index(fields=["household", "brief_date"], name="briefs_hh_date"),
        ]
        constraints = [
            models.CheckConstraint(
                condition=models.Q(trigger__in=Trigger.values),
                name="briefs_trigger_valid",
            ),
        ]
