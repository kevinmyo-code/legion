# ruff: noqa: F811 - fixtures imported from the recruiter content and media tests
"""The recruiter page's media files served from a bucket instead of Postgres (Supabase egress
cut 4, 2026-11-23).

Every window of an MP4 used to be read back out of a bytea column, which left Postgres
hex-encoded and cost about twice the file's size in database egress. With
`LEGION_DEMO_MEDIA_BUCKET` set the loader puts the bytes in that bucket, the row names the
object, and the route reads the window from the bucket. The column is still written this release,
so a rollback or an unreachable bucket still serves the page.

The store is faked here: nothing in these tests talks to Google, and `demo.media_store` never
imports Cloud Storage unless a real bucket is set. Every name and byte below is invented.
"""

from __future__ import annotations

from io import StringIO

import pytest
from django.core.management import call_command
from rest_framework.test import APIClient

from demo import media_store
from demo.models import RecruiterContent, RecruiterMediaFile
from tests.test_recruiter_content import (  # noqa: F401 - fixtures
    _enter,
    _fresh_throttle,
    fake_content,
    fake_projects,
    load_fake,
    plain_member,
    recruiter,
    write_folder,
)
from tests.test_recruiter_media import (  # noqa: F401 - fixtures and makers
    FAKE_GLB,
    FAKE_PNG,
    GADGET,
    load_with_files,
    with_models,
)

pytestmark = pytest.mark.django_db

def not_the_bucket(size: int) -> bytes:
    """Bytes of the right LENGTH and the wrong content, for the fallback column. The length
    matters: the route still reads each file's size with `octet_length` in Postgres, which is an
    integer over the wire and no egress, so the column has to stay the right size while it
    exists."""
    return b"\x7f" * size


class FakeStore:
    """A bucket that keeps what it is given in memory and says what was asked of it."""

    def __init__(self, refuses: bool = False):
        self.objects: dict[str, tuple[bytes, str]] = {}
        self.refuses = refuses
        self.puts: list[str] = []
        self.ranges: list[tuple[str, int, int]] = []

    def put(self, key: str, blob: bytes, content_type: str) -> None:
        if self.refuses:
            raise media_store.MediaStoreError("the bucket refused the upload (fake).")
        self.puts.append(key)
        self.objects[key] = (blob, content_type)

    def get_range(self, key: str, start: int, length: int) -> bytes:
        self.ranges.append((key, start, length))
        if self.refuses:
            raise media_store.MediaStoreError("the bucket refused the read (fake).")
        return self.objects[key][0][start : start + length]


@pytest.fixture
def store(monkeypatch) -> FakeStore:
    fake = FakeStore()
    monkeypatch.setattr(media_store, "get_store", lambda: fake)
    return fake


def _loaded(tmp_path) -> str:
    return load_with_files(
        tmp_path, with_models(GADGET), {"gadget.glb": FAKE_GLB, "gadget.png": FAKE_PNG}
    )


def _only_in_the_bucket(name: str = "gadget.glb") -> RecruiterMediaFile:
    """Make the column's content wrong, so any byte read out of Postgres shows up in the body."""
    row = RecruiterMediaFile.objects.get(name=name)
    RecruiterMediaFile.objects.filter(pk=row.pk).update(data=not_the_bucket(len(bytes(row.data))))
    return row


# =============================================================================
# Loading
# =============================================================================


def test_the_loader_puts_the_bytes_in_the_bucket_and_the_row_names_the_object(store, tmp_path):
    said = _loaded(tmp_path)
    model = RecruiterMediaFile.objects.get(name="gadget.glb")
    assert model.object_name == media_store.object_key(model.sha256, "gadget.glb")
    assert store.objects[model.object_name] == (FAKE_GLB, "model/gltf-binary")
    assert sorted(store.puts) == sorted(
        RecruiterMediaFile.objects.values_list("object_name", flat=True)
    )
    # Still in the column as well: the fallback this release leans on.
    assert bytes(model.data) == FAKE_GLB
    assert "2 of them put in the media bucket" in said


def test_with_no_bucket_the_row_names_no_object_and_the_loader_says_so(tmp_path, monkeypatch):
    monkeypatch.setattr(media_store, "get_store", lambda: None)
    said = _loaded(tmp_path)
    assert list(RecruiterMediaFile.objects.values_list("object_name", flat=True)) == ["", ""]
    assert "No media bucket is set" in said


def test_a_reload_moves_files_that_were_stored_before_the_bucket_existed(tmp_path, monkeypatch):
    """The sha256 of an unchanged file is unchanged, so the loader leaves its row alone. Without
    this the files loaded before the bucket was set would be read out of Postgres for ever."""
    monkeypatch.setattr(media_store, "get_store", lambda: None)
    _loaded(tmp_path / "first")
    before = dict(RecruiterMediaFile.objects.values_list("name", "pk"))

    fake = FakeStore()
    monkeypatch.setattr(media_store, "get_store", lambda: fake)
    _loaded(tmp_path / "second")

    model = RecruiterMediaFile.objects.get(name="gadget.glb")
    assert model.object_name == media_store.object_key(model.sha256, "gadget.glb")
    assert fake.objects[model.object_name][0] == FAKE_GLB
    # Moved, not rewritten: the megabytes in Postgres are left where they are.
    assert dict(RecruiterMediaFile.objects.values_list("name", "pk")) == before


