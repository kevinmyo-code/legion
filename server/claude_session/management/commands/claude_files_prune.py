"""`manage.py claude_files_prune`: delivered files' bytes past their 24 hours are deleted (files
to phone, ADR 0080). Run hourly from `deploy/crontab`.

A `deliver: files` task's file is deleted from the engine the moment the asking member's phone says
it has it (`received/`). This is the other trigger: whatever happened, its bytes go
`FILES_KEEP_HOURS` after upload, from the private bucket or from the row. The download view
already refuses them from that hour on (computed at read time), so an hourly run never leaves one
readable. The bucket's own one-day lifecycle rule (`deploy/claude_files/setup_bucket.sh`) is the
backstop for a bucket that refused a delete here. The row itself, name and size, stays for the
task's 30 days (`claude_session_prune`).

Prints counts only; never a file name, a path or a storage key.
"""

from __future__ import annotations

import datetime

from django.core.management.base import BaseCommand
from django.db.models import Q
from django.utils import timezone

from claude_session.models import DELIVER_FILES, FILES_KEEP_HOURS, ClaudeTaskFile
from claude_session.storage import StorageError
from claude_session.views import purge_bytes


def prune(now: datetime.datetime | None = None) -> dict[str, int]:
    now = now or timezone.now()
    cutoff = now - datetime.timedelta(hours=FILES_KEEP_HOURS)
    due = (
        ClaudeTaskFile.objects.filter(
            task__deliver=DELIVER_FILES, uploaded_at__lt=cutoff, purged_at__isnull=True
        )
        .filter(Q(content__isnull=False) | ~Q(storage_key=""))
        .order_by("id")
    )
    deleted = failed = 0
    for row in due.iterator(chunk_size=100):
        try:
            purge_bytes(row, now)
        except StorageError:
            failed += 1
            continue
        deleted += 1
    return {"deleted": deleted, "failed": failed}


class Command(BaseCommand):
    help = f"Delete delivered Claude files' bytes {FILES_KEEP_HOURS} hours after upload."

    def handle(self, *args, **options):
        counts = prune()
        self.stdout.write(
            f"claude_files_prune: deleted {counts['deleted']} delivered files older than "
            f"{FILES_KEEP_HOURS} hours; {counts['failed']} the bucket refused (retried next run)."
        )
