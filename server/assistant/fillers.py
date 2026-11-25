"""What the assistant says while a tool runs: one short, honest line naming what it is actually
doing (Kevin, 2026-11-17: fillers should say what Alfred is doing, "checking with the cloud").

Rules (applied in `assistant.cascade`):
- **Only from a tool call that has started.** Never before the model picks a tool, never naming a
  result: every line says what is being looked at, none says what was found or done.
- **When.** A network lookup (marked True here: the web, recalls, weather feeds, GitHub: outside
  services, never quick) gets its line the moment it starts, once the door would accept the call
  (a refused tool is never announced); web_search measured 1.4 to 2.3 s. Any other lookup gets
  its line only if the answer is still not in hand `cascade.FILLER_WAIT_S` (0.7 s, the phone's
  `ToolFillers.FAST_DELAY_MS`) after it started (Kevin, 2026-11-19,
  perf/try-faster-first-word): a quick answer, or one code speaks at
  once (his profile), gets none, so a line never holds an answer up behind it. Never once an answer
  sentence is ready, and never after the first question's acknowledgement (one line per turn).
- **Never twice running, and one TASK line a turn.** Two or three light variants each. The holding
  lines that fill a long wait are chained, up to six fillers of all kinds in a turn, and their rule
  is `agents/voice/wait_lines.py` (the phone's `WaitFillers`), not this table's.
- **From memory.** Each line's speech is made once per voice and kept in the process
  (`cascade.FillerAudio`), so it starts at once; a line not yet in memory is skipped and fetched.
- **Every tool a role can reach is mapped or deliberately silent**: a test walks both roles' tool
  lists. Writes are silent: they are quick, and "adding that" before the write's result would read
  as a claim.
"""

from __future__ import annotations

import random
import re
import threading

_ACCOUNTS = ("Checking the accounts.", "Looking at the accounts.", "Pulling up the numbers.")
_CALENDAR = ("Checking the calendar.", "Looking at the calendar.")
_CAR = ("Checking the car's records.", "Looking at the car's records.")
_LISTS = ("Checking the lists.", "Looking at the lists.")
_PURCHASES = ("Checking what's been bought.", "Looking through the purchases.")
_RECORDS = ("Checking the records.", "Looking through the records.")
_BODY = ("Checking the training log.", "Looking at the body records.")
_MEMORY = ("Checking what I remember.", "Looking through my notes.")

# tool -> (said at its start: a network lookup, never quick; lines)
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
    "brief_latest": (False, ("Pulling up the brief.", "Checking the brief.")),
    "check_conditions": (True, ("Checking the conditions.", "Looking at the weather feeds.")),
    "github_status": (True, ("Checking GitHub.", "Looking at the builds.")),
    "board_status": (False, ("Checking the board.", "Looking at what's in flight.")),
    "add_backlog_idea": (False, ("Scoring the idea.", "Weighing that one up.")),
    # LEGION itself and the demo
    "legion_docs": (False, ("Looking through the design docs.", "Checking the docs.")),
    "show_diagram": (False, ("Bringing up the diagram.", "Pulling up the diagram.")),
    "about_kevin": (False, ("Pulling up Kevin's profile.", "Checking Kevin's profile.")),
    "show_resume": (False, ("Bringing up the resume.", "Pulling up the resume.")),
    "run_procedure": (False, ("Working through the steps.", "Running that now.")),
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

# The chained holding lines on a long wait (`agents/voice/wait_lines.py`, the phone's
# `WaitFillers.LINES`). Named here only so `all_lines()` warms them: which one is said when is that
# module's rule, not this table's. Kept out of `FILLERS` because no tool owns them. "Working on it."
# is already `FALLBACK`'s first line, so the set costs four new renders per voice, not five.
WAIT = ("Still on it.", "Still checking.", "One moment more.", "Bear with me.", "Working on it.")

# The acknowledgement said at once on a turn of theirs, before any tool starts.
#
# These are the PHONE's backchannel words (`app/.../service/cascade/Backchannel.kt` LINES), pinned
# by `test_assistant_wait_lines.py`. Kevin, 2026-11-24: the demo's voice behavior is the phone's and
# only the persona differs, so the words are the phone's too.
#
# They were "Yeah, one sec." and three variants until then. Content-free matters for more than
# faithfulness: "one sec" PROMISES a look, so it could only be said on a turn that was going to take
# one, which is why the web skipped small talk and sign-offs and said nothing at all on them. "Mm."
# claims nothing, so it needs no such skip and every turn of theirs can have one, as on the phone.
ACK_TOOL = "__ack__"
ACK = ("Mm.", "Right.", "Yeah.", "Hmm.", "Okay.", "Mhm.", "Uh-huh.", "Alright.")


