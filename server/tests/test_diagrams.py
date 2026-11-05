"""`show_diagram` and the diagram catalogue (arch-diagrams ticket 01, Kevin 2026-11-05).

- The committed SVGs and `diagrams/catalogue.json` are what `tools/render_diagrams.py` makes
  from `docs/architecture/views/*.mmd` today: a source edited without a re-render fails here.
- The catalogue holds exactly the public views `VIEWS` names, each with a caption, safe SVG
  and node ids the SVG really carries.
- The tool's `project` and `view` are fixed enums; a member and a recruiter both have it.
- The card's shape, highlight filtering, and refusals in words.
- The private projects load from a folder outside the repo, keyed by the code's label, and
  **a LinkedIn visitor is never shown Quanex**, whether or not a row exists.
- legion_docs answers about architecture name the view that draws it.
"""

from __future__ import annotations

import json
import re
from io import StringIO

import pytest
from django.core.management import CommandError, call_command
from rest_framework.test import APIClient

from assistant.surface import RECRUITER_TOOLS, WEB_TOOL_NAMES, tools_for
from demo.models import DemoCode
from diagrams import catalogue, render
from diagrams.catalogue import ALL_VIEW_IDS, PUBLIC_PROJECTS, VIEWS
from diagrams.models import RecruiterDiagram
from engine_mcp.tools import TOOLS_BY_NAME
from household.models import Household
from tests.test_assistant import _tool, browser

# =============================================================================
# The catalogue: current, complete, safe
# =============================================================================


@pytest.mark.skipif(
    not render.PUBLIC_DIR.is_dir(),
    reason="the repo's docs are not here (the engine image carries server/ only)",
)
def test_the_committed_svgs_and_catalogue_match_the_sources():
    assert render.stale_public() == []


def test_the_catalogue_holds_exactly_the_public_views():
    held = set(catalogue.public_views())
    named = {(p, v) for p in PUBLIC_PROJECTS for v in VIEWS[p]}
    assert held == named


@pytest.mark.parametrize("key", sorted({(p, v) for p in PUBLIC_PROJECTS for v in VIEWS[p]}))
def test_each_public_view_is_captioned_safe_and_its_nodes_are_in_the_svg(key):
    found = catalogue.public_views()[key]
    assert found["title"] and len(found["caption"]) > 40
    assert "—" not in found["title"] + found["caption"]  # no em dashes (CLAUDE.md 9)
    render.check_svg(found["svg"], f"{key}")
    stamped = set(re.findall(r'data-node="([^"]+)"', found["svg"]))
    ids = [n["id"] for n in found["nodes"]]
    assert ids and len(ids) == len(set(ids))
    assert set(ids) <= stamped
    # The caption is the SVG's own <desc>: the picture's words travel with it.
    assert "<desc" in found["svg"] and "<title" in found["svg"]


def test_the_svgs_draw_on_the_one_neutral_ground():
    r, g, b = (int(render.BACKGROUND[i : i + 2], 16) for i in (1, 3, 5))
    for found in catalogue.public_views().values():
        assert f"background-color: rgb({r}, {g}, {b})" in found["svg"].split(">", 1)[0]


# =============================================================================
# The tool: fixed enums, on both surfaces
# =============================================================================


def test_the_model_can_only_pick_from_fixed_enums():
    schema = TOOLS_BY_NAME["show_diagram"].input_schema
    assert schema["properties"]["project"]["enum"] == list(VIEWS)
    assert set(schema["properties"]["view"]["enum"]) == set(ALL_VIEW_IDS)
    assert schema["additionalProperties"] is False
    assert TOOLS_BY_NAME["show_diagram"].writes is False


def test_members_and_recruiters_both_have_it():
    assert "show_diagram" in WEB_TOOL_NAMES
    assert "show_diagram" in RECRUITER_TOOLS


def test_a_member_is_declared_it(household_user):
    assert "show_diagram" in [t.name for t in tools_for(household_user)]


@pytest.fixture
def kevin_web(household_user):
    return browser(household_user)


def test_the_card_carries_the_svg_and_only_real_highlights(kevin_web):
    response = _tool(
        kevin_web,
        "show_diagram",
        {"project": "legion", "view": "components", "highlight": ["tooldoor", "nope", "household"]},
    )
    assert response.status_code == 200, response.data
    data = response.data
    assert data["is_error"] is False
    card = data["card"]
    assert card["card"] == "diagram"
    assert card["project"] == "legion" and card["view"] == "components"
    assert card["title"] == catalogue.public_views()[("legion", "components")]["title"]
    assert card["caption"] and card["scope"] == "LEGION"
    assert card["highlight"] == ["tooldoor", "household"]
    assert card["svg"].startswith("<svg")
    # The model is told the parts and the caption, never the SVG.
    assert "<svg" not in data["text"] and "<svg" not in json.dumps(data["response"])
    assert "tooldoor (" in data["text"]
    assert "Not in this diagram, so not highlighted: nope." in data["text"]


