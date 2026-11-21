"""A turn paused on a limb (the relay, ADR 0098; engine-brain ticket 04, slice E2): its pause, the
limb's results, and the rest of the turn.

**The pause** (`suspender`): the loop returned a `Suspension`; before any `tool_request` leaves,
the turn row gets status `awaiting_limb` and `pending_relay`:
`{loop, state, device_id, limb_id, asked_at_ms, stream_no, results}`. `state` is the whole
`TurnState` it resumes from (no outside text: `relay.decide` refuses a pause once any was read).
Saved or the request never goes (`TurnStore.suspend`, fail closed). The stream then ends. No
thread waits.

**A result** (`record_result`, `POST /api/agents/turn/<id>/tool_result`), under a row lock, so two
posts at once can neither both record nor both resume:
- only the member's own turn, from the device it was asked of; anything else is `no_turn` (404);
- a call id the paused round never asked: `unknown_call`; a `name` that is not that call's tool:
  `wrong_tool` (409); nothing is recorded;
- a call id already recorded: idempotent, nothing recorded again, except the one the wire contract
  allows: an `ok` for a call recorded `timed_out` replaces it on the trace row (it ran late; the
  model was already told nothing is claimed);
- a result after the call's deadline (its `deadline_ms` plus `relay.GRACE_MS`, on the engine's
  wall clock from when the request was saved) is recorded as the device said, but the model is told
  "did not finish; nothing claimed";
- a call still open past its deadline counts as `timed_out` when the next post comes;
- every call of the round answered (or timed out): this post claims the resume (status back to
  `running`, so a second post cannot) and answers with the rest of the turn; otherwise 202 with
  the calls still waited for;
- a turn cancelled while it waited (his stop or a barge-in, `POST .../cancel`): the result is
  recorded (the limb ran it), the turn closes as cancelled, no model round, no stream.

**A stale turn** (`close_paused`, at the start of his next turn in that conversation): a turn
still paused closes as failed, its open calls `timed_out`, its history made whole (every
functionCall answered) so the next turn's model context is valid. Its later results are recorded
as late (above).

Not built here, owed by the wire contract (section 8): replaying a stream from stored events (a
repeated post after the resume answers 200 with the turn's status, not the replay), the fact
ledger (`fact_id`, owed and write lines), the idle close after 10 minutes, a client `turn_id`.
"""

from __future__ import annotations

import json
import time
from dataclasses import dataclass, field

from django.db import transaction

from agents.graph.nodes import NodeContext, turn_graph
from agents.graph.state import TurnState
from agents.legion import redact, relay
from agents.legion.gateway import model_response
from agents.legion.store import TurnStore
from agents.models import (
    TURN_AWAITING_LIMB,
    TURN_CANCELLED,
    TURN_FAILED,
    TURN_RUNNING,
    AgentTurn,
)
from agents.runtime import events as ev
from agents.runtime.loop import CANCELLED_WORDS, Suspension, abandoned_round
from agents.runtime.tools import ToolOutcome
from household.tenancy import household_of

RESULT_OUTCOMES = ("ok", "failed", "refused", "timed_out")
# What the model is handed of one result, at most (the trace keeps `redact.RESULT_CHARS`).
MAX_MODEL_CHARS = 8_000
LATE = "late"


class RelayRefusal(Exception):
    def __init__(self, http: int, code: str, detail: str):
        super().__init__(detail)
        self.http, self.code, self.detail = http, code, detail


@dataclass
class Recorded:
    """What a result post answers: `resume` (stream the rest), `waiting_for` (202), or `status`
    (200, recorded, no stream: `late`, `cancelled`, or the turn's own status)."""

    resume: dict | None = None
    waiting_for: list[str] = field(default_factory=list)
    status: str = ""
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


def _deadline_ms(relay_row: dict, request: dict) -> int:
    return int(relay_row["asked_at_ms"]) + int(request["deadline_ms"]) + relay.GRACE_MS


def _owned(request, turn_id):
    return AgentTurn.objects.select_for_update().filter(
        pk=turn_id, household=household_of(request), owner_user=request.user
    )


