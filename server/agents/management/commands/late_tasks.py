"""`manage.py late_tasks`: run the late-answer tasks that are due (`agents/legion/late.py`).

Driven by the warm-service tick (`POST /api/internal/tick/late_tasks`, `legion/tick.py`), so it
runs on an instance with CPU, not on a request thread. Claims at most `--limit` tasks with a
lease; a task cut off by a restart is claimed again by a later run once its lease runs out.
Does nothing unless `LEGION_LATE_ANSWERS=on`. Prints counts only, never words.
"""

from __future__ import annotations

from django.core.management.base import BaseCommand

from agents.legion import late
from agents.runtime.model import GeminiClient
from ingest.statements import gemini_key


class Command(BaseCommand):
    help = "Run the background tasks of the late-answer path that are due (dark unless switched on)."

    def add_arguments(self, parser):
        parser.add_argument("--limit", type=int, default=2)

    def handle(self, *args, **options):
        if not late.enabled():
            self.stdout.write("late_tasks: switched off (LEGION_LATE_ANSWERS is not on)")
            return
        if not gemini_key():
            self.stdout.write("late_tasks: no Gemini key; nothing run")
            return
        ran = late.run_due(lambda: GeminiClient(key=gemini_key), limit=options["limit"])
        self.stdout.write(f"late_tasks: claimed={ran}")
