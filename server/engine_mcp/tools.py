"""The engine's MCP tools: one registry, and the only place one is defined.

engine-mcp ticket 03 (Kevin, 2026-10-02): "a curated Python tool registry on
the server, each tool calling existing service code". Name, description,
input schema, whether it writes, whether it deletes, and the code it runs all
live in one `EngineTool` below; `/mcp`'s `tools/list` and `tools/call` read
`TOOLS` and nothing else. A future Gemini-declaration emitter for the phone
(ticket 11) reads the same list.

## What a tool may do (tickets 06 and 07, CLAUDE.md sections 4 and 7)

- **Reads** use each table's own REST viewset - its `queryset()` (household
  scoped through `household_of`, ADR 0045's choke point) and its serializer -
  so a row reads over MCP exactly as it reads over REST.
- **Writes** never touch a serializer or a model from here. They call the
  ROUTED view (`engine_mcp/dispatch.py`), so a write over MCP is a write over
  REST: same validation, same tenancy, same refusal. Business rules stay in
  Django and Postgres once.
- **The gated tables are never writable** (section 4): no write tool lists
  them, and naming one gets the gate's own refusal sentence, the one REST
  answers with 405.
- **Memory tables are excluded** (ticket 06 sub-question 2, Kevin 2026-10-02):
  no tool reads or writes `memories`, `companion_memories` or `memory_audit`.
- **No ingestion tool** (ticket 07, Kevin 2026-10-02): nothing here submits a
  document to the gate, until the statement CSV format exists.
- **No third-party content** (section 7): mail never reaches the server, so no
  tool can expose it. `tests/test_engine_mcp.py` fails on a tool that names it.
- **A write tool refuses a read-scoped token in words** before it runs
  (`household.permissions.token_may_write`).

## The honesty contract, in the result (ticket 06 sub-question 4)

A client outside LEGION is not bound by `CANNOT_CLAUSE`; the result is the
only lever. So every result here:

- on failure sets `is_error` and says in words what did NOT happen;
- reports a write only after the routed view answered 2xx, and quotes the row
  as stored;
- says provenance in words on every row that carries it (an UNRECONCILED row
  reads "UNVERIFIED", never only a field value - section 4 rule 7);
- labels estimates as estimates (section 4 rule 5);
- tells unreadable from empty: an empty read says it is a real empty result,
  a failed read says nothing is known.
"""

from __future__ import annotations

import json
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, date, datetime, time, timedelta

from django.utils.dateparse import parse_date, parse_datetime
from jsonschema import Draft202012Validator

from api.event_columns import KIND_SUGGESTION
from api.registry import SYNCED_VIEWSETS
from engine_mcp.dispatch import RouteNotFound, call_route
from engine_mcp.models import Outcome
from household.permissions import READ_SCOPE_REFUSAL, token_may_write
from household.tenancy import visible

# =============================================================================
# Results
# =============================================================================


@dataclass(frozen=True)
class ToolResult:
    text: str
    is_error: bool = False
    outcome: str = Outcome.OK
    structured: dict | None = None


def ok(text: str, structured: dict | None = None) -> ToolResult:
    return ToolResult(text=text, structured=structured)


def refused(text: str) -> ToolResult:
    """Refused before anything ran. Nothing was read or written."""
    return ToolResult(text=text, is_error=True, outcome=Outcome.REFUSED)


def failed(text: str) -> ToolResult:
    """Ran and failed. The text says what did not happen."""
    return ToolResult(text=text, is_error=True, outcome=Outcome.FAILED)


def _json(value) -> str:
    return json.dumps(value, default=str, ensure_ascii=False)


# =============================================================================
# Which tables a tool may touch
# =============================================================================

# Ticket 06 sub-question 2, Kevin 2026-10-02 ("go with your recs"): excluded by
# default. An aspect, not a table list, so a fourth memory table is excluded
# the day it is registered rather than the day someone remembers this file.
EXCLUDED_ASPECTS = frozenset({"memory"})

_BY_TABLE = {viewset.table: viewset for viewset in SYNCED_VIEWSETS}


def _single_segment_identity(viewset) -> bool:
    """True when the row's identity is one URL segment. `maintenance_schedules`
    is keyed on a PAIR and is left to REST for now rather than given a second
    identity grammar here."""
    return viewset.detail_path_suffix() == f"<{viewset.identity_url_converter}:identity>/"


EXCLUDED_TABLES = frozenset(t for t, v in _BY_TABLE.items() if v.aspect in EXCLUDED_ASPECTS)
GATED_TABLES = frozenset(t for t, v in _BY_TABLE.items() if not v.writable)
READABLE = {t: v for t, v in _BY_TABLE.items() if t not in EXCLUDED_TABLES}
WRITABLE = {
    t: v
    for t, v in READABLE.items()
    if v.writable and not v.identity_is_primary_key and _single_segment_identity(v)
}
DELETABLE = {t: v for t, v in WRITABLE.items() if v.allow_delete}

READ_LIMIT_DEFAULT = 50
READ_LIMIT_MAX = 200


