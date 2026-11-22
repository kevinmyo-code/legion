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
from demo import profile
from demo.profile import NOT_SHARED_LINE, OFFERS, Section, speak, split_sentences
from demo.tools import ABOUT_KEVIN, ABOUT_KEVIN_SPOKEN, spoken_for
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
ROLES = ["- Data engineer", "- Support engineer"]
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
MORE = profile.MORE
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
        # Said as written: a bullet or a number is part of the line (blind review, 2026-11-18).
        (
            "- bullet one\n* bullet two\n1. numbered",
            ["- bullet one", "* bullet two", "1. numbered"],
        ),
        # The number that opens a line is never a sentence end (blind review r2, B1).
        ("2029. Joined Fabrikam as an intern.", ["2029. Joined Fabrikam as an intern."]),
        ("1. 2. 3.", ["1. 2.", "3."]),
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


@pytest.mark.parametrize(
    ("text", "sentences"),
    [
        (
            "1. Data engineer\n2. Support engineer\n3. Analyst",
            ["1. Data engineer", "2. Support engineer", "3. Analyst"],
        ),
        (
            "Roles:\n1) Data engineer\n2) Analyst",
            ["Roles:", "1) Data engineer", "2) Analyst"],
        ),
        (
            "10. Tenth item. It has two sentences.",
            ["10. Tenth item.", "It has two sentences."],
        ),
        ("2029. Joined Fabrikam as an intern.", ["2029. Joined Fabrikam as an intern."]),
        (
            "2028. Started at Contoso. 2029. Joined Fabrikam.",
            ["2028. Started at Contoso.", "2029. Joined Fabrikam."],
        ),
        ("GPA 3.75 overall. Next.", ["GPA 3.75 overall.", "Next."]),
        ("Ranked No. 5 in the class. Next.", ["Ranked No. 5 in the class.", "Next."]),
        (
            "Office at 221 Baker Street. Visits welcome.",
            ["Office at 221 Baker Street.", "Visits welcome."],
        ),
        ("Unit 12. Then the lobby.", ["Unit 12.", "Then the lobby."]),
        ("3.", ["3."]),
        ("3.\n4. Fourth", ["3.", "4. Fourth"]),
    ],
)
def test_a_number_that_opens_a_line_is_part_of_it(text, sentences):
    assert split_sentences(text) == sentences


def test_a_three_item_numbered_list_is_read_whole_under_the_cap():
    lines = tuple(split_sentences("1. Data\n2. Support\n3. Analyst"))
    sections = [Section("roles", "Roles", lines)]
    assert speak(sections, ["roles"]).lines == ("1. Data", "2. Support", "3. Analyst")


def test_cutting_keeps_every_character():
    for text in PROFILE.values():
        flat = "".join(text.split())
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
        # Spoken in his order (Location, Availability, Education), whatever order was picked.
        (
            ["education", "location", "availability"],
            None,
            LOCATION + AVAILABILITY + EDUCATION,
            None,
        ),
        # Six sentences: the first three in his order fit (5); Salary, after them, is named.
        (
            ["salary", "education", "location", "availability"],
            None,
            LOCATION + AVAILABILITY + EDUCATION,
            "There's more on Salary; just ask.",
        ),
        # The first that does not fit stops the reading; it and every one after it are named.
        (
            ["salary", "experience", "availability"],
            None,
            AVAILABILITY,
            "There's more on Experience, Salary; just ask.",
        ),
        (
            ["location", "roles_wanted", "contact", "salary"],
            None,
            LOCATION + SALARY + CONTACT,
            "There's more on Roles wanted; just ask.",
        ),
        # The first picked section alone is over the cap: its head, and there is more.
        (["experience"], None, EXPERIENCE[:5], MORE),
        (
            ["contact", "experience", "salary"],
            None,
            EXPERIENCE[:5],
            "There's more on Experience, Salary, Contact; just ask.",
        ),
        (["experience"], "experience", EXPERIENCE[5:], None),
        ([], "experience", EXPERIENCE[5:], None),
        # A section already read whole: said so, not read again.
        ([], "availability", ["That's all of Availability."], None),
        (["hobbies"], None, [NOT_SHARED_LINE], None),
        ([], None, [NOT_SHARED_LINE], None),
        (["education", "hobbies"], None, EDUCATION, None),
    ],
)
def test_what_is_said_is_whole_sections_in_his_order_capped(chosen, rest_of, lines, more):
    spoken = speak(SECTIONS, chosen, rest_of)
    assert list(spoken.lines) == lines
    assert spoken.more == more
    assert len(spoken.lines) <= profile.CAP


