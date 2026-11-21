"""One voice turn on the agents door (`POST /api/agents/turn`, DARK behind `LEGION_AGENTS_TURN`):
audio in, the agent turn (`agents.legion.turn.run_turn`) in the middle, audio out, through the same
speech stage as the web door (`agents/voice`).

Kinds (`KINDS`): `user` (his words, as `audio` or a `transcript`) and `say` (a line the client
wants spoken as it is: no model, no turn row) are built. `greeting`, `opener`, `check_in` and
`proactive` are accepted names that the view refuses with `kind_not_built` until they are built.

## The events (Server-Sent Events, one per thing that happened)

The web door's schema (`POST /api/assistant/turn`), event for event; fields marked "agents" differ
from the web door's and are listed under "Differences".

- `waiting` {}: first, at once, on an `audio` turn, before speech-to-text (which can take
  seconds): the client knows the engine has its clip (wire v1 section 5, `waiting` exists; the
  client's "unreachable" rule counts from the post to the first event, client section 9).
- `turn` {turn_id, conversation_id, seq}: once the turn row exists (agents shape).
- `heard` {text}: the transcript of an `audio` turn, right after `turn`.
- `crisis` {text}: the crisis pre-check fired (`agents/voice/crisis.py`). `text` is the fixed
  crisis line, sent just before as a `sentence` (with its `audio` on a spoken turn); a client with
  a screen shows its crisis card and does not also caption that line. `done` follows at once.
- `working` {tool}: a slow tool is about to run (the runtime's own event).
- `filler` {tool, text, line_id}: the line said while that tool runs, from memory; its `audio`
  follows.
- `tool` {name, ok, outcome, guard}: a tool call ended (agents shape: never its text).
- `sentence` {text, line_id, role}: one finished sentence of the reply, tags removed, exact
  figures. `role` is `answer` here (the model's words, the crisis line, a `say` line), or `late`
  with `owed_id` for a line owed from a background task (`agents/legion/late.py`, dark behind
  LEGION_LATE_ANSWERS); wire v1's `write` and `owed` lines come with the write ledger.
- `audio` {pcm, rate, line_id, filler?}: base64 16-bit mono PCM at `rate` (24000); `line_id` is the
  line it speaks (one speech request per line on this door, so every chunk belongs to one line);
  `filler` true on a filler's audio. Only on a spoken turn (`audio` in, `speak` set, or `say`).
  The miss line ("Didn't catch that") is audio with a `line_id` and no `sentence`, as on the web.
- `line_id` is wire v1's `"<seq>.<stream_no>.<n>"`: the turn's `seq`, stream 1 (relay
  continuations come with the relay), `n` counting this stream's lines. A line said before any
  turn row exists (the miss, the crisis line, `say`) is `"0.<request stamp>.<n>"`: seq 0, never
  a turn's, and as its stream the request's arrival in epoch milliseconds times 1,000 plus this
  process's request count (mod 1,000), so it is unique in the conversation without a stored
  counter (a stored per-conversation counter would need a migration). A client treats every
  `line_id` as OPAQUE: a string it compares for equality and sends back in `played`, never parsed
  for its parts or ordered by them (the pre-turn stamp is a 16-digit number, not a stream count).
- `speech_failed` {reason}: a sentence's speech failed; its text was still sent.
- `speech_stopped` {reason}: the speech budget ran out; the rest is shown, not said.
- `done` {status, final_text, writes_ran, contract_version, timing, ignored?, end?}: last.
  `status` is the agent turn's own (`done`, `refused`, `cancelled`, `failed`, ...) or
  `nothing_heard` (with `ignored`: `noise` when the engine's loudness or voice gate kept the clip
  from speech-to-text, `no_speech` when speech-to-text heard no words), `crisis` (with
  `end: true`, `final_text` empty) or `say`. `contract_version` is the voice contract served (1).
  `timing` is the engine's own stage times, every one an offset in ms from the request's arrival
  at the view (after its upload), never a duration: `stt_done_ms` (the transcript came back),
  `heard_ms`, `first_sentence_ms`, `first_audio_ms` (each only once reached).
- `error` {words}: the turn could not run (e.g. `STT_FAILED` after the spoken miss).

Differences from the web door, kept on purpose in V1: `turn` and `tool` carry the agent turn's own
fields (the eval runner and the limb contract read them); `heard` follows `turn` here because the
turn row is made from the transcript; `crisis` is this door's only. The web door's `greeting`,
`offer`, `tour`, `cost_usd`, `tools_ms` and `words` are the web's alone.

## What a turn's speech may cost (review 2026-11-19, C3)

- Per turn: at most `MAX_SPOKEN_LINES` speech requests, one per line (no growth past it, unlike
  the web door's member turns), and at most `MAX_SPOKEN_CHARS` characters of the answer spoken;
  past either the
  rest is shown, not said, and `speech_stopped {reason: "limit"}` is sent once. A `say` line is
  one request of at most `MAX_SAY_CHARS`.
- Per day: `agents/voice/allowance.py` (the member cap, or a code default when it is unset).
- A cancel (`POST /api/agents/turn/<id>/cancel`) or a hang-up stops speech: nothing more is asked
  for and what is playing is dropped. Speech already paid for is metered, finished or not.

The per-turn record is one `agents_voice_turn` log line of `stage.TIMING_KEYS` (names and numbers,
never words), and speech is metered on the member's cap (`stage.meter_speech`).
"""

