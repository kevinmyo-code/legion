"""PDF to EPUB, text only (books ticket 04, rulings in ticket 03).

**Run as its own process, never imported by a request.** `books/views.py`
starts it as `python books/convert.py <in.pdf> <out.epub>` under a 50 second
timeout, so a PDF that sends MuPDF into a long loop, or crashes it, costs one
child process and a 502 in words, never a gunicorn worker. The view reads ONE
line of JSON from stdout and nothing else; stderr is never read or logged,
because a library's warning text can quote the document.

**Standard library, PyMuPDF and ebooklib only, no Django.** It runs as a
script, so this file's own directory is `sys.path[0]` and nothing under
`server/` is importable from it. The tests import the pure helpers as
`books.convert` directly.

**Why PyMuPDF and not Calibre** (the ticket's primary): Calibre's Debian
package pulls 370 packages and 1105 MB installed into `python:3.13-slim`
(trixie), including Chromium's `libqt6webenginecore6` and a second Python, on
an image of roughly 400 MB - and it could not be run headless anywhere before
production, because this machine has no Docker. The ticket names this exact
fallback for that case. The commit that added this file records the numbers.

What the output is, and is not:

- One XHTML part per 20 PDF pages, named "Pages 1-20" in the table of
  contents. Text only: figures, tables and footnote placement are lost, and
  the phone says so in words every time it opens one (ticket 05).
- Paragraphs are rebuilt from MuPDF's text blocks: lines inside a paragraph
  are joined, a word hyphenated across a line end is rejoined, and a paragraph
  cut by a page break is joined to its other half.
- Running heads and feet (a line in the top or bottom tenth of the page that
  repeats across pages) and bare page numbers are dropped.

The outcome line is one of:

    {"outcome": "ok", "pages": n, "chars": n}
    {"outcome": "scanned", "pages": n, "chars": n}
    {"outcome": "unreadable", "message": "<a sentence saying which>"}
    {"outcome": "failed", "error": "<exception class name only>"}
"""

from __future__ import annotations

import hashlib
import html
import json
import math
import re
import statistics
import sys
from collections import defaultdict
from pathlib import Path

# Ticket 04: "Scanned if the median page has fewer than 200 non-whitespace
# characters." The median, not the mean, so a scanned book with a typed
# copyright page is still a scan, and a novel with blank pages is still text.
SCANNED_MEDIAN_CHARS = 200
PAGES_PER_CHAPTER = 20
# The band at the top and at the bottom of a page where running heads, feet and
# page numbers live. Nothing outside it is ever dropped as a header.
MARGIN_FRACTION = 0.10
# A margin line is a running head when it repeats on at least this share of the
# pages, or at least REPEAT_MIN times inside any REPEAT_WINDOW consecutive
# pages (a per-chapter running head in a long book repeats densely, but on a
# small share of the whole).
REPEAT_SHARE = 0.25
REPEAT_MIN = 3
REPEAT_WINDOW = 12
# A line that ends a sentence. A paragraph ending in anything else is assumed
# to continue in the next block or on the next page.
_TERMINAL = tuple('.!?:;"”’)]…*')
_PAGE_NUMBER = re.compile(
    r"^(page\s+)?(\d{1,4}|[ivxlcdm]{1,8})(\s*(of|/)\s*\d{1,4})?$", re.IGNORECASE
)
_EDGE_NUMBER = re.compile(r"^\s*\d{1,4}\s+|\s+\d{1,4}\s*$")
# Characters XML 1.0 forbids. MuPDF passes control characters through from
# some PDFs, and one of them in an XHTML part makes the whole EPUB unreadable.
_XML_ILLEGAL = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f￾￿]")
_LANG = re.compile(r"^[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8})*$")
_WS = re.compile(r"\s+")

PDF_HEADER = b"%PDF-"


class Unreadable(Exception):
    """The input is not a PDF this can read. The message is the sentence the
    phone shows, so it says which."""


# ---------------------------------------------------------------------------
# Pure helpers (tested without a PDF)
# ---------------------------------------------------------------------------


def non_whitespace_chars(text: str) -> int:
    return len(_WS.sub("", text))


def is_scanned(page_chars: list[int]) -> bool:
    """True when the median page carries fewer than SCANNED_MEDIAN_CHARS
    characters. A document with no pages is not judged here; `open_pdf`
    refuses it as unreadable first."""
    return statistics.median(page_chars) < SCANNED_MEDIAN_CHARS


def join_lines(left: str, right: str) -> str:
    """Join two pieces of one paragraph across a line or page break.

    A word split by a hyphen at the line end is rejoined when the next piece
    starts lowercase ("exam-" + "ple" -> "example"); before an uppercase
    letter the hyphen is real ("Jean-" + "Paul" -> "Jean-Paul"). A soft
    hyphen at the end is always a split.
    """
    left = left.rstrip()
    right = right.lstrip()
    if not left:
        return right
    if not right:
        return left
    if left.endswith("­"):
        return left[:-1] + right
    if left.endswith("-") and len(left) > 1 and left[-2].isalpha():
        if right[0].islower():
            return left[:-1] + right
        return left + right
    return f"{left} {right}"


