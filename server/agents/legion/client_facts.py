"""What the limb tells the engine about a turn (engine-brain plan 1119 section 1 and 4b; the
phone client's gap G3): `client_facts` in the turn request or the stream's metadata frame.

- `language`: the language the phone decided this turn is in (its whole-turn switch). The engine
  adds "(This conversation is in X: reply in X.)" to his turn, the phone's own note.
- `carry_tainted`: the line before this turn read outside text (a brief, a mail reply): the turn
  starts tainted, so no write follows it (ADR 0090's carry; plan 4b rule 5).
- `prior_line`: the last line the PHONE spoke (a greeting, a check-in, an opener), up to 300
  characters, so his reply has its context; never a read-through line (those send
  `carry_tainted` instead). It goes in the bracketed context, never in history as the model's.
- `test_mode`: the phone's test door (`TestTurnPolicy`): every write and every acting limb call
  is refused in words; reads run. Also skips the late-answer path (a background task is a write).
- `utc_offset_minutes`: the device's own clock offset, used for "today" and "tomorrow" (the
  household's zone otherwise). The engine's own clock stays the time: only the offset is taken.
- `heard_until`: `{turn_id, chars}`: how much of an EARLIER turn's reply he actually heard before
  he barged in (inventory row 23). That turn's stored history keeps only those characters of its
  last answer and says he cut in; so the next turn's model knows what he heard, not what it wrote.
  Sent with the next turn (no extra request): its only reader is the next turn.
"""

from __future__ import annotations

import re

from rest_framework import serializers

from agents.models import AgentTurn

PRIOR_LINE_CHARS = 300
# The taint a carried turn starts with (the phone's own `read_through source=carry`).
CARRY = "carry"
CUT_IN_NOTE = "[He cut in here; he did not hear the rest.]"
TEST_MODE_WORDS = (
    "Nothing was changed: this is a test turn, so {name} did not run. Say it would have run."
)
_LANGUAGE = re.compile(r"^[A-Za-z][A-Za-z \-]{0,31}$")


class HeardUntilSerializer(serializers.Serializer):
    turn_id = serializers.UUIDField()
    chars = serializers.IntegerField(min_value=0, max_value=100_000)


class ClientFactsSerializer(serializers.Serializer):
    language = serializers.RegexField(_LANGUAGE, required=False)
    carry_tainted = serializers.BooleanField(required=False, default=False)
    prior_line = serializers.CharField(
        max_length=PRIOR_LINE_CHARS, required=False, allow_blank=True, trim_whitespace=True
    )
    test_mode = serializers.BooleanField(required=False, default=False)
    utc_offset_minutes = serializers.IntegerField(min_value=-840, max_value=840, required=False)
    heard_until = HeardUntilSerializer(required=False)


def parse(raw) -> tuple[dict, str | None]:
    """(facts, problem): the validated facts, or the words saying why they do not fit."""
    if raw in (None, ""):
        return {}, None
    if not isinstance(raw, dict):
        return {}, "`client_facts` is an object."
    body = ClientFactsSerializer(data=raw)
    if not body.is_valid():
        return {}, f"`client_facts` did not fit: {body.errors}"
    facts = dict(body.validated_data)
    if "heard_until" in facts:
        facts["heard_until"] = {
            "turn_id": str(facts["heard_until"]["turn_id"]),
            "chars": facts["heard_until"]["chars"],
        }
    if "\x00" in (facts.get("prior_line") or ""):
        return {}, "`prior_line` may not hold a NUL character."
    return facts, None


def context_notes(facts: dict) -> list[str]:
    """The parts added to his turn's user content, after the engine's own context line."""
    notes = []
    if facts.get("prior_line"):
        notes.append(
            f'[Context, not said by the person: the device just said: "{facts["prior_line"]}"]'
        )
    if facts.get("language"):
        language = facts["language"].strip()
        notes.append(f"(This conversation is in {language}: reply in {language}.)")
    return notes


def trim_heard(conversation, facts: dict) -> bool:
    """Cuts an earlier turn's stored answer to what he heard (module docstring). Only a turn of
    this conversation; only its last model words. True when something was cut."""
    heard = facts.get("heard_until")
    if not heard:
        return False
    turn = conversation.turns.filter(pk=heard["turn_id"]).first()
    if turn is None or not turn.history:
        return False
    history = [dict(c) for c in turn.history]
    last = next(
        (i for i in range(len(history) - 1, -1, -1) if history[i].get("role") == "model"), None
    )
    if last is None:
        return False
    budget = int(heard["chars"])
    parts = []
    cut = False
    for part in history[last].get("parts") or []:
        if "text" not in part:
            parts.append(part)
            continue
        text = part["text"]
        if cut:
            continue
        if len(text) > budget:
            part = {**part, "text": text[:budget].rstrip()}
            cut = True
        budget -= len(part["text"])
        parts.append(part)
    if not cut:
        return False
    parts.append({"text": CUT_IN_NOTE})
    history[last] = {**history[last], "parts": [p for p in parts if p.get("text", "x")]}
    AgentTurn.objects.filter(pk=turn.pk).update(history=history)
    return True