# The phone's goodbye words (`app/.../service/Farewells.kt` FAREWELLS), pinned by
# `test_assistant_wait_lines.py`. The engine's own stop rule (`is_stop_utterance`) is a narrower
# list built for CLOSING a conversation and does not know a plain "bye", so the acknowledgement
# needed the phone's list to skip where the phone skips. Used for that skip only: what closes a web
# conversation is unchanged.
FAREWELLS = (
    "bye", "bye bye", "byebye", "buh bye", "bye now", "goodbye", "good bye", "bye for now",
    "good night", "goodnight", "night", "night night", "nighty night", "nite", "nite nite",
    "g night",
    "see you", "see ya", "see you later", "see ya later", "see you tomorrow", "see you soon",
    "talk to you later", "talk later", "talk soon", "catch you later", "later", "ciao", "cheerio",
    "farewell", "peace out", "take care", "have a good night", "have a good one",
)  # fmt: skip

# Courtesy at either end, which the phone takes off before judging a goodbye
# (`Farewells.withoutCourtesy`), so "thanks, bye" and "okay bye" are goodbyes.
_COURTESY = frozenset({"thanks", "thank", "you", "please", "ok", "okay", "alright", "right"})

_NOT_WORD = re.compile(r"[^a-z0-9]+")


def _normalised(words: str | None) -> list[str]:
    """The phone's `Farewells.normalise`: lowercase, every run of non-letters one space."""
    return _NOT_WORD.sub(" ", (words or "").lower()).split()


def is_farewell(words: str | None) -> bool:
    """The whole turn is a goodbye and nothing else (the phone's `Farewells.madeOf`): one or more
    farewell phrases, with courtesy allowed at either end. Pure."""
    said = _normalised(words)
    while said and said[0] in _COURTESY:
        said = said[1:]
    while said and said[-1] in _COURTESY:
        said = said[:-1]
    if not said:
        # Courtesy and nothing else ("Thanks!"), or silence: not a goodbye. Without this an empty
        # list fell out of the loop below as True and every "Thanks!" turn lost its backchannel.
        return False
    # Longest phrase first, so "bye bye" is one phrase and not "bye" twice.
    phrases = sorted(FAREWELLS, key=lambda p: -len(p.split()))
    while said:
        for phrase in phrases:
            parts = phrase.split()
            if said[: len(parts)] == parts:
                said = said[len(parts) :]
                break
        else:
            return False
    return True


def ack_skip(*, from_user: bool, over_reply: bool, words: str, crisis: bool, goodbye: bool):
    """Why this turn says no acknowledgement, or None to say one.

    The phone's rule (`app/.../service/cascade/Backchannel.kt`, `decide` then `wordsSkip`), in its
    order, minus the three skips that are phone routes a browser does not have: `quiet`, `on_call`
    and `half_duplex`. Kevin, 2026-11-24: the guide and Alfred behave the same in every voice
    behavior and the phone is the default, so the phone's list is the list.

    The phone plays one on EVERY turn of his, with no small-talk and no minimum-length skip, and
    this is now the same list. The web used to say one only on a conversation's FIRST utterance and
    only when the words looked like work, which left every later question silent until the answer.

    Small talk and sign-offs get one too, as on the phone. That only became right once the words
    became content-free (see `ACK`): "Mm." after "Thanks!" is what the phone says, where
    "Yeah, one sec." would have promised a look nobody asked for.

    Pure: the caller answers each question, so nothing here reads a request or a clock.
    """
    if not from_user:
        return "not_his"
    if over_reply:
        return "over_reply"
    if not (words or "").strip():
        return "noise"
    if crisis:
        return "crisis"
    if goodbye or is_farewell(words):
        return "goodbye"
    return None


def all_lines() -> tuple[str, ...]:
    """Every line, in the order they are fetched (`cascade.FillerAudio.warm`, during the greeting):
    the acknowledgement first, then one line for each slow tool, then the fallback, then the wait
    lines, then the rest, so a turn right after the greeting already has what it needs (2026-11-18:
    it once had none). The wait lines sit behind the fallback so the order a first turn depends on
    is unchanged (`test_demo_voice_first`), and ahead of the per-tool lines because every waiting
    turn can need them, tool or no tool (`agents/voice/wait_lines.py`)."""
    seen: dict[str, None] = dict.fromkeys(ACK)
    for slow, lines in FILLERS.values():
        if slow and lines:
            seen.setdefault(lines[0])
    for line in (*FALLBACK, *WAIT):
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
