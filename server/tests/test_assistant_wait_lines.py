"""The chained wait lines' rule (`agents/voice/wait_lines.py`), and that it is the phone's rule.

Kevin, 2026-11-24: the guide on /try and Alfred on the phone behave the same in every voice
behavior, only the personality differs, and the phone is the default to match. So the numbers and
the words here are read off the phone's own two files rather than written twice: a change to either
side with no change to the other fails. Invented examples only.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from agents.voice import wait_lines as w
from assistant import fillers

CASCADE_KT = (
    Path(__file__).resolve().parents[2]
    / "app/src/main/java/com/kevin/legion/service/cascade"
)
TIMING_KT = CASCADE_KT / "FillerTiming.kt"
WAIT_KT = CASCADE_KT / "WaitFillers.kt"
BACKCHANNEL_KT = CASCADE_KT / "Backchannel.kt"


def _const(path: Path, name: str) -> int:
    """`const val <name> = 1_234` from a Kotlin file, underscores dropped."""
    found = re.search(rf"const val {name}\s*=\s*([\d_]+)", path.read_text(encoding="utf-8"))
    assert found is not None, f"{name} is gone from {path.name}: the phone's rule moved"
    return int(found.group(1).replace("_", ""))


# ---- the phone is the source of these numbers --------------------------------------------------


@pytest.mark.parametrize(
    "ours,theirs",
    [
        (w.FIRST_GAP_MS, "FIRST_WAIT_GAP_MS"),
        (w.NEXT_GAP_MS, "NEXT_WAIT_GAP_MS"),
        (w.SPACING_MS, "SPACING_MS"),
        (w.MAX_PER_TURN, "MAX_PER_TURN"),
    ],
)
def test_every_timing_is_the_phones(ours, theirs):
    assert ours == _const(TIMING_KT, theirs)


@pytest.mark.parametrize(
    "ours,theirs", [(w.MIN_CLIP_MS, "MIN_CLIP_MS"), (w.MAX_CLIP_MS, "MAX_CLIP_MS")]
)
def test_the_clip_window_is_the_phones(ours, theirs):
    assert ours == _const(WAIT_KT, theirs)


def test_the_wait_lines_are_the_phones_words():
    found = re.search(r"val LINES = listOf\(([^)]*)\)", WAIT_KT.read_text(encoding="utf-8"))
    assert found is not None, "WaitFillers.LINES moved: the phone's words are the source"
    assert tuple(re.findall(r'"([^"]+)"', found.group(1))) == w.LINES


def test_the_acknowledgement_says_the_phones_backchannel_words():
    # Kevin, 2026-11-24: the demo's voice behavior is the phone's, only the persona differs. The
    # words were "Yeah, one sec." and three variants, which promised a look; the phone's claim
    # nothing, which is why every turn of theirs can have one.
    found = re.search(r"val LINES = listOf\(([^)]*)\)", BACKCHANNEL_KT.read_text(encoding="utf-8"))
    assert found is not None, "Backchannel.LINES moved: the phone's words are the source"
    assert tuple(re.findall(r'"([^"]+)"', found.group(1))) == fillers.ACK


def test_the_closing_chime_is_the_phones():
    """The web's chime (`frontend/src/assistant/cascade-words.ts`) against the phone's
    (`service/ListenCue.kt`). The web test cannot read a file (that project builds for a browser and
    has no `@types/node`), so the drift guard lives here, where reading Kotlin is already the habit.
    It was a single 150 ms 880 Hz tone at half gain until 2026-11-24."""
    kt = (CASCADE_KT.parents[1] / "ListenCue.kt").read_text(encoding="utf-8")
    ts = (
        Path(__file__).resolve().parents[1] / "frontend/src/assistant/cascade-words.ts"
    ).read_text(encoding="utf-8")
    notes = (
        "CLOSE_HIGH_HZ",
        "CLOSE_HIGH_SEC",
        "CLOSE_GAP_SEC",
        "CLOSE_LOW_HZ",
        "CLOSE_LOW_SEC",
        "CLOSE_EDGE_SEC",
    )
    for name in notes:
        phone = re.search(rf"const val {name} = ([\d_.]+)", kt)
        web = re.search(rf"const {name} = ([\d_.]+)", ts)
        assert phone is not None, f"{name} is gone from ListenCue.kt: the phone's chime moved"
        assert web is not None, f"{name} is gone from cascade-words.ts"
        assert float(phone.group(1)) == float(web.group(1)), name
    # The gain too: the phone's `EARCON_GAIN`, which the web spells the same.
    assert re.search(r"private const val EARCON_GAIN = ([\d.]+)", kt).group(1) == re.search(
        r"const EARCON_GAIN = ([\d.]+)", ts
    ).group(1)


def test_the_goodbye_skip_knows_the_phones_farewell_words():
    kt = (CASCADE_KT.parent / "Farewells.kt").read_text(encoding="utf-8")
    found = re.search(r"val FAREWELLS: List<String> = listOf\((.*?)\n    \)", kt, re.S)
    assert found is not None, "Farewells.FAREWELLS moved: the phone's words are the source"
    assert tuple(re.findall(r'"([^"]+)"', found.group(1))) == fillers.FAREWELLS


@pytest.mark.parametrize(
    "words",
    ["bye", "Goodbye.", "bye bye", "thanks, bye", "okay bye", "See you later!", "take care"],
)
def test_a_goodbye_is_one_however_it_is_said(words):
    # The engine's `is_stop_utterance` closes a conversation but does not know a plain "bye", so
    # the acknowledgement asks the phone's list instead (`fillers.is_farewell`).
    assert fillers.is_farewell(words)


@pytest.mark.parametrize(
    "words", ["Thanks!", "Hi there.", "Okay cool.", "What did we spend last month?", ""]
)
def test_courtesy_alone_is_not_a_goodbye(words):
    # "Thanks!" is courtesy at both ends and nothing else; it keeps its acknowledgement.
    assert not fillers.is_farewell(words)


def test_no_acknowledgement_promises_a_look():
    # The whole reason the words changed: one that promises a look cannot be said after "Thanks!".
    for line in fillers.ACK:
        assert not re.search(r"\b(sec|second|moment|minute|checking|looking)\b", line, re.I), line


def test_the_acknowledgement_and_the_holding_lines_share_no_words():
    # A reader tells the two kinds of line apart by its text (`cascade._answer`), so they must not
    # overlap.
    assert not set(fillers.ACK) & set(w.LINES)


def test_every_wait_line_is_warmed_so_it_can_be_said_from_memory():
    # A line not in `all_lines()` is never fetched, so it could never be said (spec 22).
    assert set(w.LINES) <= set(fillers.all_lines())


def test_no_wait_line_claims_an_outcome():
    # CLAUDE.md section 7: a holding line says a look is happening, never what was found.
    for line in w.LINES:
        assert not re.search(r"\b(done|found|sorted|set|sent|got it)\b", line, re.I), line


# ---- the chain ---------------------------------------------------------------------------------


def _fresh(now: float = 0.0) -> w.TurnFillers:
    return w.TurnFillers(last_end=now)


def test_the_first_wait_line_falls_one_and_a_half_seconds_after_the_words():
    assert _fresh().due_at() == pytest.approx(1.5)


def test_each_further_line_falls_two_and_a_half_seconds_after_the_one_before_ended():
    state = _fresh()
    state.note(w.LINES[0], clip_ms=800, now=1.5, wait=True)
    # 1.5 s start + 0.8 s of clip + 2.5 s.
    assert state.due_at() == pytest.approx(4.8)


def test_a_line_is_due_only_once_its_gap_has_passed():
    state = _fresh()
    assert not state.due_now(1.49)
    assert state.due_now(1.5)


def test_the_gap_runs_from_the_end_of_the_last_clip_not_its_start():
    quick, slow = _fresh(), _fresh()
    quick.note(w.LINES[0], clip_ms=200, now=1.5, wait=True)
    slow.note(w.LINES[0], clip_ms=1_300, now=1.5, wait=True)
    assert slow.due_at() - quick.due_at() == pytest.approx(1.1)


def test_a_task_line_the_engine_already_said_counts_toward_the_cap():
    state = _fresh()
    state.note("Checking the accounts.", clip_ms=900, now=0.7, wait=False)
    assert state.said == 1 and state.wait_said == 0
    # Still the FIRST wait line's gap: a task line is not a wait line.
    assert state.due_at() == pytest.approx(0.7 + 0.9 + 1.5)


def test_six_fillers_of_any_kind_is_the_cap():
    state = _fresh()
    for i in range(3):
        state.note(w.LINES[i], clip_ms=0, now=0.0, wait=True)
    state.note("Checking the accounts.", clip_ms=0, now=0.0, wait=False)
    state.note("Yeah, one sec.", clip_ms=0, now=0.0, wait=False)
    assert not state.capped and state.due_at() is not None
    state.note(w.LINES[3], clip_ms=0, now=0.0, wait=True)
    assert state.capped and state.due_at() is None


def test_the_chain_stops_once_every_line_has_been_said():
    state = _fresh()
    for line in w.LINES:
        state.note(line, clip_ms=0, now=0.0, wait=True)
    assert state.due_at() is None


def test_no_line_is_said_twice_in_a_turn():
    state = _fresh()
    state.note(w.LINES[0], clip_ms=400, now=1.5, wait=True)
    assert w.LINES[0] not in state.ready(lambda _line: True)


def test_a_task_lines_own_words_are_not_said_again_as_a_wait_line():
    state = _fresh()
    state.note("Working on it.", clip_ms=400, now=0.7, wait=False)
    assert "Working on it." not in state.ready(lambda _line: True)


def test_only_a_line_whose_audio_is_in_hand_is_offered():
    state = _fresh()
    assert state.ready(lambda line: line == w.LINES[2]) == (w.LINES[2],)
    assert state.ready(lambda _line: False) == ()


def test_a_task_line_is_led_by_what_spacing_it_still_owes_the_acknowledgement():
    state = _fresh()
    state.note("Yeah, one sec.", clip_ms=600, now=0.0, wait=False)
    # The clip ends at 0.6 s and the next may start at 1.0 s: at 0.9 s it owes 100 ms of silence.
    assert state.lead_ms(0.9) == 100
    assert state.lead_ms(1.0) == 0
    assert state.lead_ms(2.0) == 0


def test_the_first_filler_of_a_turn_leads_with_nothing():
    assert _fresh().lead_ms(0.0) == 0


@pytest.mark.parametrize("ms,fits", [(199, False), (200, True), (1_300, True), (1_301, False)])
def test_a_clip_outside_the_window_is_not_played(ms, fits):
    assert w.clip_fits(ms) is fits


# ---- the timeline a reader can check -----------------------------------------------------------


def test_a_four_second_wait_hears_two_lines():
    # Nothing said yet, an answer ready 4 s in, each line 0.8 s: 1.5 s and 4.8 s, so one fits.
    assert w.timeline(0.0, 0, answer_at=4.0, clip_s=0.8) == pytest.approx([1.5])


def test_a_ten_second_wait_hears_the_chain_and_never_more_than_the_cap():
    starts = w.timeline(0.0, 0, answer_at=60.0, clip_s=0.8)
    assert starts == pytest.approx([1.5, 4.8, 8.1, 11.4, 14.7])
    # Five lines exist; the cap of six never binds first.
    assert len(starts) == len(w.LINES)


def test_a_turn_that_already_said_five_fillers_hears_one_more_at_most():
    assert len(w.timeline(0.0, 5, answer_at=60.0, clip_s=0.8)) == 1


def test_an_answer_ready_before_the_first_gap_hears_nothing():
    assert w.timeline(0.0, 0, answer_at=1.4, clip_s=0.8) == []
