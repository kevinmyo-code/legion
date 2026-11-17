"""Agent tools over the user-aspect records (engine-brain ticket 15): engine twins of the phone's
aspect-engine meta-tools (`service/EngineToolbox.kt`).

The phone has nine; eight are twinned here. `aspect_clerk` is not: it is a nested model loop over
the other six, and on the engine the agent's own loop calls them directly. Three names differ from
the phone's because the engine's registry already has `read_records` / `write_record` /
`delete_record` (generic household tables) in the same spec, and a model choosing between the
phone's `delete_record` and the registry's cannot be right:

| Tool | Phone tool | Reads or writes |
|---|---|---|
| `list_aspects` | `list_aspects` | reads |
| `describe_aspect` | `describe_aspect` | reads |
| `query_aspect_records` | `query_records` | reads |
| `create_aspect_record` | `create_record` | writes one entry |
| `update_aspect_record` | `update_record` | changes fields on one entry |
| `delete_aspect_record` | `delete_record` | trashes one entry, or a confirmed set |
| `create_aspect` | `create_aspect` | drafts, then on a confirmed second call creates a tracker |
| `update_aspect` | `update_aspect` | drafts, then on a confirmed second call applies one change |

**Values are the phone's own shapes**, so a row the engine writes reads the same in Room after the
pull: dates as epoch ms (a DATE is midnight on the household's clock), money as integer cents, a
list for multi-select, a REFERENCE as the target entry's `record_id`. `due_at` / `amount_cents` /
`search_text` are promoted exactly as `RecordStore` does. A COMPUTED field is materialized by the
phone's `RecordStore` when the row reaches it, and is refused here if a write names it.

**The draft handshake has no server state.** The phone keeps a draft in process memory behind a
token; on the engine the next turn may land on another instance. Here the model passes the whole
shape both times, and `draft_token` is a hash of that shape and the household: the confirmed
second call commits only the shape that was read back, and a changed shape is refused in words.

Every tool is an `EngineTool` kept out of `engine_mcp.tools.TOOLS` (like `homes/tools.py`), run
through `run_engine_tool`: writes get the write-scope fence and the ADR 0090 refusal on a tainted
turn. Every read is `scoped()` (the rows are shared in the household).
"""

from __future__ import annotations

import difflib
import hashlib
import json
import re
import uuid
from datetime import UTC, datetime, time

from django.db import transaction
from django.db.models.functions import Now

from engine_mcp import tools as reg
from engine_mcp.tools import EngineTool, ok, refused
from homes.models import UserAspect, UserFieldDef, UserRecord, UserRecordType
from household.tenancy import household_of, scoped

QUERY_LIMIT_DEFAULT = 20
QUERY_LIMIT_MAX = 200
PREVIEW = 5
# What `create_aspect` / `update_aspect` may author: the phone's own voice vocabulary
# (`EngineToolbox.fieldDraftSchema`). REFERENCE, PHOTO, LOCATION and COMPUTED need a capture flow
# or a pairing a spoken draft cannot give.
AUTHORABLE_TYPES = (
    "TEXT",
    "NUMBER",
    "MONEY_CENTS",
    "DATE",
    "DATETIME",
    "BOOLEAN",
    "CHOICE",
    "MULTI_SELECT_CHOICE",
    "RATING",
)
CHOICE_TYPES = ("CHOICE", "MULTI_SELECT_CHOICE")
TEXTISH = ("TEXT", "CHOICE", "MULTI_SELECT_CHOICE")
# The phone's plugin aspects (`engine/*/…AspectSeeder.ASPECT_NAME`): they exist on the phone with
# typed homes on the engine, so a tracker by the same name would be a second "Fleet" there.
BUILT_IN_ASPECTS = ("fleet", "ledger", "pantry", "notes", "places", "dates")
_WRITE = " Report it done only when this tool's result says it was committed."


def _dumps(value) -> str:
    return json.dumps(value, ensure_ascii=False, default=str)


def _zone(request):
    return reg._household_zone(request)


def _ms(at: datetime) -> int:
    return int(at.timestamp() * 1000)


def _from_ms(ms) -> datetime | None:
    if ms is None:
        return None
    return datetime.fromtimestamp(int(ms) / 1000, tz=UTC)


# -- reads of the schema -------------------------------------------------------------------------


def _live(model, request):
    return scoped(model, request).filter(deleted_at__isnull=True)


def _aspect(request, name: str):
    return _pick(
        name or "", list(_live(UserAspect, request).filter(archived=False)), lambda a: a.name
    )


_GENERIC = {
    "tracker",
    "trackers",
    "log",
    "logs",
    "my",
    "the",
    "entry",
    "entries",
    "record",
    "records",
}


def _norm(name: str) -> str:
    """A name as he might say it: case, punctuation, plurals and the word "tracker" dropped."""
    words = re.findall(r"[a-z0-9]+", (name or "").lower())
    kept = [w for w in words if w not in _GENERIC] or words
    return " ".join(
        w[:-1] if len(w) > 3 and w.endswith("s") and not w.endswith("ss") else w for w in kept
    )


