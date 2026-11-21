"""The tool gateway: every tool call of an agent turn, through the guards, as the member.

- **One request per stream.** Every engine tool runs through `engine_mcp.tools.run_engine_tool`
  with the request of the stream it runs in: the write-scope fence, the table fence, the schema,
  and the handler under the member's `visible()` (ADR 0045, 0052), exactly as `/mcp` and the web
  door run them. `assistant.read_through`'s per-request flag is a second fence behind the taint
  in the turn state; a relay's continuation is a new request, so the flag is armed again there
  from the turn's taint before any engine tool runs.
- **Declarations** are the web assistant's own (`assistant.surface.tools_for(user)`, so
  `run_procedure` and `show_diagram` list only what this member may use), narrowed to the spec's
  allowlist, plus the read twins.
- **Taint before the tool.** A third-party tool (`read_through.THIRD_PARTY_TOOLS`) first adds
  itself to the turn's taint and the store saves it; if the save fails the tool does not run and
  the turn is refused writes for good (fail closed, ADR 0090).
- **Every call is recorded** (`AgentToolCall`): redacted arguments, trimmed result, outcome,
  guard, time. Never a mail tool's content (`redact.py`), and never outside text, by class: a
  third-party tool's result (or a procedure's that read one) is stubbed, it marks
  `TurnState.outside`, and every later call's arguments are stubbed too.
- **Limb tools** (the relay, slice E2, `relay.py`): the turn's own limb's manifest tools are
  offered beside the engine's; a call to one is checked by `relay.decide` and comes back as
  `ToolOutcome(relay=request)`: nothing ran, the loop pauses. Its trace row is written when the
  limb's result arrives (`relay_turn.py`).
"""

from __future__ import annotations

import time

from agents.legion import guards, hold, redact, relay
from agents.legion.client_facts import TEST_MODE_WORDS
from agents.legion.end_conversation import END_DECLARATION, END_INSTRUCTION, END_TOOL
from agents.legion.speech_rules import _verbatim
from agents.legion.spoken import SPOKEN_SPEC, TurnCall
from agents.legion.twins import TWINS
from agents.runtime.tools import RELAY_ALONE, RELAY_BATCH, ToolCall, ToolOutcome
from assistant import live, read_through
from assistant.surface import tools_for, web_description
from engine_mcp.models import Outcome
from engine_mcp.tools import ToolResult, run_engine_tool
from headunit_signin import scope as headunit_scope

SLOW_TOOLS = frozenset(
    {
        "web_search",
        "brief_latest",
        "run_procedure",
        "check_conditions",
        "github_status",
        "vehicle_recalls",
        "legion_docs",
    }
)

# A K1 device action's outcome in the turn's `tool` event: it runs after the reply.
AFTER = "after_reply"
# The one engine tool whose own steps may read third-party text (`procedures/runner.py`).
PROCEDURE_TOOL = "run_procedure"

_OUTCOMES = {
    Outcome.OK: "ok",
    Outcome.REFUSED: "refused",
    Outcome.FAILED: "failed",
}


def _rest_schema(schema):
    """REST's OpenAPI-subset `Schema` takes one `type`: a JSON Schema type list such as
    ["string", "null"] becomes its non-null type plus `nullable` (REST answered HTTP 400 on the
    list, measured 2026-11-17)."""
    if isinstance(schema, list):
        return [_rest_schema(s) for s in schema]
    if not isinstance(schema, dict):
        return schema
    out = {k: _rest_schema(v) for k, v in schema.items()}
    kind = out.get("type")
    if isinstance(kind, list):
        real = [t for t in kind if t != "null"]
        out["type"] = real[0] if real else "string"
        if "null" in kind:
            out["nullable"] = True
    return out


def rest_declarations(tools) -> list[dict]:
    """The web door's declarations (`assistant.live.function_declarations`) for the REST
    `streamGenerateContent` request: less `behavior` (a Live-only field; the phone's cascade sends
    none) and with single-type schemas (`_rest_schema`)."""
    out = []
    for decl in live.function_declarations(tools, web_description):
        decl = dict(decl)
        decl.pop("behavior", None)
        decl["parameters"] = _rest_schema(decl.get("parameters") or {})
        out.append(decl)
    return out


