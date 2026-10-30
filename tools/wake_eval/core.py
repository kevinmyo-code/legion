"""Pure half of the wake-word offline eval (wake-word ticket 20).

No sherpa-onnx, no vosk, no numpy: everything here runs on the standard library so
`tools/tests/test_wake_eval.py` can check it on any machine. The native half is
`engines.py`; the CLI is `wake_eval.py`.

What this file mirrors from the app, and where (change one, change both):

| Here                        | App                                                         |
|-----------------------------|-------------------------------------------------------------|
| SAMPLE_RATE, CHUNK          | WakeWordEngine (16 kHz, 200 ms reads)                       |
| PREROLL_SAMPLES             | WakeTwoStageDetector.PREROLL_SAMPLES (0.5 s into the KWS)   |
| CONFIRM_SAMPLES             | WakeTwoStageDetector.CONFIRM_SECONDS (2 s ring to Vosk)     |
| replay()                    | WakeTwoStageDetector.processOnWorker + WakeStageMachine     |
| feed_kws()                  | KwsFeeder.feed (decode only while ready, fresh stream on hit)|
| APP_* constants             | WakeTwoStageDetector.create (shipped values, uncalibrated)  |
| APP_KEYWORD                 | WakeKeywords.build for "hey alfred" (BpeKeywordTokenizer)   |
"""
from __future__ import annotations

import itertools
import json
import re
import wave
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Iterable, Protocol, Sequence

SAMPLE_RATE = 16_000
CHUNK = SAMPLE_RATE // 5
PREROLL_SAMPLES = SAMPLE_RATE // 2
CONFIRM_SAMPLES = 2 * SAMPLE_RATE
SHORT_SCALE = 32768.0

# Shipped operating point (ticket 18): every one a documented guess, never calibrated.
APP_KEYWORDS_SCORE = 1.0
APP_KEYWORDS_THRESHOLD = 0.25
APP_NUM_TRAILING_BLANKS = 1
APP_MAX_ACTIVE_PATHS = 4
APP_VAD = {
    "threshold": 0.5,
    "min_silence_duration": 0.5,
    "min_speech_duration": 0.1,
    "window_size": 512,
    "max_speech_duration": 20.0,
}
APP_PHRASE = "hey alfred"
APP_KEYWORD = "▁HE Y ▁A L F RE D"

# Alternative token spellings to try. Each is validated against tokens.txt before use;
# one the model cannot express is reported and skipped, never silently dropped.
KEYWORD_SPELLINGS = [
    APP_KEYWORD,
    "▁HA Y ▁A L F RE D",
    "▁HE Y ▁A L F R ED",
    "▁HE Y ▁A L F R E D",
]

LABEL_RE = re.compile(r"^(pos|neg)_([a-z]+)_(\d\d)$")


# --- Samples -----------------------------------------------------------------


@dataclass
class Sample:
    """One recording pulled off the phone: a WAV and its JSON sidecar."""

    path: Path
    label: str
    positive: bool
    condition: str
    kind: str  # "take" (enrollment) or "live"
    pcm: list[int]
    sidecar: dict = field(default_factory=dict)

    @property
    def seconds(self) -> float:
        return len(self.pcm) / SAMPLE_RATE


def parse_label(label: str) -> tuple[bool, str] | None:
    """`pos_<condition>_<nn>` or `neg_<condition>_<nn>` (WakeEnrollPlan) -> (positive, condition)."""
    m = LABEL_RE.match(label)
    if not m:
        return None
    return m.group(1) == "pos", m.group(2)


def read_wav(path: Path) -> list[int]:
    """16 kHz mono 16-bit PCM, as WavEncoder writes it. Anything else is refused in words."""
    with wave.open(str(path), "rb") as w:
        if w.getframerate() != SAMPLE_RATE or w.getnchannels() != 1 or w.getsampwidth() != 2:
            raise ValueError(
                f"{path.name}: {w.getframerate()} Hz, {w.getnchannels()} ch, "
                f"{8 * w.getsampwidth()}-bit; expected 16000 Hz mono 16-bit"
            )
        raw = w.readframes(w.getnframes())
    return [int.from_bytes(raw[i:i + 2], "little", signed=True) for i in range(0, len(raw), 2)]


