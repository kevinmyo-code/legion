"""A turn paused on a limb (the relay, ADR 0098; engine-brain ticket 04, slice E2): its pause, the
limb's results, how a paused or dropped turn is closed, and what the limb may ask about it. The
rest of the turn (the continuation stream) is `relay_resume.py`.

**The pause** (`suspender`): the loop returned a `Suspension`; before any `tool_request` leaves,
the turn row gets status `awaiting_limb` and `pending_relay`:
`{loop, state, device_id, limb_id, asked_at_ms, stream_no, results}`. `state` is the whole
`TurnState` it resumes from (no outside text: `relay.decide` refuses a pause once any was read).
Saved or the request never goes (`TurnStore.suspend`, fail closed). The stream then ends.

**Deadlines** (`deadlines`): the limb runs a round's calls one at a time, in order, so call N may
not start before calls 1..N-1 end. Call N is late after `asked_at + sum(deadline_ms of calls 1..N)
+ relay.GRACE_MS` on the engine's wall clock: a 60 s confirm screen on call 1 never makes call 2
late.

**A result** (`record_result`, `POST /api/agents/turn/<id>/tool_result`), under a row lock, so two
posts at once can neither both record nor both resume:
- only the member's own turn, from the device it was asked of; anything else is `no_turn` (404);
- a call id the turn never asked: `unknown_call`; a `name` that is not that call's tool:
  `wrong_tool` (409); nothing is recorded;
- recorded at once, before any stream: its trace row, and the turn's `writes_ran` when it is a
  write that went through, so a continuation that drops cannot lose the device's write;
- a call id already recorded: idempotent, except the one second result the wire allows: an `ok`
  after `timed_out`;
- a result after its deadline is recorded as the device said; the model is told "did not finish;
  nothing claimed";
- every call answered (or timed out): this post claims the resume (status `running`, so a second
  post cannot) unless the caller's `gate` refuses (no key: recorded, the turn keeps waiting, a
  re-post resumes; the member's daily allowance spent: recorded, the turn closes);
- a turn cancelled while it waited: the result is recorded, the turn closes cancelled, no model.

**A closed turn** (`_close`: cancelled, stale, dropped, over the allowance) keeps
`pending_relay = {issued: {call_id: {name, writes, read_through, limb_id, recorded, by}}}`: every
call it asked, so a result that comes after the close (a write the device made) is still recorded
(`_record_late`): its trace row, `writes_ran`, and its response in the turn's history (found by the
`call_id` each relayed response carries), so his next turn's model sees it. A K1 device action
(`done.device_actions`) is recorded the same way. `retention.py` nulls this with the turn's words.

**A time limit on late results** (`LATE_RESULT_MS`, 24 hours from the request): after it, 410
`too_late`, nothing recorded. A device that comes back online the next morning still gets its write
on the record; a week-old replay does not. A late result rewrites the closed turn's stored history
(the one response it answers, found by `call_id`) at the moment it arrives, and MARKS it: the
response says "Reported by the device after this turn ended" and carries `reported_late: true`, so
a later turn that reads it again knows it changed after that turn ended.

**Stale** (`close_stale`, on the member's next turn and on `GET /api/agents/turn/<id>`): a turn
still `awaiting_limb` after `IDLE_CLOSE_MS` (10 minutes, the wire's idle close) AND after its
round's last call deadline plus the grace (whichever is later: a slow round is never closed while
the device may still be working on it; `relay.MAX_CALLS_PER_ROUND` keeps that inside 10 minutes), or
`running` a continuation `RUNNING_STALE_MS` after its resume began (2 minutes: the turn's own
budget is 30 s; a process that died mid-stream), closes as failed with what is known. His next
turn in the same conversation closes a pause at once (`close_paused`).
"""

from __future__ import annotations

import json
import time
from dataclasses import dataclass, field

from django.db import transaction

from agents.graph.state import TurnState
from agents.legion import redact, relay
from agents.legion.gateway import model_response, write_fact
from agents.legion.store import TurnStore
from agents.models import (
    TURN_AWAITING_LIMB,
    TURN_CANCELLED,
    TURN_FAILED,
    TURN_RUNNING,
    AgentTurn,
)
from agents.runtime.loop import CANCELLED_WORDS, Suspension, abandoned_round
from agents.runtime.tools import ToolOutcome
from household.tenancy import household_of

