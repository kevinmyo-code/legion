"""`POST /api/books/convert` and `books/convert.py` (books ticket 04).

Three layers:

1. The converter's pure helpers, no PDF and no database: the scan threshold,
   line joining and dehyphenation, page numbers, running heads, paragraphs.
2. The converter on PDFs generated here with PyMuPDF: a text PDF converts and
   is not a scan, an image-only PDF is a scan, a password and a non-PDF are
   unreadable. One test runs it the way the view does, as a child process.
3. The view with the child process replaced, for every row of the ticket's
   status table, the size cap from `Content-Length`, the temporary directory
   being gone on success and on an exception, and a log line that carries no
   title and no text. One end-to-end test runs the real child process.

PyMuPDF is a pinned requirement, so the real conversion runs here and in CI.
It is a different library from the Calibre the ticket named first; why is in
`books/convert.py`. What has NOT run anywhere yet is the converter inside the
deployed image, on Cloud Run's 512 MiB; that is a deploy-time step.
"""

from __future__ import annotations

import io
import logging
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

import pytest
from rest_framework.test import APIClient

from books import convert as conv
from books import views

pymupdf = pytest.importorskip("pymupdf")

URL = "/api/books/convert"
TITLE = "A Title That Must Never Be Logged"
SENTENCE = (
    "The quiet harbour town woke slowly under a pale sky, and the fishing boats rocked "
    "against their moorings while gulls argued over the night's last scraps. "
)


# ---------------------------------------------------------------------------
# Fixtures, generated rather than committed
# ---------------------------------------------------------------------------


def text_pdf(pages: int = 25, *, title: str = TITLE) -> bytes:
    doc = pymupdf.open()
    for number in range(1, pages + 1):
        page = doc.new_page(width=432, height=648)
        page.insert_text((170, 30), "THE HARBOUR", fontsize=9)
        body = SENTENCE * 4 + "\n\n    " + SENTENCE * 2
        if number == 2:
            body = "A word split across lines is morn-\ning. " + body
        page.insert_textbox(pymupdf.Rect(54, 80, 378, 580), body, fontsize=11)
        page.insert_text((210, 625), str(number), fontsize=9)
    doc.set_metadata({"title": title})
    out = doc.tobytes()
    doc.close()
    return out


def image_pdf(pages: int = 3) -> bytes:
    """Pages that are pictures and nothing else: a scan, or sheet music."""
    pixmap = pymupdf.Pixmap(pymupdf.csRGB, pymupdf.IRect(0, 0, 120, 160), False)
    pixmap.clear_with(200)
    png = pixmap.tobytes("png")
    doc = pymupdf.open()
    for _ in range(pages):
        page = doc.new_page(width=432, height=648)
        page.insert_image(page.rect, stream=png)
    out = doc.tobytes()
    doc.close()
    return out


def encrypted_pdf() -> bytes:
    doc = pymupdf.open(stream=text_pdf(2), filetype="pdf")
    buffer = io.BytesIO()
    doc.save(buffer, encryption=pymupdf.PDF_ENCRYPT_AES_256, user_pw="secret", owner_pw="owner")
    doc.close()
    return buffer.getvalue()


def epub_text(blob: bytes) -> str:
    with zipfile.ZipFile(io.BytesIO(blob)) as archive:
        assert archive.namelist()[0] == "mimetype"
        assert archive.read("mimetype") == b"application/epub+zip"
        parts = sorted(n for n in archive.namelist() if "/part-" in n)
        return "\n".join(archive.read(n).decode("utf-8") for n in parts)


# ---------------------------------------------------------------------------
# 1. Pure helpers
# ---------------------------------------------------------------------------


def test_scanned_is_a_median_under_200_characters():
    assert conv.is_scanned([0, 0, 0])
    assert conv.is_scanned([199, 199, 5000])
    assert not conv.is_scanned([200, 200, 0])
    # A novel with blank pages between parts is still text.
    assert not conv.is_scanned([1500, 0, 1500, 1500, 0])


def test_non_whitespace_characters_ignore_every_kind_of_space():
    assert conv.non_whitespace_chars(" a\tb\nc  d ") == 4


