"""The demo visit's own morning briefing (`demo/procedures/morning_briefing.yaml`).

Seeded into each recruiter visit's household when the visit is made, as that household's own
procedure, so it shadows the built-in `morning_briefing` (which reads the daily brief, closed to
a visit) for the visit only. Every tool step is in `RECRUITER_TOOLS`; ruling 07 is unchanged:
`listing.recruiter_may_run` still checks it, and `run_procedure` still fences each step.
"""

from __future__ import annotations

from pathlib import Path

from django.utils import timezone

from procedures.definition import validate
from procedures.loader import read_definition
from procedures.models import Procedure, ProcedureVersion, Source

DEMO_PROCEDURES = Path(__file__).resolve().parent / "procedures"


def seed_visit_procedures(household) -> list[str]:
    """Writes the demo procedures into `household` (a visit's own). Idempotent per file hash."""
    made = []
    for path in sorted(DEMO_PROCEDURES.glob("*.yaml")):
        definition, sha = read_definition(path)
        checked = validate(definition)
        procedure, _ = Procedure.objects.get_or_create(household=household, name=path.stem)
        if procedure.versions.filter(content_sha256=sha, is_active=True).exists():
            continue
        latest = procedure.versions.order_by("-version").first()
        procedure.versions.filter(is_active=True).update(is_active=False)
        ProcedureVersion.objects.create(
            procedure=procedure,
            household=household,
            version=(latest.version + 1) if latest else 1,
            definition=definition,
            source=Source.HAND,
            content_sha256=sha,
            writes=checked.writes,
            is_active=True,
            approved_at=timezone.now(),
        )
        made.append(path.stem)
    return made
