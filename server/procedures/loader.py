"""Load the built-in procedures from `procedures/builtin/*.yaml` (procedures ticket 11, ruling 01).

Runs after every `migrate` (`procedures/apps.py`), so a stranger's clone has the built-ins after
`compose up` with nothing to run by hand. Idempotent: a file whose SHA-256 matches the procedure's
latest version writes nothing, so a second `migrate` writes no row.

- A changed file becomes a NEW version (versions are immutable), made active in place of the old.
- A file whose definition fails a load-time check stops the load with the sentences naming it.
  A broken built-in is a bug in the repo; it must not reach a running engine silently.
- A built-in whose file is gone is deactivated, never deleted: its versions are history.
- The file's stem is the procedure's name, and the definition's `name` must equal it.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from pathlib import Path

import yaml
from django.db import transaction
from django.utils import timezone

from procedures.definition import DefinitionError, validate
from procedures.models import Procedure, ProcedureVersion, Source

BUILTIN_DIR = Path(__file__).resolve().parent / "builtin"


@dataclass
class LoadReport:
    created: list[str] = field(default_factory=list)
    updated: list[str] = field(default_factory=list)
    unchanged: list[str] = field(default_factory=list)
    deactivated: list[str] = field(default_factory=list)


def read_definition(path: Path) -> tuple[dict, str]:
    """(definition, sha256 of the file's bytes). Refuses a file that is not one YAML mapping."""
    raw = path.read_bytes()
    try:
        definition = yaml.safe_load(raw.decode("utf-8"))
    except (UnicodeDecodeError, yaml.YAMLError) as exc:
        raise DefinitionError(path.stem, [f"{path.name} is not readable YAML: {exc}"]) from exc
    if not isinstance(definition, dict):
        raise DefinitionError(path.stem, [f"{path.name} must hold one mapping, a definition."])
    if definition.get("name") != path.stem:
        raise DefinitionError(
            path.stem,
            [
                f"{path.name} names itself {definition.get('name')!r}; "
                "a built-in's name is its file's."
            ],
        )
    return definition, hashlib.sha256(raw).hexdigest()


def load_builtins(directory: Path | None = None) -> LoadReport:
    """Bring the built-in rows in line with the YAML files in `directory`."""
    directory = BUILTIN_DIR if directory is None else directory
    report = LoadReport()
    files = sorted(directory.glob("*.yaml")) if directory.is_dir() else []
    # Validate every file before writing anything: one broken file writes nothing at all.
    loaded = []
    for path in files:
        definition, sha = read_definition(path)
        checked = validate(definition)
        loaded.append((path.stem, definition, sha, checked.writes))

    with transaction.atomic():
        now = timezone.now()
        for name, definition, sha, writes in loaded:
            procedure, made = Procedure.objects.get_or_create(household=None, name=name)
            latest = procedure.versions.order_by("-version").first()
            if latest is not None and latest.content_sha256 == sha:
                if not latest.is_active:
                    # Its file came back after being removed: the same version, active again.
                    ProcedureVersion.objects.filter(pk=latest.pk).update(is_active=True)
                    report.updated.append(name)
                else:
                    report.unchanged.append(name)
                continue
            procedure.versions.filter(is_active=True).update(is_active=False)
            ProcedureVersion.objects.create(
                procedure=procedure,
                household=None,
                version=(latest.version + 1) if latest else 1,
                definition=definition,
                source=Source.BUILTIN,
                content_sha256=sha,
                writes=writes,
                is_active=True,
                approved_at=now,
            )
            (report.created if made else report.updated).append(name)

        present = {name for name, *_ in loaded}
        gone = ProcedureVersion.objects.filter(
            household__isnull=True, source=Source.BUILTIN, is_active=True
        ).exclude(procedure__name__in=present)
        report.deactivated = sorted(gone.values_list("procedure__name", flat=True))
        gone.update(is_active=False)
    return report
