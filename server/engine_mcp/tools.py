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
  (`household.permissions.write_refusal`), and so does every write tool for a
  recruiter (ADR 0068), whatever credential it carries.

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
from household.permissions import write_refusal
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
# Phone-web parity quick wins (recruiter-demo research 01, 2026-11-02). Each
# is a phone tool's capability moved to the engine so the web assistant, `/mcp`
# and a recruiter's visit can use it. Reads go through `scoped()`/`visible()`;
# writes call the routed REST view, like every write above.
# =============================================================================


def _household_today(request) -> date:
    """Today in the household's zone when it has one, else UTC."""
    from household.tenancy import household_of
    from household.timezones import local_date, zone_named

    zone = zone_named(getattr(household_of(request), "timezone", None))
    return local_date(zone) if zone is not None else datetime.now(UTC).date()


def _household_zone(request):
    from zoneinfo import ZoneInfo

    from household.tenancy import household_of
    from household.timezones import zone_named

    return zone_named(getattr(household_of(request), "timezone", None)) or ZoneInfo("UTC")


def _long_date(day: date) -> str:
    return f"{day:%a} {day:%b} {day.day}, {day.year}"


def _vehicle_name(vehicle) -> str:
    return vehicle.name or f"{vehicle.year} {vehicle.make} {vehicle.model}"


def _vehicles(request, args, verb: str):
    """(vehicles, refusal). Every live, unarchived vehicle, or the ones
    `vehicle` names (an id, or words in its name, make or model)."""
    from household.tenancy import scoped
    from legacy.models.fleet import Vehicle

    live = scoped(Vehicle, request).filter(deleted_at__isnull=True, archived=False)
    asked = (args.get("vehicle") or "").strip()
    if not asked:
        found = list(live.order_by("name", "id"))
        if not found:
            return [], f"Nothing was {verb}. This household has no vehicle on file."
        return found, None
    try:
        found = list(live.filter(pk=uuid.UUID(asked)))
    except ValueError:
        words = asked.lower().split()
        found = [
            v
            for v in live.order_by("name", "id")
            if all(
                w in f"{v.name} {v.year} {v.make} {v.model} {v.trim or ''}".lower() for w in words
            )
        ]
    if not found:
        names = ", ".join(_vehicle_name(v) for v in live.order_by("name", "id")) or "none"
        return [], (
            f"Nothing was {verb}. No vehicle on file matches {asked!r}. Vehicles on file: {names}."
        )
    return found, None


ODOMETER_SOURCE_WORDS = {
    "vehicle": "the reading entered on the vehicle",
    "service": "the mileage on a service record",
    "code": "the mileage stamped on a trouble-code read",
}


def _next_service(request, args) -> ToolResult:
    from brief.maintenance import latest_odometer, schedule_status

    try:
        today = _parse_day(args["date"], "date") if args.get("date") else _household_today(request)
    except BadArgument as exc:
        return refused(f"Nothing was read. {exc}.")
    vehicles, refusal = _vehicles(request, args, "read")
    if refusal:
        return refused(refusal)
    paragraphs, out = [], []
    for vehicle in vehicles:
        name = _vehicle_name(vehicle)
        items = schedule_status(request, vehicle)
        odometer = latest_odometer(request, vehicle)
        entry = {
            "vehicle_id": str(vehicle.id),
            "name": name,
            "next_by_date": None,
            "next_by_miles": None,
            "overdue": [],
            "never_logged": [i.service_name for i in items if i.never_logged],
            "odometer": (
                {
                    "miles": odometer.miles,
                    "read_on": odometer.read_on.isoformat(),
                    "source": odometer.source,
                }
                if odometer
                else None
            ),
        }
        out.append(entry)
        if not items:
            paragraphs.append(
                f"{name}: no maintenance schedule is logged, so nothing can be timed."
            )
            continue
        said = []
        dated = sorted((i for i in items if i.due_on is not None), key=lambda i: i.due_on)
        for item in dated:
            if item.due_on < today:
                entry["overdue"].append(
                    {"service_name": item.service_name, "due_on": item.due_on.isoformat()}
                )
        upcoming = [i for i in dated if i.due_on >= today]
        if upcoming:
            item = upcoming[0]
            days = (item.due_on - today).days
            entry["next_by_date"] = {
                "service_name": item.service_name,
                "due_on": item.due_on.isoformat(),
                "days_away": days,
            }
            when = "today" if days == 0 else f"in {days} day{'s' if days != 1 else ''}"
            said.append(
                f"By date, {item.service_name} is next: due {_long_date(item.due_on)} ({when}; "
                f"every {item.interval_months} months, last done "
                f"{_long_date(item.last_done_on)})."
            )
        by_miles = [i for i in items if i.due_at_miles is not None]
        if by_miles and odometer is None:
            said.append(
                "Mileage-based items cannot be timed: there is no odometer reading on file."
            )
        elif by_miles:
            for item in by_miles:
                if item.due_at_miles <= odometer.miles:
                    entry["overdue"].append(
                        {"service_name": item.service_name, "due_at_miles": item.due_at_miles}
                    )
            ahead = sorted(
                (i for i in by_miles if i.due_at_miles > odometer.miles),
                key=lambda i: i.due_at_miles,
            )
            if ahead:
                item = ahead[0]
                to_go = item.due_at_miles - odometer.miles
                entry["next_by_miles"] = {
                    "service_name": item.service_name,
                    "due_at_miles": item.due_at_miles,
                    "miles_to_go_estimate": to_go,
                }
                said.append(
                    f"By mileage, {item.service_name} is next: due at {item.due_at_miles:,} mi, "
                    f"about {to_go:,} mi to go. That distance is an ESTIMATE: it is worked out "
                    f"from the last odometer reading on file ({odometer.miles:,} mi on "
                    f"{_long_date(odometer.read_on)}, "
                    f"{ODOMETER_SOURCE_WORDS[odometer.source]}) and the service mileage on "
                    f"record, not read off the car today; say it is approximate."
                )
        if entry["overdue"]:
            parts = []
            for row in entry["overdue"]:
                if "due_on" in row:
                    due_on = _long_date(date.fromisoformat(row["due_on"]))
                    parts.append(f"{row['service_name']} (due {due_on})")
                else:
                    parts.append(
                        f"{row['service_name']} (due at {row['due_at_miles']:,} mi; the last "
                        f"reading on file is past that)"
                    )
            said.append("Already past due by the records on file: " + "; ".join(parts) + ".")
        if entry["never_logged"]:
            said.append(
                "Never logged as done, so not timed: " + ", ".join(entry["never_logged"]) + "."
            )
        if not said:
            said.append("Nothing on the schedule can be timed from the records on file.")
        paragraphs.append(f"{name}: " + " ".join(said))
    return ok(
        f"Maintenance as of {_long_date(today)}, from the records on file (schedules and "
        f"service history; nothing here was read from the car):\n" + "\n".join(paragraphs),
        structured={"as_of": today.isoformat(), "vehicles": out},
    )


