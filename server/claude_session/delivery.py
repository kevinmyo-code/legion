"""Where a free-text task's result goes, once its machine is known (2026-11-22).

A request for a plan "as a PDF" (2026-11-22) was sent with
`deliver: email_self` to a laptop that has no mail account, so the summary said "nothing was
emailed", the file stayed on the laptop and nothing reached the phone. Two rules, applied where
the machine is fixed (create with a machine, assign, claim), never on a workflow run:

- **No mail where none can be sent.** `email_self` to a machine whose worker reports
  `can_mail: false` becomes `files` (ADR 0080: to the phone) on a `full` machine, and nothing on a
  `listed` one (it returns summaries only, ruling 5). Unknown (`can_mail` null, a worker from
  before the field) keeps the mail as asked.
- **Asking for something MADE as a file is asking for the file.** A making verb with "as a PDF"
  or "into a spreadsheet", or "make me a PDF of ...", with no delivery of its own becomes `files`
  on a `full` machine, so the made file is requested at finish and opens on his phone. Finding an
  existing file ("find the AW4 wiring PDF", "the lease I saved as a PDF") is not this: it keeps
  the found-file offer (send on his yes). A format word that is the TOPIC ("how to convert a
  Word doc into a PDF", "comparing tools", "later") is not a request for a file either.
- **A page asked for as a PDF is the PDF.** `deliver: phone` (the phone's page=true) becomes
  `files` only on a deliverable phrase: "as a PDF", "make it a PDF", "send me a PDF", "PDF
  version". When unsure it stays a page.
- **Made means made.** For such a files task the engine requests at finish only files directly in
  the task's own work folder (`made_only`, `in_task_folder`), never one found elsewhere.
"""

from __future__ import annotations

import re

from claude_session.models import (
    DELIVER_EMAIL_SELF,
    DELIVER_FILES,
    DELIVER_PHONE,
    KIND_ASK,
    SCOPE_LISTED,
    ClaudeMachine,
)

_FORMAT = (
    r"(?:pdf|excel(?:\s+(?:file|sheet|spreadsheet|workbook))?|spreadsheet|workbook|xlsx|csv"
    r"|word\s+(?:doc|document|file)|docx|document)"
)
# A making verb in its base form, then "as / into / to a <format>" later in the same sentence:
# "make the plan ... as a PDF", "turn it into a spreadsheet", "export the notes to Excel". The
# base form is the point: "the lease I saved as a PDF" or "read the report as a PDF" describe a
# file that exists (review 2026-11-22, F2), and keep the found-file offer.
_MAKING_VERB = (
    r"\b(?:make|create|turn|export|convert|save|put|write|draft|build|generate|format|print)\b"
)
_AS_FORMAT = re.compile(
    rf"{_MAKING_VERB}[^.?!]{{0,80}}?\b(?:as|into)\s+(?:an?\s+)?{_FORMAT}\b"
    # "to a document" reads as "attached to a document": the "to" form takes no bare "document".
    rf"|{_MAKING_VERB}[^.?!]{{0,80}}?\bto\s+(?:an?\s+)?(?!document\b){_FORMAT}\b",
    re.I,
)
# A request that looks for, asks about or checks a file is about one that exists, whatever verb
# it also holds ("where did I save the lease as a PDF?", "check whether I need to save it as a
# PDF", "find the receipts I put into a spreadsheet"; re-review 2026-11-22).
_FINDING = re.compile(
    r"\b(?:find|where|locate|which|whether|check|did\s+i|make\s+sure)\b", re.I
)
# "make me a PDF of ...", "create an Excel sheet with ...": a making verb directly followed by
# "a/an <format>" (one "me" or "us" between at most), never "document": "make a note about a
# document" or "write a summary of a PDF" is about a file that already exists.
_MAKE_FORMAT = re.compile(
    r"\b(?:make|create|generate|build|draft|export)\s+(?:(?:me|us)\s+)?an?\s+"
    r"(?:pdf|excel(?:\s+(?:file|sheet|spreadsheet|workbook))?|spreadsheet|workbook|xlsx"
    r"|word\s+(?:doc|document|file)|docx)\b",
    re.I,
)


# The format word is the TOPIC, not the deliverable: "a page on how to convert a Word doc into a
# PDF", "explaining how to export my notes to Excel", "comparing tools that turn scans into a
# PDF", "I will print it as a PDF later" (RED review 2026-11-22). When unsure, no file.
_TOPIC = re.compile(
    r"\b(?:how\s+(?:to|do|can)|explain\w*|about|compar\w*|later|guides?|tutorials?|tools?"
    r"|ways?\s+to|steps?\s+to|apps?|software|programs?)\b",
    re.I,
)
# What turns a requested PAGE into a PDF: the result itself named as a PDF, nothing looser.
_PDF_DELIVERABLE = re.compile(
    r"\b(?:as|in)\s+(?:an?\s+)?pdf(?:\s+(?:form|format|file))?\b"
    r"|\bmake\s+it\s+(?:a|into\s+a)\s+pdf\b"
    r"|\bsend\s+(?:it|me|them)\b[^.?!]{0,20}?\bpdf\b"
    r"|\bpdf\s+version\b",
    re.I,
)


def asks_for_made_file(request: str) -> bool:
    """True when the words ask for the result itself as a file (PDF, sheet, document)."""
    text = request or ""
    if _FINDING.search(text) or _TOPIC.search(text):
        return False
    return bool(_AS_FORMAT.search(text) or _MAKE_FORMAT.search(text))


def page_wants_pdf(request: str) -> bool:
    """A page request (`deliver: phone`) whose result is named as a PDF ("... as a PDF", "make it
    a PDF", "send me a PDF", "PDF version"); never a page whose subject is a file format."""
    text = request or ""
    if _FINDING.search(text) or _TOPIC.search(text):
        return False
    return bool(_PDF_DELIVERABLE.search(text))


def made_only(task_id: int, request: str) -> bool:
    """A files task that exists because a result was asked for as a file: only what the task made,
    in its own work folder, may go to the phone, never a file "found by name" elsewhere."""
    return asks_for_made_file(request) or page_wants_pdf(request)


def in_task_folder(task_id: int, path: str) -> bool:
    """`path` sits directly in the worker's `work/task-<id>/` folder (the worker's layout)."""
    return re.search(rf"[\\/]task-{int(task_id)}[\\/][^\\/]+$", path or "") is not None


def settle(
    deliver: str | None, request: str, kind: str, machine: ClaudeMachine | None
) -> str | None:
    """The delivery a task keeps once [machine] runs it. Unchanged while no machine is known, for
    a workflow or a memory review (their own delivery stands) and for any other delivery."""
    if machine is None or kind != KIND_ASK:
        return deliver
    listed = machine.scope == SCOPE_LISTED
    if deliver == DELIVER_EMAIL_SELF and machine.can_mail is False:
        return None if listed else DELIVER_FILES
    if deliver is None and not listed and asks_for_made_file(request):
        return DELIVER_FILES
    # A page (`phone`) is HTML; a page asked for "as a PDF" is the PDF (task 32, 2026-11-22: the
    # phone sent page=true, the helper was told to write one HTML page, and no PDF was made).
    if deliver == DELIVER_PHONE and not listed and page_wants_pdf(request):
        return DELIVER_FILES
    return deliver