from __future__ import annotations

import itertools
import logging
import threading
import time
from collections.abc import Callable, Iterator
from dataclasses import dataclass

from django.db import connection as db_connection

from agents.legion.limbs import CONTRACT_VERSION
from agents.legion.lines import MISSED_LINE, STT_FAILED
from agents.legion.turn import run_turn
from agents.runtime import events as ev
from agents.voice import crisis, stage
from agents.voice.filler_audio import FillerAudio
from agents.voice.live_stt import BLOCKED as STT_BLOCKED
from agents.voice.speaker import Speaker
from agents.voice.speech import SpeechFailure
from assistant import fillers, speech_tags, spoken_figures

log = logging.getLogger(__name__)

FEATURE = "agent_turn"
KINDS = ("user", "greeting", "opener", "check_in", "proactive", "say")
BUILT_KINDS = frozenset({"user", "say"})
MAX_SAY_CHARS = 600
# The most of one answer that is spoken (characters of the text sent to speech, after links and
# figures are put into words). At about 15 characters a second that is
# about 100 s of speech, or about 2,500 speech tokens.
MAX_SPOKEN_CHARS = 1_500
# The most lines of one answer that are spoken, one speech request each (the web door's
# `MAX_SPOKEN_SENTENCES`). One more request is kept back for a closing line.
MAX_SPOKEN_LINES = 12
ROLE_ANSWER = "answer"
# How often a turn playing speech asks the database whether it was cancelled.
CANCEL_EVERY_S = 0.2
# ESTIMATES for speech whose own counts never arrive (recheck 2026-11-20, metering leaks):
# - a request cut short by a cancel or a hang-up: Google sends its usage at the end of the stream,
#   so it is metered from the audio it had made (about 25 speech tokens a second) and its text
#   (about 4 characters a token);
# - a filler line fetched for memory is metered when its fetch ends, to the member whose turn sent
#   for it: its own counts when Google reported them, else (audio came, no counts) one short
#   line's estimate, 15 tokens in and 60 out; a fetch that failed (no audio) costs nothing.
#   A voice's whole set is about 55 lines, about $0.03 at most, once per voice per process.
TTS_TOKENS_PER_AUDIO_S = 25
TTS_CHARS_PER_TOKEN = 4
FILLER_LINE_PROMPT_TOKENS = 15
FILLER_LINE_OUTPUT_TOKENS = 60