def _pick(name: str, options: list, label) -> object | None:
    """The option `name` means: exact (any case), then the same said loosely, then one close
    sound-alike. None when nothing, or more than one, fits."""
    if not name.strip():
        return None
    exact = [o for o in options if label(o).lower() == name.strip().lower()]
    if len(exact) == 1:
        return exact[0]
    loose = [o for o in options if _norm(label(o)) == _norm(name)]
    if len(loose) == 1:
        return loose[0]
    close = difflib.get_close_matches(
        _norm(name), [_norm(label(o)) for o in options], n=2, cutoff=0.8
    )
    if len(close) == 1:
        found = [o for o in options if _norm(label(o)) == close[0]]
        return found[0] if len(found) == 1 else None
    return None


def _live_types(request) -> list:
    return list(
        _live(UserRecordType, request)
        .select_related("aspect")
        .filter(aspect__archived=False, aspect__deleted_at__isnull=True)
        .order_by("created_at_client", "id")
    )


def _record_type(request, aspect_name: str, type_name: str):
    """The record type he means, forgivingly (one query): the tracker by name, then its type by
    name; a tracker with one record type needs no type name, and a type name unique across his
    trackers needs no tracker name."""
    types = _live_types(request)
    aspects = list({t.aspect_id: t.aspect for t in types}.values())
    aspect = _pick(aspect_name or "", aspects, lambda a: a.name)
    if aspect is None:
        return _pick(type_name or "", types, lambda t: t.name)
    mine = [t for t in types if t.aspect_id == aspect.id]
    picked = _pick(type_name or "", mine, lambda t: t.name)
    return picked or (mine[0] if len(mine) == 1 else None)


def _catalogue(request) -> str:
    """His trackers, their record types and field names (no values): what a miss hands back so
    the next call can be right."""
    types = _live_types(request)
    fields: dict = {}
    for f in _live(UserFieldDef, request).filter(record_type__in=types).order_by("position", "id"):
        fields.setdefault(f.record_type_id, []).append(f.name)
    if not types:
        return "He has no trackers of his own yet."
    parts = [f"{t.aspect.name} > {t.name} ({', '.join(fields.get(t.id, []))})" for t in types]
    return "His trackers (tracker > record type (fields)): " + "; ".join(parts) + "."


def _fields(request, record_type) -> list:
    return list(
        _live(UserFieldDef, request)
        .filter(record_type=record_type)
        .order_by("position", "created_at_client", "id")
    )


def _config(field) -> dict:
    try:
        parsed = json.loads(field.config) if field.config else {}
    except ValueError:
        return {}
    return parsed if isinstance(parsed, dict) else {}


def _options(field) -> list[str]:
    raw = _config(field).get("options") or []
    return [str(o) for o in raw] if isinstance(raw, list) else []


def _no_aspect(name: str, request=None) -> str:
    where = (
        _catalogue(request) if request is not None else "list_aspects names the ones that exist."
    )
    return f"There is no tracker called {name!r}. {where}"


def _no_type(aspect_name: str, type_name: str, request=None) -> str:
    said = f"{aspect_name!r}" + (f" / {type_name!r}" if type_name else "")
    where = (
        _catalogue(request) if request is not None else "describe_aspect names its record types."
    )
    return f"No tracker or record type matches {said}. {where}"


# -- values: the phone's shapes in, words out ----------------------------------------------------


def _render(field, raw, zone):
    if raw is None:
        return None
    if isinstance(raw, dict) and "error" in raw:
        return f"could not be computed: {raw['error']}"
    if field.type == "DATE":
        return datetime.fromtimestamp(int(raw) / 1000, tz=zone).date().isoformat()
    if field.type == "DATETIME":
        return datetime.fromtimestamp(int(raw) / 1000, tz=zone).isoformat(timespec="minutes")
    return raw


def _coerce(field, raw, zone, request):
    """One spoken/typed value as the phone stores it, or `reg.BadArgument` saying why not."""
    if raw is None:
        return None
    kind = field.type
    if kind == "COMPUTED":
        raise reg.BadArgument(f"{field.name!r} is computed from other fields and is never typed in")
    if kind in ("TEXT", "PHOTO", "LOCATION"):
        return str(raw)
    if kind == "CHOICE":
        return _one_option(field, raw)
    if kind == "MULTI_SELECT_CHOICE":
        values = raw if isinstance(raw, list) else [raw]
        return [_one_option(field, v) for v in values]
    if kind in ("NUMBER", "RATING"):
        try:
            return float(raw)
        except (TypeError, ValueError) as exc:
            raise reg.BadArgument(f"{field.name!r} takes a number") from exc
    if kind == "MONEY_CENTS":
        try:
            value = float(raw)
        except (TypeError, ValueError) as exc:
            raise reg.BadArgument(f"{field.name!r} takes whole cents, a number") from exc
        if value != int(value) or isinstance(raw, bool):
            raise reg.BadArgument(f"{field.name!r} takes whole cents, never dollars and cents")
        return int(value)
    if kind == "DATE":
        day = reg._named_day(str(raw), zone)
        if day is None:
            raise reg.BadArgument(f"{field.name!r} takes a day, YYYY-MM-DD")
        return _ms(datetime.combine(day, time(0, 0), tzinfo=zone))
    if kind == "DATETIME":
        try:
            at = datetime.fromisoformat(str(raw).strip())
        except ValueError as exc:
            raise reg.BadArgument(f"{field.name!r} takes a date and time, ISO 8601") from exc
        return _ms(at if at.tzinfo else at.replace(tzinfo=zone))
    if kind == "BOOLEAN":
        if isinstance(raw, bool):
            return raw
        word = str(raw).strip().lower()
        if word in ("true", "yes"):
            return True
        if word in ("false", "no"):
            return False
        raise reg.BadArgument(f"{field.name!r} takes true or false")
    if kind == "REFERENCE":
        target = _config(field).get("targetRecordType")
        found = (
            _live(UserRecord, request)
            .filter(origin_guid=str(raw), trashed_at__isnull=True)
            .select_related("record_type")
            .first()
        )
        if found is None or (target and found.record_type.origin_guid != target):
            raise reg.BadArgument(
                f"{field.name!r} takes the record_id of an existing entry it points at"
            )
        return found.origin_guid
    return raw