# `not_run`: the limb did not start the call (a cancel came first, the phone was locked): traced
# as `refused` (the trace's outcomes are fixed by a CHECK).
RESULT_OUTCOMES = ("ok", "failed", "refused", "timed_out", "not_run")
# What the model is handed of one result, at most (the trace keeps `redact.RESULT_CHARS`).
MAX_MODEL_CHARS = 8_000
IDLE_CLOSE_MS = 10 * 60 * 1000
LATE_RESULT_MS = 24 * 60 * 60 * 1000
LATE_REPORT = "Reported by the device after this turn ended: "

RUNNING_STALE_MS = 2 * 60 * 1000
LATE = "late"
DROPPED_WORDS = (
    "That answer stopped before it was done. Nothing is claimed beyond what is on the record."
)
NO_TURN = "No turn of yours with that id; nothing recorded."
TOO_LATE = "That result came more than 24 hours after it was asked; nothing recorded."
UNKNOWN_CALL = "That call id was not asked of this device; nothing recorded."


class RelayRefusal(Exception):
    def __init__(self, http: int, code: str, detail: str):
        super().__init__(detail)
        self.http, self.code, self.detail = http, code, detail


@dataclass
class Recorded:
    """What a result post answers: `resume` (stream the rest), `waiting_for` (202), `refused` (a
    gate's (http, code, words): recorded, no stream), or `status` (200, recorded, no stream)."""

    resume: dict | None = None
    waiting_for: list[str] = field(default_factory=list)
    status: str = ""
    refused: tuple | None = None
    # This post's own result, whole: a read-through result is used from here, never stored.
    fresh: dict = field(default_factory=dict)


def _now_ms() -> int:
    return int(time.time() * 1000)


def suspender(store: TurnStore, request):
    """`NodeContext.suspend` for a LEGION turn: adds what the result post checks, then saves."""

    def suspend(state: TurnState) -> bool:
        requests = [item["request"] for item in state.pending_relay["loop"]["relay"]]
        stream_no = int(state.client_facts.get("stream_no", 1)) + 1
        state.client_facts["stream_no"] = stream_no
        state.pending_relay.update(
            device_id=getattr(request.auth, "pk", None),
            limb_id=requests[0].get("limb_id"),
            asked_at_ms=_now_ms(),
            stream_no=stream_no,
            results={},
        )
        return store.suspend(state)

    return suspend


def deadlines(paused: dict) -> dict[str, int]:
    """Each call's engine deadline (module docstring): cumulative, in the round's order."""
    out, at = {}, int(paused["asked_at_ms"])
    for item in paused["loop"]["relay"]:
        at += int(item["request"]["deadline_ms"])
        out[item["request"]["call_id"]] = at + relay.GRACE_MS
    return out


def _owned(request, turn_id):
    return AgentTurn.objects.select_for_update().filter(
        pk=turn_id, household=household_of(request), owner_user=request.user
    )


def record_result(request, turn_id, body: dict, gate=None) -> Recorded:
    """Records one call's result (module docstring). `body` is the validated post; `gate()` says
    whether the rest of the turn may run now: None, or (http, code, words, close)."""
    with transaction.atomic():
        turn = _owned(request, turn_id).first()
        if turn is None:
            raise RelayRefusal(404, "no_turn", NO_TURN)
        paused = turn.pending_relay
        if not paused or "loop" not in paused:
            return _record_late(turn, request, body)
        if paused.get("device_id") != getattr(request.auth, "pk", None):
            raise RelayRefusal(404, "no_turn", NO_TURN)
        asked = {item["request"]["call_id"]: item["request"] for item in paused["loop"]["relay"]}
        call_id = body["call_id"]
        if call_id not in asked:
            raise RelayRefusal(409, "unknown_call", UNKNOWN_CALL)
        _same_tool(asked[call_id], body)
        store = TurnStore(turn)
        results = paused.setdefault("results", {})
        fresh: dict = {}
        now = _now_ms()
        due = deadlines(paused)
        if call_id in results:
            _maybe_late_ok(turn, asked[call_id], results[call_id], body)
        else:
            fresh[call_id] = _result_row(body, now > due[call_id])
            results[call_id] = _kept(asked[call_id], fresh[call_id])
            _trace(store, asked[call_id], fresh[call_id], body)
            if body["outcome"] == "ok" and asked[call_id].get("writes"):
                turn.writes_ran = True
        for cid, req in asked.items():
            if cid not in results and now > due[cid]:
                results[cid] = _engine_timed_out()
                _trace(store, req, results[cid], None)
        if turn.status != TURN_AWAITING_LIMB:
            # Another post already resumed it: it is running and owns the row now.
            return Recorded(status=turn.status)
        if turn.cancel_requested:
            _close(turn, paused, TURN_CANCELLED, CANCELLED_WORDS)
            return Recorded(status=TURN_CANCELLED)
        turn.pending_relay = paused
        open_calls = [cid for cid in asked if cid not in results]
        if open_calls:
            turn.save(update_fields=["pending_relay", "writes_ran"])
            return Recorded(waiting_for=open_calls)
        refused = gate() if gate is not None else None
        if refused is not None:
            http, code, words, close = refused
            if close:
                _close(turn, paused, TURN_FAILED, words)
            else:
                turn.save(update_fields=["pending_relay", "writes_ran"])
            return Recorded(status=turn.status, refused=(http, code, words))
        paused["resumed_at_ms"] = now
        turn.status = TURN_RUNNING
        turn.save(update_fields=["pending_relay", "status", "writes_ran"])
        return Recorded(resume=paused, fresh=fresh)


