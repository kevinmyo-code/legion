"""wake-word ticket 20: the pure half of `tools/wake_eval/`, with fake VAD, KWS and confirm.

Proves the replay follows the app's stage rules and the metrics add up. It cannot prove the
real models behave; that is the eval run on Kevin's recordings.

    python -m pytest tools/tests/test_wake_eval.py -p no:cacheprovider
"""
from __future__ import annotations

import importlib.util
import json
import sys
import wave
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS / "wake_eval"))
spec = importlib.util.spec_from_file_location("core", TOOLS / "wake_eval" / "core.py")
core = importlib.util.module_from_spec(spec)
sys.modules["core"] = core
spec.loader.exec_module(core)


class FakeVad:
    """Speech wherever a chunk's first sample is non-zero."""

    def __init__(self):
        self._speech = False

    def accept(self, floats):
        self._speech = bool(floats) and floats[0] != 0.0

    def is_speech(self):
        return self._speech


class FakeKws:
    """Fires once it has been fed [fire_after] samples since its last fresh stream."""

    def __init__(self, fire_after: int | None):
        self.fire_after = fire_after
        self.fed = 0
        self.fresh_calls = 0
        self.first_feed_sizes: list[int] = []
        self._pending = 0

    def accept(self, floats):
        if self.fed == 0:
            self.first_feed_sizes.append(len(floats))
        self.fed += len(floats)
        self._pending = 1

    def is_ready(self):
        return self._pending > 0

    def decode(self):
        self._pending = 0

    def keyword(self):
        return "hey_alfred" if self.fire_after is not None and self.fed >= self.fire_after else ""

    def fresh(self):
        self.fresh_calls += 1
        self.fed = 0


def speech_pcm(silence_chunks: int, speech_chunks: int, tail_chunks: int = 3) -> list[int]:
    c = core.CHUNK
    return [0] * (silence_chunks * c) + [1000] * (speech_chunks * c) + [0] * (tail_chunks * c)


def test_shipped_constants_match_the_app():
    assert core.CHUNK == 3200
    assert core.PREROLL_SAMPLES == 8000
    assert core.CONFIRM_SAMPLES == 32000
    assert (core.APP_KEYWORDS_SCORE, core.APP_KEYWORDS_THRESHOLD, core.APP_NUM_TRAILING_BLANKS) == (5.0, 0.10, 1)
    assert core.APP_INPUT_GAIN == 4
    assert core.keyword_line(core.APP_KEYWORD) == "▁HE Y ▁A L F RE D @hey_alfred"


def test_apply_gain_scales_and_saturates_like_the_app():
    assert core.apply_gain([0, 100, -100, 10_000, -10_000, 32767, -32768], 4) == [
        0, 400, -400, 32767, -32768, 32767, -32768,
    ]
    assert core.apply_gain([5, -5], 1) == [5, -5]


def test_a_new_utterance_gets_a_fresh_stream_fed_the_lead_in():
    kws = FakeKws(fire_after=None)
    events = core.replay(speech_pcm(5, 4), FakeVad(), kws, confirm=lambda pcm: (True, ""))
    assert [e.stage for e in events] == ["vad_on", "vad_off"]
    # Lead-in: 0.5 s before plus the chunk the VAD flipped on.
    assert kws.first_feed_sizes == [core.PREROLL_SAMPLES + core.CHUNK]


def test_a_hit_confirmed_opens_and_a_rejected_one_does_not():
    rings: list[int] = []

    def confirm(pcm):
        rings.append(len(pcm))
        return False, "[unk]"

    events = core.replay(speech_pcm(15, 4), FakeVad(), FakeKws(fire_after=core.PREROLL_SAMPLES + 2 * core.CHUNK), confirm)
    stages = [e.stage for e in events]
    assert stages == ["vad_on", "kws_hit", "confirm", "vad_off"]
    assert core.opened(events) == 0
    assert rings == [core.CONFIRM_SAMPLES]

    events = core.replay(speech_pcm(15, 4), FakeVad(), FakeKws(fire_after=1), lambda pcm: (True, "hey alfred"))
    assert core.opened(events) == 1  # one open per utterance: the mic is gone after it


def test_silence_never_reaches_the_spotter():
    kws = FakeKws(fire_after=1)
    events = core.replay([0] * (10 * core.CHUNK), FakeVad(), kws, confirm=None)
    assert events == [] and kws.fed == 0


