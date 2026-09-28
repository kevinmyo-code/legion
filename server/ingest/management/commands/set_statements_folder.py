"""`manage.py set_statements_folder <folder id or URL> [--household <uuid>]` -
name the Drive folder `drive_statements` watches (backend-etl ticket 06).

Stored in the household's `drive` credential `config` under
`statements_folder_id`, MERGED into what is there, so the login, the scopes and
the backup setting are untouched. The Drive login must already be stored
(`tools/connect_session.py drive`); this only points it at a folder.

`--household` may be left out when exactly one household has a Drive login.
"""
from __future__ import annotations

from django.core.management.base import BaseCommand, CommandError

from ingest.folder_ids import FolderIdError, folder_id_from
from ingest.models import SessionSource, SourceCredential
from ingest.statements import FOLDER_CONFIG_KEY


class Command(BaseCommand):
    help = "Point the drive_statements job at a Drive folder (an id or a folder URL)."

    def add_arguments(self, parser):
        parser.add_argument("folder", help="The folder's id, or its URL from the browser.")
        parser.add_argument(
            "--household",
            help="The household's id. Optional when only one household has a Drive login.",
        )

    def handle(self, *args, folder: str, household: str | None = None, **options):
        try:
            folder_id = folder_id_from(folder)
        except FolderIdError as exc:
            raise CommandError(str(exc)) from None
        drive = SourceCredential.objects.filter(source=SessionSource.DRIVE)
        if household:
            credentials = list(drive.filter(household_id=household))
            if not credentials:
                raise CommandError(
                    f"Household {household} has no Drive login stored. Run "
                    f"tools/connect_session.py drive first. Nothing was changed."
                )
        else:
            credentials = list(drive)
            if len(credentials) != 1:
                ids = ", ".join(str(c.household_id) for c in credentials) or "none"
                raise CommandError(
                    f"{len(credentials)} households have a Drive login ({ids}); name one "
                    f"with --household. Nothing was changed."
                )
        credential = credentials[0]
        credential.config = {**(credential.config or {}), FOLDER_CONFIG_KEY: folder_id}
        credential.save(update_fields=["config"])
        self.stdout.write(
            f"Household {credential.household_id}: drive_statements now watches folder "
            f"{folder_id}."
        )