def _bank_as_of_words(request, as_of) -> str:
    """"as of 9:14 AM Tue Nov 3, 2026" in the household's zone, or that the
    bank gave no time."""
    if as_of is None:
        return "(no as-of time was recorded)"
    local = as_of.astimezone(_household_zone(request))
    hour = local.hour % 12 or 12
    clock = f"{hour}:{local.minute:02d} {'AM' if local.hour < 12 else 'PM'}"
    return f"as of {clock} {_long_date(local.date())}"


def _since_words(a: dict) -> str:
    """", plus -$433.93 of bank-feed transactions since", or "" with none."""
    if not a["fact_since_count"]:
        return ""
    from brief.compose import money

    kind = (
        "bank-feed transactions"
        if a["fact_since_bank_feed_count"] == a["fact_since_count"]
        else "verified transactions"
    )
    after = "dated after it" if a["anchor"] == "bank" else "since"
    return f", plus {money(a['fact_since_cents'], a['currency'])} of {kind} {after}"


def _now_words(request, a: dict) -> str:
    """"Now: -$345.43 (statement balance $88.50 on Tue Oct 6, 2026, verified,
    plus -$433.93 of bank-feed transactions since)". The figure first, the
    anchor second. A credit account says what is owed."""
    from brief.compose import money

    c = a["currency"]
    current = a["current_cents"]
    credit = a["anchor"] == "bank" and a["bank"]["type"] == "credit"
    if credit:
        figure = (
            f"{money(-current, c)} owed" if current <= 0 else f"{money(current, c)} in credit"
        )
    else:
        figure = money(current, c)
    # The anchor's own figure is repeated only when something moved since it;
    # otherwise it IS the Now figure and saying it twice invites a mix-up.
    moved = bool(a["fact_since_count"])
    if a["anchor"] == "bank":
        # Kevin, 2026-11-03 ("store them"): the bank's own balance is fact,
        # said plainly, always with when the bank stated it.
        bank = a["bank"]
        when = _bank_as_of_words(request, bank["as_of"])
        if credit:
            source = "the bank's own figure"
            if moved:
                source += f" of {money(bank['current_cents'], c)} owed"
            source += f" {when}"
            if bank["available_cents"] is not None:
                source += f", {money(bank['available_cents'], c)} of credit available"
        else:
            source = "the bank's own balance"
            if moved:
                source += f" of {money(bank['current_cents'], c)}"
            source += f" {when}"
            if bank["available_cents"] is not None:
                source += f", {money(bank['available_cents'], c)} available"
    else:
        source = "statement balance"
        if moved:
            source += f" {money(a['statement_closing_cents'], c)}"
        source += f" on {_long_date(a['statement_period_end'])}, verified"
    return f"Now: {figure} ({source}{_since_words(a)})"


