# ruff: noqa: F811 - fixtures imported from the cascade tests
"""Facts about Kevin are never reworded (2026-11-18): code speaks the profile, the model only
chooses which sections.

Measured before this change: asked "Tell me about Kevin.", the model stated something the profile
does not in 3 of 10 replies (a graduation date moved, a degree level appeared). A guard comparing
model sentences with the profile failed review both ways. So the model is never handed a sentence
of the profile at all: `about_kevin` is declared the section titles only, the model picks ids, and
code says and shows the sections' own sentences (`demo.profile`), then the turn ends. History
replays a marker, never the sentences, and the tour's lines about him likewise.

Google is never called: the text model is `FakeModel` and speech is the cascade tests' fake. The
profile, names, schools, figures and links below are all invented.
"""

from __future__ import annotations

import json

import pytest

from agents.runtime.model import FakeModel, call, say
from assistant import cascade, tour
from assistant.surface import tools_for
from demo import profile
from demo.profile import NOT_SHARED_LINE, OFFERS, Section, speak, split_sentences
from engine_mcp.tools import FOR_MODEL, SPEAK_VERBATIM
from tests.test_assistant_cascade import (  # noqa: F401 - fixtures
    _clean,
    _events,
    _of,
    _post,
    _record,
    engine,
    visitor,
    visitor_user,
)
from tests.test_recruiter_content import fake_content, load_fake

pytestmark = pytest.mark.django_db

# An invented profile with the shapes a real one has: a dotted degree, a list of lines with no
# full stop, bullets, a money figure, one section longer than the cap.
PROFILE = {
    "Location": (
        "Lives in Fakeville, Example State. Open to moving anywhere in the U.S. for the right team."
    ),
    "Availability": "Can start in June 2031.",
    "Education": (
        "B.S. Information Systems, Example State University, GPA 3.8.\n"
        "Graduating in spring 2031, a future term."
    ),
    "Experience": (
        "Analyst intern at Fabrikam Example Co. in 2029. Built ten pretend dashboards. Moved 12 "
        "fake reports to a new system. Worked with Dr. Placeholder on test data. Wrote the team's "
        "fake playbook. Trained three pretend analysts. Shipped a mock forecast."
    ),
    "Salary": "Expects $123,456 a year.",
    "Contact": "Email: pat@example.invalid\nPortfolio: example.invalid/pat",
    "Roles wanted": "- Data engineer\n- Support engineer",
}
EDUCATION = [
    "B.S. Information Systems, Example State University, GPA 3.8.",
    "Graduating in spring 2031, a future term.",
]
LOCATION = [
    "Lives in Fakeville, Example State.",
    "Open to moving anywhere in the U.S. for the right team.",
]
AVAILABILITY = ["Can start in June 2031."]
SALARY = ["Expects $123,456 a year."]
CONTACT = ["Email: pat@example.invalid", "Portfolio: example.invalid/pat"]
ROLES = ["Data engineer", "Support engineer"]
EXPERIENCE = [
    "Analyst intern at Fabrikam Example Co. in 2029.",
    "Built ten pretend dashboards.",
    "Moved 12 fake reports to a new system.",
    "Worked with Dr. Placeholder on test data.",
    "Wrote the team's fake playbook.",
    "Trained three pretend analysts.",
    "Shipped a mock forecast.",
]
ALL_SENTENCES = EDUCATION + LOCATION + AVAILABILITY + SALARY + CONTACT + ROLES + EXPERIENCE
MORE_EXPERIENCE = "There's more on Experience; just ask."
# A fake hero lead: the tour's "who" beat says it, so it is a sentence about him too.
LEAD = "Pat Example is a pretend analyst who builds fake things."


def _content(**changes) -> dict:
    content = fake_content()
    content["profile"] = dict(PROFILE)
    content["hero"]["lead"] = LEAD
    content.update(changes)
    return content


@pytest.fixture
def loaded(tmp_path):
    load_fake(tmp_path, content=_content())


# =============================================================================
# Sentences: cut where one ends, never inside "B.S." or "Dr."
# =============================================================================


