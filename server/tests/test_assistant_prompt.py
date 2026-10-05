"""One prompt for every client (web-assistant ticket 03), checked both ways.

- The generated `assistant/shared_clauses.py` must be exactly what the
  generator makes from `shared_clauses.txt` (a stale copy fails here).
- Every clause with a Kotlin twin must match `ai/AriaBrain.kt` as it is on
  disk (the phone drifting from the source fails here), and the persona
  copies must match `ai/Personas.kt`.
- The web prompt is the web's `PromptRoleNamingTest`: no hardcoded assistant
  name outside the persona register, no "driver", no IANA zone id.

No database: these read files and build strings.
"""

from __future__ import annotations

import re
from datetime import UTC, datetime

import pytest

from assistant import gen_shared_clauses, personas, shared_clauses
from assistant.companions import ResolvedCompanion, resolve, seed_persona_key
from assistant.prompt import (
    PHONE_ONLY,
    WEB_FRAME,
    build_system_prompt,
    member_fact,
    time_fact,
    utc_offset_words,
)
from tests.kotlin_strings import ANDROID_SRC, read_constant, read_named_argument

ARIA_BRAIN = ANDROID_SRC / "ai" / "AriaBrain.kt"
PERSONAS_KT = ANDROID_SRC / "ai" / "Personas.kt"
PERSONA_NAMES = re.compile(r"\b(Alfred|Dorothy|Kratos|Zero|Aria|Jarvis)\b")
NOW = datetime(2026, 10, 5, 1, 31, tzinfo=UTC)


# =============================================================================
# The source, the generated copy, and the Kotlin twins
# =============================================================================


def test_the_generated_copy_is_current():
    rendered = gen_shared_clauses.render(gen_shared_clauses.SOURCE.read_text(encoding="utf-8"))
    on_disk = gen_shared_clauses.OUTPUT.read_text(encoding="utf-8")
    assert on_disk == rendered, "Run: python server/assistant/gen_shared_clauses.py"


def test_the_generated_constants_are_the_source_clauses():
    parsed = gen_shared_clauses.parse(gen_shared_clauses.SOURCE.read_text(encoding="utf-8"))
    assert [c.name for c in parsed] == list(shared_clauses.CLAUSES)
    for clause in parsed:
        assert shared_clauses.CLAUSES[clause.name] == clause.text
        assert getattr(shared_clauses, clause.name) == clause.text


@pytest.mark.parametrize(
    "name", [name for name, twin in shared_clauses.KOTLIN.items() if twin is not None]
)
def test_every_clause_matches_its_kotlin_twin(name):
    assert ARIA_BRAIN.exists(), f"{ARIA_BRAIN} is missing; the drift check cannot run"
    val, how = shared_clauses.KOTLIN[name]
    kotlin = read_constant(ARIA_BRAIN, val)
    clause = shared_clauses.CLAUSES[name]
    if how == "whole":
        assert kotlin == clause, f"{name} has drifted from AriaBrain.kt's {val}"
    else:
        assert clause.rstrip() in kotlin, f"{name} is no longer inside AriaBrain.kt's {val}"


def test_the_frame_and_the_honesty_clause_are_whole_twins():
    """Ticket 07's brief names these two: reproduced verbatim, not paraphrased."""
    assert shared_clauses.KOTLIN["ASSISTANT_FRAME"] == ("ASSISTANT_FRAME", "whole")
    assert shared_clauses.KOTLIN["CANNOT_CLAUSE"] == ("CANNOT_CLAUSE", "whole")


@pytest.mark.parametrize("persona", personas.BUILT_IN_PERSONAS, ids=lambda p: p.key)
def test_every_persona_copy_matches_personas_kt(persona):
    obj = persona.key.upper()
    for field, kotlin_arg in (
        ("key", "key"),
        ("default_name", "defaultName"),
        ("suggested_voice", "suggestedVoice"),
        ("clause", "clause"),
        ("delivery", "delivery"),
    ):
        assert getattr(persona, field) == read_named_argument(PERSONAS_KT, obj, kotlin_arg), (
            obj,
            field,
        )


def test_the_source_refuses_what_would_not_join_cleanly():
    with pytest.raises(gen_shared_clauses.SourceError, match="double space"):
        gen_shared_clauses.parse("[A]\nclients: web\nkotlin: none\n\nOne  two.\n")
    with pytest.raises(gen_shared_clauses.SourceError, match="kotlin"):
        gen_shared_clauses.parse("[A]\nclients: web\n\nOne two.\n")
    with pytest.raises(gen_shared_clauses.SourceError, match="clients"):
        gen_shared_clauses.parse("[A]\nclients: tv\nkotlin: none\n\nOne two.\n")


