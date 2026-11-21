"""The rest of a turn that paused on a limb (the relay, ADR 0098; slice E2): the continuation
stream a completing `tool_result` post answers with (`relay_turn.record_result` claimed it).

**Crash-safe.** The results and the write facts were recorded before this stream began
(`record_result`). If the stream stops before the turn ends (a hang-up closes the generator, an
error), the turn is finished here at once as failed, with what is known: the pause's history and
its recorded results if the model never got them, else the turn's own messages made whole
(`relay_turn.made_whole`), and `writes_ran` never lowered. A process that dies takes no `finally`
with it: `relay_turn.close_stale` closes such a turn on the member's next request.
"""

from __future__ import annotations

import time

from django.db import transaction

from agents.graph.nodes import NodeContext, turn_graph
from agents.graph.state import TurnState
from agents.legion import relay_turn
from agents.legion.store import TurnStore
from agents.models import TURN_FAILED, TURN_RUNNING, AgentTurn
from agents.runtime import events as ev


def resume(request, turn: AgentTurn, paused: dict, model, *, context, fresh=None):
    """Events: `turn` (same ids, the next `stream_no`), the relayed calls' `tool` events, then the
    turn as any other. `context(request, store, started)` builds the turn's `NodeContext`
    (`turn.context`)."""
    started = time.monotonic() - float(paused["loop"].get("elapsed_s") or 0.0)
    state = TurnState.from_json(paused["state"])
    outcomes = []
    for item in paused["loop"]["relay"]:
        req = item["request"]
        result = (fresh or {}).get(req["call_id"]) or paused["results"][req["call_id"]]
        outcome = relay_turn.outcome_of(req, result)
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
    first_sentence_ms = None
    gen = turn_graph().run(state, ctx, store, from_node="agent")
    try:
        yield ev.Event(
            "turn",
            {
                "turn_id": str(turn.pk),
                "conversation_id": str(turn.conversation_id),
                "seq": turn.seq,
                "stream_no": int(paused.get("stream_no") or 2),
            },
        )
        while True:
            try:
                event = next(gen)
            except StopIteration as stop:
                state = stop.value
                break
            if event.type == "sentence" and first_sentence_ms is None:
                first_sentence_ms = int((time.monotonic() - started) * 1000)
            yield event
    except BaseException:
        gen.close()
        _dropped(turn.pk, state, started)
        raise
    store.finish(
        state,
        latency_ms=int((time.monotonic() - started) * 1000),
        first_sentence_ms=turn.first_sentence_ms or first_sentence_ms,
    )
    return state


def _dropped(turn_pk, state: TurnState, started: float) -> None:
    """The continuation stopped before its end: finish the turn now, failed, with what is known.
    Nothing when it already paused again or ended (its row is no longer `running`)."""
    with transaction.atomic():
        turn = AgentTurn.objects.select_for_update().filter(pk=turn_pk, status=TURN_RUNNING).first()
        if turn is None:
            return
        if state.pending_relay and "loop" in (turn.pending_relay or {}):
            # The model never got the limb's results: close from the pause, results included.
            relay_turn._close(turn, turn.pending_relay, TURN_FAILED, relay_turn.DROPPED_WORDS)
            return
        state.messages = relay_turn.made_whole(state.messages, relay_turn.DROPPED_WORDS)
        state.status = TURN_FAILED
        state.words = state.words or relay_turn.DROPPED_WORDS
        TurnStore(turn).finish(
            state, latency_ms=int((time.monotonic() - started) * 1000), first_sentence_ms=None
        )
