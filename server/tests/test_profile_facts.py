# ruff: noqa: F811 - fixtures imported from the cascade tests
"""Facts a recruiter weighs are said in the profile's own sentences (live demo, 2026-11-18: the
profile said "graduating summer 2027" and the answer said he "graduated"). After `about_kevin` ran
in a turn, a model sentence about education and graduation, availability, the roles he wants, his
experience or work authorisation that the profile does not state near word for word is replaced by
the profile's own sentence on that topic (or, when the profile has none, by "Kevin hasn't shared
that here"). Every other sentence stands. Invented profile only; Google is never called."""

from __future__ import annotations

import pytest

from agents.runtime.model import FakeModel, call, say
from tests.test_assistant_cascade import (  # noqa: F401 - fixtures
    _clean,
    _events,
    _of,
    _post,
    engine,
    visitor,
    visitor_user,
)
from tests.test_recruiter_content import fake_content, write_folder

pytestmark = pytest.mark.django_db

EDUCATION = "Business Analytics, Example State University, GPA 3.9, graduating spring 2028."
EXPERIENCE = "Data intern at Example Corp in 2026: built weekly finance reports from three systems."


@pytest.fixture
def profile(tmp_path):
    from django.core.management import call_command

    content = fake_content()
    content["profile"] = {
        "Education": EDUCATION,
        "Experience": EXPERIENCE,
        "Location": "Fakeville, Nowhere.",
    }
    call_command("load_recruiter_content", str(write_folder(tmp_path / "c", content)))


def _said(events):
    return [s["text"] for s in _of(events, "sentence")]


def test_a_wrong_tense_or_paraphrased_figure_becomes_the_profiles_sentence(
    visitor, engine, profile
):
    engine.model = FakeModel(
        [
            call("about_kevin", {}),
            say(
                "Pat graduated from Example State with a three-point-nine. He built weekly "
                "finance reports at Example Corp."
            ),
        ]
    )
    said = _said(_events(_post(visitor, text="Tell me about him.")))
    assert said == [EDUCATION, "He built weekly finance reports at Example Corp."]


def test_a_near_word_for_word_sentence_stands(visitor, engine, profile):
    line = "He is graduating spring 2028 from Example State University, GPA 3.9."
    engine.model = FakeModel([call("about_kevin", {}), say(line)])
    assert _said(_events(_post(visitor, text="When does he graduate?"))) == [line]


def test_an_invented_experience_claim_becomes_the_profiles_sentence(visitor, engine, profile):
    engine.model = FakeModel(
        [call("about_kevin", {}), say("He interned at Example Corp and merged ten ERP systems.")]
    )
    assert _said(_events(_post(visitor, text="What did he do?"))) == [EXPERIENCE]


def test_a_topic_the_profile_does_not_cover_is_not_shared(visitor, engine, profile):
    engine.model = FakeModel(
        [call("about_kevin", {}), say("He is authorized to work in the US without sponsorship.")]
    )
    assert _said(_events(_post(visitor, text="Does he need a visa?"))) == [
        "Kevin hasn't shared that here; ask him directly."
    ]


def test_the_same_profile_sentence_is_said_once(visitor, engine, profile):
    engine.model = FakeModel(
        [
            call("about_kevin", {}),
            say("He graduated in 2028. He has a three-point-nine GPA."),
        ]
    )
    assert _said(_events(_post(visitor, text="Education?"))) == [EDUCATION]


def test_without_about_kevin_nothing_is_checked(visitor, engine, profile):
    line = "Graduation season is busy for the household."
    engine.model = FakeModel([say(line)])
    assert _said(_events(_post(visitor, text="Anything new?"))) == [line]


def test_other_sentences_after_about_kevin_stand(visitor, engine, profile):
    line = "He likes building things that people actually use."
    engine.model = FakeModel([call("about_kevin", {}), say(line)])
    assert _said(_events(_post(visitor, text="What is he like?"))) == [line]
