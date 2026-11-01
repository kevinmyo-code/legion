"""`manage.py claude_session_prune`: tasks, their files and session rows older
than 30 days are deleted (map ruling 8: "Results and files stay on the engine
30 days"). Run daily from `deploy/crontab`.

A task is aged from when it was created, and its files go with it (the
foreign key cascades), so no file outlives the task it belongs to. A session
row is aged from the last hook post that touched it. Machines are kept: a
row per laptop, and its name is how Alfred addresses it.

Prints counts only; never a request, a summary or a file name.
"""

from __future__ import annotations

import datetime

from django.core.management.base import BaseCommand
from django.utils import timezone

from claude_session.models import RETENTION_DAYS, ClaudeCodeSession, ClaudeTask, ClaudeTaskFile


def prune(now: datetime.datetime | None = None) -> dict[str, int]:
    cutoff = (now or timezone.now()) - datetime.timedelta(days=RETENTION_DAYS)
    # Follow-ups younger than the cutoff keep their row; their `parent` is SET_NULL.
    _, tasks = ClaudeTask.objects.filter(created_at__lt=cutoff).delete()
    sessions, _ = ClaudeCodeSession.objects.filter(last_seen_at__lt=cutoff).delete()
    return {
        "tasks": tasks.get(ClaudeTask._meta.label, 0),
        "files": tasks.get(ClaudeTaskFile._meta.label, 0),
        "sessions": sessions,
    }


class Command(BaseCommand):
    help = f"Delete Claude session tasks, files and session rows older than {RETENTION_DAYS} days."

    def handle(self, *args, **options):
        counts = prune()
        self.stdout.write(
            f"claude_session_prune: deleted {counts['tasks']} tasks, {counts['files']} files, "
            f"{counts['sessions']} session rows older than {RETENTION_DAYS} days."
        )