@pytest.mark.parametrize(
    ("text", "sentences"),
    [
        (
            "B.S. Information Systems, Example State. Graduating soon.",
            ["B.S. Information Systems, Example State.", "Graduating soon."],
        ),
        ("Worked with Dr. Placeholder. Then left.", ["Worked with Dr. Placeholder.", "Then left."]),
        ("Ph.D. Student at Example. Next one.", ["Ph.D. Student at Example.", "Next one."]),
        ("e.g. Python and SQL. And more.", ["e.g. Python and SQL.", "And more."]),
        ("J. Doe wrote it. Fine.", ["J. Doe wrote it.", "Fine."]),
        ("GPA 3.8. Next.", ["GPA 3.8.", "Next."]),
        (
            "Salary is $120,000.00 a year. Negotiable.",
            ["Salary is $120,000.00 a year.", "Negotiable."],
        ),
        ("U.S.-based role. Next.", ["U.S.-based role.", "Next."]),
        ("Done! Next? Yes.", ["Done!", "Next?", "Yes."]),
        ('He said "Hi." Then left.', ['He said "Hi."', "Then left."]),
        ("Line one\nLine two", ["Line one", "Line two"]),
        ("- bullet one\n* bullet two\n1. numbered", ["bullet one", "bullet two", "numbered"]),
        ("Lives in the U.S. for now.", ["Lives in the U.S. for now."]),
        # In doubt it does not cut: an acronym that ends a sentence keeps the next one with it.
        # Both are still his words, said as written.
        ("Moved to the U.S. Then studied.", ["Moved to the U.S. Then studied."]),
        ("Fabrikam Co. in 2029. Next.", ["Fabrikam Co. in 2029.", "Next."]),
        ("", []),
        ("\n\n  \n", []),
    ],
)
def test_a_section_is_cut_into_sentences_only_where_one_ends(text, sentences):
    assert split_sentences(text) == sentences


def test_cutting_keeps_every_character_but_a_bullet():
    for text in PROFILE.values():
        flat = "".join(text.replace("- ", "").split())
        assert "".join("".join(split_sentences(text)).split()) == flat


def test_sections_get_stable_ids_from_their_titles_and_empty_ones_are_left_out():
    sections = profile.sections_of({**PROFILE, "Empty": "  ", "location!": "Twice named."})
    ids = [s.id for s in sections]
    assert ids == [
        "location",
        "availability",
        "education",
        "experience",
        "salary",
        "contact",
        "roles_wanted",
        "location_2",
    ]
    assert sections[2] == Section("education", "Education", tuple(EDUCATION))


# =============================================================================
# The cap: whole sections, shortest first, the rest named
# =============================================================================

SECTIONS = profile.sections_of(PROFILE)


@pytest.mark.parametrize(
    ("chosen", "rest_of", "lines", "more"),
    [
        (["education"], None, EDUCATION, None),
        (["education", "education"], None, EDUCATION, None),
        (
            ["education", "location", "availability"],
            None,
            EDUCATION + LOCATION + AVAILABILITY,
            None,
        ),
        # Six sentences: the shortest three fit (5) and Location is named; the model's order kept.
        (
            ["education", "location", "availability", "salary"],
            None,
            EDUCATION + AVAILABILITY + SALARY,
            "There's more on Location; just ask.",
        ),
        (["experience", "availability"], None, AVAILABILITY, MORE_EXPERIENCE),
        (["experience"], None, EXPERIENCE[:5], MORE_EXPERIENCE),
        (["experience"], "experience", None, None),
        ([], "experience", EXPERIENCE[5:], None),
        ([], "availability", AVAILABILITY, None),
        (["hobbies"], None, [NOT_SHARED_LINE], None),
        ([], None, [NOT_SHARED_LINE], None),
        (["education", "hobbies"], None, EDUCATION, None),
        (
            ["experience", "contact"],
            None,
            CONTACT,
            MORE_EXPERIENCE,
        ),
    ],
)
def test_what_is_said_is_whole_sections_capped_shortest_first(chosen, rest_of, lines, more):
    spoken = speak(SECTIONS, chosen, rest_of)
    if lines is None:  # rest_of wins over sections
        lines = EXPERIENCE[5:]
    assert list(spoken.lines) == lines
    assert spoken.more == more
    assert len(spoken.lines) <= profile.CAP


def test_a_more_line_names_only_titles_that_exist():
    for chosen in (
        ["experience"],
        ["experience", "contact", "education"],
        ["hobbies", "experience"],
    ):
        more = speak(SECTIONS, chosen).more or ""
        named = more.removeprefix("There's more on ").removesuffix("; just ask.")
        for title in named.replace(" and ", ", ").split(", ") if named else []:
            assert title in PROFILE, title


