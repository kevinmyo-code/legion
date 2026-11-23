"""The Claude hub's thread columns (claude-hub-1123 slice S1).

The hub draws one Claude Code session's messages as a conversation, so a task
has to say which session it is about (`session_id`) and what Kevin actually
approved (`session_text`). Two columns on `claude_tasks`, no new table.

What is tested here is only what a client can observe: the `?session=` read,
what a follow-up inherits, what a finish may and may not overwrite, the
summaries-only machine's posture, tenancy, and that a client knowing nothing
of any of it still works. Nothing asserts a column name through the ORM where
the endpoint can be asked instead.

Time is moved by back-dating rows, never by patching the clock. Every row a
test creates shares the transaction's `now()`, so an ordering test that did
not back-date would pass on the id tie-break alone and prove nothing.

Session ids and names here are made up.
"""

from __future__ import annotations

import datetime

import pytest
from django.utils import timezone

from claude_session.models import SESSION_TEXT_MAX_BYTES, ClaudeTask

pytestmark = pytest.mark.django_db

API = "/api/claude/"
SESSION = "11111111-aaaa-4bbb-8ccc-000000000001"
OTHER_SESSION = "22222222-aaaa-4bbb-8ccc-000000000002"


def _beat(client, name="Alienware", scope="full", state="idle"):
    body = {"name": name, "hostname": f"{name}-host", "scope": scope, "state": state,
            "running": [], "claude_version": "2.1.316"}
    response = client.post(f"{API}machines/heartbeat/", body, format="json")
    assert response.status_code == 200, response.data
    return response.data


def _create(client, request="say hello to the build session", **extra):
    body = {"request": request, "machine": None, "parent": None, "origin": "named", **extra}
    response = client.post(f"{API}tasks/", body, format="json")
    assert response.status_code == 201, response.data
    return response.data["id"]


def _claim(client, name="Alienware"):
    return client.post(f"{API}tasks/claim/", {"name": name}, format="json")


def _finish(client, task_id, **body):
    body.setdefault("status", "done")
    return client.post(f"{API}tasks/{task_id}/finish/", body, format="json")


def _thread(client, session=SESSION, **params):
    query = "&".join([f"session={session}", *[f"{k}={v}" for k, v in params.items()]])
    response = client.get(f"{API}tasks/?{query}")
    assert response.status_code == 200, response.data
    return response.data


def _age(task_id, seconds):
    """Back-date a task so creation order and id order are different questions."""
    ClaudeTask.objects.filter(pk=task_id).update(
        created_at=timezone.now() - datetime.timedelta(seconds=seconds)
    )


# --- the thread read ------------------------------------------------------------


def test_thread_read_is_oldest_first_and_only_that_session(token_a):
    """`?session=` answers one session's tasks, oldest first.

    The three rows are back-dated so the wanted order is the REVERSE of their
    ids: an answer that merely came out in insertion order fails here.
    """
    _beat(token_a)
    third = _create(token_a, "and third", session_id=SESSION)
    first = _create(token_a, "first", session_id=SESSION)
    second = _create(token_a, "second", session_id=SESSION)
    elsewhere = _create(token_a, "another session's", session_id=OTHER_SESSION)
    plain = _create(token_a, "an ordinary task")
    _age(first, 300)
    _age(second, 200)
    _age(third, 100)

    rows = _thread(token_a)

    assert [r["id"] for r in rows] == [first, second, third]
    assert elsewhere not in [r["id"] for r in rows]
    assert plain not in [r["id"] for r in rows]
    assert {r["session_id"] for r in rows} == {SESSION}


def test_thread_limit_keeps_the_newest_end(token_a):
    """A thread longer than `limit` keeps its RECENT end, still oldest first.

    This is the half that an oldest-first SQL order with a LIMIT gets wrong:
    it would answer the three OLDEST and freeze the thread for good.
    """
    _beat(token_a)
    ids = [_create(token_a, f"message {n}", session_id=SESSION) for n in range(5)]
    for position, task_id in enumerate(ids):
        _age(task_id, 500 - position * 10)

    rows = _thread(token_a, limit=3)

    assert [r["id"] for r in rows] == ids[2:]


def test_blank_session_filters_nothing(token_a):
    """`?session=` with no value is no filter, not a thread of every plain task.

    Every ordinary task carries a blank `session_id`, so a filter that took
    the blank literally would answer with unrelated work.
    """
    _beat(token_a)
    plain = _create(token_a, "an ordinary task")
    threaded = _create(token_a, "a message", session_id=SESSION)

    rows = token_a.get(f"{API}tasks/?session=").data

    assert {r["id"] for r in rows} == {plain, threaded}


