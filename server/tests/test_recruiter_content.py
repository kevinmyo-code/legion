"""recruiter-demo tickets 07 and 10: the recruiter page's content store and its reads.

`manage.py load_recruiter_content <dir>` and `GET /api/demo/session`,
`/api/demo/content`, `/api/demo/asset/<report|resume>`. Every string below is
obviously fake: real content lives in Kevin's private folder, never the repo.
"""

from __future__ import annotations

import json
from io import StringIO

import pytest
from django.core.cache import cache
from django.core.management import CommandError, call_command
from rest_framework.test import APIClient

from demo.content import CONTENT_SCHEMA, problems, public_content
from demo.content_views import NO_CONTENT, NOT_A_READER, NOT_A_VISIT, NOT_SIGNED_IN
from demo.models import DemoCode, RecruiterContent
from household.models import Household, HouseholdMember, User
from household.permissions import RECRUITER_VISIT_OVER
from tests.conftest import _client_for

pytestmark = pytest.mark.django_db

FAKE_PDF = b"%PDF-1.4\n% fake resume for tests\n%%EOF\n"
FAKE_REPORT = b"<!doctype html><title>Fake report</title><p>Lorem ipsum report.</p>"


def fake_content() -> dict:
    return {
        "hero": {
            "title": "Test Person builds test things",
            "lead": "Lorem ipsum lead.",
            "facts": ["Fact one (fake)", "Fact two (fake)"],
        },
        "look_for": [{"title": "Look here", "body": "Lorem ipsum body."}],
        "work": {
            "title": "Fake case study",
            "intro": "Lorem ipsum intro.",
            "stats": [{"value": "42", "label": "fake widgets"}],
            "diagram_svg": "<svg xmlns='http://www.w3.org/2000/svg'></svg>",
            "diagram_caption": "A fake diagram.",
            "cards": [{"title": "Card", "body": "Lorem ipsum card."}],
        },
        "experience": [
            {"when": "2001 - 2002", "title": "Fake role", "bullets": ["Did fake work."]}
        ],
        "links": {
            "linkedin": "https://example.com/in/fake",
            "github": "https://example.com/fake",
            "email": "fake@example.com",
        },
        "profile": {
            "Experience": "Fake experience: three years of pretend work at Example Corp.",
            "Location": "Fakeville, Nowhere.",
        },
    }


def write_folder(path, content=None, *, report=True, resume=True):
    path.mkdir(parents=True, exist_ok=True)
    (path / "content.json").write_text(json.dumps(content or fake_content()), encoding="utf-8")
    if report:
        (path / "report.html").write_bytes(FAKE_REPORT)
    if resume:
        (path / "resume.pdf").write_bytes(FAKE_PDF)
    return path


def load_fake(tmp_path, **kw) -> str:
    out = StringIO()
    call_command("load_recruiter_content", str(write_folder(tmp_path / "c", **kw)), stdout=out)
    return out.getvalue()


@pytest.fixture(autouse=True)
def _fresh_throttle():
    cache.clear()
    yield
    cache.clear()


@pytest.fixture
def recruiter(db):
    Household.objects.create(name="Demo")
    _row, code = DemoCode.rotate()
    browser = APIClient()
    entered = browser.post("/api/demo/enter", {"code": code, "ref": "acme"}, format="json")
    assert entered.status_code == 201, entered.data
    return browser


@pytest.fixture
def plain_member(household_a):
    user = User.objects.create_user(email="member@example.com", password="x")
    HouseholdMember.objects.create(user=user, household=household_a, role=HouseholdMember.MEMBER)
    return _client_for(user)


# =============================================================================
# The command
# =============================================================================


def test_the_command_loads_content_and_both_files(tmp_path):
    said = load_fake(tmp_path)
    row = RecruiterContent.current()
    assert row.content == fake_content()
    assert bytes(row.report) == FAKE_REPORT
    assert bytes(row.resume) == FAKE_PDF
    assert row.resume_content_type == "application/pdf"
    assert "Created the recruiter content" in said
    assert "Experience, Location" in said
    assert f"resume.pdf: loaded, {len(FAKE_PDF)} bytes." in said


