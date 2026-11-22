"""Pure readers over the phone's threadtime logcat: LEGION's audit lines, MicArbiter, sync, crashes."""
from __future__ import annotations

import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

from phone.adb import PACKAGE, THREADTIME_RE  # noqa: E402  (the one threadtime parser)
from voice_audit import parse_audit  # noqa: E402  (the one LIVE_AUDIT parser)

# The app's own lines a check reads. Each is named in the check's deps, so `stale` sees a change.
MIC_WAKE_GRANTED = re.compile(r"request: WAKE_WORD granted")
MIC_WAKE_REFUSED = re.compile(r"request: WAKE_WORD refused")
EVENTS_PULLED = re.compile(r"engine_poll\[events\]|events_auto_pull ")
RING_STOP = re.compile(r"ring_stop kind=timer")
DATE_IN_RESULT = re.compile(r'"date"\s*:\s*"(\d{4}-\d\d-\d\d)"')

# Tools a verification run must never see called, writes allowed or not: money, mail, messages,
# calls. Names as LIVE_AUDIT logs them (the resolved tool, and the action tools that reach them).
FORBIDDEN_TOOLS = frozenset({
    "email", "place_call", "answer_call", "decline_call", "phone",
    "money", "set_budget", "set_category", "categorize_transactions", "log_pending_transaction",
    "clear_pending_transaction", "set_goal", "close_goal", "accept_proposal", "accept_goal_plan",
    "generate_goal_plan", "send_message", "sms", "share",
})


@dataclass
class Line:
    tag: str
    msg: str
    pid: str
    level: str


def parse(text: str) -> list[Line]:
    out = []
    for raw in text.splitlines():
        m = THREADTIME_RE.match(raw.rstrip("\r"))
        if m:
            out.append(Line(m.group("tag").strip(), m.group("msg"), m.group("pid"), m.group("level")))
    return out


def tagged(lines: list[Line], tag: str) -> list[str]:
    return [ln.msg for ln in lines if ln.tag == tag]


@dataclass
class ToolCall:
    id: str
    name: str
    args: dict
    success: bool | None = None
    response: str = ""


def tool_calls(lines: list[Line]) -> list[ToolCall]:
    """Every assistant tool call in [lines], with its result when it came back."""
    calls: dict[str, ToolCall] = {}
    for msg in tagged(lines, "LIVE_AUDIT"):
        event, f = parse_audit(msg)
        if event == "tool_call":
            try:
                args = json.loads(f.get("args", "{}")) if isinstance(f.get("args"), str) else (f.get("args") or {})
            except ValueError:
                args = {}
            calls[str(f.get("id"))] = ToolCall(str(f.get("id")), str(f.get("name")), args if isinstance(args, dict) else {})
        elif event == "tool_result":
            c = calls.get(str(f.get("id")))
            if c:
                c.success = str(f.get("success")) == "true"
                resp = f.get("response", "")
                c.response = resp if isinstance(resp, str) else json.dumps(resp)
    return list(calls.values())


def sessions_ended(lines: list[Line]) -> list[str]:
    return [str(parse_audit(m)[1].get("reason", "")) for m in tagged(lines, "LIVE_AUDIT")
            if m.startswith("session_end")]


def crashes(lines: list[Line]) -> list[str]:
    """LEGION's crashes and ANRs: a FATAL EXCEPTION whose process is LEGION, or an ANR naming it.
    Returns one short line per crash (the exception class), never the stack (it may hold data)."""
    out = []
    crash_pids = {ln.pid for ln in lines if ln.tag == "AndroidRuntime" and f"Process: {PACKAGE}" in ln.msg}
    for i, ln in enumerate(lines):
        if ln.tag == "AndroidRuntime" and ln.pid in crash_pids and "FATAL EXCEPTION" in ln.msg:
            exc = next((x.msg.split(":")[0] for x in lines[i + 1:i + 6]
                        if x.pid == ln.pid and re.match(r"[\w.$]+(Exception|Error)\b", x.msg)), "unknown")
            out.append(f"crash {exc}")
        elif ln.tag == "ActivityManager" and ln.msg.startswith(f"ANR in {PACKAGE}"):
            out.append("ANR")
    return out