def test_body_lines_join_with_one_space_and_end_with_one():
    [clause] = gen_shared_clauses.parse(
        "# c\n[A]\nclients: web\nkotlin: none\n\n  One\ntwo.  \n# inner comment\nThree.\n"
    )
    assert clause.text == "One two. Three. "


# =============================================================================
# The web prompt: the web's PromptRoleNamingTest
# =============================================================================


def _alfred(name: str = "Alfred") -> ResolvedCompanion:
    persona = personas.ALFRED
    return ResolvedCompanion(
        name=name,
        persona_key=persona.key,
        clause=personas.with_name(persona.clause, persona, name),
        delivery=persona.delivery,
        voice_name=persona.suggested_voice,
        stored=True,
    )


def test_shared_and_web_copy_never_names_an_assistant_or_a_driver():
    copy = {
        **{name: text for name, text in shared_clauses.CLAUSES.items()},
        "WEB_FRAME": WEB_FRAME,
        "time_fact": time_fact(NOW, -300) + time_fact(NOW, None),
        "member_fact": member_fact("") + member_fact("Mia"),
    }
    for where, text in copy.items():
        assert not PERSONA_NAMES.search(text), (where, PERSONA_NAMES.search(text))
        assert not re.search(r"\bdrivers?\b", text, re.IGNORECASE), where


def test_the_web_prompt_carries_every_web_clause_and_says_the_crisis_rule_last():
    prompt = build_system_prompt(
        companion=_alfred(), first_name="Kevin", now=NOW, utc_offset_minutes=-300
    )
    for name in shared_clauses.WEB_ORDER:
        assert shared_clauses.CLAUSES[name].rstrip() in prompt, name
    assert "PROACTIVE_CLAUSE" not in shared_clauses.WEB_ORDER
    assert shared_clauses.PROACTIVE_CLAUSE.rstrip() not in prompt
    instructions, _facts = prompt.rsplit("\n\n", 1)
    assert instructions.endswith(shared_clauses.CRISIS.rstrip())
    assert prompt.startswith(personas.ALFRED.clause)
    assert personas.ALFRED.delivery in prompt
    assert WEB_FRAME.rstrip() in prompt


def test_the_web_prompt_names_what_only_the_phone_can_do():
    prompt = build_system_prompt(
        companion=_alfred(), first_name="Mia", now=NOW, utc_offset_minutes=None
    )
    for thing in PHONE_ONLY:
        assert thing in prompt
    assert "only be done in the phone app" in prompt
    assert '"ticked", never "bought"' in prompt


def test_the_clock_is_an_offset_never_a_zone_id():
    with_offset = build_system_prompt(
        companion=_alfred(), first_name="Kevin", now=NOW, utc_offset_minutes=-300
    )
    assert "UTC-05:00" in with_offset
    assert "Sunday, October 4, 2026 at 8:31 PM" in with_offset
    without = build_system_prompt(
        companion=_alfred(), first_name="Kevin", now=NOW, utc_offset_minutes=None
    )
    assert "offset is unknown" in without
    assert "Monday, October 5, 2026 at 1:31 AM UTC" in without
    for prompt in (with_offset, without):
        assert not re.search(r"\b[A-Z][a-z]+/[A-Z][A-Za-z_]+\b", prompt), "an IANA zone id"
        assert "do not guess" in prompt


def test_offset_words():
    assert utc_offset_words(-300) == "UTC-05:00"
    assert utc_offset_words(330) == "UTC+05:30"
    assert utc_offset_words(0) == "UTC+00:00"


def test_the_member_is_named_from_their_account_or_not_at_all():
    assert member_fact("Mia") == "You are speaking with Mia, a member of this household."
    assert "do not invent one" in member_fact("  ")


# =============================================================================
# Companions: Kevin's ruling, and a renamed companion
# =============================================================================


class _User:
    def __init__(self, first_name):
        self.first_name = first_name


def test_mia_is_seeded_dorothy_and_everyone_else_alfred():
    assert seed_persona_key(_User("Mia")) == "dorothy"
    assert seed_persona_key(_User(" mia ")) == "dorothy"
    assert seed_persona_key(_User("Kevin")) == "alfred"
    assert seed_persona_key(_User("")) == "alfred"
    mia = resolve(None, _User("Mia"))
    assert (mia.name, mia.voice_name, mia.stored) == ("Dorothy", "Vindemiatrix", False)
    assert mia.clause == personas.DOROTHY.clause


def test_a_renamed_companion_speaks_its_own_name():
    renamed = _alfred("Jeeves")
    assert "You are Jeeves, the household's butler" in renamed.clause
    assert "Alfred" not in renamed.clause


def test_an_unknown_persona_key_reads_as_alfred():
    assert personas.persona_for("nobody") is personas.ALFRED