@pytest.mark.parametrize(
    ("chosen", "lines", "more"),
    [
        # Every section from the top of his file while they fit; the rest named in his order.
        (
            ["overview"],
            LOCATION + AVAILABILITY + EDUCATION,
            "There's more on Experience, Salary, Contact, Roles wanted; just ask.",
        ),
        # With named ids too, it is still the overview.
        (
            ["salary", "overview", "contact"],
            LOCATION + AVAILABILITY + EDUCATION,
            "There's more on Experience, Salary, Contact, Roles wanted; just ask.",
        ),
        # A named pick is unchanged.
        (["salary", "contact"], SALARY + CONTACT, None),
    ],
)
def test_overview_reads_his_sections_in_his_order(chosen, lines, more):
    spoken = speak(SECTIONS, chosen)
    assert list(spoken.lines) == lines
    assert spoken.more == more


def test_overview_is_declared_first_with_the_titles(loaded, visitor_user):
    tool = spoken_for(visitor_user)
    enum = tool.input_schema["properties"]["sections"]["items"]["enum"]
    assert enum[0] == "overview"
    assert "overview (a general question about him)" in tool.description


def test_a_more_line_names_only_titles_that_exist():
    for chosen in (
        ["experience"],
        ["experience", "contact", "education"],
        ["hobbies", "experience", "salary"],
        ["availability", "experience", "roles_wanted"],
    ):
        more = speak(SECTIONS, chosen).more or ""
        if more == MORE:
            continue
        named = more.removeprefix("There's more on ").removesuffix("; just ask.")
        for title in named.split(", ") if named else []:
            assert title in PROFILE, title


def test_his_order_survives_the_database_and_an_old_row_keeps_the_stored_order(tmp_path):
    """Postgres keeps a JSON object's keys in its own order (by length first), so the loader
    stores the order he wrote beside the profile; the page is never served it."""
    from demo.content import public_content
    from demo.models import RecruiterContent

    load_fake(tmp_path, content=_content())
    content = RecruiterContent.current().content
    assert content[profile.PROFILE_ORDER] == list(PROFILE)
    assert [
        s.title for s in profile.sections_of(content["profile"], content[profile.PROFILE_ORDER])
    ] == list(PROFILE)
    assert profile.PROFILE_ORDER not in public_content(content)
    # A row loaded before the order was kept: the stored order, nothing dropped.
    stored = profile.sections_of(content["profile"])
    assert sorted(s.title for s in stored) == sorted(PROFILE)
    # An order naming a section no longer there, or missing one, still gives every section once.
    mixed = profile.sections_of(PROFILE, ["Salary", "Gone", "Location"])
    assert [s.title for s in mixed][:2] == ["Salary", "Location"]
    assert sorted(s.title for s in mixed) == sorted(PROFILE)


# =============================================================================
# The tool: titles only for the model, sentences only for code
# =============================================================================


def test_the_model_is_declared_titles_and_ids_never_a_sentence(loaded, visitor_user):
    tool = spoken_for(visitor_user)
    assert "education (Education)" in tool.description
    assert "roles_wanted (Roles wanted)" in tool.description
    # Postgres keeps a JSON object's keys in its own order, so the ids are compared as a set.
    assert set(tool.input_schema["properties"]["sections"]["items"]["enum"]) == {
        "overview",
        *(s.id for s in profile.sections_of(PROFILE)),
    }
    declared = json.dumps({"d": tool.description, "s": tool.input_schema})
    for sentence in ALL_SENTENCES + [LEAD]:
        assert sentence not in declared


