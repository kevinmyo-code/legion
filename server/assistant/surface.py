"""The web assistant's tool surface (web-assistant ticket 04; recruiter-demo ticket 01).

Ticket 04 (2026-10-04): "Everything the engine has" - the `/mcp` tools, the
bought log's four, and tick history (`last_ticked`), which moved to the
engine for this. So the web surface is the WHOLE registry, read from
`engine_mcp.tools.TOOLS` rather than listed again here: one registry, two
doors, and a tool added to the engine reaches both the day it lands.

The same list feeds the locked token's declarations (`session.token_request_for`)
and the tool door's allow-list (`views.AssistantToolView`), so what Gemini is
told it may call and what the engine will run cannot disagree.

`EXCLUDED_FROM_WEB` is where a tool would go that the phone and Claude Code
may call but a browser may not. It is empty: nothing has been ruled out.

## A recruiter gets an allowlist, not the registry (ADR 0068)

A recruiter visitor (recruiter-demo ticket 01) is the opposite posture:
**default deny**. `RECRUITER_TOOLS` names every tool it may call, and a tool
added to the registry later reaches it only when someone adds it here. Both
places the web surface already enforces read from `tools_for(user)` and
`refusal_for(name, user, args)`, so the declarations a recruiter's Live token
carries and what the tool door will run cannot disagree for it either.

The fence is the server's, never the prompt's: the role comes from the
visitor's `HouseholdMember` row, which the browser cannot touch, and the
tenancy (`household.tenancy`) already keeps every read inside the demo
household. Write refusal is a second fence below this one
(`household.permissions.write_refusal`), so a write tool listed here by
mistake would still refuse.
"""

from __future__ import annotations

import dataclasses

from engine_mcp.tools import READABLE, TOOLS, TOOLS_BY_NAME, WRITE_SCOPE_SENTENCE
from household.models import is_recruiter

EXCLUDED_FROM_WEB: frozenset[str] = frozenset()

WEB_TOOL_NAMES: tuple[str, ...] = tuple(t.name for t in TOOLS if t.name not in EXCLUDED_FROM_WEB)

# Read tools over the six aspects (CLAUDE.md section 1: fleet, ledger, pantry,
# notes, dates, places), and only reads. Notes and dates live in `events`
# (`api/events.py`), so `list_events` carries both. The bought log and the
# checklists are household rows of the same kind and are read-only here.
#
# Every registry tool NOT named here is refused for a recruiter. Each one is
# also named in `RECRUITER_EXCLUDED` below with the reason, and
# `tests/test_recruiter.py` requires every registry tool to sit in exactly one
# of the two, so a new tool fails the suite until someone classifies it. The
# runtime check reads `RECRUITER_TOOLS` alone: an unclassified tool is denied.
#
# Nothing in the registry reaches mail, calendar, Drive, Spotify, Plaid, device
# logs or a Claude/code session today; those live on the phone or behind REST
# routes the recruiter fence refuses (`household.permissions.IsHouseholdMember`).
# A tool that reached any of them would be refused here by default.
#
# Not built yet, and NOT added until they are: `about_kevin` (recruiter-demo
# ticket 10) and `find_track` (ticket 11). Each joins this tuple in its own
# ticket, once it exists in the registry.
RECRUITER_TOOLS: tuple[str, ...] = (
    "read_records",
    "list_events",
    "list_checklists",
    "last_ticked",
    "last_bought",
    "list_purchases",
)

_WRITES = "a write; a recruiter reads a fake household and changes none of it"

RECRUITER_EXCLUDED: dict[str, str] = {
    "list_tables": (
        "its answer names every table, body and voice notes included, and says which are "
        "'read and write'; for a recruiter both halves are false. read_records' own table "
        "enum, narrowed below, says what it may read"
    ),
    "write_record": _WRITES,
    "delete_record": _WRITES,
    "add_event": _WRITES,
    "update_event": _WRITES,
    "delete_event": _WRITES,
    "add_checklist_item": _WRITES,
    "tick_checklist_item": _WRITES,
    "log_purchase": _WRITES,
    "delete_purchase": _WRITES,
}

# The aspects whose tables `read_records` may read for a recruiter. `body`
# (health) and `voice_notes` (recordings) are not among the six; `ingest` is
# the document-ingestion ledger, not a domain; `memory` is already closed to
# every MCP caller (`engine_mcp.tools.EXCLUDED_ASPECTS`).
RECRUITER_ASPECTS: frozenset[str] = frozenset({"ledger", "fleet", "pantry", "places"})

RECRUITER_TABLES: frozenset[str] = frozenset(
    table for table, viewset in READABLE.items() if viewset.aspect in RECRUITER_ASPECTS
)


def web_tools():
    return [TOOLS_BY_NAME[name] for name in WEB_TOOL_NAMES]


def _recruiter_tool(tool):
    """The registry tool as a recruiter is told about it: `read_records`' table
    enum narrowed to `RECRUITER_TABLES`, so the model is never offered a table
    the door would refuse."""
    if tool.name != "read_records":
        return tool
    schema = dict(tool.input_schema)
    properties = dict(schema["properties"])
    properties["table"] = {**properties["table"], "enum": sorted(RECRUITER_TABLES)}
    schema["properties"] = properties
    return dataclasses.replace(tool, input_schema=schema)


def recruiter_tools():
    return [_recruiter_tool(TOOLS_BY_NAME[name]) for name in RECRUITER_TOOLS]


def tools_for(user):
    """The tools `user`'s web assistant is declared, in order. A recruiter
    gets `RECRUITER_TOOLS`; everyone else the whole web surface, unchanged."""
    return recruiter_tools() if is_recruiter(user) else web_tools()


def web_description(tool) -> str:
    """The registry's description, less the device-token scope sentence: a
    signed-in member writes as themselves, and telling the model about a
    token it does not hold invites it to say so. The outcome rule stays."""
    return tool.description.replace(WRITE_SCOPE_SENTENCE, "")


def _recruiter_refusal(name: str, args: dict | None) -> str | None:
    if name not in RECRUITER_TOOLS:
        return (
            f"Nothing was read or written. {name!r} is not available on this demo visit, and "
            f"nothing ran. A recruiter's assistant may only read the demo household: "
            f"{', '.join(RECRUITER_TOOLS)}."
        )
    table = (args or {}).get("table")
    if name == "read_records" and isinstance(table, str) and table not in RECRUITER_TABLES:
        return (
            f"Nothing was read. {table!r} is not readable on this demo visit. Tables it may "
            f"read: {', '.join(sorted(RECRUITER_TABLES))}."
        )
    return None


def refusal_for(name: str, user=None, args: dict | None = None) -> str | None:
    """None when `user`'s web assistant may call `name` with `args`;
    otherwise why not, in words. A recruiter is checked against
    `RECRUITER_TOOLS` (default deny); everyone else against the web surface."""
    if is_recruiter(user):
        return _recruiter_refusal(name, args)
    if name in WEB_TOOL_NAMES:
        return None
    if name in TOOLS_BY_NAME:
        return (
            f"Nothing was read or written. {name} is not available to the assistant on the web; "
            f"it can be used from the phone app."
        )
    return (
        f"Nothing was read or written. The web assistant has no tool called {name!r}. If it is "
        f"something only LEGION's phone app does - music, mail, the car's live readings, "
        f"navigation, the garage, calls, alarms - it cannot be done from here. Tools here: "
        f"{', '.join(WEB_TOOL_NAMES)}."
    )
