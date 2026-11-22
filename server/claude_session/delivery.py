"""Where a free-text task's result goes, once its machine is known (2026-11-22).

A request for a plan "as a PDF" (2026-11-22) was sent with
`deliver: email_self` to a laptop that has no mail account, so the summary said "nothing was
emailed", the file stayed on the laptop and nothing reached the phone. Two rules, applied where
the machine is fixed (create with a machine, assign, claim), never on a workflow run:

- **No mail where none can be sent.** `email_self` to a machine whose worker reports
  `can_mail: false` becomes `files` (ADR 0080: to the phone) on a `full` machine, and nothing on a
  `listed` one (it returns summaries only, ruling 5). Unknown (`can_mail` null, a worker from
  before the field) keeps the mail as asked.
- **Asking for something MADE as a file is asking for the file.** "as a PDF", "into a
  spreadsheet", "make me a PDF of ..." with no delivery of its own becomes `files` on a `full`
  machine, so the made file is requested at finish and opens on his phone. Finding an existing
  file ("find the AW4 wiring PDF") is not this: it keeps the found-file offer (send on his yes).
"""

from __future__ import annotations

import re

from claude_session.models import (
    DELIVER_EMAIL_SELF,
    DELIVER_FILES,
    KIND_ASK,
    SCOPE_LISTED,
    ClaudeMachine,
)

_FORMAT = (
    r"(?:pdf|excel(?:\s+(?:file|sheet|spreadsheet|workbook))?|spreadsheet|workbook|xlsx|csv"
    r"|word\s+(?:doc|document|file)|docx|document)"
)
# "... as a PDF", "into a spreadsheet", "as an Excel file".
_AS_FORMAT = re.compile(rf"\b(?:as|into)\s+(?:an?\s+)?{_FORMAT}\b", re.I)
# "make me a PDF of ...", "create an Excel sheet with ...": a making verb, then "a/an <format>".
_MAKE_FORMAT = re.compile(
    rf"\b(?:make|create|write|draft|build|generate|export|turn|save|convert|put\s+together)\b"
    rf"[^.?!]{{0,80}}?\ban?\s+{_FORMAT}\b",
    re.I,
)


def asks_for_made_file(request: str) -> bool:
    """True when the words ask for the result itself as a file (PDF, sheet, document)."""
    text = request or ""
    return bool(_AS_FORMAT.search(text) or _MAKE_FORMAT.search(text))


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
    return deliver