def ends_sentence(text: str) -> bool:
    return text.rstrip().endswith(_TERMINAL)


def is_page_number(text: str) -> bool:
    return bool(_PAGE_NUMBER.match(text.strip()))


def margin_key(text: str) -> str:
    """What a running head is compared by: case and spacing folded, and a page
    number at either end removed, so "12  THE ROAD" and "THE ROAD 13" match."""
    folded = _WS.sub(" ", text).strip().lower()
    return _EDGE_NUMBER.sub("", folded).strip()


def running_heads(margin_keys_by_page: list[set[str]]) -> set[str]:
    """The margin keys that repeat enough to be running heads or feet."""
    pages_by_key: dict[str, list[int]] = defaultdict(list)
    for index, keys in enumerate(margin_keys_by_page):
        for key in keys:
            if key:
                pages_by_key[key].append(index)
    share_floor = max(REPEAT_MIN, math.ceil(REPEAT_SHARE * len(margin_keys_by_page)))
    heads: set[str] = set()
    for key, pages in pages_by_key.items():
        if len(pages) < REPEAT_MIN:
            continue
        if len(pages) >= share_floor:
            heads.add(key)
            continue
        for start in range(len(pages) - REPEAT_MIN + 1):
            if pages[start + REPEAT_MIN - 1] - pages[start] < REPEAT_WINDOW:
                heads.add(key)
                break
    return heads


def split_block(lines: list[tuple[float, float, str]], page_width: float) -> list[str]:
    """One MuPDF text block to paragraphs. `lines` is (x0, x1, text) in reading
    order. A new paragraph starts after a line that ends a sentence when the
    next line is indented, or the ending line stops well short of the block's
    right edge (the short last line of a paragraph)."""
    lines = [(x0, x1, text) for x0, x1, text in lines if text.strip()]
    if not lines:
        return []
    left = min(x0 for x0, _, _ in lines)
    right = max(x1 for _, x1, _ in lines)
    width = max(right - left, 1.0)
    paragraphs: list[str] = []
    current = lines[0][2]
    previous_x1 = lines[0][1]
    for x0, x1, text in lines[1:]:
        indented = x0 - left > 0.02 * page_width
        short = previous_x1 < right - 0.15 * width
        if ends_sentence(current) and (indented or short):
            paragraphs.append(current.strip())
            current = text
        else:
            current = join_lines(current, text)
        previous_x1 = x1
    paragraphs.append(current.strip())
    return [p for p in paragraphs if p]


def merge_continued(paragraphs: list[tuple[int, str]]) -> list[tuple[int, str]]:
    """Join a paragraph to the one before it when that one did not end a
    sentence and this one starts lowercase - the shape of a paragraph cut by a
    page break or split by MuPDF into two blocks. Keeps the first half's page."""
    merged: list[tuple[int, str]] = []
    for page, text in paragraphs:
        if merged and text[:1].islower() and not ends_sentence(merged[-1][1]):
            first_page, first = merged[-1]
            merged[-1] = (first_page, join_lines(first, text))
        else:
            merged.append((page, text))
    return merged


