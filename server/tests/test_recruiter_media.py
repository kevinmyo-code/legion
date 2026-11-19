# ruff: noqa: F811 - fixtures imported from the recruiter content tests
"""A project section's 3D models: the schema, the loader and the private route that serves them.

`media.models` on a project section names files in the owner's private folder (a GLB and its
poster). `manage.py load_recruiter_content` checks and stores them beside the content row, and
`GET /api/demo/media/<name>` serves them to a recruiter's visit or an owner only, like the resume.
Every name and byte below is invented.
"""

from __future__ import annotations

import json
import struct
from io import StringIO

import pytest
from django.core.management import CommandError, call_command
from rest_framework.test import APIClient

from demo.content import PNG_MAGIC, media_cap, problems
from demo.models import RecruiterContent, RecruiterMediaFile
from tests.test_recruiter_content import (  # noqa: F401 - fixtures
    _fresh_throttle,
    fake_content,
    fake_projects,
    load_fake,
    plain_member,
    recruiter,
    write_folder,
)

pytestmark = pytest.mark.django_db



def fake_glb(gltf: dict | None = None) -> bytes:
    """A GLB header and JSON chunk, nothing drawable: enough for the loader's checks."""
    text = json.dumps(gltf or {"asset": {"version": "2.0"}}).encode()
    text += b" " * (-len(text) % 4)
    return (
        b"glTF"
        + struct.pack("<II", 2, 12 + 8 + len(text))
        + struct.pack("<II", len(text), 0x4E4F534A)
        + text
    )


FAKE_GLB = fake_glb()
FAKE_PNG = PNG_MAGIC + b"fake poster"
FAKE_JPEG = b"\xff\xd8\xff\xe0fake poster"


def with_models(*models: dict) -> dict:
    content = fake_content() | fake_projects()
    content["project_sections"][0]["media"] = {"note": "Fake note.", "models": list(models)}
    return content


GADGET = {"title": "Fake gadget", "model": "gadget.glb", "poster": "gadget.png"}
WIDGET = {"title": "Fake widget", "model": "widget_v2.glb", "poster": "widget.jpg", "note": "Fake."}


def load_with_files(tmp_path, content, files: dict[str, bytes]) -> str:
    folder = write_folder(tmp_path / "c", content)
    for name, data in files.items():
        (folder / name).write_bytes(data)
    out = StringIO()
    call_command("load_recruiter_content", str(folder), stdout=out)
    return out.getvalue()


# =============================================================================
# The schema
# =============================================================================


def test_old_content_without_models_still_fits():
    assert problems(fake_content() | fake_projects()) == []


def test_a_section_with_models_fits():
    assert problems(with_models(GADGET, WIDGET)) == []


@pytest.mark.parametrize(
    ("entry", "where"),
    [
        ({"title": "x", "model": "../secret.glb", "poster": "a.png"}, "model"),
        ({"title": "x", "model": "dir/a.glb", "poster": "a.png"}, "model"),
        ({"title": "x", "model": "a.gltf", "poster": "a.png"}, "model"),
        ({"title": "x", "model": "a.glb", "poster": "a.svg"}, "poster"),
        ({"title": "x", "model": "a.glb", "poster": "..\\a.png"}, "poster"),
    ],
)
def test_a_bad_file_name_is_refused_with_a_useful_message(entry, where):
    found = problems(with_models(entry))
    assert len(found) == 1
    assert found[0].startswith(f"project_sections/0/media/models/0/{where}: ")
    assert "a file name in the content folder" in found[0]


def test_a_model_entry_is_closed_and_needs_its_poster():
    no_poster = dict(GADGET)
    del no_poster["poster"]
    assert problems(with_models(no_poster))[0].startswith("project_sections/0/media/models/0:")
    extra = GADGET | {"autoplay": True}
    assert problems(with_models(extra))[0].startswith("project_sections/0/media/models/0:")


def test_models_belong_to_a_section_not_to_a_strip_line():
    content = fake_content() | fake_projects()
    content["projects"][0]["media"] = {"models": [GADGET]}
    assert problems(content)[0].startswith("projects/0/media:")


# =============================================================================
# Loading
# =============================================================================


def test_the_named_files_load_beside_the_content(tmp_path):
    said = load_with_files(
        tmp_path,
        with_models(GADGET, WIDGET),
        {
            "gadget.glb": FAKE_GLB,
            "gadget.png": FAKE_PNG,
            "widget_v2.glb": FAKE_GLB,
            "widget.jpg": FAKE_JPEG,
        },
    )
    row = RecruiterContent.current()
    stored = {f.name: f for f in RecruiterMediaFile.objects.filter(content=row)}
    assert sorted(stored) == ["gadget.glb", "gadget.png", "widget.jpg", "widget_v2.glb"]
    assert bytes(stored["gadget.glb"].data) == FAKE_GLB
    assert stored["gadget.glb"].content_type == "model/gltf-binary"
    assert stored["widget.jpg"].content_type == "image/jpeg"
    assert "4 media files loaded" in said