def test_a_second_reload_of_the_same_files_uploads_nothing(store, tmp_path):
    _loaded(tmp_path / "first")
    store.puts.clear()
    said = _loaded(tmp_path / "second")
    assert store.puts == []
    assert "nothing was uploaded" in said


# =============================================================================
# Serving
# =============================================================================


def test_a_visit_reads_the_file_from_the_bucket_and_never_from_postgres(store, recruiter, tmp_path):
    _loaded(tmp_path)
    model = _only_in_the_bucket()
    got = recruiter.get("/api/demo/media/gadget.glb")
    assert got.status_code == 200
    assert got.content == FAKE_GLB
    assert got["Content-Type"] == "model/gltf-binary"
    assert got["ETag"] == f'"{model.sha256}"'
    assert got["Cache-Control"] == "private, no-cache"
    assert store.ranges == [(model.object_name, 0, len(FAKE_GLB))]


def test_a_range_is_asked_of_the_bucket_as_a_range(store, recruiter, tmp_path):
    _loaded(tmp_path)
    model = _only_in_the_bucket()
    got = recruiter.get("/api/demo/media/gadget.glb", HTTP_RANGE="bytes=4-11")
    assert got.status_code == 206
    assert got.content == FAKE_GLB[4:12]
    assert got["Content-Range"] == f"bytes 4-11/{len(FAKE_GLB)}"
    assert store.ranges == [(model.object_name, 4, 8)]


def test_a_304_asks_the_bucket_for_nothing(store, recruiter, tmp_path):
    _loaded(tmp_path)
    model = _only_in_the_bucket()
    again = recruiter.get("/api/demo/media/gadget.glb", HTTP_IF_NONE_MATCH=f'"{model.sha256}"')
    assert again.status_code == 304
    assert again.content == b""
    assert store.ranges == []


def test_a_caller_with_no_visit_is_refused_and_the_bucket_is_never_touched(
    store, plain_member, tmp_path
):
    _loaded(tmp_path)
    _only_in_the_bucket()
    assert APIClient().get("/api/demo/media/gadget.glb").status_code == 403
    assert plain_member.get("/api/demo/media/gadget.glb").status_code == 403
    assert store.ranges == []


def test_a_file_that_names_no_object_still_comes_out_of_postgres(store, recruiter, tmp_path):
    _loaded(tmp_path)
    RecruiterMediaFile.objects.filter(name="gadget.glb").update(object_name="")
    got = recruiter.get("/api/demo/media/gadget.glb")
    assert got.status_code == 200
    assert got.content == FAKE_GLB
    assert store.ranges == []


def test_a_bucket_that_refuses_falls_back_to_the_column(recruiter, tmp_path, monkeypatch):
    """The column is still written this release, so a missing grant costs a slow page, not a
    broken one."""
    fake = FakeStore()
    monkeypatch.setattr(media_store, "get_store", lambda: fake)
    _loaded(tmp_path)
    fake.refuses = True
    got = recruiter.get("/api/demo/media/gadget.glb")
    assert got.status_code == 200
    assert got.content == FAKE_GLB  # from Postgres, which still holds it
    assert len(fake.ranges) == 1


# =============================================================================
# The store itself
# =============================================================================


def test_no_bucket_name_means_no_store_and_no_cloud_storage_import(settings):
    settings.LEGION_DEMO_MEDIA_BUCKET = ""
    assert media_store.get_store() is None
    settings.LEGION_DEMO_MEDIA_BUCKET = "fake-bucket-name"
    assert isinstance(media_store.get_store(), media_store.GcsMediaStore)


def test_an_object_is_named_by_its_own_hash():
    key = media_store.object_key("a" * 64, "gadget.glb")
    assert key == f"{media_store.KEY_PREFIX}/{'a' * 64}/gadget.glb"


def test_the_whole_content_version_is_served_from_the_bucket(store, recruiter, tmp_path):
    """Every file the page names, not only the first: the saving is the whole set."""
    _loaded(tmp_path)
    for name in ("gadget.glb", "gadget.png"):
        _only_in_the_bucket(name)
    assert recruiter.get("/api/demo/media/gadget.glb").content == FAKE_GLB
    assert recruiter.get("/api/demo/media/gadget.png").content == FAKE_PNG
    assert len(store.ranges) == 2
    assert RecruiterContent.current() is not None


def test_the_loader_refuses_to_pretend_a_failed_upload_worked(tmp_path, monkeypatch):
    """A bucket that cannot be written is a loud failure, never a row naming an object that is
    not there."""
    monkeypatch.setattr(media_store, "get_store", lambda: FakeStore(refuses=True))
    with pytest.raises(media_store.MediaStoreError):
        _loaded(tmp_path)
    assert RecruiterMediaFile.objects.count() == 0


def test_the_command_prints_what_it_loaded(store, tmp_path):
    out = StringIO()
    folder = write_folder(tmp_path / "c", with_models(GADGET))
    (folder / "gadget.glb").write_bytes(FAKE_GLB)
    (folder / "gadget.png").write_bytes(FAKE_PNG)
    call_command("load_recruiter_content", str(folder), stdout=out)
    assert "2 media files loaded" in out.getvalue()