def chapters(paragraphs: list[tuple[int, str]], page_count: int) -> list[tuple[str, list[str]]]:
    """Group paragraphs into parts of PAGES_PER_CHAPTER pages by the page each
    starts on. A part whose pages held no text is left out."""
    groups: dict[int, list[str]] = defaultdict(list)
    for page, text in paragraphs:
        groups[page // PAGES_PER_CHAPTER].append(text)
    out = []
    for index in sorted(groups):
        first = index * PAGES_PER_CHAPTER + 1
        last = min(first + PAGES_PER_CHAPTER - 1, page_count)
        title = f"Page {first}" if first == last else f"Pages {first}-{last}"
        out.append((title, groups[index]))
    return out


def xml_text(text: str) -> str:
    return html.escape(_XML_ILLEGAL.sub("", text), quote=False)


# ---------------------------------------------------------------------------
# PyMuPDF and ebooklib
# ---------------------------------------------------------------------------


def _pymupdf():
    import pymupdf

    # MuPDF prints repair warnings to stderr by default. Nothing reads them,
    # and they are off so a damaged-but-readable PDF is not noisy for nothing.
    pymupdf.TOOLS.mupdf_display_errors(False)
    pymupdf.TOOLS.mupdf_display_warnings(False)
    return pymupdf


def open_pdf(path: Path):
    with path.open("rb") as handle:
        head = handle.read(1024)
    if PDF_HEADER not in head:
        raise Unreadable("This file is not a PDF: it does not start with a PDF header.")
    pymupdf = _pymupdf()
    try:
        doc = pymupdf.open(str(path), filetype="pdf")
    except Exception as exc:
        raise Unreadable("This PDF is damaged and could not be opened.") from exc
    if doc.needs_pass:
        doc.close()
        raise Unreadable("This PDF is password-protected, so its text cannot be read.")
    if doc.page_count == 0:
        doc.close()
        raise Unreadable("This PDF has no pages.")
    return doc


def page_char_counts(doc) -> list[int]:
    return [non_whitespace_chars(page.get_text("text")) for page in doc]


def _text_flags(pymupdf) -> int:
    # Ligatures are expanded ("fi", not U+FB01), images are left out, and text
    # outside the page's media box is not text a reader ever saw.
    return pymupdf.TEXT_PRESERVE_WHITESPACE | pymupdf.TEXT_MEDIABOX_CLIP


def _page_blocks(page, flags: int):
    """(in_margin, lines) per text block, lines as (x0, x1, text)."""
    height = page.rect.height or 1.0
    top = page.rect.y0 + MARGIN_FRACTION * height
    bottom = page.rect.y1 - MARGIN_FRACTION * height
    for block in page.get_text("dict", flags=flags, sort=True)["blocks"]:
        if block.get("type") != 0:
            continue
        lines = []
        for line in block.get("lines", []):
            text = "".join(span.get("text", "") for span in line.get("spans", []))
            if text.strip():
                x0, _, x1, _ = line["bbox"]
                lines.append((x0, x1, text.rstrip()))
        if lines:
            _, y0, _, y1 = block["bbox"]
            yield (y1 <= top or y0 >= bottom), lines


def extract_paragraphs(doc) -> list[tuple[int, str]]:
    pymupdf = _pymupdf()
    flags = _text_flags(pymupdf)
    pages = [(page.rect.width, list(_page_blocks(page, flags))) for page in doc]

    margin_keys = [
        {margin_key(" ".join(t for _, _, t in lines)) for in_margin, lines in blocks if in_margin}
        for _, blocks in pages
    ]
    heads = running_heads(margin_keys) if len(pages) >= REPEAT_MIN else set()

    paragraphs: list[tuple[int, str]] = []
    for index, (width, blocks) in enumerate(pages):
        for in_margin, lines in blocks:
            if in_margin:
                whole = " ".join(t for _, _, t in lines)
                if is_page_number(whole) or margin_key(whole) in heads:
                    continue
            for paragraph in split_block(lines, width):
                paragraphs.append((index, paragraph))
    return merge_continued(paragraphs)


def _language(doc) -> str:
    """The PDF's own /Lang when it states one. Otherwise "en", which is a
    default and not a detection: the reader's Listen needs a locale its TTS
    can speak, and every book in the household at charting time was English."""
    try:
        kind, value = doc.xref_get_key(doc.pdf_catalog(), "Lang")
    except Exception:
        return "en"
    value = value.strip("()")
    if kind == "string" and _LANG.match(value):
        return value
    return "en"


def write_epub(
    parts: list[tuple[str, list[str]]],
    out_path: Path,
    *,
    title: str,
    language: str,
    identifier: str,
) -> None:
    from ebooklib import epub

    book = epub.EpubBook()
    book.set_identifier(identifier)
    book.set_title(title)
    book.set_language(language)
    items = []
    for number, (part_title, paragraphs) in enumerate(parts, start=1):
        item = epub.EpubHtml(title=part_title, file_name=f"part-{number:03d}.xhtml", lang=language)
        body = "".join(f"<p>{xml_text(p)}</p>" for p in paragraphs)
        head = f"<head><title>{xml_text(part_title)}</title></head>"
        item.content = f"<html>{head}<body>{body}</body></html>"
        book.add_item(item)
        items.append(item)
    book.toc = items
    book.add_item(epub.EpubNcx())
    book.add_item(epub.EpubNav())
    book.spine = items
    epub.write_epub(str(out_path), book)


def convert(in_path: Path, out_path: Path) -> dict:
    doc = open_pdf(in_path)
    try:
        counts = page_char_counts(doc)
        pages = doc.page_count
        chars = sum(counts)
        if is_scanned(counts):
            return {"outcome": "scanned", "pages": pages, "chars": chars}
        paragraphs = extract_paragraphs(doc)
        if not paragraphs:
            # Text was found, and all of it sat in the margins as headers or
            # page numbers. There is nothing to refit, which is not a scan.
            return {"outcome": "failed", "error": "NoBodyText", "pages": pages, "chars": chars}
        title = (doc.metadata or {}).get("title", "").strip() or "Converted PDF"
        digest = hashlib.sha256(in_path.read_bytes()).hexdigest()
        write_epub(
            chapters(paragraphs, pages),
            out_path,
            title=title,
            language=_language(doc),
            identifier=f"urn:sha256:{digest}",
        )
        return {"outcome": "ok", "pages": pages, "chars": chars}
    finally:
        doc.close()


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(json.dumps({"outcome": "failed", "error": "Usage"}))
        return 2
    try:
        result = convert(Path(argv[0]), Path(argv[1]))
    except Unreadable as exc:
        result = {"outcome": "unreadable", "message": str(exc)}
    except Exception as exc:  # reported by class name, never by message
        print(json.dumps({"outcome": "failed", "error": type(exc).__name__}))
        return 1
    print(json.dumps(result))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
