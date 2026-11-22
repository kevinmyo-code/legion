"""What the engine's agent turn says (engine-brain switch plan 1119, slice E3): the web door's turn
rules (`assistant/cascade.py`, lifted in slice E0) applied to the agent turn's event stream.

`SpokenTurn.run` wraps the graph's events, on the first stream (`turn.py`) and on a relay's
continuation (`relay_resume.py`), so every sentence of the reply passes the same checks the web
door's do before the limb hears it. Only the generalist's turns: the late-answer runner's
background turns are passed through as they are.

- **Each model sentence** goes through `SentenceGate.in_answer` (`sentence_gate.py`): a first
  sentence that is only filler, money after code spoke a money line or a figure no money tool
  returned, an unbacked torque figure, anything past the cap: dropped; a claim of a change no write
  backs yet: held to the end of the turn. One rule more than the web door (inventory row 10, the
  phone's `TrailingFiller`): a filler sentence AFTER the answer has begun is dropped too.
- **A line code says** (a timeout, a refusal, the loop's failure words: `finish_node` says them
  once the turn's status is set) is never checked: it goes out as it is.
- **At the end of the turn** (`done`, never at a pause on a limb): the held claims are decided
  (`SentenceGate.held`), then one closing line (`closing.py`); a turn that said nothing says so.
- **What is stored is what was said**: `done.final_text` and the turn's history carry the sentences
  the limb was sent (`as_heard`), so `heard_until` cuts what he heard. Outside text keeps its stub.
- **Across a pause on a limb** the turn's speech so far rides in `client_facts["speech"]`
  (counts always; sentence text only while no outside text is in the turn: that dict is not
  redacted in a checkpoint, and a turn holding outside text never pauses).
"""

from __future__ import annotations

from collections.abc import Generator
from dataclasses import dataclass, field

from agents.legion import carry, closing, done_lines
from agents.legion import sentence_gate as gates
from agents.legion.ask_again import ask_again, asks_again
from agents.legion.speech_rules import backed_cents, backed_torque, is_stop_utterance
from agents.legion.spelled_money import states_unbacked_spelled
from agents.legion.trailing_filler import is_wait_line
from agents.runtime import events as ev
from agents.runtime.budget import over
from agents.runtime.loop import AWAITING_LIMB, NO_ANSWER_WORDS
from agents.runtime.model import Usage
from assistant import speech_tags
from garage import topic as garage_topic

# The web door's cap (`assistant.cascade.MAX_SPOKEN_SENTENCES`), copied: the web module is never
# imported from here.
MAX_SPOKEN_SENTENCES = 12
SPEECH_KEY = "speech"
# The spec whose turns are spoken by these rules; the late-answer runner's `background` is not.
SPOKEN_SPEC = "generalist"


@dataclass(frozen=True)
class TurnCall:
    """One call of the stream, as the speech rules read it (`LegionGateway.calls`): the result's
    text and plain data (`ToolResult.structured`, the money figures' source), never sent on."""

    name: str
    ok: bool
    text: str
    writes: bool
    data: dict | None = None
    # Code's own sentences for this result (`speech_rules._verbatim`), said as they are; whether
    # they are the turn's whole answer; what the history keeps instead of them; what the model got.
    ends_turn: bool = False
    verbatim: tuple[str, ...] | None = None
    replay: str | None = None
    response: dict | None = None
    call_id: str = ""


@dataclass
class _Memory:
    """The turn's speech so far, kept across a pause on a limb."""

    said: list[str] = field(default_factory=list)
    # What the history keeps of each said sentence: itself, or a code line's replay marker.
    heard: list[str] = field(default_factory=list)
    held: list[list[str]] = field(default_factory=list)
    filler_dropped: int = 0
    trailing_dropped: int = 0
    money_dropped: int = 0
    torque_dropped: int = 0
    no_answer: bool = False
    verbatim_said: bool = False
    backed: list[int] | None = None
    end: bool = False
    # The writes that went through (`tool.write_fact`), for the done line.
    facts: list[dict] = field(default_factory=list)

    def to_json(self, with_words: bool) -> dict:
        out = {
            "filler_dropped": self.filler_dropped,
            "trailing_dropped": self.trailing_dropped,
            "money_dropped": self.money_dropped,
            "torque_dropped": self.torque_dropped,
            "no_answer": self.no_answer,
            "verbatim_said": self.verbatim_said,
            "backed": self.backed,
            "end": self.end,
        }
        if with_words:
            out.update(
                said=list(self.said),
                heard=list(self.heard),
                held=[list(h) for h in self.held],
                facts=list(self.facts),
            )
        return out

    @classmethod
    def from_json(cls, raw: dict | None) -> _Memory:
        raw = raw or {}
        return cls(
            said=list(raw.get("said") or []),
            heard=list(raw.get("heard") or []),
            held=[list(h) for h in raw.get("held") or []],
            filler_dropped=int(raw.get("filler_dropped") or 0),
            trailing_dropped=int(raw.get("trailing_dropped") or 0),
            money_dropped=int(raw.get("money_dropped") or 0),
            torque_dropped=int(raw.get("torque_dropped") or 0),
            no_answer=bool(raw.get("no_answer")),
            verbatim_said=bool(raw.get("verbatim_said")),
            backed=list(raw["backed"]) if raw.get("backed") is not None else None,
            end=bool(raw.get("end")),
            facts=list(raw.get("facts") or []),
        )