def _account_balances(request, args) -> ToolResult:
    from api.ledger_balances import account_balances
    from brief.compose import money

    found = account_balances(request, args.get("account"))
    if not found:
        what = (
            f"no account matching {args['account']!r}"
            if args.get("account")
            else "no accounts: no statement and no transaction on file"
        )
        return ok(
            f"The engine read this household's ledger and found {what}. That is a real empty "
            f"result, not a failure to read.",
            structured={"accounts": []},
        )
    lines = []
    for a in found:
        c = a["currency"]
        said = [f"{a['label']} (ending {a['account_last4']}, {c}):"]
        anchored = a["current_cents"] is not None
        if anchored:
            # Audit 2026-11-03: the current figure leads, in one phrase, and
            # the anchor follows as where it came from - never two figures
            # side by side for the model to pick between.
            said.append(_now_words(request, a) + ".")
        elif a["from_bank_feed"]:
            # ADR 0057: the bank's feed is fact, said plainly. It brings
            # transactions, and the engine keeps no balance from it.
            latest = a["latest_activity_on"]
            said.append(
                f"the bank's own feed supplies this account's transactions (latest "
                f"{_long_date(latest)}), but the engine stores no balance for it, so no balance "
                f"can be stated. Check the bank's app for it."
            )
        else:
            said.append("no statement on file, so no balance can be stated; never state one.")
        if a["unverified_since_count"]:
            since = (
                "Since then" if a["anchor"] == "bank" else "Since that statement" if anchored
                else "On file"
            )
            said.append(
                f"{since}, {a['unverified_since_count']} UNVERIFIED transaction(s) net "
                f"{money(a['unverified_since_cents'], c)}: no gate checked them."
            )
        if a["pending_count"]:
            said.append(
                f"{a['pending_count']} charge(s) logged by hand as pending, not confirmed by the "
                f"bank, net {money(a['pending_cents'], c)}."
            )
        if anchored and (a["unverified_since_count"] or a["pending_count"]):
            said.append(
                f"With those included the figure would be {money(a['with_unverified_cents'], c)}, "
                f"which is UNVERIFIED; say so whenever you say it."
            )
        elif not anchored and (a["unverified_since_count"] or a["pending_count"]):
            said.append("That unverified activity is movement, not a balance.")
        lines.append(" ".join(said))
    return ok(
        "Balances per account, never added across currencies:\n"
        + "\n".join(f"- {t}" for t in lines),
        structured={"accounts": found},
    )


def _grocery_spend(request, args) -> ToolResult:
    from brief.compose import money
    from household.tenancy import scoped
    from legacy.models.pantry import Receipt

    try:
        start = _parse_day(args["from"], "from") if args.get("from") else None
        end = _parse_day(args["to"], "to") if args.get("to") else None
    except BadArgument as exc:
        return refused(f"Nothing was read. {exc}.")
    if start and end and end < start:
        return refused("Nothing was read. `to` must be on or after `from`.")
    receipts = scoped(Receipt, request)
    if start:
        receipts = receipts.filter(purchase_date__gte=start)
    if end:
        receipts = receipts.filter(purchase_date__lte=end)
    window = (
        f" from {start.isoformat() if start else 'the first receipt'} to "
        f"{end.isoformat() if end else 'the latest'}"
        if start or end
        else " across every receipt on file"
    )
    by_currency: dict[str, dict] = {}
    for r in receipts.values("currency", "total_cents", "provenance", "purchase_date"):
        row = by_currency.setdefault(
            r["currency"],
            {
                "currency": r["currency"],
                "total_cents": 0,
                "receipts": 0,
                "unverified_cents": 0,
                "unverified_receipts": 0,
                "first": r["purchase_date"],
                "last": r["purchase_date"],
            },
        )
        row["total_cents"] += r["total_cents"]
        row["receipts"] += 1
        row["first"] = min(row["first"], r["purchase_date"])
        row["last"] = max(row["last"], r["purchase_date"])
        if r["provenance"] == "UNRECONCILED":
            row["unverified_cents"] += r["total_cents"]
            row["unverified_receipts"] += 1
    if not by_currency:
        return ok(
            f"The engine read this household's receipts and found none{window}. That is a real "
            f"empty result, not a failure to read; grocery spend not photographed as a receipt "
            f"is not on file.",
            structured={"currencies": []},
        )
    rows = sorted(by_currency.values(), key=lambda r: (r["currency"] != "USD", r["currency"]))
    lines = []
    for row in rows:
        c = row["currency"]
        text = (
            f"{c}: {money(row['total_cents'], c)} across {row['receipts']} receipt(s), "
            f"{row['first'].isoformat()} to {row['last'].isoformat()}."
        )
        if row["unverified_receipts"]:
            text += (
                f" {money(row['unverified_cents'], c)} of that, on {row['unverified_receipts']} "
                f"receipt(s), is UNVERIFIED: those receipts could not be checked against their "
                f"own printed numbers. Say the total is not confirmed."
            )
        else:
            text += " Every receipt in it passed the reconciliation gate."
        lines.append(text)
        row["first"], row["last"] = row["first"].isoformat(), row["last"].isoformat()
    return ok(
        f"Grocery spend from receipts{window}, per currency (never add them together). Receipt "
        f"totals as printed; a receipt the gate quarantined is not counted:\n"
        + "\n".join(f"- {t}" for t in lines),
        structured={"currencies": rows},
    )