def test_a_reload_replaces_in_place_and_clears_a_file_no_longer_there(tmp_path):
    load_fake(tmp_path)
    content = fake_content() | {"links": {"linkedin": "a", "github": "b", "email": "c"}}
    said = load_fake(tmp_path / "second", content=content, resume=False)
    assert RecruiterContent.objects.count() == 1
    row = RecruiterContent.current()
    assert row.content["links"]["email"] == "c"
    assert row.resume is None and row.report is not None
    assert "Replaced" in said and "resume.pdf: not in the folder" in said


def test_the_files_are_optional(tmp_path):
    load_fake(tmp_path, report=False, resume=False)
    row = RecruiterContent.current()
    assert row.report is None and row.resume is None


def test_a_bad_content_json_loads_nothing_and_names_every_path(tmp_path):
    content = fake_content()
    del content["hero"]["lead"]
    content["work"]["stats"] = [{"value": 3, "label": "x"}]
    content["profile"]["Salary"] = 100
    with pytest.raises(CommandError) as raised:
        load_fake(tmp_path, content=content)
    message = str(raised.value)
    assert message.startswith("Nothing was loaded.")
    assert "hero: 'lead' is a required property" in message
    assert "work/stats/0/value" in message
    assert "profile/Salary" in message
    assert RecruiterContent.objects.count() == 0


def test_missing_content_json_and_a_fake_pdf_refuse(tmp_path):
    folder = tmp_path / "empty"
    folder.mkdir()
    with pytest.raises(CommandError, match="content.json does not exist"):
        call_command("load_recruiter_content", str(folder))
    write_folder(folder, resume=False)
    (folder / "resume.pdf").write_bytes(b"not a pdf")
    with pytest.raises(CommandError, match="does not start like a PDF"):
        call_command("load_recruiter_content", str(folder))
    assert RecruiterContent.objects.count() == 0


def test_the_schema_requires_every_top_level_section():
    assert set(CONTENT_SCHEMA["required"]) == {
        "hero", "look_for", "work", "experience", "links", "profile",
    }
    assert problems(fake_content()) == []
    assert problems(fake_content() | {"extra": 1})


def fake_phone() -> dict:
    return {
        "title": "Fake phone section",
        "intro": "Lorem ipsum phone intro.",
        "clips": [
            {
                "title": "Fake clip with video",
                "body": "Lorem ipsum clip.",
                "video": "/static/demo/fake.mp4",
                "poster": "/static/demo/fake.jpg",
                "captions": "/static/demo/fake.vtt",
            },
            {"title": "Fake clip, no video yet", "body": "Lorem ipsum placeholder."},
        ],
        "full_take": "/static/demo/fake-full.mp4",
    }


def test_name_and_phone_are_optional():
    assert "name" not in CONTENT_SCHEMA["required"]
    assert "phone" not in CONTENT_SCHEMA["required"]
    # Content written before either key existed still fits.
    assert problems(fake_content()) == []
    assert problems(fake_content() | {"name": "Test Person"}) == []


def test_a_phone_section_fits_with_and_without_its_optional_keys():
    assert problems(fake_content() | {"phone": fake_phone()}) == []
    bare = fake_phone()
    del bare["full_take"]
    bare["clips"] = [{"title": "Only a title", "body": "And a body."}]
    assert problems(fake_content() | {"phone": bare}) == []


def test_a_phone_section_refuses_unknown_and_missing_keys():
    unknown_clip = fake_phone()
    unknown_clip["clips"][0]["autoplay"] = True
    found = problems(fake_content() | {"phone": unknown_clip})
    assert len(found) == 1 and found[0].startswith("phone/clips/0:")

    unknown_top = fake_phone() | {"extra": "x"}
    assert problems(fake_content() | {"phone": unknown_top})

    no_body = fake_phone()
    del no_body["clips"][1]["body"]
    assert problems(fake_content() | {"phone": no_body})

    no_clips = fake_phone()
    del no_clips["clips"]
    assert problems(fake_content() | {"phone": no_clips})

    assert problems(fake_content() | {"name": 7})


