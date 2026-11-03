"""recruiter-demo ticket 05: a recruiter's assistant is Alfred, addressed to a
stranger, and greets on the first turn.

What is pinned: the companion and voice are fixed by role, the prompt carries
the visitor register and the demo frame, a member's prompt is untouched, and
the things the greeting offers are things the recruiter's tools can do.
Whether the model obeys is not testable here (CLAUDE.md section 7: a prompt
rule, guarded for presence, never obedience).
"""

from __future__ import annotations

import re
from datetime import UTC, datetime

import pytest

from assistant import personas
from assistant.companions import companion_for, set_companion
from assistant.prompt import (
    RECRUITER_CAPABILITIES,
    RECRUITER_FRAME,
    RECRUITER_OPENER,
    RECRUITER_REGISTER,
    VISITOR_FACT,
    member_fact,
)
from assistant.session import token_request_for
from assistant.surface import RECRUITER_TOOLS, tools_for
from household.models import Household, HouseholdMember, User

pytestmark = pytest.mark.django_db

NOW = datetime(2026, 11, 2, 15, 0, tzinfo=UTC)


@pytest.fixture
def visitor(db):
    demo = Household.objects.create(name="Demo visit greeting", is_demo_visit=True)
    user = User.objects.create_user(email="visitor-test@demo.invalid", first_name="Pat")
    HouseholdMember.objects.create(user=user, household=demo, role=HouseholdMember.RECRUITER)
    return user


def _prompt(user) -> tuple[str, dict]:
    body, companion = token_request_for(user, now=NOW, utc_offset_minutes=-360)
    setup = body["bidiGenerateContentSetup"]
    return setup["systemInstruction"]["parts"][0]["text"], {
        "voice": setup["generationConfig"]["speechConfig"]["voiceConfig"][
            "prebuiltVoiceConfig"
        ]["voiceName"],
        "companion": companion,
    }


def test_a_recruiter_talks_to_alfred_in_the_voice_a_member_gets_by_default(
    visitor, household_user
):
    text, got = _prompt(visitor)
    member = companion_for(household_user)
    assert got["companion"].name == personas.ALFRED.default_name == member.name
    assert got["voice"] == personas.ALFRED.suggested_voice == member.voice_name
    assert personas.ALFRED.clause.splitlines()[0] in text


def test_a_stored_row_cannot_change_a_recruiters_companion(visitor):
    set_companion(visitor, personas.KRATOS.key, voice_name="Fenrir")
    _text, got = _prompt(visitor)
    assert got["companion"].persona_key == personas.ALFRED.key
    assert got["voice"] == personas.ALFRED.suggested_voice


def test_the_recruiter_prompt_addresses_a_stranger_and_greets(visitor):
    text, _ = _prompt(visitor)
    assert RECRUITER_REGISTER in text
    assert RECRUITER_FRAME.strip() in text
    assert 'never as "sir"' in text
    assert "greet the visitor" in text and "what they would like to try" in text
    assert "made-up household" in text
    # Kevin, 2026-11-03: "tell them in the greeting too". One clause, and the word is
    # "improve": nothing is trained on these conversations.
    assert "the conversation may be recorded to improve the assistant" in text
    assert "train" not in RECRUITER_FRAME.lower()
    # The page's hidden opener is named as a cue, not the visitor's words.
    assert f"The line {RECRUITER_OPENER} is sent by the page" in text
    assert "never quote it" in text
    assert text.endswith(VISITOR_FACT)
    # The visitor's own first name never reaches the prompt.
    assert "Pat" not in text
    assert member_fact("Pat") not in text


def test_the_visitor_register_overrides_alfreds_old_friend_terms(visitor):
    """Alfred's register (the phone's, ADR 0063) is on first-name terms with
    "this one person". A visitor is not that person: the visitor register comes
    after it and says the familiarity does not apply."""
    text, _ = _prompt(visitor)
    assert "first-name terms" in personas.ALFRED.clause
    register_at = text.index(RECRUITER_REGISTER)
    assert register_at > text.index(personas.ALFRED.clause)
    for phrase in (
        "they are not Kevin",
        "This overrides what your register above says",
        "first-name terms are not for this visitor",
        "never by a first name",
        '"Mr"',
        "no teasing",
    ):
        assert phrase in RECRUITER_REGISTER, phrase


def test_the_recruiter_prompt_names_no_one_in_kevins_household_but_kevin(visitor):
    """The only real person a visitor's prompt may name is Kevin. Alfred's
    register is generic ("this one person"); this holds it there."""
    text, _ = _prompt(visitor)
    assert not re.search(r"\bMia\b", text)
    allowed = {
        "You", "Alfred", "How", "Lead", "Short", "One", "No", "Vary", "When", "If",
        "Honesty", "A", "Never", "I", "Mr", "Very", "Right", "Shall",
    }  # fmt: skip
    names = set(re.findall(r"\b[A-Z][a-z]*\b", personas.ALFRED.clause))
    assert names <= allowed, names - allowed


def test_a_members_prompt_is_unchanged(household_user):
    text, _ = _prompt(household_user)
    assert RECRUITER_REGISTER not in text
    assert RECRUITER_FRAME.strip() not in text
    assert VISITOR_FACT not in text


def test_the_greeting_offers_only_what_the_tools_can_do(visitor):
    offered = " ".join(RECRUITER_CAPABILITIES).lower()
    assert "music" not in offered and "spotify" not in offered and "play" not in offered
    # Each offer has a tool behind it in the recruiter's declarations.
    declared = {t.name for t in tools_for(visitor)}
    assert declared == set(RECRUITER_TOOLS)
    needs = {
        "spent": "read_records",
        "service": "read_records",
        "calendar": "list_events",
        "checklists": "list_checklists",
        "last bought": "last_bought",
        "kevin himself": "about_kevin",
        "legion itself is built": "legion_docs",
    }
    for phrase, tool in needs.items():
        assert phrase in offered, phrase
        assert tool in declared, tool
    assert 3 <= len(RECRUITER_CAPABILITIES) <= 4


# =============================================================================
# English, for members and visitors (audit 2026-11-03: "money" heard as
# "monnaie", answered in French)
# =============================================================================


@pytest.mark.parametrize("who", ["visitor", "household_user"])
def test_the_setup_says_english_in_the_prompt_and_sends_no_language_code(who, request):
    """The rule is the prompt's: native-audio Live models "don't support
    explicitly setting the language code" (capabilities page), and a locked
    setup with an unsupported field could fail every mint. So the setup's
    speechConfig carries the voice alone and no transcription config carries
    a language; the prompt carries the rule, for members and recruiters."""
    from assistant.prompt import LANGUAGE_RULE

    user = request.getfixturevalue(who)
    body, _ = token_request_for(user, now=NOW, utc_offset_minutes=-360)
    setup = body["bidiGenerateContentSetup"]
    assert set(setup["generationConfig"]["speechConfig"]) == {"voiceConfig"}
    assert setup["inputAudioTranscription"] == {}
    assert "languageCode" not in str(setup) and "language_code" not in str(setup)
    text = setup["systemInstruction"]["parts"][0]["text"]
    assert LANGUAGE_RULE.strip() in text
    assert "Speak and answer in English" in text
    assert '"money" heard as "monnaie"' in text
    assert "whole sentence in another language and asks you to answer in it" in text
