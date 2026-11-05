"""The household's book library (map alfred-builds, ticket 06, engine half).

Upload, list, the licence note required, EPUB only, the size cap, a duplicate answered once,
tenancy, the signed download link, delete. The cross-household leak test is also named in
`tests/test_tenancy.py` beside the other books tables.
"""

from __future__ import annotations

import io
import zipfile

import pytest
from django.core.files.uploadedfile import SimpleUploadedFile
from django.db import IntegrityError, transaction
from rest_framework.test import APIClient

from books import library
from books.models import LibraryBook

pytestmark = pytest.mark.django_db

URL = "/api/books/library"
GUTENBERG = "https://www.gutenberg.org/ebooks/345"


def epub(text: str = "It was a dark night.") -> bytes:
    buf = io.BytesIO()
    when = (2020, 1, 1, 0, 0, 0)  # fixed, so the same text is the same bytes (and sha256)
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr(zipfile.ZipInfo("mimetype", when), "application/epub+zip",
                   compress_type=zipfile.ZIP_STORED)
        z.writestr(zipfile.ZipInfo("OEBPS/ch1.xhtml", when),
                   f"<html><body><p>{text}</p></body></html>")
    return buf.getvalue()


def body(blob: bytes | None = None, **overrides) -> dict:
    data = {
        "file": SimpleUploadedFile("dracula.epub", blob if blob is not None else epub(),
                                   content_type="application/epub+zip"),
        "title": "Dracula",
        "author": "Bram Stoker",
        "licence_note": "public domain (Project Gutenberg)",
        "source_url": GUTENBERG,
    }
    data.update(overrides)
    return data


def test_upload_then_list_without_the_bytes(token_a, household_a):
    response = token_a.post(URL, body(), format="multipart")
    assert response.status_code == 201, response.data
    assert response.data["title"] == "Dracula"
    assert response.data["licence_note"] == "public domain (Project Gutenberg)"
    assert response.data["source_url"] == GUTENBERG
    assert response.data["format"] == "epub"
    assert "household" not in response.data and "content" not in response.data
    row = LibraryBook.objects.get()
    assert row.household_id == household_a.id
    assert bytes(row.content) == epub()

    listed = token_a.get(URL).data["results"]
    assert [b["title"] for b in listed] == ["Dracula"]
    assert listed[0]["added_by"]


def test_licence_note_is_required_in_words(token_a):
    for value in (None, "", "   "):
        data = body()
        if value is None:
            data.pop("licence_note")
        else:
            data["licence_note"] = value
        response = token_a.post(URL, data, format="multipart")
        assert response.status_code == 400, value
        assert "licence note" in response.data["detail"]
    assert LibraryBook.objects.count() == 0


def test_the_database_refuses_an_empty_licence_too(household_a):
    with pytest.raises(IntegrityError), transaction.atomic():
        LibraryBook.objects.create(household=household_a, title="X", sha256="a" * 64, size=1,
                                   source_url=GUTENBERG, licence_note="  ", content=b"x")


def test_only_epub_goes_in(token_a):
    for blob in (b"%PDF-1.4 not an epub", b"PK\x03\x04 broken zip"):
        response = token_a.post(URL, body(blob), format="multipart")
        assert response.status_code == 400
        assert "Only EPUB" in response.data["detail"]
    plain_zip = io.BytesIO()
    with zipfile.ZipFile(plain_zip, "w") as z:
        z.writestr("readme.txt", "hi")
    assert token_a.post(URL, body(plain_zip.getvalue()), format="multipart").status_code == 400
    assert LibraryBook.objects.count() == 0


def test_the_source_must_be_a_url(token_a):
    response = token_a.post(URL, body(source_url="my friend"), format="multipart")
    assert response.status_code == 400


def test_over_the_cap_is_refused(token_a, monkeypatch):
    monkeypatch.setattr(library, "MAX_BYTES", 10)
    response = token_a.post(URL, body(), format="multipart")
    assert response.status_code == 413
    assert LibraryBook.objects.count() == 0


def test_the_same_file_twice_is_one_row(token_a):
    first = token_a.post(URL, body(), format="multipart")
    second = token_a.post(URL, body(), format="multipart")
    assert (first.status_code, second.status_code) == (201, 200)
    assert first.data["id"] == second.data["id"]
    assert LibraryBook.objects.count() == 1


def test_another_household_sees_nothing_and_cannot_download_or_delete(token_a, token_b):
    book_id = token_a.post(URL, body(), format="multipart").data["id"]
    assert token_b.get(URL).data["results"] == []
    assert token_b.get(f"{URL}/{book_id}/download").status_code == 404
    assert token_b.delete(f"{URL}/{book_id}").status_code == 404
    assert LibraryBook.objects.count() == 1
    # The same file in the other household is its own row.
    assert token_b.post(URL, body(), format="multipart").status_code == 201
    assert LibraryBook.objects.count() == 2


def test_signed_download_link_serves_the_file_and_dies(token_a, monkeypatch):
    book_id = token_a.post(URL, body(), format="multipart").data["id"]
    link = token_a.get(f"{URL}/{book_id}/download")
    assert link.status_code == 200
    assert link.data["expires_in"] == 900
    url = link.data["url"]
    response = APIClient().get(url)  # no token header
    assert response.status_code == 200
    assert response["Content-Type"] == "application/epub+zip"
    assert response.content == epub()

    assert APIClient().get(url.replace("t=", "t=x")).status_code == 404
    other = url.replace(f"/{book_id}/file", f"/{book_id + 1}/file")
    assert APIClient().get(other).status_code == 404
    monkeypatch.setattr(library, "LINK_SECONDS", -1)
    assert APIClient().get(url).status_code == 404


def test_delete_takes_it_out(token_a):
    book_id = token_a.post(URL, body(), format="multipart").data["id"]
    assert token_a.delete(f"{URL}/{book_id}").status_code == 204
    assert token_a.get(URL).data["results"] == []
    assert token_a.delete(f"{URL}/{book_id}").status_code == 404


def test_unauthenticated_is_refused(db):
    assert APIClient().get(URL).status_code == 401
    assert APIClient().post(URL, body(), format="multipart").status_code == 401


def test_since_filters_and_refuses_garbage(token_a):
    token_a.post(URL, body(), format="multipart")
    assert token_a.get(URL, {"since": "2999-01-01T00:00:00Z"}).data["results"] == []
    assert len(token_a.get(URL, {"since": "2000-01-01T00:00:00Z"}).data["results"]) == 1
    assert token_a.get(URL, {"since": "soon"}).status_code == 400