def _one_option(field, raw) -> str:
    options = _options(field)
    for option in options:
        if option.lower() == str(raw).strip().lower():
            return option
    raise reg.BadArgument(f"{field.name!r} is one of: {', '.join(options) or '(no options set)'}")


def _values(fields: list, given: dict, zone, request) -> tuple[dict, list[str]]:
    """Name-keyed args to a guid-keyed payload delta. Unknown names come back, never dropped."""
    out, unknown = {}, []
    for name, raw in (given or {}).items():
        field = _pick(str(name), fields, lambda f: f.name)  # "minute" finds "minutes"
        if field is None:
            unknown.append(str(name))
            continue
        out[field.origin_guid] = _coerce(field, raw, zone, request)
    return out, unknown


def _promote(record, record_type, fields: list) -> None:
    """`RecordStore`'s promoted columns, from the payload."""
    payload = record.payload or {}
    due = payload.get(record_type.primary_due_date_field or "")
    amount = payload.get(record_type.primary_amount_field or "")
    record.due_at = _from_ms(due) if isinstance(due, int | float) else None
    record.amount_cents = int(amount) if isinstance(amount, int | float) else None
    parts = []
    for field in fields:
        if field.type not in TEXTISH:
            continue
        value = payload.get(field.origin_guid)
        if isinstance(value, list):
            value = " ".join(str(v) for v in value)
        if value not in (None, ""):
            parts.append(str(value))
    record.search_text = " ".join(p for p in parts if p.strip())


def _line(record, fields: list, zone) -> dict:
    payload = record.payload or {}
    shown = {}
    for field in fields:
        value = _render(field, payload.get(field.origin_guid), zone)
        if value is not None:
            shown[field.name] = value
    return {
        "record_id": record.origin_guid,
        "fields": shown,
        "updated_on": record.updated_at_client.astimezone(zone).date().isoformat(),
    }


# -- list_aspects / describe_aspect --------------------------------------------------------------


def _list_aspects(request, args):
    """His trackers, each record type and its field names (no values): enough to read or write
    any of them next, so describe_aspect is only for options and required fields."""
    aspects = list(
        _live(UserAspect, request).filter(archived=False).order_by("position", "created_at_client")
    )
    types = list(
        _live(UserRecordType, request)
        .filter(aspect__in=aspects)
        .order_by("created_at_client", "id")
    )
    fields: dict = {}
    for f in _live(UserFieldDef, request).filter(record_type__in=types).order_by("position", "id"):
        fields.setdefault(f.record_type_id, []).append(f.name)
    by_aspect: dict = {}
    for t in types:
        by_aspect.setdefault(t.aspect_id, []).append(
            {"name": t.name, "fields": fields.get(t.id, [])}
        )
    lines = [{"name": a.name, "record_types": by_aspect.get(a.id, [])} for a in aspects]
    if not lines:
        return ok(
            "He has not set up any trackers of his own. That is a real empty result.",
            structured={"aspects": []},
        )
    return ok(_dumps({"aspects": lines}), structured={"aspects": lines})


def _describe_aspect(request, args):
    name = args.get("aspect_name") or ""
    aspect = _aspect(request, name)
    if aspect is None:
        return refused(_no_aspect(name, request))
    types = list(
        _live(UserRecordType, request)
        .filter(aspect=aspect)
        .prefetch_related("field_defs")
        .order_by("created_at_client", "id")
    )
    out = []
    for record_type in types:
        fields = sorted(
            (f for f in record_type.field_defs.all() if f.deleted_at is None),
            key=lambda f: (f.position, f.created_at_client),
        )
        described = []
        for field in fields:
            entry = {"name": field.name, "type": field.type, "required": field.required}
            if field.type in CHOICE_TYPES:
                entry["options"] = _options(field)
            if field.type == "COMPUTED":
                entry["note"] = "computed from other fields; never typed in"
            described.append(entry)
        out.append({"name": record_type.name, "fields": described})
    body = {"aspect": aspect.name, "record_types": out}
    return ok(_dumps(body), structured=body)


# -- query / create / update ---------------------------------------------------------------------


def _matches(record, fields: list, filters: dict, zone, request) -> bool:
    by_name = {f.name.lower(): f for f in fields}
    payload = record.payload or {}
    for name, wanted in filters.items():
        field = by_name.get(str(name).lower())
        if field is None:
            return False
        try:
            target = _coerce(field, wanted, zone, request)
        except reg.BadArgument:
            return False
        stored = payload.get(field.origin_guid)
        if field.type == "MULTI_SELECT_CHOICE":
            wanted_all = target if isinstance(target, list) else [target]
            if not all(w in (stored or []) for w in wanted_all):
                return False
        elif str(stored) != str(target):
            return False
    return True


