"""A head unit's own daily spend on agent turns (review B6), inside its member's.

A turn made with a unit token spends the member's household key, so a stolen unit could burn the
member's whole daily allowance. Each credential therefore gets its own cap: `DEFAULT_DAILY_USD`
($0.25) a UTC day unless `LEGION_HEADUNIT_DAILY_CAP_USD` says otherwise, checked before any paid
call, on top of the member's own cap (`agents.voice.allowance`), which still applies.

**How a turn is counted.** The turn's cost is what the member's `agent_turn` metering grew by
while the unit's turn ran (text rounds and speech, `llm_usage`). A phone turn of the same member
running at the same moment is counted against the unit too: that only ever narrows the unit.
A turn in flight when the cap is reached finishes; the next is refused.
"""

from __future__ import annotations

import os
from datetime import UTC, date, datetime
from decimal import Decimal, InvalidOperation

from django.db import transaction
from django.db.models import F

from agents.voice import allowance
from headunit_signin.models import HeadUnitCredential

CAP_ENV = "LEGION_HEADUNIT_DAILY_CAP_USD"
DEFAULT_DAILY_USD = "0.25"
USED_UP = (
    "Nothing was answered: this head unit has used its own allowance for today. It resets at "
    "midnight UTC; the member's phone is not affected."
)


def cap_micro_usd() -> int:
    raw = os.environ.get(CAP_ENV, "").strip() or DEFAULT_DAILY_USD
    try:
        value = Decimal(raw)
    except InvalidOperation:
        value = Decimal(DEFAULT_DAILY_USD)
    return max(int(value * 1_000_000), 0)


def _today() -> date:
    return datetime.now(UTC).date()


def spent_today_micro_usd(credential: HeadUnitCredential) -> int:
    return credential.spent_micro_usd if credential.spend_day == _today() else 0


def over_cap(credential: HeadUnitCredential) -> bool:
    return spent_today_micro_usd(credential) >= cap_micro_usd()


def member_spent(user) -> int:
    """The member's agent-turn metering so far today: read before a unit's turn, and after."""
    return allowance.spent_today_micro_usd(user)


def charge(credential: HeadUnitCredential, micro_usd: int) -> None:
    """Add a finished turn's cost to the unit's day."""
    if micro_usd <= 0:
        return
    today = _today()
    with transaction.atomic():
        row = HeadUnitCredential.objects.select_for_update().get(pk=credential.pk)
        if row.spend_day != today:
            HeadUnitCredential.objects.filter(pk=row.pk).update(
                spend_day=today, spent_micro_usd=micro_usd
            )
        else:
            HeadUnitCredential.objects.filter(pk=row.pk).update(
                spent_micro_usd=F("spent_micro_usd") + micro_usd
            )
