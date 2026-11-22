"""PreToolUse hook for the message courier (`sessions.py send`, alfred-sessions 2026-11-22).

The gate between Kevin's yes and another Claude session. The courier is a one-shot `claude -p`
with only ListAgents, SendMessage and ToolSearch; this hook, in its own settings file, is the
whole check that what goes out is what Kevin saw on the card:

- SendMessage passes ONLY while `sends/sending.json` is armed (written by `sessions.py send`
  after it spent Kevin's `session_send` token), with every recipient field equal to the planned
  name and every text field equal to the planned text, character for character, no field this
  hook does not know, and `summary` absent or the fixed one. The first one that passes disarms
  the plan (state `sent`) BEFORE it is allowed, so a second send is denied.
- ListAgents and ToolSearch pass (the courier loads SendMessage with ToolSearch).
- Everything else is denied, and anything unexpected denies (fails closed): the output says deny
  and the exit code is 2, which blocks the call even if the JSON were lost.

`sending.json` lives in the worker's own folder (`<root>/sends`), which no helper may write (the
ask-list hook's worker-files rule). Logs nothing but the decision.
"""
from __future__ import annotations

# ruff: noqa: E402
# Review round 4d: these lines run before any other import (the same preamble as report.py). This
# folder is taken off the front of the path and added back at the END, and bytecode lives under
# the worker's own root, so nothing planted beside the scripts can shadow the standard library.
import sys

sys.path[:] = [_p for _p in sys.path if _p.replace("\\", "/").rstrip("/").lower()
               != __file__.replace("\\", "/").rsplit("/", 1)[0].lower()]
import os

sys.pycache_prefix = os.path.join(os.environ.get("LEGION_CLAUDE_HOME") or os.path.join(
    os.environ.get("APPDATA") or os.path.expanduser("~/AppData/Roaming"), "legion-claude"),
    "pycache")

import json
import sys
import time
from pathlib import Path

sys.path.append(str(Path(__file__).resolve().parent))  # own modules after the stdlib
from common import Paths, read_json, write_json_atomic  # noqa: E402

RECIPIENT_FIELDS = ("to", "recipient")
# `message` is what the model wrote and what is delivered. `content` is the harness's own preview
# of it, cut with an ellipsis past about 50 characters (seen on the real CLI, 2026-11-22): it must
# be the message or a prefix of it ending in the ellipsis, never anything else.
PREVIEW_FIELD = "content"
ELLIPSIS = "…"
KNOWN = set(RECIPIENT_FIELDS) | {"message", PREVIEW_FIELD, "recipient_kind", "type", "summary"}
SUMMARY = "Message from Kevin, via LEGION"  # sessions.COURIER_SUMMARY
PASS_TOOLS = ("ListAgents", "ToolSearch")


def _deny(reason: str) -> dict:
    return {"hookSpecificOutput": {"hookEventName": "PreToolUse", "permissionDecision": "deny",
                                   "permissionDecisionReason": reason}}


def check(inp: dict, plan: dict | None) -> str | None:
    """None when this SendMessage is exactly the planned one; else why not."""
    if not isinstance(plan, dict) or plan.get("state") != "armed":
        return "No message is waiting to go. Do not retry."
    if not isinstance(inp, dict) or set(inp) - KNOWN:
        return "Use only to, summary and message. Nothing was sent."
    to = [inp[k] for k in RECIPIENT_FIELDS if k in inp]
    if not to or any(t != plan.get("name") for t in to):
        return f"The message goes only to {json.dumps(plan.get('name'))}. Nothing was sent."
    if inp.get("message") != plan.get("text") or not _preview_ok(inp, plan.get("text")):
        return ("The message must be exactly the text between the markers, character for "
                "character, nothing added or changed. Nothing was sent; try once more.")
    if inp.get("recipient_kind", "name") != "name" or inp.get("type", "message") != "message":
        return "Send it as a plain message to the name. Nothing was sent."
    if "summary" in inp and inp["summary"] != SUMMARY:
        return f"Use the summary {json.dumps(SUMMARY)} exactly. Nothing was sent."
    return None


def _preview_ok(inp: dict, text) -> bool:
    if PREVIEW_FIELD not in inp:
        return True
    p = inp[PREVIEW_FIELD]
    if not isinstance(p, str) or not isinstance(text, str):
        return False
    return p == text or (p.endswith(ELLIPSIS) and len(p) > 1 and text.startswith(p[:-1]))


def decide(event: dict, paths: Paths) -> dict | None:
    tool = event.get("tool_name", "")
    if tool in PASS_TOOLS:
        return None
    if tool != "SendMessage":
        return _deny("The courier only delivers one message.")
    plan_file = paths.sends / "sending.json"
    plan = read_json(plan_file, None)
    why = check(event.get("tool_input") or {}, plan)
    if why:
        return _deny(why)
    plan["state"] = "sent"  # disarmed before the call runs: one send per yes
    plan["sent_at"] = time.time()
    write_json_atomic(plan_file, plan)
    return None


def main() -> int:
    try:
        out = decide(json.load(sys.stdin), Paths())
    except Exception:  # fail closed
        out = _deny("The message gate failed. Nothing was sent.")
    if out:
        print(json.dumps(out))
        print(out["hookSpecificOutput"]["permissionDecisionReason"], file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