def _same_tool(req: dict, body: dict) -> None:
    if body.get("name") and body["name"] != req["name"]:
        raise RelayRefusal(
            409,
            "wrong_tool",
            f"Call {req['call_id']} asked for {req['name']}, not {body['name']}; nothing recorded.",
        )


def _engine_timed_out() -> dict:
    return {"outcome": "timed_out", "words": "", "by": "engine"}


def _result_row(body: dict, late: bool) -> dict:
    words = (body.get("words") or "").strip()
    row = {"outcome": body["outcome"], "words": words, "ms": body.get("ms"), "by": "limb"}
    if body["outcome"] == "ok" and body.get("result") is not None:
        row["result"] = body["result"]
    if late:
        row["late"] = True
    return row


def _kept(req: dict, row: dict) -> dict:
    """What `pending_relay` may hold of a result: a read-through call's words and result are
    outside text, so only its outcome (its post resumes at once: it paused alone)."""
    if not req.get("read_through"):
        return row
    return {k: v for k, v in row.items() if k not in ("words", "result")} | {"outside": True}


def _maybe_late_ok(turn, req: dict, recorded: dict, body: dict) -> None:
    """The one second result the wire allows: `ok` after `timed_out` (it landed late)."""
    if recorded.get("outcome") == "timed_out" and body["outcome"] == "ok":
        recorded["landed_late"] = True
        turn.tool_calls.filter(call_id=req["call_id"], outcome="timed_out").update(outcome="ok")
        if req.get("writes"):
            turn.writes_ran = True


def _record_late(turn: AgentTurn, request, body: dict) -> Recorded:
    """A result for a call of a turn no longer paused (closed, or a K1 device action after its
    reply): recorded as a fact, no model step. The limb's own report replaces the engine's
    `timed_out`; after that only an `ok` after its own `timed_out` is taken."""
    issued = (turn.pending_relay or {}).get("issued") or {}
    call_id = body["call_id"]
    meta = issued.get(call_id)
    limb = relay.limb_of(request)
    if meta is None:
        return _record_repeat(turn, limb, body)
    if limb is None or str(limb.pk) != meta.get("limb_id"):
        raise RelayRefusal(409, "unknown_call", UNKNOWN_CALL)
    asked = meta.get("asked_at_ms") or int(turn.created_at.timestamp() * 1000)
    if _now_ms() > int(asked) + LATE_RESULT_MS:
        raise RelayRefusal(410, "too_late", TOO_LATE)
    _same_tool({**meta, "call_id": call_id}, body)
    prior, by = meta.get("recorded"), meta.get("by")
    takes = prior is None or by == "engine" or (prior == "timed_out" and body["outcome"] == "ok")
    if takes:
        req = {**meta, "call_id": call_id}
        row = _result_row(body, False)
        store = TurnStore(turn)
        traced = turn.tool_calls.filter(call_id=call_id)
        if traced.exists():
            traced.update(
                outcome=_trace_outcome(body["outcome"]),
                result_trimmed=_trace_text(req, row),
                ms=body.get("ms"),
            )
        else:
            _trace(store, req, row, body)
        _patch_history(turn, call_id, _history_response(req, row))
        meta.update(recorded=body["outcome"], by="limb")
        if body["outcome"] == "ok" and meta.get("writes"):
            turn.writes_ran = True
        turn.save(update_fields=["pending_relay", "history", "writes_ran"])
    status = turn.status if turn.status in (TURN_CANCELLED, TURN_RUNNING) else LATE
    return Recorded(status=status)


