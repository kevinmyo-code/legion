"""Reads of the bought log, shared by REST (`purchases/views.py`) and `/mcp`
(`engine_mcp/tools.py`), so the screen, the web and the voice path read the
same rows through the same filter.

Every read starts from `visible(Purchase, request)`: the household's shared
entries plus this member's own private ones (ADR 0045, ADR 0052).
"""

from __future__ import annotations

from dataclasses import dataclass

from household.tenancy import visible
from purchases.matching import Match, match_entries, matches
from purchases.models import Purchase

LIST_LIMIT_DEFAULT = 100
LIST_LIMIT_MAX = 500


def live(request):
    return (
        visible(Purchase, request)
        .filter(deleted_at__isnull=True)
        .select_related("created_by")
    )


@dataclass(frozen=True)
class Listing:
    rows: list
    truncated: bool


def list_entries(
    request,
    *,
    query: str | None = None,
    from_day: int | None = None,
    to_day: int | None = None,
    source: str | None = None,
    limit: int = LIST_LIMIT_DEFAULT,
) -> Listing:
    """Live entries, newest first, narrowed by any of: a text query (ticket
    02's matcher, so a search here finds what `last_bought` finds), an
    inclusive range of local epoch days, a source."""
    queryset = live(request)
    if from_day is not None:
        queryset = queryset.filter(bought_on__gte=from_day)
    if to_day is not None:
        queryset = queryset.filter(bought_on__lte=to_day)
    if source:
        queryset = queryset.filter(source=source)
    queryset = queryset.order_by("-bought_on", "-logged_at", "-id")
    if query:
        rows = [row for row in queryset if matches(query, row.item)[0]]
    else:
        rows = list(queryset[: limit + 1])
    return Listing(rows=rows[:limit], truncated=len(rows) > limit)


def last_bought(request, query: str) -> list[Match]:
    return match_entries(query, live(request))
