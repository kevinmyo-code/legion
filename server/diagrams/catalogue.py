"""`show_diagram`: an architecture diagram as a card (arch-diagrams ticket 01, Kevin 2026-11-05).

*"can we add the project architecture diagrams like uml layers, views etc. so a technical
interviewer could ask and the diagrams pop up?"*, then *"same for all projects"* and *"same with
my agentic coding workflows"*.

**The model picks; it never draws.** `project` and `view` are fixed enums (`VIEWS`), and every
picture was rendered ahead of time from a Mermaid source someone read the code to write
(`diagrams/render.py`). The SVG rides in the card, beside the result; the model is told the
title, the caption and the node ids it may highlight, never the SVG.

Where each project's views come from:

- `legion` and `workflow`: `catalogue.json`, rendered from `docs/architecture/views/` in the
  public repo and carried by the image.
- `midcon` and `quanex`: other companies' systems, so never in the repo. `RecruiterDiagram`
  rows, loaded from Kevin's private folder (`manage.py load_recruiter_diagrams`), keyed by the
  visitor's demo code label like `RecruiterContent`, else `current`.

**The LinkedIn version gets no Quanex diagram** (Kevin, 2026-11-05: that version strips all
Quanex work). `WITHHELD` says so here, whatever rows exist, and the loader refuses to load one.
"""

from __future__ import annotations

import json
from functools import cache
from pathlib import Path

from diagrams.models import CURRENT_KEY, RecruiterDiagram

CATALOGUE = Path(__file__).with_name("catalogue.json")

PROJECT_NAMES = {
    "legion": "LEGION",
    "workflow": "Kevin's agentic coding workflow",
    "midcon": "the Mid-Continent Equipment ops dashboard",
    "quanex": "Kevin's Quanex internship work",
}

# Every view there is, per project, with what it shows in a few words (for the model).
VIEWS: dict[str, dict[str, str]] = {
    "legion": {
        "context": "C4 level 1: the people, the clients and every outside service",
        "containers": "C4 level 2: what is deployed where, the scheduled jobs and the buckets",
        "components": "C4 level 3, layered: clients, the API, the engine's domain apps, tenancy, "
        "Postgres",
        "voice_turn": "sequence: one spoken turn, wake word to spoken answer",
        "recruiter_visit": "sequence: a recruiter's visit, invite code to deletion",
        "alfred_builds": "sequence: Alfred builds Alfred, a request to a Claude worker to a "
        "release",
        "ingestion_gate": "activity: the bank feed, statements and receipts through the "
        "reconciliation gate",
        "data_model": "entity relationships: households, members, ledger, budgets, accounts, "
        "events, checklists",
        "claude_task_states": "state machine: a Claude task's statuses",
    },
    "workflow": {
        "loop": "the loop: an idea, grilling, a map of tickets, parallel agents, gates, merge, "
        "release",
        "roles": "who does what: Kevin, the orchestrator, subagents, the Claude worker, hooks",
        "knowledge": "the knowledge layer: rules, state, decisions, the board",
        "ticket_to_phone": "sequence: one real change, Kevin's ask to installed on his phone",
    },
    "midcon": {
        "context": "context: who uses the ops dashboard and what it reads",
        "containers": "deployment: the Cloud Run service, the refresh job, storage, the voice app",
        "po_status_voice": "sequence: a spoken question about a purchase order's status",
    },
    "quanex": {
        "data_flow": "layered data flow: sources, ingestion, the warehouse layers, the semantic "
        "model and the reports",
        "publish_gate": "activity: how one subject is checked and swapped in, or held",
    },
}

PUBLIC_PROJECTS = ("legion", "workflow")
PRIVATE_PROJECTS = ("midcon", "quanex")
ALL_VIEW_IDS: tuple[str, ...] = tuple(sorted({v for views in VIEWS.values() for v in views}))

# A demo code's label -> projects its visitors are never shown, whatever is loaded.
WITHHELD: dict[str, frozenset[str]] = {"linkedin": frozenset({"quanex"})}

HIGHLIGHT_MAX = 12