def test_lines_join_with_one_space_and_rejoin_a_split_word():
    assert conv.join_lines("the quick", "brown fox") == "the quick brown fox"
    assert conv.join_lines("a morn-", "ing walk") == "a morning walk"
    assert conv.join_lines("Jean-", "Paul came") == "Jean-Paul came"
    assert conv.join_lines("soft­", "ly") == "softly"
    # A dash after a space is punctuation, not a split word.
    assert conv.join_lines("wait -", "no") == "wait - no"


def test_page_numbers_are_recognised_and_words_are_not():
    for text in ["12", " 7 ", "xiv", "Page 3", "3 of 200", "4 / 9"]:
        assert conv.is_page_number(text), text
    for text in ["Chapter 12", "The 12 days", "Index", "1984 was a year"]:
        assert not conv.is_page_number(text), text


def test_margin_keys_fold_page_numbers_at_either_end():
    assert conv.margin_key("12   THE ROAD") == conv.margin_key("THE ROAD 13") == "the road"
    assert conv.margin_key("Part 2 of the book") == "part 2 of the book"


def test_a_running_head_on_a_quarter_of_the_pages_is_dropped():
    keys = [{"the harbour"} if i % 2 else set() for i in range(40)]
    assert conv.running_heads(keys) == {"the harbour"}


def test_a_dense_per_chapter_head_is_dropped_and_a_rare_heading_is_not():
    keys: list[set[str]] = [set() for _ in range(300)]
    for page in range(100, 110, 2):  # five times in ten pages, under 25% overall
        keys[page].add("chapter three: the storm")
    for page in (0, 60, 120):  # three times, far apart: a heading, not a running head
        keys[page].add("interlude")
    assert conv.running_heads(keys) == {"chapter three: the storm"}


def test_a_block_splits_at_an_indent_after_a_full_stop():
    lines = [
        (50.0, 380.0, "The first paragraph runs across"),
        (50.0, 380.0, "two lines and ends here."),
        (70.0, 380.0, "The second starts indented and"),
        (50.0, 380.0, "continues."),
    ]
    assert conv.split_block(lines, 432) == [
        "The first paragraph runs across two lines and ends here.",
        "The second starts indented and continues.",
    ]


def test_a_block_splits_after_a_short_last_line():
    lines = [
        (50.0, 380.0, "A paragraph that fills the line"),
        (50.0, 200.0, "and stops short."),
        (50.0, 380.0, "Next paragraph, flush left."),
    ]
    assert conv.split_block(lines, 432) == [
        "A paragraph that fills the line and stops short.",
        "Next paragraph, flush left.",
    ]


def test_a_paragraph_cut_by_a_page_break_is_joined():
    merged = conv.merge_continued(
        [(0, "It went on and"), (1, "on into the next page."), (1, "New one.")]
    )
    assert merged == [(0, "It went on and on into the next page."), (1, "New one.")]
    assert conv.merge_continued([(0, "Ends here."), (1, "lowercase start.")]) == [
        (0, "Ends here."),
        (1, "lowercase start."),
    ]
    assert conv.merge_continued([(0, "a hyphen-"), (1, "ated word.")]) == [
        (0, "a hyphenated word.")
    ]


def test_chapters_are_twenty_pages_and_empty_ones_are_left_out():
    paragraphs = [(0, "a"), (19, "b"), (20, "c"), (65, "d")]
    assert conv.chapters(paragraphs, 66) == [
        ("Pages 1-20", ["a", "b"]),
        ("Pages 21-40", ["c"]),
        ("Pages 61-66", ["d"]),
    ]
    assert conv.chapters([(20, "x")], 21) == [("Page 21", ["x"])]


def test_xml_text_escapes_and_drops_characters_xml_forbids():
    assert conv.xml_text("a < b & c\x0c\x00d") == "a &lt; b &amp; cd"


# ---------------------------------------------------------------------------
# 2. The converter on real PDFs
# ---------------------------------------------------------------------------


def test_scan_detection_on_a_text_pdf_and_an_image_pdf(tmp_path):
    text_path = tmp_path / "text.pdf"
    text_path.write_bytes(text_pdf(3))
    image_path = tmp_path / "image.pdf"
    image_path.write_bytes(image_pdf(3))

    doc = conv.open_pdf(text_path)
    assert not conv.is_scanned(conv.page_char_counts(doc))
    doc.close()
    doc = conv.open_pdf(image_path)
    assert conv.page_char_counts(doc) == [0, 0, 0]
    doc.close()

    result = conv.convert(image_path, tmp_path / "image.epub")
    assert result == {"outcome": "scanned", "pages": 3, "chars": 0}
    assert not (tmp_path / "image.epub").exists()