def _table_refusal(table: str, verb: str) -> str:
    """Why `table` cannot be `verb` ('read', 'written', 'deleted'), in words,
    with the nearest thing there is a path to."""
    if table in EXCLUDED_TABLES:
        return (
            f"Nothing was {verb}. {table} is a memory table, and memory tables are excluded "
            f"from this engine's MCP tools by the household's own ruling (2026-10-02): no "
            f"MCP tool reads or writes what the companion remembers."
        )
    if table in GATED_TABLES:
        method = "DELETE" if verb == "deleted" else "PUT"
        # The gate's own sentence, from the viewset REST answers 405 with, so
        # the two surfaces refuse in the same words.
        return _BY_TABLE[table]()._gate_refusal(method).data["detail"]
    if table in READABLE:
        viewset = READABLE[table]
        if viewset.identity_is_primary_key:
            why = (
                f"its rows are keyed on an id the server mints, so they are created by "
                f"POST /api/{viewset.route_prefix()} from the app, not by this tool"
            )
        elif verb == "deleted" and not viewset.allow_delete:
            why = viewset.no_delete_reason or "it has no delete route"
        else:
            why = (
                "its identity is more than one value, and this tool takes a single "
                "identity; it is written from the app"
            )
        return f"Nothing was {verb}. {table} cannot be {verb} by this tool: {why}."
    return (
        f"Nothing was {verb}. This engine has no table called {table!r}. Tables it can "
        f"read: {', '.join(sorted(READABLE))}."
    )


# =============================================================================
# Saying provenance and estimates in words
# =============================================================================

PROVENANCE_WORDS = {
    "DETERMINISTIC": (
        "verified: passed the reconciliation gate against the document's own stated "
        "totals, extracted deterministically"
    ),
    "LLM_RECONCILED": (
        "verified: passed the reconciliation gate against the document's own stated "
        "totals; the lines were extracted by a model"
    ),
    "UNRECONCILED": (
        "UNVERIFIED: this row did not pass the reconciliation gate. It is provisional "
        "and must never be stated as fact"
    ),
    "USER": "entered by a person",
    # ADR 0057 (Kevin, 2026-10-09): the bank's own feed is fact, said plainly,
    # never as unverified.
    "BANK_API": "fact: from the bank's own transaction feed",
    "LLM_DERIVED": "derived by a model from a recording",
}

# Section 4 rule 5: what the source never stated is an estimate, said so.
ESTIMATE_FIELDS = {
    "meal_logs": ("calories_kcal", "protein_g", "carbs_g", "fat_g"),
    "receipt_line_items": (
        "estimated_calories_kcal",
        "estimated_protein_g",
        "estimated_carbs_g",
        "estimated_fat_g",
    ),
    "service_history": ("mileage",),
}


def _say(table: str, row: dict) -> str:
    """The words that ride on one row: provenance, estimates, the unexplained
    amount. Empty when the row carries none of them."""
    said = []
    provenance = row.get("provenance")
    if provenance:
        said.append(f"provenance {provenance}: {PROVENANCE_WORDS.get(provenance, provenance)}")
    estimates = [f for f in ESTIMATE_FIELDS.get(table, ()) if row.get(f) is not None]
    if estimates:
        said.append(
            f"ESTIMATES, not measurements: {', '.join(estimates)} (the source never "
            f"stated them; a model guessed)"
        )
    if row.get("unaccounted_cents") is not None:
        said.append(
            "unaccounted_cents is an amount this receipt's lines do not explain. It is not "
            "tax and is never added into a total"
        )
    if row.get("display_description") is not None:
        said.append(
            "display_description is a merchant name the household chose (a merchant alias); "
            "description is the bank's own text, unchanged"
        )
    return "; ".join(said)


def _annotate(table: str, rows: list[dict]) -> tuple[list[dict], int]:
    unverified = 0
    for row in rows:
        words = _say(table, row)
        if words:
            row["_engine_says"] = words
        if row.get("provenance") == "UNRECONCILED":
            unverified += 1
    return rows, unverified


# =============================================================================
# Argument parsing helpers. A value that cannot be understood is refused in
# words, never silently widened (REST's `parse_since` degrades garbage to the
# epoch; a model deserves to hear it was misunderstood).
# =============================================================================


class BadArgument(Exception):
    pass


def _parse_instant(raw: str, name: str, *, end_of_day: bool = False) -> datetime:
    parsed = parse_datetime(raw)
    if parsed is None:
        day = parse_date(raw)
        if day is None:
            raise BadArgument(f"{name} {raw!r} is not an ISO date or date-time")
        parsed = datetime.combine(day, time.max if end_of_day else time.min)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=UTC)
    return parsed


def _parse_day(raw: str, name: str) -> date:
    day = parse_date(raw)
    if day is None:
        raise BadArgument(f"{name} {raw!r} is not an ISO date (YYYY-MM-DD)")
    return day


def _viewset_for(request, viewset_cls):
    """The REST viewset, bound to this request, so its own `queryset()` and
    serializer do the reading."""
    view = viewset_cls()
    view.request = request
    view.format_kwarg = None
    view.args = ()
    view.kwargs = {}
    return view


def _detail(response) -> str:
    data = getattr(response, "data", None)
    if isinstance(data, dict) and set(data) == {"detail"}:
        return str(data["detail"])
    if data in (None, ""):
        return f"HTTP {response.status_code}"
    return _json(data)


def _write_result(response, what: str, verb: str, *, created_status: int | None = None):
    """Turn the routed view's answer into a result. 2xx is the only thing
    that counts as written; anything else says nothing was."""
    status = response.status_code
    if 200 <= status < 300:
        if status == 204:
            return ok(
                f"Deleted {what}. The engine set its tombstone (deleted_at); the row stays "
                f"in the database so every device learns it is gone."
            )
        return ok(
            f"{verb.capitalize()} {what}. The engine committed it; the row as stored: "
            f"{_json(response.data)}",
            structured={"row": response.data},
        )
    text = f"Nothing was {verb}. The engine refused it (HTTP {status}): {_detail(response)}"
    if status in (403, 405):
        return refused(text)
    return failed(text)