class SpokenTurn:
    def __init__(
        self, state, gateway, *, on: bool, torque_topic=None, about_front_end=False, again=None
    ):
        self.state = state
        self.gateway = gateway
        self.on = on
        # The ask-again round (`AskAgain`), or None: never asked.
        self.again = again
        self.memory = _Memory.from_json(state.client_facts.get(SPEECH_KEY))
        self.gate = gates.SentenceGate(
            backed=self._backed,
            torque=lambda: backed_torque(self.gateway.calls, torque_topic, about_front_end),
            actions=lambda: self.state.actions,
        )

    # ---- inputs ---------------------------------------------------------------------------

    def _backed(self) -> set[int] | None:
        """The money figures this turn's tools returned, this stream's and any before a pause."""
        now = backed_cents(self.gateway.calls)
        before = self.memory.backed
        if now is None and before is None:
            return None
        return (now or set()) | set(before or ())

    def _keep(self) -> None:
        self.memory.end = self.memory.end or bool(getattr(self.gateway, "end_requested", False))
        backed = backed_cents(self.gateway.calls)
        if backed is not None:
            self.memory.backed = sorted(backed | set(self.memory.backed or ()))
        self.state.client_facts[SPEECH_KEY] = self.memory.to_json(not self.state.outside)

    # ---- the stream -----------------------------------------------------------------------

    def run(self, events) -> Generator[ev.Event, None, object]:
        """`events`: the graph's run (a generator returning the final state). Yields what the
        limb is sent; returns that state."""
        try:
            while True:
                try:
                    event = next(events)
                except StopIteration as stop:
                    return stop.value
                if not self.on:
                    yield event
                elif event.type == "sentence":
                    yield from self._sentence(event)
                elif event.type == "tool":
                    if event.data.get("ok") and event.data.get("write_fact"):
                        self.memory.facts.append(dict(event.data["write_fact"]))
                    yield event
                    yield from self._verbatim(event)
                elif event.type == "done" and event.data.get("status") != AWAITING_LIMB:
                    if event.data.get("status") != "cancelled":
                        # After a cancel nothing more is said (the web door's rule); the phone
                        # says its own "Done:" line under a stop.
                        yield from self._end()
                    yield self._done(event)
                else:
                    yield event
                if self.on:
                    self._keep()
        finally:
            # A hang-up closes this generator: the graph's own is closed with it, at once.
            events.close()

    def _say(self, text: str, tagged: str | None = None, replay: str | None = None) -> ev.Event:
        self.memory.said.append(text)
        self.memory.heard.append(text if replay is None else replay)
        return ev.sentence(tagged if tagged is not None else text)

    def _verbatim(self, event):
        """Code's own sentences for a `VERBATIM_TOOLS` result, said as they are (ADR 0094): his
        whole answer (`ends_turn`) always; a money line once per turn, and only it holds back the
        model's later money sentences."""
        call = next(
            (c for c in reversed(self.gateway.calls) if c.name == event.data.get("name")), None
        )
        if call is None or not call.ok or not call.verbatim:
            return
        m = self.memory
        if not (call.ends_turn or not m.verbatim_said):
            return
        if not call.ends_turn:
            m.verbatim_said = True
        for i, line in enumerate(call.verbatim):
            marker = None
            if call.replay is not None:
                marker = call.replay if i == 0 else ""
            yield self._say(line, replay=marker)

    def _sentence(self, event):
        tagged = event.data.get("text", "")
        if self.state.status not in ("running", AWAITING_LIMB):
            # Code's own line, said by `finish_node` once the turn's status is set. A relay's
            # continuation starts from the paused state (`awaiting_limb`): its model sentences
            # come before the status is set again, and are checked like any.
            yield self._say(tagged)
            return
        text = speech_tags.untagged(tagged)
        m = self.memory
        verdict = self.gate.in_answer(
            text,
            sentences=len(m.said),
            verbatim_said=m.verbatim_said,
            max_spoken=MAX_SPOKEN_SENTENCES,
        )
        if verdict == gates.SAY and states_unbacked_spelled(text, self._backed()):
            m.money_dropped += 1
        elif verdict == gates.SAY and m.said and is_wait_line(text):
            # The phone's `TrailingFiller`: once the answer has begun, a filler line is not said.
            m.trailing_dropped += 1
        elif verdict == gates.SAY:
            yield self._say(text, tagged)
        elif verdict == gates.NO_ANSWER:
            m.no_answer = True
        elif verdict == gates.FILLER:
            m.filler_dropped += 1
        elif verdict == gates.MONEY:
            m.money_dropped += 1
        elif verdict == gates.TORQUE:
            m.torque_dropped += 1
        elif verdict == gates.HOLD:
            m.held.append([text, tagged])

    # ---- the end of the turn --------------------------------------------------------------

    def _end(self):
        """What code says once the model is done (`assistant.cascade._answer`'s closing half)."""
        m = self.memory
        state = self.state
        if self.again is not None and asks_again(
            failed=state.status != "done",
            status=state.status,
            sentences=len(m.said),
            filler_only=bool(m.filler_dropped or m.no_answer),
            tools_ran=bool(state.actions),
            ending=self._ending(),
        ):
            # Spec 23: the tools ran and the answer was only filler, or nothing: asked once more,
            # tool-free, with the phone's note; whatever it says is the answer.
            for tagged in self.again(state):
                text = speech_tags.untagged(tagged)
                verdict = self.gate.in_ask_again(text, verbatim_said=m.verbatim_said)
                if verdict == gates.SAY and states_unbacked_spelled(text, self._backed()):
                    verdict = gates.MONEY
                if verdict == gates.MONEY:
                    m.money_dropped += 1
                elif verdict == gates.TORQUE:
                    m.torque_dropped += 1
                elif verdict == gates.HOLD:
                    m.held.append([text, tagged])
                elif verdict == gates.SAY:
                    yield self._say(text, tagged)
        if m.no_answer and not m.said and state.status == "done":
            # The runtime's own line for a turn with no words, held until now.
            yield self._say(NO_ANSWER_WORDS)
        unbacked = 0
        for text, tagged in m.held:
            verdict = self.gate.held(text)
            if verdict == gates.SAY and states_unbacked_spelled(text, self._backed()):
                verdict = gates.MONEY
            if verdict == gates.MONEY:
                m.money_dropped += 1
                unbacked += 1
            elif verdict == gates.TORQUE:
                m.torque_dropped += 1
                unbacked += 1
            elif verdict == gates.SAY:
                yield self._say(text, tagged)
            else:
                unbacked += 1
        m.held = []
        line = closing.closing_line(
            self.gateway.calls,
            refused=closing.guard_refused(self.gateway.calls),
            unbacked=unbacked,
            wrote=state.writes_ran,
            # A turn tainted only by the carry did not read outside text itself: "this turn read
            # text from the web" would be false (ADR 0090 amended 2026-11-21).
            tainted=lambda: state.tainted and not carry.only_carried(state.taint),
            carried=closing.refused_by_carry(self.gateway.calls),
            # "Ask me again" only when the exception's mark was written (re-check R1).
            promise=carry.REFUSED_HERE in state.taint,
        )
        if line is not None:
            yield self._say(line)
        else:
            # Plan 1119 section 2: a write the reply never named is said by code.
            done = done_lines.done_line(m.facts, " ".join(m.said))
            if done is not None:
                yield self._say(done)
        unsure = done_lines.unconfirmed_line(
            sum(1 for a in state.actions if a.get("writes") and a.get("outcome") == "timed_out")
        )
        if unsure is not None:
            yield self._say(unsure)
        if not m.said and state.status == "done" and not self._ending():
            yield self._say(closing.nothing_said_line(state.writes_ran))

    def _done(self, event) -> ev.Event:
        said = " ".join(self.memory.said).strip()
        heard = " ".join(h for h in self.memory.heard if h).strip()
        self.state.final_text = said
        messages = answered(self.state.messages, self.gateway.calls)
        self.state.messages = as_heard(
            messages, int(self.state.client_facts.get("history_from", 0)), heard
        )
        data = dict(event.data)
        if not self.state.outside:
            data["final_text"] = said
        if self._ending() or is_stop_utterance(self.state.transcript):
            # The limb closes the conversation once this answer is said (`done.end`, the web
            # door's `end`): the model called `end_conversation`, or his words were only a stop
            # phrase. The phone decides its own stop words before sending; this covers the rest.
            data["end"] = True
        return ev.Event("done", data)

    def _ending(self) -> bool:
        return self.memory.end or bool(getattr(self.gateway, "end_requested", False))


