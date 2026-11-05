"""`manage.py load_recruiter_diagrams <folder> [--for-code linkedin] [--project midcon]`.

arch-diagrams ticket 01 (Kevin, 2026-11-05). `<folder>` is Kevin's private diagrams folder,
outside the repo: `<folder>/<project>/<view>.mmd`, each rendered beside itself by
`python tools/render_diagrams.py --private <folder>`, which also writes the project's
`manifest.json`. This reads each manifest and its SVGs and replaces that project's rows for the
version: `current`, or a demo code's label with `--for-code`.

Refuses in words and loads nothing for a project when anything in it is wrong: a view not in
`diagrams.catalogue.VIEWS`, an SVG missing, unsafe or stale against its `.mmd` (rendered from an
older source). **Never loads a project the version withholds** (`catalogue.WITHHELD`: no Quanex
for LinkedIn); it says so and skips it.
"""

from __future__ import annotations

import json
from pathlib import Path

from django.core.management.base import BaseCommand, CommandError
from django.db import transaction

from diagrams import render
from diagrams.catalogue import PRIVATE_PROJECTS, VIEWS, WITHHELD
from diagrams.models import CURRENT_KEY, RecruiterDiagram


def read_project(folder: Path, project: str) -> list[dict]:
    """The checked views of one project folder, or CommandError naming every problem."""
    manifest_path = folder / project / render.MANIFEST
    if not manifest_path.is_file():
        raise CommandError(
            f"{manifest_path} is missing. Run: python tools/render_diagrams.py --private {folder}"
        )
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    problems, views = [], []
    for entry in manifest.get("views", []):
        view = entry.get("view", "")
        where = f"{project}/{view}"
        if view not in VIEWS[project]:
            problems.append(f"{where}: not a {project} view ({', '.join(VIEWS[project])})")
            continue
        mmd = folder / project / f"{view}.mmd"
        svg_path = folder / project / entry.get("svg_file", f"{view}.svg")
        if not mmd.is_file() or not svg_path.is_file():
            problems.append(f"{where}: its .mmd or .svg is missing")
            continue
        if render.source_hash(mmd) != entry.get("source_sha256"):
            problems.append(f"{where}: the .mmd changed since it was rendered; re-render it")
            continue
        svg = svg_path.read_text(encoding="utf-8")
        try:
            render.check_svg(svg, where)
        except render.DiagramError as exc:
            problems.append(str(exc))
            continue
        title, caption = render.read_meta(mmd)
        if not entry.get("nodes"):
            problems.append(f"{where}: no nodes in the manifest")
            continue
        views.append(
            {
                "view": view,
                "title": title,
                "caption": caption,
                "nodes": entry["nodes"],
                "svg": svg,
                "source_sha256": entry["source_sha256"],
            }
        )
    if problems:
        raise CommandError(f"Nothing loaded for {project}:\n  " + "\n  ".join(problems))
    if not views:
        raise CommandError(f"Nothing loaded for {project}: its manifest lists no view.")
    return views


class Command(BaseCommand):
    help = "Load the private projects' rendered diagrams (Midcon, Quanex) from a private folder."

    def add_arguments(self, parser):
        parser.add_argument("folder", type=Path)
        parser.add_argument(
            "--for-code",
            default="",
            help="A demo code's label (e.g. linkedin): load these as that code's own version.",
        )
        parser.add_argument(
            "--project",
            action="append",
            choices=PRIVATE_PROJECTS,
            help="Load only this project (repeatable). Default: every project folder present.",
        )

    def handle(self, *args, **options):
        folder: Path = options["folder"]
        if not folder.is_dir():
            raise CommandError(f"{folder} is not a folder.")
        key = options["for_code"].strip().lower() or CURRENT_KEY
        withheld = WITHHELD.get(key, frozenset())
        projects = options["project"] or [p for p in PRIVATE_PROJECTS if (folder / p).is_dir()]
        if not projects:
            raise CommandError(f"{folder} holds none of: {', '.join(PRIVATE_PROJECTS)}.")
        for project in projects:
            if project in withheld:
                deleted, _ = RecruiterDiagram.objects.filter(key=key, project=project).delete()
                self.stdout.write(
                    f"{project}: never loaded for the {key} version (withheld); skipped"
                    + (f", and {deleted} old row(s) removed." if deleted else ".")
                )
                continue
            views = read_project(folder, project)
            with transaction.atomic():
                RecruiterDiagram.objects.filter(key=key, project=project).delete()
                for v in views:
                    RecruiterDiagram.objects.create(key=key, project=project, **v)
            names = ", ".join(v["view"] for v in views)
            self.stdout.write(f"{project}: loaded {len(views)} view(s) for {key}: {names}.")
