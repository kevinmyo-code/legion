"""Who may read the facts pack and record a topic, and the recording itself (ADR 0091). The REST
views (`greeting/views.py`) and the engine tool `greeting_facts` (`engine_mcp/tools.py`) call
these, one code path for both doors (ADR 0035).

**Who.** A member of a real household. A recruiter's visit is refused in words (ADR 0068,
default deny; a second fence beside the route table and the tool allowlist). The Claude worker
may READ the pack (it answers "what is on today" too), but never records a topic: what Alfred
has said, and what the member answered or silenced, is the phone's to record, and the worker may
have read mail in the same task (CLAUDE.md section 7).
"""

from __future__ import annotations

import re

from django.db import IntegrityError, transaction
from django.utils import timezone

from claude_session.tokens import is_claude_token
from greeting.models import (
    CLOSED_STATES,
    TOPIC_KEY_MAX_CHARS,
    GreetingTopic,
)
from household.models import is_recruiter
from household.recruiter_routes import RECRUITER_DENIED
from household.tenancy import household_of, visible

CLAUDE_REFUSAL = (
    "Nothing was recorded. A Claude session may read the greeting facts but never records what "
    "Alfred raised or what the member answered; only the member's phone does."
)
# `<kind>:<id>`: a lowercase kind, a colon, then the rest on one line.
TOPIC_KEY = re.compile(r"^[a-z][a-z_]*:[^\r\n]+$")
BAD_TOPIC_KEY = (
    "Nothing was recorded. A topic key is a lowercase kind, a colon and an id on one line "
    f"(at most {TOPIC_KEY_MAX_CHARS} characters), like event:<id>, due:<id>, "
    "money:<YYYY-MM>:<category>, body:<date> or place:<area>."
)


def recruiter_reason() -> str:
    return RECRUITER_DENIED["greeting.views.GreetingFactsView"]


def refusal(request) -> str | None:
    if is_recruiter(getattr(request, "user", None)):
        reason = recruiter_reason()
        return f"Nothing was read. {reason[0].upper()}{reason[1:]}."
    return None


def write_refusal(request) -> str | None:
    reason = refusal(request)
    if reason is not None:
        return reason.replace("Nothing was read.", "Nothing was recorded.", 1)
    if is_claude_token(request):
        return CLAUDE_REFUSAL
    return None


def clean_key(raw) -> str | None:
    key = str(raw or "").strip()
    if not key or len(key) > TOPIC_KEY_MAX_CHARS or not TOPIC_KEY.match(key):
        return None
    return key


def record(request, topic_key: str, state: str) -> tuple[GreetingTopic, bool]:
    """Sets the member's topic to `state`, and says whether anything changed. A closed topic
    (answered or silenced) stays closed: raising it again records nothing, so a slip on the phone
    can never reopen a question the member already answered."""
    household = household_of(request)
    now = timezone.now()
    for _attempt in range(2):
        row = visible(GreetingTopic, request).filter(
            owner_user=request.user, topic_key=topic_key
        ).first()
        if row is None:
            try:
                with transaction.atomic():
                    row = GreetingTopic.objects.create(
                        household=household,
                        owner_user=request.user,
                        topic_key=topic_key,
                        state=state,
                        at=now,
                    )
                return row, True
            except IntegrityError:
                continue  # another request created it first; read it and go on
        if row.state == state or (row.state in CLOSED_STATES and state not in CLOSED_STATES):
            return row, False
        row.state = state
        row.at = now
        row.save(update_fields=["state", "at"])
        return row, True
    raise IntegrityError("greeting topic could not be recorded")


def topic_dict(row: GreetingTopic) -> dict:
    return {"topic_key": row.topic_key, "state": row.state, "at": row.at.isoformat()}
