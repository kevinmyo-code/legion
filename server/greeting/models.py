"""`public.greeting_topics`: what Alfred has already raised, and what was answered or silenced
(ADR 0091, alfred-greets ticket 01: "never the same question twice").

One row per (household, member, topic key). A topic key is a stable id for one thing a greeting
or check-in may mention: `event:<id>`, `due:<id>`, `money:<YYYY-MM>:<category>`, `body:<date>`,
and the phone's own `place:<area>`. The phone records each one when Alfred raises it, and again
when the member answers it or says to drop it; the facts pack (`greeting/facts.py`) then leaves an
answered or silenced topic out and lists a raised one in `said`.

**Private to the member it is about** (ADR 0052): `owner_user` is never null, so
`household.tenancy.visible()` hands each member their own topics and nobody else's, and the owner
guard trigger (`household/visibility_sql.py`) refuses an owner from outside the row's household.
Born tenanted and listed in `TENANT_TABLES`.
"""

from __future__ import annotations

import uuid

from django.db import models
from django.db.models.functions import Now

from household.tenancy_sql import household_unique_name

STATE_RAISED = "raised"
STATE_ANSWERED = "answered"
STATE_SILENCED = "silenced"
STATES: tuple[str, ...] = (STATE_RAISED, STATE_ANSWERED, STATE_SILENCED)
# A closed topic is never raised again; a raised one may be, if nothing better is on.
CLOSED_STATES: frozenset[str] = frozenset({STATE_ANSWERED, STATE_SILENCED})

TOPIC_KEY_MAX_CHARS = 200


class GreetingTopic(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    owner_user = models.ForeignKey("household.User", on_delete=models.CASCADE, related_name="+")
    topic_key = models.TextField()
    state = models.TextField()
    # When `state` was last set.
    at = models.DateTimeField(db_default=Now())
    created_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "greeting_topics"
        indexes = [
            models.Index(fields=["household", "owner_user", "at"], name="greeting_topics_owner_idx")
        ]
        constraints = [
            models.UniqueConstraint(
                fields=["household", "owner_user", "topic_key"],
                name=household_unique_name("greeting_topics", ["owner_user_id", "topic_key"]),
            ),
            models.CheckConstraint(
                condition=models.Q(state__in=STATES), name="greeting_topics_state_valid"
            ),
        ]
