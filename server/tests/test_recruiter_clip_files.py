# ruff: noqa: F811 - fixtures imported from the recruiter content tests
"""A clip's video, poster and captions may name a private file instead of a public link.

A project section's and a phone clip's `video`, `poster` and `captions` are a link, as before, or
a file name in the owner's private folder, stored at load and served by `GET /api/demo/media/<name>`
to a visit or an owner only, with byte ranges so a video can seek. Every name and byte is invented.
"""

from __future__ import annotations

from io import StringIO

import pytest
from django.core.management import CommandError, call_command
from rest_framework.test import APIClient

from demo import content_views
from demo.content import PNG_MAGIC, problems
from demo.models import RecruiterContent, RecruiterMediaFile
from tests.test_recruiter_content import (  # noqa: F401 - fixtures
    _fresh_throttle,
    fake_content,
    fake_projects,
    plain_member,
    recruiter,
    write_folder,
)

pytestmark = pytest.mark.django_db

FAKE_MP4 = b"\x00\x00\x00\x18ftypisom" + bytes(range(256)) * 4
FAKE_WEBM = bytes([0x1A, 0x45, 0xDF, 0xA3]) + b"fake webm"
FAKE_VTT = bytes([0xEF, 0xBB, 0xBF]) + b"WEBVTT\n\n00:00.000 --> 00:01.000\nA fake cue.\n"
FAKE_PNG = PNG_MAGIC + b"fake still"


def with_clips(section_media: dict | None = None, phone_clip: dict | None = None) -> dict:
    content = fake_content() | fake_projects()
    if section_media is not None:
        content["project_sections"][0]["media"] = section_media
    if phone_clip is not None:
        content["phone"] = {
            "title": "Fake phone",
            "intro": "Lorem ipsum.",
            "clips": [{"title": "Fake clip", "body": "Lorem ipsum.", **phone_clip}],
        }
    return content


SECTION_FILES = {"video": "demo-take.mp4", "poster": "demo-take.png", "captions": "demo-take.vtt"}
PHONE_FILES = {"video": "phone-1.webm", "poster": "phone-1.png", "captions": "phone-1.vtt"}
ALL_FILES = {
    "demo-take.mp4": FAKE_MP4,
    "demo-take.png": FAKE_PNG,
    "demo-take.vtt": FAKE_VTT,
    "phone-1.webm": FAKE_WEBM,
    "phone-1.png": FAKE_PNG,
    "phone-1.vtt": FAKE_VTT,
}


def load(tmp_path, content, files: dict[str, bytes]) -> str:
    folder = write_folder(tmp_path / "c", content)
    for name, data in files.items():
        (folder / name).write_bytes(data)
    out = StringIO()
    call_command("load_recruiter_content", str(folder), stdout=out)
    return out.getvalue()


# =============================================================================
# The schema: a link or a file name
# =============================================================================


def test_links_still_fit_and_file_names_fit_too():
    links = {"video": "https://example.com/fake.mp4", "poster": "/static/fake.jpg"}
    assert problems(with_clips(links, {"video": "https://example.com/fake.webm"})) == []
    assert problems(with_clips(dict(SECTION_FILES), dict(PHONE_FILES))) == []


@pytest.mark.parametrize(
    ("media", "where"),
    [
        ({"video": "take.mov"}, "project_sections/0/media/video"),
        ({"video": "dir/take.mp4"}, "project_sections/0/media/video"),
        ({"captions": "take.srt"}, "project_sections/0/media/captions"),
        ({"poster": "take.png\n"}, "project_sections/0/media/poster"),
        ({"video": "fake take.mp4"}, "project_sections/0/media/video"),
    ],
)
def test_a_value_that_is_neither_a_link_nor_a_file_name_is_refused_in_words(media, where):
    found = problems(with_clips(media))
    assert len(found) == 1
    assert found[0].startswith(f"{where}: ")
    assert "is not a link (https://... or /...) or a file name in the content folder" in found[0]


def test_a_phone_clip_name_is_checked_the_same_way():
    found = problems(with_clips(None, {"video": "phone.avi"}))
    assert found and found[0].startswith("phone/clips/0/video: ")


# =============================================================================
# Loading
# =============================================================================


def test_section_and_phone_clip_files_load_with_their_types(tmp_path):
    said = load(tmp_path, with_clips(dict(SECTION_FILES), dict(PHONE_FILES)), ALL_FILES)
    stored = dict(RecruiterMediaFile.objects.values_list("name", "content_type"))
    assert stored == {
        "demo-take.mp4": "video/mp4",
        "demo-take.png": "image/png",
        "demo-take.vtt": "text/vtt; charset=utf-8",
        "phone-1.webm": "video/webm",
        "phone-1.png": "image/png",
        "phone-1.vtt": "text/vtt; charset=utf-8",
    }
    assert "6 media files loaded" in said


def test_links_load_no_file(tmp_path):
    load(tmp_path, with_clips({"video": "https://example.com/fake.mp4"}), {})
    assert RecruiterMediaFile.objects.count() == 0


