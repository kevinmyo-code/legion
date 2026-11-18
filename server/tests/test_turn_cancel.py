# ruff: noqa: F811 - fixtures imported from the cascade tests
"""Interrupt and the turn slot (2026-11-17, the browser/server contract): every refusal of the turn
route carries a `code`; `busy` (the caller's own earlier turn still holds its slot) is told apart
from `time_limit`; a turn can be stopped by its `turn_id` from any worker; a stopped turn starts no
further paid call and releases its slot; the next turn waits briefly for the caller's own earlier
one. Google is never called; every word is invented."""

from __future__ import annotations

import threading
import time
import uuid
from datetime import timedelta

import pytest
from django.db import connections

from agents.runtime.model import FakeModel, ModelFailure, call, say
from assistant import cascade, turn_slots, views
from assistant.models import TurnCancel
from tests.test_assistant_cascade import (  # noqa: F401 - fixtures
    PCM,
    _clean,
    _events,
    _of,
    _post,
    _record,
    engine,
    member,
    visitor,
    visitor_user,
)

pytestmark = pytest.mark.django_db


def _hold_user_lock(user, seconds: float) -> threading.Thread:
    """Another worker's turn for the same caller, holding the caller's slot for `seconds`."""
    ready = threading.Event()

    def run():
        conn = connections.create_connection("default")
        try:
            with conn.cursor() as cur:
                key = turn_slots._key(f"user:{user.pk}")
                cur.execute("SELECT pg_advisory_lock(%s, %s)", [turn_slots._NS_USER, key])
                ready.set()
                time.sleep(seconds)
                cur.execute("SELECT pg_advisory_unlock(%s, %s)", [turn_slots._NS_USER, key])
        finally:
            conn.close()

    thread = threading.Thread(target=run)
    thread.start()
    ready.wait(5)
    return thread


def test_every_refusal_carries_a_code_and_busy_is_not_the_time_limit(
    visitor, visitor_user, engine, monkeypatch
):
    monkeypatch.setattr(turn_slots, "OWN_TURN_WAIT_S", 0.2)
    holder = _hold_user_lock(visitor_user, 1.5)
    try:
        busy = _post(visitor, text="Hi.")
    finally:
        holder.join()
    assert busy.status_code == 409
    assert busy.data["code"] == "busy" and busy.data["retry_ms"] == views.BUSY_RETRY_MS

    engine.model = FakeModel([say("Hello.")])
    conversation = _of(_events(_post(visitor, text="Hi.")), "turn")[0]["conversation_id"]
    from devicelogs.models import DeviceLog

    row = DeviceLog.objects.get(batch_id=conversation)
    row.window_start = row.window_start - timedelta(hours=1)
    row.save(update_fields=["window_start"])
    late = _post(visitor, text="Still there?", conversation_id=conversation)
    assert late.status_code == 409 and late.data["code"] == "time_limit"

    unknown = _post(visitor, text="Hi.", conversation_id=str(uuid.uuid4()))
    assert unknown.status_code == 404 and unknown.data["code"] == "no_conversation"
    bad = _post(visitor)
    assert bad.status_code == 400 and bad.data["code"] == "bad_request"
    for code in ("busy", "time_limit", "demo_full", "budget", "throttled", "no_conversation"):
        assert code in views.TURN_REFUSAL_CODES


def test_a_second_turn_right_after_an_interrupt_waits_for_the_first_and_is_accepted(
    visitor, visitor_user, engine
):
    # The earlier turn takes half a second to notice its cancel and let go.
    holder = _hold_user_lock(visitor_user, 0.5)
    try:
        engine.model = FakeModel([say("Yes?")])
        response = _post(visitor, text="Actually, one more thing.")
        assert response.status_code == 200
        assert [s["text"] for s in _of(_events(response), "sentence")] == ["Yes?"]
    finally:
        holder.join()


