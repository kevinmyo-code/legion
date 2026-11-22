"""Draft-start on the engine's voice route (front/back split report 1120, ranked change 7;
LiveKit's `preemptive_generation`). DARK: behind `LEGION_AGENTS_PREEMPT`, on top of the streamed
door (`LEGION_AGENTS_STREAM_STT`, `LEGION_AGENTS_TURN`).

While he is still speaking, the streaming speech-to-text keeps a running guess of his words
(`live_stt.TranscribeSession.interim`). When that guess holds still for `STABLE_S`, the model's
FIRST round is asked on it at once, on its own thread, and its answer is kept in memory. When the
final words come, the turn runs as always (`turn.run_turn`); if they are the draft's words
(`normalise`d) the turn's first round is answered from memory (`PrimedModel`), and the time the
model took is saved. If not, the draft is dropped (cancelled) and the turn asks the model again.

**What a draft may do: nothing but ask the model.** The rules, and why:
- **No tool runs on a draft.** Only the first model round is kept, events in memory. A tool call
  in it is the model's plan; it runs, or not, only after the final words matched, when the turn's
  loop reads that round, through the same gateway, guards (ADR 0090, ADR 0098), test-mode refusal
  and the phone's hold as any turn. So a write, a procedure, a relayed phone call, a web search
  or anything else that acts or leaves the engine is never started on words he did not finish.
  Chosen over "run read-only tools on a draft": a read that leaves the engine (a web search) is
  still his words leaving it, and a read can taint the turn; no tool at all is the simpler line.
- **Nothing is stored for a draft before the final words are known**: no turn row, no history,
  no tool row, no taint mark, no carry spent. The draft's request is built from reads only
  (`draft_request`), and its answer lives in this request's memory.
- **The answer is used only when the turn would have asked the very same question**:
  `PrimedModel` compares the turn's first request with the draft's, field by field (model,
  system prompt, every content, the declarations), his words aside (they match after
  `normalise`). Any difference (a turn of the conversation landed meanwhile, the clock's minute
  moved, a pause closed) and the draft is not used. So the turn's answer is never built on
  anything the turn itself would not have sent.
- **No draft** when the late-answer path is on (its intercept may answer by code), for a barge-in
  turn (`heard_until`: the earlier answer is cut before the history is read), for a stop phrase or
  a crisis phrase (those never reach the model), past `MAX_DRAFTS_PER_UTTERANCE`, or once the
  conversation has wasted `MAX_WASTED_PER_CONVERSATION` drafts. Never for a recruiter (the door
  refuses one) or a member over the cap (the door refuses before the upload is read).
- **Wasted drafts are paid for and counted.** Each draft not used is written, once the turn is
  over, as an `agents_model_call` row (`node="preempt"`, `finish_reason="preempt_wasted"`, usage and
  cost only: no words) on the turn, and an `llm_usage` row (`FEATURE`), so the member's daily
  allowance sees it. The cap per conversation is counted from those rows (one query when the
  upload starts), so it holds across instances. An utterance that never became a turn (noise, a
  crisis, a hang-up) writes the `llm_usage` row only.
"""

from __future__ import annotations

import json
import logging
import queue
import re
import threading
import time
import unicodedata
from collections.abc import Callable, Iterator
from dataclasses import replace

from django.utils import timezone as dj_timezone

from agents.legion import flags, late
from agents.legion.gateway import LegionGateway
from agents.legion.specs import spec as spec_named
from agents.legion.speech_rules import is_stop_utterance
from agents.legion.turn import cost_of, existing_conversation, system_for, user_turn
from agents.models import AgentModelCall, AgentTurn
from agents.runtime.model import ModelFailure, ModelRequest, Usage
from agents.voice import crisis
from household.models import is_recruiter
from llm import client as llm_client

log = logging.getLogger(__name__)

AGENT = "generalist"
# How long the running guess must hold still before a draft starts (the phone drafts at 300 ms of
# quiet).
STABLE_S = 0.3
MAX_DRAFTS_PER_UTTERANCE = 3
MAX_WASTED_PER_CONVERSATION = 6
NODE = "preempt"
WASTED = "preempt_wasted"
FEATURE = "agent_turn_preempt"
# How long the turn's end waits for a dropped draft to stop, so its usage is known.
JOIN_S = 2.0
_END = object()


def enabled() -> bool:
    return flags.preempt_enabled()


def normalise(text: str) -> str:
    """His words for the match: case, punctuation and spacing do not count."""
    t = unicodedata.normalize("NFKC", text or "").lower().replace("’", "'")
    return " ".join(re.sub(r"[^\w\s']", " ", t).split())