def _record_repeat(turn: AgentTurn, limb, body: dict) -> Recorded:
    """A result for a call of a turn that resumed and ended: already recorded (its continuation
    ran on it), so idempotent; only an `ok` after its `timed_out` replaces the trace's outcome."""
    rows = turn.tool_calls.filter(call_id=body["call_id"]).exclude(where="engine")
    row = rows.first()
    if row is None or limb is None or str(limb.pk) != row.where:
        raise RelayRefusal(409, "unknown_call", UNKNOWN_CALL)
    if _now_ms() > int(turn.created_at.timestamp() * 1000) + LATE_RESULT_MS:
        raise RelayRefusal(410, "too_late", TOO_LATE)
    if row.outcome == "timed_out" and body["outcome"] == "ok":
        rows.update(outcome="ok")
    status = turn.status if turn.status in (TURN_CANCELLED, TURN_RUNNING) else LATE
    return Recorded(status=status)


def _history_response(req: dict, row: dict) -> dict:
    """The closed turn's response, rewritten for a late result: marked as reported late."""
    if req.get("read_through"):
        return {"note": redact.OUTSIDE_STUB, "call_id": req["call_id"], "reported_late": True}
    response = outcome_of(req, row).response
    return {**response, "message": LATE_REPORT + response["message"], "reported_late": True}


def _patch_history(turn: AgentTurn, call_id: str, response: dict) -> None:
    for content in turn.history or []:
        for part in content.get("parts") or []:
            resp = part.get("functionResponse")
            if isinstance(resp, dict) and (resp.get("response") or {}).get("call_id") == call_id:
                resp["response"] = {**response, "call_id": call_id}


def _trace_outcome(outcome: str) -> str:
    return "refused" if outcome == "not_run" else outcome


def _trace_text(req: dict, row: dict) -> str:
    return redact.trim_result(req["name"], _model_text(row), outside=req.get("read_through", False))


def _trace(store: TurnStore, req: dict, result: dict, body: dict | None) -> None:
    store.tool_call(
        None,
        call_id=req["call_id"],
        tool=req["name"],
        writes=bool(req.get("writes")),
        args=redact.redact_args(req["name"], req.get("args") or {}),
        result=_trace_text(req, result),
        outcome=_trace_outcome(result["outcome"]),
        guard=None,
        ms=(body or {}).get("ms"),
        where=req.get("limb_id") or "limb",
    )


def _model_text(result: dict) -> str:
    text = result.get("words") or ""
    if "result" in result:
        text = f"{text} {json.dumps(result['result'], ensure_ascii=False)}".strip()
    return text[:MAX_MODEL_CHARS]


def outcome_of(req: dict, result: dict) -> ToolOutcome:
    """What the model is handed for one relayed call (its `call_id` rides in the response)."""
    if result["outcome"] == "timed_out" or result.get("late"):
        ok, outcome, text = False, "timed_out", relay.TIMED_OUT_WORDS
    else:
        ok, outcome = result["outcome"] == "ok", _trace_outcome(result["outcome"])
        text = _model_text(result) or ("Done." if ok else "It did not run.")
    wrote = ok and bool(req.get("writes"))
    return ToolOutcome(
        name=req["name"],
        ok=ok,
        response={**model_response(ok, text), "call_id": req["call_id"]},
        writes=bool(req.get("writes")),
        outcome=outcome,
        fact=write_fact(req["name"], result.get("result")) if wrote else None,
    )


def close_paused(conversation, before_seq: int) -> int:
    """Closes this conversation's turns still paused on a limb (his next turn began): failed,
    open calls timed out, history made whole. Returns how many."""
    pks = conversation.turns.filter(status=TURN_AWAITING_LIMB, seq__lt=before_seq).values_list(
        "pk", flat=True
    )
    return sum(_close_if(pk, (TURN_AWAITING_LIMB,), relay.TIMED_OUT_WORDS) for pk in pks)


def close_stale(household, user, now_ms: int | None = None) -> int:
    """Closes the member's paused turns past the idle close and continuations that stopped
    without ending (module docstring). One query when there are none."""
    now_ms = now_ms or _now_ms()
    closed = 0
    rows = AgentTurn.objects.filter(
        household=household,
        owner_user=user,
        status__in=(TURN_AWAITING_LIMB, TURN_RUNNING),
        pending_relay__has_key="loop",
    ).values_list("pk", "status", "pending_relay")
    for pk, status, paused in rows:
        if status == TURN_AWAITING_LIMB:
            asked = int(paused.get("asked_at_ms") or 0)
            last_deadline = max(deadlines(paused).values(), default=asked)
            stale = now_ms > max(asked + IDLE_CLOSE_MS, last_deadline)
        else:
            stale = now_ms - int(paused.get("resumed_at_ms") or 0) > RUNNING_STALE_MS
        if stale:
            closed += _close_if(pk, (status,), DROPPED_WORDS)
    return closed


