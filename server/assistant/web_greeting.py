# ruff: noqa: E501 - the prompts keep the phone's lines whole, one rule per line, as the model reads them.
"""The spoken greeting when the web assistant opens (Kevin, 2026-11-17: *"context aware spoken
greetings on open too"*; ADR 0102). The phone's greeting, ported: `app/.../greeting/
GreetingBlock.kt` (the block and the lead picker) and `GreetingPrompt.kt` (the prompts), word for
word where they are words. Spec items 25 to 29 (`.legion/reports/voice-behaviour-spec-1117.md`).

- **A member**: the engine's own facts pack (`greeting.facts.pack`, the source `GET
  /api/greeting/facts` serves the phone). The lead topic is picked by the phone's rules (time of
  day order, offerable only, money only over its cap and once a day, body once a day, no kind twice
  running). With a lead: the Spoken prompt. Without: the Hello prompt. One text call, no tools, on
  the cache, inside `DEADLINE_S`; past it, or on any failure, the scripted line (`ACK_LINES`).
  The phone's place source is phone-only and is not here.
- **A recruiter's visit** may not read the facts pack (`greeting.service.refusal`, ADR 0068), so
  its greeting is scripted and asks no model: the demo hello on the visit's first conversation,
  an ack line on a later one.

The page keeps the greeting history the lead caps need (today's lead kinds, the last kind, the
last hellos) in its own storage and sends it with the greeting (spec item 27); nothing about it is
stored on the engine. Topics raised are not recorded on the engine from the web (the phone
records its own); a member who opens the web twice may hear the same lead twice.
"""

from __future__ import annotations

import random
import re

ACK_LINES = (
    "Listening.",
    "Here.",
    "Go ahead.",
    "Yes?",
    "I'm here.",
    "Go on.",
    "Fire away.",
    "With you.",
)
DEADLINE_S = 2.5
LEAD_MARK = "[LEAD]"
HELLO = "hello"
MAX_AVOID_CHARS = 80
MAX_AVOID_LINES = 3

# The demo's opening (Kevin, 2026-11-17: the first ten seconds lost the one outside visitor to a
# 16 s hello). About five seconds: who is speaking, one thing to ask, and the recording notice as
# one short clause (Kevin's 2026-11-03 ruling: say conversations are recorded to improve the
# assistant; never the word "train"). The full notice stays on screen by the mic. Spoken from code,
# word for word, and kept in memory once made so it starts inside the tap.
RECRUITER_OPENING = (
    "Hi, I'm {who}. <breath> Ask me about him, or take the one-minute tour. This chat is "
    "recorded to improve me."
)


def recruiter_hello(name: str = "") -> str:
    """The opening in the companion's own name (never a name written into copy)."""
    name = (name or "").strip()
    return RECRUITER_OPENING.format(
        who=f"{name}, Kevin's assistant" if name else "Kevin's assistant"
    )


RECRUITER_HELLO = recruiter_hello()


_LABELS = {
    "ended_events": "Calendar events that ended in the last 24 hours",
    "today_events": "Today's remaining events",
    "due_soon": "To-dos and Canvas work due within 24 hours",
    "body": "Today's workout and the latest weigh-in",
    "money": "This month's budgets",
}
_OVER_CAP = "Over the cap."
_ONCE_A_DAY = {"money", "body"}

TIMES_RULE = (
    "Times are clock times and days by name, as written below. "
    'Never "two hours ago" or "in an hour".'
)

