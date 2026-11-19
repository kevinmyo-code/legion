"""PROTOTYPE, off by default (`voice_engine.audio_first_round_on`, perf/try-faster-first-word fix 5):
the model's first round hears the utterance itself, asked the moment the audio arrives, while
speech-to-text runs beside it for the caption, the record and every check.

Measured before (2026-11-19, /try): speech-to-text took ~1.2 s and the first model round could not
start until it returned. Here both start together, so the first round ends at about the later of
the two instead of their sum.

What does NOT change, by construction:
- The words. The caption, the record, the stop phrase, the echo check, "that sounded robotic", the
  addressed signals and the acknowledgement all read speech-to-text's words, exactly as before, and
  run before anything the first round said is used.
- Nothing is said early. The first round's events are held in a queue and handed to the turn's
  loop only when the loop asks for its first round, which is after every word check passed; a turn
  that ends on a check (no speech, an echo, a robotic remark, a cancel) drops them unread.
- Every sentence check, the money figure guard and the tool door's refusals run on those events as
  on any round: the loop and the door are the same code.
- The local no-voice gate (`speech.worth_sending`) runs first: audio it refuses starts no round.
What changes: the model never sees the transcript in this turn (its later rounds see the same audio),
so its answer is to what IT heard; the caption shows what speech-to-text heard. How often those
differ is what the prototype measures. The first round is paid even when a check then drops it.
"""

from __future__ import annotations

import base64
import queue
import threading

from agents.runtime.model import ModelFailure, ModelRequest
from assistant.speech import wav

# Said to the model in place of the typed words.
AUDIO_NOTE = "(The person's words are in the audio above. Answer them as if they had been typed.)"

_END = object()


def audio_parts(pcm: bytes) -> list[dict]:
    """The user message's words, as audio."""
    data = base64.b64encode(wav(pcm)).decode("ascii")
    return [{"inlineData": {"mimeType": "audio/wav", "data": data}}, {"text": AUDIO_NOTE}]


class AudioFirst:
    """A model client whose first `stream` is answered from a request started earlier (`start`).
    The loop's own first request must match it (contents, system, declarations), or the early one
    is dropped and the loop's is sent as usual. Later rounds pass straight through."""

    def __init__(self, inner, req: ModelRequest, spawn=None):
        self.inner = inner
        self.req = req
        self.used = False
        self._events: queue.Queue = queue.Queue()
        self._stop = threading.Event()
        self._spawn = spawn or (lambda work: threading.Thread(target=work, daemon=True).start())

    def start(self) -> None:
        def run() -> None:
            try:
                for item in self.inner.stream(self.req):
                    if self._stop.is_set():
                        break
                    self._events.put(item)
            except Exception as exc:  # noqa: BLE001 - handed to the loop, which says it in words
                self._events.put(exc)
            finally:
                self._events.put(_END)

        self._spawn(run)

    def cancel(self) -> None:
        """Nothing it said will be used: stop reading it."""
        self._stop.set()

    def stream(self, req: ModelRequest):
        if self.used:
            yield from self.inner.stream(req)
            return
        self.used = True
        same = (
            req.contents == self.req.contents
            and req.system == self.req.system
            and req.declarations == self.req.declarations
            and req.allow_tools == self.req.allow_tools
        )
        if not same:
            self.cancel()
            yield from self.inner.stream(req)
            return
        while True:
            try:
                item = self._events.get(timeout=req.timeout_s + 2.0)
            except queue.Empty:
                self.cancel()
                raise ModelFailure("the model round ran past its time limit") from None
            if item is _END:
                return
            if isinstance(item, ModelFailure):
                raise item
            if isinstance(item, Exception):
                raise ModelFailure(f"the first round failed ({type(item).__name__})")
            yield item

    def __getattr__(self, name):
        return getattr(self.inner, name)
