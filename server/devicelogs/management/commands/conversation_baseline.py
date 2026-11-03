"""`manage.py conversation_baseline` - what car, money and morning asks cost today
(procedures ticket 10).

    python manage.py conversation_baseline                  # last 60 days, every member device
    python manage.py conversation_baseline --days 14 --device web
    python manage.py conversation_baseline --include-visits # recruiter visits as their own device

Reads every `reason=conversation` batch received in the window, joins a phone's or head unit's
batches into one stream per household and device (a session can span two uploads), keeps each
web conversation as its own stream, and prints Markdown tables of counts, medians and p95s
(`devicelogs/baseline.py` defines every column). **No transcript text, tool arguments or tool
results are printed**: the output is meant to be pasted into the public repo, as
`.scratch/procedures/research/baseline-<date>.md`.

Against the deployed engine (Cloud Run Job), with `--numbered` so log lines can be put back in
order (Cloud Logging may reorder lines written in the same instant):

    gcloud run jobs execute legion-worker --region us-south1 --wait \\
        --args=manage.py,conversation_baseline,--days,60,--numbered
"""

from __future__ import annotations

import datetime

from django.core.management.base import BaseCommand, CommandError
from django.utils import timezone

from devicelogs import voice_audit
from devicelogs.baseline import Totals, asks_in, render
from devicelogs.models import DEVICE_LABEL_WEB, REASON_CONVERSATION, DeviceLog
from devicelogs.views import inflate_capped

VISIT_DEVICE = "web (recruiter visit)"


class Command(BaseCommand):
    help = "Prints round trips, latency and tool errors per ask class, from conversation records."

    def add_arguments(self, parser):
        parser.add_argument("--days", type=int, default=60, help="Window in days (default 60).")
        parser.add_argument("--household", default=None, help="Household uuid; default is all.")
        parser.add_argument("--device", default=None, help="Only this device label, e.g. web.")
        parser.add_argument(
            "--include-visits",
            action="store_true",
            help="Also recruiter visits' web records, as their own device row.",
        )
        parser.add_argument(
            "--numbered",
            action="store_true",
            help="Prefix each line with a 4-digit number, to re-sort lines read from a log.",
        )

    def handle(self, *args, **options):
        days = options["days"]
        if days < 1:
            raise CommandError("--days must be at least 1.")
        since = timezone.now() - datetime.timedelta(days=days)
        rows = DeviceLog.objects.filter(reason=REASON_CONVERSATION, received_at__gte=since)
        if not options["include_visits"]:
            rows = rows.filter(demo_visitor=False)
        if options["household"]:
            rows = rows.filter(household_id=options["household"])
        if options["device"]:
            rows = rows.filter(device_label__iexact=options["device"])

        totals = Totals()
        streams: dict[tuple, tuple[str, list[str]]] = {}
        ordered = rows.order_by("received_at").only(
            "id", "household_id", "device_label", "batch_id", "demo_visitor", "body_gz"
        )
        for row in ordered.iterator(chunk_size=50):
            totals.batches += 1
            try:
                text = inflate_capped(bytes(row.body_gz)).decode("utf-8", errors="replace")
            except ValueError:
                totals.unreadable += 1
                continue
            if row.device_label == DEVICE_LABEL_WEB:
                # One row per web conversation; two open at once must not interleave.
                key = ("web", row.batch_id)
                device = VISIT_DEVICE if row.demo_visitor else DEVICE_LABEL_WEB
            else:
                key = (row.household_id, row.device_label)
                device = row.device_label
            streams.setdefault(key, (device, []))[1].extend(text.splitlines())

        asks = []
        for device, lines in streams.values():
            # A re-sent upload repeats lines; keep each once, in order.
            unique = list(dict.fromkeys(lines))
            totals.sessions += sum(1 for s in voice_audit._sessions(unique) if s["head"])
            asks.extend(asks_in(unique, device))
        totals.streams = len(streams)

        window = (
            f"received in the last {days} days (since {since:%Y-%m-%d %H:%M}Z), "
            f"recruiter visits {'included' if options['include_visits'] else 'excluded'}"
        )
        lines = render(asks, totals, window=window)
        if options["numbered"]:
            lines = [f"{i:04d} {line}" for i, line in enumerate(lines, 1)]
        self.stdout.write("\n".join(lines))
