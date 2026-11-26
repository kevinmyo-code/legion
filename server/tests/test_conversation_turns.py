"""Each finished turn is saved as it happens (memory chain ticket 450, CLAUDE.md sections 4 and 7).

One row is one TURN: the person's words, the assistant's spoken reply and the tools it ran. The
phone sends it the moment the turn completes, so a chat that closed a minute ago is readable
before the hourly consolidation job has looked at it. Read-through content (mail, the web) is
withheld by the engine whatever the phone says, the row is private to the member who spoke, and a
repeated send of the same turn is a no-op. Made-up examples only.
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
MARK = "[redacted: third-party content]"


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
        _turn(
            chat,
            0,
            tool_calls=[{"name": "list_checklists", "ok": True, "result": "2 lists: Milk, Eggs"}],
        ),
    )
    assert sent.status_code == 201, sent.data
    assert sent.data["withheld"] is False

    got = auth_client.get(URL, {"chat_id": str(chat)})
    assert got.status_code == 200
    (row,) = got.data["results"]
    assert row["user_text"] == "what is on my list"
    assert row["assistant_text"] == "Milk and eggs."
    assert row["tool_calls"] == [
        {"name": "list_checklists", "ok": True, "result": "2 lists: Milk, Eggs"}
    ]


def test_a_tool_turn_keeps_the_spoken_words(auth_client, chat):
    # The consolidation job drops the assistant line of any turn that ran a tool; this does not.
    _send(auth_client, _turn(chat, 0, tool_calls=[{"name": "start_timer", "ok": True}]))
    (row,) = auth_client.get(URL).data["results"]
    assert row["assistant_text"] == "Milk and eggs."


def test_sending_the_same_turn_twice_stores_it_once(auth_client, chat):
    first = _send(auth_client, _turn(chat, 3))
    again = _send(auth_client, _turn(chat, 3, assistant_text="A different reply."))
    assert first.status_code == 201 and again.status_code == 200
    assert ConversationTurn.objects.filter(chat_id=chat).count() == 1
    assert ConversationTurn.objects.get(chat_id=chat).assistant_text == "Milk and eggs."


def test_turns_read_back_in_the_order_they_were_spoken(auth_client, chat):
    for seq in (2, 0, 1):
        _send(auth_client, _turn(chat, seq, user_text=f"line {seq}"))
    rows = auth_client.get(URL, {"chat_id": str(chat)}).data["results"]
    assert [r["user_text"] for r in rows] == ["line 0", "line 1", "line 2"]


# --- read-through (CLAUDE.md section 7) ------------------------------------------------------


@pytest.mark.parametrize("tool", sorted(turns.READ_THROUGH_TOOLS))
def test_a_read_through_tool_turn_keeps_his_words_and_nothing_else(auth_client, chat, tool):
    sent = _send(
        auth_client,
        _turn(
            chat,
            0,
            user_text="anything from the school",
            assistant_text="The email from the school says the trip is on Friday.",
            tool_calls=[{"name": tool, "ok": True, "result": "Subject: Trip on Friday"}],
        ),
    )
    assert sent.data["withheld"] is True
    row = ConversationTurn.objects.get(chat_id=chat)
    assert row.user_text == "anything from the school"
    assert row.assistant_text is None
    assert row.tool_calls == [{"name": tool, "ok": True, "result": None}]


def test_a_redaction_mark_from_the_phone_withholds_the_turn(auth_client, chat):
    # The phone's own LiveAudit already redacted it; the engine does not trust that alone, but it
    # does honour the mark.
    _send(auth_client, _turn(chat, 0, assistant_text=MARK))
    row = ConversationTurn.objects.get(chat_id=chat)
    assert row.withheld is True and row.assistant_text is None


def test_the_phone_can_flag_a_turn_read_through_without_naming_a_tool(auth_client, chat):
    _send(auth_client, _turn(chat, 0, read_through=True))
    row = ConversationTurn.objects.get(chat_id=chat)
    assert row.withheld is True and row.assistant_text is None


def test_a_read_through_turn_taints_the_rest_of_its_chat(auth_client, chat):
    # An async mail result can be spoken turns after the call (the consolidation job's rule too).
    _send(auth_client, _turn(chat, 0, tool_calls=[{"name": "search_mail", "ok": True}]))
    _send(auth_client, _turn(chat, 1, assistant_text="They also wrote about Saturday."))
    later = ConversationTurn.objects.get(chat_id=chat, seq=1)
    assert later.withheld is True and later.assistant_text is None
    assert later.user_text == "what is on my list"


def test_another_chat_is_not_tainted(auth_client, chat):
    _send(auth_client, _turn(chat, 0, tool_calls=[{"name": "read_mail", "ok": True}]))
    other = uuid.uuid4()
    _send(auth_client, _turn(other, 0))
    assert ConversationTurn.objects.get(chat_id=other).withheld is False


def test_a_web_search_turn_is_withheld_like_the_consolidation_job_does(auth_client, chat):
    _send(auth_client, _turn(chat, 0, tool_calls=[{"name": "web_search", "ok": True}]))
    assert ConversationTurn.objects.get(chat_id=chat).withheld is True


# --- what is never kept ----------------------------------------------------------------------


def test_an_identity_number_is_never_stored(auth_client, chat):
    _send(
        auth_client,
        _turn(chat, 0, user_text="my account number is 123456789012", assistant_text="Noted."),
    )
    row = ConversationTurn.objects.get(chat_id=chat)
    assert row.user_text is None
    assert "123456789012" not in str(row.__dict__)


def test_a_tool_result_is_clipped_to_a_line(auth_client, chat):
    _send(
        auth_client,
        _turn(chat, 0, tool_calls=[{"name": "list_events", "ok": True, "result": "x" * 5000}]),
    )
    (call,) = ConversationTurn.objects.get(chat_id=chat).tool_calls
    assert len(call["result"]) <= turns.RESULT_MAX_CHARS


def test_an_oversized_turn_is_refused_in_words(auth_client, chat):
    response = _send(auth_client, _turn(chat, 0, user_text="y" * (turns.TEXT_MAX_CHARS + 1)))
    assert response.status_code == 400
    assert "Nothing was saved" in response.data["detail"]
    assert not ConversationTurn.objects.exists()


def test_a_malformed_turn_is_refused_in_words(auth_client):
    response = _send(auth_client, {"chat_id": "not-a-uuid", "seq": 0})
    assert response.status_code == 400


def test_turns_past_the_retention_window_go_on_the_next_save(auth_client, chat):
    _send(auth_client, _turn(chat, 0))
    ConversationTurn.objects.update(
        received_at=timezone.now() - timedelta(days=turns.RETENTION_DAYS + 1)
    )
    _send(auth_client, _turn(chat, 1))
    assert list(ConversationTurn.objects.values_list("seq", flat=True)) == [1]


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


def test_a_recruiter_is_refused(auth_client, chat, monkeypatch):
    from member_memory import service

    monkeypatch.setattr(service, "refusal", lambda request: "Nothing was saved. Demo visit.")
    response = _send(auth_client, _turn(chat, 0))
    assert response.status_code == 403
    assert not ConversationTurn.objects.exists()


def test_forgetting_a_member_takes_their_turns(auth_client, household_user, chat):
    _send(auth_client, _turn(chat, 0))
    household_user.delete()
    assert not ConversationTurn.objects.exists()