@pytest.mark.parametrize(
    ("name", "data", "called"),
    [
        ("demo-take.mp4", b"not a video at all", "an MP4"),
        ("demo-take.vtt", b"1\n00:00:00,000 --> 00:00:01,000\nan srt\n", "a WebVTT file"),
    ],
)
def test_a_clip_file_that_is_not_what_its_name_says_loads_nothing(tmp_path, name, data, called):
    files = dict(ALL_FILES) | {name: data}
    with pytest.raises(CommandError, match=rf"'{name}' does not start like {called}"):
        load(tmp_path, with_clips(dict(SECTION_FILES)), files)
    assert RecruiterMediaFile.objects.count() == 0


def test_a_video_over_its_cap_loads_nothing(tmp_path, monkeypatch):
    from demo import content

    monkeypatch.setitem(content.MEDIA_MAX_BYTES, "video/mp4", 100)
    with pytest.raises(CommandError, match=r"'demo-take.mp4' is \d+ bytes, over the 100 byte"):
        load(tmp_path, with_clips(dict(SECTION_FILES)), ALL_FILES)


# =============================================================================
# The page and the route
# =============================================================================


def test_the_page_gets_private_links_for_names_and_links_as_written(recruiter, tmp_path):
    section = dict(SECTION_FILES) | {"note": "Fake note."}
    phone = {"video": "https://example.com/fake.webm", "poster": "phone-1.png"}
    load(tmp_path, with_clips(section, phone), ALL_FILES)
    page = recruiter.get("/api/demo/content").json()
    assert page["project_sections"][0]["media"] == {
        "video": "/api/demo/media/demo-take.mp4",
        "poster": "/api/demo/media/demo-take.png",
        "captions": "/api/demo/media/demo-take.vtt",
        "note": "Fake note.",
    }
    assert page["phone"]["clips"][0]["video"] == "https://example.com/fake.webm"
    assert page["phone"]["clips"][0]["poster"] == "/api/demo/media/phone-1.png"


@pytest.fixture
def clip(recruiter, tmp_path):
    load(tmp_path, with_clips(dict(SECTION_FILES)), ALL_FILES)
    return recruiter


URL = "/api/demo/media/demo-take.mp4"


def test_a_whole_video_is_served_with_ranges_offered(clip):
    got = clip.get(URL)
    assert got.status_code == 200
    assert got["Accept-Ranges"] == "bytes"
    assert got["Content-Type"] == "video/mp4"
    assert got.content == FAKE_MP4


@pytest.mark.parametrize(
    ("header", "first", "last"),
    [
        ("bytes=0-9", 0, 9),
        ("bytes=100-", 100, len(FAKE_MP4) - 1),
        ("bytes=-5", len(FAKE_MP4) - 5, len(FAKE_MP4) - 1),
        ("bytes=10-99999", 10, len(FAKE_MP4) - 1),
    ],
)
def test_a_range_is_served_as_206(clip, header, first, last):
    got = clip.get(URL, HTTP_RANGE=header)
    assert got.status_code == 206
    assert got["Content-Range"] == f"bytes {first}-{last}/{len(FAKE_MP4)}"
    assert got.content == FAKE_MP4[first : last + 1]
    assert got["Cache-Control"] == "private, no-cache"


def test_a_range_past_the_end_is_416(clip):
    got = clip.get(URL, HTTP_RANGE=f"bytes={len(FAKE_MP4)}-")
    assert got.status_code == 416
    assert got["Content-Range"] == f"bytes */{len(FAKE_MP4)}"


@pytest.mark.parametrize("header", ["bytes=0-1,5-6", "items=0-5", "bytes=-", "bytes=9-3"])
def test_a_range_this_route_does_not_read_gets_the_whole_file(clip, header):
    got = clip.get(URL, HTTP_RANGE=header)
    assert got.status_code == 200
    assert got.content == FAKE_MP4


def test_a_stale_if_range_gets_the_whole_file(clip):
    got = clip.get(URL, HTTP_RANGE="bytes=0-9", HTTP_IF_RANGE='"not-this-version"')
    assert got.status_code == 200
    assert got.content == FAKE_MP4


def test_one_answer_never_holds_more_than_a_chunk(clip, monkeypatch):
    monkeypatch.setattr(content_views, "RANGE_CHUNK", 100)
    part = clip.get(URL, HTTP_RANGE="bytes=0-")
    assert part.status_code == 206
    assert part["Content-Range"] == f"bytes 0-99/{len(FAKE_MP4)}"
    assert part.content == FAKE_MP4[:100]
    whole = clip.get(URL)
    assert whole.status_code == 200
    assert whole.streaming
    assert whole["Content-Length"] == str(len(FAKE_MP4))
    assert b"".join(whole.streaming_content) == FAKE_MP4


def test_captions_are_served_as_webvtt(clip):
    got = clip.get("/api/demo/media/demo-take.vtt")
    assert got.status_code == 200
    assert got["Content-Type"] == "text/vtt; charset=utf-8"
    assert got.content == FAKE_VTT


def test_a_revisit_with_the_etag_is_304_even_with_a_range(clip):
    etag = clip.get(URL)["ETag"]
    again = clip.get(URL, HTTP_RANGE="bytes=0-9", HTTP_IF_NONE_MATCH=etag)
    assert again.status_code == 304
    assert again.content == b""