RECALLS_SHOWN = 20


def _vehicle_recalls(request, args) -> ToolResult:
    from engine_mcp import recalls

    vehicles, refusal = _vehicles(request, args, "read")
    if refusal:
        return refused(refusal)
    if len(vehicles) > 1:
        names = ", ".join(_vehicle_name(v) for v in vehicles)
        return refused(
            f"Nothing was read. Recalls are looked up for one vehicle at a time, and several are "
            f"on file: {names}. Say which with `vehicle`."
        )
    vehicle = vehicles[0]
    name = _vehicle_name(vehicle)
    missing = [
        label
        for label, value in (
            ("year", vehicle.year),
            ("make", vehicle.make),
            ("model", vehicle.model),
        )
        if not value or (isinstance(value, str) and not value.strip())
    ]
    if missing:
        return refused(
            f"Nothing was read. {name} has no {' or '.join(missing)} on file, and NHTSA's recall "
            f"list is looked up by year, make and model. Nothing was guessed."
        )
    car = f"{vehicle.year} {vehicle.make} {vehicle.model}"
    try:
        found = recalls.fetch_recalls(vehicle.make, vehicle.model, vehicle.year)
    except recalls.RecallLookupFailed as exc:
        return failed(
            f"Nothing was read. The lookup of NHTSA's recall list for the {car} did not complete "
            f"({exc}), so it is NOT known whether it has recalls. This is not a finding of none; "
            f"try again later."
        )
    scope = (
        "NHTSA's public list for that model year, make and model, not a check of this car's VIN: "
        "whether a recall applies to this exact car, or was already repaired, is not known here"
    )
    if not found:
        return ok(
            f"NHTSA's recall list shows no recalls for the {car} ({name}) as of this lookup. That "
            f"is {scope}.",
            structured={"vehicle_id": str(vehicle.id), "vehicle": car, "recalls": []},
        )
    shown = found[:RECALLS_SHOWN]
    lines = "\n".join(
        f"- {r['campaign'] or 'no campaign number'}: {r['component'] or 'component not stated'}. "
        f"{r['summary']} Remedy: {r['remedy'] or 'not stated'}"
        for r in shown
    )
    more = f" Only the first {RECALLS_SHOWN} are shown." if len(found) > len(shown) else ""
    return ok(
        f"NHTSA lists {len(found)} recall(s) for the {car} ({name}).{more} This is {scope}:\n"
        f"{lines}",
        structured={"vehicle_id": str(vehicle.id), "vehicle": car, "recalls": shown},
    )


SCHEDULE_KINDS = {"none": None, "daily": "DAILY", "weekly": "WEEKLY"}


def _create_checklist(request, args) -> ToolResult:
    from api.recurrence import parse_weekdays

    schedule = SCHEDULE_KINDS[args.get("schedule", "none")]
    days = (args.get("days") or "").strip()
    if schedule == "WEEKLY" and not days:
        return refused(
            "Nothing was written. A weekly checklist needs `days`, e.g. 'MON,WED,FRI'."
        )
    if days and schedule != "WEEKLY":
        return refused("Nothing was written. `days` applies only to a weekly checklist.")
    if days and not parse_weekdays(days):
        return refused(
            f"Nothing was written. {days!r} is not a list of weekdays such as 'MON,WED,FRI'."
        )
    body = {
        "name": args["name"],
        "schedule_kind": schedule,
        "schedule_days_of_week": days or None,
        "visibility": "private" if args.get("private") else "shared",
    }
    if args.get("sync_id"):
        body["sync_id"] = args["sync_id"]
    response = call_route(request, "POST", "/api/checklists/", body)
    if response.status_code == 200:
        return ok(
            f"Nothing was created: a checklist with sync_id {args.get('sync_id')!r} already "
            f"exists, and it was left as it was: {_json(response.data)}",
            structured={"row": response.data},
        )
    return _write_result(response, f"the checklist {args['name']!r}", "written")


def _checklist_item(request, checklist_id, item_id):
    from checklists.models import ChecklistItem

    return (
        visible(ChecklistItem, request)
        .filter(pk=item_id, checklist_id=checklist_id)
        .select_related("checklist")
        .first()
    )


