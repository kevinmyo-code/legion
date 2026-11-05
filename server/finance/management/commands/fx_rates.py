"""`manage.py fx_rates` - store today's USD/SGD rate (household-finance
ticket 03). `deploy/crontab` runs it daily.

A rate is a fact about the world, not about a household, so this is a plain
command rather than a per-household `JobCommand`. Like every scheduled job it
exits 0 when every source fails: it says so in words, and the next run is the
retry. Converted figures fall back to the nearest earlier day meanwhile.
"""

from __future__ import annotations

from django.core.management.base import BaseCommand

from finance import fx


class Command(BaseCommand):
    help = "Fetch today's USD/SGD rate from a free public source and store it as int micros."

    def handle(self, *args, **options):
        report = fx.record_latest()
        for failure in report.failures:
            self.stdout.write(f"A rate source failed: {failure}.")
        if report.quote is None:
            self.stdout.write(
                "No rate was stored: every source failed. Figures keep using the latest rate "
                "on file; the next run tries again."
            )
            return
        quote = report.quote
        verb = "Stored" if report.created else "Already on file, unchanged:"
        self.stdout.write(
            f"{verb} 1 {fx.BASE} = {fx.rate_words(quote.rate_micros)} {fx.QUOTE} for "
            f"{quote.on_date.isoformat()} (source: {quote.source})."
        )