# =============================================================================
# The tool: titles only for the model, sentences only for code
# =============================================================================


def test_the_model_is_declared_titles_and_ids_never_a_sentence(loaded, visitor_user):
    tool = next(t for t in tools_for(visitor_user) if t.name == "about_kevin")
    assert "education (Education)" in tool.description
    assert "roles_wanted (Roles wanted)" in tool.description
    # Postgres keeps a JSON object's keys in its own order, so the ids are compared as a set.
    assert set(tool.input_schema["properties"]["sections"]["items"]["enum"]) == {
        s.id for s in profile.sections_of(PROFILE)
    }
    declared = json.dumps({"d": tool.description, "s": tool.input_schema})
    for sentence in ALL_SENTENCES + [LEAD]:
        assert sentence not in declared


def test_with_no_profile_the_declaration_says_nothing_is_shared(visitor_user):
    tool = next(t for t in tools_for(visitor_user) if t.name == "about_kevin")
    assert "none (nothing shared yet)" in tool.description
    assert "enum" not in tool.input_schema["properties"]["sections"]["items"]


# =============================================================================
# The turn table: what the visitor hears and sees, case by case
# =============================================================================


def ak(*sections, offer=None, rest_of=None):
    args = {"sections": list(sections)}
    if offer:
        args["offer"] = offer
    if rest_of:
        args["rest_of"] = rest_of
    return call("about_kevin", args)


def both(*rounds):
    """One model round that streams each part in order (text, then a call...)."""
    out = []
    for r in rounds:
        out += [e for e in r if type(e).__name__ in ("PartEvent", "TextDelta", "ToolCallEvent")]
    return out + rounds[-1][-2:]


HE_IS_GREAT = say("He is a fine engineer.")

# id | visitor words | scripted model rounds | expected caption (= what is spoken)
TABLE = [
    ("about", "Tell me about Kevin.", [ak("education", "location")], EDUCATION + LOCATION),
    ("study", "Where did he study?", [ak("education")], EDUCATION),
    ("graduate", "When does he graduate?", [ak("education")], EDUCATION),
    ("start", "When can he start?", [ak("availability")], AVAILABILITY),
    ("based", "Where is he based?", [ak("location")], LOCATION),
    ("salary", "What does he expect to earn?", [ak("salary")], SALARY),
    ("contact", "How can I reach him?", [ak("contact")], CONTACT),
    ("roles", "What roles is he after?", [ak("roles_wanted")], ROLES),
    (
        "experience",
        "Tell me about his experience.",
        [ak("experience")],
        EXPERIENCE[:5] + [MORE_EXPERIENCE],
    ),
    ("rest", "Tell me the rest of his experience.", [ak(rest_of="experience")], EXPERIENCE[5:]),
    ("authorised", "Is Kevin authorised to work in the US?", [ak()], [NOT_SHARED_LINE]),
    ("sponsor", "Does he need sponsorship?", [ak()], [NOT_SHARED_LINE]),
    ("unknown id", "What are his hobbies?", [ak("hobbies")], [NOT_SHARED_LINE]),
    ("known and unknown", "Education and hobbies?", [ak("education", "hobbies")], EDUCATION),
    (
        "over the cap",
        "Tell me everything about him.",
        [ak("education", "location", "availability", "salary")],
        EDUCATION + AVAILABILITY + SALARY + ["There's more on Location; just ask."],
    ),
    (
        "long and short",
        "His experience and start date?",
        [ak("experience", "availability")],
        AVAILABILITY + [MORE_EXPERIENCE],
    ),
    (
        "offer resume",
        "Where did he study?",
        [ak("education", offer="resume")],
        EDUCATION + [OFFERS["resume"]],
    ),
    (
        "offer contact",
        "When can he start?",
        [ak("availability", offer="contact")],
        AVAILABILITY + [OFFERS["contact"]],
    ),
    (
        "more wins over offer",
        "His experience?",
        [ak("experience", offer="resume")],
        EXPERIENCE[:5] + [MORE_EXPERIENCE],
    ),
    # An offer outside the fixed list is refused by the tool's schema; asked again, it is said.
    (
        "offer unknown",
        "Where is he?",
        [ak("location", offer="lunch"), ak("location")],
        LOCATION,
    ),
    # The model's words before its call are said (no holding); it has no fact of his to put in them.
    (
        "preamble",
        "Who is Kevin?",
        [both(say("Here is what he wrote."), ak("education"))],
        ["Here is what he wrote."] + EDUCATION,
    ),
    # After the call nothing of the model's is asked for or said.
    ("nothing after", "Tell me about Kevin.", [ak("availability"), HE_IS_GREAT], AVAILABILITY),
    # A later call in the same round never runs: the profile was the answer (stated limit).
    (
        "call after",
        "Kevin, and the docs?",
        [both(ak("availability"), call("legion_docs", {"query": "gate"}))],
        AVAILABILITY,
    ),
    # An earlier call in the same round runs, but its answer is never asked for (stated limit).
    (
        "call before",
        "Docs, then Kevin?",
        [both(call("legion_docs", {"query": "gate"}), ak("availability"))],
        AVAILABILITY,
    ),
    # Not about him: the model answers in its own words, as before.
    (
        "visa type",
        "What is an H-1B1 visa?",
        [say("An H-1B1 is a US work visa for citizens of Chile and Singapore.")],
        ["An H-1B1 is a US work visa for citizens of Chile and Singapore."],
    ),
    ("thanks", "Thanks!", [say("You're welcome.")], ["You're welcome."]),
    (
        "household",
        "What is on the calendar?",
        [say("Nothing is on today.")],
        ["Nothing is on today."],
    ),
    # The model answering about him without the tool: its words are said as they are. It was
    # never handed a fact of his to reword (the structural tests below); this is the invention
    # case the real-model replay measures.
    ("no tool", "Is he good?", [HE_IS_GREAT], ["He is a fine engineer."]),
]