def test_a_text_pdf_converts_with_heads_and_page_numbers_gone(tmp_path):
    source = tmp_path / "in.pdf"
    source.write_bytes(text_pdf(25))
    result = conv.convert(source, tmp_path / "out.epub")
    assert result["outcome"] == "ok" and result["pages"] == 25 and result["chars"] > 25 * 200

    text = epub_text((tmp_path / "out.epub").read_bytes())
    assert "The quiet harbour town woke slowly" in text
    assert "morning" in text and "morn-" not in text
    assert "THE HARBOUR" not in text
    assert "<p>7</p>" not in text and "<p>25</p>" not in text
    with zipfile.ZipFile(tmp_path / "out.epub") as archive:
        nav = archive.read("EPUB/nav.xhtml").decode("utf-8")
    assert "Pages 1-20" in nav and "Pages 21-25" in nav


@pytest.mark.parametrize(
    ("blob", "expected"),
    [
        (b"PK\x03\x04 a zip, not a pdf", "does not start with a PDF header"),
        (b"%PDF-1.7\n this is not the rest of a pdf", "damaged"),
    ],
)
def test_a_non_pdf_is_unreadable_in_words(tmp_path, blob, expected):
    source = tmp_path / "in.pdf"
    source.write_bytes(blob)
    with pytest.raises(conv.Unreadable, match=expected):
        conv.convert(source, tmp_path / "out.epub")


def test_a_password_protected_pdf_is_unreadable_in_words(tmp_path):
    source = tmp_path / "in.pdf"
    source.write_bytes(encrypted_pdf())
    with pytest.raises(conv.Unreadable, match="password-protected"):
        conv.convert(source, tmp_path / "out.epub")


def test_the_child_process_prints_one_outcome_line(tmp_path):
    """The way the view runs it: a script, its own process, JSON on stdout."""
    source = tmp_path / "in.pdf"
    source.write_bytes(text_pdf(3))
    out = tmp_path / "out.epub"
    completed = views.run_converter(source, out, tmp_path)
    assert completed.returncode == 0, completed.stderr
    outcome = views._outcome(completed)
    assert outcome["outcome"] == "ok" and outcome["pages"] == 3 and outcome["chars"] > 600
    assert out.is_file()

    source.write_bytes(b"not a pdf at all")
    completed = views.run_converter(source, out, tmp_path)
    assert views._outcome(completed)["outcome"] == "unreadable"


# ---------------------------------------------------------------------------
# 3. The view
# ---------------------------------------------------------------------------


class Recorder:
    """Stands in for `run_converter`: records the call and plays one outcome."""

    def __init__(self, stdout="", returncode=0, epub: bytes | None = None, raises=None):
        self.stdout = stdout
        self.returncode = returncode
        self.epub = epub
        self.raises = raises
        self.calls: list[tuple[Path, Path, Path]] = []

    def __call__(self, in_path: Path, out_path: Path, workdir: Path):
        self.calls.append((in_path, out_path, workdir))
        if self.raises is not None:
            raise self.raises
        if self.epub is not None:
            out_path.write_bytes(self.epub)
        return subprocess.CompletedProcess([], self.returncode, self.stdout.encode(), b"")


@pytest.fixture
def temp_dirs(monkeypatch):
    """Every temporary directory the view opens, so a test can check it is gone."""
    opened: list[Path] = []
    real = tempfile.TemporaryDirectory

    class Tracking(real):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, **kwargs)
            opened.append(Path(self.name))

    monkeypatch.setattr(views.tempfile, "TemporaryDirectory", Tracking)
    return opened


def _post(client, body: bytes, **extra):
    return client.post(URL, data=body, content_type="application/pdf", **extra)


@pytest.mark.django_db
def test_an_unauthenticated_request_is_refused(db):
    assert _post(APIClient(), b"%PDF-1.7").status_code == 401


@pytest.mark.django_db
def test_a_read_scoped_token_is_refused_like_any_post(household_user):
    from household.models import DeviceToken

    _row, raw_key = DeviceToken.issue(household_user, "reader", scope=DeviceToken.SCOPE_READ)
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw_key}")
    assert _post(client, b"%PDF-1.7").status_code == 403


