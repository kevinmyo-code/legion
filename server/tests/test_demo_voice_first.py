# ruff: noqa: F811 - fixtures imported from the cascade tests
"""The demo's voice-first build (Kevin, 2026-11-17): honest per-tool fillers, the live strip's
numbers, early transcripts, the one-minute tour, the short opening and the funnel counts.

Google is never called: the text model is `FakeModel` and speech is the cascade tests' fake. Every
visitor, household, line of content and ref tag here is invented.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from io import StringIO

import pytest
from django.core.files.uploadedfile import SimpleUploadedFile
from django.core.management import call_command

from agents.runtime.model import FakeModel, call, say
from assistant import cascade, fillers, tour, voice_engine, web_greeting
from assistant.speech_tags import untagged as strip
from assistant.surface import RECRUITER_TOOLS, WEB_TOOL_NAMES
from demo import funnel
from demo.models import DemoBudgetDay, DemoFunnelDay
from tests.test_assistant_cascade import (  # noqa: F401 - fixtures
    PCM,
    _clean,
    _events,
    _kinds,
    _of,
    _post,
    engine,
    member,
    visitor,
    visitor_user,
)
from tests.test_recruiter_content import load_fake

pytestmark = pytest.mark.django_db


def _warm(monkeypatch, engine, user):
    audio = cascade.FillerAudio(spawn=lambda work: work())
    audio.warm(engine.speech, voice_engine.voice_for(user))
    monkeypatch.setattr(cascade, "FILLER_AUDIO", audio)
    monkeypatch.setattr(cascade, "LINES", fillers.LinePicker())
    engine.speech.spoken.clear()
    return audio


# =============================================================================
# Fillers say what is actually happening
# =============================================================================


def test_every_tool_either_role_can_reach_is_mapped_or_deliberately_silent():
    reachable = set(WEB_TOOL_NAMES) | set(RECRUITER_TOOLS)
    unmapped = sorted(t for t in reachable if t not in fillers.FILLERS and t not in fillers.SILENT)
    assert unmapped == []
    assert set(fillers.FILLERS) & fillers.SILENT == set()


def test_the_named_tools_say_what_they_are_doing():
    for tool, line in (
        ("monthly_spend", "Checking the accounts."),
        ("list_events", "Checking the calendar."),
        ("next_service", "Checking the car's records."),
        ("legion_docs", "Looking through the design docs."),
        ("about_kevin", "Pulling up Kevin's profile."),
        ("web_search", "Looking that up online."),
        ("show_diagram", "Bringing up the diagram."),
    ):
        assert line in fillers.lines_for(tool), tool
    assert fillers.lines_for("a_tool_added_later") == fillers.FALLBACK
    assert "Working on it." in fillers.FALLBACK


def test_filler_lines_are_short_varied_and_claim_no_result():
    for tool, (_slow, lines) in fillers.FILLERS.items():
        assert 2 <= len(lines) <= 3, tool
        assert len(set(lines)) == len(lines), tool
        for line in lines:
            assert len(line.split()) <= 6, line
            lowered = line.lower()
            for claim in ("done", "found", "added", "saved", "here is", "here's", "got it"):
                assert claim not in lowered, line
    for tool in fillers.SILENT:
        assert fillers.lines_for(tool) == ()


def test_the_picker_never_says_the_same_line_twice_running():
    picker = fillers.LinePicker(choose=lambda options: options[0])
    lines = ("A.", "B.")
    said = [picker.pick(lines) for _ in range(6)]
    assert all(a != b for a, b in zip(said, said[1:], strict=False))
    # Only the last line said is in hand: nothing, rather than a repeat.
    picker = fillers.LinePicker(choose=lambda options: options[0])
    assert picker.pick(("A.",)) == "A."
    assert picker.pick(("A.",)) is None


def test_a_fast_tool_gets_its_line_after_it_returns_once_the_wait_is_long(
    member, engine, monkeypatch, household_user
):
    _warm(monkeypatch, engine, household_user)
    monkeypatch.setattr(fillers, "FAST_AFTER_MS", 0)
    engine.model = FakeModel([call("list_events", {}), say("Your day is clear.")])
    events = _events(_post(member, audio=PCM))
    kinds = _kinds(events)
    assert kinds.index("tool") < kinds.index("filler") < kinds.index("sentence")
    filler = _of(events, "filler")[0]
    assert filler["tool"] == "list_events"
    assert filler["text"] in fillers.lines_for("list_events")
    # The filler's audio is flagged, so the page can tell it from the answer.
    flagged = [data for kind, data in events if kind == "audio" and data.get("filler")]
    assert len(flagged) == 1


def test_a_fast_tool_that_returns_quickly_gets_no_line(member, engine, monkeypatch, household_user):
    _warm(monkeypatch, engine, household_user)
    monkeypatch.setattr(fillers, "FAST_AFTER_MS", 10_000_000)
    engine.model = FakeModel([call("list_events", {}), say("Your day is clear.")])
    events = _events(_post(member, audio=PCM))
    assert _of(events, "filler") == []


def test_a_write_is_silent_and_two_tools_get_one_line(member, engine, monkeypatch, household_user):
    _warm(monkeypatch, engine, household_user)
    monkeypatch.setattr(fillers, "FAST_AFTER_MS", 0)
    engine.model = FakeModel(
        [
            call("list_events", {}),
            call("list_checklists", {}),
            say("Nothing on today and the lists are empty."),
        ]
    )
    events = _events(_post(member, audio=PCM))
    assert [f["tool"] for f in _of(events, "filler")] == ["list_events"]


def test_a_typed_turn_gets_no_filler(member, engine, monkeypatch, household_user):
    _warm(monkeypatch, engine, household_user)
    monkeypatch.setattr(fillers, "FAST_AFTER_MS", 0)
    engine.model = FakeModel([call("list_events", {}), say("Your day is clear.")])
    events = _events(_post(member, text="What's on?"))
    assert _of(events, "filler") == []


def test_a_filler_line_not_yet_made_is_skipped_not_waited_for(member, engine, monkeypatch):
    # Nothing warmed: the slow tool's line is fetched for next time and the answer is not held.
    monkeypatch.setattr(cascade, "FILLER_AUDIO", cascade.FillerAudio(spawn=lambda work: None))
    engine.model = FakeModel([call("legion_docs", {"query": "x"}), say("It is Django.")])
    events = _events(_post(member, audio=PCM))
    assert _of(events, "filler") == []
    assert _of(events, "sentence")


# =============================================================================
# The live strip's numbers
# =============================================================================


def test_tool_events_carry_how_long_they_took(member, engine):
    engine.model = FakeModel([call("list_events", {}), say("Clear.")])
    tool = _of(_events(_post(member, text="What's on?")), "tool")[0]
    assert isinstance(tool["ms"], int) and tool["ms"] >= 0


def test_a_visitor_turn_ends_with_its_estimated_cost_and_a_member_turn_does_not(
    visitor, member, engine
):
    engine.model = FakeModel([say("Hello there.")])
    done = _of(_events(_post(visitor, text="Hi")), "done")[0]
    assert isinstance(done["cost_usd"], float) and done["cost_usd"] >= 0
    assert done["tools_ms"] == []
    engine.model = FakeModel([say("Hello there.")])
    done = _of(_events(_post(member, text="Hi")), "done")[0]
    assert "cost_usd" not in done


def test_the_cost_estimate_prices_cached_input_at_the_cached_rate():
    timings = {"prompt_tokens": 1_000_000, "cached_tokens": 1_000_000, "output_tokens": 0}
    assert cascade.turn_cost_usd(timings) == pytest.approx(cascade.PRICE_TEXT_CACHED)
    timings = {"prompt_tokens": 1_000_000, "output_tokens": 1_000_000, "tts_output_tokens": 0}
    assert cascade.turn_cost_usd(timings) == pytest.approx(
        cascade.PRICE_TEXT_IN + cascade.PRICE_TEXT_OUT
    )


# =============================================================================
# Early transcripts (the phone's speculation)
# =============================================================================


def _transcribe(client, audio=PCM):
    return client.post(
        "/api/assistant/transcribe",
        {"audio": SimpleUploadedFile("s.pcm", audio, "application/octet-stream")},
        format="multipart",
    )


def test_transcribe_returns_the_words_and_answers_nothing(member, engine):
    engine.speech.heard = "How is LEGION built"
    response = _transcribe(member)
    assert response.status_code == 200
    assert response.data["text"] == "How is LEGION built"
    assert engine.model.requests == []
    assert engine.speech.spoken == []


def test_transcribe_refuses_a_stranger_and_a_live_caller(client, member, engine, monkeypatch):
    assert _transcribe(client).status_code in (401, 403)
    monkeypatch.setenv("LEGION_WEB_VOICE", "live")
    assert _transcribe(member).status_code == 409


def test_a_visitors_transcribe_is_charged_to_the_demo_budget(visitor, engine, settings):
    before = DemoBudgetDay.objects.count()
    assert _transcribe(visitor).status_code == 200
    assert DemoBudgetDay.objects.count() >= before
    settings.LEGION_DEMO_DAILY_BUDGET_USD = "0.00001"
    assert _transcribe(visitor).status_code == 429


def test_an_early_transcript_is_answered_aloud_without_transcribing_again(member, engine):
    engine.model = FakeModel([say("It runs on Django.")])
    events = _events(_post(member, text="How is it built", spoken="true"))
    assert _of(events, "heard")[0]["text"] == "How is it built"
    assert _of(events, "audio")
    assert engine.speech.transcribed == []


# =============================================================================
# The opening and the tour
# =============================================================================


def test_the_opening_is_short_names_the_tour_and_the_recording_and_never_says_train():
    line = strip(web_greeting.recruiter_hello("Alfred"))
    assert len(line.split()) <= 22
    assert "tour" in line and "recorded" in line
    assert "train" not in line.lower()
    assert web_greeting.recruiter_hello("").startswith("Hi, I'm Kevin's assistant.")


@pytest.mark.parametrize(
    ("words", "skip", "stop"),
    [
        ("skip", True, False),
        ("Next please.", True, False),
        ("stop", False, True),
        ("Stop the tour.", False, True),
        ("What does he do at work?", False, False),
        ("Don't stop telling me about the diagram", False, False),
    ],
)
def test_skip_and_stop_are_whole_utterances(words, skip, stop):
    assert tour.is_skip(words) is skip
    assert tour.is_stop(words) is stop


def _walk_tour(client, engine):
    """Plays every beat as the page would; returns (sentences, tools, done payloads)."""
    beat, said, tools, dones, conversation = 0, [], [], [], None
    for _ in range(10):
        fields = {"tour": beat}
        if conversation:
            fields["conversation_id"] = conversation
        events = _events(_post(client, **fields))
        conversation = _of(events, "turn")[0]["conversation_id"]
        said += [s["text"] for s in _of(events, "sentence")]
        tools += [t["name"] for t in _of(events, "tool")]
        done = _of(events, "done")[0]
        dones.append(done)
        if done["tour"]["next"] is None:
            break
        beat = done["tour"]["next"]
    return said, tools, dones


def test_the_tour_plays_from_loaded_content_and_ends_with_the_ask(visitor, engine, tmp_path):
    load_fake(tmp_path)
    said, tools, dones = _walk_tour(visitor, engine)
    assert said[0] == "Lorem ipsum lead."  # the content's own words, none written in code
    assert said[-1] == strip(tour.END_LINE)
    assert "show_resume" in tools
    assert 2 <= len(dones) <= 6
    # No model wrote a word of it.
    assert engine.model.requests == []


def test_a_tour_with_no_content_skips_the_beats_with_nothing_to_show(visitor, engine):
    said, _tools, _dones = _walk_tour(visitor, engine)
    assert "Lorem ipsum lead." not in said
    assert said[-1] == strip(tour.END_LINE)


def test_stop_said_during_the_tour_ends_it(visitor, engine, tmp_path):
    load_fake(tmp_path)
    engine.speech.heard = "stop"
    events = _events(_post(visitor, tour=1, audio=PCM))
    assert [s["text"] for s in _of(events, "sentence")] == [strip(tour.STOPPED_LINE)]
    assert _of(events, "done")[0]["tour"]["next"] is None


def test_a_question_during_the_tour_is_answered(visitor, engine, tmp_path):
    load_fake(tmp_path)
    engine.speech.heard = "What does the household spend on food?"
    engine.model = FakeModel([say("About three hundred a month.")])
    events = _events(_post(visitor, tour=1, audio=PCM))
    assert [s["text"] for s in _of(events, "sentence")] == ["About three hundred a month."]
    assert "tour" not in _of(events, "done")[0]


def test_the_tour_is_the_demos_alone_and_takes_no_text(member, visitor, engine):
    assert _post(member, tour=0).status_code == 400
    assert _post(visitor, tour=0, text="hi").status_code == 400


# =============================================================================
# Funnel counts
# =============================================================================


def test_a_funnel_step_is_counted_anonymously_with_a_clean_ref(client):
    for _ in range(2):
        response = client.post(
            "/api/demo/funnel", {"event": "page_load", "ref": "Indeed!"}, format="json"
        )
        assert response.status_code == 204
    row = DemoFunnelDay.objects.get(event="page_load")
    assert row.ref == "indeed" and row.count == 2
    bad = client.post("/api/demo/funnel", {"event": "visited_twice"}, format="json")
    assert bad.status_code == 400
    assert DemoFunnelDay.objects.count() == 1


def test_the_funnel_command_prints_counts_per_day_and_ref():
    funnel.count("page_load", "li")
    funnel.count("orb_tap", "li")
    funnel.count("page_load", "")
    out = StringIO()
    call_command("demo_funnel", stdout=out)
    text = out.getvalue()
    assert "page_load" in text and "tour_finished" in text
    assert " li " in text or "li  " in text


def test_funnel_rows_age_out_with_the_visitor_retention():
    old = datetime.now(UTC) - timedelta(days=400)
    funnel.count("page_load", "x", now=old)
    funnel.count("page_load", "x")
    assert funnel.prune(30) == 1
    assert DemoFunnelDay.objects.count() == 1