def draft_request(request, conversation, transcript: str, now, facts: dict) -> ModelRequest:
    """The first request the turn would send for `transcript` (`run_turn` -> the agent node ->
    `run_loop`), built from reads only."""
    spec = spec_named(AGENT)
    history: list[dict] = []
    if conversation is not None:
        for earlier in conversation.turns.order_by("seq").values_list("history", flat=True):
            history.extend(earlier or [])
    return ModelRequest(
        model=spec.model,
        system=system_for(spec, None),
        contents=[*history, user_turn(request, now, facts, transcript)],
        declarations=LegionGateway(request).declarations(spec),
        allow_tools=spec.limits.max_rounds > 1,
        timeout_s=spec.limits.round_timeout_s,
    )


class Draft:
    """One model round on a draft, on its own thread; its events kept in memory, in order."""

    def __init__(self, text: str, req: ModelRequest, model, now):
        self.text = text
        self.key = normalise(text)
        self.req = req
        self.now = now
        self.used = False
        self.items: list = []
        self.usage: Usage | None = None
        self.error: Exception | None = None
        self.started = time.monotonic()
        self.ms = 0
        self.done = threading.Event()
        self.cancelled = threading.Event()
        self._events: queue.Queue = queue.Queue()
        self._thread = threading.Thread(target=self._run, args=(model,), daemon=True)

    def start(self) -> None:
        self._thread.start()

    def _run(self, model) -> None:
        stream = None
        try:
            stream = model.stream(self.req)
            for item in stream:
                if isinstance(item, Usage):
                    self.usage = item
                self.items.append(item)
                self._events.put(item)
                if self.cancelled.is_set():
                    break
        except Exception as exc:  # noqa: BLE001 - a failed draft is only a draft not used
            self.error = exc
        finally:
            close = getattr(stream, "close", None)
            if close is not None:
                try:
                    close()
                except Exception:  # noqa: BLE001
                    pass
            self.ms = int((time.monotonic() - self.started) * 1000)
            self.done.set()
            self._events.put(_END)

    def matches(self, req: ModelRequest, final: str) -> bool:
        """Whether `req` is the draft's request with his final words in place of the draft's."""
        if normalise(final) != self.key:
            return False
        contents = [dict(c) for c in self.req.contents]
        last = dict(contents[-1])
        last["parts"] = [*last["parts"][:-1], {"text": final}]
        contents[-1] = last
        mine = replace(self.req, contents=contents, timeout_s=req.timeout_s)
        return mine == req

    def replay(self, timeout_s: float) -> Iterator:
        """The draft's events as they come; `ModelFailure` if it failed or went quiet."""
        while True:
            try:
                item = self._events.get(timeout=timeout_s)
            except queue.Empty:
                raise ModelFailure("the draft round ran past its time limit") from None
            if item is _END:
                break
            yield item
        if self.error is not None:
            raise ModelFailure("the draft round failed")


class PrimedModel:
    """The turn's model, whose FIRST request is answered from the draft when it is the draft's
    own request (`Draft.matches`); everything else goes to `model`."""

    def __init__(self, model, draft: Draft, final: str):
        self.model = model
        self.draft = draft
        self.final = final
        self._first = True

    def stream(self, req: ModelRequest) -> Iterator:
        if not self._first:
            yield from self.model.stream(req)
            return
        self._first = False
        draft = self.draft
        if not draft.matches(req, self.final):
            draft.cancelled.set()
            yield from self.model.stream(req)
            return
        draft.used = True
        said = False
        try:
            for item in draft.replay(req.timeout_s):
                said = True
                yield item
        except ModelFailure:
            if said:
                raise
            # Failed before a word: a draft not used after all; the turn asks the model itself.
            draft.used = False
            yield from self.model.stream(req)


