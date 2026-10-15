"""`manage.py device_logs` - read what a device uploaded about itself.

    python manage.py device_logs --last 3
    python manage.py device_logs --last 10 --grep spotify
    python manage.py device_logs --last 1 --reason drive_end --household <uuid>

Prints the newest N batches (by `received_at`, the server's clock), oldest of
those first so the text reads in time order, each under a one-line header.
`--grep` keeps only lines containing the text (case-insensitive) and drops
batches with no match. This is an operator command on the engine's own
database, so it is not household-scoped unless `--household` says so; the HTTP
surface (`POST /api/device-logs/`) is scoped at `household_of`.

Against the deployed engine (Cloud Run Job, see `deploy/cloudrun/README.md`):

    gcloud run jobs execute legion-worker --region us-south1 --wait \\
        --args=manage.py,device_logs,--last,3
    gcloud run jobs executions logs ...   # or read the execution's log output
"""

from __future__ import annotations

from django.core.management.base import BaseCommand, CommandError

from devicelogs.models import REASONS, DeviceLog
from devicelogs.views import inflate_capped


class Command(BaseCommand):
    help = "Prints the newest uploaded device-log batches, decompressed."

    def add_arguments(self, parser):
        parser.add_argument("--last", type=int, default=5, help="How many batches (default 5).")
        parser.add_argument("--grep", default=None, help="Keep only lines containing this text.")
        parser.add_argument("--reason", choices=REASONS, default=None)
        parser.add_argument("--household", default=None, help="Household uuid; default is all.")

    def handle(self, *args, **options):
        last = options["last"]
        if last < 1:
            raise CommandError("--last must be at least 1.")
        rows = DeviceLog.objects.all()
        if options["household"]:
            rows = rows.filter(household_id=options["household"])
        if options["reason"]:
            rows = rows.filter(reason=options["reason"])
        needle = (options["grep"] or "").lower()

        batches = list(rows.order_by("-received_at")[:last])
        if not batches:
            self.stdout.write("No device logs stored.")
            return
        shown = 0
        for row in reversed(batches):
            try:
                text = inflate_capped(bytes(row.body_gz)).decode("utf-8", errors="replace")
            except ValueError as exc:
                self.stdout.write(f"=== {row.id} unreadable: {exc}")
                continue
            lines = text.splitlines()
            if needle:
                lines = [line for line in lines if needle in line.lower()]
                if not lines:
                    continue
            shown += 1
            self.stdout.write(
                f"=== batch {row.id} | received {row.received_at:%Y-%m-%d %H:%M:%S}Z | "
                f"{row.device_label} v{row.app_version_name or '?'}"
                f"({row.app_version_code if row.app_version_code is not None else '?'}) | "
                f"{row.reason} | device window {row.window_start} .. {row.window_end} | "
                f"{len(lines)} lines"
            )
            self.stdout.write("\n".join(lines))
        if not shown:
            self.stdout.write(f"No lines matching {options['grep']!r} in the newest {last} batches.")