def record_result(request, turn_id, body: dict) -> Recorded:
    """Records one call's result (module docstring). `body` is the validated post."""
    with transaction.atomic():
        turn = _owned(request, turn_id).first()
        if turn is None:
            raise RelayRefusal(404, "no_turn", "No turn of yours with that id; nothing recorded.")
        paused = turn.pending_relay
        if paused is None:
            return _after_the_pause(turn, request, body)
        if paused.get("device_id") != getattr(request.auth, "pk", None):
            raise RelayRefusal(404, "no_turn", "No turn of yours with that id; nothing recorded.")
        asked = {item["request"]["call_id"]: item["request"] for item in paused["loop"]["relay"]}
        call_id = body["call_id"]
        if call_id not in asked:
            raise RelayRefusal(
                409, "unknown_call", "That call id was not asked of this device; nothing recorded."
            )
        if body.get("name") and body["name"] != asked[call_id]["name"]:
            raise RelayRefusal(
                409,
                "wrong_tool",
                f"Call {call_id} asked for {asked[call_id]['name']}, not {body['name']}; "
                "nothing recorded.",
            )
        store = TurnStore(turn)
        results = paused.setdefault("results", {})
        fresh: dict = {}
        now = _now_ms()
        if call_id in results:
            _maybe_late_ok(turn, call_id, results[call_id], body)
        else:
            late = now > _deadline_ms(paused, asked[call_id])
            fresh[call_id] = _result_row(body, late)
            results[call_id] = _kept(asked[call_id], fresh[call_id])
            _trace(store, asked[call_id], fresh[call_id], body)
        for cid, req in asked.items():
            if cid not in results and now > _deadline_ms(paused, req):
                results[cid] = {"outcome": "timed_out", "words": "", "by": "engine"}
                _trace(store, req, results[cid], None)
        if turn.status != TURN_AWAITING_LIMB:
            # Another post already resumed it: it is running, and its own loop sees any cancel.
            # Nothing is saved here: the running turn owns the row now.
            return Recorded(status=turn.status)
        if turn.cancel_requested:
            _close(turn, paused, TURN_CANCELLED, CANCELLED_WORDS)
            return Recorded(status=TURN_CANCELLED)
        open_calls = [cid for cid in asked if cid not in results]
        turn.pending_relay = paused
        if open_calls:
            turn.save(update_fields=["pending_relay"])
            return Recorded(waiting_for=open_calls)
        turn.status = TURN_RUNNING
        turn.save(update_fields=["pending_relay", "status"])
        return Recorded(resume=paused, fresh=fresh)


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


def _maybe_late_ok(turn, call_id: str, recorded: dict, body: dict) -> None:
    """The one second result the wire allows: `ok` after `timed_out` (it landed late)."""
    if recorded.get("outcome") == "timed_out" and body["outcome"] == "ok":
        recorded["landed_late"] = True
        turn.tool_calls.filter(call_id=call_id, outcome="timed_out").update(outcome="ok")


def _after_the_pause(turn: AgentTurn, request, body: dict) -> Recorded:
    """A post for a turn no longer paused: a call it asked is recorded late (the `ok` after a
    `timed_out` replaces it); anything else is unknown."""
    rows = turn.tool_calls.filter(call_id=body["call_id"]).exclude(where="engine")
    row = rows.first()
    if row is None or not _same_device(row, request):
        raise RelayRefusal(
            409, "unknown_call", "That call id was not asked of this device; nothing recorded."
        )
    if row.outcome == "timed_out" and body["outcome"] == "ok":
        rows.update(outcome="ok")
    return Recorded(status=LATE if turn.status in (TURN_FAILED, "done") else turn.status)


def _same_device(row, request) -> bool:
    limb = relay.limb_of(request)
    return limb is not None and str(limb.pk) == row.where


