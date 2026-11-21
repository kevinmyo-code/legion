"""What a turn request may say about how it is answered, read the same way on both turn doors
(`POST /api/agents/turn` and the stream's metadata frame), engine-brain slice E2b:

- `go` (bool, default true): false holds the turn until the phone's decision
  (`agents/legion/hold.py`). A phone that did its own speech-to-text, or a typed turn, already has
  the words: it sends true (or nothing) and no extra hop is made.
- `speak` (bool): false makes no speech at all (no text-to-speech request, no `audio` events,
  nothing metered as speech, `done` as soon as the text is complete). Unset keeps each door's
  default: an audio turn is spoken, a transcript turn is not.
- `client_facts` (object, `agents/legion/client_facts.py`).
- **The brain** (`brain_refusal`): a device whose resolved brain is `phone` (the server's force or
  override, the member's Settings choice as last attached, the server default) gets 409
  `brain_phone` and its phone answers. Not on a `test_mode` turn (the phone's test door may reach
  the engine whatever the setting; plan 1119 section 4, flag precedence). A caller with no limb
  (a browser session, a head unit's token) is not a limb and is not checked.
"""

from __future__ import annotations

import json

from rest_framework import status
from rest_framework.response import Response

from agents.legion import client_facts, limbs, relay

BRAIN_PHONE = (
    "Nothing was answered: this device's turns are answered on the phone (the engine brain is "
    "not on for it)."
)


def _refuse(detail: str, code: str, http: int) -> Response:
    return Response({"detail": f"Nothing was answered. {detail}", "code": code}, status=http)


def read(raw: dict) -> tuple[dict, Response | None]:
    """`{go, speak, client_facts}` from a request body or metadata frame, or the refusal."""
    facts_raw = raw.get("client_facts")
    if isinstance(facts_raw, str):
        try:
            facts_raw = json.loads(facts_raw)
        except ValueError:
            return {}, _refuse("`client_facts` is not JSON.", "bad_request", 400)
    facts, problem = client_facts.parse(facts_raw)
    if problem:
        return {}, _refuse(problem, "bad_request", 400)
    out = {"go": True, "speak": None, "client_facts": facts}
    for name in ("go", "speak"):
        value = raw.get(name)
        if isinstance(value, str):
            value = {"true": True, "false": False}.get(value.strip().lower(), value)
        if value is None:
            continue
        if not isinstance(value, bool):
            return {}, _refuse(f"`{name}` is true or false.", "bad_request", 400)
        out[name] = value
    return out, None


def brain_refusal(request, options: dict) -> Response | None:
    if options.get("client_facts", {}).get("test_mode"):
        return None
    limb = relay.limb_of(request)
    if limb is None:
        return None
    brain, _why = limbs.resolve_brain(limb, (limb.manifest or {}).get("brain_asked"))
    if brain == "phone":
        return _refuse(BRAIN_PHONE, "brain_phone", status.HTTP_409_CONFLICT)
    return None