def test_kws_only_policy_opens_on_the_hit_once_per_utterance():
    two_utterances = speech_pcm(5, 3) + speech_pcm(0, 3)
    events = core.replay(two_utterances, FakeVad(), FakeKws(fire_after=1), confirm=None)
    assert core.opened(events) == 2


def test_a_rejected_hit_keeps_listening_in_the_same_utterance():
    verdicts = iter([(False, "[unk]"), (True, "hey alfred")])
    events = core.replay(speech_pcm(5, 4), FakeVad(), FakeKws(fire_after=1), lambda pcm: next(verdicts))
    assert [e.stage for e in events].count("confirm") == 2
    assert core.opened(events) == 1


def test_confirm_text_policies():
    assert core.vosk_text_accepts("phrase", "hey alfred")
    assert not core.vosk_text_accepts("phrase", "alfred")
    assert core.vosk_text_accepts("name_anywhere", "[unk] alfred")
    assert not core.vosk_text_accepts("name_anywhere", "[unk]")


def test_labels_follow_the_enrollment_plan():
    assert core.parse_label("pos_quiet_03") == (True, "quiet")
    assert core.parse_label("neg_ambient_01") == (False, "ambient")
    assert core.parse_label("live") is None
    assert core.parse_label("pos_quiet_3") is None


def test_spelling_validation_names_missing_tokens():
    vocab = {"▁HE", "Y", "▁A", "L", "F", "RE", "D"}
    assert core.validate_spelling(core.APP_KEYWORD, vocab) == []
    assert core.validate_spelling("▁HEY ▁A L F RE D", vocab) == ["▁HEY"]


def test_metrics_recall_per_condition_and_false_accepts_per_hour():
    def sample(label, seconds):
        positive, condition = core.parse_label(label)
        return core.Sample(Path(label), label, positive, condition, "take", [0] * int(seconds * core.SAMPLE_RATE))

    point = core.grid("app")[0]
    assert point.is_app
    s = core.Score(point)
    s.add(sample("pos_normal_01", 4), opens=1)
    s.add(sample("pos_normal_02", 4), opens=0)
    s.add(sample("pos_far_01", 4), opens=2)
    s.add(sample("neg_ambient_01", 120), opens=1)
    assert s.recall == 2 / 3
    assert s.false_accepts_per_hour == 30.0
    assert s.as_dict()["by_condition"] == {"far": {"hits": 1, "total": 1}, "normal": {"hits": 1, "total": 2}}
    assert not s.meets(core.RECALL_TARGET, core.FA_PER_HOUR_TARGET)


def test_rank_puts_points_meeting_the_target_first():
    pts = core.grid("quick")
    good, better_recall_but_noisy = core.Score(pts[0]), core.Score(pts[1])
    good.positives, good.hits, good.negative_seconds, good.false_accepts = 20, 19, 3600, 1
    better_recall_but_noisy.positives, better_recall_but_noisy.hits = 20, 20
    better_recall_but_noisy.negative_seconds, better_recall_but_noisy.false_accepts = 3600, 5
    assert core.rank([better_recall_but_noisy, good]) == [good, better_recall_but_noisy]


def test_grids_hold_the_shipped_point():
    for name in core.GRIDS:
        assert any(p.is_app for p in core.grid(name)), name


def test_load_samples_reads_takes_and_skips_live(tmp_path):
    def write(dirname, name, sidecar, pcm=(1, -1, 2)):
        d = tmp_path / dirname
        d.mkdir(exist_ok=True)
        with wave.open(str(d / f"{name}.wav"), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(core.SAMPLE_RATE)
            w.writeframes(b"".join(int(v).to_bytes(2, "little", signed=True) for v in pcm))
        (d / f"{name}.json").write_text(json.dumps(sidecar), encoding="utf-8")

    write("enroll-1", "a_pos_normal_01", {"kind": "take", "label": "pos_normal_01"})
    write("enroll-1", "b_neg_ambient_01", {"kind": "take", "label": "neg_ambient_01"})
    write("live", "c_live", {"kind": "live", "label": "live"})
    samples, skipped = core.load_samples(tmp_path)
    assert [s.label for s in samples] == ["pos_normal_01", "neg_ambient_01"]
    assert samples[0].pcm == [1, -1, 2]
    assert len(skipped) == 1 and "live segment" in skipped[0]
