"""demo-modals (Kevin's /try test, 2026-11-05): diagrams first, and the resume over the page.

Kevin's words: *"some questions didnt show diagrams at all"* and *"asking for the resume should
pop up a modal thats scrollable showing my 1 page resume"*.

- **Diagrams first.** The recruiter prompt says every how-it-works question gets a show_diagram
  call before the words, ahead of the about_kevin and legion_docs rules; the tool's description
  maps the ways people ask to the views, filtered with the views so a version is never told of a
  project it withholds; legion_docs suggests the view from the question's own words.
- **show_resume.** A recruiter-only tool (like about_kevin) whose card carries links to the
  visitor's own version's resume page image and PDF, never bytes; a version with no resume (the
  LinkedIn one) is refused in words and draws nothing.
- **A later conversation in the same visit is not greeted again** (the page sends
  RECRUITER_CONTINUE instead of RECRUITER_OPENER; the prompt says what it means).

Every string here is fake. The real resume and its image live in Kevin's private folder.
"""

from __future__ import annotations

import json
from datetime import UTC, datetime
from io import StringIO

import pytest
from django.core.cache import cache
from django.core.management import CommandError, call_command
from rest_framework.test import APIClient

from assistant.prompt import (
    DIAGRAM_RULE,
    RECRUITER_CONTINUE,
    RECRUITER_CONTINUE_RULE,
    RECRUITER_FRAME,
    RECRUITER_OPENER,
    RESUME_RULE,
    build_system_prompt,
)
from assistant.surface import RECRUITER_ONLY_TOOLS, RECRUITER_TOOLS, WEB_TOOL_NAMES, tools_for
from demo.content import PNG_MAGIC
from demo.models import DemoCode, RecruiterContent
from demo.tools import NO_RESUME
from diagrams import catalogue
from diagrams.catalogue import ASKS, VIEWS
from engine_mcp.server import SCREEN_ONLY_TOOLS
from engine_mcp.tools import TOOLS_BY_NAME
from household.models import Household
from tests.test_assistant import _tool, browser
from tests.test_diagrams import _load, _private_view
from tests.test_recruiter_content import FAKE_PDF, fake_content, write_folder

pytestmark = pytest.mark.django_db

FAKE_PNG = PNG_MAGIC + b"\x00\x00\x00\rIHDR fake resume page for tests"


@pytest.fixture(autouse=True)
def _fresh_throttle():
    cache.clear()
    yield
    cache.clear()


def _folder(path, *, image=True, resume=True, content=None):
    folder = write_folder(path, content, resume=resume)
    if image:
        (folder / "resume.png").write_bytes(FAKE_PNG)
    return folder


def _load_content(folder, *args) -> str:
    out = StringIO()
    call_command("load_recruiter_content", str(folder), *args, stdout=out)
    return out.getvalue()


def _enter(code) -> APIClient:
    client = APIClient()
    assert client.post("/api/demo/enter", {"code": code}, format="json").status_code == 201
    return client


@pytest.fixture
def codes(db):
    Household.objects.create(name="Demo")
    _r, resume_code = DemoCode.rotate()
    _l, linkedin_code = DemoCode.rotate(label="linkedin", daily_cap_cents=200)
    return resume_code, linkedin_code


# =============================================================================
# 1. Diagrams first: the prompt rule
# =============================================================================


def _recruiter_prompt() -> str:
    from assistant.companions import recruiter_companion

    return build_system_prompt(
        companion=recruiter_companion(),
        first_name="",
        now=datetime(2026, 11, 5, 18, 0, tzinfo=UTC),
        utc_offset_minutes=-360,
        recruiter=True,
    )


def test_the_recruiter_prompt_puts_the_diagram_first():
    text = _recruiter_prompt()
    assert DIAGRAM_RULE.strip() in text
    for kind in ("architecture", "design", "data model", "deployment", "what runs where", "flow"):
        assert kind in DIAGRAM_RULE, kind
    assert "what Kevin did on a project or job" in DIAGRAM_RULE
    assert "how he builds with AI agents" in DIAGRAM_RULE
    assert "show_diagram call FIRST" in DIAGRAM_RULE
    assert "closest view" in DIAGRAM_RULE
    assert "goes deeper" in DIAGRAM_RULE
    # Read before the rules that send the model to about_kevin and legion_docs.
    rule_at = RECRUITER_FRAME.index(DIAGRAM_RULE)
    assert rule_at < RECRUITER_FRAME.index("use about_kevin")
    assert rule_at < RECRUITER_FRAME.index("use legion_docs")


