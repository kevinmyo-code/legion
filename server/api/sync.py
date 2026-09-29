"""Shared helpers for the domain API's `?since=` sync convention (django-engine
ticket 04, the Phase 2 slice: events and checklists). Every synced endpoint
in this ticket reads a changed-since watermark the same way and pages the
same way, so the rule lives once here rather than being hand-copied into
`api/events.py`, `checklists/views.py`, and `api/changes.py` separately.
"""
from __future__ import annotations

from datetime import UTC, datetime

from django.core.exceptions import ValidationError
from django.db import DatabaseError, transaction
from django.db.models import Q
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


def paginate_since(queryset, page_size: int = PAGE_SIZE, cursor_field: str = "updated_at"):
    """`queryset` must already be `.order_by(cursor_field)` ascending.
    Returns `(rows, next_iso)`: `next_iso` is the `cursor_field` of the last
    row on this page, ISO-formatted, when a full page came back (there may
    be more rows the caller has not seen yet); `None` when fewer than
    `page_size` rows came back, meaning this was the last page. A caller
    pages by re-requesting with `since=<next>` until `next` comes back
    null - the same shape ticket 04's own brief describes ("page size 500
    with a next cursor").

    **`cursor_field` is `updated_at` for every table that HAS one, and this
    parameter exists because five of them do not.** `statements`,
    `ledger_transactions`, `receipts` and `receipt_line_items` carry
    `created_at` and nothing else - they are the section 4 gate's output and
    `private.forbid_mutation_of_facts` blocks UPDATE on all four outright, so
    a column meaning "when this last changed" would have no writer.
    `ingested_files` carries `first_seen_at` and `last_attempt_at`, and the
    second is the one that MOVES (every commit and every retry rewrites it in
    `ingest.views._upsert_file`), so it is the only honest watermark there.
    Confirmed against the live schema on 2026-09-07 through
    `information_schema.columns`, not inferred from the model files.
    `LedgerBackend.fetchChangedTransactionsSince` and
    `PantryBackend.fetchChangedReceiptsSince` already state the same choice on
    the phone side, in words, as does `RemoteLedgerTransaction`'s own doc
    comment: `created_at >= sinceMs`, inclusive, insert-if-absent only.

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
        return page, DateTimeField().to_representation(getattr(page[-1], cursor_field))
    return rows, None


def parse_after(queryset, raw: str | None):
    """`?after=<pk>`, coerced by the model's own primary-key field, or None.

    None for absent AND for unparsable, and None means "no tiebreak": the
    page is then the old inclusive `cursor_field >= since` read. Same
    posture as `parse_since` - a caller that cannot be understood sees too
    much, never silently nothing.
    """
    if not raw:
        return None
    try:
        return queryset.model._meta.pk.to_python(raw)
    except ValidationError:
        return None


def paginate_keyset(queryset, since, after, page_size: int = PAGE_SIZE, cursor_field: str = "updated_at"):
    """`paginate_since` with a primary-key tiebreak, for `SyncedModelViewSet`.

    **Why it exists: the gated tables share one timestamp per ingest.**
    `ledger_transactions` is keyed on `created_at`, and the gate writes every
    line of one statement in one `bulk_create` with `created_at=Now()` -
    Postgres's transaction time, identical on every row. A statement or a
    card export of more than `page_size` lines therefore filled a whole page
    with one timestamp, `next` repeated the cursor it was given, and a client
    following the documented loop stopped there, holding a truncated table
    and no way to know. That was harmless-looking while the phone only
    inserted what was missing. It is not harmless once the phone replaces
    its set of server rows with what the server lists, because a truncated
    list reads as "the server deleted these".

    The fix is the ordinary keyset: order by `(cursor_field, pk)`, and hand
    back the last row's `pk` as `next_after` beside the unchanged `next`. A
    caller that sends both gets rows strictly after that position; a caller
    that sends only `since` gets exactly what it got before, so an installed
    client that has never heard of `after` is unaffected.

    `queryset` must NOT already be filtered on `since` - this does it.
    Returns `(rows, next_iso, next_after)`; both cursors are None on the last
    page.
    """
    if after is None:
        queryset = queryset.filter(**{f"{cursor_field}__gte": since})
    else:
        queryset = queryset.filter(
            Q(**{f"{cursor_field}__gt": since}) | Q(**{cursor_field: since, "pk__gt": after})
        )
    queryset = queryset.order_by(cursor_field, "pk")
    rows = list(queryset[: page_size + 1])
    if len(rows) > page_size:
        page = rows[:page_size]
        last = page[-1]
        return page, DateTimeField().to_representation(getattr(last, cursor_field)), str(last.pk)
    return rows, None, None


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
