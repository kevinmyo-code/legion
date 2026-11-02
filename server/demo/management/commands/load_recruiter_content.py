"""`manage.py load_recruiter_content <dir>` (recruiter-demo tickets 07 and 10).

Reads `<dir>/content.json` (required), `<dir>/report.html` and
`<dir>/resume.pdf` (each optional), checks content.json against
`demo.content.CONTENT_SCHEMA`, and replaces the engine's recruiter content
with it. `<dir>` is Kevin's private folder outside the repo: never commit real
content or a resume. Refuses in words, naming every path that does not fit,
and loads nothing when anything is wrong.
"""

from __future__ import annotations

from pathlib import Path

from django.core.management.base import BaseCommand, CommandError

from demo.content import ASSET_FILES, ContentInvalid, load


class Command(BaseCommand):
    help = "Load the recruiter page's content (and its report and resume) from a private folder."

    def add_arguments(self, parser):
        parser.add_argument("folder", type=Path)

    def handle(self, *args, **options):
        try:
            loaded = load(options["folder"])
        except ContentInvalid as exc:
            raise CommandError(str(exc)) from None
        verb = "Created" if loaded.created else "Replaced"
        self.stdout.write(f"{verb} the recruiter content from {options['folder']}.")
        sections = ", ".join(loaded.profile_sections) or "none"
        self.stdout.write(f"Profile sections (about_kevin answers from these): {sections}.")
        for kind, (name, _type) in ASSET_FILES.items():
            size = loaded.files.get(kind)
            if size is None:
                self.stdout.write(f"{name}: not in the folder, so none is served.")
            else:
                self.stdout.write(f"{name}: loaded, {size} bytes.")
