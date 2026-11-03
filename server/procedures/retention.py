"""How long a run trace lives (procedures ticket 13, ruling 06).

- **Members: `RETENTION_DAYS` (60).** Pruned inline at the start of every run, the way
  `devicelogs.views.prune` runs on each upload: no new `deploy/crontab` line (Cloud Scheduler
  bills per job). The delete is server-wide and by age alone, so a household that stopped running
  procedures loses its old runs the next time ANY household runs one; and
  `manage.py prune_demo_visitors` (hourly) calls the same function.
- **Recruiter visits: deleted with the visit household** (`ProcedureRun.household` CASCADE, and
  `demo.visit_cleanup` deletes every `TENANT_TABLES` table in foreign-key order). A visit lasts
  two hours, so `VISIT_RETENTION_DAYS` (30) is a backstop for a visit household that outlived its
  visit, not the usual path.
- **A question nobody answered** (ruling 03): a waiting run past its two minutes is marked
  cancelled and its held values are dropped.

Deletes and updates only; nothing is read back to anyone.
"""

from __future__ import annotations

import datetime

from django.db.models import Q
from django.utils import timezone

from procedures.models import (
    RETENTION_DAYS,
    VISIT_RETENTION_DAYS,
    CallerKind,
    ProcedureRun,
    RunStatus,
)

EXPIRED_RESULT = (
    "Cancelled: the question was not answered within two minutes. Nothing after it ran, and "
    "nothing after it was written."
)


def cancel_expired(now: datetime.datetime | None = None) -> int:
    now = now or timezone.now()
    return ProcedureRun.objects.filter(status=RunStatus.WAITING, resume_expires_at__lt=now).update(
        status=RunStatus.CANCELLED,
        resume_state=None,
        resume_token_sha256="",
        waiting_step="",
        result=EXPIRED_RESULT,
    )


def prune(now: datetime.datetime | None = None) -> int:
    """Delete runs past retention (steps go with them). Returns runs deleted."""
    now = now or timezone.now()
    cancel_expired(now)
    member_cutoff = now - datetime.timedelta(days=RETENTION_DAYS)
    visit_cutoff = now - datetime.timedelta(days=VISIT_RETENTION_DAYS)
    visit = Q(caller_kind=CallerKind.RECRUITER) | Q(household__is_demo_visit=True)
    deleted, per_model = ProcedureRun.objects.filter(
        Q(started_at__lt=member_cutoff) | (visit & Q(started_at__lt=visit_cutoff))
    ).delete()
    return per_model.get("procedures.ProcedureRun", 0)
