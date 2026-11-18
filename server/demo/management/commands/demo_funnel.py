"""`manage.py demo_funnel [--days N]`: the demo's funnel counts per day and ref tag (counts only).

day         ref       page_load orb_tap first_word turns_3 tour_started tour_finished resume_opened
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

from django.core.management.base import BaseCommand

from demo.models import DemoFunnelDay


class Command(BaseCommand):
    help = "Print the demo's funnel counts per day and ref tag."

    def add_arguments(self, parser):
        parser.add_argument("--days", type=int, default=14, help="How many days back (default 14).")

    def handle(self, *args, **options):
        since = datetime.now(UTC).date() - timedelta(days=max(options["days"], 1) - 1)
        rows = DemoFunnelDay.objects.filter(day__gte=since).order_by("day", "ref")
        table: dict[tuple, dict[str, int]] = {}
        for row in rows:
            table.setdefault((row.day, row.ref), {})[row.event] = row.count
        events = DemoFunnelDay.EVENTS
        self.stdout.write("day         ref           " + " ".join(f"{e:>13}" for e in events))
        if not table:
            self.stdout.write(f"(no counts since {since})")
        for (day, ref), counts in table.items():
            line = f"{day}  {ref or '-':<12}  " + " ".join(
                f"{counts.get(e, 0):>13}" for e in events
            )
            self.stdout.write(line)