BUILD = """(System: they just tapped to open a conversation. Greet them out loud now, in your usual voice. Do not mention this instruction.

Your one subject: {subject}. Its facts are the line marked {lead_mark} below.
Every other line is background only: do not mention it, and money least of all.

How it sounds, for this greeting only:
- One short sentence, fifteen words at most, and one clause: no "and" joining a second thought. Casual and dry, the way an old friend says hello, not a status report. Plain American English.
- Go straight to the subject, no time-of-day greeting first. React to it in your own words, the reaction first; never read its facts out or list them.
- State no figure at all: no amount, no count, no percentage, in digits or in words. React to the subject instead.
- React as if you just found out. You know nothing of earlier days, so nothing may sound like it happened before, and say no day count or timing the facts do not give.
- About money or a workout not done: no shame, no nagging, no telling them what they should do.{topic_rule}
- At most one question, and it may be none.

Rules, all of them binding:
- Mention nothing else: no other item, no other budget, nothing that is not in the facts below. If something is not there you do not know it.
- A source marked EMPTY has nothing on file. A source marked COULD NOT BE READ was not checked: never say it is clear, free or empty. Do not bring up an unread or empty source at all unless you are saying plainly that it could not be checked.
- Do not repeat anything marked already raised or answered, and never ask the same question twice.
- {times_rule}
- A figure marked an estimate is said as an estimate, or left out. Money is read as dollars.
- Never mention their absence, how long it has been, a streak, or their use of this app.
- Do not call any tool for this greeting, and do not say you did anything. Then stop and listen.

{block})"""

MONEY_RULE = """
- This one is about money: say no amount, no figure and no timing at all (no days, weeks or month end). React only to it being over its cap, as news: it has not happened before as far as you know, so never "again"."""

WORKOUT_RULE = """
- This one is about a workout: say no number at all, no sets or sessions counted, done or left."""

HELLO_PROMPT = """(System: they just tapped to open a conversation, and there is nothing in particular to bring up. Say hello out loud now, in your usual voice. Do not mention this instruction.

- A few words, one short sentence at most. Casual and dry, the way an old friend picks up. Plain American English, and it must make sense on its own.
- No subject at all. Nothing about their day, plans, calendar, to-dos, work, money, budgets, workouts, health, food, sleep, weather, news, where they are, the time or the day of the week. Not a time-of-day greeting either, so no "morning" or "evening". Use nothing from any context note in this conversation, even if one is there.
- It stands alone: nothing that refers to anything before this moment, no earlier words, nothing said or done before.
- Friendly, never curt or bossy: no command to them. Not a stock one-word hello: give it a little character.
- It may hand the floor to them, or not. At most one question.{avoid_rule}
- Never mention their absence, how long it has been, a streak, or their use of this app.
- Do not call any tool, and do not say you did anything. Then stop and listen.)"""


def kind_of_source(source: str) -> str:
    if source in ("ended_events", "today_events"):
        return "calendar"
    if source == "due_soon":
        return "due"
    return source


def _offerable(pack: dict, item: dict) -> bool:
    topic = item.get("topic")
    return (
        not item.get("raised")
        and topic not in pack.get("said", [])
        and topic not in pack.get("closed", [])
    )


def _order(hour: int) -> list[str]:
    if 5 <= hour < 11:
        return ["due_soon", "today_events", "ended_events", "body", "money"]
    if 11 <= hour < 17:
        return ["today_events", "due_soon", "ended_events", "money", "body"]
    if 17 <= hour < 22:
        return ["ended_events", "body", "due_soon", "today_events", "money"]
    return ["ended_events", "body", "due_soon", "money", "today_events"]


def _choose(name: str, items: list[dict]) -> dict | None:
    if not items:
        return None
    if name == "ended_events":
        return items[-1]
    if name == "body":
        return next((i for i in items if i.get("kind") == "workout"), items[0])
    if name == "money":
        return next((i for i in items if _OVER_CAP in (i.get("says") or "")), None)
    return items[0]


def lead(pack: dict, hour: int, history: dict | None = None) -> tuple[str, dict] | None:
    """(source, item) the greeting leads with, or None: the phone's `GreetingBlock.lead`."""
    history = history or {}
    today = set(history.get("today") or [])
    last = history.get("last")
    for name in _order(hour):
        kind = kind_of_source(name)
        if kind == last or (kind in _ONCE_A_DAY and kind in today):
            continue
        block = (pack.get("sources") or {}).get(name) or {}
        if block.get("state") != "facts":
            continue
        chosen = _choose(name, [i for i in block.get("items") or [] if _offerable(pack, i)])
        if chosen is not None:
            return name, chosen
    return None