NOT_RUN_RESPONSE = {"success": False, "message": "Not run: the turn ended before this call."}


def answered(messages: list[dict], calls) -> list[dict]:
    """`messages` with the last model turn's calls answered, when a round ended on code's own
    answer (`ends_turn`: the loop asks no further round, so it sent no responses): each call gets
    the response the model would have had. Unchanged otherwise."""
    if not messages or messages[-1].get("role") != "model":
        return messages
    asked = [p["functionCall"] for p in messages[-1].get("parts") or [] if "functionCall" in p]
    if not asked:
        return messages
    pool = list(calls)[-len(asked) :]
    parts = []
    for fc in asked:
        found = next(
            (
                c
                for c in pool
                if c.name == fc.get("name") and (not fc.get("id") or c.call_id == fc.get("id"))
            ),
            None,
        )
        if found is not None:
            pool.remove(found)
        response = (
            found.response if found is not None and found.response else dict(NOT_RUN_RESPONSE)
        )
        part = {"name": fc.get("name"), "response": response}
        if fc.get("id"):
            part["id"] = fc["id"]
        parts.append({"functionResponse": part})
    return [*messages, {"role": "user", "parts": parts}]


def as_heard(messages: list[dict], start: int, said: str) -> list[dict]:
    """`messages` with this turn's model words (from `start` on) made what was said: unchanged when
    they already are; else every text part of this turn's model turns is taken out (calls and
    their order stay), and one model turn with the said words ends the turn."""
    mine = messages[start:]
    model_text = " ".join(
        p["text"].strip()
        for c in mine
        if c.get("role") == "model"
        for p in c.get("parts") or []
        if isinstance(p.get("text"), str) and p["text"].strip()
    )
    if " ".join(model_text.split()) == " ".join(said.split()):
        return messages
    kept = []
    for content in mine:
        if content.get("role") == "model":
            parts = [p for p in content.get("parts") or [] if "text" not in p]
            if parts:
                kept.append({**content, "parts": parts})
        else:
            kept.append(content)
    if said:
        kept.append({"role": "model", "parts": [{"text": said}]})
    return [*messages[:start], *kept]


