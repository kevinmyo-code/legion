"""Each finished turn is saved as it happens (memory chain ticket 450, CLAUDE.md sections 4 and 7).

One row is one TURN: the person's own words and the NAMES of the tools it ran, nothing else. The
assistant's reply and any tool result are never stored (either can carry text other people wrote),
so there is no read-through machinery to get wrong. The row is private to the member who spoke and
a repeated send of the same turn is a no-op. Made-up examples only.
"""

from __future__ import annotations

import uuid
from datetime import timedelta

import pytest
from django.utils import timezone

from household.tenancy import TENANT_TABLES
from member_memory import turns
from member_memory.models import ConversationTurn
from tests.conftest import _client_for, _member

URL = "/api/member-memories/turns"


def _turn(chat, seq, **fields):
    return {
        "chat_id": str(chat),
        "seq": seq,
        "surface": "voice",
        "user_text": "what is on my list",
        "assistant_text": "Milk and eggs.",
        "tool_calls": [],
        "spoken_at": timezone.now().isoformat(),
        **fields,
    }


def _send(client, payload):
    return client.post(URL, payload, format="json")


@pytest.fixture
def chat():
    return uuid.uuid4()


# --- the round trip ---------------------------------------------------------------------------


def test_a_turn_is_readable_straight_after_it_is_sent(auth_client, chat):
    sent = _send(
        auth_client,
        _turn(chat, 0, tool_calls=[{"name": "list_lists", "ok": True, "result": "Milk, Eggs"}]),
    )
    assert sent.status_code == 201, sent.data
    got = auth_client.get(URL, {"chat_id": str(chat)})
    assert got.status_code == 200
    (row,) = got.data["results"]
    assert row["user_text"] == "what is on my list"
    assert row["tool_calls"] == [{"name": "list_lists", "ok": True}]


def test_a_text_only_turn_is_readable_too(auth_client, chat):
    _send(auth_client, _turn(chat, 0))
    (row,) = auth_client.get(URL).data["results"]
    assert row["user_text"] == "what is on my list"
    assert row["tool_calls"] == []


def test_sending_the_same_turn_twice_stores_it_once(auth_client, chat):
    first = _send(auth_client, _turn(chat, 3))
    again = _send(auth_client, _turn(chat, 3))
    assert first.status_code == 201 and again.status_code == 200
    assert ConversationTurn.objects.filter(chat_id=chat).count() == 1


def test_the_same_chat_and_seq_with_other_words_is_a_409_and_changes_nothing(auth_client, chat):
    _send(auth_client, _turn(chat, 3))
    again = _send(auth_client, _turn(chat, 3, user_text="something else entirely"))
    assert again.status_code == 409
    assert "Nothing was saved" in again.data["detail"]
    assert ConversationTurn.objects.get(chat_id=chat).user_text == "what is on my list"


def test_turns_read_back_in_the_order_they_were_spoken(auth_client, chat):
    for seq in (2, 0, 1):
        _send(auth_client, _turn(chat, seq, user_text=f"line {seq}"))
    rows = auth_client.get(URL, {"chat_id": str(chat)}).data["results"]
    assert [r["user_text"] for r in rows] == ["line 0", "line 1", "line 2"]


def test_a_seq_past_the_column_is_a_worded_400_not_a_500(auth_client, chat):
    response = _send(auth_client, _turn(chat, 2**31))
    assert response.status_code == 400
    assert "Nothing was saved" in response.data["detail"]
    assert _send(auth_client, _turn(chat, 2**31 - 1)).status_code == 201


def test_a_malformed_since_is_a_worded_400(auth_client):
    response = auth_client.get(URL, {"since": "2026-13-45T00:00:00"})
    assert response.status_code == 400
    assert "Nothing was read" in response.data["detail"]


# --- third-party text is never stored (CLAUDE.md section 7) ----------------------------------


MAIL_TOOLS = ["search_mail", "read_mail", "ask_mail", "get_sitrep", "web_search"]