@pytest.mark.django_db
def test_over_the_cap_is_413_from_content_length_before_reading(token_a, monkeypatch, temp_dirs):
    recorder = Recorder()
    monkeypatch.setattr(views, "run_converter", recorder)
    size = int(35.6 * 1024 * 1024)
    response = _post(token_a, b"%PDF-1.7", CONTENT_LENGTH=str(size))
    assert response.status_code == 413
    assert response.json() == {
        "code": "too_big",
        "message": "This PDF is 35.6 MB; the most the server takes is 30 MB.",
    }
    assert recorder.calls == [] and temp_dirs == []


@pytest.mark.django_db
def test_the_cap_is_inclusive(token_a, monkeypatch):
    """At the cap converts, one byte over is refused. The cap is shrunk so the
    boundary can be sent for real."""
    monkeypatch.setattr(views, "MAX_BYTES", 16)
    recorder = Recorder('{"outcome": "scanned", "pages": 1, "chars": 0}')
    monkeypatch.setattr(views, "run_converter", recorder)
    assert _post(token_a, b"%PDF-1.7 16bytes").status_code == 422
    assert len(recorder.calls) == 1
    assert _post(token_a, b"%PDF-1.7 17 bytes").status_code == 413
    assert len(recorder.calls) == 1


@pytest.mark.django_db
def test_an_empty_body_is_unreadable(token_a, monkeypatch):
    recorder = Recorder()
    monkeypatch.setattr(views, "run_converter", recorder)
    response = _post(token_a, b"")
    assert response.status_code == 422
    assert response.json() == {"code": "unreadable", "message": views.EMPTY}
    assert recorder.calls == []


class ShortStream:
    """A body that ends before its `Content-Length`: the client went away. Django's
    test client cannot send one (its payload asserts on over-reads), so the spool
    is driven directly, and the view's handling of a False from it is below."""

    def __init__(self, data: bytes):
        self._data = io.BytesIO(data)

    def read(self, size=-1):
        return self._data.read(size)


class FakeRequest:
    def __init__(self, data: bytes):
        self.stream = ShortStream(data)


def test_the_spool_reports_a_body_shorter_than_promised(tmp_path):
    target = tmp_path / "in.pdf"
    assert views._spool(FakeRequest(b"%PDF-1.7 whole"), target, 14)
    assert target.read_bytes() == b"%PDF-1.7 whole"
    assert not views._spool(FakeRequest(b"%PDF-1.7 short"), target, 5000)


@pytest.mark.django_db
def test_an_upload_cut_short_is_unreadable_and_not_converted(token_a, monkeypatch, temp_dirs):
    recorder = Recorder()
    monkeypatch.setattr(views, "run_converter", recorder)
    monkeypatch.setattr(views, "_spool", lambda request, path, expected: False)
    response = _post(token_a, b"%PDF-1.7 short")
    assert response.status_code == 422
    assert response.json() == {"code": "unreadable", "message": views.CUT_SHORT}
    assert recorder.calls == []
    assert temp_dirs and not any(d.exists() for d in temp_dirs)


@pytest.mark.django_db
def test_converted_is_200_with_the_epub_and_the_page_count(token_a, monkeypatch, temp_dirs):
    pdf = b"%PDF-1.7 the whole body"
    recorder = Recorder('{"outcome": "ok", "pages": 212, "chars": 400000}', epub=b"PK-epub-bytes")
    seen: list[bytes] = []

    def spy(in_path, out_path, workdir):
        seen.append(in_path.read_bytes())
        return recorder(in_path, out_path, workdir)

    monkeypatch.setattr(views, "run_converter", spy)
    response = _post(token_a, pdf, HTTP_ACCEPT="application/epub+zip")
    assert response.status_code == 200
    assert response["Content-Type"] == "application/epub+zip"
    assert response["X-Legion-Pages"] == "212"
    assert response["Cache-Control"] == "no-store"
    assert response.content == b"PK-epub-bytes"
    assert seen == [pdf]
    assert temp_dirs and not any(d.exists() for d in temp_dirs)


