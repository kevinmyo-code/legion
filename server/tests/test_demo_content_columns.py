# ruff: noqa: F811 - fixtures imported from the recruiter content tests
"""No demo read pulls a stored file it is not serving (Supabase database egress, 2026-11-23).

`RecruiterContent.current()` / `for_user()` used to select every column, so the report HTML, the
resume PDF and the resume's page image left Postgres on every `/api/demo/*` call - a 304 for a
media file included - and no answer but `GET /api/demo/asset/<kind>` ever showed them. On the
loaded row those three were about 0.9 MB, and bytea leaves Postgres hex-encoded, so roughly twice
that on the wire: measured at 2.3 GB of the 12 GB November bill.

So these tests watch the SQL a request runs, which is the only place the saving shows. They pass
trivially if the page breaks, so each one also checks the answer it came for.

Every byte, name and section below is invented.
"""

from __future__ import annotations

import re
from types import SimpleNamespace

import pytest
from django.db import connection
from django.test.utils import CaptureQueriesContext

from demo.content import ASSET_KINDS, PNG_MAGIC
from tests.test_assistant import _tool
from tests.test_recruiter_content import (  # noqa: F401 - fixtures
    FAKE_PDF,
    FAKE_REPORT,
    _fresh_throttle,
    recruiter,
)
from tests.test_recruiter_media import FAKE_GLB, GADGET, load_with_files, with_models

pytestmark = pytest.mark.django_db

FAKE_RESUME_PNG = PNG_MAGIC + b"\x00\x00\x00\rIHDR fake resume page"

# The column as a whole item of a SELECT list: the bytes themselves are on their way out. A
# presence check names the column too (`"...". "resume" IS NOT NULL`), and so does `substr(...)`,
# and neither sends a byte of it, so what counts is the column followed by the next item's comma
# or by FROM.
_SELECTED = {
    kind: re.compile(rf'"demo_recruitercontent"\."{kind}"\s*(?:,|FROM\b)') for kind in ASSET_KINDS
}


def _files_read(queries) -> list[str]:
    """Which of the row's stored files a captured run of queries read the bytes of."""
    return sorted(
        kind
        for kind, pattern in _SELECTED.items()
        if any(pattern.search(query["sql"] or "") for query in queries)
    )


def _loaded(tmp_path, *, image: bool = True) -> None:
    """Content with all three files and one media file, as the live folder holds it."""
    files = {"gadget.glb": FAKE_GLB, "gadget.png": FAKE_RESUME_PNG}
    if image:
        files["resume.png"] = FAKE_RESUME_PNG
    load_with_files(tmp_path, with_models(GADGET), files)


def test_the_page_content_is_read_without_the_stored_files(recruiter, tmp_path):
    _loaded(tmp_path)
    with CaptureQueriesContext(connection) as queries:
        got = recruiter.get("/api/demo/content")
    assert got.status_code == 200
    assert got.json()["assets"] == {
        "report": "/api/demo/asset/report",
        "resume": "/api/demo/asset/resume",
        "resume_image": "/api/demo/asset/resume_image",
    }
    assert _files_read(queries) == []


def test_a_file_the_folder_does_not_hold_is_still_offered_to_nobody(recruiter, tmp_path):
    """The presence check answers the same without the bytes: no page image, no link."""
    _loaded(tmp_path, image=False)
    with CaptureQueriesContext(connection) as queries:
        assets = recruiter.get("/api/demo/content").json()["assets"]
    assert assets["resume_image"] is None
    assert assets["resume"] == "/api/demo/asset/resume"
    assert _files_read(queries) == []


def test_a_media_request_and_its_304_read_no_stored_file(recruiter, tmp_path):
    _loaded(tmp_path)
    with CaptureQueriesContext(connection) as first:
        got = recruiter.get("/api/demo/media/gadget.glb")
    assert got.status_code == 200 and got.content == FAKE_GLB
    assert _files_read(first) == []
    with CaptureQueriesContext(connection) as again:
        not_modified = recruiter.get("/api/demo/media/gadget.glb", HTTP_IF_NONE_MATCH=got["ETag"])
    assert not_modified.status_code == 304 and not_modified.content == b""
    assert _files_read(again) == []


def test_an_asset_request_reads_only_the_file_it_serves(recruiter, tmp_path):
    _loaded(tmp_path)
    with CaptureQueriesContext(connection) as queries:
        got = recruiter.get("/api/demo/asset/resume")
    assert got.status_code == 200 and got.content == FAKE_PDF
    assert _files_read(queries) == ["resume"]
    with CaptureQueriesContext(connection) as report:
        assert recruiter.get("/api/demo/asset/report").content == FAKE_REPORT
    assert _files_read(report) == ["report"]


def test_the_resume_card_is_built_without_the_resume(recruiter, tmp_path):
    """`show_resume` hands the page links, never bytes, so it needs neither file."""
    _loaded(tmp_path)
    with CaptureQueriesContext(connection) as queries:
        got = _tool(recruiter, "show_resume", {})
    assert got.status_code == 200, got.data
    assert got.data["is_error"] is False
    assert got.data["card"]["image"] == "/api/demo/asset/resume_image"
    assert got.data["card"]["pdf"] == "/api/demo/asset/resume"
    assert _files_read(queries) == []


def test_the_profile_answer_reads_no_stored_file(recruiter, tmp_path):
    """The web and `/mcp` door: `about_kevin` hands the model the profile out of `content`."""
    _loaded(tmp_path)
    with CaptureQueriesContext(connection) as queries:
        got = _tool(recruiter, "about_kevin", {})
    assert got.status_code == 200, got.data
    assert got.data["is_error"] is False, got.data["text"]
    assert "Fake experience" in got.data["text"]
    assert _files_read(queries) == []


def test_the_spoken_answer_offers_the_resume_without_reading_it(recruiter, tmp_path):
    """The cascade's `about_kevin` (`spoken_for`) closes with the resume offer only when one is
    loaded, which used to cost the PDF itself."""
    from demo.models import DemoVisit
    from demo.profile import OFFERS
    from demo.tools import spoken_for
    from engine_mcp.tools import run_engine_tool

    _loaded(tmp_path)
    visitor = DemoVisit.objects.get().user
    request = SimpleNamespace(user=visitor)
    with CaptureQueriesContext(connection) as queries:
        got = run_engine_tool(request, spoken_for(visitor), {"offer": "resume"})
    assert not got.is_error, got.text
    assert OFFERS["resume"] in got.text
    assert _files_read(queries) == []