@pytest.mark.parametrize("tool", MAIL_TOOLS)
def test_a_mail_turn_stores_only_his_words_and_tool_names(auth_client, chat, tool):
    secret = "Subject: Trip on Friday at nine"
    _send(
        auth_client,
        _turn(
            chat,
            0,
            user_text="anything from the school",
            assistant_text=f"The school says: {secret}",
            tool_calls=[{"name": tool, "ok": True, "result": secret, "action": "ask_mail"}],
        ),
    )
    row = ConversationTurn.objects.get(chat_id=chat)
    assert row.user_text == "anything from the school"
    assert row.tool_calls == [{"name": tool, "ok": True}]
    stored = str(
        list(ConversationTurn.objects.values()) + [f.name for f in ConversationTurn._meta.fields]
    )
    assert "Friday" not in stored
    assert "assistant_text" not in stored and "withheld" not in stored


def test_a_later_turn_restating_mail_stores_only_his_words(auth_client, chat):
    _send(auth_client, _turn(chat, 0, tool_calls=[{"name": "read_mail", "ok": True}]))
    later = _turn(chat, 1, user_text="when is that again", assistant_text="Friday at nine")
    _send(auth_client, later)
    assert "Friday" not in str(list(ConversationTurn.objects.values()))


def test_the_row_has_no_column_for_a_reply_or_a_result():
    names = {f.name for f in ConversationTurn._meta.fields}
    assert not names & {"assistant_text", "withheld", "tool_result", "result"}


# --- what is never kept ----------------------------------------------------------------------


def test_an_identity_number_is_never_stored(auth_client, chat):
    _send(
        auth_client,
        _turn(chat, 0, user_text="my account number is 123456789012"),
    )
    row = ConversationTurn.objects.get(chat_id=chat)
    assert row.user_text is None
    assert "123456789012" not in str(row.__dict__)


def test_an_absurd_turn_is_still_refused_in_words(auth_client, chat):
    response = _send(auth_client, _turn(chat, 0, user_text="y" * (turns.TEXT_ACCEPT_CHARS + 1)))
    assert response.status_code == 400
    assert "Nothing was saved" in response.data["detail"]
    assert not ConversationTurn.objects.exists()


def test_a_malformed_turn_is_refused_in_words(auth_client):
    response = _send(auth_client, {"chat_id": "not-a-uuid", "seq": 0})
    assert response.status_code == 400


# --- privacy and tenancy ---------------------------------------------------------------------


def test_a_turn_is_private_to_the_member_who_spoke(auth_client, household_a, chat):
    _send(auth_client, _turn(chat, 0))
    mia = _client_for(_member(household_a, "mia@example.com"))
    assert mia.get(URL).data["results"] == []
    assert mia.get(URL, {"chat_id": str(chat)}).data["results"] == []


def test_another_household_never_sees_a_turn(auth_client, token_b, chat):
    _send(auth_client, _turn(chat, 0))
    assert token_b.get(URL, {"chat_id": str(chat)}).data["results"] == []


def test_the_same_chat_id_in_two_households_is_two_chats(auth_client, token_b, chat):
    assert _send(auth_client, _turn(chat, 0)).status_code == 201
    assert _send(token_b, _turn(chat, 0, user_text="their words")).status_code == 201
    assert ConversationTurn.objects.filter(chat_id=chat).count() == 2


def test_the_table_is_registered_as_a_tenant_table():
    assert "conversation_turns" in TENANT_TABLES


def test_no_response_carries_a_household_id(auth_client, chat):
    _send(auth_client, _turn(chat, 0))
    assert "household" not in str(auth_client.get(URL).data)


