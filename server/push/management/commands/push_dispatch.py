"""`manage.py push_dispatch` - send what is due to be said (web-revamp ticket 15,
spec D7). `deploy/crontab` runs it every five minutes.

A `JobCommand` like every other scheduled job: one `ingest_runs` row per
household per run, so `/api/freshness` can say when notifications were last
checked. A household with no subscribed device is recorded as not set up.

With the VAPID keys absent, push is off: this prints that in words and
records nothing, because nothing was attempted (`push/vapid.py`). The
decisions are `push/dispatch.py`'s; this is the wiring.
"""

from __future__ import annotations

from django.utils import timezone

from ingest.jobs import JobCommand
from ingest.models import IngestRun, Source
from push.copy import PUSH_OFF_NOTHING_SENT
from push.dispatch import dispatch_household
from push.models import PushSubscription
from push.sender import webpush_sender
from push.vapid import vapid_config


class Command(JobCommand):
    help = "Sends due Web Push notifications: list changes, event reminders, the morning list."

    source = Source.PUSH

    def is_configured(self, household) -> bool:
        return PushSubscription.objects.filter(household=household).exists()

    def handle(self, *args, **options):
        config = vapid_config()
        if config is None:
            self.stdout.write(PUSH_OFF_NOTHING_SENT)
            return
        self.sender = webpush_sender(config)
        super().handle(*args, **options)

    def job(self, run: IngestRun) -> str | None:
        report = dispatch_household(run.household, timezone.now(), self.sender)
        run.rows_written = len(report.delivered)
        run.rows_unchanged = 0
        return None