def _untick_checklist_item(request, args) -> ToolResult:
    from checklists.models import ChecklistTick

    try:
        on_day = _parse_day(args["date"], "date")
    except BadArgument as exc:
        return refused(f"Nothing was written. {exc}.")
    item = _checklist_item(request, args["checklist_id"], args["item_id"])
    if item is None:
        return refused(
            f"Nothing was written. There is no item {args['item_id']} on checklist "
            f"{args['checklist_id']} that you can see."
        )
    day = (on_day - date(1970, 1, 1)).days
    ticked = ChecklistTick.objects.filter(item=item, day=day, deleted_at__isnull=True).exists()
    if not ticked:
        return ok(
            f"Nothing was unticked: {item.text!r} on {item.checklist.name!r} had no tick for "
            f"{on_day.isoformat()}, so nothing changed.",
            structured={"unticked": False},
        )
    path = f"/api/checklists/{item.checklist_id}/items/{item.id}/tick/{day}"
    response = call_route(request, "DELETE", path)
    if response.status_code == 204:
        return ok(
            f"Unticked {item.text!r} on {item.checklist.name!r} for {on_day.isoformat()}. The "
            f"engine committed it. On the Groceries list, an untick on the tick's own day also "
            f"takes back the bought entry that tick logged.",
            structured={"unticked": True},
        )
    return _write_result(response, f"the tick on {item.text!r} for {on_day}", "written")


def _remove_checklist_item(request, args) -> ToolResult:
    item = _checklist_item(request, args["checklist_id"], args["item_id"])
    if item is None:
        return refused(
            f"Nothing was deleted. There is no item {args['item_id']} on checklist "
            f"{args['checklist_id']} that you can see."
        )
    if item.deleted_at is not None:
        return ok(f"Nothing was deleted: {item.text!r} was already removed.")
    response = call_route(
        request, "DELETE", f"/api/checklists/{item.checklist_id}/items/{item.id}"
    )
    if response.status_code == 204:
        return ok(
            f"Removed {item.text!r} from {item.checklist.name!r}. The engine set its tombstone; "
            f"its past ticks stay in the history."
        )
    return _write_result(response, f"item {item.text!r}", "deleted")


def _archive_checklist(request, args) -> ToolResult:
    archived = args.get("archived", True)
    try:
        response = call_route(
            request, "PATCH", f"/api/checklists/{args['checklist_id']}", {"archived": archived}
        )
    except RouteNotFound:
        return refused(f"Nothing was written. {args['checklist_id']!r} is not a checklist id.")
    if response.status_code == 200:
        verb = "Archived" if archived else "Unarchived"
        tail = (
            "It is kept with its items and history, and no longer listed unless archived "
            "lists are asked for."
            if archived
            else "It is listed again."
        )
        return ok(
            f"{verb} the checklist {response.data['name']!r}. The engine committed it. {tail}",
            structured={"row": response.data},
        )
    return _write_result(response, f"checklist {args['checklist_id']}", "written")


def _rename_place(request, args) -> ToolResult:
    source, target = args["from"].strip(), args["to"].strip()
    if not source or not target:
        return refused("Nothing was renamed. Both the current and the new label are needed.")
    if "/" in source:
        return refused(f"Nothing was renamed. There is no saved place called {source!r}.")
    try:
        response = call_route(request, "POST", f"/api/places/{source}/rename/", {"to": target})
    except RouteNotFound:
        return refused(f"Nothing was renamed. There is no saved place called {source!r}.")
    status = response.status_code
    if status == 200:
        return ok(
            f"Renamed the place {source!r} to {target!r}. The engine committed it: same location "
            f"and address, and live reminders on the old name moved with it. "
            f"{_json(response.data)}",
            structured=response.data if isinstance(response.data, dict) else None,
        )
    detail = _detail(response)
    if not detail.startswith("Nothing was"):
        detail = f"Nothing was renamed. The engine refused it (HTTP {status}): {detail}"
    return refused(detail) if status in (403, 404, 405, 409) else failed(detail)


def _skip_event_occurrence(request, args) -> ToolResult:
    from api.recurrence import end_from_series, occurrences_in_window, rule_from_series
    from legacy.models.dates import Event, EventSkip

    try:
        skip_day = _parse_day(args["date"], "date")
    except BadArgument as exc:
        return refused(f"Nothing was written. {exc}.")
    event = visible(Event, request).filter(pk=args["id"], deleted_at__isnull=True).first()
    if event is None:
        return refused(f"Nothing was written. There is no event {args['id']} that you can see.")
    if not event.repeat_kind or event.starts_at is None:
        return refused(
            f"Nothing was written. {event.title!r} does not repeat, so it has no occurrence to "
            f"skip; to remove it, delete the event."
        )
    zone = _household_zone(request)
    skipped = set(
        EventSkip.objects.filter(event=event, deleted_at__isnull=True).values_list(
            "skip_date", flat=True
        )
    )
    if skip_day in skipped:
        return ok(
            f"Nothing was written: {event.title!r} on {_long_date(skip_day)} was already skipped.",
            structured={"skipped": True, "changed": False},
        )
    window_start = datetime.combine(skip_day, time.min, tzinfo=zone)
    window_end = datetime.combine(skip_day, time.max, tzinfo=zone)
    rule = rule_from_series(event)
    occurs = rule is not None and occurrences_in_window(
        event.starts_at, rule, end_from_series(event), (), window_start, window_end, zone
    )
    if not occurs:
        return refused(
            f"Nothing was written. {event.title!r} has no occurrence on {_long_date(skip_day)} "
            f"(read in {zone.key}), so there is nothing to skip."
        )
    response = call_route(
        request, "POST", f"/api/events/{event.id}/skips", {"skip_date": skip_day.isoformat()}
    )
    if response.status_code in (200, 201):
        return ok(
            f"Skipped {event.title!r} on {_long_date(skip_day)}. The engine committed it; every "
            f"other occurrence of the series stays.",
            structured={"skipped": True, "changed": True, "row": response.data},
        )
    return _write_result(response, f"a skip of {event.title!r} on {skip_day}", "written")