def _close_if(pk, statuses, words: str) -> int:
    with transaction.atomic():
        turn = AgentTurn.objects.select_for_update().filter(pk=pk, status__in=statuses).first()
        if turn is None or "loop" not in (turn.pending_relay or {}):
            return 0
        _close(turn, turn.pending_relay, TURN_FAILED, words)
        return 1


def _close(turn: AgentTurn, paused: dict, status: str, words: str) -> None:
    """Ends a paused turn with no further model round. Open calls are traced `timed_out`. Its
    history is the state's messages plus the round's responses: a limb call's own result where
    one came (a write the device made is on the record), `words` for the rest; and it keeps the
    calls it asked, so a later result is still recorded (`_record_late`)."""
    state = TurnState.from_json(paused["state"])
    suspension = Suspension.from_json(paused["loop"])
    results = paused.setdefault("results", {})
    store = TurnStore(turn)
    answered = {}
    wrote = False
    requests = [item["request"] for item in suspension.relay]
    for req in requests:
        if req["call_id"] not in results:
            results[req["call_id"]] = _engine_timed_out()
            _trace(store, req, results[req["call_id"]], None)
        elif results[req["call_id"]].get("by") != "engine":
            outcome = outcome_of(req, results[req["call_id"]])
            answered[req["call_id"]] = (
                {"note": redact.OUTSIDE_STUB} if req.get("read_through") else outcome.response
            )
            wrote = wrote or (outcome.ok and outcome.writes)
    state.messages.append(abandoned_round(suspension, words, answered))
    turn.status = status
    turn.pending_relay = relay.issued_record(requests, results, paused.get("asked_at_ms"))
    turn.writes_ran = turn.writes_ran or state.writes_ran or wrote
    turn.final_text = " ".join(suspension.spoken).strip() or words
    turn.history = redact.history_for_storage(
        state.messages[state.client_facts.get("history_from", 0) :], state.outside
    )
    turn.save(update_fields=["status", "pending_relay", "writes_ran", "final_text", "history"])


def made_whole(messages: list[dict], words: str) -> list[dict]:
    """`messages` with any functionCall of the last model turn answered by `words`, so a stored
    history is a valid exchange however the turn stopped."""
    if not messages or messages[-1].get("role") != "model":
        return messages
    calls = [p["functionCall"] for p in messages[-1].get("parts") or [] if "functionCall" in p]
    if not calls:
        return messages
    parts = []
    for call in calls:
        response = {"name": call.get("name"), "response": {"success": False, "message": words}}
        if call.get("id"):
            response["id"] = call["id"]
        parts.append({"functionResponse": response})
    return [*messages, {"role": "user", "parts": parts}]


def status_of(request, turn_id) -> dict:
    """`GET /api/agents/turn/<id>`: what the limb that was asked needs to pick a turn up again
    after a dropped stream: its status, and the calls still waited for, whole (the same
    `tool_request` data). Only the member's own turn and the device asked: else `no_turn`."""
    close_stale(household_of(request), request.user)
    turn = (
        AgentTurn.objects.filter(
            pk=turn_id, household=household_of(request), owner_user=request.user
        )
        .only("pk", "status", "seq", "conversation_id", "pending_relay")
        .first()
    )
    if turn is None:
        raise RelayRefusal(404, "no_turn", "No turn of yours with that id.")
    paused = turn.pending_relay or {}
    limb = relay.limb_of(request)
    if "loop" in paused:
        if paused.get("device_id") != getattr(request.auth, "pk", None):
            raise RelayRefusal(404, "no_turn", "No turn of yours with that id.")
        results = paused.get("results") or {}
        requests = [item["request"] for item in paused["loop"]["relay"]]
        waiting = [r for r in requests if r["call_id"] not in results]
        return {
            "turn_id": str(turn.pk),
            "seq": turn.seq,
            "status": turn.status,
            "stream_no": paused.get("stream_no"),
            "calls": requests,
            "waiting_for": [r["call_id"] for r in waiting],
            "recorded": sorted(results),
        }
    issued = paused.get("issued") or {}
    if issued and (limb is None or any(m.get("limb_id") != str(limb.pk) for m in issued.values())):
        raise RelayRefusal(404, "no_turn", "No turn of yours with that id.")
    return {
        "turn_id": str(turn.pk),
        "seq": turn.seq,
        "status": turn.status,
        "stream_no": None,
        "calls": [],
        "waiting_for": [],
        "recorded": sorted(c for c, m in issued.items() if m.get("recorded")),
    }
