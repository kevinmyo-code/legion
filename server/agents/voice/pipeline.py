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
- `filler` {tool, text}: the line said while that tool runs, from memory; its `audio` follows.
- `tool` {name, ok, outcome, guard}: a tool call ended (agents shape: never its text).
- `sentence` {text}: one finished sentence of the reply, tags removed, exact figures.
- `audio` {pcm, rate, filler?}: base64 16-bit mono PCM at `rate` (24000); `filler` true on a
  filler's audio. Only on a spoken turn (`audio` in, `speak` set, or `say`).
- `speech_failed` {reason}: a sentence's speech failed; its text was still sent.
- `speech_stopped` {reason}: the speech budget ran out; the rest is shown, not said.
- `done` {status, final_text, writes_ran, ignored?, end?}: last. `status` is the agent turn's own
  (`done`, `refused`, `cancelled`, `failed`, ...) or `nothing_heard` (with `ignored: "no_speech"`),
  `crisis` (with `end: true`, `final_text` empty) or `say`.
- `error` {words}: the turn could not run (e.g. `STT_FAILED` after the spoken miss).

Differences from the web door, kept on purpose in V1: `turn` and `tool` carry the agent turn's own
fields (the eval runner and the limb contract read them); `heard` follows `turn` here because the
turn row is made from the transcript; `crisis` is this door's only. The web door's `greeting`,
`offer`, `tour`, `cost_usd`, `tools_ms` and `words` are the web's alone.

## What a turn's speech may cost (review 2026-11-19, C3)

- Per turn: at most `MAX_TTS_REQUESTS` speech requests (no growth past it, unlike the web door's
  member turns) and at most `MAX_SPOKEN_CHARS` characters of the answer spoken; past either the
  rest is shown, not said, and `speech_stopped {reason: "limit"}` is sent once. A `say` line is
  one request of at most `MAX_SAY_CHARS`.
- Per day: `agents/voice/allowance.py` (the member cap, or a code default when it is unset).
- A cancel (`POST /api/agents/turn/<id>/cancel`) or a hang-up stops speech: nothing more is asked
  for and what is playing is dropped. Speech already paid for is metered, finished or not.