# =============================================================================
# The registry
# =============================================================================
# Monthly spend (2026-10-24). The head unit's `ask_engine` had no way to answer
# "what did I spend this month": no tool here read it, so the model reached for
# the car's build sheet instead. This reads `api.ledger_spend.spend_for`, the
# body `GET /api/ledger/spend` returns, so the figure is the web's figure.
# =============================================================================


def _monthly_spend(request, args) -> ToolResult:
    from django.db import connection

    from api.ledger_spend import spend_for
    from api.spend import BadRequest, resolve_month

    currency = (args.get("currency") or "USD").upper()
    with connection.cursor() as cursor:
        cursor.execute("SELECT statement_timestamp()")
        now = cursor.fetchone()[0]
    try:
        month = resolve_month(args.get("month"), args.get("tz"), now)
    except BadRequest as exc:
        return refused(f"Nothing was read. {exc}")
    body = spend_for(request, month, currency)
    total = sum(a["spend_cents"] for a in body["accounts"])
    if not body["accounts"]:
        return ok(
            f"The engine read this household's ledger and found no {currency} spend for "
            f"{body['month']}. That is an empty month on file, not proof nothing was spent.",
            structured=body,
        )
    parts = [
        f"Categorised spend for {body['month']} is {_cents_words(total)} {currency}, the same "
        f"figure the Money page shows."
    ]
    # A budgeted category with nothing spent is a line of its own below, not
    # one of the "largest".
    spent = [c for c in body["categories"] if c["spend_cents"]]
    if spent:
        top = sorted(spent, key=lambda c: -c["spend_cents"])[:5]
        parts.append(
            "Largest categories: "
            + ", ".join(f"{c['category']} {_cents_words(c['spend_cents'])}" for c in top)
            + "."
        )
    budgeted = [c for c in body["categories"] if c.get("target_cents")]
    if budgeted:
        said = []
        for c in sorted(budgeted, key=lambda c: c["category"]):
            gap = c["target_cents"] - c["spend_cents"]
            state = f"{_cents_words(gap)} left" if gap >= 0 else f"{_cents_words(-gap)} over"
            said.append(
                f"{c['category']} {_cents_words(c['spend_cents'])} of "
                f"{_cents_words(c['target_cents'])} ({state})"
            )
        parts.append("Against the household's budget: " + "; ".join(said) + ".")
    unverified_cents = sum(a["unverified_cents"] for a in body["accounts"])
    if unverified_cents:
        parts.append(
            f"Of the categorised {_cents_words(total)}, {_cents_words(unverified_cents)} is "
            f"UNVERIFIED (rows no reconciliation gate checked) and "
            f"{_cents_words(total - unverified_cents)} is verified or from the bank's own feed; "
            f"say which is which."
        )
    elif total:
        parts.append(
            "None of the categorised figure is unverified: every row in it is verified or from "
            "the bank's own feed."
        )
    if body["uncategorised_cents"]:
        parts.append(
            f"A further {_cents_words(body['uncategorised_cents'])} was spent but has no "
            f"category yet, so it is NOT in that total; say so."
        )
    excluded = body["excluded"]
    moved = excluded["own_account_moves_cents"] + excluded["not_spending_cents"]
    if moved:
        parts.append(
            f"{_cents_words(moved)} moved between the household's own accounts or under "
            f"not-spending categories and is left out; say so."
        )
    if body["uncategorised_unverified"]:
        parts.append(
            "The amount with no category includes UNVERIFIED rows (no gate checked them); say so."
        )
    if not body["complete"]:
        parts.append(
            "The month is not fully covered by the bank data on file, so the figure may be "
            "incomplete; say so."
        )
    return ok(" ".join(parts), structured=body)


# =============================================================================
# legion_docs: how LEGION is built, from its own public docs (Kevin, 2026-11-03)
#
# Reads `assistant/docs_corpus.json` and nothing else: no household row, no
# request state. The corpus is an allowlist of the repo's public docs
# (`assistant/gen_docs_corpus.py`); retrieval is BM25, deterministic, with no
# model call (`assistant/docs_search.py`).
# =============================================================================