def test_with_no_profile_the_declaration_says_nothing_is_shared(visitor_user):
    tool = spoken_for(visitor_user)
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
    ("about", "Tell me about Kevin.", [ak("education", "location")], LOCATION + EDUCATION),
    ("study", "Where did he study?", [ak("education")], EDUCATION),
    (
        "overview",
        "What's his background?",
        [ak("overview")],
        LOCATION
        + AVAILABILITY
        + EDUCATION
        + ["There's more on Experience, Salary, Contact, Roles wanted; just ask."],
    ),
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
        EXPERIENCE[:5] + [MORE],
    ),
    ("rest", "Tell me the rest of his experience.", [ak(rest_of="experience")], EXPERIENCE[5:]),
    ("authorised", "Is Kevin authorised to work in the US?", [ak()], [NOT_SHARED_LINE]),
    ("sponsor", "Does he need sponsorship?", [ak()], [NOT_SHARED_LINE]),
    # An id that is not one of his sections is refused by the tool's own schema; asked again.
    ("unknown id", "What are his hobbies?", [ak("hobbies"), ak()], [NOT_SHARED_LINE]),
    (
        "known and unknown",
        "Education and hobbies?",
        [ak("education", "hobbies"), ak("education")],
        EDUCATION,
    ),
    (
        "over the cap",
        "Tell me everything about him.",
        [ak("education", "location", "availability", "salary")],
        LOCATION + AVAILABILITY + EDUCATION + ["There's more on Salary; just ask."],
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
        EXPERIENCE[:5] + [MORE],
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
    # A later call in the same round still runs; the turn then ends on the profile.
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


# Follow-ups: the same table's columns plus the prior turns of the conversation. The next turn's
# model must still see what was asked of the visitor (the offer, the "more" line), and a section
# asked for again is said again word for word.
STUDY = ("Where did he study?", [ak("education", offer="resume")])
FOLLOW_UPS = [
    (
        "yes to the offer",
        [STUDY],
        "Yes.",
        [call("show_resume"), say("His resume is on the screen now.")],
        ["His resume is on the screen now."],
        "(Read aloud from Kevin's profile: Education.) Want to see his résumé?",
    ),
    (
        "go on after the cap",
        [("His experience?", [ak("experience")])],
        "Go on.",
        [ak(rest_of="experience")],
        EXPERIENCE[5:],
        f"(Read aloud from Kevin's profile: Experience, sentences 1 to 5 of 7.) {MORE}",
    ),
    (
        "the same question again",
        [("Where did he study?", [ak("education")])],
        "Where did he study, again?",
        [ak("education")],
        EDUCATION,
        "(Read aloud from Kevin's profile: Education.)",
    ),
    (
        "a follow-up with no tool",
        [("Where did he study?", [ak("education")])],
        "Is that a good school?",
        [say("I can't judge schools; ask him directly.")],
        ["I can't judge schools; ask him directly."],
        "(Read aloud from Kevin's profile: Education.)",
    ),
]


@pytest.mark.parametrize(
    ("case", "prior", "words", "rounds", "said", "replayed"),
    FOLLOW_UPS,
    ids=[row[0] for row in FOLLOW_UPS],
)
def test_a_follow_up_sees_the_question_put_to_the_visitor_and_never_his_sentences(
    loaded, visitor, engine, case, prior, words, rounds, said, replayed
):
    engine.model = FakeModel([r for _, prior_rounds in prior for r in prior_rounds] + list(rounds))
    conversation = None
    for prior_words, _ in prior:
        fields = {"text": prior_words}
        if conversation:
            fields["conversation_id"] = conversation
        events = _events(_post(visitor, **fields))
        conversation = _of(events, "turn")[0]["conversation_id"]
    events = _events(_post(visitor, text=words, conversation_id=conversation))
    assert [s["text"] for s in _of(events, "sentence")] == said
    asked = engine.model.requests[len(engine.model.requests) - len(rounds)]
    assert {"role": "model", "parts": [{"text": replayed}]} in asked.contents
    for request in engine.model.requests:
        assert not _profile_in(request), case


# =============================================================================
# Blind review 2026-11-18: lost answers, cap edges, his order unknown, his work
# =============================================================================

LONG = {"Story": " ".join(f"Pretend fact number {n}." for n in range(1, 13))}
MANY = {**PROFILE, **{f"Extra {n}": f"Extra fact {n}." for n in range(1, 7)}}


def test_a_long_section_carries_on_past_its_second_part_and_says_where_it_stopped():
    sections = profile.sections_of(LONG)
    first = speak(sections, ["story"])
    assert len(first.lines) == 5 and first.more == MORE
    assert first.part == "sentences 1 to 5 of 12"
    second = speak(sections, [], "story")
    assert second.lines[0] == "Pretend fact number 6." and second.more == MORE
    assert second.part == "sentences 6 to 10 of 12"
    third = speak(sections, [], "story", from_sentence=11)
    assert list(third.lines) == ["Pretend fact number 11.", "Pretend fact number 12."]
    assert third.more is None and third.part == "sentences 11 to 12 of 12"
    done = speak(sections, [], "story", from_sentence=13)
    assert list(done.lines) == ["That's all of Story."]


def test_the_more_line_names_four_titles_at_most_then_and_more():
    spoken = speak(profile.sections_of(MANY), ["overview"])
    assert spoken.more == (
        "There's more on Experience, Salary, Contact, Roles wanted and more; just ask."
    )


def test_without_his_order_the_overview_asks_which_one_and_reads_nothing():
    sections = profile.sections_of(PROFILE)
    spoken = speak(sections, ["overview"], ordered=False)
    assert spoken.read == ()
    assert list(spoken.lines) == [
        "I can tell you about Location, Availability, Education, Experience, Salary, Contact, "
        "Roles wanted; which one?"
    ]
    # A named pick is not affected.
    assert list(speak(sections, ["education"], ordered=False).lines) == EDUCATION


def test_a_row_loaded_before_the_order_was_kept_gets_the_which_one_line(loaded, visitor, engine):
    from demo.models import RecruiterContent

    row = RecruiterContent.current()
    row.content = {k: v for k, v in row.content.items() if k != profile.PROFILE_ORDER}
    row.save(update_fields=["content"])
    engine.model = FakeModel([ak("overview")])
    events = _events(_post(visitor, text="Tell me about Kevin."))
    said = [s["text"] for s in _of(events, "sentence")]
    assert len(said) == 1 and said[0].startswith("I can tell you about ")
    assert said[0].endswith("; which one?")


def _net_cash(monkeypatch):
    """account_balances as a household with cash would answer: one code-written money sentence."""
    import dataclasses

    from engine_mcp import tools as engine_tools

    line = "Net cash is $1,234 across two accounts."

    def handler(request, arguments):
        return engine_tools.ok("Balances.", {engine_tools.SPEAK_VERBATIM: line})

    tool = dataclasses.replace(engine_tools.TOOLS_BY_NAME["account_balances"], handler=handler)
    monkeypatch.setitem(engine_tools.TOOLS_BY_NAME, "account_balances", tool)
    return line


@pytest.mark.parametrize("cash_first", [True, False])
def test_net_cash_and_his_profile_in_one_turn_are_both_said(
    loaded, visitor, engine, monkeypatch, cash_first
):
    cash = _net_cash(monkeypatch)
    calls = [call("account_balances"), ak("availability")]
    if not cash_first:
        calls.reverse()
    engine.model = FakeModel([both(*calls)])
    events = _events(_post(visitor, text="What's the net cash, and tell me about Kevin?"))
    said = [s["text"] for s in _of(events, "sentence")]
    assert cash in said
    assert AVAILABILITY[0] in said


def test_a_resume_asked_for_in_the_same_round_still_opens(loaded, visitor, engine):
    engine.model = FakeModel([both(ak("availability"), call("show_resume"))])
    events = _events(_post(visitor, text="When can he start, and show me his resume."))
    assert [t["name"] for t in _of(events, "tool")] == ["about_kevin", "show_resume"]
    assert any(t.get("card", {}).get("card") == "resume" for t in _of(events, "tool"))
    assert [s["text"] for s in _of(events, "sentence")] == AVAILABILITY


def test_a_write_in_the_same_round_is_confirmed_in_code_words(loaded, visitor, engine):
    engine.model = FakeModel(
        [both(call("create_checklist", {"name": "Questions for Kevin"}), ak("availability"))]
    )
    events = _events(
        _post(visitor, text="Make a list called questions for Kevin. When can he start?")
    )
    said = [s["text"] for s in _of(events, "sentence")]
    assert said == AVAILABILITY + [cascade.CHANGE_MADE]
    assert _of(events, "done")[0]["writes_ran"] is True


def test_a_question_about_his_work_is_his_words_and_no_diagram_is_offered():
    # A "yes" to a diagram offer would have the model narrate his job over the diagram's labels in
    # its own words (blind review r2): no diagram is offered for his work until captions are code's.
    from assistant.prompt import RECRUITER_FRAME, cascade_recruiter_frame

    frame = cascade_recruiter_frame()
    assert "what Kevin did on a project or job, or how he builds" not in frame
    assert "call about_kevin first and offer no diagram" in frame
    assert "offer=diagram" not in frame
    # Live keeps it as it was (`test_profile_live_unchanged.py` checks the bytes).
    assert "what Kevin did on a project or job, or how he builds" in RECRUITER_FRAME


def test_the_offers_are_resume_and_contact_only():
    assert set(profile.OFFERS) == {"resume", "contact"}
    assert profile.offer_line("diagram", has_resume=True, has_contact=True) is None
    assert "diagram" not in ABOUT_KEVIN_SPOKEN.input_schema["properties"]["offer"]["enum"]
    assert "diagram" not in ABOUT_KEVIN_SPOKEN.description


def test_an_offer_of_a_diagram_is_refused_and_asked_again(loaded, visitor, engine):
    engine.model = FakeModel([ak("experience", offer="diagram"), ak("availability")])
    events = _events(_post(visitor, text="What did he do at his last job?"))
    assert [s["text"] for s in _of(events, "sentence")] == AVAILABILITY
    assert "Want the diagram for that?" not in str(events)


def test_a_second_profile_call_in_the_turn_is_not_read(loaded, visitor, engine):
    # The cap (five lines and one closing line) is what one turn's speech reserve covers: a second
    # reading in the same round would double it (blind review r2).
    engine.model = FakeModel([both(ak("education"), ak("location"))])
    events = _events(_post(visitor, text="Where did he study, and where is he?"))
    assert [s["text"] for s in _of(events, "sentence")] == EDUCATION
    tools = _of(events, "tool")
    assert [t["name"] for t in tools] == ["about_kevin", "about_kevin"]
    assert tools[0]["ok"] is True
    assert tools[1]["ok"] is False and tools[1]["text"].startswith("Not read:")
    assert not any(sentence in tools[1]["text"] for sentence in ALL_SENTENCES)
    assert len(_of(events, "done")) == 1


def test_a_refused_profile_call_does_not_use_up_the_reading(loaded, visitor, engine):
    engine.model = FakeModel([both(ak("hobbies"), ak("location"))])
    events = _events(_post(visitor, text="Hobbies, or where he is?"))
    assert [s["text"] for s in _of(events, "sentence")] == LOCATION


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


def test_live_keeps_the_tool_that_hands_the_model_the_profile(loaded, visitor_user):
    """The voice rollback (Live) has no code speaker: its about_kevin is dev's, unchanged (the
    byte-for-byte check against 421306f0 is `test_profile_live_unchanged.py`)."""
    from assistant.surface import tools_for

    live = next(t for t in tools_for(visitor_user) if t.name == "about_kevin")
    assert live is ABOUT_KEVIN

    class Request:
        user = visitor_user

    result = ABOUT_KEVIN.handler(Request(), {})
    assert all(sentence in result.text.replace("\n", " ") for sentence in AVAILABILITY)


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
    assert [s["text"] for s in _of(beat, "sentence")][0] == LOCATION[0]
    diagram = _events(_post(visitor, tour=1, conversation_id=conversation))
    caption = [s["text"] for s in _of(diagram, "sentence")][0]
    assert _of(diagram, "tool")[0]["name"] == "show_diagram"
    _events(_post(visitor, text="What was that?", conversation_id=conversation))
    assert len(engine.model.requests) == 4
    for request in engine.model.requests:
        assert _profile_in(request) == []
    later = json.dumps(engine.model.requests[-1].contents)
    assert "(Read aloud from Kevin's profile: Location, Education.)" in later
    assert "(Read aloud from Kevin's profile: Experience, sentences 1 to 5 of 7.)" in later
    assert "(Tour line read aloud: who.)" in later
    # A diagram's caption is about the project, not him: the follow-up still sees it.
    assert json.dumps(caption)[1:-1] in later
    assert "(Tour line read aloud: legion.)" not in later


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


def test_a_profile_line_with_a_link_and_a_slash_list_is_spoken_exactly(tmp_path, visitor, engine):
    # Blind review round 7: the speech wording rewrote profile lines ("Python/SQL/Django" became
    # "the file on screen"). His lines are code's: spoken as written, links and slashes too.
    profile = {
        "Skills": "Python/SQL/Django and React, Node.js, Next.js and Vue.js.\n"
        "Portfolio: https://example.invalid/pat/work"
    }
    load_fake(tmp_path, content=_content(profile=profile))
    engine.model = FakeModel([ak("skills")])
    _events(_post(visitor, text="What are his skills?", spoken="true"))
    spoken = " ".join(item[0] for item in engine.speech.spoken)
    assert "Python/SQL/Django and React, Node.js, Next.js and Vue.js." in spoken
    assert "https://example.invalid/pat/work" in spoken
    assert "on screen" not in spoken


_FIRST_PERSON = ("I", "I'm", "I've", "me", "my", "mine", "myself")


def test_the_tours_who_beat_is_his_profile_in_the_third_person_never_the_page_lead(
    tmp_path, visitor, engine
):
    # Kevin, 2026-11-18: "the 1 minute tour the ai says 'I' when it should be kevin". The page's
    # lead is written in the first person for the page; the tour speaks his profile's first
    # section (his order, as written) instead. No pronoun is ever swapped.
    load_fake(
        tmp_path,
        content=_content(
            hero={**_content()["hero"], "lead": "I build pretend things. My work is fake."}
        ),
    )
    beat = _events(_post(visitor, tour=0))
    said = [s["text"] for s in _of(beat, "sentence")][0]
    # One sentence since 2026-11-22 (Kevin found the tour too dense).
    assert said == LOCATION[0]
    words = said.replace(".", " ").replace(",", " ").split()
    assert not any(w in _FIRST_PERSON or w.lower() in ("my", "me", "mine", "myself") for w in words)
    assert "I build" not in said


def test_without_his_order_the_tour_skips_the_who_beat(tmp_path, visitor, engine):
    load_fake(tmp_path, content=_content())
    from demo.models import RecruiterContent

    row = RecruiterContent.current()
    row.content = {k: v for k, v in row.content.items() if k != profile.PROFILE_ORDER}
    row.save(update_fields=["content"])
    beat = _events(_post(visitor, tour=0))
    said = [s["text"] for s in _of(beat, "sentence")]
    assert not any(s in " ".join(said) for s in LOCATION)


def test_a_profile_word_is_respelled_for_speech_and_kept_on_screen(
    tmp_path, visitor, engine, monkeypatch
):
    from assistant import spoken_words

    monkeypatch.setattr(spoken_words, "SAY_AS", {"Quanex": "Kwah-nex"})
    load_fake(
        tmp_path, content=_content(profile={"Work": "Interned at Quanex for a pretend summer."})
    )
    engine.model = FakeModel([ak("work")])
    events = _events(_post(visitor, text="Where did he work?", spoken="true"))
    assert [s["text"] for s in _of(events, "sentence")] == [
        "Interned at Quanex for a pretend summer."
    ]
    spoken = " ".join(item[0] for item in engine.speech.spoken)
    assert "Kwah-nex" in spoken and "Quanex" not in spoken