def view_words(views: dict[str, list[str]] | None = None) -> str:
    """The projects' views in words, for the tool's description: every view, or `views`."""
    views = views if views is not None else {p: list(v) for p, v in VIEWS.items()}
    return " ".join(
        f"{project} ({PROJECT_NAMES[project]}): "
        + "; ".join(f"{view} - {VIEWS[project][view]}" for view in names)
        + "."
        for project, names in views.items()
        if names
    )


@cache
def public_views() -> dict[tuple[str, str], dict]:
    data = json.loads(CATALOGUE.read_text(encoding="utf-8"))
    return {(v["project"], v["view"]): v for v in data["views"]}


def _label_of(user) -> str:
    """The visitor's demo code label, or "" (a member, an owner, the shared resume code)."""
    if user is None or not getattr(user, "pk", None):
        return ""
    from demo.models import DemoVisit

    label = (
        DemoVisit.objects.filter(user=user)
        .exclude(code__label="")
        .values_list("code__label", flat=True)
        .first()
    )
    return label or ""


def version_for(user) -> tuple[str, frozenset[str]]:
    """(key of the private rows this caller reads, projects withheld from it). A label with
    rows of its own reads them; any other reads `current`. Withheld follows the label alone."""
    label = _label_of(user)
    withheld = WITHHELD.get(label, frozenset())
    if label and RecruiterDiagram.objects.filter(key=label).exists():
        return label, withheld
    return CURRENT_KEY, withheld


def find(user, project: str, view: str) -> dict | None:
    if project in PUBLIC_PROJECTS:
        return public_views().get((project, view))
    key, withheld = version_for(user)
    if project in withheld:
        return None
    row = RecruiterDiagram.objects.filter(key=key, project=project, view=view).first()
    return row.as_view() if row is not None else None


def available(user, project: str) -> list[str]:
    """The views of `project` this caller can be shown, in `VIEWS` order."""
    if project in PUBLIC_PROJECTS:
        return [v for v in VIEWS[project] if (project, v) in public_views()]
    key, withheld = version_for(user)
    if project in withheld:
        return []
    loaded = set(
        RecruiterDiagram.objects.filter(key=key, project=project).values_list("view", flat=True)
    )
    return [v for v in VIEWS[project] if v in loaded]


def declared_for(tool, user):
    """`show_diagram` as `user`'s assistant is told about it: only the projects and views this
    caller can be shown. A private project with nothing loaded for its version, or one the
    version withholds (Quanex for LinkedIn), is not named at all, in the enum or the words, so
    the model can never offer it. The door still checks every call (`show`)."""
    import dataclasses

    shown = {project: available(user, project) for project in VIEWS}
    shown = {project: views for project, views in shown.items() if views}
    schema = dict(tool.input_schema)
    properties = dict(schema["properties"])
    properties["project"] = {**properties["project"], "enum": list(shown)}
    properties["view"] = {
        **properties["view"],
        "enum": sorted({v for views in shown.values() for v in views}),
    }
    schema["properties"] = properties
    description = tool.description.replace(view_words(), view_words(shown))
    return dataclasses.replace(tool, input_schema=schema, description=description)


def diagram_card(found: dict, highlight: list[str]) -> dict:
    """The `diagram` card (`engine_mcp/cards.py`). `svg` is inline so the screen can highlight
    its nodes; `caption` is its alt text, in words."""
    return {
        "card": "diagram",
        "title": found["title"],
        "scope": PROJECT_NAMES[found["project"]],
        "project": found["project"],
        "view": found["view"],
        "caption": found["caption"],
        "highlight": highlight,
        "nodes": found["nodes"],
        "svg": found["svg"],
    }


def _nodes_words(nodes: list[dict]) -> str:
    return "; ".join(f"{n['id']} ({n['label']})" for n in nodes)