def _run_query(request, args):
    aspect_name = args.get("aspect_name") or ""
    type_name = args.get("record_type_name") or ""
    record_type = _record_type(request, aspect_name, type_name)
    if record_type is None:
        return None, None, None, _no_type(aspect_name, type_name, request)
    fields = _fields(request, record_type)
    zone = _zone(request)
    filters = args.get("filters") or {}
    known = {f.name.lower() for f in fields}
    unknown = [str(n) for n in filters if str(n).lower() not in known]
    if unknown:
        return (
            None,
            None,
            None,
            (
                f"{record_type.name!r} has no field called {', '.join(unknown)}. Its fields: "
                f"{', '.join(f.name for f in fields)}."
            ),
        )
    words = _search_words(args.get("search_text") or "", record_type)
    rows = _live(UserRecord, request).filter(record_type=record_type, trashed_at__isnull=True)
    matched = [
        r
        for r in rows.order_by("-updated_at_client")
        if _matches(r, fields, filters, zone, request)
        and all(w in _norm(r.search_text or "") for w in words)
    ]
    return record_type, fields, matched, None


def _search_words(phrase: str, record_type) -> list[str]:
    """ "the rowing session" finds an entry whose text says Rowing: the type's and tracker's own
    names and filler words are not required to appear."""
    skip = set(_norm(record_type.name).split()) | set(_norm(record_type.aspect.name).split())
    return [w for w in _norm(phrase).split() if w not in skip and len(w) > 1]


def _query(request, args):
    record_type, fields, matched, error = _run_query(request, args)
    if error:
        return refused(f"Nothing was read. {error}")
    limit = max(1, min(int(args.get("limit") or QUERY_LIMIT_DEFAULT), QUERY_LIMIT_MAX))
    zone = _zone(request)
    lines = [_line(r, fields, zone) for r in matched[:limit]]
    body = {"count": len(matched), "shown": len(lines), "records": lines}
    if not lines:
        return ok(
            f"No {record_type.name} entries match. That is a real empty result.",
            structured=body,
        )
    return ok(_dumps(body), structured=body)


def _create_record(request, args):
    aspect_name = args.get("aspect_name") or ""
    type_name = args.get("record_type_name") or ""
    record_type = _record_type(request, aspect_name, type_name)
    if record_type is None:
        return refused(f"Nothing was written. {_no_type(aspect_name, type_name, request)}")
    fields = _fields(request, record_type)
    zone = _zone(request)
    try:
        values, unknown = _values(fields, args.get("fields") or {}, zone, request)
    except reg.BadArgument as exc:
        return refused(f"Nothing was written. {exc}.")
    if unknown:
        return refused(
            f"Nothing was written. {record_type.name!r} has no field called "
            f"{', '.join(unknown)}. Its fields: {', '.join(f.name for f in fields)}."
        )
    missing = [
        f.name
        for f in fields
        if f.required and f.type != "COMPUTED" and values.get(f.origin_guid) in (None, "", [])
    ]
    if missing:
        return refused(
            f"Nothing was written. {', '.join(missing)} {'is' if len(missing) == 1 else 'are'} "
            f"required; ask him for {'it' if len(missing) == 1 else 'them'}."
        )
    now = reg._now()
    payload = {f.origin_guid: values.get(f.origin_guid) for f in fields if f.type != "COMPUTED"}
    record = UserRecord(
        household=household_of(request),
        origin_guid=str(uuid.uuid4()),
        record_type=record_type,
        provenance="USER",
        payload=payload,
        created_at_client=now,
        updated_at_client=now,
    )
    _promote(record, record_type, fields)
    record.save(force_insert=True)
    line = _line(record, fields, zone)
    return ok(
        f"Created and committed in {record_type.name}: {_dumps(line)}",
        structured={"record": line},
    )


def _find_record(request, record_id: str):
    return (
        _live(UserRecord, request)
        .filter(origin_guid=(record_id or "").strip(), record_type__deleted_at__isnull=True)
        .select_related("record_type")
        .first()
    )


def _one_by_search(request, args, verb: str):
    """(record, None) when the tracker and search phrase name exactly one live entry, else
    (None, the refusal in words, naming the candidates or what exists)."""
    record_type, fields, matched, error = _run_query(request, args)
    if error:
        return None, f"Nothing was {verb}. {error}"
    if len(matched) == 1:
        return matched[0], None
    if not matched:
        return None, f"Nothing was {verb}. No {record_type.name} entry matches that."
    zone = _zone(request)
    shown = _dumps([_line(r, fields, zone) for r in matched[:PREVIEW]])
    return None, (
        f"Nothing was {verb}. {len(matched)} {record_type.name} entries match; ask which one and "
        f"pass its record_id: {shown}"
    )