def test_a_view_the_project_does_not_have_is_refused_in_words(kevin_web):
    data = _tool(kevin_web, "show_diagram", {"project": "workflow", "view": "voice_turn"}).data
    assert data["is_error"] is True
    assert data["text"].startswith("Nothing was shown.")
    assert "loop" in data["text"] and "card" not in data


def test_an_invented_view_never_reaches_the_handler(kevin_web):
    data = _tool(kevin_web, "show_diagram", {"project": "legion", "view": "my_drawing"}).data
    assert data["is_error"] is True and "do not fit show_diagram" in data["text"]


def test_a_private_project_with_nothing_loaded_says_so(kevin_web):
    data = _tool(kevin_web, "show_diagram", {"project": "midcon", "view": "context"}).data
    assert data["is_error"] is True
    assert "not available here" in data["text"] and "card" not in data


# =============================================================================
# Private projects: loaded from a folder outside the repo, per content version
# =============================================================================


SAFE_SVG = (
    '<svg id="dg-x" xmlns="http://www.w3.org/2000/svg" style="background-color: #f7f7f5;">'
    "<title>t</title><desc>d</desc>"
    '<g class="node default" id="dg-x-flowchart-{node}-0" data-node="{node}"><text>{label}</text>'
    "</g></svg>"
)


def _private_view(folder, project, view, node, label, title):
    project_dir = folder / project
    project_dir.mkdir(parents=True, exist_ok=True)
    mmd = project_dir / f"{view}.mmd"
    mmd.write_text(
        f"flowchart TB\n    accTitle: {title}\n    accDescr: A made-up {project} picture for a "
        f'test, long enough to be a caption.\n    {node}["{label}"]\n',
        encoding="utf-8",
    )
    svg = SAFE_SVG.format(node=node, label=label)
    (project_dir / f"{view}.svg").write_text(svg, encoding="utf-8")
    built = render.build_view(project, view, mmd, svg)
    entry = {k: v for k, v in built.items() if k != "svg"} | {"svg_file": f"{view}.svg"}
    manifest = project_dir / render.MANIFEST
    views = json.loads(manifest.read_text())["views"] if manifest.exists() else []
    manifest.write_text(json.dumps({"project": project, "views": [*views, entry]}))


@pytest.fixture
def private_folder(tmp_path):
    folder = tmp_path / "diagrams"
    _private_view(folder, "midcon", "context", "service", "Ops service", "Ops context")
    _private_view(folder, "quanex", "data_flow", "gold", "Gold layer", "Warehouse layers")
    return folder


def _load(folder, *args) -> str:
    out = StringIO()
    call_command("load_recruiter_diagrams", str(folder), *args, stdout=out)
    return out.getvalue()


def _enter(code) -> APIClient:
    client = APIClient()
    assert client.post("/api/demo/enter", {"code": code}, format="json").status_code == 201
    return client


def test_the_loader_stores_each_project_for_the_current_version(db, private_folder):
    said = _load(private_folder)
    assert "midcon: loaded 1 view(s) for current: context." in said
    assert "quanex: loaded 1 view(s) for current: data_flow." in said
    row = RecruiterDiagram.objects.get(key="current", project="quanex", view="data_flow")
    assert row.nodes == [{"id": "gold", "label": "Gold layer"}]
    # A reload replaces in place.
    _load(private_folder)
    assert RecruiterDiagram.objects.count() == 2


def test_the_loader_never_loads_quanex_for_linkedin(db, private_folder):
    said = _load(private_folder, "--for-code", "linkedin")
    assert "quanex: never loaded for the linkedin version (withheld); skipped." in said
    assert not RecruiterDiagram.objects.filter(key="linkedin", project="quanex").exists()
    # Kevin 2026-11-05: "leave midcon out of the linkedin one".
    assert not RecruiterDiagram.objects.filter(key="linkedin", project="midcon").exists()