# =============================================================================
# Handlers
# =============================================================================


def _list_tables(request, args) -> ToolResult:
    lines = []
    for table, viewset in sorted(READABLE.items(), key=lambda kv: (kv[1].aspect, kv[0])):
        if table in WRITABLE:
            mode = f"read and write (identity: {viewset.identity_field})"
            if table not in DELETABLE:
                mode += ", no delete"
        elif table in GATED_TABLES:
            mode = f"read only: written by the reconciliation gate alone ({viewset.gate_endpoint})"
        else:
            mode = "read only over MCP"
        lines.append(f"- {table} [{viewset.aspect}]: {mode}")
    return ok(
        "Tables this engine's MCP tools can reach, for this household:\n"
        + "\n".join(lines)
        + "\nMemory tables (memories, companion_memories, memory_audit) are excluded by the "
        "household's ruling. Events, checklists and the bought log have their own tools. "
        "Money is integer cents."
    )


def _read_records(request, args) -> ToolResult:
    table = args["table"]
    if table not in READABLE:
        return refused(_table_refusal(table, "read"))
    viewset = READABLE[table]
    view = _viewset_for(request, viewset)
    queryset = view.queryset()
    active_only = args.get("active_only", True)
    if active_only and viewset.has_tombstones:
        queryset = queryset.filter(deleted_at__isnull=True)
    since = args.get("since")
    if since:
        try:
            queryset = queryset.filter(
                **{f"{viewset.cursor_field}__gte": _parse_instant(since, "since")}
            )
        except BadArgument as exc:
            return refused(f"Nothing was read. {exc}.")
    limit = args.get("limit", READ_LIMIT_DEFAULT)
    rows = list(queryset.order_by(f"-{viewset.cursor_field}", "-pk")[: limit + 1])
    more = len(rows) > limit
    data, unverified = _annotate(table, list(view._serialize(rows[:limit], many=True)))
    scope = "live rows" if active_only and viewset.has_tombstones else "rows, tombstones included"
    if not data:
        return ok(
            f"The engine read {table} for this household and found no {scope}"
            f"{' changed since ' + since if since else ''}. That is a real empty result, "
            f"not a failure to read.",
            structured={"table": table, "rows": []},
        )
    head = f"{len(data)} {table} {scope}, newest first"
    if more:
        head += f"; there are more than {limit}, and only the newest {limit} are shown"
    if unverified:
        head += (
            f". {unverified} of them are UNVERIFIED (UNRECONCILED): provisional, never to be "
            f"stated as fact"
        )
    return ok(f"{head}:\n{_json(data)}", structured={"table": table, "rows": data})


def _list_events(request, args) -> ToolResult:
    from api.events import EventSerializer
    from legacy.models.dates import Event

    try:
        start = _parse_instant(args["from"], "from") if args.get("from") else datetime.now(UTC)
        end = (
            _parse_instant(args["to"], "to", end_of_day=True)
            if args.get("to")
            else start + timedelta(days=14)
        )
    except BadArgument as exc:
        return refused(f"Nothing was read. {exc}.")
    if end <= start:
        return refused("Nothing was read. `to` must be after `from`.")

    live = visible(Event, request).filter(deleted_at__isnull=True)
    if not args.get("include_done", False):
        live = live.filter(done=False)
    # Suggestions (Kevin, 2026-10-09) are things the household COULD do, never
    # their plans: their own section, never mixed into the three below.
    suggested = live.filter(
        kind=KIND_SUGGESTION, starts_at__gte=start, starts_at__lte=end
    ).order_by("starts_at")[:READ_LIMIT_MAX]
    live = live.exclude(kind=KIND_SUGGESTION)
    one_off = live.filter(
        repeat_kind__isnull=True, starts_at__gte=start, starts_at__lte=end
    ).order_by("starts_at")[:READ_LIMIT_MAX]
    repeating = live.filter(repeat_kind__isnull=False, starts_at__lte=end).order_by("starts_at")[
        :READ_LIMIT_MAX
    ]
    undated = live.filter(starts_at__isnull=True).order_by("sort_order", "created_at")[
        :READ_LIMIT_MAX
    ]
    sections = {
        "in_window": EventSerializer(one_off, many=True).data,
        "repeating": EventSerializer(repeating, many=True).data,
        "undated": EventSerializer(undated, many=True).data,
        "suggestions": EventSerializer(suggested, many=True).data,
    }
    window = f"{start.isoformat()} to {end.isoformat()}"
    if not any(sections.values()):
        return ok(
            f"The engine read this household's events and found none in {window}, no "
            f"repeating events starting by then, and no undated tasks"
            f"{'' if args.get('include_done') else ' that are not done'}. That is a real "
            f"empty result, not a failure to read.",
            structured=sections,
        )
    return ok(
        f"Events for {window} (UTC unless the row says otherwise):\n"
        f"- in_window: {len(sections['in_window'])} one-off events starting in the window.\n"
        f"- repeating: {len(sections['repeating'])} repeating events whose FIRST occurrence is "
        f"on or before the window's end. They are NOT expanded into occurrences: read each "
        f"one's repeat_* fields before saying whether it falls in the window.\n"
        f"- undated: {len(sections['undated'])} tasks with no date.\n"
        f"- suggestions: {len(sections['suggestions'])} things the household could do in the "
        f"window. NOT plans: never say they have one on, are going, or are busy then. "
        f"`pinned_by` names the members who want to go to one: a wish, still not a plan.\n"
        f"{_json(sections)}",
        structured=sections,
    )


