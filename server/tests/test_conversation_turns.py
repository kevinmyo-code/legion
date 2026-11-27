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


def test_an_overlong_reply_is_trimmed_and_marked_not_refused(auth_client, chat):
    long_reply = "word " * 5_000
    response = _send(auth_client, _turn(chat, 0, assistant_text=long_reply))
    assert response.status_code == 201
    row = ConversationTurn.objects.get(chat_id=chat)
    assert len(row.assistant_text) <= turns.TEXT_MAX_CHARS
    assert row.trimmed is True
    assert row.user_text == "what is on my list"


def test_too_many_tool_calls_keep_the_first_ones_and_mark_it(auth_client, chat):
    calls = [{"name": f"tool_{i}", "ok": True} for i in range(turns.MAX_TOOL_CALLS + 5)]
    assert _send(auth_client, _turn(chat, 0, tool_calls=calls)).status_code == 201
    row = ConversationTurn.objects.get(chat_id=chat)
    assert [c["name"] for c in row.tool_calls] == [f"tool_{i}" for i in range(turns.MAX_TOOL_CALLS)]
    assert row.trimmed is True


def test_a_read_through_call_past_the_cap_still_withholds_the_turn(auth_client, chat):
    calls = [{"name": f"tool_{i}", "ok": True} for i in range(turns.MAX_TOOL_CALLS)]
    calls.append({"name": "search_mail", "ok": True})
    _send(auth_client, _turn(chat, 0, tool_calls=calls))
    assert ConversationTurn.objects.get(chat_id=chat).withheld is True
    _send(auth_client, _turn(chat, 1, assistant_text="They also wrote about Saturday."))
    assert ConversationTurn.objects.get(chat_id=chat, seq=1).assistant_text is None


def test_a_normal_turn_is_not_marked_trimmed(auth_client, chat):
    _send(auth_client, _turn(chat, 0))
    assert ConversationTurn.objects.get(chat_id=chat).trimmed is False


# --- the engine's own read-through check -------------------------------------------------------


@pytest.mark.parametrize("action", ["ask_mail", "get_sitrep", "search_mail"])
def test_a_folded_mail_call_is_withheld_by_the_engine_itself(auth_client, chat, action):
    # The phone sends the wire name (`look_up`, `day`) and the tool it resolved to; no phone flag.
    calls = [{"name": "look_up", "ok": True, "action": action, "result": "From the school"}]
    _send(auth_client, _turn(chat, 0, tool_calls=calls, read_through=False))
    row = ConversationTurn.objects.get(chat_id=chat)
    assert row.withheld is True
    assert row.assistant_text is None
    assert row.tool_calls[0]["result"] is None


def test_a_folded_call_that_reads_nothing_third_party_is_kept(auth_client, chat):
    calls = [{"name": "look_up", "ok": True, "action": "list_events", "result": "Two events"}]
    _send(auth_client, _turn(chat, 0, tool_calls=calls))
    assert ConversationTurn.objects.get(chat_id=chat).withheld is False


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
