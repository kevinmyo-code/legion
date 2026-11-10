"""A member's daily web-assistant spend cap (architecture review 2026-11-10, Q9).

**Off unless `LEGION_MEMBER_DAILY_CAP` is set** to a USD amount above zero. Suggested value:
`3` (six member Live mints a day at the default mint estimate, or fewer with web searches).

Members spent the engine's Gemini key with no ceiling; only recruiters had one
(`demo/budget.py`). This counts a member's `llm_usage` rows since UTC midnight against the
cap. Like the demo budget, the engine never sees what a Live conversation actually cost
(the browser talks to Google directly), so a mint is counted at a flat estimate,
`LEGION_MEMBER_MINT_COST_USD` (default 0.50: about five minutes at the demo's own $0.10 a
minute, `demo/budget.py`), recorded BEFORE the token is minted. Engine calls a member's
conversation makes (`web_search`) are recorded with Google's own token counts.

**What it does not do, said plainly.** It is a soft cap, not the demo budget's
reserve-then-charge UPDATE: two tabs minting in the same instant can both fit and overshoot
by one mint. For one member's own tabs that is acceptable; for strangers the demo budget
stays the mechanism. Recruiters are never counted here (`demo_budget.counts` and
`is_recruiter` own them).
"""

from __future__ import annotations

import math
import os
from datetime import UTC, datetime
from decimal import Decimal, InvalidOperation

from django.core.exceptions import ImproperlyConfigured
from django.db.models import Sum

from llm import client

LIVE_MINT_FEATURE = "live_mint"
DEFAULT_MINT_COST_USD = "0.50"
USED_UP = (
    "Today's assistant spend limit for your account is used up, so no conversation was "
    "started. It resets at midnight UTC."
)
SEARCH_USED_UP = (
    "Nothing was searched. Today's assistant spend limit for this account is used up; say so."
)


def _micro_usd(name: str, default: str) -> int:
    raw = os.environ.get(name, "").strip() or default
    if raw.lower() == "off":
        return 0
    try:
        value = Decimal(raw)
    except InvalidOperation:
        raise ImproperlyConfigured(f"{name} must be an amount in USD, like 3 or 0.50; got {raw!r}.")
    if value < 0 or not value.is_finite():
        raise ImproperlyConfigured(f"{name} must be zero or more; got {raw!r}.")
    return math.ceil(value * 1_000_000)


def cap_micro_usd() -> int | None:
    """The cap in micro-dollars, or None when the cap is off."""
    cap = _micro_usd("LEGION_MEMBER_DAILY_CAP", "0")
    return cap or None


def mint_cost_micro_usd() -> int:
    return _micro_usd("LEGION_MEMBER_MINT_COST_USD", DEFAULT_MINT_COST_USD)


def applies_to(user) -> bool:
    from household.models import is_recruiter

    return bool(getattr(user, "pk", None)) and not is_recruiter(user)


def spent_today_micro_usd(user, now: datetime | None = None) -> int:
    from llm.models import LlmUsage

    now = (now or datetime.now(UTC)).astimezone(UTC)
    midnight = now.replace(hour=0, minute=0, second=0, microsecond=0)
    total = LlmUsage.objects.filter(user=user, created_at__gte=midnight).aggregate(
        spent=Sum("cost_estimate_micro_usd")
    )["spent"]
    return int(total or 0)


class _Admitted:
    """A member mint that fitted. `release()` takes its estimate back when Google refused
    the mint, so an outage never spends the member's day."""

    def __init__(self, row=None):
        self.row = row

    def release(self) -> None:
        if self.row is not None:
            type(self.row).objects.filter(pk=self.row.pk).delete()
            self.row = None


def admit_live_mint(user, household, model: str) -> _Admitted | None:
    """Record a member's mint at its estimate if it fits under today's cap (or the cap is
    off). None: over the cap, nothing recorded, no token may be minted."""
    if not applies_to(user):
        return _Admitted()
    cost = mint_cost_micro_usd()
    cap = cap_micro_usd()
    if cap is not None and spent_today_micro_usd(user) + cost > cap:
        return None
    row = client.record(
        household=household,
        feature=LIVE_MINT_FEATURE,
        model=model,
        user=user,
        cost_estimate_micro_usd=cost,
    )
    return _Admitted(row)


def over_cap(user) -> bool:
    """True when a capped member has already spent today's cap."""
    cap = cap_micro_usd()
    if cap is None or not applies_to(user):
        return False
    return spent_today_micro_usd(user) >= cap