@pytest.mark.parametrize(("case", "words", "rounds", "said"), TABLE, ids=[row[0] for row in TABLE])
def test_what_the_visitor_hears_about_kevin(loaded, visitor, engine, case, words, rounds, said):
    engine.model = FakeModel(list(rounds))
    events = _events(_post(visitor, text=words, spoken="true"))
    caption = [s["text"] for s in _of(events, "sentence")]
    assert caption == said
    # Spoken word for word as shown: no rounding, no rewording. Speech may join sentences into
    # one request, so the whole is compared.
    spoken = " ".join(text for text, _voice in engine.speech.spoken)
    assert spoken == " ".join(said)
    assert _of(events, "done")[0]["status"] == "done"
    for request in engine.model.requests:
        assert not _profile_in(request), case


def test_his_status_gets_the_not_shared_line_and_a_visa_type_is_answered(loaded, visitor, engine):
    engine.model = FakeModel([ak()])
    status = _events(_post(visitor, text="Is Kevin authorised to work in the US?"))
    assert [s["text"] for s in _of(status, "sentence")] == [NOT_SHARED_LINE]
    engine.model = FakeModel([say("An H-1B1 is a work visa for citizens of Chile and Singapore.")])
    general = _events(_post(visitor, text="What is an H-1B1?"))
    said = [s["text"] for s in _of(general, "sentence")]
    assert said == ["An H-1B1 is a work visa for citizens of Chile and Singapore."]
    assert NOT_SHARED_LINE not in said


def test_a_salary_is_spoken_exactly_never_rounded(loaded, visitor, engine):
    engine.model = FakeModel([ak("salary")])
    _events(_post(visitor, text="Salary?", spoken="true"))
    spoken = [text for text, _ in engine.speech.spoken]
    assert "Expects $123,456 a year." in spoken
    assert not any("about" in text for text in spoken)


def test_without_a_resume_no_resume_is_offered(tmp_path, visitor, engine):
    load_fake(tmp_path, content=_content(), resume=False)
    engine.model = FakeModel([ak("education", offer="resume")])
    events = _events(_post(visitor, text="Where did he study?"))
    assert [s["text"] for s in _of(events, "sentence")] == EDUCATION


def test_with_no_profile_loaded_the_model_is_told_nothing_was_read(visitor, engine):
    engine.model = FakeModel(
        [ak("education"), say("Kevin hasn't shared that here; ask him directly.")]
    )
    events = _events(_post(visitor, text="Where did he study?"))
    assert [s["text"] for s in _of(events, "sentence")] == [NOT_SHARED_LINE]
    tool = _of(events, "tool")[0]
    assert tool["ok"] is False and tool["text"].startswith("Nothing was read.")