def test_a_missing_or_mislabelled_file_loads_nothing(tmp_path):
    content = with_models(GADGET)
    with pytest.raises(CommandError, match=r"models/0/poster: 'gadget.png' is not in the folder"):
        load_with_files(tmp_path / "a", content, {"gadget.glb": FAKE_GLB})
    with pytest.raises(CommandError, match=r"'gadget.glb' does not start like a GLB"):
        load_with_files(tmp_path / "b", content, {"gadget.glb": b"nope", "gadget.png": FAKE_PNG})
    with pytest.raises(CommandError, match=r"'gadget.png' does not start like a PNG"):
        load_with_files(tmp_path / "c", content, {"gadget.glb": FAKE_GLB, "gadget.png": FAKE_JPEG})
    assert RecruiterContent.objects.count() == 0
    assert RecruiterMediaFile.objects.count() == 0


def test_a_model_with_embedded_textures_is_refused(tmp_path):
    textured = fake_glb({"asset": {"version": "2.0"}, "images": [{"bufferView": 0}]})
    with pytest.raises(CommandError, match=r"'gadget.glb' embeds 1 texture image.*blob:"):
        load_with_files(
            tmp_path, with_models(GADGET), {"gadget.glb": textured, "gadget.png": FAKE_PNG}
        )
    assert RecruiterMediaFile.objects.count() == 0


def test_an_oversized_file_loads_nothing(tmp_path):
    big = FAKE_GLB + b"\x00" * media_cap("model/gltf-binary")
    with pytest.raises(CommandError, match=r"'gadget.glb' is .* over the .* limit for a GLB"):
        load_with_files(tmp_path, with_models(GADGET), {"gadget.glb": big, "gadget.png": FAKE_PNG})


def test_a_poster_has_its_own_smaller_cap(tmp_path):
    assert media_cap("image/png") < media_cap("model/gltf-binary")
    big = FAKE_PNG + b"\x00" * media_cap("image/png")
    with pytest.raises(CommandError, match=r"'gadget.png' is .* over the .* limit for a PNG"):
        load_with_files(
            tmp_path / "a", with_models(GADGET), {"gadget.glb": FAKE_GLB, "gadget.png": big}
        )
    assert RecruiterMediaFile.objects.count() == 0


def test_a_reload_replaces_the_files_and_drops_ones_no_longer_named(tmp_path):
    first = {"gadget.glb": FAKE_GLB, "gadget.png": FAKE_PNG}
    load_with_files(tmp_path / "a", with_models(GADGET), first)
    second = {"widget_v2.glb": FAKE_GLB + b"v2", "widget.jpg": FAKE_JPEG}
    load_with_files(tmp_path / "b", with_models(WIDGET), second)
    names = sorted(RecruiterMediaFile.objects.values_list("name", flat=True))
    assert names == ["widget.jpg", "widget_v2.glb"]


# =============================================================================
# Serving
# =============================================================================


def _loaded(tmp_path):
    files = {"gadget.glb": FAKE_GLB, "gadget.png": FAKE_PNG}
    load_with_files(tmp_path, with_models(GADGET), files)


def test_the_page_gets_urls_for_the_named_files(recruiter, tmp_path):
    _loaded(tmp_path)
    media = recruiter.get("/api/demo/content").json()["project_sections"][0]["media"]
    assert media["models"] == [
        {
            "title": "Fake gadget",
            "model": "/api/demo/media/gadget.glb",
            "poster": "/api/demo/media/gadget.png",
        }
    ]
    assert media["note"] == "Fake note."


def test_a_visit_fetches_a_model_privately_with_a_validator(recruiter, tmp_path):
    _loaded(tmp_path)
    got = recruiter.get("/api/demo/media/gadget.glb")
    assert got.status_code == 200
    assert got.content == FAKE_GLB
    assert got["Content-Type"] == "model/gltf-binary"
    assert got["X-Content-Type-Options"] == "nosniff"
    assert got["Cache-Control"] == "private, no-cache"
    etag = got["ETag"]
    again = recruiter.get("/api/demo/media/gadget.glb", HTTP_IF_NONE_MATCH=etag)
    assert again.status_code == 304
    assert again.content == b""
    poster = recruiter.get("/api/demo/media/gadget.png")
    assert poster.status_code == 200 and poster["Content-Type"] == "image/png"


def test_an_owner_may_preview_a_model(token_a, tmp_path):
    _loaded(tmp_path)
    assert token_a.get("/api/demo/media/gadget.glb").status_code == 200


def test_no_session_and_a_plain_member_are_refused(plain_member, tmp_path):
    _loaded(tmp_path)
    assert APIClient().get("/api/demo/media/gadget.glb").status_code == 403
    assert plain_member.get("/api/demo/media/gadget.glb").status_code == 403


@pytest.mark.parametrize(
    "path",
    [
        "/api/demo/media/..%2Fcontent.json",
        "/api/demo/media/..%5Cgadget.glb",
        "/api/demo/media/%2E%2E",
        "/api/demo/media/content.json",
        "/api/demo/media/resume.pdf",
        "/api/demo/media/other.glb",
    ],
)
def test_a_name_that_is_not_a_loaded_file_is_a_404(recruiter, tmp_path, path):
    _loaded(tmp_path)
    assert recruiter.get(path).status_code == 404
