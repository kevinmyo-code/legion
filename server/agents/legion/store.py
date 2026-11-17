"""The Postgres side of a turn: the conversation and turn rows, a checkpoint after every node, one
row per model round and per tool call (plan sections 13.5 and 15).

Every row carries the turn's `household` and `owner_user`, so each table is member-private by the
same one-column rule. Content goes through `redact.py` first: a mail tool's arguments and result
are never written, here or in the history a later turn reads.

A failed trace write never costs the person their answer (`llm/client.record`'s rule), with one
exception: the taint mark. If it cannot be saved, `mark_taint` says so and the gateway refuses the
third-party tool (fail closed, ADR 0090).
"""

from __future__ import annotations

import logging

from django.db import DatabaseError, transaction
from django.utils import timezone

from agents.legion import redact
from agents.models import (
    AgentCheckpoint,
    AgentConversation,
    AgentModelCall,
    AgentToolCall,
    AgentTurn,
)

log = logging.getLogger(__name__)


def _safe(what: str):
    def wrap(fn):
        def inner(*args, **kwargs):
            try:
                with transaction.atomic():
                    return fn(*args, **kwargs)
            except DatabaseError:
                log.warning("agents: %s not written", what, exc_info=True)
                return None

        return inner

    return wrap


class TurnStore:
    """One turn's rows. `turn` is the `AgentTurn` this store writes under."""

    def __init__(self, turn: AgentTurn, agent: str = "generalist"):
        self.turn = turn
        self.agent = agent
        self._owner = {"household_id": turn.household_id, "owner_user_id": turn.owner_user_id}

    # The graph's checkpointer.
    @_safe("checkpoint")
    def save(self, state, node: str, step: int, events) -> None:
        raw = state.to_json()
        raw["messages"] = redact.history_for_storage(raw.get("messages") or [])
        AgentCheckpoint.objects.create(
            turn=self.turn,
            step=step,
            node=node,
            state=raw,
            events=[e.to_json() for e in events],
            **self._owner,
        )

    @_safe("model call")
    def model_call(self, row: dict) -> None:
        AgentModelCall.objects.create(
            turn=self.turn,
            node="agent",
            agent=self.agent,
            model=row["model"],
            round=row["round"],
            prompt_tokens=row["prompt_tokens"],
            cached_tokens=row["cached_tokens"],
            output_tokens=row["output_tokens"],
            first_token_ms=row["first_token_ms"],
            total_ms=row["total_ms"],
            cost_micro_usd=row["cost_micro_usd"],
            finish_reason=(row["finish_reason"] or "")[:200],
            **self._owner,
        )

    @_safe("tool call")
    def tool_call(self, state, **row) -> None:
        AgentToolCall.objects.create(
            turn=self.turn,
            call_id=row["call_id"] or "",
            tool=row["tool"],
            writes=row["writes"],
            args_redacted=row["args"],
            result_trimmed=row["result"],
            outcome=row["outcome"],
            guard=row["guard"],
            ms=row["ms"],
            **self._owner,
        )

    def mark_taint(self, state) -> bool:
        try:
            with transaction.atomic():
                AgentTurn.objects.filter(pk=self.turn.pk).update(
                    tainted=True, taint_sources=list(state.taint)
                )
            return True
        except DatabaseError:
            log.warning("agents: taint not saved; refusing the tool", exc_info=True)
            return False

    def cancelled(self) -> bool:
        try:
            return AgentTurn.objects.filter(pk=self.turn.pk, cancel_requested=True).exists()
        except DatabaseError:
            return False

    @_safe("turn")
    def finish(self, state, *, latency_ms: int, first_sentence_ms: int | None) -> None:
        status = (
            state.status if state.status in ("done", "cancelled", "failed", "refused") else "failed"
        )
        AgentTurn.objects.filter(pk=self.turn.pk).update(
            route_agents=list((state.route or {}).get("agents") or []),
            route_reason=(state.route or {}).get("reason", ""),
            status=status,
            tainted=state.tainted,
            taint_sources=list(state.taint),
            writes_ran=state.writes_ran,
            final_text=state.final_text or state.words,
            honesty_flag=state.honesty_flag,
            latency_ms=latency_ms,
            first_sentence_ms=first_sentence_ms,
            cost_micro_usd=state.budget.cost_micro_usd,
            history=redact.history_for_storage(
                state.messages[state.client_facts.get("history_from", 0) :]
            ),
        )
        AgentConversation.objects.filter(pk=self.turn.conversation_id).update(
            last_turn_at=timezone.now(), pending_confirm=state.pending_confirm
        )