# The agents door's filler lines, kept in this process per voice (`FillerAudio`). A second cache
# beside the web door's `assistant.cascade.FILLER_AUDIO`, kept on purpose (recheck 2026-11-20):
# sharing that one would change the live web door, because its turns would then find lines in
# memory that only this door fetched (a filler said where none was before) and its line picker
# would skip a line this door said last. The cost of two caches is one more set of filler lines per
# voice per process (metered here: `filler_meter`). Merge them when the web door
# moves onto this pipeline.
FILLER_AUDIO = FillerAudio()


class TurnCancel:
    """ "Was this turn cancelled?" (`AgentTurn.cancel_requested`, set by the cancel route), for the
    speaker: no turn yet, no. `often` is the same question at most every `CANCEL_EVERY_S`, for the
    places asked many times a second. Once true it stays true."""

    def __init__(self, clock: Callable[[], float] = time.monotonic):
        self.turn_id: str | None = None
        self.hit = False
        self._clock = clock
        self._asked: float | None = None

    def __call__(self) -> bool:
        if self.hit or self.turn_id is None:
            return self.hit
        from agents.models import AgentTurn

        self._asked = self._clock()
        self.hit = AgentTurn.objects.filter(pk=self.turn_id, cancel_requested=True).exists()
        return self.hit

    def often(self) -> bool:
        if self._asked is not None and self._clock() - self._asked < CANCEL_EVERY_S:
            return self.hit
        return self()


_REQUESTS = itertools.count()


class Lines:
    """Wire v1 line ids for one stream: `"<seq>.1.<n>"` once the turn row exists, and before it
    `"0.<request ms>.<n>"` (see the module docstring)."""

    def __init__(self, request_ms: int):
        self.seq = 0
        self.stream = request_ms
        self.n = 0

    def turn(self, seq: int) -> None:
        self.seq, self.stream = seq, 1

    def next(self) -> str:
        self.n += 1
        return f"{self.seq}.{self.stream}.{self.n}"


class AgentSpeaker(Speaker):
    """The agents door's speaker. One speech request per line, so each `audio` event names the one
    line it speaks (wire v1 `line_id`); and it keeps every request it sent, so speech cut short is
    metered too (the base drops a cancelled request from `jobs` before the turn's end counts it)."""

    def __init__(self, speech, voice: str, enabled: bool, clock=time.monotonic):
        super().__init__(speech, voice, enabled, clock, grouping="sentence")
        self.allowed = MAX_SPOKEN_LINES + 1
        self.sent: list = []
        self._line: str | None = None

    def say(self, text: str, line_id: str, **kwargs) -> None:
        self._line = line_id
        try:
            self.add(text, **kwargs)
        finally:
            self._line = None

    def _submit(self, spoken: str) -> None:
        before = len(self.jobs)
        super()._submit(spoken)
        if len(self.jobs) > before:
            job = self.jobs[-1]
            job.line_id = self._line
            self.sent.append(job)

    def audio_event(self, chunk: bytes, filler: bool = False) -> ev.Event:
        event = super().audio_event(chunk, filler)
        # Called while the line being spoken is the head of `jobs` (`Speaker._take`); a filler's
        # line id is set by the caller.
        if not filler and self.jobs and getattr(self.jobs[0], "line_id", None):
            event.data["line_id"] = self.jobs[0].line_id
        return event

    def cut_short_tokens(self) -> tuple[int, int]:
        """(prompt, output) tokens of requests that never finished: their own counts if any came,
        else the estimate from the audio they made and their text."""
        finished = {id(stats) for stats in self.done}
        prompt = output = 0
        for job in self.sent:
            stats = job.stats
            if id(stats) in finished:
                continue
            if stats.prompt_tokens or stats.output_tokens:
                prompt += stats.prompt_tokens
                output += stats.output_tokens
            else:
                prompt += len(job.text) // TTS_CHARS_PER_TOKEN + 1
                output += stats.audio_ms * TTS_TOKENS_PER_AUDIO_S // 1000
        return prompt, output


