"""`manage.py device_logs` - read what a device uploaded about itself.

    python manage.py device_logs --last 3
    python manage.py device_logs --last 10 --grep spotify
    python manage.py device_logs --last 1 --reason drive_end --household <uuid>
    python manage.py device_logs --last 3 --reason conversation --render
    python manage.py device_logs --last 3 --reason conversation --device web --render
    python manage.py device_logs --reason logcat --since 2h --tag AndroidRuntime --tag LIVE_AUDIT
    python manage.py device_logs --reason logcat --reason crash --since 2026-11-09T14:00 \\
        --until 2026-11-09T15:30 --raw
    python manage.py device_logs --reason logcat --since 90m --raw --b64

The phone's own logs (2026-11-09): `logcat` is every line the app's process wrote, `crash` the
stack of an uncaught exception plus Android's exit records. `--since` / `--until` take an ISO time
(UTC unless it names a zone) or a span back from now (`90m`, `2h`, `1d`), keep the batches whose
device window overlaps, and cut each batch's lines to the window by their own threadtime stamps
(the phone's local time, put on UTC with the offset `devicelogs.lines.utc_offset` works out).
`--tag` (repeatable, case-insensitive) keeps only those logcat tags. `--run` keeps one phone
process run (`run_id`). `--raw` prints only the lines, every batch merged into one time order, so
the output feeds `tools/voice_audit.py` as it is. `--b64` (with `--raw`) prints that text gzipped
and base64'd as numbered `BLOB i/n` lines instead, for `tools/phone_audit_engine.py` to reassemble
from Cloud Logging, where plain lines can arrive out of order.

Prints the newest N batches (by `received_at`, the server's clock), oldest of
those first so the text reads in time order, each under a one-line header.
`--grep` keeps only lines containing the text (case-insensitive) and drops
batches with no match. `--render` prints each batch as the conversation timeline
`tools/voice_audit.py` prints for a phone capture (`devicelogs/render.py`: who said what,
each tool call and result, reply latency), instead of the raw lines; it pairs with
`--reason conversation` and cannot be combined with `--grep`. This is an
operator command on the engine's own database, so it is not household-scoped
unless `--household` says so; the HTTP surface (`POST /api/device-logs/`) is
scoped at `household_of`. `--device web` keeps the web assistant's conversation
records (`devicelogs/web_conversation.py`); a recruiter visit's record says
`visit ref=<ref>` in its header and has no household.

Against the deployed engine (Cloud Run Job, see `deploy/cloudrun/README.md`):

    gcloud run jobs execute legion-worker --region us-south1 --wait \\
        --args=manage.py,device_logs,--last,3
    gcloud run jobs executions logs ...   # or read the execution's log output
"""

from __future__ import annotations

import datetime
import re

from django.core.management.base import BaseCommand, CommandError
from django.db.models import Q
from django.utils import timezone
from django.utils.dateparse import parse_datetime

from devicelogs import lines as loglines
from devicelogs.models import REASONS, DeviceLog
from devicelogs.render import batch_header, batch_text, render_batch

# With a time window and no --last, at most this many batches are read (the newest; a phone
# batch is at most 2 MB raw, so this bounds the Job's memory).
WINDOW_BATCH_LIMIT = 300
# A batch's device window is untrusted; a batch whose window just misses still has its lines
# cut by their own stamps, so a little slack costs nothing.
WINDOW_SLACK = datetime.timedelta(minutes=5)
_SPAN = re.compile(r"^(\d+)([mhd])$")


def parse_when(value: str, *, now: datetime.datetime | None = None) -> datetime.datetime:
    """`90m` / `2h` / `1d` back from now, or an ISO time (UTC when it names no zone)."""
    now = now or timezone.now()
    span = _SPAN.match(value.strip())
    if span:
        unit = {"m": "minutes", "h": "hours", "d": "days"}[span.group(2)]
        return now - datetime.timedelta(**{unit: int(span.group(1))})
    parsed = parse_datetime(value.strip())
    if parsed is None:
        raise CommandError(
            f"Not a time: {value!r}. Use an ISO time like 2026-11-09T14:00 or a span like 2h."
        )
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=datetime.UTC)
    return parsed


