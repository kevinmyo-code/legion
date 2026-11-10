"""`manage.py evaluate_watches`: every household's standing watches, evaluated deterministically
against the rows the other jobs keep (ADR 0083). Firings land in `watch_firings`; the phone reads
them on its existing engine poll and says each once. Runs on the schedule in `deploy/crontab`.
"""

from __future__ import annotations

from django.core.management.base import BaseCommand
from django.utils import timezone

from household.models import Household
from watches.evaluate import evaluate_household


class Command(BaseCommand):
    help = "Evaluate every household's watches and record what fired."

    def handle(self, *args, **options):
        now = timezone.now()
        total_watches = total_fired = total_baseline = 0
        for household in Household.objects.filter(is_demo_visit=False).order_by("id"):
            report = evaluate_household(household, now)
            total_watches += report.watches
            total_fired += report.fired
            total_baseline += report.baselined
            for error in report.errors:
                self.stderr.write(f"household {household.id}: watch {error} failed; others ran.")
        self.stdout.write(
            f"evaluate_watches: {total_watches} watches, {total_fired} fired, "
            f"{total_baseline} recorded as already true (not said)."
        )
