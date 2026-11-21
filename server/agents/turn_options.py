"""What a turn request may say about how it is answered, read the same way on both turn doors
(`POST /api/agents/turn` and the stream's metadata frame), engine-brain slice E2b:

- `go` (bool): false holds the turn until the phone's decision (`agents/legion/hold.py`).
  ABSENT means HOLD wherever the engine hears the words itself (the stream route; an `audio` clip
  on the form route), since the phone sees them only at `heard`; it means go only where the
  request carries the words (a `transcript`: typed, or the phone's own speech-to-text).
- `speak` (bool): false makes no speech at all (no text-to-speech request, no `audio` events,
  nothing metered as speech, `done` as soon as the text is complete). Unset keeps each door's
  default: an audio turn is spoken, a transcript turn is not.
- `client_facts` (object, `agents/legion/client_facts.py`).
- `turn_id` (UUID, made by the client): the turn row is made with it, so a re-post of the same
  turn (a lost response, a retry) never runs twice: it answers 409 `duplicate` with the turn's
  status, and `GET /api/agents/turn/<turn_id>` reads it (`duplicate_refusal`). A `turn_id` that
  is another member's answers the same 409, with no status.
- **The brain** (`brain_refusal`): a device whose resolved brain is `phone` (the server's force or
  override, the member's Settings choice as last attached, the server default) gets 409
  `brain_phone` and its phone answers, test turns included (`test_mode` only ever refuses
  things inside a turn; it never opens the engine to a device the server sends to the phone, so
  `LEGION_AGENTS_BRAIN_FORCE=phone` stays a rollback switch). A caller with no limb (a browser
  session, a head unit's token) is not a limb and is not checked.
- **A head unit sends no `client_facts`** (403 `headunit_client_facts`): no head unit client uses
  them, and whoever holds the unit may not be him (`headunit_refusal`).
"""

from __future__ import annotations

import json
import uuid

from rest_framework import status
from rest_framework.response import Response

from agents.legion import client_facts, limbs, relay

BRAIN_PHONE = (
    "Nothing was answered: this device's turns are answered on the phone (the engine brain is "
    "not on for it)."
)


def _refuse(detail: str, code: str, http: int) -> Response:
    return Response({"detail": f"Nothing was answered. {detail}", "code": code}, status=http)


def read(raw: dict, default_go: bool) -> tuple[dict, Response | None]:
    """`{go, speak, client_facts, turn_id}` from a request body or metadata frame, or the refusal.
    `default_go`: what an absent `go` means on this door (module docstring)."""
    facts_raw = raw.get("client_facts")
    if isinstance(facts_raw, str):
        try:
            facts_raw = json.loads(facts_raw)
        except ValueError:
            return {}, _refuse("`client_facts` is not JSON.", "bad_request", 400)
    facts, problem = client_facts.parse(facts_raw)
    if problem:
        return {}, _refuse(problem, "bad_request", 400)
    out = {"go": default_go, "speak": None, "client_facts": facts, "turn_id": None}
    if raw.get("turn_id") not in (None, ""):
        try:
            out["turn_id"] = uuid.UUID(str(raw["turn_id"]))
        except ValueError:
            return {}, _refuse("`turn_id` is not a UUID.", "bad_request", 400)
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


def duplicate_refusal(request, options: dict) -> Response | None:
    turn_id = options.get("turn_id")
    if turn_id is None:
        return None
    from agents.models import AgentTurn

    row = AgentTurn.objects.filter(pk=turn_id).values("owner_user_id", "status").first()
    if row is None:
        return None
    body = {
        "detail": "Nothing was run again: this turn was already posted.",
        "code": "duplicate",
        "turn_id": str(turn_id),
    }
    if row["owner_user_id"] == request.user.pk:
        body["status"] = row["status"]
    return Response(body, status=status.HTTP_409_CONFLICT)


def headunit_refusal(request, options: dict) -> Response | None:
    from headunit_signin import scope as headunit_scope

    if options.get("client_facts") and headunit_scope.is_headunit(request):
        return _refuse(
            "A head unit sends no `client_facts`.",
            "headunit_client_facts",
            status.HTTP_403_FORBIDDEN,
        )
    return None


def brain_refusal(request, options: dict) -> Response | None:
    limb = relay.limb_of(request)
    if limb is None:
        return None
    brain, _why = limbs.resolve_brain(limb, (limb.manifest or {}).get("brain_asked"))
    if brain == "phone":
        return _refuse(BRAIN_PHONE, "brain_phone", status.HTTP_409_CONFLICT)
    return None