class Command(BaseCommand):
    help = "Prints the newest uploaded device-log batches, decompressed."

    def add_arguments(self, parser):
        parser.add_argument(
            "--last", type=int, default=None,
            help=(
                "How many batches (default 5; with --since or --until, "
                f"up to {WINDOW_BATCH_LIMIT})."
            ),
        )
        parser.add_argument("--grep", default=None, help="Keep only lines containing this text.")
        parser.add_argument(
            "--reason", choices=REASONS, action="append", default=None, help="Repeatable."
        )
        parser.add_argument(
            "--since", default=None, help="ISO time (UTC) or a span back: 90m, 2h, 1d."
        )
        parser.add_argument("--until", default=None, help="ISO time (UTC) or a span back: 30m.")
        parser.add_argument(
            "--tag", action="append", default=None, help="Keep only this logcat tag. Repeatable."
        )
        parser.add_argument(
            "--run", default=None, help="Keep only this phone process run (run_id)."
        )
        parser.add_argument(
            "--raw", action="store_true",
            help="Only the lines, every batch merged in time order (no batch headers).",
        )
        parser.add_argument(
            "--b64", action="store_true",
            help="With --raw: print the text as numbered gzip+base64 BLOB lines.",
        )
        parser.add_argument("--household", default=None, help="Household uuid; default is all.")
        parser.add_argument(
            "--device",
            default=None,
            help="Keep only this device label (case-insensitive), e.g. web.",
        )
        parser.add_argument(
            "--render",
            action="store_true",
            help="Print each batch as a conversation timeline (LIVE_AUDIT / LIVE_LATENCY lines).",
        )
        parser.add_argument(
            "--verbose", action="store_true", help="With --render: also mic and activity lines."
        )

    def handle(self, *args, **options):
        since = parse_when(options["since"]) if options["since"] else None
        until = parse_when(options["until"]) if options["until"] else None
        windowed = since is not None or until is not None
        last = (
            options["last"]
            if options["last"] is not None
            else (WINDOW_BATCH_LIMIT if windowed else 5)
        )
        if last < 1:
            raise CommandError("--last must be at least 1.")
        if options["render"] and options["grep"]:
            raise CommandError("--render reads whole batches; it cannot be combined with --grep.")
        if options["render"] and (options["raw"] or options["tag"]):
            raise CommandError(
                "--render prints a timeline; it cannot be combined with --raw or --tag."
            )
        if options["b64"] and not options["raw"]:
            raise CommandError("--b64 encodes the --raw output; pass both.")
        rows = DeviceLog.objects.all()
        if options["household"]:
            rows = rows.filter(household_id=options["household"])
        if options["reason"]:
            rows = rows.filter(reason__in=options["reason"])
        if options["device"]:
            rows = rows.filter(device_label__iexact=options["device"])
        if options["run"]:
            rows = rows.filter(run_id=options["run"])
        rows = _in_window(rows, since, until)
        needle = (options["grep"] or "").lower()
        tags = {t.lower() for t in options["tag"]} if options["tag"] else None

        batches = list(rows.order_by("-received_at")[:last])
        if options["raw"]:
            self._raw(list(reversed(batches)), since, until, tags, needle, options["b64"])
            return
        if not batches:
            self.stdout.write("No device logs stored.")
            return
        shown = 0
        for row in reversed(batches):
            header = None
            try:
                if options["render"]:
                    # The same function `GET /api/device-logs/<id>/render` serves (ticket 02).
                    header, lines = render_batch(row, verbose=options["verbose"])
                else:
                    lines = batch_text(row).splitlines()
            except ValueError as exc:
                self.stdout.write(f"=== {row.id} unreadable: {exc}")
                continue
            if header is None:
                if needle or tags is not None or windowed:
                    lines = [
                        line for _when, line in _filtered(row, lines, since, until, tags, needle)
                    ]
                    if not lines:
                        continue
                header = batch_header(row, len(lines))
            shown += 1
            self.stdout.write(header)
            self.stdout.write("\n".join(lines))
        if not shown:
            self.stdout.write(
                f"No lines matching the filters in the newest {len(batches)} batches."
            )

    def _raw(self, batches, since, until, tags, needle, b64: bool) -> None:
        per_batch = []
        unreadable = []
        for row in batches:
            try:
                text = batch_text(row)
            except ValueError as exc:
                unreadable.append(f"# batch {row.id} unreadable: {exc}")
                continue
            per_batch.append(_filtered(row, text.splitlines(), since, until, tags, needle))
        out = unreadable + loglines.merged(per_batch)
        if b64:
            for line in loglines.blob_lines("\n".join(out) + ("\n" if out else "")):
                self.stdout.write(line)
            return
        if out:
            self.stdout.write("\n".join(out))


def _in_window(rows, since, until):
    """Batches whose device window overlaps [since, until]; a batch with no window by
    received_at."""
    if since is not None:
        lo = since - WINDOW_SLACK
        rows = rows.filter(Q(window_end__gte=lo) | Q(window_end__isnull=True, received_at__gte=lo))
    if until is not None:
        hi = until + WINDOW_SLACK
        rows = rows.filter(
            Q(window_start__lte=hi) | Q(window_start__isnull=True, received_at__lte=hi)
        )
    return rows


def _filtered(row, lines, since, until, tags, needle):
    """(utc, line) for the lines of one batch that pass every line filter."""
    offset = loglines.utc_offset(lines, row.window_start)
    out = []
    for line in lines:
        when = loglines.line_utc(line, row.window_start, offset)
        if loglines.keep(line, when=when, since=since, until=until, tags=tags, needle=needle):
            out.append((when, line))
    return out