# =============================================================================
# GET /api/demo/session
# =============================================================================


def test_session_answers_a_recruiter(recruiter):
    response = recruiter.get("/api/demo/session")
    assert response.status_code == 200
    assert response.data["role"] == "recruiter"
    assert response.data["ref"] == "acme"
    assert response.data["expires_at"] is not None


def test_session_ref_is_null_when_the_link_had_none(db):
    Household.objects.create(name="Demo")
    _row, code = DemoCode.rotate()
    browser = APIClient()
    browser.post("/api/demo/enter", {"code": code}, format="json")
    assert browser.get("/api/demo/session").data["ref"] is None


def test_session_refuses_everyone_else(token_a, plain_member):
    assert token_a.get("/api/demo/session").data == {"detail": NOT_A_VISIT}
    assert token_a.get("/api/demo/session").status_code == 403
    assert plain_member.get("/api/demo/session").status_code == 403
    anonymous = APIClient().get("/api/demo/session")
    assert anonymous.status_code == 403
    assert anonymous.data == {"detail": NOT_SIGNED_IN, "code": "not_signed_in"}


# =============================================================================
# GET /api/demo/content and /api/demo/asset/<kind>
# =============================================================================


def test_content_404s_in_words_before_anything_is_loaded(recruiter):
    response = recruiter.get("/api/demo/content")
    assert response.status_code == 404
    assert response.data == {"detail": NO_CONTENT}


def test_content_is_served_without_the_profile(recruiter, tmp_path):
    load_fake(tmp_path)
    response = recruiter.get("/api/demo/content")
    assert response.status_code == 200
    expected = {k: v for k, v in fake_content().items() if k != "profile"}
    expected["assets"] = {"report": "/api/demo/asset/report", "resume": "/api/demo/asset/resume"}
    assert response.json() == expected
    assert "pretend work" not in response.content.decode()


def test_name_and_phone_are_served_without_the_profile(recruiter, tmp_path):
    content = fake_content() | {"name": "Test Person", "phone": fake_phone()}
    load_fake(tmp_path, content=content)
    served = recruiter.get("/api/demo/content").json()
    assert served["name"] == "Test Person"
    assert served["phone"] == fake_phone()
    assert "profile" not in served
    assert public_content(content) == {k: v for k, v in content.items() if k != "profile"}


def test_assets_are_null_when_not_loaded(recruiter, tmp_path):
    load_fake(tmp_path, report=False, resume=False)
    assert recruiter.get("/api/demo/content").json()["assets"] == {"report": None, "resume": None}
    missing = recruiter.get("/api/demo/asset/resume")
    assert missing.status_code == 404
    assert missing.data["detail"].startswith("Nothing was read.")


def test_the_report_is_sandboxed_and_the_resume_downloads(recruiter, tmp_path):
    load_fake(tmp_path)
    report = recruiter.get("/api/demo/asset/report")
    assert report.status_code == 200
    assert report.content == FAKE_REPORT
    assert report["Content-Type"].startswith("text/html")
    assert report["Content-Security-Policy"] == "sandbox allow-scripts"
    assert report["X-Content-Type-Options"] == "nosniff"
    resume = recruiter.get("/api/demo/asset/resume")
    assert resume.status_code == 200
    assert resume.content == FAKE_PDF
    assert resume["Content-Type"] == "application/pdf"
    assert resume["Content-Disposition"] == 'attachment; filename="Kevin-Win-Resume.pdf"'
    assert resume["X-Content-Type-Options"] == "nosniff"
    assert recruiter.get("/api/demo/asset/other").status_code == 404


def test_an_owner_may_preview(token_a, tmp_path):
    load_fake(tmp_path)
    assert token_a.get("/api/demo/content").status_code == 200
    assert token_a.get("/api/demo/asset/resume").status_code == 200


