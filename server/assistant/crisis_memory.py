"""Nothing from a crisis conversation is ever saved as a memory (ticket #462).

The voice door answers a crisis by code and calls no model at all
(`agents/voice/pipeline.py`), so no tool is ever on offer there. The web cascade does not: it
says a crisis turn's words to a model that holds `remember`, and `assistant/cascade.py` said so
in a comment ("nothing here handles a crisis before this point"). #460's engine gate closed the
other door into memory, the hourly consolidation of a device's conversation record, and named
this one as still open.

The rule, which is the one #458 settled for the car: the WHOLE conversation goes, not the one
turn. The turns said before the phrase are the same exchange, and so are the turns after it. So
`remember` refuses for the rest of the conversation once any turn of it tripped the crisis
pre-check.

Two halves, because a web cascade turn is one HTTP request and the engine runs on several
instances:

- **This turn.** `crisis.detect` on his own words for this turn, as `read_through.heard` was
  given them. Nothing is stored and nothing can go missing: the decision is made from the same
  string the pre-check reads.
- **Every later turn.** A marker line in the conversation's own record
  (`web_conversation.engine_crisis_line`), the same mechanism as `read_through source=`: a
  durable per-conversation fact, scoped and pruned with that record, needing no table of its own.
  The line carries NO words, only that a turn tripped the check, so re-reading the record never
  re-reads his distress.

**Fails closed, both ways.** A mark that cannot be written puts the conversation in this
process's `_UNMARKED`, so every later `remember` in it refuses; the turn that could not be
marked refuses on its own words anyway. A mark that cannot be read refuses too.

**Doors with no conversation allow.** `/mcp` (the car, Claude Code) and REST hand the engine no
words and no conversation id, so there is nothing here to judge and `remember` is unchanged:
those callers carry their own gate (the phone's and the head unit's toolboxes, #458). Breaking
them here would be a guess at turns the engine cannot see, which is the rule
`read_through` already settled for that door.
"""

from __future__ import annotations

import logging
import uuid

from django.db import DatabaseError, transaction
from django.utils import timezone

from agents.voice import crisis
from assistant import read_through

log = logging.getLogger(__name__)

# What `remember` refuses with. Says what did not happen and that it holds, with no promise that
# asking again will work: unlike the read-through rule, this one does not lift next turn. Never
# quotes him and never names what he said.
REFUSAL = (
    "Not remembered: nothing from this conversation is saved to memory. Say that nothing was "
    "saved; do not offer to save it."
)

# Conversations whose crisis mark could not be written, in this process. One id per failed write,
# and a failed write is a database fault.
_UNMARKED: set[uuid.UUID] = set()


def mark(request, conversation_id) -> bool:
    """Records that a turn of this conversation tripped the crisis pre-check, so `remember`
    refuses for the rest of it. Called by the door before the model runs. True when recorded, or
    when the turn named no conversation; False when the write failed, which puts the conversation
    in `_UNMARKED`."""
    from devicelogs import web_conversation

    if conversation_id is None:
        return True
    try:
        with transaction.atomic():
            row = web_conversation.owned(request, conversation_id)
            if row is None:
                return True
            web_conversation.append(row, [web_conversation.engine_crisis_line(timezone.now())])
    except (DatabaseError, web_conversation.TooLarge):
        log.warning("crisis memory: could not mark a conversation; refusing remember")
        _UNMARKED.add(conversation_id)
        return False
    return True


def _marked(request, conversation_id) -> bool:
    """Whether this conversation's record holds the crisis marker. Reads the caller's own record
    only (`batch_id` is a uuid and `user` is the caller), and nothing but the marker's presence:
    no words are parsed out of it."""
    from devicelogs.models import DeviceLog
    from devicelogs.views import inflate_capped
    from devicelogs.web_conversation import (
        CRISIS_MARK,
        DEVICE_LABEL_WEB,
        REASON_CONVERSATION,
    )

    row = (
        DeviceLog.objects.filter(
            batch_id=conversation_id,
            user=request.user,
            reason=REASON_CONVERSATION,
            device_label=DEVICE_LABEL_WEB,
        )
        .only("body_gz")
        .first()
    )
    if row is None:
        return False
    body = inflate_capped(bytes(row.body_gz)).decode("utf-8", errors="replace")
    return CRISIS_MARK in body


def refusal(request) -> str | None:
    """The sentence `remember` refuses with, or None when it may write."""
    if crisis.detect(read_through.heard_words(request)):
        return REFUSAL
    ref = read_through.turn_of(request)
    if ref is None or ref.conversation_id is None:
        return None
    if ref.conversation_id in _UNMARKED:
        return REFUSAL
    try:
        with transaction.atomic():
            return REFUSAL if _marked(request, ref.conversation_id) else None
    except DatabaseError:
        log.warning("crisis memory: could not read a conversation's mark; refusing remember")
        return REFUSAL