def _trace(store: TurnStore, req: dict, result: dict, body: dict | None) -> None:
    outside = req.get("read_through", False)
    store.tool_call(
        None,
        call_id=req["call_id"],
        tool=req["name"],
        writes=bool(req.get("writes")),
        args=redact.redact_args(req["name"], req.get("args") or {}),
        result=redact.trim_result(req["name"], _model_text(result), outside=outside),
        outcome=result["outcome"],
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
    """What the model is handed for one relayed call."""
    if result["outcome"] == "timed_out" or result.get("late"):
        return ToolOutcome(
            name=req["name"],
            ok=False,
            response=model_response(False, relay.TIMED_OUT_WORDS),
            writes=bool(req.get("writes")),
            outcome="timed_out",
        )
    ok = result["outcome"] == "ok"
    return ToolOutcome(
        name=req["name"],
        ok=ok,
        response=model_response(ok, _model_text(result) or ("Done." if ok else "It did not run.")),
        writes=bool(req.get("writes")),
        outcome=result["outcome"],
    )


def resume(request, turn: AgentTurn, paused: dict, model, *, context, fresh=None):
    """The rest of a paused turn, as events: `turn` (same ids, the next `stream_no`), the relayed
    calls' `tool` events, then the turn as any other. `context(request, store, started)` builds the
    turn's `NodeContext` (`turn._context`)."""
    started = time.monotonic() - float(paused["loop"].get("elapsed_s") or 0.0)
    state = TurnState.from_json(paused["state"])
    outcomes = []
    for item in paused["loop"]["relay"]:
        req = item["request"]
        result = (fresh or {}).get(req["call_id"]) or paused["results"][req["call_id"]]
        outcome = outcome_of(req, result)
        outcomes.append(outcome)
        if req.get("read_through"):
            # Its result is outside text now in the turn: by class, whatever the tool's name.
            state.add_outside(req["name"])
        if outcome.outcome == "timed_out" and req.get("writes"):
            done_before = state.client_facts.setdefault("relay_timed_out_writes", [])
            if req["name"] not in done_before:
                done_before.append(req["name"])
    state.pending_relay = {"loop": paused["loop"]}
    store = TurnStore(turn)
    ctx: NodeContext = context(request, store, started)
    ctx.relay_results = outcomes
    yield ev.Event(
        "turn",
        {
            "turn_id": str(turn.pk),
            "conversation_id": str(turn.conversation_id),
            "seq": turn.seq,
            "stream_no": int(paused.get("stream_no") or 2),
        },
    )
    first_sentence_ms = None
    gen = turn_graph().run(state, ctx, store, from_node="agent")
    try:
        while True:
            event = next(gen)
            if event.type == "sentence" and first_sentence_ms is None:
                first_sentence_ms = int((time.monotonic() - started) * 1000)
            yield event
    except StopIteration as stop:
        state = stop.value
    store.finish(
        state,
        latency_ms=int((time.monotonic() - started) * 1000),
        first_sentence_ms=turn.first_sentence_ms or first_sentence_ms,
    )
    return state


def close_paused(conversation, before_seq: int) -> int:
    """Closes this conversation's turns still paused on a limb (his next turn began): failed,
    open calls timed out, history made whole. Returns how many."""
    closed = 0
    for pk in conversation.turns.filter(status=TURN_AWAITING_LIMB, seq__lt=before_seq).values_list(
        "pk", flat=True
    ):
        with transaction.atomic():
            turn = (
                AgentTurn.objects.select_for_update()
                .filter(pk=pk, status=TURN_AWAITING_LIMB)
                .first()
            )
            if turn is None or turn.pending_relay is None:
                continue
            paused = turn.pending_relay
            store = TurnStore(turn)
            results = paused.setdefault("results", {})
            for item in paused["loop"]["relay"]:
                req = item["request"]
                if req["call_id"] not in results:
                    results[req["call_id"]] = {"outcome": "timed_out", "words": "", "by": "engine"}
                    _trace(store, req, results[req["call_id"]], None)
            _close(turn, paused, TURN_FAILED, relay.TIMED_OUT_WORDS)
            closed += 1
    return closed


def _close(turn: AgentTurn, paused: dict, status: str, words: str) -> None:
    """Ends a paused turn with no further model round. Its history is the state's messages plus
    the round's responses: a limb call's own result where one came (a write the device made is on
    the record), `words` for the rest."""
    state = TurnState.from_json(paused["state"])
    suspension = Suspension.from_json(paused["loop"])
    results = paused.get("results") or {}
    answered = {}
    wrote = False
    for item in suspension.relay:
        req = item["request"]
        if req["call_id"] in results:
            outcome = outcome_of(req, results[req["call_id"]])
            answered[req["call_id"]] = outcome.response
            wrote = wrote or (outcome.ok and outcome.writes)
    state.messages.append(abandoned_round(suspension, words, answered))
    turn.status = status
    turn.pending_relay = None
    turn.writes_ran = turn.writes_ran or state.writes_ran or wrote
    turn.final_text = " ".join(suspension.spoken).strip() or words
    turn.history = redact.history_for_storage(
        state.messages[state.client_facts.get("history_from", 0) :]
    )
    turn.save(update_fields=["status", "pending_relay", "writes_ran", "final_text", "history"])