def test_the_rule_names_no_project_so_no_version_is_told_of_one_it_withholds():
    lowered = DIAGRAM_RULE.lower().replace("legion_docs", "")
    for name in ("quanex", "midcon", "mid-continent", "legion"):
        assert name not in lowered, name


def test_a_member_prompt_carries_none_of_the_demo_rules(household_user):
    from assistant.companions import companion_for

    text = build_system_prompt(
        companion=companion_for(household_user),
        first_name="Kevin",
        now=datetime(2026, 11, 5, 18, 0, tzinfo=UTC),
        utc_offset_minutes=-360,
    )
    for rule in (DIAGRAM_RULE, RESUME_RULE, RECRUITER_CONTINUE_RULE):
        assert rule.strip() not in text


# =============================================================================
# 1. Diagrams first: the tool's description maps phrasings to views
# =============================================================================

# Kevin's test lines (2026-11-05) -> the view each should draw.
KEVINS_LINES = {
    "Walk me through LEGION's architecture": ("legion", "components"),
    "Go one level deeper, what runs where": ("legion", "containers"),
    "Show me what happens when someone talks to it": ("legion", "voice_turn"),
    "How is the data modeled": ("legion", "data_model"),
    "How does Kevin build with AI agents": ("workflow", "loop"),
    "Show me one change going from his request to his phone": ("workflow", "ticket_to_phone"),
    "How does Alfred build Alfred": ("legion", "alfred_builds"),
    "Tell me about the Midcon dashboard": ("midcon", "context"),
    "Show me its architecture": ("midcon", "containers"),
    "What did he do at Quanex": ("quanex", "data_flow"),
}

# The phrase in ASKS each of Kevin's lines is matched by (the words the model reads).
KEVINS_PHRASES = {
    "Walk me through LEGION's architecture": "the architecture",
    "Go one level deeper, what runs where": "what runs where",
    "Show me what happens when someone talks to it": "what happens when someone talks to it",
    "How is the data modeled": "how the data is modeled",
    "How does Kevin build with AI agents": "how Kevin builds with AI agents",
    "Show me one change going from his request to his phone": (
        "one change from his request to his phone"
    ),
    "How does Alfred build Alfred": "how Alfred builds Alfred",
    "Tell me about the Midcon dashboard": "the Midcon dashboard",
    "Show me its architecture": "its architecture",
    "What did he do at Quanex": "what he did at Quanex",
}


def test_every_ask_names_a_real_view():
    for project, views in ASKS.items():
        for view, asks in views.items():
            assert view in VIEWS[project], (project, view)
            assert asks and all(a.strip() for a in asks), (project, view)


@pytest.mark.parametrize("line", list(KEVINS_LINES))
def test_each_of_kevins_lines_is_mapped_to_its_view(line):
    project, view = KEVINS_LINES[line]
    assert KEVINS_PHRASES[line] in ASKS[project][view]
    # ...and the words land after that view in the description the model reads.
    words = catalogue.view_words({project: [view]})
    assert f'"{KEVINS_PHRASES[line]}"' in words
    assert words.startswith(f"{project} (")


def test_the_registry_description_says_first_and_carries_the_asks():
    description = TOOLS_BY_NAME["show_diagram"].description
    assert "Call it FIRST, before any other tool" in description
    assert "what runs where" in description
    assert "going a level deeper" in description
    assert catalogue.view_words() in description


@pytest.fixture
def private_folder(tmp_path):
    folder = tmp_path / "diagrams"
    _private_view(folder, "midcon", "context", "service", "Ops service", "Ops context")
    _private_view(folder, "quanex", "data_flow", "gold", "Gold layer", "Warehouse layers")
    return folder


def _declared(user):
    return next(t for t in tools_for(user) if t.name == "show_diagram")


def test_the_full_version_is_told_the_private_asks(codes, private_folder):
    from demo.models import DemoVisit

    resume_code, _linkedin = codes
    _load(private_folder)
    _enter(resume_code)
    tool = _declared(DemoVisit.objects.get(code__label="").user)
    assert '"what he did at Quanex"' in tool.description
    assert '"the Midcon dashboard"' in tool.description