def show(request, args: dict):
    """The `show_diagram` handler. Returns an `engine_mcp.tools.ToolResult`."""
    from engine_mcp.tools import ok, refused

    project, view = args["project"], args["view"]
    user = getattr(request, "user", None)
    name = PROJECT_NAMES[project]
    if view not in VIEWS[project]:
        views = ", ".join(VIEWS[project])
        return refused(
            f"Nothing was shown. {name} has no {view!r} view; its views are: {views}. Call "
            f"show_diagram again with one of those."
        )
    if project in version_for(user)[1]:
        # Withheld from this version: said without naming the project or its work.
        return refused(
            "Nothing was shown. Those diagrams are not available here. Say so plainly, and do "
            "not describe or draw one from memory."
        )
    found = find(user, project, view)
    if found is None:
        others = available(user, project)
        if not others:
            return refused(
                f"Nothing was shown. Diagrams of {name} are not available here. Say so plainly, "
                f"and do not describe or draw one from memory."
            )
        return refused(
            f"Nothing was shown. The {view} diagram of {name} is not available here. Available: "
            f"{', '.join(others)}."
        )
    known = {n["id"] for n in found["nodes"]}
    asked = list(dict.fromkeys(args.get("highlight") or []))
    shown = [n for n in asked if n in known]
    unknown = [n for n in asked if n not in known]
    parts = [
        f'The diagram "{found["title"]}" is on the screen now. Caption: {found["caption"]}',
        "Walk the visitor through it in your own words, naming each part as you come to it. "
        "Describe only what the diagram and your other tools' results say; never add a part it "
        "does not show.",
    ]
    if shown:
        parts.append(f"Highlighted: {', '.join(shown)}.")
    if unknown:
        parts.append(f"Not in this diagram, so not highlighted: {', '.join(unknown)}.")
    parts.append(
        "To point at parts while you explain, call show_diagram again with the same view and "
        f"highlight set to their ids. Its parts: {_nodes_words(found['nodes'])}."
    )
    structured = {
        "project": project,
        "view": view,
        "title": found["title"],
        "caption": found["caption"],
        "nodes": found["nodes"],
        "highlight": shown,
    }
    return ok(" ".join(parts), structured, card=diagram_card(found, shown))


# =============================================================================
# legion_docs -> show_diagram: the view that draws what a passage describes
# =============================================================================

PATH_VIEWS: dict[str, tuple[str, str]] = {
    "docs/architecture/c1-context.md": ("legion", "context"),
    "docs/architecture/c2-containers.md": ("legion", "containers"),
    "docs/architecture/c3-voice-loop.md": ("legion", "voice_turn"),
    "docs/architecture/c3-ingestion.md": ("legion", "ingestion_gate"),
    "docs/architecture/c3-data.md": ("legion", "data_model"),
    "docs/working-with-agents.md": ("workflow", "loop"),
    "server/README.md": ("legion", "components"),
    "README.md": ("legion", "context"),
}

ADR_VIEWS: dict[str, tuple[str, str]] = {
    "0005": ("legion", "ingestion_gate"),
    "0006": ("legion", "ingestion_gate"),
    "0009": ("legion", "ingestion_gate"),
    "0016": ("legion", "ingestion_gate"),
    "0057": ("legion", "ingestion_gate"),
    "0044": ("legion", "components"),
    "0045": ("legion", "data_model"),
    "0052": ("legion", "data_model"),
    "0064": ("legion", "voice_turn"),
    "0067": ("legion", "claude_task_states"),
    "0068": ("legion", "recruiter_visit"),
    "0069": ("legion", "recruiter_visit"),
    "0072": ("legion", "alfred_builds"),
    "0074": ("legion", "alfred_builds"),
}


def suggested_views(passages) -> list[tuple[str, str]]:
    """The views that draw what these passages describe, best passage first, each once."""
    out: list[tuple[str, str]] = []
    for p in passages:
        adr = getattr(p, "adr", None)
        pick = ADR_VIEWS.get(str(adr["number"]).zfill(4)) if adr else PATH_VIEWS.get(p.path)
        if pick and pick not in out and (pick in public_views()):
            out.append(pick)
    return out[:2]


def suggestion_words(passages) -> str:
    views = suggested_views(passages)
    if not views:
        return ""
    calls = " or ".join(f'show_diagram(project="{p}", view="{v}")' for p, v in views)
    return (
        f" A diagram draws this: {calls}. Show it while you explain, and highlight the parts you "
        f"name."
    )
