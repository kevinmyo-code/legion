"""Count stored web conversation records the current redaction rule would change (ticket #475).

Read only. Records written before the engine redacted at write time
(`devicelogs.read_through_lines`) may keep a read-through tool's reply, a procedure's reply that
read one (`morning_briefing`), or the lines after one in clear. Whether to rewrite or delete them
is the owner's call; this prints counts so he can make it, never a word of any record. His own
lines are never counted: the rule never changes them.

    manage.py count_read_through_web
"""

from __future__ import annotations

from pathlib import Path

from django.core.management.base import BaseCommand

from agents.legion.redact import reads_outside
from devicelogs.models import DEVICE_LABEL_WEB, REASON_CONVERSATION, DeviceLog
from devicelogs.read_through_lines import redact
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


def count() -> tuple[int, int, int]:
    """(records scanned, records the rule would change, lines it would change)."""
    procedures = outside_procedures()
    scanned = affected = lines = 0
    # Members' records and recruiter visits' (no household) alike.
    rows = DeviceLog.objects.filter(device_label=DEVICE_LABEL_WEB, reason=REASON_CONVERSATION)
    for body_gz in rows.values_list("body_gz", flat=True).iterator():
        scanned += 1
        stored = lines_of(inflate_capped(bytes(body_gz)).decode("utf-8", errors="replace"))
        kept = redact([], [(None, text) for text in stored], procedures=procedures, prints=False)
        changed = sum(1 for text, (_, after) in zip(stored, kept, strict=True) if text != after)
        if changed:
            affected += 1
            lines += changed
    return scanned, affected, lines


class Command(BaseCommand):
    help = "Count web conversation records the read-through redaction would change. Read only."

    def handle(self, *args, **options):
        scanned, affected, lines = count()
        self.stdout.write(
            f"web conversation records: {scanned} scanned, {affected} the current rule would "
            f"redact, {lines} lines in all. Nothing was changed."
        )