def test_the_linkedin_version_is_never_told_a_withheld_ask(codes, private_folder):
    from demo.models import DemoVisit

    _resume, linkedin_code = codes
    _load(private_folder)
    _enter(linkedin_code)
    tool = _declared(DemoVisit.objects.get(code__label="linkedin").user)
    text = (tool.description + json.dumps(tool.input_schema)).lower()
    for word in ("quanex", "midcon", "mid-continent", "internship", "data platform"):
        assert word not in text, word
    # The public asks are still there.
    assert '"what runs where"' in tool.description


# =============================================================================
# 1. Diagrams first: legion_docs points at the view from the question's own words
# =============================================================================


@pytest.mark.parametrize(
    "question,view",
    [
        ("Go one level deeper, what runs where", "containers"),
        ("How is the data modeled", "data_model"),
        ("Show me what happens when someone talks to it", "voice_turn"),
        ("How does Alfred build Alfred", "alfred_builds"),
        ("Walk me through LEGION's architecture", "components"),
    ],
)
def test_the_question_picks_the_view_before_the_passages(question, view):
    picked = catalogue.suggested_views([], question)
    assert picked and picked[0][1] == view


def test_legion_docs_names_the_view_for_what_runs_where(household_user):
    data = _tool(
        browser(household_user), "legion_docs", {"query": "Go one level deeper, what runs where"}
    ).data
    assert data["is_error"] is False
    assert 'show_diagram(project="legion", view="containers")' in data["text"]
    assert "show it now, before you answer" in data["text"]


# =============================================================================
# 3. show_resume
# =============================================================================


def test_show_resume_is_recruiter_only_and_classified():
    assert "show_resume" in RECRUITER_TOOLS
    assert "show_resume" in RECRUITER_ONLY_TOOLS
    assert "show_resume" not in TOOLS_BY_NAME  # so /mcp and the phone never list it
    assert "show_resume" not in WEB_TOOL_NAMES
    assert "show_resume" not in SCREEN_ONLY_TOOLS  # not needed: it is not in the registry


def test_a_member_is_never_offered_show_resume(household_user):
    assert "show_resume" not in [t.name for t in tools_for(household_user)]
    data = _tool(browser(household_user), "show_resume", {}).data
    assert "card" not in data


def test_the_full_version_shows_the_page_image_and_the_pdf(codes, tmp_path):
    resume_code, _ = codes
    said = _load_content(_folder(tmp_path / "full"))
    assert f"resume.png: loaded, {len(FAKE_PNG)} bytes." in said
    visitor = _enter(resume_code)
    data = _tool(visitor, "show_resume", {}).data
    assert data["is_error"] is False
    card = data["card"]
    assert {k: v for k, v in card.items() if k != "alt"} == {
        "card": "resume",
        "title": "Kevin's resume",
        "image": "/api/demo/asset/resume_image",
        "pdf": "/api/demo/asset/resume",
    }
    assert "one-page resume" in card["alt"]
    # The model is told it is on the screen, never its contents, and never gets the card.
    assert "on the screen now" in data["text"]
    assert "card" not in json.dumps(data["response"])

    image = visitor.get("/api/demo/asset/resume_image")
    assert image.status_code == 200
    assert image.content == FAKE_PNG
    assert image["Content-Type"] == "image/png"
    assert "Content-Disposition" not in image  # drawn inline by the modal
    assert image["X-Content-Type-Options"] == "nosniff"
    assert image["Cache-Control"] == "private, no-store"
    pdf = visitor.get("/api/demo/asset/resume")
    assert pdf.content == FAKE_PDF and pdf["Content-Disposition"].startswith("attachment;")
    assert visitor.get("/api/demo/content").json()["assets"]["resume_image"] == (
        "/api/demo/asset/resume_image"
    )


def test_a_pdf_with_no_page_image_still_offers_the_download(codes, tmp_path):
    resume_code, _ = codes
    _load_content(_folder(tmp_path / "full", image=False))
    data = _tool(_enter(resume_code), "show_resume", {}).data
    assert data["is_error"] is False
    assert data["card"]["image"] is None and data["card"]["pdf"] == "/api/demo/asset/resume"
    assert "no image of the page" in data["text"]