def test_a_turn_cancelled_mid_round_makes_no_further_paid_call_and_releases_its_slot(
    visitor, visitor_user, engine
):
    turn_id = uuid.uuid4()

    class CancelledDuringRound(FakeModel):
        def stream(self, req):
            # The cancel arrives while the first round streams (another worker wrote it).
            TurnCancel.objects.create(turn_id=turn_id, user=visitor_user)
            yield from super().stream(req)

    engine.model = CancelledDuringRound(
        [call("next_service", {}), say("This answer is never asked for.")]
    )
    engine.speech.spoken.clear()
    events = _events(_post(visitor, audio=PCM, turn_id=str(turn_id)))
    assert len(engine.model.requests) == 1  # no second round
    assert [t for t in _of(events, "tool") if t["ok"]] == []  # the chosen tool did not run
    assert engine.speech.spoken == []  # and nothing was spoken
    assert _of(events, "done")[0]["status"] == "cancelled"
    other = connections.create_connection("default")
    try:
        with other.cursor() as cur:
            key = turn_slots._key(f"user:{visitor_user.pk}")
            cur.execute("SELECT pg_try_advisory_lock(%s, %s)", [turn_slots._NS_USER, key])
            assert cur.fetchone()[0] is True
    finally:
        other.close()


def test_cancelling_someone_elses_or_an_unknown_turn_is_a_harmless_204(member, visitor, engine):
    turn_id = uuid.uuid4()
    # The visitor names the member's turn id: their row stops only their own turns.
    assert (
        visitor.post(
            "/api/assistant/turn/cancel", {"turn_id": str(turn_id)}, format="json"
        ).status_code
        == 204
    )
    engine.model = FakeModel([say("Your day is clear.")])
    events = _events(_post(member, text="What's on?", turn_id=str(turn_id)))
    assert [s["text"] for s in _of(events, "sentence")] == ["Your day is clear."]
    again = visitor.post("/api/assistant/turn/cancel", {"turn_id": str(turn_id)}, format="json")
    assert again.status_code == 204
    assert (
        member.post("/api/assistant/turn/cancel", {"turn_id": "nope"}, format="json").data["code"]
        == "bad_request"
    )


def test_a_cancelled_turn_gives_back_what_it_did_not_use(visitor, visitor_user, engine):
    from demo.models import DemoBudgetDay

    turn_id = uuid.uuid4()
    TurnCancel.objects.create(turn_id=turn_id, user=visitor_user)
    engine.model = FakeModel([say("Never asked.")])
    _events(_post(visitor, text="Hi.", turn_id=str(turn_id)))
    assert engine.model.requests == []
    assert DemoBudgetDay.objects.get().reserved_cents == 0


def test_a_turn_with_no_turn_id_is_never_cancelled(member, engine):
    check = cascade.CancelCheck(1, None)
    assert check() is False and check.often() is False
    assert ModelFailure  # imported for the module's own use


def test_a_resent_turn_id_is_answered_once_and_charged_once(visitor, engine):
    from demo.models import DemoBudgetDay

    turn_id = str(uuid.uuid4())
    engine.model = FakeModel([say("Hello.")])
    _events(_post(visitor, text="Hi.", turn_id=turn_id))
    charged = DemoBudgetDay.objects.get().reserved_cents
    again = _post(visitor, text="Hi.", turn_id=turn_id)
    assert again.status_code == 409 and again.data["code"] == "duplicate"
    assert DemoBudgetDay.objects.get().reserved_cents == charged
    assert len(engine.model.requests) == 1


def test_a_busy_turn_resent_with_its_id_runs_once_its_slot_is_free(
    visitor, visitor_user, engine, monkeypatch
):
    monkeypatch.setattr(turn_slots, "OWN_TURN_WAIT_S", 0.1)
    turn_id = str(uuid.uuid4())
    holder = _hold_user_lock(visitor_user, 0.6)
    try:
        assert _post(visitor, text="Hi.", turn_id=turn_id).data["code"] == "busy"
    finally:
        holder.join()
    engine.model = FakeModel([say("Hello.")])
    events = _events(_post(visitor, text="Hi.", turn_id=turn_id))
    assert [s["text"] for s in _of(events, "sentence")] == ["Hello."]