@pytest.mark.django_db
@pytest.mark.parametrize(
    ("stdout", "returncode", "status", "code", "message"),
    [
        ('{"outcome": "scanned", "pages": 4, "chars": 0}', 0, 422, "scanned", views.SCANNED),
        (
            '{"outcome": "unreadable", "message": "This PDF is password-protected, so its text '
            'cannot be read."}',
            0,
            422,
            "unreadable",
            "This PDF is password-protected, so its text cannot be read.",
        ),
        (
            '{"outcome": "failed", "error": "NoBodyText", "pages": 2, "chars": 40}',
            0,
            422,
            "unreadable",
            views.NO_BODY_TEXT,
        ),
        ('{"outcome": "failed", "error": "MemoryError"}', 1, 502, "failed", views.FAILED),
        ("", -9, 502, "failed", views.FAILED),
        ("Traceback (most recent call last): garbage", 1, 502, "failed", views.FAILED),
        # "ok" with no file written is not a success.
        ('{"outcome": "ok", "pages": 1, "chars": 900}', 0, 502, "failed", views.FAILED),
    ],
)
def test_each_refusal_row(
    token_a, monkeypatch, temp_dirs, stdout, returncode, status, code, message
):
    monkeypatch.setattr(views, "run_converter", Recorder(stdout, returncode))
    response = _post(token_a, b"%PDF-1.7 body")
    assert response.status_code == status
    assert response.json() == {"code": code, "message": message}
    assert not any(d.exists() for d in temp_dirs)


@pytest.mark.django_db
def test_a_timeout_is_502_in_words(token_a, monkeypatch, temp_dirs):
    timeout = subprocess.TimeoutExpired(cmd="convert", timeout=views.CONVERT_TIMEOUT_SECONDS)
    monkeypatch.setattr(views, "run_converter", Recorder(raises=timeout))
    response = _post(token_a, b"%PDF-1.7 body")
    assert response.status_code == 502
    assert response.json() == {"code": "failed", "message": views.TIMED_OUT}
    assert "50 seconds" in views.TIMED_OUT
    assert temp_dirs and not any(d.exists() for d in temp_dirs)


@pytest.mark.django_db
def test_the_temp_dir_is_gone_after_an_exception(token_a, monkeypatch, temp_dirs):
    monkeypatch.setattr(views, "run_converter", Recorder(raises=RuntimeError("boom")))
    with pytest.raises(RuntimeError):
        _post(token_a, b"%PDF-1.7 body")
    assert temp_dirs and not any(d.exists() for d in temp_dirs)


@pytest.mark.django_db
def test_end_to_end_real_conversion_logs_metadata_only(token_a, temp_dirs, caplog):
    pdf = text_pdf(25)
    with caplog.at_level(logging.WARNING, logger="books.views"):
        response = _post(token_a, pdf)
    assert response.status_code == 200, response.content[:300]
    assert response["X-Legion-Pages"] == "25"
    assert "The quiet harbour town woke slowly" in epub_text(response.content)
    assert not any(d.exists() for d in temp_dirs)

    lines = [r.getMessage() for r in caplog.records if r.name == "books.views"]
    assert len(lines) == 1
    line = lines[0]
    assert "outcome=ok" in line and f"bytes_in={len(pdf)}" in line and "pages=25" in line
    assert "chars=" in line and "seconds=" in line
    for secret in (TITLE, "harbour", "THE HARBOUR", "in.pdf", "out.epub", "legion-book-"):
        assert secret.lower() not in caplog.text.lower(), secret


@pytest.mark.django_db
def test_a_refusal_is_logged_with_its_code(token_a, caplog):
    with caplog.at_level(logging.WARNING, logger="books.views"):
        _post(token_a, b"%PDF-1.7", CONTENT_LENGTH=str(40 * 1024 * 1024))
    assert "outcome=too_big" in caplog.text and f"bytes_in={40 * 1024 * 1024}" in caplog.text


def test_the_child_is_this_interpreter_and_gets_no_secrets(monkeypatch):
    monkeypatch.setenv("SECRET_KEY", "do-not-pass")
    monkeypatch.setenv("DATABASE_URL", "postgres://x")
    env = views._child_env()
    assert "SECRET_KEY" not in env and "DATABASE_URL" not in env
    assert env["PYTHONIOENCODING"] == "utf-8"
    assert views.CONVERTER.is_file() and sys.executable
