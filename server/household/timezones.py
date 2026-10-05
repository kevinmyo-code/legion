"""The household's timezone (Kevin, 2026-10-05): which calendar day "today"
is for a household, worked out on the server so no rule has to trust the
client's clock.

**Server-side date math only.** CLAUDE.md section 1: never hand the model an
IANA timezone id - `America/Chicago` is a database key that happens to contain
a city, and asserting one made the assistant talk about Chicago to a man in
Houston. Nothing in this module returns a zone NAME to a caller that builds a
prompt; the assistant asks `utc_offset_minutes` below for a number and nothing
else.

Unset is a real state, not an error: a household that never chose a zone gets
None from every function here, and each caller says what it falls back to.
"""

from __future__ import annotations

from datetime import UTC, date, datetime
from functools import lru_cache
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError, available_timezones


@lru_cache(maxsize=1)
def known_zones() -> frozenset[str]:
    """Every IANA zone name this server's tz database knows (`tzdata` is
    pinned in requirements.txt, so the set does not depend on the host)."""
    return frozenset(available_timezones())


def is_known_zone(name: str) -> bool:
    return name in known_zones()


def zone_named(name: str | None) -> ZoneInfo | None:
    """The zone, or None for unset or a name this server cannot load. A
    stored name the tz database later drops reads as unset rather than a 500:
    every caller already has an unset branch."""
    if not name or not is_known_zone(name):
        return None
    try:
        return ZoneInfo(name)
    except (ZoneInfoNotFoundError, ValueError):
        return None


def household_zone_name(household_id) -> str | None:
    from household.models import Household

    if household_id is None:
        return None
    return (
        Household.objects.filter(pk=household_id).values_list("timezone", flat=True).first()
    )


def local_date(zone: ZoneInfo, now: datetime | None = None) -> date:
    return (now or datetime.now(UTC)).astimezone(zone).date()


def utc_offset_minutes(zone_name: str | None, now: datetime) -> int | None:
    """Minutes EAST of UTC in `zone_name` at `now` (Houston in summer is
    -300), or None when unset. The ONLY shape in which a household's zone may
    travel toward a model prompt."""
    zone = zone_named(zone_name)
    if zone is None:
        return None
    offset = now.astimezone(zone).utcoffset()
    if offset is None:
        return None
    return int(offset.total_seconds() // 60)