def test_a_plain_member_and_a_stranger_are_refused(plain_member, tmp_path):
    load_fake(tmp_path)
    for path in ("/api/demo/content", "/api/demo/asset/report", "/api/demo/asset/resume"):
        refused = plain_member.get(path)
        assert refused.status_code == 403 and refused.data == {"detail": NOT_A_READER}, path
        anonymous = APIClient().get(path)
        assert anonymous.status_code == 403, path
        assert FAKE_PDF not in anonymous.content


def test_a_recruiter_whose_visit_ended_is_refused(recruiter, tmp_path):
    from household.models import DeviceToken

    load_fake(tmp_path)
    DeviceToken.objects.filter(user__email__endswith="@demo.invalid").update(revoked_at=_now())
    for path in ("/api/demo/content", "/api/demo/session", "/api/assistant/companion"):
        ended = recruiter.get(path)
        assert ended.status_code == 403, path
        assert ended.json() == {"detail": RECRUITER_VISIT_OVER, "code": "demo_visit_ended"}, path


def test_a_recruiter_reads_its_companion_as_alfred(recruiter):
    from assistant import personas

    response = recruiter.get("/api/assistant/companion")
    assert response.status_code == 200, response.data
    assert response.data["name"] == personas.ALFRED.default_name
    assert response.data["voice_name"] == personas.ALFRED.suggested_voice


def _now():
    from django.utils import timezone

    return timezone.now()


# =============================================================================
# about_kevin (ticket 10): recruiters only, answers only from the profile
# =============================================================================


def _tool(client, name, args=None):
    return client.post("/api/assistant/tool", {"name": name, "args": args or {}}, format="json")


def test_about_kevin_returns_the_profile_sections(recruiter, tmp_path):
    load_fake(tmp_path)
    response = _tool(recruiter, "about_kevin")
    assert response.status_code == 200, response.data
    assert response.data["is_error"] is False
    text = response.data["text"]
    assert "Experience:\nFake experience: three years of pretend work" in text
    assert "Location:\nFakeville, Nowhere." in text
    assert "Kevin hasn't shared that here; ask him directly" in text
    # Nothing outside `profile` rides along.
    assert "Fake case study" not in text


def test_about_kevin_with_nothing_loaded_says_so_in_words(recruiter):
    response = _tool(recruiter, "about_kevin")
    assert response.status_code == 200
    assert response.data["is_error"] is True
    assert response.data["text"].startswith("Nothing was read.")
    assert "Kevin hasn't shared that here; ask him directly" in response.data["text"]


def test_about_kevin_refuses_extra_arguments(recruiter, tmp_path):
    load_fake(tmp_path)
    response = _tool(recruiter, "about_kevin", {"section": "salary"})
    assert response.data["is_error"] is True
    assert "Nothing was read" in response.data["text"]


def test_about_kevin_is_never_offered_to_a_member(household_user, tmp_path):
    from assistant.surface import tools_for
    from engine_mcp.tools import TOOLS_BY_NAME

    load_fake(tmp_path)
    assert "about_kevin" not in TOOLS_BY_NAME  # so /mcp never lists it
    assert "about_kevin" not in [t.name for t in tools_for(household_user)]
    web = APIClient()
    web.force_login(household_user)
    response = _tool(web, "about_kevin")
    assert response.status_code == 400
    assert response.data["detail"].startswith("Nothing was read or written.")
    assert "pretend work" not in str(response.data)


def test_about_kevin_is_declared_to_a_recruiter_with_its_rule():
    from assistant.surface import recruiter_tools

    tool = {t.name: t for t in recruiter_tools()}["about_kevin"]
    assert "Kevin hasn't shared that here; ask him directly" in tool.description
    assert not tool.writes


def test_the_legion_section_is_optional_and_closed():
    assert problems(fake_content()) == []
    good = dict(fake_content(), legion={"title": "How", "intro": "It", "parts": [{"title": "a", "body": "b"}]})
    assert problems(good) == []
    bad = dict(fake_content(), legion={"title": "How", "intro": "It", "parts": [], "extra": 1})
    assert problems(bad)