@dataclass(frozen=True)
class Utterance:
    kind: str = "user"
    # 16 kHz PCM16 mono samples, header already removed (`agents/views.py`).
    audio: bytes | None = None
    transcript: str | None = None
    # `say`: the line to speak.
    text: str | None = None
    # A `transcript` turn answered aloud too (an `audio` or `say` turn always is).
    speak: bool = False
    # The streamed door's result (`streaming.Streamed`): its transcript, usage and marks.
    streamed: object | None = None

    @property
    def spoken(self) -> bool:
        return self.audio is not None or self.kind == "say" or self.speak


def voice_turn(
    request,
    utterance: Utterance,
    model_for: Callable[[], object],
    speech,
    *,
    conversation_for: Callable[[], object],
    voice: str,
    filler_audio: FillerAudio | None = None,
    clock: Callable[[], float] = time.monotonic,
    arrived_at: float | None = None,
) -> Iterator[ev.Event]:
    """Yields the turn's events. `model_for` and `conversation_for` make the model client and open
    (or find) the conversation, called only once a turn will reach the model, so a silent clip, a
    failed transcription, a `say` or a crisis makes neither. `arrived_at` is when the request
    reached the view, on `clock`: `done.timing` counts from it."""
    t0 = clock()
    origin = t0 if arrived_at is None else arrived_at
    timings: dict = {}
    filler_audio = filler_audio or FILLER_AUDIO
    speaker = AgentSpeaker(speech, voice, enabled=utterance.spoken, clock=clock)
    lines = Lines(int(time.time() * 1000) * 1000 + next(_REQUESTS) % 1000)
    marks: dict = {}
    # Bounded per turn: never more requests than reserved at the start (review C3).
    speaker.more = lambda n: False
    cancel = TurnCancel(clock)
    speaker.cancelled = cancel
    speaker.cancelled_often = cancel.often
    status = "failed"
    try:
        if utterance.kind == "say":
            status = "say"
            yield from _say(speaker, utterance.text or "", lines, marks, origin, clock, timings)
            return
        heard = None
        words = (utterance.transcript or "").strip()
        if utterance.audio is not None:
            # The first event, before the transcription it would otherwise wait on (review).
            yield ev.waiting()
            streamed = utterance.streamed
            if streamed is not None:
                # The streamed door's words (`agents/voice/streaming.py`): the stream's final
                # transcript, or None when its session failed and the batch path takes the clip.
                marks.update(streamed.marks)
                marks["stt_fallback"] = streamed.fallback
                timings["stt_stream_prompt_tokens"] = streamed.stream_prompt_tokens
                timings["stt_stream_output_tokens"] = streamed.stream_output_tokens
            try:
                if streamed is not None and streamed.failure == STT_BLOCKED:
                    # The stream's service refused the words: the batch path's refusal, with no
                    # second request (review 1120, finding 9).
                    raise SpeechFailure(STT_BLOCKED)
                if streamed is not None and streamed.heard is not None:
                    heard = streamed.heard
                    timings.update(audio_ms=heard.audio_ms, stt_ms=heard.ms)
                else:
                    heard = stage.transcribe(speech, utterance.audio, timings)
            except SpeechFailure as exc:
                timings["stt_failed"] = str(exc)
                status = "stt_failed"
                # The miss is said like a person, and shown (the web door's rule).
                speaker.say(MISSED_LINE, lines.next())
                yield from speaker.drain()
                yield ev.error(STT_FAILED)
                return
            words = heard.text
            marks.setdefault("stt_done_ms", _ms(clock, origin))
            if not words:
                # No speech: silence, no model call, nothing kept. `noise` when no request was
                # sent at all (the loudness or voice gate), `no_speech` when one heard no words.
                status = "nothing_heard"
                ignored = "noise" if heard.gated else "no_speech"
                yield _done(
                    "nothing_heard", "", False, speaker, marks, origin, clock, ignored=ignored
                )
                return
        if crisis.detect(words):
            # No model, no tool, no turn row, none of his words kept. One fixed line, by code,
            # never the model's, with the help number; the card for a client with a screen.
            status = "crisis"
            if heard is not None:
                marks["heard_ms"] = _ms(clock, origin)
                yield ev.Event("heard", {"text": words})
            speaker.exact = True
            line_id = lines.next()
            speaker.say(crisis.LINE, line_id)
            marks["first_sentence_ms"] = _ms(clock, origin)
            yield _sentence(crisis.LINE, line_id)
            yield from speaker.drain()
            yield ev.Event("crisis", {"text": crisis.LINE})
            yield _done("crisis", "", False, speaker, marks, origin, clock, end=True)
            return
        if speaker.enabled:
            # The filler lines' audio, fetched once speech was heard (room noise costs nothing).
            timings["speech_fetched"] = filler_audio.warm(
                speech, voice, on_fetched=filler_meter(request)
            )
        # He asked for exact figures: nothing is rounded when spoken (the web door's rule; its
        # second half, exact after a write, needs the tool event's write flag: slice E3).
        speaker.exact = spoken_figures.wants_exact(words)
        status = yield from _answer(
            request,
            words,
            heard,
            model_for(),
            conversation_for(),
            speaker,
            filler_audio,
            timings,
            clock,
            cancel,
            lines,
            marks,
            origin,
        )
    finally:
        speaker.close()
        stage.record_speech(timings, speaker)
        # Speech cut short by a cancel or a hang-up was still paid for as far as it went.
        cut_prompt, cut_output = speaker.cut_short_tokens()
        timings["tts_prompt_tokens"] += cut_prompt
        timings["tts_output_tokens"] += cut_output
        timings["turn_ms"] = int((clock() - t0) * 1000)
        timings["tts_requests"] = speaker.requests
        stage.meter_speech(request, timings, FEATURE)
        streamed = utterance.streamed
        log.warning(
            "agents_voice_turn status=%s spoken=%s %s%s",
            status,
            str(utterance.spoken).lower(),
            stage.timing_values(timings),
            (
                f" stream=1 stt_fallback={streamed.fallback} stt_failure={streamed.failure or '-'}"
                if streamed is not None
                else ""
            ),
        )


