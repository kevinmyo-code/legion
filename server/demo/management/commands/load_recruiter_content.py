"""`manage.py load_recruiter_content <dir>` (recruiter-demo tickets 07 and 10).

Reads `<dir>/content.json` (required), `<dir>/report.html` and
`<dir>/resume.pdf` (each optional), checks content.json against
`demo.content.CONTENT_SCHEMA`, and replaces the engine's recruiter content
with it. `<dir>` is Kevin's private folder outside the repo: never commit real
content or a resume. Refuses in words, naming every path that does not fit,
and loads nothing when anything is wrong.

`--for-code linkedin` loads it as the version a named code's visitors read
(`RecruiterContent.for_user`); without it, the full version everyone else reads.

With `LEGION_DEMO_MEDIA_BUCKET` set in the shell that runs this, the media files' bytes go in
that bucket and the page reads them from there (`demo.media_store`, Supabase egress cut 4); the
shell needs credentials that may write it, and `google-cloud-storage` installed. EVERY loaded
version has to be reloaded with it set, `--for-code` ones included, or that version's files keep
coming out of Postgres.
"""

from __future__ import annotations

from pathlib import Path

from django.core.management.base import BaseCommand, CommandError

from demo import media_store
from demo.content import ASSET_FILES, ContentInvalid, load
from demo.models import RecruiterContent


class Command(BaseCommand):
    help = "Load the recruiter page's content (and its report and resume) from a private folder."

    def add_arguments(self, parser):
        parser.add_argument("folder", type=Path)
        parser.add_argument(
            "--for-code",
            default="",
            help="A demo code's label (e.g. linkedin): load this as that code's own version.",
        )

    @staticmethod
    def _bucket_line(loaded) -> str:
        """What the media bucket did, in three distinct sentences: a forgotten
        LEGION_DEMO_MEDIA_BUCKET must never read like a load that had nothing to upload."""
        if media_store.get_store() is None:
            return (
                "NO MEDIA BUCKET IS SET (LEGION_DEMO_MEDIA_BUCKET): the page reads these files "
                "out of Postgres, which is what the egress cut was about. Nothing was uploaded."
            )
        if not loaded.media_to_bucket:
            return "Every file was already in the media bucket: nothing was uploaded."
        return (
            f"{loaded.media_to_bucket} of them put in the media bucket, which is where the page "
            "reads them from."
        )

    def handle(self, *args, **options):
        try:
            key = options["for_code"].strip().lower() or RecruiterContent.CURRENT_KEY
            loaded = load(options["folder"], key=key)
        except ContentInvalid as exc:
            raise CommandError(str(exc)) from None
        except media_store.MediaStoreError as exc:
            raise CommandError(
                f"Nothing was loaded. The media bucket refused a file: {exc} Set "
                "LEGION_DEMO_MEDIA_BUCKET to the right bucket, or unset it to load into Postgres."
            ) from None
        verb = "Created" if loaded.created else "Replaced"
        self.stdout.write(
            f"{verb} the recruiter content ({loaded.row.key}) from {options['folder']}."
        )
        sections = ", ".join(loaded.profile_sections) or "none"
        self.stdout.write(f"Profile sections (about_kevin answers from these): {sections}.")
        for kind, (name, _type) in ASSET_FILES.items():
            size = loaded.files.get(kind)
            if size is None:
                self.stdout.write(f"{name}: not in the folder, so none is served.")
            else:
                self.stdout.write(f"{name}: loaded, {size} bytes.")
        self.stdout.write(
            f"{loaded.media_files} media files loaded (models, stills, videos, captions): "
            f"{loaded.media_bytes} bytes stored in all; {loaded.media_written} written this time "
            f"({loaded.media_bytes_written} bytes), the rest unchanged."
        )
        if loaded.media_files:
            self.stdout.write(self._bucket_line(loaded))
        if loaded.file_named_videos:
            self.stdout.write(
                "WARNING: clip videos named by file ("
                + ", ".join(loaded.file_named_videos)
                + "). An engine rolled back to before demo migration 0010 serves them as broken "
                "relative links at once: right after switching traffic back, reload the content "
                "with link-form clips or without them (the media ship's rollback note)."
            )