def load_samples(root: Path) -> tuple[list[Sample], list[str]]:
    """Every labelled enrollment take under [root]; returns (samples, skipped-with-reason).

    Live segments (`live/`) carry no ground truth, so they are skipped here: whether Kevin said
    the phrase in one is unknown until someone labels it. A take recorded while the Vosk-only
    fallback was listening (`twoStage` false) is kept, since the replay does not depend on what
    the phone decided.
    """
    samples: list[Sample] = []
    skipped: list[str] = []
    for wav in sorted(root.rglob("*.wav")):
        side_path = wav.with_suffix(".json")
        sidecar = json.loads(side_path.read_text(encoding="utf-8")) if side_path.exists() else {}
        kind = sidecar.get("kind", "live" if wav.parent.name == "live" else "take")
        label = sidecar.get("label", "")
        if kind != "take":
            skipped.append(f"{wav.name}: live segment, no ground-truth label")
            continue
        parsed = parse_label(label)
        if parsed is None:
            skipped.append(f"{wav.name}: label {label!r} is not pos_/neg_<condition>_<nn>")
            continue
        try:
            pcm = read_wav(wav)
        except (ValueError, wave.Error) as e:
            skipped.append(str(e))
            continue
        positive, condition = parsed
        samples.append(Sample(wav, label, positive, condition, kind, pcm, sidecar))
    return samples, skipped


def validate_spelling(spelling: str, vocab: set[str]) -> list[str]:
    """Tokens in [spelling] the model's tokens.txt does not have (empty = usable)."""
    return [t for t in spelling.split() if t not in vocab]


def read_vocab(tokens_txt: Path) -> set[str]:
    return {line.split()[0] for line in tokens_txt.read_text(encoding="utf-8").splitlines() if line.strip()}


def keyword_line(spelling: str, tag: str = "hey_alfred") -> str:
    """One keywords-file line, the shape WakeKeywords.build writes."""
    return f"{spelling} @{tag}"


# --- The pipeline, as the app runs it ---------------------------------------


class Vad(Protocol):
    def accept(self, floats: list[float]) -> None: ...
    def is_speech(self) -> bool: ...


class KwsStream(Protocol):
    """One sherpa-onnx OnlineStream plus its spotter, as KeywordStreamPort sees it."""

    def accept(self, floats: list[float]) -> None: ...
    def is_ready(self) -> bool: ...
    def decode(self) -> None: ...
    def keyword(self) -> str: ...
    def fresh(self) -> None: ...


Confirmer = Callable[[list[int]], tuple[bool, str]]
"""Stage 2: buffered int16 audio, oldest first -> (accepted, what it heard)."""


@dataclass
class Event:
    stage: str  # "vad_on", "vad_off", "kws_hit", "confirm", "open"
    at_sample: int
    detail: str = ""


def feed_kws(stream: KwsStream, floats: list[float]) -> bool:
    """KwsFeeder.feed: decode only while ready; on a hit take a fresh stream and stop."""
    stream.accept(floats)
    while stream.is_ready():
        stream.decode()
        if stream.keyword().strip():
            stream.fresh()
            return True
    return False


def replay(
    pcm: Sequence[int],
    vad: Vad,
    stream: KwsStream,
    confirm: Confirmer | None,
    chunk: int = CHUNK,
) -> list[Event]:
    """Runs one recording through stage 0 -> 1 -> 2 exactly as WakeTwoStageDetector does.

    [confirm] None means "a KWS hit opens on its own" (the skip-Vosk confirm policy). The ring
    is the last CONFIRM_SAMPLES of audio including silence, because the phrase starts before the
    VAD flips on; a new utterance's stream is fed the PREROLL_SAMPLES lead-in first.

    At most one open per utterance: on the phone an open hands the mic to the conversation, so
    the rest of that utterance is never heard by the wake engine. A rejected hit keeps listening.
    """
    events: list[Event] = []
    ring: list[int] = []
    in_speech = False
    opened_here = False
    pos = 0
    while pos < len(pcm):
        buf = list(pcm[pos:pos + chunk])
        pos += len(buf)
        ring.extend(buf)
        if len(ring) > CONFIRM_SAMPLES:
            del ring[: len(ring) - CONFIRM_SAMPLES]
        floats = [s / SHORT_SCALE for s in buf]
        vad.accept(floats)
        speech = vad.is_speech()
        was = in_speech
        if not speech and in_speech:
            in_speech = False
            events.append(Event("vad_off", pos))
        if speech and not in_speech:
            in_speech = True
            opened_here = False
            events.append(Event("vad_on", pos))
        if not speech or opened_here:
            continue
        if was:
            feed = floats
        else:
            stream.fresh()
            feed = [s / SHORT_SCALE for s in ring[-(PREROLL_SAMPLES + len(buf)):]]
        if not feed_kws(stream, feed):
            continue
        events.append(Event("kws_hit", pos))
        if confirm is None:
            events.append(Event("open", pos, "kws only"))
            opened_here = True
            continue
        accepted, heard = confirm(list(ring))
        events.append(Event("confirm", pos, heard))
        if accepted:
            events.append(Event("open", pos, heard))
            opened_here = True
    # The app drops the stream at VAD off and never decodes a partial tail; so does this.
    return events


def opened(events: Iterable[Event]) -> int:
    return sum(1 for e in events if e.stage == "open")