def _update_record(request, args):
    record_id = (args.get("record_id") or "").strip()
    if record_id:
        record = _find_record(request, record_id)
    elif args.get("aspect_name") or args.get("search_text"):
        record, refusal = _one_by_search(request, args, "written")
        if refusal:
            return refused(refusal)
    else:
        record = None
    if record is None:
        return refused(
            f"Nothing was written. No entry has record_id {record_id!r}; give the tracker and a "
            f"search_text, or a record_id from query_aspect_records."
        )
    if record.trashed_at is not None:
        return refused("Nothing was written. That entry is in the trash.")
    fields = _fields(request, record.record_type)
    zone = _zone(request)
    try:
        values, unknown = _values(fields, args.get("fields") or {}, zone, request)
    except reg.BadArgument as exc:
        return refused(f"Nothing was written. {exc}.")
    if unknown:
        return refused(
            f"Nothing was written. That entry's type has no field called {', '.join(unknown)}. "
            f"Its fields: {', '.join(f.name for f in fields)}."
        )
    if not values:
        return refused("Nothing was written. Name at least one field to change in `fields`.")
    required_cleared = [
        f.name
        for f in fields
        if f.required and f.origin_guid in values and values[f.origin_guid] in (None, "", [])
    ]
    if required_cleared:
        return refused(f"Nothing was written. {', '.join(required_cleared)} cannot be emptied.")
    record.payload = {**(record.payload or {}), **values}
    record.updated_at_client = reg._now()
    _promote(record, record.record_type, fields)
    record.updated_at = Now()
    record.save(
        update_fields=[
            "payload",
            "updated_at_client",
            "due_at",
            "amount_cents",
            "search_text",
            "updated_at",
        ]
    )
    record.refresh_from_db()
    line = _line(record, fields, zone)
    return ok(f"Updated and committed: {_dumps(line)}", structured={"record": line})


# -- delete: trash, with the phone's reference policy --------------------------------------------


def _reference_fields(request) -> list[tuple[object, str, str]]:
    """Every live REFERENCE field: (field, target record type guid, delete policy)."""
    out = []
    for field in (
        _live(UserFieldDef, request).filter(type="REFERENCE").select_related("record_type")
    ):
        config = _config(field)
        target = config.get("targetRecordType")
        if target:
            out.append((field, str(target), str(config.get("deletePolicy") or "BLOCK")))
    return out


def _delete_plan(request, roots: list, refs: list) -> tuple[list[str], list, list]:
    """`RecordStore.buildDeletePlan`: walk CASCADE children; any BLOCK referrer anywhere stops
    the whole delete. Returns (blockers in words, records to trash, (record, field) to null)."""
    blockers, cascade, nullify = [], [], []
    seen = {r.origin_guid for r in roots}
    frontier = list(roots)
    while frontier:
        record = frontier.pop()
        for field, target, policy in refs:
            if target != record.record_type.origin_guid:
                continue
            referrers = (
                _live(UserRecord, request)
                .filter(
                    record_type=field.record_type,
                    trashed_at__isnull=True,
                    payload__contains={field.origin_guid: record.origin_guid},
                )
                .select_related("record_type")
            )
            for child in referrers:
                if policy == "BLOCK":
                    blockers.append(
                        f"a {child.record_type.name} entry ({child.origin_guid}) still points at "
                        f"it through {field.name!r}"
                    )
                elif policy == "CASCADE" and child.origin_guid not in seen:
                    seen.add(child.origin_guid)
                    cascade.append(child)
                    frontier.append(child)
                elif policy == "NULLIFY":
                    nullify.append((child, field))
    return blockers, cascade, nullify


def _trash(request, roots: list) -> tuple[int, list[str]]:
    refs = _reference_fields(request)
    blockers, cascade, nullify = _delete_plan(request, roots, refs)
    if blockers:
        return 0, blockers
    now = reg._now()
    with transaction.atomic():
        guids = [r.origin_guid for r in (*roots, *cascade)]
        _live(UserRecord, request).filter(origin_guid__in=guids).update(
            trashed_at=now, updated_at_client=now, updated_at=Now()
        )
        for child, field in nullify:
            child.payload = {**child.payload, field.origin_guid: None}
            child.updated_at_client = now
            child.updated_at = Now()
            child.save(update_fields=["payload", "updated_at_client", "updated_at"])
    return len(guids), []


def _label(record, fields: list) -> str | None:
    for field in fields:
        if field.type in ("TEXT", "CHOICE"):
            value = (record.payload or {}).get(field.origin_guid)
            if value:
                return str(value)
    return None


def _delete_record(request, args):
    record_id = (args.get("record_id") or "").strip()
    if record_id:
        record = _find_record(request, record_id)
        if record is None or record.trashed_at is not None:
            return refused(
                f"Nothing was deleted. No entry has record_id {record_id!r} (or it is already "
                f"in the trash)."
            )
        trashed, blockers = _trash(request, [record])
        if blockers:
            return refused(f"Nothing was deleted. {'; '.join(blockers)}.")
        label = _label(record, _fields(request, record.record_type))
        also = f" and {trashed - 1} entries that pointed at it" if trashed > 1 else ""
        return ok(
            f"Deleted and committed: the {record.record_type.name} entry"
            f"{f' ({label})' if label else ''}{also}. It stays in the trash for 30 days.",
            structured={"record_id": record.origin_guid, "trashed": trashed},
        )
    if not args.get("aspect_name") and not args.get("record_type_name"):
        return refused(
            "Nothing was deleted. Give one record_id, or the tracker (aspect_name) with a "
            "search_text or filters."
        )
    record_type, fields, matched, error = _run_query(request, args)
    narrowed = bool((args.get("search_text") or "").strip() or args.get("filters"))
    if not error and narrowed and len(matched) == 1 and not args.get("confirm"):
        return _delete_record(request, {"record_id": matched[0].origin_guid})
    if error:
        return refused(f"Nothing was deleted. {error}")
    if not matched:
        return refused("Nothing was deleted. No entry matches that.")
    if not args.get("confirm"):
        zone = _zone(request)
        preview = [_line(r, fields, zone) for r in matched[:PREVIEW]]
        return ok(
            f"{len(matched)} {record_type.name} entries match. Nothing has been deleted yet: tell "
            f"him the count, and only after he agrees call delete_aspect_record again with the "
            f"same arguments and confirm: true. {_dumps(preview)}",
            structured={"committed": False, "matched": len(matched), "preview": preview},
        )
    trashed, blockers = _trash(request, matched)
    if blockers:
        return refused(f"Nothing was deleted. {'; '.join(blockers)}.")
    return ok(
        f"Deleted and committed: {trashed} {record_type.name} entries, kept in the trash for 30 "
        f"days.",
        structured={"committed": True, "trashed": trashed},
    )


