"""MCP server over the LEGION engine's live household data, read-only.

engine-mcp ticket 09: a spike, not the engine's MCP surface. It wraps REST routes that already
exist (`server/api/`, `server/checklists/`) with a device token that already exists, so tenancy
comes from the engine's own `household_of` choke point and nothing here can widen it. Kevin,
2026-10-02: Claude Code may query his live household while building. Read-only.

Config, from the environment (`.claude/mcp.env`, gitignored, loaded by `tools/mcp/launch.py`):

- `LEGION_ENGINE_URL`   the engine's base URL, e.g. `https://<service>.run.app`. No `/api`.
- `LEGION_ENGINE_TOKEN` a device token, sent as `Authorization: Token <key>`. Never committed.

**GET only.** Every request this module makes goes through `_get`, which builds a
`urllib.request.Request` with `method="GET"` and nothing else. There is no code path that sends
a body. `test_server.py` asserts it by recording every method the fake engine receives while
every tool runs, success and failure paths alike.

**Every failure says what did not happen** (CLAUDE.md section 7). An unreachable engine is never
rendered as an empty list (section 1: unreadable and empty are different sentences), and a
failure on page three of a listing returns nothing rather than the first two pages, so a partial
read can never be mistaken for the whole.

**Unverified rows say so in words** (section 4 rule 7). A ledger transaction or a receipt whose
provenance is `UNRECONCILED` carries `verified: false` and a note that begins "Unverified".

    uv run --project tools/mcp/engine tools/mcp/engine/server.py
"""
from __future__ import annotations

import json
import os
import socket
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, datetime, timedelta, timezone

from mcp.server.fastmcp import FastMCP

URL_VAR = "LEGION_ENGINE_URL"
TOKEN_VAR = "LEGION_ENGINE_TOKEN"
TIMEOUT_S = 30  # Cloud Run at min-instances 0 measured a 5.4 s cold start.
MAX_PAGES = 40  # 500 rows a page on the engine side, so 20,000 rows before we stop and say so.

UNVERIFIED_TXN_NOTE = (
    "Unverified: the source stated no balance or total to check this row against (section 4 "
    "rule 7). It is provisional. Any total that includes it is unverified too."
)
UNVERIFIED_RECEIPT_NOTE = (
    "Unverified: this receipt's line items could not be re-verified against its printed total "
    "(section 4 rule 7). Any figure that includes it is unverified. `unaccounted_cents`, when "
    "present, is the unexplained remainder and must never be added into a total."
)

mcp = FastMCP("legion-engine")


class EngineError(Exception):
    """A read that did not happen. `message` is the sentence the tool returns, in words."""

    def __init__(self, kind: str, message: str):
        super().__init__(message)
        self.kind = kind
        self.message = message


def _failure(err: EngineError) -> dict:
    return {"ok": False, "failure": err.kind, "read": "nothing", "error": err.message}


def _config() -> tuple[str, str]:
    base = os.environ.get(URL_VAR, "").strip()
    token = os.environ.get(TOKEN_VAR, "").strip()
    missing = [name for name, value in ((URL_VAR, base), (TOKEN_VAR, token)) if not value]
    if missing:
        raise EngineError(
            "not_configured",
            "%s not set, so no request was made and nothing was read. Put it in .claude/mcp.env "
            "(gitignored) and restart the MCP server." % ", ".join(missing),
        )
    return base.rstrip("/"), token


def _detail(raw: bytes) -> str:
    try:
        body = json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        return raw[:200].decode("utf-8", "replace").strip()
    if isinstance(body, dict) and "detail" in body:
        return str(body["detail"])
    return json.dumps(body)[:200]