class Drafter:
    """The drafts of one streamed utterance: started from `heard`, matched by `primed`, paid for
    by `close`."""

    def __init__(
        self,
        request,
        *,
        conversation,
        facts: dict,
        model_for: Callable[[], object],
        wasted_before: int = 0,
        clock: Callable[[], float] = time.monotonic,
    ):
        self.request = request
        self.conversation = conversation
        self.facts = facts
        self.model_for = model_for
        self.wasted_before = wasted_before
        self.clock = clock
        self.drafts: list[Draft] = []
        self.current: Draft | None = None
        self.turn_id = None
        self._seen = ""
        self._seen_at = 0.0
        self._off = False
        self._closed = False

    def heard(self, guess: str) -> None:
        """The running guess of his words, after each piece of audio. Never raises."""
        try:
            self._heard(guess)
        except Exception:  # noqa: BLE001 - a draft is never worth the turn
            log.warning("agents_preempt: drafting stopped", exc_info=True)
            self._off = True

    def _heard(self, guess: str) -> None:
        key = normalise(guess)
        now = self.clock()
        if key != self._seen:
            self._seen, self._seen_at = key, now
            if self.current is not None and self.current.key != key:
                self.current.cancelled.set()
                self.current = None
            return
        if self._off or not key or self.current is not None or now - self._seen_at < STABLE_S:
            return
        if not self._may_draft(guess):
            return
        when = dj_timezone.now()
        req = draft_request(self.request, self.conversation, guess, when, self.facts)
        draft = Draft(guess, req, self.model_for(), when)
        self.drafts.append(draft)
        self.current = draft
        draft.start()

    def _may_draft(self, guess: str) -> bool:
        if len(self.drafts) >= MAX_DRAFTS_PER_UTTERANCE:
            return False
        if self.wasted_before + len(self.drafts) >= MAX_WASTED_PER_CONVERSATION:
            return False
        return not (crisis.detect(guess) or is_stop_utterance(guess))

    def primed(self, final: str, model):
        """`(model, now)` for the turn: the draft-primed model and the draft's clock when the
        final words are the current draft's; else `(model, None)`."""
        draft = self.current
        if draft is None or draft.key != normalise(final):
            if draft is not None:
                draft.cancelled.set()
            return model, None
        return PrimedModel(model, draft, final), draft.now

    def watch(self, events):
        """`events` (a turn's) passed through, noting the turn's id for `close`."""
        try:
            while True:
                try:
                    event = next(events)
                except StopIteration as stop:
                    return stop.value
                if event.type == "turn" and self.turn_id is None:
                    self.turn_id = event.data.get("turn_id")
                yield event
        finally:
            events.close()

    def close(self) -> None:
        """Once the utterance is over: every draft not used is stopped, metered and logged.
        Idempotent."""
        if self._closed:
            return
        self._closed = True
        wasted = [d for d in self.drafts if not d.used]
        for draft in wasted:
            draft.cancelled.set()
        for draft in wasted:
            draft.done.wait(JOIN_S)
        turn = AgentTurn.objects.filter(pk=self.turn_id).first() if self.turn_id else None
        for draft in wasted:
            usage = draft.usage or _estimate(draft)
            cost = cost_of(usage)
            if turn is not None:
                AgentModelCall.objects.create(
                    turn=turn,
                    node=NODE,
                    agent=AGENT,
                    model=draft.req.model,
                    round=-1,
                    prompt_tokens=usage.prompt_tokens,
                    cached_tokens=usage.cached_tokens,
                    output_tokens=usage.output_tokens,
                    first_token_ms=None,
                    total_ms=draft.ms,
                    cost_micro_usd=cost,
                    finish_reason=WASTED,
                    household_id=turn.household_id,
                    owner_user_id=turn.owner_user_id,
                )
            llm_client.record(
                household=self.request.user.household,
                feature=FEATURE,
                model=draft.req.model,
                user=self.request.user,
                usage={
                    "prompt_tokens": usage.prompt_tokens,
                    "output_tokens": usage.output_tokens,
                    "total_tokens": usage.prompt_tokens + usage.output_tokens,
                },
                cost_estimate_micro_usd=cost,
            )
        log.info(
            "agents_preempt drafts=%d used=%d wasted=%d wasted_before=%d",
            len(self.drafts),
            sum(1 for d in self.drafts if d.used),
            len(wasted),
            self.wasted_before,
        )


def _estimate(draft: Draft) -> Usage:
    """A draft stopped before its usage came: its request at about four characters a token, its
    answer so far likewise. An estimate."""
    sent = len(draft.req.system) + len(json.dumps(draft.req.contents, default=str))
    sent += len(json.dumps(draft.req.declarations, default=str))
    said = sum(len(str(getattr(i, "text", "") or "")) for i in draft.items)
    return Usage(sent // 4 + 1, 0, said // 4, sent // 4 + 1 + said // 4)


def drafter_for(request, data: dict, options: dict, model_for) -> Drafter | None:
    """A `Drafter` for this streamed utterance, or None when no draft may start (module doc)."""
    if not enabled() or late.enabled() or is_recruiter(request.user):
        return None
    facts = options.get("client_facts") or {}
    if facts.get("heard_until"):
        return None
    conversation = existing_conversation(request, data.get("conversation_id"), data["client"])
    wasted = 0
    if conversation is not None:
        wasted = AgentModelCall.objects.filter(turn__conversation=conversation, node=NODE).count()
        if wasted >= MAX_WASTED_PER_CONVERSATION:
            return None
    return Drafter(
        request,
        conversation=conversation,
        facts=facts,
        model_for=model_for,
        wasted_before=wasted,
    )
