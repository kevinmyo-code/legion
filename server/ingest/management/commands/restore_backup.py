"""`manage.py restore_backup <YYYY-MM-DD>` - the restore drill (backend-etl
ticket 03: "drilled, not described").

Downloads that day's dump from the operator household's Drive, restores it
into a THROWAWAY database it creates and always drops, and prints a row count
per table against live. Exits non-zero on any difference, and on anything that
stops the drill. It refuses, before touching anything, a target named like the
live database.

Run it by hand, never from the crontab. It reads live (counts only) and never
writes to it.
"""
from __future__ import annotations

import datetime
import tempfile
from pathlib import Path

from django.core.management.base import BaseCommand, CommandError

from ingest import backup, vault
from ingest.drive import DriveClient, DriveError, DriveRefused
from ingest.jobs import NeedsLogin
from ingest.models import SessionSource


class Command(BaseCommand):
    help = (
        "Restore one day's Drive backup into a throwaway database, compare per-table "
        "row counts with live, drop it. Non-zero exit on any mismatch."
    )

    def add_arguments(self, parser):
        parser.add_argument("date", help="The backup's date, YYYY-MM-DD (UTC).")
        parser.add_argument(
            "--target",
            default=None,
            help="Throwaway database name (default: legion_restore_<date>_<random>). "
            "Never the live database; refused if it is.",
        )

    def handle(self, *args, date: str, target: str | None, **options):
        try:
            day = datetime.date.fromisoformat(date).isoformat()
        except ValueError as exc:
            raise CommandError(f"{date!r} is not a YYYY-MM-DD date.") from exc
        target = target or backup.throwaway_name(day)
        try:
            backup.refuse_live_target(target)
            self._drill(day, target)
        except backup.BackupError as exc:
            raise CommandError(str(exc)) from exc

    def _drill(self, day: str, target: str) -> None:
        write = self.stdout.write
        household = backup.backup_household()
        if household is None:
            raise backup.BackupError(backup.not_set_up_reason())
        try:
            _credential, secret = vault.session_for(household, SessionSource.DRIVE)
        except NeedsLogin as exc:
            raise backup.BackupError(str(exc)) from exc
        restore_bin = backup.pg_binary("pg_restore")
        backup.check_versions(restore_bin)
        client = DriveClient(secret)

        with tempfile.TemporaryDirectory(prefix="legion-restore-") as tmp:
            path = Path(tmp) / backup.dump_name(day)
            try:
                dump = backup.find_dump(client, day)
                size = client.download(dump["id"], path)
            except DriveRefused as exc:
                raise backup.BackupError(
                    f"Google refused the Drive login ({exc}). Run {backup.LOGIN_COMMAND}."
                ) from exc
            except DriveError as exc:
                raise backup.BackupError(str(exc)) from exc
            write(f"Downloaded {dump.get('name')} ({size} bytes) for {day}.")

            backup.create_database(target)
            write(f"Created throwaway database {target}.")
            try:
                backup.prepare_extensions(target, write=write)
                stderr = backup.pg_restore(path, target, binary=restore_bin)
                if stderr.strip():
                    write("pg_restore said (the row counts below are the verdict):")
                    write(stderr.strip())
                schema_names = backup.schemas()
                restored = backup.restored_counts(target, schema_names)
                live = backup.live_counts(schema_names)
            finally:
                backup.drop_database(target)
                write(f"Dropped throwaway database {target}.")

        # A drill that restored nothing must not read as a pass (CLAUDE.md
        # section 4 rule 6's shape: a check an empty result satisfies is none).
        if not restored:
            raise CommandError(
                f"The restore produced no tables in {', '.join(schema_names)}; the dump is "
                f"empty or did not restore. {len(live)} live tables were not matched."
            )
        lines, mismatched = backup.compare(restored, live)
        for line in lines:
            write(line)
        if mismatched:
            raise CommandError(
                f"{len(mismatched)} of {len(lines)} tables differ from live: "
                f"{', '.join(mismatched)}."
            )
        write(f"All {len(lines)} tables match live.")