def test_another_households_session_id_is_not_visible(token_a, token_b):
    """The same session id in another household answers nothing here."""
    _beat(token_b)
    theirs = _create(token_b, "their message", session_id=SESSION)
    _beat(token_a)
    mine = _create(token_a, "my message", session_id=SESSION)

    assert [r["id"] for r in _thread(token_a)] == [mine]
    assert [r["id"] for r in _thread(token_b)] == [theirs]


# --- what a follow-up inherits --------------------------------------------------


def test_follow_up_with_no_session_inherits_its_parents(token_a):
    """Kevin's yes joins the thread of the message it answers.

    The phone taps Yes on a card; it does not re-send the session id. Without
    the inheritance the yes falls out of the thread entirely.
    """
    _beat(token_a)
    card = _create(token_a, "message card", session_id=SESSION)
    _claim(token_a)
    assert _finish(token_a, card, status="needs_confirmation",
                   question="Send this message to your Claude session build?").status_code == 200

    yes = _create(token_a, "yes", parent=card, confirms=True)

    rows = _thread(token_a)
    assert [r["id"] for r in rows] == [card, yes]
    assert next(r for r in rows if r["id"] == yes)["session_id"] == SESSION


def test_follow_up_cannot_re_address_itself_to_another_session(token_a):
    """A client-sent session id loses to the parent's (ruling 10's reach)."""
    _beat(token_a)
    card = _create(token_a, "message card", session_id=SESSION)
    _claim(token_a)
    _finish(token_a, card, status="needs_confirmation", question="Send it?")

    yes = _create(token_a, "yes", parent=card, confirms=True, session_id=OTHER_SESSION)

    assert [r["id"] for r in _thread(token_a)] == [card, yes]
    assert _thread(token_a, session=OTHER_SESSION) == []


def test_follow_up_of_a_plain_task_may_name_a_session(token_a):
    """A parent with no thread does not veto a client's own session id."""
    _beat(token_a)
    parent = _create(token_a, "an ordinary task")
    _claim(token_a)
    _finish(token_a, parent, status="needs_confirmation", question="Carry on?")

    child = _create(token_a, "yes", parent=parent, confirms=True, session_id=SESSION)

    assert [r["id"] for r in _thread(token_a)] == [child]


def test_a_follow_up_never_inherits_the_message_text(token_a):
    """`session_text` is the one approved message, so it belongs to one task."""
    _beat(token_a)
    card = _create(token_a, "message card", session_id=SESSION)
    _claim(token_a)
    _finish(token_a, card, status="needs_confirmation", question="Send it?",
            session_text="check the build")

    yes = _create(token_a, "yes", parent=card, confirms=True)

    rows = {r["id"]: r for r in _thread(token_a)}
    assert rows[card]["session_text"] == "check the build"
    assert rows[yes]["session_text"] is None


# --- what a finish may write ----------------------------------------------------


def test_finish_names_the_session_for_a_card_the_phone_never_addressed(token_a):
    """Alfred drafts, the worker names the target: the card joins a thread."""
    _beat(token_a)
    card = _create(token_a, "alfred drafted this")
    _claim(token_a)

    assert _finish(token_a, card, status="needs_confirmation", question="Send it?",
                   session_id=SESSION, session_text="check the build").status_code == 200

    rows = _thread(token_a)
    assert [r["id"] for r in rows] == [card]
    assert rows[0]["session_text"] == "check the build"


def test_a_finish_that_sends_neither_field_keeps_what_create_set(token_a):
    """An older worker finishing a task must not blank its thread.

    This is the regression the two fields' `""`/`None` defaults invite: write
    them unconditionally and every finish by a worker that predates slice S1
    silently drops the card out of its thread.
    """
    _beat(token_a)
    card = _create(token_a, "message card", session_id=SESSION)
    _claim(token_a)
    _finish(token_a, card, status="needs_confirmation", question="Send it?",
            session_text="check the build")
    _claim(token_a)
    follow = _create(token_a, "yes", parent=card, confirms=True)
    _claim(token_a)

    assert _finish(token_a, follow, status="done", summary="it is in its inbox").status_code == 200

    rows = {r["id"]: r for r in _thread(token_a)}
    assert set(rows) == {card, follow}
    assert rows[card]["session_text"] == "check the build"


