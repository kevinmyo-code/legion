"""Calendar entries someone else wrote are outside text on the agent turn (ADR 0090, ADR 0098;
relay report 12.4, owed before `LEGION_AGENTS_TURN` goes on).

An invite or a shared calendar puts another person's words in his calendar: a title, a place, a
note. Read back to the model, they can carry instructions that are not his, exactly as a web page
can. So when an engine tool hands the model such an event, the turn is marked as having read
outside text, before the result reaches the model: writes refuse for this turn and the next (the
carry), the reply after it is not kept, and a limb call in it never pauses (K2).

- **Whose words.** An event is HIS when its `source` is the household's own entry
  (`OWN_SOURCE`): nothing a member typed taints, whatever its organizer field says. Any other
  source is IMPORTED (a synced calendar, `google` today). An imported event is his own when its
  `organizer` is `self` (its calendar says its owner organized it) or a member's address. An
  imported event with another organizer is someone else's.
- **Organizer unknown** (every row before the column, and any client that does not send it):
  `UNKNOWN_ORGANIZER_IS_OUTSIDE`, the ONE switch, built strict (True) by the orchestrator's brief;
  the owner decides. True: "what's on today" with an imported event in the day refuses a change
  in that turn and the next. False: only a known outside organizer does.
- **Which reads.** `EVENT_READERS`, the engine tools whose results carry event rows; each row
  carries its id, so the events the model is handed are exactly the ids in the result (one query).
- **The mark is saved first** (`TurnStore.mark_taint`). If it cannot be saved the result is
  withheld from the model (`MARK_FAILED`), and the turn stays marked in memory.
- Not covered here: the web door (live, unchanged) and the relayed phone calendar (its own
  `calendar` class taints every call, E2).
"""

from __future__ import annotations

import json
import re

from household.tenancy import household_of, visible

# The household's own entries (`api.events.SOURCE_CHOICES`); every other source is imported.
OWN_SOURCE = "legion"
# The organizer a syncing client writes when the calendar says its own owner organized the event.
ORGANIZER_SELF = "self"
# The owner's choice (pending): an imported event with no known organizer counts as outside text.
# Flip to False to taint only a known outside organizer.
UNKNOWN_ORGANIZER_IS_OUTSIDE = True
# The turn's taint source name (`AgentTurn.taint_sources`); not a carry name, so it carries.
TAINT_SOURCE = "calendar"
# Engine tools whose results carry event rows, each with its id.
EVENT_READERS = frozenset(
    {
        "read_calendar",
        "read_list",
        "list_events",
        "greeting_facts",
        "read_records",
        "update_event",
        "delete_event",
        "skip_event_occurrence",
    }
)
MARK_FAILED = (
    "Nothing was read. The engine could not record that this turn read calendar entries other "
    "people wrote, so it did not use them; say it could not be done just now."
)

_ID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", re.IGNORECASE)


def reads_outside(request, name: str, result) -> bool:
    """Whether `result` (a `ToolResult` of tool `name`) hands the model an event someone outside
    the household wrote. No query for any other tool, a failed call, or a result with no ids."""
    if name not in EVENT_READERS or result.is_error:
        return False
    found = set(_ID.findall(result.text or ""))
    if result.structured is not None:
        found |= set(_ID.findall(json.dumps(result.structured, default=str)))
    if not found:
        return False
    from legacy.models.dates import Event

    organizers = list(
        visible(Event, request)
        .filter(pk__in={i.lower() for i in found})
        .exclude(source=OWN_SOURCE)
        .values_list("organizer", flat=True)
    )
    if not organizers:
        return False
    if UNKNOWN_ORGANIZER_IS_OUTSIDE and any(not (o and o.strip()) for o in organizers):
        return True
    known = {o.strip().lower() for o in organizers if o and o.strip()}
    known.discard(ORGANIZER_SELF)
    if not known:
        return False
    return bool(known - _member_addresses(request))


def _member_addresses(request) -> set[str]:
    from household.models import HouseholdMember

    rows = HouseholdMember.objects.filter(household=household_of(request)).values_list(
        "user__email", flat=True
    )
    return {e.strip().lower() for e in rows if e}
