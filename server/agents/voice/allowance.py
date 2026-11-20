"""A member's daily spend on the agents voice door, bounded even when no cap is set (review
2026-11-19, C3).

The member cap (`llm/cap.py`, `LEGION_MEMBER_DAILY_CAP`) is off unless it is set, and then only
the per-minute throttles bound a member. This door adds a code default for that case: a member
whose agent turns (text rounds and speech, `feature="agent_turn"`) have cost `DEFAULT_DAILY_USD`
or more since UTC midnight is refused before any paid call, until midnight. With the cap set,
the cap decides, as everywhere else.
"""

from __future__ import annotations

from datetime import UTC, datetime

from django.db.models import Sum

from llm import cap as member_cap

FEATURE = "agent_turn"
DEFAULT_DAILY_USD = 1
DEFAULT_DAILY_MICRO_USD = DEFAULT_DAILY_USD * 1_000_000


def spent_today_micro_usd(user, now: datetime | None = None) -> int:
    """What this member's agent turns have cost since UTC midnight (estimates, as recorded)."""
    from llm.models import LlmUsage

    now = (now or datetime.now(UTC)).astimezone(UTC)
    midnight = now.replace(hour=0, minute=0, second=0, microsecond=0)
    total = LlmUsage.objects.filter(user=user, feature=FEATURE, created_at__gte=midnight).aggregate(
        spent=Sum("cost_estimate_micro_usd")
    )["spent"]
    return int(total or 0)


def over_cap(user) -> bool:
    """True when the member may not start another agent turn today."""
    if member_cap.over_cap(user):
        return True
    if member_cap.cap_micro_usd() is not None or not member_cap.applies_to(user):
        return False
    return spent_today_micro_usd(user) >= DEFAULT_DAILY_MICRO_USD
