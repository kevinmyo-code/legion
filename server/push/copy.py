"""Every word a notification says (web-revamp ticket 15, spec D7).

**The compulsion test, CLAUDE.md section 7, applied here.** Every message
names a fact the person can check (a list item, an event time, a due date),
is actionable now, never mentions their absence, a streak or how they use
the app, and carries "Turn these off". `tests/test_push.py` greps every
string in this module for "haven't", "miss", "streak", "days since" and
"come back", so a future edit cannot drift into a re-engagement ping.

Plain and warm, no assistant name (D14). All strings live here so the grep
sees all of them.
"""

from __future__ import annotations

PUSH_OFF = "Notifications are not set up on this server."
PUSH_OFF_NOTHING_SENT = PUSH_OFF + " Nothing was sent."
PUSH_OFF_NOTHING_SAVED = PUSH_OFF + " Nothing was saved."

TURN_OFF_ACTION = "Turn these off"

TITLE_MORNING = "Due today"

KIND_NAMES = {
    "list_changes": "list changes",
    "event_reminders": "event reminders",
    "task_due_morning": "the morning list of what is due",
}

TURNED_OFF = "You will not get {kind} any more. Turn them back on in Settings, Notifications."
UNKNOWN_KIND = "{kind!r} is not a kind of notification. Use one of: {kinds}. Nothing was changed."
NOT_YOUR_SUBSCRIPTION = "No subscription of yours has that id. Nothing was removed."


def _names(names: list[str], *, shown: int) -> str:
    """ "oat milk", "oat milk and eggs", "oat milk, eggs and 2 more"."""
    if len(names) == 1:
        return names[0]
    if len(names) <= shown:
        return ", ".join(names[:-1]) + " and " + names[-1]
    return ", ".join(names[:shown]) + f" and {len(names) - shown} more"


def list_added(who: str, items: list[str], list_name: str) -> str:
    """ "Kevin added oat milk, eggs and 2 more to Groceries." """
    return f"{who} added {_names(items, shown=2)} to {list_name}."


def _lead(minutes: int) -> str:
    if minutes == 0:
        return "starting now"
    if minutes % 60 == 0:
        hours = minutes // 60
        return "in 1 hour" if hours == 1 else f"in {hours} hours"
    return f"in {minutes} minutes"


def event_reminder(title: str, clock: str, minutes: int) -> str:
    """ "Dentist at 3:00 pm, in 30 minutes." A day ahead says tomorrow."""
    if minutes == 1440:
        return f"{title} tomorrow at {clock}."
    return f"{title} at {clock}, {_lead(minutes)}."


def all_day_reminder(title: str, minutes: int) -> str:
    return f"{title} is tomorrow." if minutes >= 1440 else f"{title} is today."


def morning(titles: list[str]) -> str:
    """ "3 things due today: HW 4, rent, return library books." """
    count = len(titles)
    noun = "thing" if count == 1 else "things"
    shown = titles if count <= 5 else titles[:5] + [f"{count - 5} more"]
    return f"{count} {noun} due today: {', '.join(shown)}."


def clock(hour: int, minute: int) -> str:
    """3:00 pm. Lower case, no leading zero, the way a person says it."""
    suffix = "am" if hour < 12 else "pm"
    return f"{(hour % 12) or 12}:{minute:02d} {suffix}"
