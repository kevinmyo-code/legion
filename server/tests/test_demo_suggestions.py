# ruff: noqa: F811 - fixtures imported from the cascade tests
"""Suggestion chips on the demo's voice stage (`demo/suggestions.py`). Invented profile only."""

from __future__ import annotations

import pytest
from django.core import signing

from agents.runtime.model import FakeModel, call, say
from assistant import cascade
from assistant.surface import RECRUITER_TOOLS
from demo import suggestions
from demo.suggestions import CHIP_TOOLS, EXAMPLES, MONEY, Chip
from household.demo_seed import seed_household
from household.models import HouseholdMember
from tests.test_assistant_cascade import (  # noqa: F401 - fixtures
    _clean,
    _events,
    _of,
    _post,
    engine,
    member,
    visitor,
    visitor_user,
)
from tests.test_profile_verbatim import ALL_SENTENCES, EDUCATION, _content
from tests.test_recruiter_content import load_fake

pytestmark = pytest.mark.django_db


@pytest.fixture
def loaded(tmp_path):
    load_fake(tmp_path, content=_content())


@pytest.fixture
def seeded(visitor_user):
    household = HouseholdMember.objects.get(user=visitor_user).household
    seed_household(household, created=True)
    return household


def _chips(client, offer) -> list[dict]:
    response = client.post("/api/demo/chips", {"offer": offer}, format="json")
    assert response.status_code == 200, response.data
    return response.data["chips"]


def _done(events) -> dict:
    return _of(events, "done")[-1]


# =============================================================================
# The catalog: data, reads only, nothing paid
# =============================================================================


def _engine_tool(name):
    from demo.tools import ABOUT_KEVIN_SPOKEN, SHOW_RESUME
    from engine_mcp.tools import TOOLS_BY_NAME

    return {"about_kevin": ABOUT_KEVIN_SPOKEN, "show_resume": SHOW_RESUME}.get(name) or (
        TOOLS_BY_NAME[name]
    )


def test_every_chip_tool_reads_is_a_recruiter_tool_and_none_is_paid():
    assert "web_search" not in CHIP_TOOLS
    for name in CHIP_TOOLS:
        assert name in RECRUITER_TOOLS, name
        assert _engine_tool(name).writes is False, name
    for chip in (*EXAMPLES, *MONEY.values()):
        assert chip.tool in CHIP_TOOLS, chip.key


def test_examples_are_four_whole_questions_with_distinct_keys():
    assert len(EXAMPLES) == suggestions.EXAMPLES_MAX == 4
    assert len({c.key for c in EXAMPLES}) == 4
    for chip in EXAMPLES:
        assert chip.question.endswith(("?", ".")) and len(chip.question.split()) >= 4


# =============================================================================
# Every candidate against an invented visit, BEFORE any filter (not circular)
# =============================================================================


def test_every_candidate_is_checked_against_this_visit(rf, loaded, seeded, visitor_user):
    request = rf.post("/api/demo/chips")
    request.user = visitor_user
    facts = [
        {"tool": "about_kevin", "read": ["Education"]},
        {"tool": "show_diagram", "project": "legion", "view": "containers"},
        {"tool": "legion_docs", "adrs": ["57"]},
        {"tool": "monthly_spend"},
    ]
    candidates = [*EXAMPLES, *MONEY.values(), *suggestions.follow_ups(visitor_user, facts)]
    verdicts = {c.key: suggestions.answerable(request, c) for c in candidates}
    # Every example answers for a loaded, seeded visit.
    assert all(verdicts[c.key] for c in EXAMPLES), verdicts
    # His other sections, in his order, never the one read.
    sections = [k for k in verdicts if k.startswith("section:")]
    assert "section:education" not in sections
    assert sections[:2] == ["section:location", "section:availability"]
    assert all(verdicts[k] for k in sections)
    # The other legion views exist on this engine; the one shown is not offered again.
    views = [k for k in verdicts if k.startswith("view:legion:") and k != "view:legion:containers"]
    assert views and all(verdicts[k] for k in views)
    assert "adr:0057" in verdicts and verdicts["adr:0057"] is True
    # The printout a reviewer reads when this fails.
    print({k: v for k, v in verdicts.items()})