def _list_checklists(request, args) -> ToolResult:
    from checklists.models import Checklist, ChecklistItem, ChecklistTick
    from checklists.serializers import ChecklistItemSerializer, ChecklistSerializer

    on_day = None
    if args.get("date"):
        try:
            on_day = _parse_day(args["date"], "date")
        except BadArgument as exc:
            return refused(f"Nothing was read. {exc}.")
    checklists = visible(Checklist, request).filter(deleted_at__isnull=True)
    if not args.get("include_archived", False):
        checklists = checklists.filter(archived=False)
    checklists = list(checklists.order_by("sort_order", "name")[:READ_LIMIT_MAX])
    if not checklists:
        return ok(
            "The engine read this household's checklists and found none. That is a real "
            "empty result, not a failure to read.",
            structured={"checklists": []},
        )
    items = (
        visible(ChecklistItem, request)
        .filter(deleted_at__isnull=True, checklist__in=checklists)
        .order_by("sort_order", "created_at")
    )
    ticked = set()
    if on_day is not None:
        epoch_day = (on_day - date(1970, 1, 1)).days
        ticked = set(
            visible(ChecklistTick, request)
            .filter(deleted_at__isnull=True, day=epoch_day, item__in=items)
            .values_list("item_id", flat=True)
        )
    by_list: dict = {}
    for item in items:
        row = ChecklistItemSerializer(item).data
        if on_day is not None:
            row["ticked_on_date"] = item.id in ticked
        by_list.setdefault(item.checklist_id, []).append(row)
    out = []
    for checklist in checklists:
        row = ChecklistSerializer(checklist).data
        row["items"] = by_list.get(checklist.id, [])
        out.append(row)
    tick_note = (
        f" Each item carries ticked_on_date for {on_day.isoformat()}."
        if on_day
        else " No date was given, so no tick state is shown; pass `date` to see it."
    )
    return ok(
        f"{len(out)} checklists with their live items.{tick_note}\n{_json(out)}",
        structured={"checklists": out},
    )


def _write_record(request, args) -> ToolResult:
    table, identity = args["table"], args["identity"]
    if table not in WRITABLE:
        return refused(_table_refusal(table, "written"))
    viewset = WRITABLE[table]
    try:
        response = call_route(
            request, "PUT", f"/api/{viewset.route_prefix()}{identity}/", args["fields"]
        )
    except RouteNotFound:
        return refused(
            f"Nothing was written. {identity!r} is not a valid {viewset.identity_field} for "
            f"{table}."
        )
    return _write_result(response, f"{table} row {identity!r}", "written")


def _delete_record(request, args) -> ToolResult:
    table, identity = args["table"], args["identity"]
    if table not in DELETABLE:
        return refused(_table_refusal(table, "deleted"))
    viewset = DELETABLE[table]
    try:
        response = call_route(request, "DELETE", f"/api/{viewset.route_prefix()}{identity}/")
    except RouteNotFound:
        return refused(
            f"Nothing was deleted. {identity!r} is not a valid {viewset.identity_field} for "
            f"{table}."
        )
    return _write_result(response, f"{table} row {identity!r}", "deleted")


def _add_event(request, args) -> ToolResult:
    response = call_route(request, "POST", "/api/events", args["fields"])
    if response.status_code == 200:
        # The view's idempotent-retry answer: this origin_guid already exists.
        return ok(
            f"Nothing was created: an event with origin_guid "
            f"{args['fields'].get('origin_guid')!r} already exists, and it was left exactly "
            f"as it was. The existing row: {_json(response.data)}",
            structured={"row": response.data},
        )
    return _write_result(response, "a new event", "written")


def _update_event(request, args) -> ToolResult:
    try:
        response = call_route(request, "PATCH", f"/api/events/{args['id']}", args["fields"])
    except RouteNotFound:
        return refused(f"Nothing was written. {args['id']!r} is not an event id.")
    return _write_result(response, f"event {args['id']}", "written")


def _delete_event(request, args) -> ToolResult:
    try:
        response = call_route(request, "DELETE", f"/api/events/{args['id']}")
    except RouteNotFound:
        return refused(f"Nothing was deleted. {args['id']!r} is not an event id.")
    return _write_result(response, f"event {args['id']}", "deleted")


def _add_checklist_item(request, args) -> ToolResult:
    fields = {k: v for k, v in args.items() if k != "checklist_id"}
    try:
        response = call_route(
            request, "POST", f"/api/checklists/{args['checklist_id']}/items", fields
        )
    except RouteNotFound:
        return refused(f"Nothing was written. {args['checklist_id']!r} is not a checklist id.")
    return _write_result(response, f"a new item on checklist {args['checklist_id']}", "written")


def _tick_checklist_item(request, args) -> ToolResult:
    try:
        on_day = _parse_day(args["date"], "date")
    except BadArgument as exc:
        return refused(f"Nothing was written. {exc}.")
    body = {"day": (on_day - date(1970, 1, 1)).days}
    if "value" in args:
        body["value"] = args["value"]
    path = f"/api/checklists/{args['checklist_id']}/items/{args['item_id']}/tick"
    try:
        response = call_route(request, "POST", path, body)
    except RouteNotFound:
        return refused("Nothing was written. checklist_id and item_id must both be ids.")
    if response.status_code == 200:
        return ok(
            f"Item {args['item_id']} is ticked for {on_day.isoformat()}. It already had a "
            f"tick for that day (kept as it was), or one that had been unticked and is now "
            f"ticked again. The tick as stored: {_json(response.data)}",
            structured={"row": response.data},
        )
    return _write_result(response, f"a tick on item {args['item_id']} for {on_day}", "written")