def filler_meter(request) -> Callable[[object, bool], None]:
    """What `FillerAudio.warm` tells about each line it fetched: metered on the member's cap
    (`FILLER_LINE_*` above). Runs on the fetching thread, which then closes its own database
    connection; on the request's own thread (a test's synchronous fetch) it leaves it open."""
    origin = threading.current_thread()

    def meter(stats, paid: bool) -> None:
        if stats.prompt_tokens or stats.output_tokens:
            prompt, output = stats.prompt_tokens, stats.output_tokens
        elif paid:
            prompt, output = FILLER_LINE_PROMPT_TOKENS, FILLER_LINE_OUTPUT_TOKENS
        else:
            return
        try:
            stage.meter_speech(
                request, {"tts_prompt_tokens": prompt, "tts_output_tokens": output}, FEATURE
            )
        except Exception:  # noqa: BLE001 - metering a filler line must never break its fetch
            log.warning("agents voice: filler meter failed", exc_info=True)
        if threading.current_thread() is not origin:
            try:
                db_connection.close()
            except Exception:  # noqa: BLE001 - nor may closing the thread's own connection
                log.warning("agents voice: filler meter could not close its connection")

    return meter


def _ms(clock, t0) -> int:
    return int((clock() - t0) * 1000)


def _sentence(text: str, line_id: str) -> ev.Event:
    return ev.Event("sentence", {"text": text, "line_id": line_id, "role": ROLE_ANSWER})


def _done(status, final_text, writes_ran, speaker, marks, origin, clock, **extra) -> ev.Event:
    """`done` with wire v1's `contract_version` and the engine's own stage times (offsets from
    `origin`, the request's arrival)."""
    timing = dict(marks)
    if speaker.first_audio_ms is not None:
        # The speaker counts from its own start; moved onto the request's arrival.
        timing["first_audio_ms"] = (
            int((speaker.started_at - origin) * 1000) + speaker.first_audio_ms
        )
    timing = {k: v for k, v in timing.items() if isinstance(v, int) and v >= 0}
    return ev.done(
        status,
        final_text,
        writes_ran,
        **{**extra, "contract_version": CONTRACT_VERSION, "timing": timing},
    )