def test_a_section_that_does_not_exist_and_an_unloaded_view_are_not_answerable(
    rf, loaded, visitor_user
):
    request = rf.post("/api/demo/chips")
    request.user = visitor_user
    assert not suggestions.answerable(
        request, Chip("section:hobbies", "Hobbies", "Q?", "about_kevin", {"sections": ["hobbies"]})
    )
    assert not suggestions.answerable(
        request,
        Chip("view:quanex:x", "X", "Q?", "show_diagram", {"project": "quanex", "view": "context"}),
    )
    assert not suggestions.answerable(
        request, Chip("adr:9999", "ADR 9999", "Q?", "legion_docs", {"query": "ADR 9999"})
    )


def test_an_empty_household_question_is_not_offered(rf, visitor_user):
    # The visit's own copy, never seeded: no ledger, no car.
    request = rf.post("/api/demo/chips")
    request.user = visitor_user
    for chip in MONEY.values():
        assert not suggestions.answerable(request, chip), chip.key
    assert not suggestions.answerable(request, EXAMPLES[3])


# =============================================================================
# The examples: with the first greeting, checked after it
# =============================================================================


def test_the_first_greeting_offers_the_examples_and_a_later_one_does_not(
    loaded, seeded, visitor, engine
):
    events = _events(_post(visitor, greet="true"))
    offer = _done(events)["offer"]
    chips = _chips(visitor, offer)
    assert [c["question"] for c in chips] == [c.question for c in EXAMPLES]
    assert all(c["token"] for c in chips)
    later = _events(_post(visitor, greet="true", preface="Another conversation."))
    assert "offer" not in _done(later)


def test_an_offer_is_for_the_visitor_it_was_made_for(loaded, seeded, visitor, visitor_user):
    from django.contrib.auth import get_user_model

    other = get_user_model().objects.create_user(email="other@demo.invalid")
    forged = suggestions.offer(other, {"x": 1})
    assert _chips(visitor, forged) == []
    assert _chips(visitor, "not-a-token") == []


def test_a_member_cannot_ask_for_chips(member):
    response = member.post("/api/demo/chips", {"offer": "x"}, format="json")
    assert response.status_code == 403


# =============================================================================
# Follow-ups: built by code from what ran; none after a refusal, a failure, small talk
# =============================================================================


def _ak(*sections):
    return call("about_kevin", {"sections": list(sections)})


def test_after_his_profile_the_chips_are_his_other_sections(loaded, visitor, engine):
    engine.model = FakeModel([_ak("education")])
    events = _events(_post(visitor, text="Where did he study?"))
    chips = _chips(visitor, _done(events)["offer"])
    labels = [c["label"] for c in chips]
    assert "Education" not in labels
    assert labels == ["Location", "Availability", "Experience", "Salary"]
    assert 2 <= len(chips) <= suggestions.FOLLOW_UPS_MAX
    # The offer carries no sentence of his.
    assert not any(
        s in signing.loads(_done(events)["offer"], salt=suggestions.TOKEN_SALT).__repr__()
        for s in ALL_SENTENCES
    )


@pytest.mark.parametrize(
    ("case", "rounds", "text"),
    [
        ("small talk", [say("You're welcome.")], "Thanks!"),
        ("not shared", [_ak()], "Does he need sponsorship?"),
        (
            "refused",
            [call("delete_record", {"table": "nope", "id": 1}), say("I can't.")],
            "Delete it.",
        ),
        ("unknown tool", [call("no_such_tool"), say("I can't.")], "Do the thing."),
    ],
)
def test_no_chips_after_small_talk_a_fixed_line_or_a_refusal(
    loaded, visitor, engine, case, rounds, text
):
    engine.model = FakeModel(rounds)
    events = _events(_post(visitor, text=text))
    assert "offer" not in _done(events), case


def test_an_adr_chip_only_for_an_adr_the_answer_said(loaded, visitor, engine):
    engine.model = FakeModel(
        [
            call("legion_docs", {"query": "reconciliation gate"}),
            say("ADR 57 lets the bank feed in."),
        ]
    )
    events = _events(_post(visitor, text="How does the gate work?"))
    facts = signing.loads(_done(events)["offer"], salt=suggestions.TOKEN_SALT)["f"]
    adrs = facts[0]["adrs"]
    assert adrs == ["57"] or adrs == []  # only one the answer named, if the docs cited it
    engine.model = FakeModel(
        [call("legion_docs", {"query": "reconciliation gate"}), say("The gate checks totals.")]
    )
    events = _events(_post(visitor, text="How does the gate work?"))
    facts = signing.loads(_done(events)["offer"], salt=suggestions.TOKEN_SALT)["f"]
    assert facts[0]["adrs"] == []


