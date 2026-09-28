"""PDF bytes to plain text: the one thin layer between a file and a text parser.

Kept apart from the parsers on purpose (backend-etl ticket 10): the golden tests
feed each parser the SAME text this function produces from the Kotlin suite's
own fixture PDFs, and a separate test pins this function's output for those
PDFs, so a change of extractor shows up as a text diff rather than as a
mysterious parse failure.

**Why pypdf, not pdfplumber** (the ticket named pdfplumber):

- pypdf is pure Python with no dependencies (`py3-none-any` wheel), so the
  `python:3.13-slim` image installs it with no compiler and no system library.
  pdfplumber pulls pdfminer.six, Pillow and pypdfium2 (a native PDFium build).
- Its plain `extract_text()` keeps runs of spaces inside a line the way the
  phone's PdfBox `PDFTextStripper` did. pdfplumber collapses them, and the
  Kotlin card test pins a description with six spaces in it
  (`NORTHWIND OUTFITTERS      Northwind.com/billWA`). On every other BofA
  fixture the two extractors produce the same lines.

Pages are joined with a newline, as `PDFTextStripper` ended each page with a
line separator.
"""
from __future__ import annotations

import io
import logging

# pypdf reports recoverable oddities through `logging` at WARNING. A statement
# is never logged, and neither is a parser's opinion of one.
logging.getLogger("pypdf").setLevel(logging.ERROR)


class UnreadablePdf(Exception):
    """The bytes are not a PDF this extractor can read. Not a refusal of a
    document: a parser treats it as "not mine", so the file falls through."""


def extract_text(content: bytes) -> str:
    """Every page's text, in page order. Raises `UnreadablePdf`."""
    from pypdf import PdfReader

    try:
        reader = PdfReader(io.BytesIO(content))
        return "\n".join((page.extract_text() or "") for page in reader.pages)
    except Exception as exc:  # pypdf raises a wide family for malformed input
        raise UnreadablePdf(type(exc).__name__) from None