def test_the_linkedin_version_has_no_resume_and_draws_nothing(codes, tmp_path):
    """The LinkedIn folder carries no resume.pdf and no resume.png (Kevin's generator unlinks
    both), so its visitors are told in words and shown nothing, even with the full version's
    resume loaded."""
    _resume, linkedin_code = codes
    _load_content(_folder(tmp_path / "full"))
    li = fake_content()
    li.pop("work")
    li_folder = _folder(tmp_path / "li", image=False, resume=False, content=li)
    _load_content(li_folder, "--for-code", "linkedin")
    stranger = _enter(linkedin_code)
    data = _tool(stranger, "show_resume", {}).data
    assert data["is_error"] is True and "card" not in data
    assert data["text"] == NO_RESUME
    assert stranger.get("/api/demo/asset/resume_image").status_code == 404
    assert stranger.get("/api/demo/asset/resume").status_code == 404


def test_nothing_loaded_is_said_in_words(codes):
    resume_code, _ = codes
    data = _tool(_enter(resume_code), "show_resume", {}).data
    assert data["is_error"] is True and data["text"] == NO_RESUME


def test_show_resume_takes_no_arguments(codes, tmp_path):
    resume_code, _ = codes
    _load_content(_folder(tmp_path / "full"))
    data = _tool(_enter(resume_code), "show_resume", {"page": 2}).data
    assert data["is_error"] is True and "card" not in data


def test_the_loader_refuses_a_page_image_that_is_not_a_png(db, tmp_path):
    folder = _folder(tmp_path / "bad")
    (folder / "resume.png").write_bytes(b"GIF89a not a png")
    with pytest.raises(CommandError, match="resume.png does not start like a PNG"):
        _load_content(folder)
    assert not RecruiterContent.objects.exists()


def test_the_loader_refuses_a_page_image_without_its_pdf(db, tmp_path):
    folder = _folder(tmp_path / "alone", resume=False)
    with pytest.raises(CommandError, match="without resume.pdf"):
        _load_content(folder)
    assert not RecruiterContent.objects.exists()


def test_a_reload_without_the_image_clears_it(db, tmp_path):
    _load_content(_folder(tmp_path / "one"))
    assert RecruiterContent.current().resume_image is not None
    said = _load_content(_folder(tmp_path / "two", image=False))
    assert RecruiterContent.current().resume_image is None
    assert "resume.png: not in the folder, so none is served." in said


def test_the_prompt_sends_the_resume_to_show_resume():
    assert "call show_resume" in RESUME_RULE
    assert "Download PDF" in RESUME_RULE
    assert "Kevin can share it directly" in RESUME_RULE
    assert RESUME_RULE in RECRUITER_FRAME
    # The old answer, "it is on this page", is gone for the resume.
    assert "Download resume button" not in RECRUITER_FRAME


def test_the_recruiter_setup_declares_show_resume(codes, tmp_path):
    from demo.models import DemoVisit

    resume_code, _ = codes
    _enter(resume_code)
    names = [t.name for t in tools_for(DemoVisit.objects.get(code__label="").user)]
    assert names[-1] == "show_resume"


# =============================================================================
# The re-greeting: a later conversation in the same visit
# =============================================================================


def test_the_continue_line_is_explained_and_never_the_opener():
    assert RECRUITER_CONTINUE != RECRUITER_OPENER
    assert RECRUITER_CONTINUE_RULE in RECRUITER_FRAME
    assert RECRUITER_CONTINUE in RECRUITER_CONTINUE_RULE
    assert "do not greet them again" in RECRUITER_CONTINUE_RULE
    assert "recording notice" in RECRUITER_CONTINUE_RULE


def test_the_page_sends_the_same_continue_line():
    """`frontend/src/screens/try.tsx` CONTINUE_TURN must be this exact line."""
    from pathlib import Path

    from django.conf import settings

    try_tsx = Path(settings.BASE_DIR) / "frontend" / "src" / "screens" / "try.tsx"
    if not try_tsx.is_file():
        pytest.skip("the frontend source is not here (the engine image carries no frontend src)")
    text = try_tsx.read_text(encoding="utf-8")
    assert f"export const CONTINUE_TURN = '{RECRUITER_CONTINUE}'" in text
    assert f"export const OPENING_TURN = '{RECRUITER_OPENER}'" in text