DOCS_CONTRACT = (
    "Answer only from these passages, and cite the source of each point you use (\"ADR 0057\", "
    "or the file and heading). A SUPERSEDED ADR is history, not the current design: say it was "
    "superseded and by which ADR. A dated snapshot describes the code as it was that day. "
    "Anything these passages do not say is not covered by the docs: say so, and never fill in "
    "implementation details from general knowledge."
)


def _legion_docs(request, args) -> ToolResult:
    from assistant.docs_search import LIMIT_DEFAULT, is_overview, search, tokens

    query = args["query"].strip()
    if not tokens(query) and not is_overview(query):
        return refused(
            "Nothing was searched. The question has no words to look up in LEGION's docs "
            f"({query!r}); ask it with the subject named, for example \"how does the "
            "reconciliation gate work\"."
        )
    passages = search(query, args.get("limit", LIMIT_DEFAULT))
    if not passages:
        return ok(
            f"No passage in LEGION's design docs matches {query!r}. The docs do not cover this "
            "as asked: say so, and do not answer it from general knowledge. Other words for the "
            "same subject may find something.",
            {"query": query, "passages": []},
        )
    body = "\n\n".join(f"[{i}] {p.label}\n{p.text}" for i, p in enumerate(passages, 1))
    return ok(
        f"{len(passages)} passage(s) from LEGION's design docs for {query!r}. {DOCS_CONTRACT}"
        f"\n\n{body}",
        {"query": query, "passages": [p.as_dict() for p in passages]},
    )


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
        name="monthly_spend",
        title="How much was spent this month",
        description=(
            "The household's categorised spending for one budget month (default: the current "
            "one), from the bank data on file: the total, the largest categories, and what was "
            "left out. This is the answer to 'how much have I spent this month' or 'my monthly "
            "spending'; it is never the car's build sheet. Optional `month` (YYYY-MM), `tz` "
            "(the zone that decides which month is current) and `currency` (default USD). "
            "Repeat every caveat the result states."
        ),
        input_schema=_object(
            {
                "month": {"type": "string", "pattern": "^[0-9]{4}-[0-9]{2}$"},
                "tz": {"type": "string"},
                "currency": {"type": "string", "pattern": "^[A-Za-z]{3}$"},
            }
        ),
        handler=_monthly_spend,
    ),
    EngineTool(
        name="next_service",
        title="What maintenance is next",
        description=(
            "Per vehicle, the next maintenance item due by date and the next due by mileage, "
            "anything already past due, and items never logged as done, from the schedule and "
            "service history on file. Miles to go is an ESTIMATE worked out from the last "
            "odometer reading on file, never a reading of the car today: say it is approximate. "
            "Optional `vehicle` (an id, or words from its name, make or model) and `date` "
            "(YYYY-MM-DD, default the household's today)."
        ),
        input_schema=_object({"vehicle": {"type": "string"}, "date": {"type": "string"}}),
        handler=_next_service,
    ),
    EngineTool(
        name="account_balances",
        title="Account balances",
        description=(
            "The balance of each bank account, per currency (never added across currencies). "
            "Each account's line LEADS with \"Now: <figure>\": that figure is the balance, so "
            "say it as the balance; what follows in brackets is where it came from (the bank's "
            "own balance with the time the bank stated it - always say \"as of\" that time - "
            "or the closing balance printed on its latest statement, plus transactions since). "
            "Never give the statement's or the bank's figure as the balance when the Now figure "
            "differs. Separately it lists any activity no gate checked (UNVERIFIED) or that a "
            "person logged as pending. Say UNVERIFIED wherever the result does, and nowhere "
            "else: the bank's own feed is fact. An account with no balance on file has none to "
            "state: never make one up from its transactions. Optional `account` (last four "
            "digits or words of its name)."
        ),
        input_schema=_object({"account": {"type": "string"}}),
        handler=_account_balances,
    ),
    EngineTool(
        name="grocery_spend",
        title="Grocery spend from receipts",
        description=(
            "Total grocery spend from the household's receipts, PER CURRENCY (never add USD and "
            "SGD together), over an optional `from`/`to` (YYYY-MM-DD, inclusive; default every "
            "receipt). Says how much is on receipts that could not be verified; repeat that."
        ),
        input_schema=_object({"from": {"type": "string"}, "to": {"type": "string"}}),
        handler=_grocery_spend,
    ),
    EngineTool(
        name="vehicle_recalls",
        title="Recalls for a vehicle",
        description=(
            "Looks up NHTSA's public recall list for one vehicle on file, by its model year, "
            "make and model (not its VIN), live. Optional `vehicle` (an id, or words from its "
            "name, make or model); needed when more than one is on file. A failed lookup means "
            "it is not known, never that there are none. Whether a listed recall applies to "
            "this exact car or was already repaired is not known here: say so."
        ),
        input_schema=_object({"vehicle": {"type": "string"}}),
        handler=_vehicle_recalls,
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
        name="create_checklist",
        title="Create a checklist",
        description=(
            "Creates a checklist. `schedule`: none (done once; the default), daily (resets each "
            "day) or weekly (only on `days`, e.g. 'MON,WED,FRI'). Shared with the household "
            "unless `private` is true. The Groceries list is built in and always exists: never "
            "create it. Pass sync_id so a retry cannot create it twice." + _WRITE_NOTE
        ),
        input_schema=_object(
            {
                "name": {"type": "string", "minLength": 1},
                "schedule": {"type": "string", "enum": sorted(SCHEDULE_KINDS)},
                "days": {"type": "string"},
                "private": {"type": "boolean"},
                "sync_id": {"type": "string", "minLength": 1, "maxLength": 64},
            },
            ("name",),
        ),
        handler=_create_checklist,
        writes=True,
    ),
    EngineTool(
        name="untick_checklist_item",
        title="Untick a checklist item",
        description=(
            "Takes the tick off one checklist item for a date (YYYY-MM-DD, the household's local "
            "date). Says so when there was no tick to take off." + _WRITE_NOTE
        ),
        input_schema=_object(
            {"checklist_id": _UUID, "item_id": _UUID, "date": {"type": "string"}},
            ("checklist_id", "item_id", "date"),
        ),
        handler=_untick_checklist_item,
        writes=True,
    ),
    EngineTool(
        name="remove_checklist_item",
        title="Remove a checklist item",
        description=(
            "Removes one item from a checklist (a tombstone); its past ticks stay in the "
            "history." + _WRITE_NOTE
        ),
        input_schema=_object(
            {"checklist_id": _UUID, "item_id": _UUID}, ("checklist_id", "item_id")
        ),
        handler=_remove_checklist_item,
        writes=True,
        destructive=True,
    ),
    EngineTool(
        name="archive_checklist",
        title="Archive a checklist",
        description=(
            "Archives a checklist (kept with its items and history, no longer listed), or "
            "brings it back with `archived` false. The built-in Groceries list cannot be "
            "archived." + _WRITE_NOTE
        ),
        input_schema=_object(
            {"checklist_id": _UUID, "archived": {"type": "boolean"}}, ("checklist_id",)
        ),
        handler=_archive_checklist,
        writes=True,
    ),
    EngineTool(
        name="rename_place",
        title="Rename a saved place",
        description=(
            "Renames a saved place, keeping its location and address; reminders on the old "
            "name move with it. Use this for every rename, never a delete and a new place."
            + _WRITE_NOTE
        ),
        input_schema=_object(
            {"from": {"type": "string", "minLength": 1}, "to": {"type": "string", "minLength": 1}},
            ("from", "to"),
        ),
        handler=_rename_place,
        writes=True,
    ),
    EngineTool(
        name="skip_event_occurrence",
        title="Skip one occurrence of a repeating event",
        description=(
            "Skips one date of a repeating event (YYYY-MM-DD, the occurrence's local date); "
            "every other occurrence stays. Refused for an event that does not repeat or does "
            "not fall on that date. To remove the whole series, delete the event." + _WRITE_NOTE
        ),
        input_schema=_object({"id": _UUID, "date": {"type": "string"}}, ("id", "date")),
        handler=_skip_event_occurrence,
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
    EngineTool(
        name="legion_docs",
        title="How LEGION is built",
        description=(
            "Searches LEGION's own design docs - the README, the architecture notes, the "
            "architecture decision records (ADRs) and the glossary - for passages about how "
            "LEGION is built and why: the engine, tenancy, the reconciliation gate, voice, roles. "
            "Pass the person's WHOLE question as `query`, in their own words (\"Explain to me "
            "the codebase\", \"how does the voice loop handle interruptions\"), never a single "
            "word or a topic label: the full question is what finds the right passages. A broad "
            "question (the codebase, how it works, the architecture) returns the overview: the "
            "clients, the engine and the head unit. Answer only from the passages returned and "
            "cite each source (\"ADR 0057\", or the file and heading). An ADR marked superseded "
            "must be described as superseded, never as the current design. When the passages do "
            "not answer it, say the docs do not cover it; never invent an implementation detail."
        ),
        input_schema=_object(
            {
                "query": {"type": "string", "minLength": 1, "maxLength": 300},
                "limit": {"type": "integer", "minimum": 1, "maximum": 8},
            },
            ("query",),
        ),
        handler=_legion_docs,
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
    return run_engine_tool(request, tool, arguments)


def run_engine_tool(request, tool: EngineTool, arguments: dict) -> ToolResult:
    """`run_tool` for a tool already in hand: the write fence, the table fence,
    the schema, and the handler, in that order. Also the door for a tool kept
    OUT of the registry on purpose (`assistant.surface.RECRUITER_ONLY_TOOLS`),
    so it gets the same checks without being listed on `/mcp`."""
    name = tool.name
    if tool.writes:
        refusal = write_refusal(request)
        if refusal is not None:
            return refused(refusal)
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