def test_no_session_and_a_plain_member_get_no_byte(plain_member, tmp_path):
    load(tmp_path, with_clips(dict(SECTION_FILES)), ALL_FILES)
    for client in (APIClient(), plain_member):
        got = client.get(URL, HTTP_RANGE="bytes=0-9")
        assert got.status_code == 403
        assert FAKE_MP4[:10] not in got.content


# =============================================================================
# Size discipline (review R2-1): the files live in the engine's small Postgres
# =============================================================================


def test_the_caps_are_the_databases_not_the_pages():
    from demo.content import MAX_VIDEO_FILES, MEDIA_TOTAL_MAX_BYTES, media_cap

    mb = 1024 * 1024
    assert media_cap("video/mp4") == media_cap("video/webm") == 16 * mb
    assert media_cap("model/gltf-binary") == 4 * mb
    assert media_cap("image/png") == 1 * mb
    assert media_cap("text/vtt; charset=utf-8") == 256 * 1024
    assert MEDIA_TOTAL_MAX_BYTES == 40 * mb
    assert MAX_VIDEO_FILES == 12


def test_a_version_over_the_total_loads_nothing_and_names_the_file(tmp_path, monkeypatch):
    from demo import content

    monkeypatch.setattr(content, "MEDIA_TOTAL_MAX_BYTES", len(FAKE_MP4) + 10)
    with pytest.raises(CommandError, match=r"'demo-take.png' takes the media files to \d+ bytes"):
        load(tmp_path, with_clips(dict(SECTION_FILES)), ALL_FILES)
    assert RecruiterContent.objects.count() == 0
    assert RecruiterMediaFile.objects.count() == 0


def test_too_many_videos_load_nothing(tmp_path, monkeypatch):
    from demo import content

    monkeypatch.setattr(content, "MAX_VIDEO_FILES", 1)
    with pytest.raises(CommandError, match=r"names 2 video files; at most 1"):
        load(tmp_path, with_clips(dict(SECTION_FILES), dict(PHONE_FILES)), ALL_FILES)
    assert RecruiterMediaFile.objects.count() == 0


def test_the_phone_section_holds_at_most_twelve_clips():
    content = with_clips(None, {"video": "https://example.com/fake.webm"})
    content["phone"]["clips"] = content["phone"]["clips"] * 13
    found = problems(content)
    assert found and found[0].startswith("phone/clips:")


def test_one_row_per_insert_and_a_reload_rewrites_only_what_changed(tmp_path):
    from django.db import connection
    from django.test.utils import CaptureQueriesContext

    content = with_clips(dict(SECTION_FILES), dict(PHONE_FILES))
    with CaptureQueriesContext(connection) as queries:
        said = load(tmp_path / "a", content, ALL_FILES)
    inserts = [q for q in queries if q["sql"].startswith('INSERT INTO "demo_recruitermediafile"')]
    assert len(inserts) == 6
    total = sum(len(ALL_FILES[name]) for name in ALL_FILES)
    assert f"{total} bytes stored in all; 6 written this time" in said
    before = dict(RecruiterMediaFile.objects.values_list("name", "pk"))

    said = load(tmp_path / "b", content, ALL_FILES)  # a text-only reload
    assert f"{total} bytes stored in all; 0 written this time (0 bytes)" in said
    assert dict(RecruiterMediaFile.objects.values_list("name", "pk")) == before

    changed = dict(ALL_FILES) | {"demo-take.mp4": FAKE_MP4 + b"recut"}
    said = load(tmp_path / "c", content, changed)
    assert f"1 written this time ({len(FAKE_MP4) + 5} bytes)" in said
    after = dict(RecruiterMediaFile.objects.values_list("name", "pk"))
    assert after["demo-take.mp4"] != before["demo-take.mp4"]
    assert {k: v for k, v in after.items() if k != "demo-take.mp4"} == {
        k: v for k, v in before.items() if k != "demo-take.mp4"
    }
    stored = RecruiterMediaFile.objects.get(name="demo-take.mp4")
    assert bytes(stored.data) == FAKE_MP4 + b"recut"


def test_file_named_videos_carry_a_rollback_warning_and_links_do_not(tmp_path):
    said = load(tmp_path / "a", with_clips(dict(SECTION_FILES), dict(PHONE_FILES)), ALL_FILES)
    assert (
        "WARNING: clip videos named by file (project_sections/0/media/video, "
        "phone/clips/0/video)" in said
    )
    assert "reload the content with link-form clips or without them" in said
    said = load(tmp_path / "b", with_clips({"video": "https://example.com/fake.mp4"}), {})
    assert "WARNING" not in said


@pytest.mark.parametrize("header", ["bytes=" + "9" * 5000 + "-", "bytes=0-" + "9" * 5000])
def test_a_range_with_absurd_digits_is_416_not_a_500(clip, header):
    got = clip.get(URL, HTTP_RANGE=header)
    assert got.status_code == 416
    assert got["Content-Range"] == f"bytes */{len(FAKE_MP4)}"
