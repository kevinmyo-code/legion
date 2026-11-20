"""How long an agent turn's rows live (ruling 6, plan sections 5 and 15.2).

- Turns, conversations and tool calls: `TURN_RETENTION_DAYS` (30).
- Checkpoints (full state, for resume and replay): `CHECKPOINT_RETENTION_DAYS` (7).
- Late-answer tasks and owed lines (`late.py`): `TURN_RETENTION_DAYS` (30), deleted whole.
- Model-call numbers: `METRIC_RETENTION_DAYS` (90). A model-call row belongs to its turn, so a
  turn past 30 days whose numbers are still inside 90 is kept as a shell: its words are blanked
  (transcript, final text, history) and its tool calls deleted, and the row itself goes at 90.

Pruned the way `procedures/retention.py` prunes: inline at the start of every turn and by the
hourly `manage.py prune_demo_visitors`, so no new schedule is installed. Server-wide, by age.
A demo visit's rows go with the visit household (`demo.visit_cleanup`).
"""

from __future__ import annotations

import datetime

from django.utils import timezone

from agents.models import (
    CHECKPOINT_RETENTION_DAYS,
    METRIC_RETENTION_DAYS,
    TURN_RETENTION_DAYS,
    AgentCheckpoint,
    AgentConversation,
    AgentToolCall,
    AgentTurn,
    LateTask,
    OwedLine,
)


def prune(now: datetime.datetime | None = None) -> dict[str, int]:
    now = now or timezone.now()
    content_cutoff = now - datetime.timedelta(days=TURN_RETENTION_DAYS)
    checkpoint_cutoff = now - datetime.timedelta(days=CHECKPOINT_RETENTION_DAYS)
    metric_cutoff = now - datetime.timedelta(days=METRIC_RETENTION_DAYS)
    out = {}
    out["checkpoints"] = AgentCheckpoint.objects.filter(created_at__lt=checkpoint_cutoff).delete()[
        0
    ]
    out["tool_calls"] = AgentToolCall.objects.filter(created_at__lt=content_cutoff).delete()[0]
    out["blanked"] = (
        AgentTurn.objects.filter(created_at__lt=content_cutoff)
        .exclude(transcript="", final_text="", history=[])
        .update(transcript="", final_text="", history=[])
    )
    # Everything else of a turn past 90 days (its model calls go with it, CASCADE).
    out["turns"] = (
        AgentTurn.objects.filter(created_at__lt=metric_cutoff)
        .delete()[1]
        .get("agents.AgentTurn", 0)
    )
    out["late_tasks"] = LateTask.objects.filter(created_at__lt=content_cutoff).delete()[0]
    out["owed_lines"] = OwedLine.objects.filter(created_at__lt=content_cutoff).delete()[0]
    out["conversations"] = AgentConversation.objects.filter(
        last_turn_at__lt=content_cutoff, turns__isnull=True
    ).delete()[0]
    return out
