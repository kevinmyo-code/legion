"""engine-brain ticket 04, slice E1: the `Limb` table, `AgentTurn.pending_relay`, the widened
turn status, the limb contract's manifest upload and the warm route. All of it DARK: the routes
answer 404 "switched off" unless `LEGION_AGENTS_TURN=on`, like the turn endpoint.

Every device id, version and tool below is invented.
"""

from __future__ import annotations

import uuid

import pytest
from django.db import IntegrityError, connection, transaction

from agents.models import TURN_AWAITING_LIMB, AgentConversation, AgentTurn, Limb

pytestmark = pytest.mark.django_db


def _conversation(household, user) -> AgentConversation:
    return AgentConversation.objects.create(household=household, owner_user=user, client="phone")


# ---- the schema (migration agents 0002) -------------------------------------------------------


def test_a_turn_may_wait_for_a_limb_and_hold_its_pending_relay(household_a, household_user):
    conversation = _conversation(household_a, household_user)
    turn = AgentTurn.objects.create(
        household=household_a,
        owner_user=household_user,
        conversation=conversation,
        seq=1,
        transcript="set a timer for five minutes",
        status=TURN_AWAITING_LIMB,
        pending_relay={"call_id": "c-1", "name": "set_timer"},
    )
    turn.refresh_from_db()
    assert turn.status == "awaiting_limb" and turn.pending_relay["call_id"] == "c-1"


def test_a_turn_status_outside_the_list_is_still_refused(household_a, household_user):
    conversation = _conversation(household_a, household_user)
    with pytest.raises(IntegrityError), transaction.atomic():
        AgentTurn.objects.create(
            household=household_a,
            owner_user=household_user,
            conversation=conversation,
            seq=1,
            transcript="x",
            status="paused",
        )


def test_the_previous_revisions_turn_insert_still_works_on_the_new_schema(
    household_a, household_user
):
    """Rollback proof: the revision before E1 (d79cdbe1) inserts `agents_turn` rows naming only
    the columns it knows. On the new schema that insert still works and `pending_relay` is NULL,
    so `update-traffic` back to it needs no backwards migration."""
    conversation = _conversation(household_a, household_user)
    old_columns = (
        "id, household_id, owner_user_id, conversation_id, seq, transcript, route_agents, "
        "route_reason, status, cancel_requested, tainted, taint_sources, writes_ran, final_text, "
        "honesty_flag, cost_micro_usd, graph_version, history"
    )
    turn_id = uuid.uuid4()
    with connection.cursor() as cursor:
        cursor.execute(
            f"insert into public.agents_turn ({old_columns}) values "
            "(%s, %s, %s, %s, 1, 'old revision', '[]', '', 'done', false, false, '[]', false, "
            "'', false, 0, '', '[]')",
            [turn_id, household_a.id, household_user.pk, conversation.pk],
        )
    turn = AgentTurn.objects.get(pk=turn_id)
    assert turn.pending_relay is None and turn.status == "done"


def _token(user, name="Invented phone"):
    from household.models import DeviceToken

    token, _raw = DeviceToken.issue(user, name)
    return token


def test_one_limb_row_per_device(household_a, household_user):
    token = _token(household_user)
    Limb.objects.create(
        household=household_a,
        owner_user=household_user,
        device=token,
        kind="phone",
        app_version="2026-01-01-abc1234",
        contract_version=1,
    )
    with pytest.raises(IntegrityError), transaction.atomic():
        Limb.objects.create(
            household=household_a,
            owner_user=household_user,
            device=token,
            kind="phone",
            app_version="2026-01-02-abc1234",
            contract_version=1,
        )


def test_a_limb_kind_or_brain_choice_outside_the_list_is_refused(household_a, household_user):
    token = _token(household_user)
    with pytest.raises(IntegrityError), transaction.atomic():
        Limb.objects.create(
            household=household_a,
            owner_user=household_user,
            device=token,
            kind="toaster",
            app_version="1",
            contract_version=1,
        )
    with pytest.raises(IntegrityError), transaction.atomic():
        Limb.objects.create(
            household=household_a,
            owner_user=household_user,
            device=token,
            kind="phone",
            app_version="1",
            contract_version=1,
            brain_choice="cloud",
        )
