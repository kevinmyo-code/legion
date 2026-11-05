"""`manage.py finance_snapshots` - one balance snapshot a day for every
Plaid-linked household account (household-finance ticket 01). `deploy/crontab`
runs it daily, after a Plaid sync has stored the bank's balances.

Plain rather than a `JobCommand`: it only copies figures the Plaid sync already
stored, and its unique (account, day, source) key makes a re-run update the
day's row. Exits 0 always; anything it could not do is said in words.
"""

from __future__ import annotations

from django.core.management.base import BaseCommand

from finance.models import FinanceAccount
from finance.snapshots import snapshot_plaid
from household.models import Household
from household.timezones import local_date, zone_named


class Command(BaseCommand):
    help = "Record today's bank balance for each Plaid-linked household account."

    def handle(self, *args, **options):
        from zoneinfo import ZoneInfo

        households = Household.objects.filter(
            id__in=FinanceAccount.objects.values("household_id")
        ).order_by("id")
        for household in households:
            zone = zone_named(household.timezone) or ZoneInfo("UTC")
            report = snapshot_plaid(household, local_date(zone), zone)
            line = f"Household {household.id}: {report.written} snapshot(s) written."
            if report.unmatched:
                line += (
                    f" No bank record matched {len(report.unmatched)} Plaid account(s): "
                    f"{', '.join(report.unmatched)}."
                )
            if report.no_balance:
                line += (
                    f" The bank stated no balance for {', '.join(report.no_balance)}; "
                    f"nothing was written for them."
                )
            self.stdout.write(line)