# =============================================================================
# The bought log (purchase-log ticket 06, ADR 0055). Reads go through
# `purchases/queries.py`, the same functions REST reads with; writes call the
# routed `/api/purchases` view, like every other write here.
# =============================================================================


def _cents_words(cents: int) -> str:
    return f"{cents // 100}.{cents % 100:02d}"


def _say_purchase(row: dict) -> str:
    """One entry in words. Names the entry's own text, its date, who logged
    it (or that nobody was recorded), and says a price was entered by hand."""
    from purchases.matching import say_date

    who = row.get("logged_by") or "who logged it was not recorded"
    line = f"{row['item']} on {say_date(row['bought_on'])} ({who})"
    if row.get("source") != "MANUAL":
        line += ", ticked off the Groceries list"
    if row.get("store"):
        line += f", at {row['store']}"
    if row.get("price_cents") is not None:
        line += f", price {_cents_words(row['price_cents'])} (entered by hand, never checked)"
    if row.get("quantity_note"):
        line += f", note: {row['quantity_note']}"
    if row.get("visibility") == "private":
        line += ", private to you"
    return line


def _day_arg(args, name):
    from purchases.matching import date_to_day

    return date_to_day(_parse_day(args[name], name)) if args.get(name) else None


def _log_purchase(request, args) -> ToolResult:
    from purchases.matching import say_date

    try:
        bought_on = _day_arg(args, "date")
    except BadArgument as exc:
        return refused(f"Nothing was logged. {exc}.")
    body = {
        "item": args["item"],
        "bought_on": bought_on,
        "visibility": "private" if args.get("private") else "shared",
    }
    for key in ("store", "price_cents", "quantity_note", "sync_id"):
        if key in args:
            body[key] = args[key]
    response = call_route(request, "POST", "/api/purchases/", body)
    status = response.status_code
    if status == 200:
        return ok(
            f"Nothing was logged: an entry with sync_id {args.get('sync_id')!r} already "
            f"exists, and it was left as it was: {_say_purchase(response.data)}.",
            structured={"row": response.data},
        )
    if status == 201:
        row = response.data
        who = "private to you" if row["visibility"] == "private" else "shared with the household"
        return ok(
            f"Logged {row['item']} as bought on {say_date(row['bought_on'])}, {who}. The "
            f"engine committed it; the entry as stored: {_json(row)}",
            structured={"row": row},
        )
    text = f"Nothing was logged. The engine refused it (HTTP {status}): {_detail(response)}"
    return refused(text) if status in (403, 405) else failed(text)


def _last_bought(request, args) -> ToolResult:
    from purchases.matching import is_searchable, say_last_bought
    from purchases.queries import last_bought
    from purchases.serializers import PurchaseSerializer

    item = args["item"].strip()
    if not is_searchable(item):
        return refused("Nothing was read. Say what was bought, in letters or numbers.")
    found = last_bought(request, item)
    matches = [
        {
            "entry": PurchaseSerializer(m.entry, context={"request": request}).data,
            "exact": m.exact,
            "times_logged": m.times_logged,
        }
        for m in found
    ]
    text = say_last_bought(item, found)
    if not found:
        text += (
            " The engine read this household's bought log; that is the absence of a record, "
            "not proof it was never bought."
        )
    elif any(not m.exact for m in found):
        text += " Say the entry's own words, not the question's: some are loose matches."
    return ok(text, structured={"query": item, "matches": matches})


def _list_purchases(request, args) -> ToolResult:
    from purchases.queries import LIST_LIMIT_DEFAULT, list_entries
    from purchases.serializers import PurchaseSerializer

    try:
        from_day = _day_arg(args, "from")
        to_day = _day_arg(args, "to")
    except BadArgument as exc:
        return refused(f"Nothing was read. {exc}.")
    query = (args.get("query") or "").strip() or None
    listing = list_entries(
        request,
        query=query,
        from_day=from_day,
        to_day=to_day,
        source=args.get("source"),
        limit=args.get("limit", LIST_LIMIT_DEFAULT),
    )
    rows = PurchaseSerializer(listing.rows, many=True, context={"request": request}).data
    window = ""
    if args.get("from") or args.get("to"):
        window = f" between {args.get('from') or 'the start'} and {args.get('to') or 'now'}"
    if not rows:
        if query:
            text = (
                f"I have no record of buying {query}{window}. The engine read this "
                f"household's bought log; that is the absence of a record, not proof it was "
                f"never bought."
            )
        else:
            text = (
                f"The engine read this household's bought log and found no entries{window}. "
                f"That is a real empty result, not a failure to read."
            )
        return ok(text, structured={"rows": []})
    head = f"{len(rows)} bought entries{window}, newest first"
    if query:
        head += f", matching {query!r} loosely (say each entry's own words)"
    if listing.truncated:
        head += f"; there are more, and only the newest {len(rows)} are shown"
    lines = "\n".join(f"- {_say_purchase(row)} [id {row['id']}]" for row in rows)
    return ok(f"{head}:\n{lines}", structured={"rows": rows})


