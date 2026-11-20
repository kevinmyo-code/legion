"""One agent turn on the engine: LEGION's wiring around the reusable graph.

`run_turn` is a generator of `Event`s, used by the dark endpoint (`agents/views.py`) and the eval
runner (`agents/evals/runner.py`) alike, so the eval measures the code that would answer.

What it adds around the graph:
- the conversation and turn rows, and the history of the conversation's earlier turns;
- a bracketed context line ahead of what the person said: the household's clock, today and
  tomorrow (plan section 4.3: per-turn context stays out of the cacheable prefix);
- the route: one spec (the generalist) in this stage; a recruiter or a member over the daily cap
  (`llm/cap.py`) is refused at the route and nothing calls the model;
- the system prompt: the locked text from code (`agents/specs/locked.py`), then the spec's own;
- spend: each model round is one `llm_usage` row (`feature="agent_turn"`), so the member cap sees
  agent spend, plus an `agents_model_call` row with the numbers.
"""

from __future__ import annotations

import hashlib
import time
from datetime import datetime, timedelta
from functools import cache
from pathlib import Path

from django.db.models import Max
from django.utils import timezone as dj_timezone

from agents.graph.nodes import NodeContext, turn_graph
from agents.graph.state import TurnState
from agents.legion import guards, late
from agents.legion.gateway import LegionGateway
from agents.legion.retention import prune
from agents.legion.specs import spec as spec_named
from agents.legion.store import TurnStore
from agents.models import AgentConversation, AgentTurn
from agents.runtime import events as ev
from agents.runtime.loop import LoopHooks
from agents.runtime.model import ModelClient, Usage
from agents.specs.locked import LOCKED
from assistant.prompt import member_fact, time_fact
from headunit_signin import scope as headunit_scope
from household.models import is_recruiter
from household.tenancy import household_of
from household.timezones import utc_offset_minutes, zone_named
from llm import cap as member_cap
from llm import client as llm_client

FEATURE = "agent_turn"
RECRUITER_REFUSAL = (
    "Nothing was answered: the engine's agent turn is not open to a demo visit. The demo's "
    "assistant has its own door."
)
# Per-token prices in hundredths of a micro-dollar: `llm/client.py`'s list prices, and the cached
# rate the repo quotes for flash-lite ($0.03 / 1M, plan section 7). ESTIMATES.
CACHED_MICRO_USD_PER_TOKEN_X100 = 3

_AGENTS_DIR = Path(__file__).resolve().parent.parent


@cache
def graph_version() -> str:
    """A short hash of `server/agents` (code and specs): what a conversation pins (plan 17)."""
    digest = hashlib.sha256()
    for path in sorted(_AGENTS_DIR.rglob("*")):
        if (
            path.is_file()
            and path.suffix in (".py", ".yaml", ".md", ".lock")
            and ("evals" not in path.parts and "migrations" not in path.parts)
        ):
            digest.update(path.relative_to(_AGENTS_DIR).as_posix().encode())
            digest.update(path.read_bytes().replace(b"\r\n", b"\n"))
    return digest.hexdigest()[:12]


