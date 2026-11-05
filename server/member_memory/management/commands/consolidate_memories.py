"""`manage.py consolidate_memories`: finished conversation records become memories.

Hourly from `deploy/crontab` (memory-and-vault ticket 03). See `member_memory/consolidate.py`
for what the model is shown and what it may write. Prints counts only, never a memory or a
transcript.
"""

from __future__ import annotations

from django.core.management.base import BaseCommand

from member_memory import consolidate


class Command(BaseCommand):
    help = "Distil finished conversation records into members' private memories."

    def add_arguments(self, parser):
        parser.add_argument(
            "--limit",
            type=int,
            default=consolidate.BATCH_LIMIT,
            help=f"At most this many records this run (default {consolidate.BATCH_LIMIT}).",
        )

    def handle(self, *args, limit, **options):
        report = consolidate.run(limit=max(1, limit))
        self.stdout.write(report.sentence())