# -- create_aspect / update_aspect: a stateless draft handshake ----------------------------------


def _draft_fields(raw_fields) -> tuple[list[dict], str | None]:
    out, names = [], set()
    for raw in raw_fields or []:
        name = str(raw.get("name") or "").strip()
        kind = str(raw.get("type") or "").strip().upper()
        if not name:
            return [], "every field needs a name"
        if name.lower() in names:
            return [], f"two fields are called {name!r}"
        names.add(name.lower())
        if kind not in AUTHORABLE_TYPES:
            return [], f"{name!r} has type {kind!r}; use one of {', '.join(AUTHORABLE_TYPES)}"
        entry = {"name": name, "type": kind, "required": bool(raw.get("required"))}
        if kind in CHOICE_TYPES:
            options = [str(o).strip() for o in (raw.get("options") or []) if str(o).strip()]
            if not options:
                return [], f"{name!r} is a choice field and needs its options"
            entry["options"] = options
        out.append(entry)
    if not out:
        return [], "a record type needs at least one field"
    return out, None


def _draft_type(raw) -> tuple[dict | None, str | None]:
    name = str((raw or {}).get("name") or "").strip()
    if not name:
        return None, "every record type needs a name"
    fields, error = _draft_fields((raw or {}).get("fields"))
    if error:
        return None, error
    return {"name": name, "fields": fields}, None


def _token(request, draft: dict) -> str:
    canon = json.dumps(draft, sort_keys=True, ensure_ascii=False)
    seed = f"{household_of(request).pk}|{canon}".encode()
    return hashlib.sha256(seed).hexdigest()[:12]


def _shape_words(record_type: dict) -> str:
    return f"{record_type['name']} ({', '.join(f['name'] for f in record_type['fields'])})"


def _make_type(request, aspect, draft: dict, now) -> tuple[object, list]:
    record_type = UserRecordType(
        household=household_of(request),
        origin_guid=str(uuid.uuid4()),
        aspect=aspect,
        name=draft["name"],
        created_at_client=now,
        updated_at_client=now,
    )
    return record_type, [
        _make_field(request, record_type, f, n, now) for n, f in enumerate(draft["fields"])
    ]


def _make_field(request, record_type, draft: dict, position: int, now):
    config = None
    if draft["type"] in CHOICE_TYPES:
        config = json.dumps({"options": draft["options"]}, ensure_ascii=False)
    return UserFieldDef(
        household=household_of(request),
        origin_guid=str(uuid.uuid4()),
        record_type=record_type,
        name=draft["name"],
        type=draft["type"],
        required=draft["required"],
        position=position,
        config=config,
        created_at_client=now,
        updated_at_client=now,
    )


def _name_taken(request, name: str) -> str | None:
    if name.lower() in BUILT_IN_ASPECTS:
        return f"{name!r} is one of the phone's built-in areas; pick another name"
    if _live(UserAspect, request).filter(name__iexact=name).exists():
        return f"a tracker called {name!r} already exists (it may be archived)"
    return None


def _create_aspect(request, args):
    name = (args.get("aspect_name") or "").strip()
    if not name:
        return refused("Nothing was created. Name the tracker in `aspect_name`.")
    types, seen = [], set()
    for raw in args.get("record_types") or []:
        draft, error = _draft_type(raw)
        if error:
            return refused(f"Nothing was created. {error}.")
        if draft["name"].lower() in seen:
            return refused(f"Nothing was created. Two record types are called {draft['name']!r}.")
        seen.add(draft["name"].lower())
        types.append(draft)
    if not types:
        return refused("Nothing was created. A tracker needs at least one record type.")
    taken = _name_taken(request, name)
    if taken:
        return refused(f"Nothing was created. {taken}.")
    draft = {"aspect_name": name, "record_types": types}
    token = _token(request, draft)
    words = f"a tracker called {name!r} with {'; '.join(_shape_words(t) for t in types)}"
    if not args.get("confirm"):
        return ok(
            f"Draft only, NOT created: {words}. Read it back to him; only after he agrees call "
            f"create_aspect again with the same arguments, draft_token {token!r} and confirm: "
            f"true.",
            structured={"committed": False, "draft_token": token, "draft": draft},
        )
    if args.get("draft_token") != token:
        return refused(
            "Nothing was created. That draft_token does not match this shape: the draft changed "
            "since it was read back. Read the new one back and ask again."
        )
    now = reg._now()
    with transaction.atomic():
        aspect = UserAspect.objects.create(
            household=household_of(request),
            origin_guid=str(uuid.uuid4()),
            name=name,
            created_at_client=now,
            updated_at_client=now,
        )
        made = [_make_type(request, aspect, t, now) for t in types]
        UserRecordType.objects.bulk_create([rt for rt, _ in made])
        UserFieldDef.objects.bulk_create([f for _, fs in made for f in fs])
    return ok(
        f"Created and committed: {words}.",
        structured={"committed": True, "aspect": name, "record_types": len(types)},
    )


