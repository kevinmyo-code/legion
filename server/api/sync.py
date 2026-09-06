"""Shared helpers for the domain API's `?since=` sync convention (django-engine
ticket 04, the Phase 2 slice: events and checklists). Every synced endpoint
in this ticket reads a changed-since watermark the same way and pages the
same way, so the rule lives once here rather than being hand-copied into
`api/events.py`, `checklists/views.py`, and `api/changes.py` separately.
"""
from __future__ import annotations

from datetime import UTC, datetime

from django.db import DatabaseError, transaction
from django.utils.dateparse import parse_datetime
from rest_framework import status
from rest_framework.fields import DateTimeField
from rest_framework.response import Response

PAGE_SIZE = 500

# A missing or unparsable `since` means "fetch everything", never "fetch
# nothing" - the same rule the phone's own pull cursor states by name
# (`backend/EventsSync.kt`'s `EventsPullCursor` doc comment: "a missing
# watermark means fetch everything, never fetch nothing... there is no
# separate 'never pulled' sentinel to get wrong"). Mirrored here so a fresh
# device's first sync, or a caller that forgot the parameter, gets the same
# safe default rather than a query that silently returns nothing.
EPOCH = datetime(1970, 1, 1, tzinfo=UTC)


def parse_since(raw: str | None) -> datetime:
    """`raw` is the `?since=` query string, or None if the caller omitted
    it. An unparsable value degrades to EPOCH rather than raising - the
    same "missing means everything" posture, applied to "garbled" as well
    as "absent", since a caller that cannot be understood is not
    distinguishable from one who said nothing."""
    if not raw:
        return EPOCH
    parsed = parse_datetime(raw)
    if parsed is None:
        return EPOCH
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=UTC)
    return parsed


def paginate_since(queryset, page_size: int = PAGE_SIZE):
    """`queryset` must already be `.order_by("updated_at")` ascending.
    Returns `(rows, next_iso)`: `next_iso` is the `updated_at` of the last
    row on this page, ISO-formatted, when a full page came back (there may
    be more rows the caller has not seen yet); `None` when fewer than
    `page_size` rows came back, meaning this was the last page. A caller
    pages by re-requesting with `since=<next>` until `next` comes back
    null - the same shape ticket 04's own brief describes ("page size 500
    with a next cursor").

    **`next` is rendered with DRF's own `DateTimeField`, not Python's
    `.isoformat()`.** This line used to read `page[-1].updated_at.isoformat()`,
    which renders UTC as `+00:00`; a raw `+` in an un-percent-encoded query
    string decodes to a literal SPACE, so a client handing `next` straight
    back as `?since=<next>` corrupted its own watermark. That footgun was
    found empirically while building the Phase 2 slice and is written up at
    length in `api/changes.py`, which already dodged it for `server_time`.
    Changed here in Phase 5 so every cursor this API hands out has the same
    `Z` suffix as every timestamp inside the rows.
    """
    rows = list(queryset[: page_size + 1])
    if len(rows) > page_size:
        page = rows[:page_size]
        return page, DateTimeField().to_representation(page[-1].updated_at)
    return rows, None


def save_or_400(save_callable):
    """Runs `save_callable()` inside its own transaction SAVEPOINT, catching
    any `DatabaseError` - a Postgres CHECK constraint or trigger refusal
    that slipped past this API's own Python-level validation (an
    intentionally-unenumerated one, e.g. `events_recurring_not_done`, or a
    trigger backstop like `checklists_enforce_measured_tick`) - and turning
    it into a 400 naming what the database said, never a 500.

    **The SAVEPOINT is load-bearing, not decoration.** Postgres aborts an
    ENTIRE transaction on any statement error, not just the one statement -
    so catching the exception in plain Python without `transaction.atomic()`
    around it would leave the connection in "current transaction is
    aborted" for every query that runs afterward IN THE SAME transaction,
    which is exactly the shape of every test in this suite (pytest-django
    wraps each test in one outer transaction). `transaction.atomic()`
    nested inside an already-open transaction is a SAVEPOINT, not a second
    top-level transaction - only the failed statement's effects roll back,
    and the outer transaction (the rest of the request, or the rest of the
    test) stays usable. Confirmed empirically, not assumed: a version of
    this helper without the `atomic()` wrapper made every assertion AFTER
    the expected-400 call fail with exactly that Postgres error.

    Returns `(result, error_response)` - exactly one is not-None. Callers
    always check `error_response` first:

        instance, error = save_or_400(lambda: serializer.save())
        if error is not None:
            return error
    """
    try:
        with transaction.atomic():
            return save_callable(), None
    except DatabaseError as exc:
        return None, Response(
            {"detail": f"That write was refused by the database: {exc}"},
            status=status.HTTP_400_BAD_REQUEST,
        )