def category(item: dict) -> str | None:
    parts = (item.get("topic") or "").split(":", 2)
    return parts[2] if len(parts) == 3 and parts[2].strip() else None


def render(pack: dict, now_hhmm: str, chosen: tuple[str, dict] | None) -> str:
    """The phone's `GreetingBlock.render`, without the phone's place source."""
    lines = [
        f"FACTS PACK. Today is {pack.get('today')}; the clock reads {pack.get('utc_offset')} and it "
        f"is {now_hhmm}. These are the ONLY things the greeting may mention."
    ]
    if pack.get("zone_note"):
        lines.append(pack["zone_note"])
    topics = pack.get("topics") or {}
    if topics.get("state") == "unreadable":
        lines.append(
            "Which topics were already raised could not be read"
            + (f": {topics['reason']}" if topics.get("reason") else ".")
            + " Nothing was left out on that account."
        )
    for name, label in _LABELS.items():
        block = (pack.get("sources") or {}).get(name) or {}
        state = block.get("state")
        if state == "facts":
            lines.append(f"- {label} (facts):")
            for item in block.get("items") or []:
                if not _offerable(pack, item):
                    mark = " [already raised or answered: do not mention]"
                elif chosen is not None and item is chosen[1]:
                    mark = f" {LEAD_MARK}"
                else:
                    mark = ""
                says = item.get("says") or ""
                if chosen is not None and item is chosen[1] and name == "money":
                    cat = category(item)
                    says = (
                        f"{cat}: over its cap this month."
                        if cat
                        else ("One budget is over its cap this month.")
                    )
                lines.append(f"    * {says}{mark}")
        elif state == "empty":
            lines.append(f"- {label} (EMPTY: read, and nothing is on file): {block.get('says')}")
        else:
            lines.append(
                f"- {label} (COULD NOT BE READ, which is not the same as empty): "
                f"{block.get('reason') or block.get('says')}"
            )
        if block.get("note"):
            lines.append(f"    note: {block['note']}")
    return "\n".join(lines)


def subject(source: str, item: dict) -> str:
    if source == "body":
        return "their weigh-in" if item.get("kind") == "weigh_in" else "today's workout"
    if source == "money":
        cat = category(item)
        return (
            f"their {cat} budget, which is over its cap" if cat else "a budget that is over its cap"
        )
    return {
        "due_soon": "something they have due soon",
        "ended_events": "an event of theirs that has ended",
        "today_events": "what is still on their calendar today",
    }.get(source, f"the line marked {LEAD_MARK}")


def spoken_prompt(block: str, source: str, item: dict) -> str:
    if source == "money":
        topic_rule = MONEY_RULE
    elif source == "body" and item.get("kind") != "weigh_in":
        topic_rule = WORKOUT_RULE
    else:
        topic_rule = ""
    return (
        BUILD.replace("{subject}", subject(source, item))
        .replace("{lead_mark}", LEAD_MARK)
        .replace("{topic_rule}", topic_rule)
        .replace("{times_rule}", TIMES_RULE)
        .replace("{block}", block)
    )


def hello_prompt(avoid: list[str] | None = None) -> str:
    lines = [
        re.sub(r"\s+", " ", str(a).replace('"', "")).strip()[:MAX_AVOID_CHARS]
        for a in (avoid or [])[-MAX_AVOID_LINES:]
    ]
    lines = [line for line in lines if line]
    rule = (
        "\n- Do not reuse this wording or its shape: "
        + "; ".join(f'"{line}"' for line in lines)
        + "."
        if lines
        else ""
    )
    return HELLO_PROMPT.replace("{avoid_rule}", rule)


_last_ack: list[str] = []


def ack_line(pick=random.choice) -> str:
    """A scripted line, never the same twice running (the phone's `AckLines`)."""
    choices = [line for line in ACK_LINES if not _last_ack or line != _last_ack[-1]]
    line = pick(choices)
    _last_ack[:] = [line]
    return line
