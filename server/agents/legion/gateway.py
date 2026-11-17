"""The tool gateway: every tool call of an agent turn, through the guards, as the member.

- **One request per turn.** Every engine tool runs through `engine_mcp.tools.run_engine_tool`
  with the turn's own request: the write-scope fence, the table fence, the schema, and the
  handler under the member's `visible()` (ADR 0045, 0052), exactly as `/mcp` and the web door run
  them. Because it is one request, `assistant.read_through`'s per-request flag is a second fence
  behind the taint in the turn state.
- **Declarations** are the web assistant's own (`assistant.surface.tools_for(user)`, so
  `run_procedure` and `show_diagram` list only what this member may use), narrowed to the spec's
  allowlist, plus the read twins.
- **Taint before the tool.** A third-party tool (`read_through.THIRD_PARTY_TOOLS`) first adds
  itself to the turn's taint and the store saves it; if the save fails the tool does not run and
  the turn is refused writes for good (fail closed, ADR 0090).
- **Every call is recorded** (`AgentToolCall`): redacted arguments, trimmed result, outcome,
  guard, time. Never a mail tool's content (`redact.py`).
"""

from __future__ import annotations

import time

from agents.legion import guards, redact
from agents.legion.twins import TWINS
from agents.runtime.tools import ToolCall, ToolOutcome
from assistant import live, read_through
from assistant.surface import tools_for, web_description
from engine_mcp.models import Outcome
from engine_mcp.tools import ToolResult, run_engine_tool

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

_OUTCOMES = {
    Outcome.OK: "ok",
    Outcome.REFUSED: "refused",
    Outcome.FAILED: "failed",
}


def rest_declarations(tools) -> list[dict]:
    """The web door's declarations (`assistant.live.function_declarations`) less `behavior`, a Live
    API field the REST `streamGenerateContent` request does not take (the phone's cascade sends no
    such field either)."""
    out = []
    for decl in live.function_declarations(tools, web_description):
        decl = dict(decl)
        decl.pop("behavior", None)
        out.append(decl)
    return out


def model_response(ok: bool, text: str) -> dict:
    """The web door's shape (`assistant.views._forward`), so both brains read results alike."""
    return {"success": ok, "message": text}


class LegionGateway:
    def __init__(self, request, recorder=None, observe=None):
        self.request = request
        self.user = request.user
        self.recorder = recorder
        # The eval runner's ear: every call's name, arguments, outcome and full result text.
        self.observe = observe
        self._tools = None

    def tools(self) -> dict:
        if self._tools is None:
            by_name = {t.name: t for t in tools_for(self.user)}
            by_name.update(TWINS)
            self._tools = by_name
        return self._tools

    def declarations(self, spec) -> list[dict]:
        tools = self.tools()
        chosen = [tools[name] for name in spec.tools if name in tools]
        return rest_declarations(chosen)

    def is_slow(self, name: str) -> bool:
        return name in SLOW_TOOLS

    def call(self, state, spec, call: ToolCall, deadline_s: float) -> ToolOutcome:
        tool = self.tools().get(call.name)
        started = time.monotonic()
        verdict = guards.check_call(state, spec, call.name, call.args, tool, self.user)
        if not verdict.allow:
            return self._done(
                state, call, tool, False, verdict.words, "refused", verdict.guard, started
            )
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
        result: ToolResult = run_engine_tool(self.request, tool, dict(call.args))
        # The deadline is soft (budget.py): a late tool is not interrupted; its `ms` says so.
        outcome = _OUTCOMES.get(result.outcome, "failed" if result.is_error else "ok")
        return self._done(
            state, call, tool, not result.is_error, result.text, outcome, None, started
        )

    def _done(self, state, call, tool, ok, text, outcome, guard, started) -> ToolOutcome:
        ms = int((time.monotonic() - started) * 1000)
        writes = bool(tool is not None and tool.writes)
        if self.recorder is not None:
            self.recorder.tool_call(
                state,
                call_id=call.id,
                tool=call.name,
                writes=writes,
                args=redact.redact_args(call.name, call.args),
                result=redact.trim_result(call.name, text),
                outcome=outcome,
                guard=guard,
                ms=ms,
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
            response=model_response(ok, text),
            writes=writes,
            outcome=outcome,
            guard=guard,
            ms=ms,
        )