def test_a_recruiter_is_refused_by_the_real_guard(db, chat):
    from rest_framework.test import APIClient

    from household.models import DeviceToken, Household, HouseholdMember, User

    visit = Household.objects.create(name="Demo visit turns", is_demo_visit=True)
    visitor = User.objects.create_user(email=f"visitor-{uuid.uuid4().hex[:10]}@demo.invalid")
    visitor.set_unusable_password()
    visitor.save()
    HouseholdMember.objects.create(user=visitor, household=visit, role=HouseholdMember.RECRUITER)
    _token, raw = DeviceToken.issue(
        visitor,
        "Demo visit",
        DeviceToken.SCOPE_READ,
        expires_at=timezone.now() + timedelta(hours=2),
    )
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw}")
    assert _send(client, _turn(chat, 0)).status_code == 403
    assert client.get(URL).status_code == 403
    assert not ConversationTurn.objects.exists()


def test_the_turn_view_is_denied_to_recruiters_by_name():
    from household.recruiter_routes import RECRUITER_DENIED

    assert "member_memory.turn_views.TurnListCreateView" in RECRUITER_DENIED


# --- a 4xx must not lose a turn ----------------------------------------------------------------


def test_overlong_words_are_trimmed_and_marked_not_refused(auth_client, chat):
    response = _send(auth_client, _turn(chat, 0, user_text="word " * 5_000))
    assert response.status_code == 201
    row = ConversationTurn.objects.get(chat_id=chat)
    assert len(row.user_text) <= turns.TEXT_MAX_CHARS
    assert row.trimmed is True


def test_too_many_tool_calls_keep_the_first_ones_and_mark_it(auth_client, chat):
    calls = [{"name": f"tool_{i}", "ok": True} for i in range(turns.MAX_TOOL_CALLS + 5)]
    assert _send(auth_client, _turn(chat, 0, tool_calls=calls)).status_code == 201
    row = ConversationTurn.objects.get(chat_id=chat)
    assert [c["name"] for c in row.tool_calls] == [f"tool_{i}" for i in range(turns.MAX_TOOL_CALLS)]
    assert row.trimmed is True


def test_a_normal_turn_is_not_marked_trimmed(auth_client, chat):
    _send(auth_client, _turn(chat, 0))
    assert ConversationTurn.objects.get(chat_id=chat).trimmed is False


# --- retention for every member -----------------------------------------------------------------


def test_prune_all_clears_members_who_never_save_again(auth_client, household_a, chat):
    _send(auth_client, _turn(chat, 0))
    mia = _client_for(_member(household_a, "mia-old@example.com"))
    _send(mia, _turn(uuid.uuid4(), 0))
    ConversationTurn.objects.update(
        received_at=timezone.now() - timedelta(days=turns.RETENTION_DAYS + 1)
    )
    assert turns.prune_all() == 2
    assert not ConversationTurn.objects.exists()


def test_the_memory_job_prunes_old_turns_even_with_no_key(auth_client, chat, monkeypatch):
    from django.core.management import call_command

    _send(auth_client, _turn(chat, 0))
    ConversationTurn.objects.update(
        received_at=timezone.now() - timedelta(days=turns.RETENTION_DAYS + 1)
    )
    monkeypatch.setattr(
        "member_memory.management.commands.consolidate_memories.gemini_key", lambda: ""
    )
    call_command("consolidate_memories")
    assert not ConversationTurn.objects.exists()


# --- the same (chat_id, seq) from two members ---------------------------------------------------


def test_the_same_chat_and_seq_from_another_member_is_a_409_not_a_500(
    auth_client, household_a, chat
):
    assert _send(auth_client, _turn(chat, 0)).status_code == 201
    mia = _client_for(_member(household_a, "mia-clash@example.com"))
    response = _send(mia, _turn(chat, 0, user_text="their words"))
    assert response.status_code == 409
    assert "Nothing was saved" in response.data["detail"]
    assert ConversationTurn.objects.filter(chat_id=chat).count() == 1
    kept = list(ConversationTurn.objects.values_list("user_text", flat=True))
    assert "their words" not in str(kept)


def test_forgetting_a_member_takes_their_turns(auth_client, household_user, chat):
    _send(auth_client, _turn(chat, 0))
    household_user.delete()
    assert not ConversationTurn.objects.exists()