UPDATE_CHANGES = ("add_record_type", "add_field", "rename")


def _update_aspect(request, args):
    name = (args.get("aspect_name") or "").strip()
    aspect = _aspect(request, name)
    if aspect is None:
        return refused(f"Nothing changed. {_no_aspect(name, request)}")
    change = args.get("change")
    if change not in UPDATE_CHANGES:
        return refused(f"Nothing changed. `change` is one of {', '.join(UPDATE_CHANGES)}.")
    draft: dict = {"aspect": aspect.origin_guid, "change": change}
    target = None
    if change == "rename":
        new_name = (args.get("new_name") or "").strip()
        if not new_name:
            return refused("Nothing changed. Give the tracker's new name in `new_name`.")
        if new_name.lower() != aspect.name.lower():
            taken = _name_taken(request, new_name)
            if taken:
                return refused(f"Nothing changed. {taken}.")
        draft["new_name"] = new_name
        words = f"rename {aspect.name!r} to {new_name!r}"
    elif change == "add_record_type":
        record_type, error = _draft_type(args.get("record_type"))
        if error:
            return refused(f"Nothing changed. {error}.")
        if (
            _live(UserRecordType, request)
            .filter(aspect=aspect, name__iexact=record_type["name"])
            .exists()
        ):
            return refused(f"Nothing changed. {aspect.name!r} already has {record_type['name']!r}.")
        draft["record_type"] = record_type
        words = f"add {_shape_words(record_type)} to {aspect.name!r}"
    else:
        target = _record_type(request, aspect.name, args.get("target_record_type") or "")
        if target is None:
            missing = _no_type(aspect.name, args.get("target_record_type") or "", request)
            return refused(f"Nothing changed. {missing}")
        fields, error = _draft_fields([args.get("field") or {}])
        if error:
            return refused(f"Nothing changed. {error}.")
        if any(f.name.lower() == fields[0]["name"].lower() for f in _fields(request, target)):
            return refused(f"Nothing changed. {target.name!r} already has {fields[0]['name']!r}.")
        draft["target"] = target.origin_guid
        draft["field"] = fields[0]
        words = f"add a {fields[0]['type']} field {fields[0]['name']!r} to {target.name!r}"
    token = _token(request, draft)
    if not args.get("confirm"):
        return ok(
            f"Draft only, NOT applied: {words}. Read it back to him; only after he agrees call "
            f"update_aspect again with the same arguments, draft_token {token!r} and confirm: "
            f"true.",
            structured={"committed": False, "draft_token": token},
        )
    if args.get("draft_token") != token:
        return refused(
            "Nothing changed. That draft_token does not match this change: the draft changed "
            "since it was read back. Read the new one back and ask again."
        )
    now = reg._now()
    with transaction.atomic():
        if change == "rename":
            aspect.name = draft["new_name"]
            aspect.updated_at_client = now
            aspect.updated_at = Now()
            aspect.save(update_fields=["name", "updated_at_client", "updated_at"])
        elif change == "add_record_type":
            record_type, fields = _make_type(request, aspect, draft["record_type"], now)
            record_type.save(force_insert=True)
            UserFieldDef.objects.bulk_create(fields)
        else:
            position = len(_fields(request, target))
            _make_field(request, target, draft["field"], position, now).save(force_insert=True)
    return ok(f"Applied and committed: {words}.", structured={"committed": True, "change": change})


# -- declarations --------------------------------------------------------------------------------


def _obj(props: dict, required: tuple[str, ...] = ()) -> dict:
    return {
        "type": "object",
        "properties": props,
        "required": list(required),
        "additionalProperties": False,
    }


