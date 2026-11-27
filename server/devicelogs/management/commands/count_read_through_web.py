"""Count stored web conversation records that still hold third-party text (ticket #475).

Read only. A record written before the engine stopped storing the assistant's words, tool
arguments and tool replies (`devicelogs.web_conversation`, module doc) may hold them, and in a
record whose conversation read outside text (a read-through tool, a procedure that reads one such
as `morning_briefing`, or a `read_through` line) those can be text other people wrote. Whether to
rewrite or delete them is the owner's call; this prints counts so he can make it, never a word of
any record. His own lines are never counted.

    manage.py count_read_through_web
"""

from __future__ import annotations

import json
import re
from pathlib import Path

from django.core.management.base import BaseCommand

from agents.legion.redact import reads_outside
from devicelogs.models import DEVICE_LABEL_WEB, REASON_CONVERSATION, DeviceLog
from devicelogs.views import inflate_capped
from devicelogs.web_conversation import lines_of

_BUILTIN_DIRS = ("procedures/builtin", "demo/procedures")


def _reads_outside_steps(definition) -> bool:
    steps = definition.get("steps") if isinstance(definition, dict) else None
    return any(isinstance(s, dict) and reads_outside(s.get("tool") or "") for s in steps or ())


def outside_procedures() -> frozenset[str]:
    """Names of procedures, any household's and any version, whose steps call a read-through
    tool (`agents.legion.redact.reads_outside`), plus the built-in files (a record may predate
    a version's row)."""
    from procedures.loader import read_definition
    from procedures.models import ProcedureVersion

    names = {
        name
        for name, definition in ProcedureVersion.objects.values_list(
            "procedure__name", "definition"
        )
        if _reads_outside_steps(definition)
    }
    root = Path(__file__).resolve().parents[3]
    for folder in _BUILTIN_DIRS:
        for path in sorted((root / folder).glob("*.yaml")):
            definition, _ = read_definition(path)
            if _reads_outside_steps(definition):
                names.add(definition["name"])
    return frozenset(names)


_AUDIT = re.compile(r"^\S+ \S+\s+\d+\s+\d+ [VDIWEF] LIVE_AUDIT: (?P<msg>.*)$")
_NAME = re.compile(r" name=(\S+)")
# Free text an old record may hold: the assistant's words, a tool's arguments, a tool's reply.
_FREE_TEXT = re.compile(
    r"^(?:assistant(?:_replay)? text=|tool_call .* args=|tool_result .* response=)"
)


def _reads_outside(msg: str, procedures: frozenset[str]) -> bool:
    if msg.startswith("read_through "):
        return True
    if not msg.startswith("tool_call "):
        return False
    found = _NAME.search(msg)
    name = found.group(1) if found else ""
    if reads_outside(name):
        return True
    if name != "run_procedure":
        return False
    try:
        args = json.loads(msg.partition(" args=")[2])
    except ValueError:
        return False
    return isinstance(args, dict) and args.get("name") in procedures


def count() -> tuple[int, int, int, int]:
    """(records scanned, records holding any free text, records holding free text whose
    conversation read outside text, the free-text lines in those)."""
    procedures = outside_procedures()
    scanned = old = affected = lines = 0
    # Members' records and recruiter visits' (no household) alike.
    rows = DeviceLog.objects.filter(device_label=DEVICE_LABEL_WEB, reason=REASON_CONVERSATION)
    for body_gz in rows.values_list("body_gz", flat=True).iterator():
        scanned += 1
        body = inflate_capped(bytes(body_gz)).decode("utf-8", errors="replace")
        msgs = [m.group("msg") for m in map(_AUDIT.match, lines_of(body)) if m]
        free = sum(1 for msg in msgs if _FREE_TEXT.match(msg))
        if not free:
            continue
        old += 1
        if any(_reads_outside(msg, procedures) for msg in msgs):
            affected += 1
            lines += free
    return scanned, old, affected, lines


class Command(BaseCommand):
    help = "Count web conversation records still holding third-party text. Read only."

    def handle(self, *args, **options):
        scanned, old, affected, lines = count()
        self.stdout.write(
            f"web conversation records: {scanned} scanned, {old} hold assistant words, tool "
            f"arguments or replies, {affected} of them read outside text ({lines} such lines). "
            f"Nothing was changed."
        )