# --- Confirm policies --------------------------------------------------------


def vosk_text_accepts(policy: str, text: str, phrase: str = APP_PHRASE) -> bool:
    """How stage 2 reads Vosk's text. "phrase" is the app today (WakeWordEngine.confirmWithVosk)."""
    text = text.strip()
    if policy == "phrase":
        return phrase in text
    if policy == "name_anywhere":
        return phrase.split()[-1] in text.split()
    raise ValueError(f"unknown confirm policy {policy!r}")


CONFIRM_POLICIES = ["phrase", "name_anywhere", "kws_only"]


# --- Sweep and metrics -------------------------------------------------------


@dataclass(frozen=True)
class Point:
    keywords_threshold: float
    keywords_score: float
    num_trailing_blanks: int
    spelling: str
    confirm_policy: str

    @property
    def is_app(self) -> bool:
        return (
            self.keywords_threshold == APP_KEYWORDS_THRESHOLD
            and self.keywords_score == APP_KEYWORDS_SCORE
            and self.num_trailing_blanks == APP_NUM_TRAILING_BLANKS
            and self.spelling == APP_KEYWORD
            and self.confirm_policy == "phrase"
        )


GRIDS = {
    # The shipped point alone: does the replay reproduce what the phone did?
    "app": dict(
        keywords_threshold=[APP_KEYWORDS_THRESHOLD],
        keywords_score=[APP_KEYWORDS_SCORE],
        num_trailing_blanks=[APP_NUM_TRAILING_BLANKS],
        spelling=[APP_KEYWORD],
        confirm_policy=["phrase"],
    ),
    "quick": dict(
        keywords_threshold=[0.1, 0.15, 0.2, 0.25],
        keywords_score=[1.0, 1.5, 2.0],
        num_trailing_blanks=[1],
        spelling=[APP_KEYWORD],
        confirm_policy=CONFIRM_POLICIES,
    ),
    "full": dict(
        keywords_threshold=[0.05, 0.1, 0.15, 0.2, 0.25, 0.3],
        keywords_score=[0.5, 1.0, 1.5, 2.0, 3.0],
        num_trailing_blanks=[1, 2],
        spelling=KEYWORD_SPELLINGS,
        confirm_policy=CONFIRM_POLICIES,
    ),
}


def grid(name: str) -> list[Point]:
    g = GRIDS[name]
    keys = ["keywords_threshold", "keywords_score", "num_trailing_blanks", "spelling", "confirm_policy"]
    return [Point(*combo) for combo in itertools.product(*(g[k] for k in keys))]


@dataclass
class Score:
    point: Point
    positives: int = 0
    hits: int = 0
    negative_seconds: float = 0.0
    false_accepts: int = 0
    by_condition: dict[str, list[int]] = field(default_factory=dict)  # condition -> [hits, total]

    def add(self, sample: Sample, opens: int) -> None:
        if sample.positive:
            self.positives += 1
            hit = opens > 0
            self.hits += int(hit)
            c = self.by_condition.setdefault(sample.condition, [0, 0])
            c[0] += int(hit)
            c[1] += 1
        else:
            self.negative_seconds += sample.seconds
            self.false_accepts += opens

    @property
    def recall(self) -> float | None:
        return self.hits / self.positives if self.positives else None

    @property
    def false_accepts_per_hour(self) -> float | None:
        return self.false_accepts * 3600.0 / self.negative_seconds if self.negative_seconds else None

    def meets(self, recall_target: float, fa_per_hour_target: float) -> bool:
        r, fa = self.recall, self.false_accepts_per_hour
        return r is not None and fa is not None and r >= recall_target and fa <= fa_per_hour_target

    def as_dict(self) -> dict:
        return {
            "point": self.point.__dict__ | {"is_app": self.point.is_app},
            "positives": self.positives,
            "hits": self.hits,
            "recall": self.recall,
            "negative_seconds": round(self.negative_seconds, 1),
            "false_accepts": self.false_accepts,
            "false_accepts_per_hour": self.false_accepts_per_hour,
            "by_condition": {k: {"hits": v[0], "total": v[1]} for k, v in sorted(self.by_condition.items())},
        }


# Ticket 20's target. A point that misses it on his recordings does not ship.
RECALL_TARGET = 0.95
FA_PER_HOUR_TARGET = 1.0


def rank(scores: list[Score]) -> list[Score]:
    """Best first: points meeting the target, then by recall, then by fewest false accepts."""
    def key(s: Score):
        return (
            not s.meets(RECALL_TARGET, FA_PER_HOUR_TARGET),
            -(s.recall or 0.0),
            s.false_accepts_per_hour if s.false_accepts_per_hour is not None else float("inf"),
        )
    return sorted(scores, key=key)