def model_response(ok: bool, text: str) -> dict:
    """The web door's shape (`assistant.views._forward`), so both brains read results alike."""
    return {"success": ok, "message": text}


class LegionGateway:
    def __init__(self, request, recorder=None, observe=None, limit=None, turn_hold=None):
        self.request = request
        # The phone's go (`hold.py`): nothing that acts runs before it. None: never held.
        self.hold = turn_hold
        # A caller's own cap on calls (the late-answer runner's, `late.py`): (state, call) ->
        # refusal words, or None to go on. A refusal is recorded with guard `budget`.
        self.limit = limit
        self.user = request.user
        self.recorder = recorder
        # The eval runner's ear: every call's name, arguments, outcome and full result text.
        self.observe = observe
        self._tools = None
        self._limb = None
        self._limb_tools = None
        # Every call of this stream as the speech rules read it (`spoken.py`): name, success, the
        # result's text and plain data. Never sent anywhere; gone with the stream.
        self.calls: list[TurnCall] = []
        # `end_conversation` was called: the limb closes the conversation after the sign-off.
        self.end_requested = False

    def limb_tools(self) -> dict[str, relay.LimbTool]:
        """The relayable tools of the limb that made this turn (none without one, no query)."""
        if self._limb_tools is None:
            self._limb = relay.limb_of(self.request)
            self._limb_tools = relay.tools_of(self._limb, set(self.tools()))
        return self._limb_tools

    def relay_kind(self, call: ToolCall) -> str | None:
        tool = self.limb_tools().get(call.name)
        if tool is None:
            return None
        return RELAY_ALONE if relay.reads_outside(tool, call.args) else RELAY_BATCH

    def working_kind(self, call: ToolCall) -> str:
        """The class of a call about to run, for the limb's filler: `relay` (a limb tool),
        `slow` (`SLOW_TOOLS`), `write`, else `local`."""
        tool = self.tools().get(call.name)
        if tool is None and call.name in self.limb_tools():
            return "relay"
        if call.name in SLOW_TOOLS:
            return "slow"
        return "write" if tool is not None and tool.writes else "local"

    def _acts(self, tool, name: str) -> bool:
        """What waits for the phone's go: a write, a procedure (its steps may write;
        `run_procedure` is `writes=False` on purpose), and a read that sends his words out of the
        engine (`relay.leaves_engine`: a web search, a weather or recall service, GitHub)."""
        if tool is None:
            return False
        return tool.writes or name == PROCEDURE_TOOL or relay.leaves_engine(name)

    def _writes(self, tool, name: str) -> bool:
        return bool(tool is not None and (tool.writes or name == PROCEDURE_TOOL))

    def _held(self, state, call, tool, started, where="engine") -> ToolOutcome | None:
        """None when this acting call may run now; else its refusal (the phone took the turn,
        or never said go)."""
        if self.hold is None or self.hold.peek() == hold.GO or self.hold.wait() == hold.GO:
            return None
        words = hold.HELD_WORDS.format(name=call.name)
        return self._done(state, call, tool, False, words, "refused", "cancelled", started, where)

    def tools(self) -> dict:
        if self._tools is None:
            by_name = {t.name: t for t in tools_for(self.user)}
            by_name.update(TWINS)
            if headunit_scope.is_headunit(self.request):
                # T3b: a turn made with a head unit's token is offered, and may call, only the
                # unit's own tools; any other name is then "not one of this agent's tools".
                by_name = {n: t for n, t in by_name.items() if n in headunit_scope.TOOLS}
            self._tools = by_name
        return self._tools

    def declarations(self, spec) -> list[dict]:
        tools = self.tools()
        chosen = [tools[name] for name in spec.tools if name in tools]
        limb = [
            {**decl, "parameters": _rest_schema(decl["parameters"])}
            for decl in map(relay.declaration, self.limb_tools().values())
        ]
        return rest_declarations(chosen) + limb + ([END_DECLARATION] if self._ends(spec) else [])

    def _ends(self, spec) -> bool:
        """`end_conversation` (spec 18, the web door's control): offered on the spoken spec's
        turns, never the late-answer runner's and never a head unit's (it keeps to its own
        tools)."""
        return spec.name == SPOKEN_SPEC and not headunit_scope.is_headunit(self.request)

    def is_slow(self, name: str) -> bool:
        return name in SLOW_TOOLS

    def call(self, state, spec, call: ToolCall, deadline_s: float) -> ToolOutcome:
        tool = self.tools().get(call.name)
        started = time.monotonic()
        if call.name == END_TOOL and tool is None and self._ends(spec):
            # A control, not a data tool: it reaches nothing; the sign-off follows it.
            self.end_requested = True
            return self._done(state, call, None, True, END_INSTRUCTION, "ok", None, started)
        if tool is None and call.name in self.limb_tools():
            return self._relay(state, spec, call, self.limb_tools()[call.name], started)
        if headunit_scope.is_headunit(self.request):
            # T3b review B4: a head unit's turn reads at a point, and its own vehicle only.
            args, refusal = headunit_scope.tool_arguments(self.request, call.name, call.args)
            if refusal is not None:
                return self._done(state, call, tool, False, refusal, "refused", "headunit", started)
            call = ToolCall(call.id, call.name, args)
        if self.limit is not None:
            words = self.limit(state, call)
            if words is not None:
                return self._done(state, call, tool, False, words, "refused", "budget", started)
        verdict = guards.check_call(state, spec, call.name, call.args, tool, self.user)
        if not verdict.allow:
            return self._done(
                state, call, tool, False, verdict.words, "refused", verdict.guard, started
            )
        if self._acts(tool, call.name):
            if state.client_facts.get("test_mode") and self._writes(tool, call.name):
                words = TEST_MODE_WORDS.format(name=call.name)
                return self._done(
                    state, call, tool, False, words, "refused", "not_allowed", started
                )
            held = self._held(state, call, tool, started)
            if held is not None:
                return held
        if guards.needs_taint(call.name):
            state.add_taint(call.name)
            saved = self.recorder.mark_taint(state) if self.recorder is not None else True
            if not saved:
                return self._done(
                    state,
                    call,
                    tool,
                    False,
                    read_through.MARK_FAILED,
                    "refused",
                    "tainted",
                    started,
                )
        if state.tainted:
            # The request's flag is the second fence (a procedure's own steps check it, not the
            # turn's state); a relay's continuation is a new request, so it is armed again here.
            read_through.flag_request(self.request)
        result: ToolResult = run_engine_tool(self.request, tool, dict(call.args))
        # The deadline is soft (budget.py): a late tool is not interrupted; its `ms` says so.
        outcome = _OUTCOMES.get(result.outcome, "failed" if result.is_error else "ok")
        # Its result is outside text by class: a third-party tool, or a procedure run while the
        # request is flagged (a step read one, `read_through.before_tool`, or the turn had).
        outside = guards.needs_taint(call.name) or (
            call.name == PROCEDURE_TOOL and read_through.read_third_party(self.request)
        )
        fact = (
            write_fact(call.name, result.structured)
            if tool.writes and not result.is_error
            else None
        )
        # Code's own sentences (`speech_rules.VERBATIM_TOOLS`, ADR 0094): said by the speech rules
        # (`spoken.py`), never reworded by the model, which gets `for_model` when there is one.
        spoken = _verbatim(call.name, result) if not result.is_error else None
        done = self._done(
            state,
            call,
            tool,
            not result.is_error,
            result.text,
            outcome,
            None,
            started,
            outside=outside,
            fact=fact,
            data=result.structured if not result.is_error else None,
            spoken=spoken,
        )
        if outside:
            state.add_taint(call.name)
            state.add_outside(call.name)
        return done

    def _relay(self, state, spec, call, tool, started) -> ToolOutcome:
        """A limb tool's call: refused here in words, kept as a device action for after the reply
        (K1), or handed to the limb (`ToolOutcome.relay`; the loop pauses)."""
        where = str(self._limb.pk)
        decision = relay.decide(state, spec, tool, call.args)
        if decision.verdict is not None:
            v = decision.verdict
            return self._done(state, call, tool, False, v.words, "refused", v.guard, started, where)
        held = self._held(state, call, tool, started, where)
        if held is not None:
            return held
        if decision.after_turn:
            # K1: run by the limb once the reply ends, with a call id and a result post like any
            # relayed call; its trace row is written when that result comes (`relay_turn.py`).
            request = relay.request(state, tool, call, state.tainted)
            request.update(limb_id=where, after_reply=True)
            relay.count_call(state)
            state.after_turn.append(request)
            words = relay.AFTER_TURN_WORDS.format(name=tool.name)
            response = {**model_response(False, words), "call_id": request["call_id"]}
            return ToolOutcome(
                name=tool.name, ok=False, response=response, writes=tool.writes, outcome=AFTER
            )
        tainted_before = state.tainted
        if relay.reads_outside(tool, call.args):
            # Taint before the request leaves (ADR 0098), saved or the request never goes.
            state.add_taint(tool.name)
            saved = self.recorder.mark_taint(state) if self.recorder is not None else True
            if not saved:
                words = read_through.MARK_FAILED
                return self._done(
                    state, call, tool, False, words, "refused", "tainted", started, where
                )
        request = relay.request(state, tool, call, tainted_before)
        request["limb_id"] = where
        relay.count_call(state)
        return ToolOutcome(name=tool.name, ok=False, response={}, writes=tool.writes, relay=request)

    def _done(
        self,
        state,
        call,
        tool,
        ok,
        text,
        outcome,
        guard,
        started,
        where="engine",
        outside=False,
        fact=None,
        data=None,
        spoken=None,
    ) -> ToolOutcome:
        ms = int((time.monotonic() - started) * 1000)
        writes = bool(tool is not None and tool.writes)
        response = model_response(ok, spoken.for_model if spoken and spoken.for_model else text)
        self.calls.append(
            TurnCall(
                call.name,
                ok,
                text,
                writes,
                data,
                ends_turn=bool(spoken and spoken.ends_turn),
                verbatim=spoken.lines if spoken else None,
                replay=spoken.replay if spoken else None,
                response=response,
                call_id=call.id,
            )
        )
        if self.recorder is not None:
            self.recorder.tool_call(
                state,
                call_id=call.id,
                tool=call.name,
                writes=writes,
                args=redact.redact_args(call.name, call.args, after_outside=bool(state.outside)),
                result=redact.trim_result(call.name, text, outside=outside or bool(state.outside)),
                outcome=outcome,
                guard=guard,
                ms=ms,
                where=where,
            )
        if self.observe is not None:
            self.observe(
                {
                    "name": call.name,
                    "args": dict(call.args),
                    "ok": ok,
                    "text": text,
                    "outcome": outcome,
                    "guard": guard,
                    "writes": writes,
                }
            )
        return ToolOutcome(
            name=call.name,
            ok=ok,
            response=response,
            writes=writes,
            outcome=outcome,
            guard=guard,
            ms=ms,
            fact=fact,
            ends_turn=bool(spoken and spoken.ends_turn),
        )


FACT_CHARS = 120


def write_fact(tool: str, structured) -> dict:
    """What a write that went through did, in words a limb may say (`{tool, title?, list?}`):
    the row's own title or text and its list, never its arguments. Only the tool when the
    result names neither."""
    fact = {"tool": tool}
    found = structured if isinstance(structured, dict) else {}
    row = found.get("row") if isinstance(found.get("row"), dict) else found
    for key, names in (("title", ("title", "text", "name")), ("list", ("list", "checklist"))):
        for name in names:
            value = row.get(name)
            if isinstance(value, str) and value.strip():
                fact[key] = value.strip()[:FACT_CHARS]
                break
    return fact
