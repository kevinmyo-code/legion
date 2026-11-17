"""What the assistant says while a tool runs: one short, honest line naming what it is actually
doing (Kevin, 2026-11-17: fillers should say what Alfred is doing, "checking with the cloud").

Rules (applied in `assistant.cascade`):
- **Only from a tool call that has started.** Never before the model picks a tool, never naming a
  result: every line says what is being looked at, none says what was found or done.
- **When.** A tool marked slow here gets its line the moment it starts (the turn's stream is held
  while an engine tool runs, so a line can only go out before it). A fast tool's line goes out
  after it returns, and only when the person has already waited `FAST_AFTER_MS` with nothing said,
  to fill the model's next round; never once any answer has been said.
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

FAST_AFTER_MS = 700

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
    "monthly_spend": (False, _ACCOUNTS),
    "safe_to_spend": (False, _ACCOUNTS),
    "net_worth": (False, _ACCOUNTS),
    "cash_flow": (False, _ACCOUNTS),
    "runway": (False, _ACCOUNTS),
    "budgets": (False, _ACCOUNTS),
    "account_balances": (False, _ACCOUNTS),
    "grocery_spend": (False, _ACCOUNTS),
    # calendar and lists
    "list_events": (False, _CALENDAR),
    "greeting_facts": (False, ("Checking your day.", "Looking at today.")),
    "list_checklists": (False, _LISTS),
    "last_ticked": (False, _LISTS),
    "last_bought": (False, _PURCHASES),
    "list_purchases": (False, _PURCHASES),
    "read_records": (False, _RECORDS),
    "list_tables": (False, _RECORDS),
    # car
    "next_service": (False, _CAR),
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
    "canvas_grades": (False, ("Checking the grades.", "Looking at the grades.")),
    "what_i_need": (False, ("Checking the grades.", "Working out the numbers.")),
    "get_energy_estimate": (False, _BODY),
    "get_weight_trend": (False, _BODY),
    "list_saved_meals": (False, _BODY),
    "get_todays_workout": (False, _BODY),
    "get_exercise_alternatives": (False, _BODY),
    "get_body_plan": (False, _BODY),
    "suggest_meal": (False, ("Thinking about meals.", "Looking at meal options.")),
    # memory
    "recall_memory": (False, _MEMORY),
    "list_memories": (False, _MEMORY),
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


def all_lines() -> tuple[str, ...]:
    seen: dict[str, None] = {}
    for _slow, lines in FILLERS.values():
        for line in lines:
            seen[line] = None
    for line in FALLBACK:
        seen[line] = None
    return tuple(seen)


def is_slow(tool: str) -> bool:
    return FILLERS.get(tool, (False, ()))[0]


def lines_for(tool: str) -> tuple[str, ...]:
    """The lines for a tool that has started, or () when it is deliberately silent. An unmapped
    tool (one added later) gets the honest fallback."""
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
            fresh = [o for o in options if o != self._last] or options
            line = self._choose(fresh)
            self._last = line
            return line
