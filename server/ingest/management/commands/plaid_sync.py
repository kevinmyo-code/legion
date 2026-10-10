"""`manage.py plaid_sync` - the bank's own transaction feed into the ledger
(ADR 0057). `deploy/crontab` runs it every six hours.

Everything it decides lives in `ingest/plaid_sync.py`. Like every `JobCommand`
it exits 0 for a failed run: the failure is written down where
`/api/freshness` reads it, and the next run is the retry. A household with no
bank connected records `skipped` ("not set up").
"""
from __future__ import annotations

from ingest import plaid_sync
from ingest.jobs import JobCommand
from ingest.models import IngestRun, PlaidItem, Source


class Command(JobCommand):
    help = (
        "Pull new, changed and removed transactions from each household's bank connection "
        "(Plaid /transactions/sync) into the ledger as fact, and replace the older CSV- and "
        "statement-derived rows the feed now covers."
    )

    source = Source.PLAID

    def is_configured(self, household) -> bool:
        return PlaidItem.objects.filter(household=household).exists()

    def job(self, run: IngestRun) -> str | None:
        return plaid_sync.run_sync(run, write=self.stdout.write)