def _delete_purchase(request, args) -> ToolResult:
    from purchases.matching import say_date
    from purchases.models import Purchase

    try:
        uuid.UUID(str(args["id"]))
    except ValueError:
        return refused(f"Nothing was deleted. {args['id']!r} is not an entry id.")
    entry = visible(Purchase, request).filter(pk=args["id"]).first()
    if entry is None:
        return refused(
            f"Nothing was deleted. There is no bought entry {args['id']} in this household's "
            f"log that you can see."
        )
    if entry.deleted_at is not None:
        return ok(f"Nothing was deleted: the entry for {entry.item} was already deleted.")
    response = call_route(request, "DELETE", f"/api/purchases/{args['id']}")
    if response.status_code == 204:
        return ok(
            f"Deleted the bought entry for {entry.item} on {say_date(entry.bought_on)}. The "
            f"engine committed it; it no longer answers when it was last bought."
        )
    return _write_result(response, f"bought entry {args['id']}", "deleted")


# =============================================================================
# Tick history (web-assistant ticket 04, ADR 0049). The phone's
# `get_last_ticked` (`TickHistoryController` + `TickMatch.kt`), moved to the
# engine so a browser can ask it. Same narrow rule: exact text after
# `normalise` (lower-case, trim, collapse whitespace), read THROUGH tombstones
# on the item and the checklist, only ticks that still stand.
# =============================================================================

LAST_TICKED_HISTORY = 10


def _last_ticked(request, args) -> ToolResult:
    from django.db.models import F, Func, TextField, Value
    from django.db.models.functions import Lower, Trim

    from checklists.models import ChecklistTick
    from purchases.matching import normalise, say_date

    query = args["item"]
    target = normalise(query)
    if not target:
        return refused("Nothing was read. Say which line to look for.")
    # The database narrows; `normalise` below is the authority. Postgres's
    # `\s` and Python's agree on every whitespace a typed line can hold.
    normalised = Trim(
        Func(
            Lower(F("item__text")),
            Value(r"\s+"),
            Value(" "),
            Value("g"),
            function="regexp_replace",
            output_field=TextField(),
        )
    )
    ticks = (
        visible(ChecklistTick, request)
        .filter(deleted_at__isnull=True)
        .annotate(normalised_text=normalised)
        .filter(normalised_text=target)
        .select_related("item", "item__checklist")
        .order_by("-ticked_at", "-day", "-pk")
    )
    matches = [
        tick for tick in ticks[:LAST_TICKED_HISTORY] if normalise(tick.item.text) == target
    ]
    if not matches:
        return ok(
            f"I have no record of {query.strip()!r} being ticked off any list. That is an "
            f"absent record, not proof of anything about whether it was got - plenty never "
            f"touches a list.",
            structured={"item": query, "history": []},
        )
    history = [
        {
            "checklist": tick.item.checklist.name,
            "line": tick.item.text,
            # The local day the tick counts for, as the client stamped it.
            # Words use it because the engine knows no member's time zone;
            # `ticked_at` is the tap's instant in UTC.
            "day": say_date(tick.day),
            "ticked_at": tick.ticked_at.isoformat(),
        }
        for tick in matches[:LAST_TICKED_HISTORY]
    ]
    latest = history[0]
    text = (
        f"{latest['line']!r} was last ticked off {latest['checklist']!r} on {latest['day']}. "
        f"That is the last time the line was ticked - a tap on a list, not a purchase record; "
        f"say \"ticked\", never \"bought\"."
    )
    if len(history) > 1:
        earlier = "; ".join(f"{h['day']} ({h['checklist']})" for h in history[1:])
        text += f" Earlier ticks, newest first: {earlier}."
    return ok(text, structured={"item": query, "history": history})


# =============================================================================
# The registry
# =============================================================================


@dataclass(frozen=True)
class EngineTool:
    name: str
    title: str
    description: str
    input_schema: dict
    handler: Callable
    writes: bool = False
    destructive: bool = False
    # The table a table-taking tool may touch. Checked BEFORE the schema, so a
    # gated, memory or unknown table is refused with the reason in words (the
    # gate's own sentence for a gated one) rather than a bare enum mismatch.
    tables: tuple[dict, str] | None = None
    validator: Draft202012Validator = field(init=False, repr=False, compare=False)

    def __post_init__(self):
        Draft202012Validator.check_schema(self.input_schema)
        object.__setattr__(self, "validator", Draft202012Validator(self.input_schema))

    @property
    def verb(self) -> str:
        return "written" if self.writes else "read"


def _object(properties: dict, required: tuple[str, ...] = ()) -> dict:
    return {
        "type": "object",
        "properties": properties,
        "required": list(required),
        "additionalProperties": False,
    }


_UUID = {"type": "string", "format": "uuid"}
# The scope half is true of a device token only; the web door
# (`assistant/surface.py`) drops it from what a browser session's model is told.
WRITE_SCOPE_SENTENCE = (
    "Needs a device token with write scope; a read-only token is refused and nothing is "
    "written. "
)
_WRITE_NOTE = (
    " " + WRITE_SCOPE_SENTENCE + "Report it done only when this tool's result says it was "
    "committed."
)