def _say(speaker, text, lines, marks, t0, clock, timings) -> Iterator[ev.Event]:
    line = text.strip()[:MAX_SAY_CHARS]
    # Said as given: no figure in the client's line is rounded for speech.
    speaker.exact = True
    line_id = lines.next()
    speaker.say(line, line_id)
    marks["first_sentence_ms"] = _ms(clock, t0)
    yield _sentence(line, line_id)
    yield from speaker.drain()
    yield _done("say", line, False, speaker, marks, t0, clock)


def _answer(
    request,
    words,
    heard,
    model,
    conversation,
    speaker,
    filler_audio,
    timings,
    clock,
    cancel,
    lines,
    marks,
    t_turn,
):
    """The agent turn's events, with its sentences spoken as they come (up to the turn's bound)
    and a slow tool's filler line said from memory. Returns the turn's status."""
    t0 = clock()
    status = "failed"
    sentences = 0
    spoken_chars = 0
    filler_said = False
    for event in run_turn(request, words, model, conversation=conversation):
        yield from speaker.ready()
        if event.type == "sentence":
            tagged = event.data.get("text", "")
            text = speech_tags.untagged(tagged)
            if not text:
                continue
            sentences += 1
            if timings.get("first_sentence_ms") is None:
                timings["first_sentence_ms"] = int((clock() - t0) * 1000)
                marks["first_sentence_ms"] = _ms(clock, t_turn)
            line_id = lines.next()
            # Counted as it goes to speech: a link is said "the link on screen", a figure in words.
            said = (
                speaker.spoken_text(tagged, expressive=True, model=True) if speaker.enabled else ""
            )
            if speaker.enabled and spoken_chars + len(said) <= MAX_SPOKEN_CHARS:
                spoken_chars += len(said)
                # To speech before it is shown, as on the web door: a cancel that lands while it
                # is shown stops everything after it.
                speaker.say(tagged, line_id, expressive=True, model=True)
            elif speaker.enabled and not speaker.stopped:
                speaker.stopped = True
                yield ev.Event("speech_stopped", {"reason": "limit"})
            sentence = _sentence(text, line_id)
            if event.data.get("role") == "late":
                # A late-answer line keeps its role and owed id (`agents/legion/late.py`).
                sentence.data.update(role="late", owed_id=event.data.get("owed_id"))
            yield sentence
            continue
        # A round ended (a tool is next, or the turn): what it said goes to speech now.
        speaker.flush()
        if event.type == "done":
            status = event.data.get("status", "done")
            timings["sentences"] = sentences
            yield from speaker.drain()
            data = dict(event.data)
            yield _done(
                data.pop("status"),
                data.pop("final_text"),
                data.pop("writes_ran"),
                speaker,
                marks,
                t_turn,
                clock,
                **data,
            )
            continue
        if event.type == "error":
            yield from speaker.drain()
            yield event
            continue
        if event.type == "turn":
            cancel.turn_id = event.data.get("turn_id")
            lines.turn(event.data.get("seq") or 0)
        yield event
        if event.type == "turn" and heard is not None:
            marks["heard_ms"] = _ms(clock, t_turn)
            yield ev.Event("heard", {"text": words})
        if (
            event.type == "working"
            and speaker.enabled
            and not sentences
            and not filler_said
            and fillers.is_slow(event.data.get("tool", ""))
        ):
            picked = filler_audio.pick(speaker.voice, event.data["tool"])
            if picked is not None:
                line, audio = picked
                filler_said = True
                timings["filler"] = 1
                timings["filler_ms"] = int((clock() - t0) * 1000)
                line_id = lines.next()
                yield ev.Event(
                    "filler", {"tool": event.data["tool"], "text": line, "line_id": line_id}
                )
                said = speaker.audio_event(audio, filler=True)
                said.data["line_id"] = line_id
                yield said
    return status