_NAME = {"type": "string", "minLength": 1}
_FIELD_DRAFT = {
    "type": "object",
    "properties": {
        "name": _NAME,
        "type": {"type": "string", "enum": list(AUTHORABLE_TYPES)},
        "required": {"type": "boolean"},
        "options": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["name", "type"],
    "additionalProperties": False,
}
_TYPE_DRAFT = {
    "type": "object",
    "properties": {"name": _NAME, "fields": {"type": "array", "items": _FIELD_DRAFT}},
    "required": ["name", "fields"],
    "additionalProperties": False,
}
_VALUES = (
    "Field name to value, using describe_aspect's names: a DATE is YYYY-MM-DD, a DATETIME is "
    "ISO 8601, MONEY_CENTS is whole cents (never dollars), a choice is one of its options, a "
    "multi-select a list. Only values he said; never guess one."
)

LIST_ASPECTS = EngineTool(
    name="list_aspects",
    title="His trackers",
    description=(
        "List the trackers he set up himself (guitar practice, reading, anything he defined) "
        'with each record type and its field names, for "what do I track". Read-only. Not '
        "needed before reading or writing a tracker he named. Not money, cars, food, workouts, "
        "notes, places or the calendar: those have their own tools."
    ),
    input_schema=_obj({}),
    handler=_list_aspects,
    writes=False,
)

DESCRIBE_ASPECT = EngineTool(
    name="describe_aspect",
    title="A tracker's fields",
    description=(
        "Describe one of his trackers: each record type and its fields (name, type, required, a "
        'choice field\'s options), for "what can I log". Read-only. Not needed before a write: '
        "pass field names as he says them, and a write with a name that does not fit is refused "
        "with the real names, nothing written."
    ),
    input_schema=_obj({"aspect_name": _NAME}, ("aspect_name",)),
    handler=_describe_aspect,
    writes=False,
)

QUERY_ASPECT_RECORDS = EngineTool(
    name="query_aspect_records",
    title="Entries in a tracker",
    description=(
        "Find entries in one of his trackers, newest first, each with its `record_id`. Call it "
        "straight away with the tracker's name as he says it; record_type_name is needed only "
        "when the tracker has several. `search_text` matches words in the entry's text; "
        "`filters` is field name to exact value. A miss lists his trackers. Read-only."
    ),
    input_schema=_obj(
        {
            "aspect_name": _NAME,
            "record_type_name": {"type": "string"},
            "filters": {"type": "object"},
            "search_text": {"type": "string"},
            "limit": {"type": "integer", "minimum": 1, "maximum": QUERY_LIMIT_MAX},
        },
        ("aspect_name",),
    ),
    handler=_query,
    writes=False,
)

CREATE_ASPECT_RECORD = EngineTool(
    name="create_aspect_record",
    title="Log an entry in a tracker",
    description=(
        "Log one new entry in one of his trackers (record_type_name only when it has several). "
        + _VALUES
        + _WRITE
    ),
    input_schema=_obj(
        {
            "aspect_name": _NAME,
            "record_type_name": {"type": "string"},
            "fields": {"type": "object"},
        },
        ("aspect_name", "fields"),
    ),
    handler=_create_record,
    writes=True,
)

UPDATE_ASPECT_RECORD = EngineTool(
    name="update_aspect_record",
    title="Change an entry in a tracker",
    description=(
        "Change fields on one entry: by `record_id`, or by the tracker (aspect_name) and a "
        "`search_text` that names exactly one entry (if several match, it lists them and changes "
        "nothing). Only the fields named change. " + _VALUES + _WRITE
    ),
    input_schema=_obj(
        {
            "record_id": {"type": "string"},
            "aspect_name": {"type": "string"},
            "record_type_name": {"type": "string"},
            "search_text": {"type": "string"},
            "fields": {"type": "object"},
        },
        ("fields",),
    ),
    handler=_update_record,
    writes=True,
)

DELETE_ASPECT_RECORD = EngineTool(
    name="delete_aspect_record",
    title="Delete tracker entries",
    description=(
        "Trash (30 days, recoverable) one entry: by `record_id`, or by the tracker (aspect_name) "
        "and a `search_text` that names exactly one entry; that is done at once. A set (several "
        "match, or filters only): the first call only counts them and deletes nothing; call again "
        "with the same arguments and confirm: true only after he agreed to that count." + _WRITE
    ),
    input_schema=_obj(
        {
            "record_id": {"type": "string"},
            "aspect_name": {"type": "string"},
            "record_type_name": {"type": "string"},
            "search_text": {"type": "string"},
            "filters": {"type": "object"},
            "confirm": {"type": "boolean"},
        }
    ),
    handler=_delete_record,
    writes=True,
)

CREATE_ASPECT = EngineTool(
    name="create_aspect",
    title="Start a new tracker",
    description=(
        "Start a tracker he describes: its name, record types and their fields. The first call "
        "only drafts it and returns a draft_token; read the shape back. Call again with the same "
        "arguments, that draft_token and confirm: true only after he agreed. Only fields he "
        "asked for." + _WRITE
    ),
    input_schema=_obj(
        {
            "aspect_name": _NAME,
            "record_types": {"type": "array", "items": _TYPE_DRAFT, "minItems": 1},
            "confirm": {"type": "boolean"},
            "draft_token": {"type": "string"},
        },
        ("aspect_name", "record_types"),
    ),
    handler=_create_aspect,
    writes=True,
)

UPDATE_ASPECT = EngineTool(
    name="update_aspect",
    title="Change a tracker",
    description=(
        "One additive change to one of his trackers: add_record_type (record_type), add_field "
        "(target_record_type and field) or rename (new_name). Same handshake as create_aspect: "
        "draft first, then the same arguments with draft_token and confirm: true after he "
        "agreed." + _WRITE
    ),
    input_schema=_obj(
        {
            "aspect_name": _NAME,
            "change": {"type": "string", "enum": list(UPDATE_CHANGES)},
            "record_type": _TYPE_DRAFT,
            "target_record_type": {"type": "string"},
            "field": _FIELD_DRAFT,
            "new_name": {"type": "string"},
            "confirm": {"type": "boolean"},
            "draft_token": {"type": "string"},
        },
        ("aspect_name", "change"),
    ),
    handler=_update_aspect,
    writes=True,
)

RECORD_TOOLS: tuple[EngineTool, ...] = (
    LIST_ASPECTS,
    DESCRIBE_ASPECT,
    QUERY_ASPECT_RECORDS,
    CREATE_ASPECT_RECORD,
    UPDATE_ASPECT_RECORD,
    DELETE_ASPECT_RECORD,
    CREATE_ASPECT,
    UPDATE_ASPECT,
)