def cost_of(usage: Usage) -> int:
    uncached = max(usage.prompt_tokens - usage.cached_tokens, 0)
    hundredths = (
        uncached * llm_client.INPUT_MICRO_USD_PER_TOKEN_X100
        + usage.cached_tokens * CACHED_MICRO_USD_PER_TOKEN_X100
        + usage.output_tokens * llm_client.OUTPUT_MICRO_USD_PER_TOKEN_X100
    )
    return -(-hundredths // 100)


def context_line(request, now: datetime) -> str:
    household = household_of(request)
    zone_name = getattr(household, "timezone", None)
    zone = zone_named(zone_name)
    offset = utc_offset_minutes(zone_name, now) if zone is not None else None
    local = now.astimezone(zone) if zone is not None else now
    today = local.date()
    tomorrow = today + timedelta(days=1)
    # T3b review B4: a head unit's turn is not told the member's name; whoever holds the unit may
    # not be him. The clock stays.
    first = "" if headunit_scope.is_headunit(request) else getattr(request.user, "first_name", "")
    first = first or ""
    clock = time_fact(now, offset, from_household=True)
    return (
        f"[Context, not said by the person: {clock} Today is {today:%A} {today.isoformat()}; "
        f"tomorrow is {tomorrow:%A} {tomorrow.isoformat()}. {member_fact(first)}]"
    )


def system_for(spec, state) -> str:
    return f"{LOCKED}\n\n{spec.prompt}"


def route(request, agent: str = "generalist"):
    def decide(state: TurnState) -> dict:
        if is_recruiter(request.user):
            return {"agents": [], "reason": "recruiter", "refusal": RECRUITER_REFUSAL}
        if member_cap.over_cap(request.user):
            return {"agents": [], "reason": "member_cap", "refusal": member_cap.USED_UP}
        return {"agents": [agent], "reason": "single_spec"}

    return decide


def open_conversation(request, conversation_id=None, client: str = "phone") -> AgentConversation:
    """The member's own conversation `conversation_id`, or a new one. Another member's id (or
    another household's) is never found: it starts a new conversation instead."""
    household = household_of(request)
    unit = headunit_scope.is_headunit(request)
    if unit:
        # T3b: a head unit's token keeps to the unit's own conversations: never the member's
        # phone or web history, whatever id it sends.
        client = headunit_scope.TURN_CLIENT
    if conversation_id:
        mine = AgentConversation.objects.filter(
            pk=conversation_id, household=household, owner_user=request.user
        )
        found = (mine.filter(client=client) if unit else mine).first()
        if found is not None:
            return found
    return AgentConversation.objects.create(
        household=household, owner_user=request.user, client=client, graph_version=graph_version()
    )


def run_turn(
    request,
    transcript: str,
    model: ModelClient,
    *,
    conversation: AgentConversation,
    now: datetime | None = None,
    do_prune: bool = True,
    observe=None,
    agent: str = "generalist",
    feature: str = FEATURE,
):
    """Yields the turn's events; returns its final `TurnState`. `agent` and `feature` are the
    spec that answers and the `llm_usage` feature it is metered under: the late-answer runner
    (`late.py`) passes its read-only `background` spec and its own feature."""
    started = time.monotonic()
    now = now or dj_timezone.now()
    if do_prune:
        prune()
    seq = (conversation.turns.aggregate(m=Max("seq"))["m"] or 0) + 1
    turn = AgentTurn.objects.create(
        household_id=conversation.household_id,
        owner_user_id=conversation.owner_user_id,
        conversation=conversation,
        seq=seq,
        transcript=transcript,
        graph_version=graph_version(),
    )
    store = TurnStore(turn)
    history: list[dict] = []
    for earlier in conversation.turns.filter(seq__lt=seq).order_by("seq"):
        history.extend(earlier.history or [])
    conversation.refresh_from_db(fields=["pending_confirm"])
    pending = conversation.pending_confirm
    if pending and int(pending.get("until_seq", 0)) < seq:
        pending = None
    user_content = {
        "role": "user",
        "parts": [{"text": context_line(request, now)}, {"text": transcript}],
    }
    state = TurnState(
        turn_id=str(turn.pk),
        conversation_id=str(conversation.pk),
        seq=seq,
        household_id=str(conversation.household_id),
        member_id=str(conversation.owner_user_id),
        client=conversation.client,
        transcript=transcript,
        client_facts={"history_from": len(history)},
        messages=[*history, user_content],
        pending_confirm=pending,
        graph_version=graph_version(),
    )
    yield ev.Event(
        "turn", {"turn_id": str(turn.pk), "conversation_id": str(conversation.pk), "seq": seq}
    )
    if agent == "generalist" and not pending:
        # The late-answer path (dark unless LEGION_LATE_ANSWERS=on). Not while a write waits for
        # his yes: that turn is his answer to it ("cancel that" then drops the write, as today).
        # Code's own answer to "look into X and tell me later", "anything pending?" or "cancel
        # that", with no model round; else the one line owed from earlier, said first and kept
        # out of this turn's model context.
        handled = late.intercept(request, transcript, conversation.client)
        if handled is not None:
            yield from _code_answer(state, store, handled, started)
            return state
        line, more = late.claim(request, conversation.client)
        if line is not None:
            yield late.late_event(line, more)

    def on_model_call(row: dict) -> None:
        store.model_call(row)
        llm_client.record(
            household=request.user.household,
            feature=feature,
            model=row["model"],
            user=request.user,
            usage={
                "prompt_tokens": row["prompt_tokens"],
                "output_tokens": row["output_tokens"],
                "total_tokens": (row["prompt_tokens"] or 0) + (row["output_tokens"] or 0),
            },
            cost_estimate_micro_usd=row["cost_micro_usd"],
        )

    ctx = NodeContext(
        model=model,
        gateway=LegionGateway(request, recorder=store, observe=observe),
        route=route(request, agent),
        spec=spec_named,
        system_for=system_for,
        hooks=LoopHooks(
            cancelled=store.cancelled, cost_of=cost_of, on_model_call=on_model_call, started=started
        ),
        is_yes=guards.is_yes,
        honesty=lambda s: guards.claims_unbacked_outcome(s.final_text, s.actions),
    )
    first_sentence_ms = None
    gen = turn_graph().run(state, ctx, store)
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
        first_sentence_ms=first_sentence_ms,
    )
    return state


def _code_answer(state: TurnState, store: TurnStore, words: str, started: float):
    """A turn answered by code alone: one sentence, `done`, the turn row finished. The words join
    the conversation's history as the model's, so the next turn's model knows what was said."""
    state.route = {"agents": [], "reason": "late_answer"}
    state.status = "done"
    state.final_text = words
    state.messages = [*state.messages, {"role": "model", "parts": [{"text": words}]}]
    yield ev.sentence(words)
    yield ev.done("done", words, False, honesty_flag=False, pending_confirm=False)
    store.finish(
        state, latency_ms=int((time.monotonic() - started) * 1000), first_sentence_ms=0
    )


def cancel(request, turn_id) -> bool:
    """Marks the member's own running turn cancelled; checked between rounds and before every
    call (plan section 4.2). False when there is no such turn of theirs."""
    turns = AgentTurn.objects.filter(
        pk=turn_id, household=household_of(request), owner_user=request.user
    )
    if headunit_scope.is_headunit(request):
        turns = turns.filter(conversation__client=headunit_scope.TURN_CLIENT)
    return bool(turns.update(cancel_requested=True))