The per-turn record is one `agents_voice_turn` log line of `stage.TIMING_KEYS` (names and numbers,
never words), and speech is metered on the member's cap (`stage.meter_speech`).
"""

from __future__ import annotations

import logging
import time
from collections.abc import Callable, Iterator
from dataclasses import dataclass

from agents.legion.lines import MISSED_LINE, STT_FAILED
from agents.legion.turn import run_turn
from agents.runtime import events as ev
from agents.voice import crisis, stage
from agents.voice.filler_audio import FillerAudio
from agents.voice.speaker import Speaker
from agents.voice.speech import SpeechFailure
from assistant import fillers, speech_tags, spoken_figures

log = logging.getLogger(__name__)

FEATURE = "agent_turn"
KINDS = ("user", "greeting", "opener", "check_in", "proactive", "say")
BUILT_KINDS = frozenset({"user", "say"})
MAX_SAY_CHARS = 600
# The most of one answer that is spoken (characters). At about 15 characters a second that is
# about 100 s of speech, or about 2,500 speech tokens.
MAX_SPOKEN_CHARS = 1_500
# How often a turn playing speech asks the database whether it was cancelled.
CANCEL_EVERY_S = 0.2
# ESTIMATES for speech whose own counts never arrive (recheck 2026-11-20, metering leaks):
# - a request cut short by a cancel or a hang-up: Google sends its usage at the end of the stream,
#   so it is metered from the audio it had made (about 25 speech tokens a second) and its text
#   (about 4 characters a token);
# - a filler line fetched for memory (`FillerAudio.warm` keeps no counts): each line is metered to
#   the turn that sent for it at one short line's cost, 15 tokens in and 60 out (about 2.4 s).
#   A voice's whole set is about 25 lines, so about 1,500 tokens, about $0.014, once per voice per
#   process, plus a retry of a failed line at most every 10 minutes.
TTS_TOKENS_PER_AUDIO_S = 25
TTS_CHARS_PER_TOKEN = 4
FILLER_LINE_PROMPT_TOKENS = 15
FILLER_LINE_OUTPUT_TOKENS = 60

# The agents door's filler lines, kept in this process per voice (`FillerAudio`).
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


class AgentSpeaker(Speaker):
    """The agents door's speaker: it keeps every request it sent, so speech cut short is metered
    too (the base drops a cancelled request from `jobs` before the turn's end can count it)."""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.sent: list = []

    def _submit(self, spoken: str) -> None:
        before = len(self.jobs)
        super()._submit(spoken)
        if len(self.jobs) > before:
            self.sent.append(self.jobs[-1])

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
) -> Iterator[ev.Event]:
    """Yields the turn's events. `model_for` and `conversation_for` make the model client and open
    (or find) the conversation, called only once a turn will reach the model, so a silent clip, a
    failed transcription, a `say` or a crisis makes neither."""
    t0 = clock()
    timings: dict = {}
    filler_audio = filler_audio or FILLER_AUDIO
    speaker = AgentSpeaker(speech, voice, enabled=utterance.spoken, clock=clock)
    # Bounded per turn: never more requests than reserved at the start (review C3).
    speaker.more = lambda n: False
    cancel = TurnCancel(clock)
    speaker.cancelled = cancel
    speaker.cancelled_often = cancel.often
    status = "failed"
    fetched = 0
    try:
        if utterance.kind == "say":
            status = "say"
            yield from _say(speaker, utterance.text or "")
            return
        heard = None
        words = (utterance.transcript or "").strip()
        if utterance.audio is not None:
            # The first event, before the transcription it would otherwise wait on (review).
            yield ev.waiting()
            try:
                heard = stage.transcribe(speech, utterance.audio, timings)
            except SpeechFailure as exc:
                timings["stt_failed"] = str(exc)
                status = "stt_failed"
                # The miss is said like a person, and shown (the web door's rule).
                speaker.add(MISSED_LINE)
                yield from speaker.drain()
                yield ev.error(STT_FAILED)
                return
            words = heard.text
            if not words:
                # No speech: silence, no model call, nothing kept.
                status = "nothing_heard"
                yield ev.done("nothing_heard", "", False, ignored="no_speech")
                return
        if crisis.detect(words):
            # No model, no tool, no turn row, none of his words kept. One fixed line, by code,
            # never the model's, with the help number; the card for a client with a screen.
            status = "crisis"
            if heard is not None:
                yield ev.Event("heard", {"text": words})
            speaker.exact = True
            speaker.add(crisis.LINE)
            yield ev.Event("sentence", {"text": crisis.LINE})
            yield from speaker.drain()
            yield ev.Event("crisis", {"text": crisis.LINE})
            yield ev.done("crisis", "", False, end=True)
            return
        if speaker.enabled:
            # The filler lines' audio, fetched once speech was heard (room noise costs nothing).
            timings["speech_fetched"] = filler_audio.warm(speech, voice)
            fetched = timings["speech_fetched"]
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
        )
    finally:
        speaker.close()
        stage.record_speech(timings, speaker)
        # Speech cut short by a cancel or a hang-up was still paid for as far as it went, and the
        # filler lines this turn sent for are paid for too (estimates, above).
        cut_prompt, cut_output = speaker.cut_short_tokens()
        timings["tts_prompt_tokens"] += cut_prompt + fetched * FILLER_LINE_PROMPT_TOKENS
        timings["tts_output_tokens"] += cut_output + fetched * FILLER_LINE_OUTPUT_TOKENS
        timings["turn_ms"] = int((clock() - t0) * 1000)
        timings["tts_requests"] = speaker.requests
        stage.meter_speech(request, timings, FEATURE)
        log.warning(
            "agents_voice_turn status=%s spoken=%s %s",
            status,
            str(utterance.spoken).lower(),
            stage.timing_values(timings),
        )


def _say(speaker: Speaker, text: str) -> Iterator[ev.Event]:
    line = text.strip()[:MAX_SAY_CHARS]
    # Said as given: no figure in the client's line is rounded for speech.
    speaker.exact = True
    yield ev.Event("sentence", {"text": line})
    speaker.add(line)
    yield from speaker.drain()
    yield ev.done("say", line, False)


def _answer(
    request, words, heard, model, conversation, speaker, filler_audio, timings, clock, cancel
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
            if speaker.enabled and spoken_chars + len(text) <= MAX_SPOKEN_CHARS:
                spoken_chars += len(text)
                # To speech before it is shown, as on the web door: a cancel that lands while it
                # is shown stops everything after it.
                speaker.add(tagged, expressive=True, model=True)
            elif speaker.enabled and not speaker.stopped:
                speaker.stopped = True
                yield ev.Event("speech_stopped", {"reason": "limit"})
            yield ev.Event("sentence", {"text": text})
            continue
        # A round ended (a tool is next, or the turn): what it said goes to speech now.
        speaker.flush()
        if event.type == "done":
            status = event.data.get("status", "done")
            timings["sentences"] = sentences
            yield from speaker.drain()
            yield event
            continue
        if event.type == "error":
            yield from speaker.drain()
            yield event
            continue
        if event.type == "turn":
            cancel.turn_id = event.data.get("turn_id")
        yield event
        if event.type == "turn" and heard is not None:
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
                yield ev.Event("filler", {"tool": event.data["tool"], "text": line})
                yield speaker.audio_event(audio, filler=True)
    return status