# =============================================================================
# The tap: the chip's own checked lookup; his profile with no model call
# =============================================================================


def _chip_token(client, engine, question):
    engine.model = FakeModel([say("Hello.")])
    events = _events(_post(client, greet="true"))
    for chip in _chips(client, _done(events)["offer"]):
        if chip["question"] == question:
            return chip["token"]
    raise AssertionError(question)


def test_a_profile_chip_is_answered_by_code_with_no_model_call(loaded, seeded, visitor, engine):
    token = _chip_token(visitor, engine, "Tell me about Kevin.")
    engine.model = FakeModel([])
    events = _events(_post(visitor, text="Tell me about Kevin.", chip=token))
    assert engine.model.requests == []
    said = [s["text"] for s in _of(events, "sentence")]
    assert said and all(s in ALL_SENTENCES or s.startswith("There's more") for s in said)
    assert _of(events, "tool")[0]["name"] == "about_kevin"
    assert _done(events)["status"] == "done"


def test_a_follow_up_section_chip_reads_that_section(loaded, visitor, engine):
    engine.model = FakeModel([_ak("location")])
    events = _events(_post(visitor, text="Where is he?"))
    chips = _chips(visitor, _done(events)["offer"])
    study = next(c for c in chips if c["label"] == "Education")
    engine.model = FakeModel([])
    events = _events(_post(visitor, text=study["question"], chip=study["token"]))
    assert [s["text"] for s in _of(events, "sentence")] == EDUCATION
    assert engine.model.requests == []


def test_a_chip_runs_its_own_args_and_the_model_only_words_the_result(
    loaded, seeded, visitor, engine
):
    token = _chip_token(visitor, engine, "Show me how LEGION is built.")
    engine.model = FakeModel([say("Here is how it is deployed.")])
    events = _events(_post(visitor, text="Show me how LEGION is built.", chip=token))
    tool = _of(events, "tool")[0]
    assert tool["name"] == "show_diagram" and tool["card"]["view"] == "containers"
    # One model request: the wording round. Its history holds the injected call, signed.
    assert len(engine.model.requests) == 1
    model_turn = engine.model.requests[0].contents[-2]
    part = model_turn["parts"][0]
    assert part["functionCall"]["name"] == "show_diagram"
    assert part["thoughtSignature"] == cascade.INJECTED_SIGNATURE


@pytest.mark.parametrize("bad", ["wrong-question", "other-visitor", "garbage"])
def test_a_chip_token_counts_only_for_its_visitor_and_its_question(
    loaded, seeded, visitor, engine, bad
):
    from django.contrib.auth import get_user_model

    token = _chip_token(visitor, engine, "Tell me about Kevin.")
    text = "Tell me about Kevin."
    if bad == "wrong-question":
        text = "Delete every checklist."
    elif bad == "other-visitor":
        other = get_user_model().objects.create_user(email="o2@demo.invalid")
        token = suggestions.offer(
            other, {"c": {"tool": "about_kevin", "args": {}, "fast": True, "question": text}}
        )
    else:
        token = "garbage"
    engine.model = FakeModel([say("A plain answer.")])
    _events(_post(visitor, text=text, chip=token))
    # A plain typed turn: the model was asked.
    assert len(engine.model.requests) == 1


def test_no_model_request_in_a_chip_conversation_holds_his_sentences(
    loaded, seeded, visitor, engine
):
    token = _chip_token(visitor, engine, "Tell me about Kevin.")
    engine.model = FakeModel([])
    first = _events(_post(visitor, text="Tell me about Kevin.", chip=token))
    conversation = _of(first, "turn")[0]["conversation_id"]
    engine.model = FakeModel([say("Happy to help.")])
    _events(_post(visitor, text="And what else?", conversation_id=conversation))
    for request in engine.model.requests:
        flat = repr(request.contents) + repr(request.system)
        assert not any(sentence in flat for sentence in ALL_SENTENCES)


def test_over_the_rate_the_page_gets_no_chips_and_no_error(loaded, seeded, visitor, monkeypatch):
    from demo.content_views import DemoChipsThrottle

    rates = {**DemoChipsThrottle.THROTTLE_RATES, "demo_chips": "1/min"}
    monkeypatch.setattr(DemoChipsThrottle, "THROTTLE_RATES", rates)
    offer = suggestions.examples_offer(HouseholdMember.objects.get(household=seeded).user)
    assert len(_chips(visitor, offer)) == 4
    assert _chips(visitor, offer) == []
