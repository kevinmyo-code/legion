"""What the assistant says while a tool runs: one short, honest line naming what it is actually
doing (Kevin, 2026-11-17: fillers should say what Alfred is doing, "checking with the cloud").

Rules (applied in `assistant.cascade`):
- **Only from a tool call that has started.** Never before the model picks a tool, never naming a
  result: every line says what is being looked at, none says what was found or done.
- **When.** A tool marked slow here gets its line the moment it starts, and only once the door
  would accept the call (a refused tool is never announced). Every lookup is marked slow (Kevin,
  2026-11-19, perf/try-faster-first-word fix 1): a fast tool's result is in hand at once, but the
  model's next round still takes about a second before any answer, and later questions were
  silent until then. Never once any answer has been said, and never after the first question's
  acknowledgement (one line per turn at most).
- **Never twice running, never two in a turn.** Two or three light variants each.
- **From memory.** Each line's speech is made once per voice and kept in the process
  (`cascade.FillerAudio`), so it starts at once; a line not yet in memory is skipped and fetched.
- **Every tool a role can reach is mapped or deliberately silent**: a test walks both roles' tool
  lists. Writes are silent: they are quick, and "adding that" before the write's result would read
  as a claim.
"""

from __future__ import annotations

import random
import threading

_ACCOUNTS = ("Checking the accounts.", "Looking at the accounts.", "Pulling up the numbers.")
_CALENDAR = ("Checking the calendar.", "Looking at the calendar.")
_CAR = ("Checking the car's records.", "Looking at the car's records.")
_LISTS = ("Checking the lists.", "Looking at the lists.")
_PURCHASES = ("Checking what's been bought.", "Looking through the purchases.")
_RECORDS = ("Checking the records.", "Looking through the records.")
_BODY = ("Checking the training log.", "Looking at the body records.")
_MEMORY = ("Checking what I remember.", "Looking through my notes.")

# tool -> (slow, lines)
FILLERS: dict[str, tuple[bool, tuple[str, ...]]] = {
    # money
    "monthly_spend": (True, _ACCOUNTS),
    "safe_to_spend": (True, _ACCOUNTS),
    "net_worth": (True, _ACCOUNTS),
    "cash_flow": (True, _ACCOUNTS),
    "runway": (True, _ACCOUNTS),
    "budgets": (True, _ACCOUNTS),
    "account_balances": (True, _ACCOUNTS),
    "grocery_spend": (True, _ACCOUNTS),
    # calendar and lists
    "list_events": (True, _CALENDAR),
    "greeting_facts": (True, ("Checking your day.", "Looking at today.")),
    "list_checklists": (True, _LISTS),
    "last_ticked": (True, _LISTS),
    "last_bought": (True, _PURCHASES),
    "list_purchases": (True, _PURCHASES),
    "read_records": (True, _RECORDS),
    "list_tables": (True, _RECORDS),
    # car
    "next_service": (True, _CAR),
    "vehicle_recalls": (True, ("Checking the recall list.", "Looking up recalls for the car.")),
    # outside
    "web_search": (True, ("Looking that up online.", "Searching the web.", "Checking online.")),
    "brief_latest": (True, ("Pulling up the brief.", "Checking the brief.")),
    "check_conditions": (True, ("Checking the conditions.", "Looking at the weather feeds.")),
    "github_status": (True, ("Checking GitHub.", "Looking at the builds.")),
    # LEGION itself and the demo
    "legion_docs": (True, ("Looking through the design docs.", "Checking the docs.")),
    "show_diagram": (True, ("Bringing up the diagram.", "Pulling up the diagram.")),
    "about_kevin": (True, ("Pulling up Kevin's profile.", "Checking Kevin's profile.")),
    "show_resume": (True, ("Bringing up the resume.", "Pulling up the resume.")),
    "run_procedure": (True, ("Working through the steps.", "Running that now.")),
    # school and body
    "canvas_grades": (True, ("Checking the grades.", "Looking at the grades.")),
    "what_i_need": (True, ("Checking the grades.", "Working out the numbers.")),
    "get_energy_estimate": (True, _BODY),
    "get_weight_trend": (True, _BODY),
    "list_saved_meals": (True, _BODY),
    "get_todays_workout": (True, _BODY),
    "get_exercise_alternatives": (True, _BODY),
    "get_body_plan": (True, _BODY),
    "suggest_meal": (True, ("Thinking about meals.", "Looking at meal options.")),
    # memory
    "recall_memory": (True, _MEMORY),
    "list_memories": (True, _MEMORY),
}

# Deliberately silent: writes (quick, and a line before the result would read as a claim) and the
# conversation's own control.
SILENT: frozenset[str] = frozenset(
    {
        "end_conversation",
        "write_record",
        "delete_record",
        "add_event",
        "update_event",
        "delete_event",
        "add_checklist_item",
        "tick_checklist_item",
        "create_checklist",
        "untick_checklist_item",
        "remove_checklist_item",
        "archive_checklist",
        "rename_place",
        "skip_event_occurrence",
        "log_purchase",
        "delete_purchase",
        "remember",
        "forget_memory",
        "set_body_profile",
        "log_waist",
        "save_meal",
        "delete_saved_meal",
        "log_saved_meal",
        "accept_energy_proposal",
        "dismiss_energy_proposal",
        "start_workout",
        "tick_workout_set",
        "swap_exercise",
        "finish_workout",
        "propose_programme_change",
        "accept_programme_change",
        "dismiss_programme_change",
        "log_meal_option",
        "draft_body_plan",
        "lock_body_plan",
        "discard_body_plan",
    }
)

FALLBACK = ("Working on it.", "One moment.")

# The acknowledgement said at once when a turn clearly needs a look (voice-persona section 8.2),
# before any tool starts. It claims nothing: it says a look is coming, not that one ran.
ACK_TOOL = "__ack__"
ACK = ("Yeah, one sec.", "Sure, one sec.", "Okay, one sec.", "Mm, one sec.")


def all_lines() -> tuple[str, ...]:
    """Every line, in the order they are fetched (`cascade.FillerAudio.warm`, during the greeting):
    the acknowledgement first, then one line for each slow tool, then the fallback, then the rest,
    so a turn right after the greeting already has what it needs (2026-11-18: it once had none)."""
    seen: dict[str, None] = dict.fromkeys(ACK)
    for slow, lines in FILLERS.values():
        if slow and lines:
            seen.setdefault(lines[0])
    for line in FALLBACK:
        seen.setdefault(line)
    for _slow, lines in FILLERS.values():
        for line in lines:
            seen.setdefault(line)
    return tuple(seen)


def is_slow(tool: str) -> bool:
    return FILLERS.get(tool, (False, ()))[0]


def lines_for(tool: str) -> tuple[str, ...]:
    """The lines for a tool that has started, or () when it is deliberately silent. An unmapped
    tool (one added later) gets the honest fallback."""
    if tool == ACK_TOOL:
        return ACK
    if tool in SILENT:
        return ()
    found = FILLERS.get(tool)
    return found[1] if found else FALLBACK


class LinePicker:
    """Never the same line twice running, across the whole process (one picker per conversation
    would forget across turns)."""

    def __init__(self, choose=random.choice):
        self._last: str | None = None
        self._lock = threading.Lock()
        self._choose = choose

    def pick(self, options) -> str | None:
        options = list(options)
        if not options:
            return None
        with self._lock:
            fresh = [o for o in options if o != self._last]
            if not fresh:
                # The only line in hand was the last one said: nothing, rather than a repeat.
                return None
            line = self._choose(fresh)
            self._last = line
            return line