def test_a_summaries_only_machine_never_stores_the_message(token_a):
    """`listed` scope keeps a session's words off the engine (ruling 5).

    The worker arms no session send on the work laptop, so this is the mirror
    of `SessionReportView` dropping `last_prompt_summary` and `name` there.
    """
    _beat(token_a, name="work laptop", scope="listed")
    card = _create(token_a, "message card", machine="work laptop", session_id=SESSION)
    _claim(token_a, name="work laptop")

    assert _finish(token_a, card, status="needs_confirmation", question="Send it?",
                   session_text="check the build").status_code == 200

    rows = _thread(token_a)
    assert [r["id"] for r in rows] == [card]
    assert rows[0]["session_text"] is None


def test_an_over_long_message_is_refused_and_never_echoed(token_a):
    """Over the cap the finish is refused, and the refusal does not quote it."""
    _beat(token_a)
    card = _create(token_a, "message card", session_id=SESSION)
    _claim(token_a)
    over_cap = "s" * (SESSION_TEXT_MAX_BYTES + 1)

    response = _finish(token_a, card, status="needs_confirmation", question="Send it?",
                       session_text=over_cap)

    assert response.status_code == 400, response.data
    assert "s" * 50 not in str(response.data)


def test_a_two_thousand_character_non_ascii_message_fits(token_a):
    """The cap is the worker's own 2 000 CHARACTERS measured in bytes.

    The character is deliberately one that costs FOUR bytes in UTF-8, which
    is the worst case and so the only one that pins the cap: 2 000 of them
    are 8 000 bytes. An earlier draft used a two-byte character, which fits
    in 4 KB and so would have passed with the cap set too low, refusing the
    whole finish on a real message and hanging the task to the 30 minute cap.
    """
    _beat(token_a)
    card = _create(token_a, "message card", session_id=SESSION)
    _claim(token_a)
    long_message = "\U00024b62" * 2000
    assert len(long_message.encode("utf-8")) == 8000

    response = _finish(token_a, card, status="needs_confirmation", question="Send it?",
                       session_text=long_message)

    assert response.status_code == 200, response.data
    assert _thread(token_a)[0]["session_text"] == long_message


# --- a client that knows nothing of threads -------------------------------------


def test_a_create_with_no_session_fields_still_works(token_a):
    """The installed phone and head unit predate all of this."""
    _beat(token_a)
    task_id = _create(token_a, "find the wiring diagram in Downloads")

    row = next(r for r in token_a.get(f"{API}tasks/").data if r["id"] == task_id)

    assert row["session_id"] == ""
    assert row["session_text"] is None


def test_the_message_is_never_in_the_admin_form(token_a):
    """Admin's `LogEntry.object_repr` is why `__str__` and the forms stay bare."""
    from claude_session.admin import ClaudeTaskAdmin

    _beat(token_a)
    card = _create(token_a, "message card", session_id=SESSION)
    _claim(token_a)
    _finish(token_a, card, status="needs_confirmation", question="Send it?",
            session_text="check the build")

    task = ClaudeTask.objects.get(pk=card)
    assert "check the build" not in str(task)
    assert "session_text" in ClaudeTaskAdmin.exclude


# --- schema (no user-visible behaviour of its own) ------------------------------


def test_the_thread_index_is_on_the_table(db):
    """The thread's read index exists in the database, not only in the model.

    A schema test, not a behaviour one: `?session=` answers correctly with no
    index at all. It is here so a migration that drops it is caught.
    """
    from django.db import connection

    with connection.cursor() as cursor:
        cursor.execute(
            "select indexdef from pg_indexes where tablename = 'claude_tasks' "
            "and indexname = 'claude_tasks_session'"
        )
        found = cursor.fetchone()

    assert found is not None, "claude_tasks_session is missing"
    assert "session_id" in found[0] and "created_at" in found[0]


def test_an_old_insert_that_names_neither_column_still_works(db, household_a):
    """An engine revision still serving mid-rollout inserts rows without them.

    `default=""` lives in Python only, so without `db_default` this INSERT
    fails on NOT NULL and every write by the outgoing revision 500s during a
    deploy. The raw SQL is the point: the ORM would supply the default.
    """
    from django.db import connection

    with connection.cursor() as cursor:
        cursor.execute(
            "insert into claude_tasks (household_id, origin, kind, confirms, request, status, "
            "steps, created_at) values (%s, 'named', 'ask', false, 'x', 'queued', '[]', now()) "
            "returning session_id, session_text",
            [str(household_a.id)],
        )
        session_id, session_text = cursor.fetchone()

    assert session_id == ""
    assert session_text is None


def test_no_migration_is_missing(db):
    """The two columns and the index are all in a migration file."""
    from io import StringIO

    from django.core.management import call_command

    call_command("makemigrations", "claude_session", check=True, dry_run=True, stdout=StringIO())
