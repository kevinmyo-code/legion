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

## A recruiter gets an allowlist, not the registry (ADR 0068, ADR 0069)

A recruiter visitor (recruiter-demo ticket 01) is the opposite posture:
**default deny**. `RECRUITER_TOOLS` names every tool it may call, and a tool
added to the registry later reaches it only when someone adds it here. Both
places the web surface already enforces read from `tools_for(user)` and
`refusal_for(name, user, args)`, so the declarations a recruiter's Live token
carries and what the tool door will run cannot disagree for it either.

**Since ticket 13 (ADR 0069) the list is the member web surface minus what
reaches outside the household's rows or into the body aspect**, writes
included: each visit has its own throwaway household (`is_demo_visit`) with
the visitor as its only member, so a write lands where nobody else can see
it. The tables the record tools may touch are narrowed to `RECRUITER_TABLES`
(no body, no ingestion ledger, no memory), in the declared enum and at the
door.

The fence is the server's, never the prompt's: the role comes from the
visitor's `HouseholdMember` row, which the browser cannot touch, and the
tenancy (`household.tenancy`) keeps every read and write inside the visit's
household. `household.permissions.write_refusal` is a second fence below this
one: a recruiter may write only inside a demo-visit household, and the
visitor's read-scoped token cannot write at all.
"""

from __future__ import annotations

import dataclasses

from demo.tools import ABOUT_KEVIN, SHOW_RESUME
from engine_mcp.tools import READABLE, TOOLS, TOOLS_BY_NAME, WRITE_SCOPE_SENTENCE
from household.models import is_recruiter
from household.recruiter_routes import RECRUITER_DENIED

EXCLUDED_FROM_WEB: frozenset[str] = frozenset()

WEB_TOOL_NAMES: tuple[str, ...] = tuple(t.name for t in TOOLS if t.name not in EXCLUDED_FROM_WEB)

# The member web surface less what a recruiter's visit may not reach (ADR
# 0069): every read and write over the household's own rows, in registry
# order, plus `about_kevin` and `show_resume`.
#
# Every registry tool NOT named here is refused for a recruiter. Each one is
# also named in `RECRUITER_EXCLUDED` below with the reason, and
# `tests/test_recruiter.py` requires every registry tool to sit in exactly one
# of the two, so a new tool fails the suite until someone classifies it. The
# runtime check reads `RECRUITER_TOOLS` alone: an unclassified tool is denied.
#
# Nothing in the registry reaches mail, calendar sync, Drive, Spotify, Plaid,
# device logs or a Claude/code session today; those live on the phone or
# behind REST routes the recruiter fence refuses
# (`household.recruiter_routes`). A tool that reached any of them would be
# refused here by default.
#
# `about_kevin` (ticket 10) and `show_resume` (demo-modals) are the tools here
# NOT in the registry: they are for strangers reading about Kevin, so `/mcp` and
# members' web never list them (`RECRUITER_ONLY_TOOLS`, `demo/tools.py`). `find_track` (ticket 11)
# is not built and joins this tuple in its own ticket.
RECRUITER_TOOLS: tuple[str, ...] = (
    "read_records",
    "list_events",
    "list_checklists",
    "last_ticked",
    "write_record",
    "delete_record",
    "add_event",
    "update_event",
    "delete_event",
    "add_checklist_item",
    "tick_checklist_item",
    "log_purchase",
    "last_bought",
    "list_purchases",
    "delete_purchase",
    # Parity quick wins (2026-11-02): reads and writes over the visit's own
    # rows. `vehicle_recalls` reaches NHTSA's public, keyless recall list with
    # the visit car's year, make and model only: no account, nothing of the
    # household's beyond those three words, nothing stored.
    "monthly_spend",
    # Household finance (ADR 0073): reads over the visit's own copy, which
    # holds no finance rows, so each answers in words that nothing is on file.
    "safe_to_spend",
    "net_worth",
    "cash_flow",
    "runway",
    "budgets",
    "next_service",
    "account_balances",
    "grocery_spend",
    "vehicle_recalls",
    "create_checklist",
    "untick_checklist_item",
    "remove_checklist_item",
    "archive_checklist",
    "rename_place",
    "skip_event_occurrence",
    # How LEGION is built (Kevin, 2026-11-03: "yes legion docs tool so they
    # can ask technical questions"). Reads only the committed corpus of the
    # public repo's docs (`assistant/docs_corpus.json`): no household row, no
    # private file.
    "legion_docs",
    # The architecture diagrams (arch-diagrams ticket 01, Kevin 2026-11-05: "so
    # a technical interviewer could ask and the diagrams pop up"). Pre-rendered
    # pictures picked from a fixed list (`diagrams/`): no household row. The
    # private projects' views follow the visit code's content version, and the
    # LinkedIn version is never shown Quanex (`diagrams.catalogue.WITHHELD`).
    "show_diagram",
    # The web, through Gemini with Google Search grounding (audit 2026-11-03:
    # "What is the H1B1?" had no answer). Sends the visitor's question and
    # nothing of the household; each call is charged against the demo's daily
    # budget before Google is asked (`demo.budget.charge_web_search`).
    "web_search",
    # Procedures (ADR 0070, ruling 07): only procedures whose EVERY tool step
    # is in this tuple, checked when the declaration is built
    # (`procedures.listing.tool_for`) and again before the run starts; each
    # step still goes through `refusal_for` below as it runs
    # (`procedures/runner.py`), so a procedure never reaches past this list.
    "run_procedure",
    "about_kevin",
    # Kevin's one-page resume over the page (demo-modals, Kevin 2026-11-05). Recruiter-only like
    # `about_kevin`: the files are the visitor's own content version's (`demo/tools.py`).
    "show_resume",
)

# Tools a recruiter's assistant may call that no other door has. The tool
# door runs them through `engine_mcp.tools.run_engine_tool` (`tool_for`).
RECRUITER_ONLY_TOOLS = {ABOUT_KEVIN.name: ABOUT_KEVIN, SHOW_RESUME.name: SHOW_RESUME}

RECRUITER_EXCLUDED: dict[str, str] = {
    # Kevin, 2026-11-12: Canvas grades are one member's own coursework. The visit's REST fence's
    # own reason; `grades.service.refusal` refuses a recruiter a second time.
    "canvas_grades": RECRUITER_DENIED["grades.views.GradeListView"],
    "what_i_need": RECRUITER_DENIED["grades.views.GradeListView"],
    # alfred-greets ticket 01 (ADR 0091): one member's own day, coursework and budgets. The
    # visit's REST fence's own reason; `greeting.service.refusal` refuses a recruiter again.
    "greeting_facts": RECRUITER_DENIED["greeting.views.GreetingFactsView"],
    # Round 5 (2026-11-10): public agency feeds at a point the owner's saved places or GPS name;
    # a visit's demo places are not a reason to spend the household's NWS/USGS/FIRMS calls.
    "check_conditions": (
        "it reaches public agency feeds at the household's own places and keys, outside the "
        "demo's rows"
    ),
    # Round 5 (2026-11-10): Kevin's own GitHub repos through his token. Nothing of the demo's;
    # a visitor never reads his builds, issues or commits through the household's credential.
    "github_status": (
        "it reads the owner's GitHub repos through the owner's own token, which is not part of "
        "a demo visit"
    ),
    "list_tables": (
        "its answer names every table, body and memory included, as readable and writable; "
        "for a recruiter that is false. The record tools' own table enums, narrowed below, "
        "say what it may touch"
    ),
    # The visit's REST fence's own reason for `/api/brief/latest`, so the tool
    # door and the route cannot disagree (procedures ticket 15).
    "brief_latest": RECRUITER_DENIED["brief.views.BriefLatestView"],
    # Memory v2 (ADR 0075): the visit's REST fence's own reason, so the tool door and the route
    # cannot disagree. `member_memory.service.refusal` refuses a recruiter a second time.
    "recall_memory": RECRUITER_DENIED["member_memory.views.MemoryRecallView"],
    "list_memories": RECRUITER_DENIED["member_memory.views.MemoryListCreateView"],
    "remember": RECRUITER_DENIED["member_memory.views.MemoryListCreateView"],
    "forget_memory": RECRUITER_DENIED["member_memory.views.MemoryDetailView"],
    # The body coach (fat-loss ticket 08): the body aspect is not part of the demo, the visit's
    # REST fence's own reason, and its routes refuse a recruiter a second time.
    **{
        name: RECRUITER_DENIED["body_coach.views.EnergyView"]
        for name in (
            "get_energy_estimate",
            "get_weight_trend",
            "list_saved_meals",
            "set_body_profile",
            "log_waist",
            "save_meal",
            "delete_saved_meal",
            "log_saved_meal",
            "accept_energy_proposal",
            "dismiss_energy_proposal",
            "get_todays_workout",
            "start_workout",
            "tick_workout_set",
            "swap_exercise",
            "finish_workout",
            # body coach 2 (2026-11-06)
            "get_exercise_alternatives",
            "propose_programme_change",
            "accept_programme_change",
            "dismiss_programme_change",
            "suggest_meal",
            "log_meal_option",
            "get_body_plan",
            "draft_body_plan",
            "lock_body_plan",
            "discard_body_plan",
        )
    },
}

# The body coach's engine tools, named once for the tests and for anyone reading the fence.
BODY_COACH_TOOLS: frozenset[str] = frozenset(
    name
    for name, reason in RECRUITER_EXCLUDED.items()
    if reason == RECRUITER_DENIED["body_coach.views.EnergyView"]
)

# The aspects whose tables the record tools may read, write or delete for a
# recruiter: ledger, fleet, pantry, places, and voice notes (Kevin's ruling 4,
# ticket 13). `body` stays out (ruling 3); `ingest` is the document-ingestion
# ledger, not a domain, and would show the seed's quarantined receipt (map
# ruling 11); `memory` is already closed to every MCP caller
# (`engine_mcp.tools.EXCLUDED_ASPECTS`).
RECRUITER_ASPECTS: frozenset[str] = frozenset(
    {"ledger", "fleet", "pantry", "places", "voice_notes"}
)

RECRUITER_TABLES: frozenset[str] = frozenset(
    table for table, viewset in READABLE.items() if viewset.aspect in RECRUITER_ASPECTS
)

# The record tools whose `table` argument is narrowed to `RECRUITER_TABLES`.
RECRUITER_TABLE_TOOLS: frozenset[str] = frozenset({"read_records", "write_record", "delete_record"})


def web_tools():
    return [TOOLS_BY_NAME[name] for name in WEB_TOOL_NAMES]


def _recruiter_tool(tool):
    """The registry tool as a recruiter is told about it: the record tools'
    table enums narrowed to `RECRUITER_TABLES`, so the model is never offered
    a table the door would refuse."""
    if tool.name not in RECRUITER_TABLE_TOOLS:
        return tool
    schema = dict(tool.input_schema)
    properties = dict(schema["properties"])
    allowed = sorted(set(properties["table"]["enum"]) & RECRUITER_TABLES)
    properties["table"] = {**properties["table"], "enum": allowed}
    schema["properties"] = properties
    return dataclasses.replace(tool, input_schema=schema)


def _any_tool(name: str):
    return RECRUITER_ONLY_TOOLS.get(name) or TOOLS_BY_NAME[name]


def recruiter_tools():
    return [_recruiter_tool(_any_tool(name)) for name in RECRUITER_TOOLS]


def recruiter_only_tool(name: str, user):
    """The recruiter-only tool `name`, when `user` is a recruiter and it is
    one; else None, and the door runs the registry's tool by name. Checked
    after `refusal_for`, so a member never reaches here with one."""
    if is_recruiter(user):
        return RECRUITER_ONLY_TOOLS.get(name)
    return None


def tools_for(user):
    """The tools `user`'s web assistant is declared, in order. A recruiter
    gets `RECRUITER_TOOLS`; everyone else the whole web surface, unchanged,
    but for `run_procedure`, whose `name` enum lists what THIS user may run
    (`procedures.listing.tool_for`), and `show_diagram`, whose projects and
    views are the ones THIS user can be shown (`diagrams.catalogue.declared_for`)."""
    from diagrams.catalogue import declared_for
    from procedures.listing import tool_for

    tools = recruiter_tools() if is_recruiter(user) else web_tools()
    out = []
    for tool in tools:
        if tool.name == "run_procedure":
            tool = tool_for(tool, user, user.household)
        elif tool.name == "show_diagram":
            # Only the diagrams this caller can be shown: never a withheld project (arch-diagrams
            # ticket 01: no Quanex for the LinkedIn version), never one with nothing loaded.
            tool = declared_for(tool, user)
        out.append(tool)
    return out


def web_description(tool) -> str:
    """The registry's description, less the device-token scope sentence: a
    signed-in member writes as themselves, and telling the model about a
    token it does not hold invites it to say so. The outcome rule stays."""
    return tool.description.replace(WRITE_SCOPE_SENTENCE, "")


def _recruiter_refusal(name: str, args: dict | None) -> str | None:
    if name not in RECRUITER_TOOLS:
        return (
            f"Nothing was read or written. {name!r} is not available on this demo visit, and "
            f"nothing ran. A recruiter's assistant works on its own copy of the demo "
            f"household with: {', '.join(RECRUITER_TOOLS)}."
        )
    table = (args or {}).get("table")
    if name in RECRUITER_TABLE_TOOLS and isinstance(table, str) and table not in RECRUITER_TABLES:
        verb = "read" if name == "read_records" else "written or deleted"
        return (
            f"Nothing was {verb}. {table!r} is not part of this demo visit. Tables it may "
            f"use: {', '.join(sorted(RECRUITER_TABLES))}."
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