TOOLS: tuple[EngineTool, ...] = (
    EngineTool(
        name="list_tables",
        title="What this engine holds",
        description=(
            "Lists the household tables the other tools can read or write, which are "
            "read-only and why. Call it first when unsure which table holds something."
        ),
        input_schema=_object({}),
        handler=_list_tables,
    ),
    EngineTool(
        name="read_records",
        title="Read a household table",
        description=(
            "Reads one household table, newest first. Each row carries `_engine_says` when "
            "it has provenance, estimates or an unexplained amount: repeat those words, never "
            "present an UNVERIFIED row or an estimate as fact. An empty result is a real "
            "empty result; an error means nothing is known. Money is integer cents. Memory "
            "tables are not available."
        ),
        input_schema=_object(
            {
                "table": {"type": "string", "enum": sorted(READABLE)},
                "active_only": {
                    "type": "boolean",
                    "description": "Leave out deleted rows (default true).",
                },
                "since": {
                    "type": "string",
                    "description": "ISO date or date-time: only rows changed at or after it.",
                },
                "limit": {"type": "integer", "minimum": 1, "maximum": READ_LIMIT_MAX},
            },
            ("table",),
        ),
        handler=_read_records,
        tables=(READABLE, "read"),
    ),
    EngineTool(
        name="list_events",
        title="What is due",
        description=(
            "Events and tasks in a window (default: now to 14 days ahead), plus repeating "
            "events and undated tasks. Repeating events are NOT expanded into occurrences. "
            "Suggestions come in their own list and are not plans."
        ),
        input_schema=_object(
            {
                "from": {"type": "string", "description": "ISO date or date-time."},
                "to": {"type": "string", "description": "ISO date or date-time."},
                "include_done": {"type": "boolean"},
            }
        ),
        handler=_list_events,
    ),
    EngineTool(
        name="list_checklists",
        title="Checklists",
        description=(
            "The household's checklists with their items. Pass `date` (YYYY-MM-DD, the "
            "household's local date) to see which items are ticked that day."
        ),
        input_schema=_object({"date": {"type": "string"}, "include_archived": {"type": "boolean"}}),
        handler=_list_checklists,
    ),
    EngineTool(
        name="last_ticked",
        title="When was a line last ticked",
        description=(
            "When a checklist line was last TICKED, from tick history across every checklist, "
            "including ones since deleted. A tick is only evidence of a tap: it carries no price "
            "and nothing checked it against anything. Always say \"ticked\", and never claim it "
            "was bought or done. No match is an absent RECORD, not proof it was never got: say "
            "there is no record of it being ticked, and leave it there. Matches the exact line "
            "text (case and spacing only): 'toothpaste' does not match 'Colgate toothpaste'."
        ),
        input_schema=_object({"item": {"type": "string", "minLength": 1}}, ("item",)),
        handler=_last_ticked,
    ),
    EngineTool(
        name="write_record",
        title="Create or update a row",
        description=(
            "Creates or updates one row of a writable household table under the identity "
            "given (an upsert, safe to retry). `fields` are the table's REST fields; the "
            "engine validates them exactly as the app's own sync does. Statements, ledger "
            "transactions, receipts and their lines come only from the reconciliation gate "
            "and cannot be written." + _WRITE_NOTE
        ),
        input_schema=_object(
            {
                "table": {"type": "string", "enum": sorted(WRITABLE)},
                "identity": {"type": "string", "minLength": 1},
                "fields": {"type": "object"},
            },
            ("table", "identity", "fields"),
        ),
        handler=_write_record,
        tables=(WRITABLE, "written"),
        writes=True,
    ),
    EngineTool(
        name="delete_record",
        title="Delete a row",
        description=(
            "Deletes one row of a writable household table by setting its tombstone, which "
            "every device then syncs." + _WRITE_NOTE
        ),
        input_schema=_object(
            {
                "table": {"type": "string", "enum": sorted(DELETABLE)},
                "identity": {"type": "string", "minLength": 1},
            },
            ("table", "identity"),
        ),
        handler=_delete_record,
        tables=(DELETABLE, "deleted"),
        writes=True,
        destructive=True,
    ),
    EngineTool(
        name="add_event",
        title="Add an event or task",
        description=(
            "Creates an event or task. `fields` takes the REST event fields: title (required), "
            "starts_at, ends_at, all_day, location, notes, origin_guid (pass one so a retry "
            "cannot create a duplicate), remind_minutes_before, kind (reminder, event, task, "
            "or suggestion: a thing the household could do, not their plan; no reminder; give it "
            "structured_meta {city, venue, address (street, or null), url, price} and location "
            "\"venue, street address, city TX\"), and "
            "visibility: \"shared\" (the default; every member of the household sees it) or "
            "\"private\" (only the member this token belongs to)." + _WRITE_NOTE
        ),
        input_schema=_object({"fields": {"type": "object"}}, ("fields",)),
        handler=_add_event,
        writes=True,
    ),
    EngineTool(
        name="update_event",
        title="Change an event or task",
        description=(
            "Changes fields on one event or task, by id; marking a task done is "
            '{"done": true}; adding a suggestion to the plans is {"kind": "event"}.'
            + _WRITE_NOTE
        ),
        input_schema=_object({"id": _UUID, "fields": {"type": "object"}}, ("id", "fields")),
        handler=_update_event,
        writes=True,
    ),
    EngineTool(
        name="delete_event",
        title="Delete an event or task",
        description="Deletes one event or task, by id (a tombstone)." + _WRITE_NOTE,
        input_schema=_object({"id": _UUID}, ("id",)),
        handler=_delete_event,
        writes=True,
        destructive=True,
    ),
    EngineTool(
        name="add_checklist_item",
        title="Add a checklist item",
        description=(
            "Adds an item to a checklist. A measured item takes measure_unit, measure_target "
            "and measure_direction." + _WRITE_NOTE
        ),
        input_schema=_object(
            {
                "checklist_id": _UUID,
                "text": {"type": "string", "minLength": 1},
                "measure_unit": {"type": "string"},
                "measure_target": {"type": "number"},
                "measure_direction": {"type": "string"},
            },
            ("checklist_id", "text"),
        ),
        handler=_add_checklist_item,
        writes=True,
    ),
    EngineTool(
        name="tick_checklist_item",
        title="Tick a checklist item",
        description=(
            "Ticks one checklist item for a date (YYYY-MM-DD, the household's local date). A "
            "measured item needs `value`." + _WRITE_NOTE
        ),
        input_schema=_object(
            {
                "checklist_id": _UUID,
                "item_id": _UUID,
                "date": {"type": "string"},
                "value": {"type": "number"},
            },
            ("checklist_id", "item_id", "date"),
        ),
        handler=_tick_checklist_item,
        writes=True,
    ),
    EngineTool(
        name="log_purchase",
        title="Log something as bought",
        description=(
            "Logs that the household bought something on a date (YYYY-MM-DD, the household's "
            "local date: today's date when they say today). Shared with the household unless "
            "`private` is true. Optional store, price_cents (whole cents, entered by hand and "
            "never checked against the bank) and quantity_note. Pass sync_id so a retry cannot "
            "log it twice. A tick on the Groceries list already logs itself; do not log it "
            "again." + _WRITE_NOTE
        ),
        input_schema=_object(
            {
                "item": {"type": "string", "minLength": 1},
                "date": {"type": "string"},
                "store": {"type": "string"},
                "price_cents": {"type": "integer", "minimum": 0},
                "quantity_note": {"type": "string"},
                "private": {"type": "boolean"},
                "sync_id": {"type": "string", "minLength": 1, "maxLength": 64},
            },
            ("item", "date"),
        ),
        handler=_log_purchase,
        writes=True,
    ),
    EngineTool(
        name="last_bought",
        title="When was it last bought",
        description=(
            "When the household last bought something, from the bought log. Matching is loose "
            "(every word asked for appears in the entry), so repeat the entry's own words and "
            "date from the result, never the question's. Several different matches are all "
            "returned: say them, never pick one. No match means there is no record, never "
            "that it was never bought."
        ),
        input_schema=_object({"item": {"type": "string", "minLength": 1}}, ("item",)),
        handler=_last_bought,
    ),
    EngineTool(
        name="list_purchases",
        title="The bought log",
        description=(
            "Entries in the household's bought log, newest first. Optional `query` (loose "
            "match, as last_bought), `from` and `to` (YYYY-MM-DD, inclusive), `source` "
            "(MANUAL, GROCERIES_TICK, GROCERIES_BACKFILL) and `limit`. Prices were entered by "
            "hand; say so."
        ),
        input_schema=_object(
            {
                "query": {"type": "string"},
                "from": {"type": "string"},
                "to": {"type": "string"},
                "source": {
                    "type": "string",
                    "enum": ["MANUAL", "GROCERIES_TICK", "GROCERIES_BACKFILL"],
                },
                "limit": {"type": "integer", "minimum": 1, "maximum": READ_LIMIT_MAX},
            }
        ),
        handler=_list_purchases,
    ),
    EngineTool(
        name="delete_purchase",
        title="Delete a bought entry",
        description=(
            "Deletes one entry from the bought log, by the id list_purchases or last_bought "
            "returned (a tombstone)." + _WRITE_NOTE
        ),
        input_schema=_object({"id": _UUID}, ("id",)),
        handler=_delete_purchase,
        writes=True,
        destructive=True,
    ),
)