def test_the_loader_refuses_a_stale_or_unknown_view(db, private_folder):
    mmd = private_folder / "midcon" / "context.mmd"
    mmd.write_text(mmd.read_text() + '    extra["Edited after render"]\n')
    with pytest.raises(CommandError, match="changed since it was rendered"):
        _load(private_folder, "--project", "midcon")
    assert not RecruiterDiagram.objects.exists()
    _private_view(private_folder, "quanex", "revenue", "x", "X", "Not a view")
    with pytest.raises(CommandError, match="not a quanex view"):
        _load(private_folder, "--project", "quanex")


def test_the_loader_refuses_an_svg_that_could_run_script(db, private_folder):
    svg = private_folder / "midcon" / "context.svg"
    svg.write_text(svg.read_text().replace("<title>", "<script>alert(1)</script><title>"))
    with pytest.raises(CommandError, match="script"):
        _load(private_folder, "--project", "midcon")


def test_the_resume_visitor_sees_quanex_and_the_linkedin_visitor_never_does(db, private_folder):
    Household.objects.create(name="Demo")
    _r, resume_code = DemoCode.rotate()
    _l, linkedin_code = DemoCode.rotate(label="linkedin", daily_cap_cents=200)
    _load(private_folder)
    _load(private_folder, "--for-code", "linkedin")

    resume = _enter(resume_code)
    data = _tool(resume, "show_diagram", {"project": "quanex", "view": "data_flow"}).data
    assert data["is_error"] is False and data["card"]["title"] == "Warehouse layers"

    linkedin = _enter(linkedin_code)
    data = _tool(linkedin, "show_diagram", {"project": "quanex", "view": "data_flow"}).data
    assert data["is_error"] is True and "card" not in data
    assert "not available here" in data["text"]
    assert "Gold" not in data["text"] and "quanex" not in data["text"].lower()
    # Midcon is withheld from LinkedIn too (Kevin, 2026-11-05).
    data = _tool(linkedin, "show_diagram", {"project": "midcon", "view": "context"}).data
    assert data["is_error"] is True and "card" not in data


def test_linkedin_never_falls_back_to_the_full_versions_quanex(db, private_folder):
    """No LinkedIn rows at all: the visitor falls back to `current`, and Quanex is
    still withheld there."""
    Household.objects.create(name="Demo")
    _l, linkedin_code = DemoCode.rotate(label="linkedin")
    _load(private_folder)
    linkedin = _enter(linkedin_code)
    data = _tool(linkedin, "show_diagram", {"project": "quanex", "view": "data_flow"}).data
    assert data["is_error"] is True and "card" not in data
    data = _tool(linkedin, "show_diagram", {"project": "midcon", "view": "context"}).data
    assert data["is_error"] is True and "card" not in data


# =============================================================================
# legion_docs points at the diagram
# =============================================================================


def test_legion_docs_suggests_the_matching_view(kevin_web):
    data = _tool(kevin_web, "legion_docs", {"query": "how does the reconciliation gate work"}).data
    assert data["is_error"] is False
    assert 'show_diagram(project="legion", view="ingestion_gate")' in data["text"]


def test_every_suggested_view_exists():
    for project, view in [*catalogue.PATH_VIEWS.values(), *catalogue.ADR_VIEWS.values()]:
        assert view in VIEWS[project]


def _declared(user):
    return next(t for t in tools_for(user) if t.name == "show_diagram")


def test_the_linkedin_visitor_is_never_told_quanex_exists(db, private_folder):
    """The declaration a visitor's Live token carries names only what it can be shown."""
    from demo.models import DemoVisit

    Household.objects.create(name="Demo")
    _r, resume_code = DemoCode.rotate()
    _l, linkedin_code = DemoCode.rotate(label="linkedin")
    _load(private_folder)
    _enter(resume_code)
    _enter(linkedin_code)
    full = _declared(DemoVisit.objects.get(code__label="").user)
    assert "quanex" in full.input_schema["properties"]["project"]["enum"]
    assert "Quanex" in full.description and "data_flow" in full.description
    stripped = _declared(DemoVisit.objects.get(code__label="linkedin").user)
    text = json.dumps(stripped.input_schema) + stripped.description
    assert "quanex" not in text.lower()
    assert "midcon" not in text.lower() and "mid-continent" not in text.lower()
    assert stripped.input_schema["properties"]["project"]["enum"] == ["legion", "workflow"]


def test_nothing_loaded_means_no_private_project_is_declared(household_user):
    tool = _declared(household_user)
    assert tool.input_schema["properties"]["project"]["enum"] == ["legion", "workflow"]
    assert "midcon" not in tool.description.lower() and "quanex" not in tool.description.lower()
