"""`public.llm_usage`: one row per engine model call or Live mint, per member where known.

Architecture review 2026-11-10, item 15, and Q9. Before this the engine's Gemini spend had
no meter outside the recruiter demo's budget (`demo/budget.py`); the brief stored its own
news usage on each brief row and nothing else did. This is the one ledger: what was called,
for which household and (when a person asked) which member, the tokens Google reported, and
an ESTIMATED cost in micro-dollars (list prices, `llm/usage.py`; never a billed figure).

A Live mint is a row too (`feature="live_mint"`): the browser talks to Google directly, so
the engine never sees that conversation's tokens and records the mint's flat estimate
instead (`LEGION_MEMBER_MINT_COST_USD`). That estimate is what the member daily cap counts.

Tenanted: born with `household_id`, listed in `household.tenancy.TENANT_TABLES`. Rows go
with their household; a member's rows lose the member (SET NULL) when the member is removed.
Additive: one new table, nothing else touched.
"""

from __future__ import annotations

from django.conf import settings
from django.db import models
from django.db.models.functions import Now


class LlmUsage(models.Model):
    household = models.ForeignKey("household.Household", on_delete=models.CASCADE, related_name="+")
    user = models.ForeignKey(
        settings.AUTH_USER_MODEL, null=True, blank=True, on_delete=models.SET_NULL, related_name="+"
    )
    feature = models.TextField()
    model = models.TextField()
    prompt_tokens = models.IntegerField(null=True, blank=True)
    output_tokens = models.IntegerField(null=True, blank=True)
    total_tokens = models.IntegerField(null=True, blank=True)
    search_queries = models.IntegerField(default=0)
    cost_estimate_micro_usd = models.BigIntegerField(null=True, blank=True)
    created_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "llm_usage"
        indexes = [
            models.Index(fields=["user", "created_at"], name="llm_usage_user_day_idx"),
            models.Index(fields=["household", "created_at"], name="llm_usage_household_day_idx"),
        ]