class AskAgain:
    """The ask-again round on the agent turn (`ask_again.ask_again`), metered like any round: an
    `agents_model_call` and `llm_usage` row (`hooks.on_model_call`) and the turn's budget. Not
    asked once the turn was cancelled, ran out of time or out of budget."""

    def __init__(self, ctx, spec):
        self.ctx = ctx
        self.spec = spec

    def __call__(self, state) -> list[str]:
        hooks, limits = self.ctx.hooks, self.spec.limits
        started = hooks.started if hooks.started is not None else hooks.clock()
        if (
            hooks.cancelled()
            or hooks.clock() - started > limits.turn_timeout_s
            or over(limits, state.budget)
        ):
            return []
        rounds: list[dict] = []
        t0 = hooks.clock()
        said = ask_again(
            self.ctx.model,
            self.spec.model,
            self.ctx.system_for(self.spec, state),
            state.messages,
            self.ctx.gateway.declarations(self.spec),
            limits.round_timeout_s,
            rounds,
        )
        row = rounds[-1]
        usage = Usage(
            row["prompt_tokens"],
            row["cached_tokens"],
            row["output_tokens"],
            row["prompt_tokens"] + row["output_tokens"],
        )
        cost = hooks.cost_of(usage)
        state.budget.rounds += 1
        state.budget.prompt_tokens += usage.prompt_tokens
        state.budget.cached_tokens += usage.cached_tokens
        state.budget.output_tokens += usage.output_tokens
        state.budget.cost_micro_usd += cost
        hooks.on_model_call(
            {
                "round": state.budget.rounds - 1,
                "model": self.spec.model,
                **row,
                "first_token_ms": None,
                "total_ms": int((hooks.clock() - t0) * 1000),
                "cost_micro_usd": cost,
                "finish_reason": "ask_again",
            }
        )
        return said


def spoken_turn(state, ctx, agent: str) -> SpokenTurn:
    """The turn's speech, over the graph context `ctx` (`turn.context`). The torque guard is on
    exactly when `show_part` is offered to this turn, as on the web door."""
    on = agent == SPOKEN_SPEC
    spec = ctx.spec(agent) if on else None
    offered = on and "show_part" in spec.tools and "show_part" in ctx.gateway.tools()
    words = state.transcript or ""
    return SpokenTurn(
        state,
        ctx.gateway,
        on=on,
        torque_topic=garage_topic.SENTENCE if offered else None,
        about_front_end=offered and garage_topic.about_front_end(words, _last_reply(state)),
        again=AskAgain(ctx, spec) if on else None,
    )


def _last_reply(state) -> str:
    start = int(state.client_facts.get("history_from", 0))
    for content in reversed(state.messages[:start]):
        if content.get("role") == "model":
            return " ".join(p.get("text", "") for p in content.get("parts") or []).strip()
    return ""