def test_the_tool_event_and_the_model_get_the_titles_not_the_sentences(loaded, visitor, engine):
    engine.model = FakeModel([ak("education")])
    events = _events(_post(visitor, text="Where did he study?"))
    tool = _of(events, "tool")[0]
    assert tool["text"].startswith("Done: read aloud and shown, word for word")
    assert "Education" in tool["text"]
    assert not any(sentence in tool["text"] for sentence in ALL_SENTENCES)


def test_the_old_live_path_is_handed_the_sentences_to_say(loaded, visitor_user):
    """The voice rollback (Live, `POST /api/assistant/tool`) has no code speaker."""
    from demo.tools import ABOUT_KEVIN

    class Request:
        user = visitor_user

    result = ABOUT_KEVIN.handler(Request(), {"sections": ["education"]})
    assert result.text.startswith("Say exactly these sentences about Kevin, word for word")
    assert all(sentence in result.text for sentence in EDUCATION)
    assert result.structured[SPEAK_VERBATIM] == EDUCATION
    assert "B.S." not in result.structured[FOR_MODEL]


# =============================================================================
# The structural guarantee: no model request ever holds a sentence of his
# =============================================================================


def _texts(request) -> str:
    return (
        json.dumps(request.contents)
        + "\n"
        + request.system
        + "\n"
        + json.dumps(request.declarations)
    )


def _profile_in(request) -> list[str]:
    text = _texts(request)
    return [s for s in ALL_SENTENCES + [LEAD] if s in text]


def test_no_model_request_in_a_conversation_ever_holds_his_sentences(loaded, visitor, engine):
    """Four turns in one conversation: a profile answer, a capped one, a follow-up the model
    answers in its own words, then a tour beat that says his lead and a question after it. Every
    request the model was ever sent is searched for every sentence of the profile and the lead."""
    engine.model = FakeModel(
        [
            ak("education", "location"),
            ak("experience"),
            say("Happy to say more about any of that."),
            say("The tour shows how it is built."),
        ]
    )
    first = _events(_post(visitor, text="Tell me about Kevin."))
    conversation = _of(first, "turn")[0]["conversation_id"]
    _events(_post(visitor, text="And his experience?", conversation_id=conversation))
    _events(_post(visitor, text="Did he graduate?", conversation_id=conversation))
    beat = _events(_post(visitor, tour=0, conversation_id=conversation))
    assert [s["text"] for s in _of(beat, "sentence")][0] == LEAD
    _events(_post(visitor, text="What was that?", conversation_id=conversation))
    assert len(engine.model.requests) == 4
    for request in engine.model.requests:
        assert _profile_in(request) == []
    later = json.dumps(engine.model.requests[-1].contents)
    assert "(Read aloud from Kevin's profile: Education, Location.)" in later
    assert "(Read aloud from Kevin's profile: Experience.)" in later
    assert "(Tour line read aloud: who.)" in later


def test_the_record_keeps_what_was_said_and_history_replays_the_marker(loaded, visitor, engine):
    engine.model = FakeModel([ak("education"), say("Glad to help.")])
    first = _events(_post(visitor, text="Where did he study?"))
    conversation = _of(first, "turn")[0]["conversation_id"]
    record = _record(conversation)
    # Kevin reads his record with the real words in it.
    assert json.dumps(" ".join(EDUCATION)) in record
    assert "assistant_replay text=" in record
    _events(_post(visitor, text="Thanks.", conversation_id=conversation))
    replayed = engine.model.requests[-1].contents
    assert replayed[-2] == {
        "role": "model",
        "parts": [{"text": "(Read aloud from Kevin's profile: Education.)"}],
    }


def test_a_tour_code_line_is_replayed_as_said_and_a_content_line_as_a_marker():
    report = cascade.TurnReport()
    report.say(LEAD, "(Tour line read aloud: who.)")
    report.say(tour.LIVE_LINE)
    assert report.history_text() == f"(Tour line read aloud: who.) {tour.LIVE_LINE}"
    report.say("Second profile line.", "")
    assert report.history_text().endswith(tour.LIVE_LINE)