def _get(path: str, params: dict | None = None) -> dict:
    """The ONLY function that talks to the engine. GET, nothing else, no body."""
    base, token = _config()
    url = base + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    request = urllib.request.Request(
        url,
        method="GET",
        headers={"Authorization": "Token " + token, "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_S) as response:
            raw = response.read()
    except urllib.error.HTTPError as exc:
        try:
            detail = _detail(exc.read() or b"")
        finally:
            exc.close()
        if exc.code == 401:
            raise EngineError(
                "token_refused",
                "The engine refused the token (HTTP 401: %s). Nothing was read. The token in "
                "%s is wrong, revoked, or belongs to an inactive user." % (detail, TOKEN_VAR),
            ) from None
        if exc.code == 403:
            raise EngineError(
                "no_household",
                "The engine accepted the token but refused the read (HTTP 403: %s). Nothing was "
                "read. On these routes that means the account the token belongs to is in no "
                "household, so there is no set of rows it may see. An owner has to add it to "
                "one." % detail,
            ) from None
        if exc.code == 404:
            raise EngineError(
                "not_found",
                "The engine has no route or row at %s (HTTP 404: %s). Nothing was read. %s may "
                "point at the wrong host, or the engine predates this route." % (path, detail, URL_VAR),
            ) from None
        raise EngineError(
            "engine_error",
            "The engine answered HTTP %d for %s (%s). Nothing was read." % (exc.code, path, detail),
        ) from None
    except (urllib.error.URLError, socket.timeout, TimeoutError, ConnectionError, OSError) as exc:
        reason = getattr(exc, "reason", exc)
        raise EngineError(
            "unreachable",
            "The engine at %s is unreachable (%s). Nothing was read. This is not an empty "
            "result: the household's rows were not seen at all." % (base, reason),
        ) from None
    try:
        return json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        raise EngineError(
            "bad_response",
            "The engine answered %s with something that is not JSON. Nothing was read. %s may "
            "point at a web page rather than the engine." % (path, URL_VAR),
        ) from None


def _list_all(path: str) -> tuple[list[dict], bool]:
    """Every row of a `{results, next[, next_after]}` listing. Returns (rows, truncated).

    Two cursor shapes exist on the engine: the synced tables return `next_after` (a keyset
    tiebreak), events and checklists return only `next`, which is inclusive, so rows on the page
    boundary repeat and are de-duplicated by id here. A failure on any page raises, so the
    caller never sees a partial listing presented as the whole.
    """
    rows: dict[str, dict] = {}
    params: dict = {}
    for _ in range(MAX_PAGES):
        body = _get(path, params)
        if not isinstance(body, dict) or not isinstance(body.get("results"), list):
            raise EngineError(
                "bad_response",
                "The engine answered %s without a `results` list. Nothing was read." % path,
            )
        before = len(rows)
        for row in body["results"]:
            key = str(row.get("id", len(rows)))
            rows[key] = row
        nxt = body.get("next")
        if not nxt:
            return list(rows.values()), False
        after = body.get("next_after")
        new_params = {"since": nxt}
        if after is not None:
            new_params["after"] = after
        if new_params == params and len(rows) == before:
            # An inclusive cursor that re-served the same page: stop rather than loop forever.
            return list(rows.values()), True
        params = new_params
    return list(rows.values()), True


def _live(rows: list[dict]) -> list[dict]:
    return [r for r in rows if not r.get("deleted_at")]


def _parse_dt(raw) -> datetime | None:
    if not raw:
        return None
    try:
        parsed = datetime.fromisoformat(str(raw).replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def _parse_day(raw) -> date | None:
    if not raw:
        return None
    try:
        return date.fromisoformat(str(raw)[:10])
    except ValueError:
        return None


def _truncation_note(truncated: bool) -> str | None:
    if not truncated:
        return None
    return (
        "Truncated: the engine had more than %d pages and reading stopped there. Rows past that "
        "point were not read, so a count or total here is a lower bound." % MAX_PAGES
    )


EVENT_FIELDS = (
    "id", "title", "kind", "starts_at", "ends_at", "all_day", "location", "notes", "done",
    "done_at", "source", "trigger_place_label", "repeat_kind", "repeat_every",
    "repeat_days_of_week", "repeat_day", "repeat_month", "repeat_end_kind", "repeat_end_date",
    "repeat_end_count", "missed_at",
)


def _event_view(row: dict) -> dict:
    return {k: row.get(k) for k in EVENT_FIELDS if k in row}


@mcp.tool()
def due(days_ahead: int = 7, days_back: int = 1, include_done: bool = False) -> dict:
    """What is on the household's calendar and to-do list, from the engine's `events` table.

    Returns four lists of live (not deleted) events: `upcoming` (starts between now and
    `days_ahead` days from now), `recent` (started in the last `days_back` days), `overdue`
    (tasks and reminders, not events, that started before now and are not done), and
    `repeating` (rows with a repeat rule; their `starts_at` is the FIRST occurrence, and the
    next occurrence is NOT computed here). Undated tasks are in `undated`. Done rows are left out
    unless `include_done`. Times are UTC as stored. A failure reads nothing and says why.
    """
    try:
        rows, truncated = _list_all("/api/events")
    except EngineError as err:
        return _failure(err)
    now = datetime.now(timezone.utc)
    horizon = now + timedelta(days=max(0, days_ahead))
    floor = now - timedelta(days=max(0, days_back))
    out = {"upcoming": [], "recent": [], "overdue": [], "repeating": [], "undated": []}
    for row in _live(rows):
        if row.get("done") and not include_done:
            continue
        view = _event_view(row)
        if row.get("repeat_kind"):
            out["repeating"].append(view)
            continue
        starts = _parse_dt(row.get("starts_at"))
        if starts is None:
            if row.get("kind") != "event":
                out["undated"].append(view)
            continue
        if now <= starts <= horizon:
            out["upcoming"].append(view)
        elif floor <= starts < now:
            out["recent"].append(view)
        elif starts < floor and row.get("kind") != "event" and not row.get("done"):
            out["overdue"].append(view)
    for key in ("upcoming", "recent", "overdue"):
        out[key].sort(key=lambda v: v.get("starts_at") or "")
    return {
        "ok": True,
        "now_utc": now.isoformat(timespec="seconds"),
        "window": {"from": floor.isoformat(timespec="seconds"), "to": horizon.isoformat(timespec="seconds")},
        **out,
        "note": _truncation_note(truncated),
    }


@mcp.tool()
def checklists(include_items: bool = True, include_archived: bool = False) -> dict:
    """The household's checklists from the engine, each with its items when `include_items`.

    Live rows only. Ticks (which days an item was done) are not read here. One request per
    checklist for its items; if any of them fails, nothing is returned and the result says which.
    """
    try:
        lists, truncated = _list_all("/api/checklists/")
        lists = [c for c in _live(lists) if include_archived or not c.get("archived")]
        lists.sort(key=lambda c: (c.get("sort_order") or 0, c.get("name") or ""))
        out = []
        for c in lists:
            entry = {k: c.get(k) for k in ("id", "name", "schedule_kind", "schedule_every",
                                           "schedule_days_of_week", "archived")}
            if include_items:
                items, items_truncated = _list_all("/api/checklists/%s/items" % c["id"])
                truncated = truncated or items_truncated
                entry["items"] = [
                    {k: i.get(k) for k in ("id", "text", "sort_order", "measure_unit",
                                           "measure_target", "measure_direction")}
                    for i in sorted(_live(items), key=lambda i: i.get("sort_order") or 0)
                ]
            out.append(entry)
    except EngineError as err:
        return _failure(err)
    return {"ok": True, "count": len(out), "checklists": out, "note": _truncation_note(truncated)}


@mcp.tool()
def places() -> dict:
    """The household's tagged places (label, latitude, longitude) from the engine. Live rows only."""
    try:
        rows, truncated = _list_all("/api/places/")
    except EngineError as err:
        return _failure(err)
    out = [{k: r.get(k) for k in ("label", "latitude", "longitude", "provenance")}
           for r in sorted(_live(rows), key=lambda r: r.get("label") or "")]
    return {"ok": True, "count": len(out), "places": out, "note": _truncation_note(truncated)}


def _in_range(day: date | None, start: date | None, end: date | None) -> bool:
    if start is None and end is None:
        return True
    if day is None:
        return False
    return (start is None or day >= start) and (end is None or day <= end)


def _bad_date(name: str, value: str) -> dict:
    return {
        "ok": False, "failure": "bad_argument", "read": "nothing",
        "error": "%s=%r is not a YYYY-MM-DD date. No request was made." % (name, value),
    }


@mcp.tool()
def ledger_transactions(from_date: str = "", to_date: str = "", account_last4: str = "",
                        limit: int = 200) -> dict:
    """Ledger transactions from the engine, newest first, filtered by `txn_date` (YYYY-MM-DD,
    inclusive) and optionally by account's last four digits. Amounts are integer cents.

    Every row carries `provenance` (DETERMINISTIC / LLM_RECONCILED / UNRECONCILED / USER) and
    `verified`. **An UNRECONCILED row is provisional and unverified** (CLAUDE.md section 4 rule 7):
    its `verification_note` begins "Unverified", and any total that includes one is unverified
    and must be said so in words. `unverified_count` says how many such rows were returned.
    """
    start = _parse_day(from_date) if from_date else None
    end = _parse_day(to_date) if to_date else None
    if from_date and start is None:
        return _bad_date("from_date", from_date)
    if to_date and end is None:
        return _bad_date("to_date", to_date)
    try:
        rows, truncated = _list_all("/api/ledger/transactions/")
    except EngineError as err:
        return _failure(err)
    picked = [r for r in rows
              if _in_range(_parse_day(r.get("txn_date")), start, end)]
    if account_last4:
        picked = [r for r in picked if str(r.get("account_last4") or "") == account_last4]
    picked.sort(key=lambda r: (r.get("txn_date") or "", r.get("created_at") or ""), reverse=True)
    matched = len(picked)
    picked = picked[: max(1, limit)]
    out = []
    for r in picked:
        unverified = r.get("provenance") == "UNRECONCILED"
        note = r.get("verification_note")
        if unverified and not (note and str(note).startswith("Unverified")):
            note = UNVERIFIED_TXN_NOTE
        out.append({
            "id": r.get("id"),
            "txn_date": r.get("txn_date"),
            "description": r.get("description"),
            "amount_cents": r.get("amount_cents"),
            "currency": r.get("currency"),
            "account_last4": r.get("account_last4"),
            "account_nickname": r.get("account_nickname"),
            "category": r.get("category"),
            "category_pending": r.get("category_pending"),
            "reversal_of": r.get("reversal_of"),
            "provenance": r.get("provenance"),
            "verified": not unverified,
            "verification_note": note if unverified else None,
        })
    unverified_count = sum(1 for r in out if not r["verified"])
    notes = [n for n in (
        _truncation_note(truncated),
        ("%d of these rows are Unverified (UNRECONCILED). Any total that includes them is "
         "unverified and must be said so in words." % unverified_count) if unverified_count else None,
        ("Showing the newest %d of %d matching rows." % (len(out), matched)) if matched > len(out) else None,
    ) if n]
    return {"ok": True, "matched": matched, "returned": len(out), "unverified_count": unverified_count,
            "transactions": out, "note": " ".join(notes) or None}


@mcp.tool()
def pantry_receipts(from_date: str = "", to_date: str = "", limit: int = 50) -> dict:
    """Pantry receipt headers from the engine, newest first, filtered by `purchase_date`
    (YYYY-MM-DD, inclusive). Store, date, total, subtotal, tax and other charges, in integer
    cents, with `provenance` and `verified`. Line items are not read here.

    **An UNRECONCILED receipt is unverified** and carries a note saying so; its
    `unaccounted_cents` is the remainder the arithmetic could not explain and is never part of
    any total.
    """
    start = _parse_day(from_date) if from_date else None
    end = _parse_day(to_date) if to_date else None
    if from_date and start is None:
        return _bad_date("from_date", from_date)
    if to_date and end is None:
        return _bad_date("to_date", to_date)
    try:
        rows, truncated = _list_all("/api/pantry/receipts/")
    except EngineError as err:
        return _failure(err)
    picked = [r for r in rows
              if _in_range(_parse_day(r.get("purchase_date")), start, end)]
    picked.sort(key=lambda r: (r.get("purchase_date") or "", r.get("created_at") or ""), reverse=True)
    matched = len(picked)
    picked = picked[: max(1, limit)]
    out = []
    for r in picked:
        unverified = r.get("provenance") == "UNRECONCILED" or r.get("unaccounted_cents") is not None
        out.append({
            "id": r.get("id"),
            "store": r.get("store"),
            "purchase_date": r.get("purchase_date"),
            "currency": r.get("currency"),
            "total_cents": r.get("total_cents"),
            "subtotal_cents": r.get("subtotal_cents"),
            "tax_cents": r.get("tax_cents"),
            "other_charges_cents": r.get("other_charges_cents"),
            "unaccounted_cents": r.get("unaccounted_cents"),
            "provenance": r.get("provenance"),
            "verified": not unverified,
            "verification_note": UNVERIFIED_RECEIPT_NOTE if unverified else None,
        })
    unverified_count = sum(1 for r in out if not r["verified"])
    notes = [n for n in (
        _truncation_note(truncated),
        ("%d of these receipts are Unverified." % unverified_count) if unverified_count else None,
        ("Showing the newest %d of %d matching receipts." % (len(out), matched)) if matched > len(out) else None,
    ) if n]
    return {"ok": True, "matched": matched, "returned": len(out), "unverified_count": unverified_count,
            "receipts": out, "note": " ".join(notes) or None}


if __name__ == "__main__":
    mcp.run()