TOOLS_BY_NAME = {tool.name: tool for tool in TOOLS}


def run_tool(request, name: str, arguments: dict) -> ToolResult:
    """The one entry point every door into the registry uses: `/mcp`'s
    `tools/call` (a device token) and `/api/assistant/tool` (a signed-in
    member's browser, web-assistant ticket 07). One registry, two doors: the
    same code reads and writes, under the caller's own `visible()`.
    Synchronous, and run on the request's own thread (`engine_mcp/server.py`),
    so the ORM sees the request's connection."""
    tool = TOOLS_BY_NAME.get(name)
    if tool is None:
        return refused(
            f"Nothing was read or written. This engine has no tool called {name!r}. Its "
            f"tools: {', '.join(TOOLS_BY_NAME)}."
        )
    if tool.writes and not token_may_write(request):
        return refused(READ_SCOPE_REFUSAL)
    if tool.tables is not None:
        allowed, verb = tool.tables
        table = arguments.get("table")
        if isinstance(table, str) and table not in allowed:
            return refused(_table_refusal(table, verb))
    errors = sorted(tool.validator.iter_errors(arguments), key=lambda e: list(e.path))
    if errors:
        reasons = "; ".join(
            f"{'/'.join(str(p) for p in e.path) or 'arguments'}: {e.message}" for e in errors
        )
        return refused(f"Nothing was {tool.verb}. The arguments do not fit {name}: {reasons}.")
    try:
        return tool.handler(request, arguments)
    except Exception as exc:  # noqa: BLE001 - the result must say what happened, always
        if tool.writes:
            return failed(
                f"The engine hit an error running {name} ({type(exc).__name__}) and cannot "
                f"confirm that anything was written. Treat it as not written, and read the "
                f"row back before saying otherwise."
            )
        return failed(
            f"Nothing was read. The engine hit an error running {name} "
            f"({type(exc).__name__}), so nothing is known from this call - which is not the "
            f"same as there being nothing."
        )
